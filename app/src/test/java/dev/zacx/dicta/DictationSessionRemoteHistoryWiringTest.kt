package dev.zacx.dicta

import android.content.Context
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

/**
 * 🔴 The seam [DictationSessionHistoryCaptureTest] measured and declined to
 * claim: the `updateText` calls wired into `DictationSession`'s
 * [RemoteTranscriber.Events] handlers.
 *
 * ## Why this file exists at all
 *
 * The capture hook files a history item with the **on-device** transcript,
 * because that is the only text that exists when the recording finishes. What
 * the user then reads on screen is the **server's** transcript, and
 * `updateText` is the single line that makes history agree with it. Both sides
 * of that line were already green in isolation — the store's `updateText` is
 * pinned by `DictationHistoryStoreTest`, and the ladder that produces the
 * remote transcript by `RemoteTranscriberTest` — so the defect had nowhere to
 * live except the join, where no fixture built the combined state. Its symptom
 * is also the quietest one available: history keeps the worse transcript
 * forever, with no error, no log and no visible failure.
 *
 * The seam became reachable by making `RemoteTranscriber` injectable into
 * `DictationSession` (`transcriberFactory`, whose default is the construction
 * it replaced argument for argument). Before that, `onRemoteTranscript` needed
 * a real server.
 *
 * ## Why the two transcripts are different strings
 *
 * [theRemoteTranscriptReplacesTheLocalOneInHistory] is only a test because
 * [LOCAL] and [REMOTE] differ. If the server's text could equal the text the
 * capture hook filed, the assertion would pass whether `updateText` ran or not
 * — the vacuous shape. [theTwoTranscriptsUsedByThisFileAreDifferent] pins that
 * precondition so a future edit cannot quietly collapse them.
 *
 * ## What the fall-back case can and cannot establish
 *
 * [aLateLocalTranscriptIsRefiledOnFallback] drives the other handler. It
 * **can** establish that `onFellBack`'s `updateText` call is wired and that its
 * `fromRemote = false` reaches the store — and it is discriminating only
 * because the fake transport moves the session's local transcript *after* the
 * capture hook filed the earlier value, which is the real ordering
 * `RemoteTranscriber`'s lazily-read `localTranscript` exists for.
 *
 * It **cannot** establish that the fall-back path is right in production, for
 * two reasons worth stating rather than implying. It drives the 401 branch
 * only, so nothing here is evidence about the retry/recovery branches (those
 * are `RemoteTranscriberTest`'s). And the late-transcript move is performed by
 * this test, not by a recogniser: that a real `onFinal` can land in that window
 * is a claim about `SpeechHelper`'s timing which this file does not measure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictationSessionRemoteHistoryWiringTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    private class Silent : DictationSession.Callbacks {
        override fun onTranscript(text: String, fromRemote: Boolean, isFinal: Boolean) = Unit
        override fun onStatus(text: String) = Unit
        override fun onLevel(level: Double) = Unit
        override fun onTier(tier: AudioCaptureLogic.Tier) = Unit
        override fun onClientSilenced(silenced: Boolean) = Unit
        override fun onRecordingStopped() = Unit
    }

    /** History rooted in a temp dir, with a fixed clock. */
    private fun store(): DictationHistoryStore =
        DictationHistoryStore(temp.newFolder("history"), { 1_700_000_000_000L })

    /**
     * Settings whose token decrypts, so the ladder gets past
     * `isRemoteConfigured`. Constructed directly rather than via
     * `SttSettings.create`, which needs an `AndroidKeyStore` Robolectric does
     * not have — the same seam `RemoteTranscriberTest` uses.
     */
    private fun configuredSettings(): SttSettings {
        val prefs = ctx.getSharedPreferences(
            "remote-history-wiring-${counter++}", Context.MODE_PRIVATE
        )
        prefs.edit().clear().apply()
        val cipher = object : SecretCipher {
            override fun encrypt(plaintext: String): String = plaintext
            override fun decrypt(blob: String): String? = blob
        }
        val tokens = TokenStore(prefs, cipher)
        tokens.putToken("a-bearer-token")
        return SttSettings(prefs, tokens)
    }

    private var counter = 0

    /**
     * A `RemoteTranscriber` with a scripted transport and no sleeping — the
     * real ladder, so what drives the `Events` handlers under test is the
     * production code path and not a hand-rolled stand-in for it.
     *
     * @param onUpload runs just before the outcome is returned, i.e. while the
     *   worker thread is inside `transcribe`. That is the window a late local
     *   transcript lands in.
     */
    private fun fakeTranscriber(
        outcome: SttOutcome,
        onUpload: () -> Unit = {},
        attempts: MutableList<TranscriptionCoordinator.UploadAttempt> = mutableListOf(),
    ): (Context, SttSettings, Locale) -> RemoteTranscriber = { _, _, _ ->
        RemoteTranscriber(
            ctx,
            configuredSettings(),
            locale = Locale("en", "US"),
            transportFactory = { _, _ ->
                RemoteTranscriber.Transport(
                    upload = { attempt ->
                        attempts += attempt
                        onUpload()
                        outcome
                    },
                    health = { true },
                )
            },
            sleeper = { },
            unitRandom = { 0.5 },
            elapsedMs = { 0L },
        )
    }

    private fun session(
        history: DictationHistoryStore,
        transcribers: (Context, SttSettings, Locale) -> RemoteTranscriber,
    ) = DictationSession(
        ctx,
        configuredSettings(),
        Silent(),
        history = history,
        transcriberFactory = transcribers,
    )

    private fun recordingFile(): File =
        File(temp.newFolder("src-${counter++}"), "dictation-9.ogg")
            .apply { writeBytes(ByteArray(4096)) }

    /**
     * Calls the session's own `MicCapture.Listener.onFinished` — the real entry
     * point — by reflection, because the listener is a private inner class and
     * `MicCapture` cannot be started here. Same mechanism as
     * [DictationSessionHistoryCaptureTest].
     */
    private fun finishRecording(session: DictationSession, file: File) {
        val listenerClass = Class.forName("dev.zacx.dicta.DictationSession\$MicListener")
        val ctor = listenerClass.getDeclaredConstructor(DictationSession::class.java)
            .apply { isAccessible = true }
        (ctor.newInstance(session) as MicCapture.Listener).onFinished(file, 3.0)
    }

    /**
     * Writes the session's on-device transcript, which is what the capture hook
     * files and what the fall-back re-files. Private, and set only from the
     * recogniser callbacks in production, so reflection is the only way to put
     * a known value there.
     */
    private fun setLocalTranscript(session: DictationSession, text: String) {
        DictationSession::class.java.getDeclaredField("localTranscript")
            .apply { isAccessible = true }
            .set(session, text)
    }

    /** Lets the worker thread finish whatever it started and drains the looper. */
    private fun settle() {
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
    }

    // --- the preconditions --------------------------------------------------

    @Test
    fun theTwoTranscriptsUsedByThisFileAreDifferent() {
        assertNotEquals(
            "the local and remote transcripts this file uses are the same " +
                "string, so the assertion in " +
                "theRemoteTranscriptReplacesTheLocalOneInHistory would pass " +
                "whether updateText ran or not",
            LOCAL,
            REMOTE,
        )
    }

    // --- the seam -----------------------------------------------------------

    @Test
    fun theRemoteTranscriptReplacesTheLocalOneInHistory() {
        val history = store()
        val attempts = mutableListOf<TranscriptionCoordinator.UploadAttempt>()
        val session = session(
            history,
            fakeTranscriber(SttOutcome.Success(REMOTE), attempts = attempts),
        )
        setLocalTranscript(session, LOCAL)

        finishRecording(session, recordingFile())
        settle()

        // Positive control FIRST: a store holding nothing, or a ladder that
        // never uploaded, would make every assertion below vacuous in a way
        // that reads exactly like a passing test.
        assertEquals(
            "the upload never happened, so nothing below is evidence about the " +
                "updateText wiring",
            1,
            attempts.size,
        )
        val item = history.recent(10).singleOrNull()
        assertTrue(
            "the capture hook filed no history item, so there was nothing for " +
                "updateText to replace: ${history.recent(10)}",
            item != null,
        )

        assertEquals(
            "history still holds the ON-DEVICE transcript after the server's " +
                "arrived. updateText is the only thing that makes history " +
                "agree with the text the user actually read, and when it does " +
                "not run the entry keeps the worse transcript forever with no " +
                "error of any kind.",
            REMOTE,
            item!!.text,
        )
        assertTrue(
            "the stored item is still marked as coming from the phone even " +
                "though the server's transcript replaced its text, so history " +
                "will attribute the server's words to the on-device " +
                "recognizer — the two engines disagree, which is why that " +
                "flag exists at all",
            item.fromRemote,
        )
        session.destroy()
    }

    @Test
    fun aLateLocalTranscriptIsRefiledOnFallback() {
        val history = store()
        val attempts = mutableListOf<TranscriptionCoordinator.UploadAttempt>()
        lateinit var session: DictationSession
        session = session(
            history,
            fakeTranscriber(
                // A 401 cannot recover, so this is exactly one upload and one
                // terminal onFellBack.
                SttOutcome.Http(401, "invalid or missing bearer token"),
                // The recogniser's final result landing after the ladder
                // started — the ordering RemoteTranscriber reads
                // localTranscript lazily for.
                onUpload = { setLocalTranscript(session, LATE_LOCAL) },
                attempts = attempts,
            ),
        )
        setLocalTranscript(session, LOCAL)

        finishRecording(session, recordingFile())
        settle()

        assertEquals(
            "the upload never happened, so the fall-back handler was never " +
                "reached and nothing below is evidence about its wiring",
            1,
            attempts.size,
        )
        val item = history.recent(10).singleOrNull()
        assertTrue(
            "the capture hook filed no history item: ${history.recent(10)}",
            item != null,
        )
        assertEquals(
            "the fall-back handler did not re-file the local transcript that " +
                "was actually shown. The capture hook filed \"$LOCAL\", the " +
                "recognizer then produced \"$LATE_LOCAL\", and that later text " +
                "is the one on screen — history showing the earlier guess is " +
                "the same silent divergence as the remote case.",
            LATE_LOCAL,
            item!!.text,
        )
        assertEquals(
            "a fall-back entry is marked as coming from the server",
            false,
            item.fromRemote,
        )
        session.destroy()
    }

    private companion object {
        /** What the phone heard, and what the capture hook therefore files. */
        const val LOCAL = "the phone's first guess"

        /**
         * What the server heard — deliberately NOT equal to [LOCAL]; see the
         * file kdoc.
         */
        const val REMOTE = "the server's transcript, which differs"

        /** A recogniser final that lands after the ladder has started. */
        const val LATE_LOCAL = "the phone's late final result"
    }
}
