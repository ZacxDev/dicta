package dev.zacx.dicta

import android.content.Context
import android.os.Looper
import org.junit.Assert.assertEquals
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

/**
 * 🔴 The **third** half of the cancel button.
 *
 * [BubbleDictationTest] proves the state machine decides not to upload.
 * [DictationSessionUploadGateTest] proves the session then does not upload. Both
 * are worth nothing if the session *files the recording into a browsable
 * history* instead — the user pressed cancel, and "nothing was uploaded" and
 * "nothing was kept" are the same promise to them. The comment on
 * `suppressUpload()` says the first out loud; this test is what makes the
 * second true rather than incidental.
 *
 * So the capture hook sits BELOW both gates in `onFinished`, and that placement
 * is pinned behaviourally here — by counting what is on disk afterwards, not by
 * reading the order of the lines.
 *
 * [aFinishedRecordingIsFiledInHistory] is the positive control: a zero from a
 * harness that never reaches the hook at all is indistinguishable from a zero
 * caused by the suppression under test, so the pair is what carries the claim.
 *
 * ⚠ **Still not covered HERE, but no longer uncovered.** The `updateText` calls
 * wired into the `RemoteTranscriber.Events` handlers are not exercised by this
 * file: the sessions built here have no token, so nothing reaches
 * `onRemoteTranscript`. That gap used to be unclosable, because
 * `RemoteTranscriber` was constructed inside `transcribeRemotely` rather than
 * injected. It now is injected (`transcriberFactory`), and
 * [DictationSessionRemoteHistoryWiringTest] drives both handlers with a
 * scripted transport and asserts the STORE's resulting state. The store side
 * alone remains pinned by
 * `DictationHistoryStoreTest.updateTextReplacesTheStoredTranscript`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictationSessionHistoryCaptureTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class Silent : DictationSession.Callbacks {
        override fun onTranscript(text: String, fromRemote: Boolean, isFinal: Boolean) = Unit
        override fun onStatus(text: String) = Unit
        override fun onLevel(level: Double) = Unit
        override fun onTier(tier: AudioCaptureLogic.Tier) = Unit
        override fun onClientSilenced(silenced: Boolean) = Unit
        override fun onRecordingStopped() = Unit
    }

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    /** History rooted in a temp dir, with a fixed clock. */
    private fun store(): DictationHistoryStore =
        DictationHistoryStore(temp.newFolder("history"), { 1_700_000_000_000L })

    /** As in [DictationSessionUploadGateTest]: no usable token, no socket. */
    private fun settings(): SttSettings {
        val prefs = ctx.getSharedPreferences("history-capture-test", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        val cipher = object : SecretCipher {
            override fun encrypt(plaintext: String): String = plaintext
            override fun decrypt(blob: String): String? = null
        }
        return SttSettings(prefs, TokenStore(prefs, cipher))
    }

    private fun session(history: DictationHistoryStore) =
        DictationSession(ctx, settings(), Silent(), history = history)

    private fun recordingFile(): File =
        File(temp.newFolder("src-${counter++}"), "dictation-9.ogg")
            .apply { writeBytes(ByteArray(4096)) }

    private var counter = 0

    /**
     * Calls the session's own `MicCapture.Listener.onFinished` — the real entry
     * point — by reflection, because the listener is a private inner class and
     * `MicCapture` cannot be started here. Same mechanism as
     * [DictationSessionUploadGateTest].
     */
    private fun finishRecording(session: DictationSession, file: File) {
        val listenerClass = Class.forName("dev.zacx.dicta.DictationSession\$MicListener")
        val ctor = listenerClass.getDeclaredConstructor(DictationSession::class.java)
            .apply { isAccessible = true }
        (ctor.newInstance(session) as MicCapture.Listener).onFinished(file, 3.0)
    }

    /** Lets the worker thread finish whatever it started and drains the looper. */
    private fun settle() {
        Thread.sleep(100)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun aFinishedRecordingIsFiledInHistory() {
        // 🔴 Positive control. Without it, the empty store in the suppression
        // test below could just as easily mean this harness never reaches the
        // capture hook at all.
        val history = store()
        val session = session(history)
        val source = recordingFile()

        finishRecording(session, source)
        settle()

        val items = history.recent(10)
        assertEquals(
            "a normally finished recording was NOT filed in history, so this " +
                "harness cannot observe the capture hook and the suppression " +
                "test below proves nothing. Items: $items",
            1,
            items.size,
        )
        assertEquals(4096L, items.single().audioBytes)
        assertTrue(
            "the capture hook MOVED the recording instead of copying it — " +
                "DictationSession still owns that file and deletes it in " +
                "destroy()",
            source.isFile,
        )
        session.destroy()
    }

    @Test
    fun aSuppressedRecordingFilesNothingInHistory() {
        val history = store()
        val session = session(history)
        session.suppressUpload()

        finishRecording(session, recordingFile())
        settle()

        assertEquals(
            "a cancelled recording was filed into history. uploadSuppressed " +
                "IS the cancel button: a cancel that still leaves the audio " +
                "sitting in a browsable list is not a cancel, and 'nothing " +
                "was kept' is the same promise to the user as 'nothing was " +
                "uploaded' — which the comment on suppressUpload() already " +
                "makes. The capture hook must stay BELOW that gate.",
            emptyList<DictationHistoryStore.Item>(),
            history.recent(10),
        )
        assertEquals(
            "the directory is not empty, so audio was written even though no " +
                "index entry was: ${history.dir.list()?.toList()}",
            DictationHistoryStore.Usage(0, 0L),
            history.usage(),
        )
        assertEquals(
            "a cancelled recording left files in the history directory",
            emptyList<String>(),
            (history.dir.listFiles() ?: emptyArray()).map { it.name }.sorted(),
        )
        session.destroy()
    }

    @Test
    fun aDestroyedSessionFilesNothingInHistory() {
        // destroy() stops the capture, which lands in onFinished. A dead
        // session deletes its recording rather than keeping it, so the hook
        // must be below this gate too.
        val history = store()
        val session = session(history)
        session.destroy()

        finishRecording(session, recordingFile())
        settle()

        assertEquals(
            "a destroyed session filed its recording into history",
            emptyList<DictationHistoryStore.Item>(),
            history.recent(10),
        )
        assertEquals(
            emptyList<String>(),
            (history.dir.listFiles() ?: emptyArray()).map { it.name }.sorted(),
        )
    }
}
