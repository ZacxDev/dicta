package dev.zacx.dicta

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 🔴 [RemoteTranscriber] is the upload path **without a microphone**, and that
 * is a claim about what the class does *not* do. Three layered guards, which
 * fail in different ways on purpose — any one of them alone would be a comment:
 *
 *  * [aTranscribeSucceedsWhileAForeignOwnerHoldsTheMicrophone] — the
 *    **behavioural** guard, and the one that actually encodes the hazard. It is
 *    also the assertion that distinguishes this design from the rejected
 *    near-miss ("reuse `DictationSession` but skip `start()`"): a re-transcribe
 *    that acquires [MicrophoneOwner] is refused here and goes red.
 *  * [theSourceNamesNoMicrophoneTypeAnywhere] — the **textual** guard, with
 *    both controls, because an absence-assertion is otherwise indistinguishable
 *    from a check wired to the wrong file.
 *  * the ladder tests — **behaviour preservation** for the code that moved out
 *    of `DictationSession.transcribeRemotely`, including the per-call
 *    [UploadBudget] and the 🔴 the-input-file-survives property.
 *
 * The file property is not hygiene: the whole reason this class exists is that
 * `DictationSession.destroy()` deletes `audioFile` unconditionally, so a
 * never-started session pointed at a stored history entry would delete the
 * user's recording. [theInputFileIsNeverDeleted] is that exact failure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteTranscriberTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        // Never strand ownership for the rest of the suite.
        MicrophoneOwner.clearForTest()
    }

    // --- (a) the behavioural guard ------------------------------------------

    /**
     * 🔴 The hazard, as behaviour.
     *
     * A **foreign** token holds the microphone — exactly the state the
     * dictation-history re-transcribe runs in, because the bubble may well be
     * recording while the user re-processes an old entry. [MicrophoneOwner]
     * is a plain compare-and-set, so any acquire inside the re-transcribe path
     * is refused, and a path that treats that refusal as fatal (as every
     * capture path in this app correctly does) delivers nothing.
     *
     * A transcript coming back is therefore evidence that nothing on this path
     * asked for the microphone at all.
     */
    @Test
    fun aTranscribeSucceedsWhileAForeignOwnerHoldsTheMicrophone() {
        val foreign = Any()
        assertTrue(
            "the test could not take the microphone, so it is not reproducing " +
                "the state under test",
            MicrophoneOwner.acquire(foreign),
        )

        val stub = Stub(listOf(SttOutcome.Success("the server transcript")))
        val events = Recorder()
        transcriber(configuredSettings(), stub).transcribe(
            audio = recordingFile(),
            audioSeconds = 3.0,
            localTranscript = { "" },
            events = events,
        )

        assertEquals(
            "a re-transcribe produced no remote transcript while somebody " +
                "ELSE held the microphone. This class must not touch the " +
                "microphone at all: a stored recording is re-sent from disk, " +
                "and the near-miss design (reuse DictationSession, skip " +
                "start()) fails exactly here.",
            listOf("the server transcript"),
            events.remote.map { it.first },
        )
        assertEquals(
            "the upload never happened, so the assertion above is about the " +
                "harness and not about the microphone",
            1,
            stub.attempts.size,
        )
        assertTrue(
            "the re-transcribe took or released somebody else's microphone",
            MicrophoneOwner.isHeldBy(foreign),
        )
    }

    // --- (b) the textual guard ----------------------------------------------

    /**
     * 🔴 The source of this one file, comments stripped, must not name a
     * microphone type.
     *
     * Comments stripped is the difference between a guard on the code and a
     * guard on the prose: the kdoc in `RemoteTranscriber.kt` *explains* why it
     * touches no `MicrophoneOwner`, so a raw substring search finds the word
     * inside the comment that exists to say it is absent — the same way
     * `NetworkManifestTest`'s first cleartext check was wrong.
     *
     * Controls live in [theMicrophoneScanSeesATypeButNotAMentionOfOneInAComment].
     */
    @Test
    fun theSourceNamesNoMicrophoneTypeAnywhere() {
        val src = stripComments(transcriberSource())
        for (type in MICROPHONE_TYPES) {
            assertFalse(
                "RemoteTranscriber.kt names <$type>. This class is the upload " +
                    "path for a recording that is already on disk and it must " +
                    "own no microphone surface — a re-transcribe runs while " +
                    "another session may be recording, and a second opener is " +
                    "not refused by Android, it is fed bit-exact zeros.",
                src.contains(type),
            )
        }
    }

    /**
     * Positive and negative control for the scan above. Without both halves a
     * clean result is indistinguishable from a check wired to the wrong file or
     * the wrong strings.
     */
    @Test
    fun theMicrophoneScanSeesATypeButNotAMentionOfOneInAComment() {
        val names = """
            class Fake {
                private val capture = MicCapture(listener, file)
                fun go() = MicrophoneOwner.acquire(this)
            }
        """.trimIndent()
        val stripped = stripComments(names)
        assertTrue(
            "the scan cannot see a microphone type when one IS named, so the " +
                "real test's pass proves nothing",
            MICROPHONE_TYPES.any { stripped.contains(it) },
        )

        val onlyInAComment = """
            /** Owns no MicrophoneOwner token and constructs no MicCapture. */
            class Fake {
                // AudioRecord / RECORD_AUDIO are deliberately absent.
                fun go() = Unit
            }
        """.trimIndent()
        assertTrue(
            "a mention inside a comment was mistaken for real code, so the " +
                "guard is pinning prose rather than the implementation",
            MICROPHONE_TYPES.none { stripComments(onlyInAComment).contains(it) },
        )
    }

    /**
     * The scan must be reading the real file, not an empty string.
     *
     * 🔴 `read()` already refuses to pass on a file it could not locate; this
     * adds the other half — that what it found is the class under test.
     */
    @Test
    fun theScannedSourceIsActuallyRemoteTranscriber() {
        val src = stripComments(transcriberSource())
        assertTrue(
            "the located file is not RemoteTranscriber.kt's source",
            src.contains("class RemoteTranscriber(") &&
                src.contains("fun transcribe("),
        )
    }

    // --- (c) behaviour preservation: the ladder ------------------------------

    @Test
    fun aSuccessDeliversTheTranscriptWithTheDoneStatusAsOneUnit() {
        val stub = Stub(listOf(SttOutcome.Success("the server transcript")))
        val events = Recorder()
        transcriber(configuredSettings(), stub).transcribe(
            audio = recordingFile(),
            audioSeconds = 11.0,
            localTranscript = { "the phone's guess" },
            events = events,
        )

        assertEquals(
            "the transcript and the status that accompanies it must arrive as " +
                "one Events call — they were one postToMain before the " +
                "extraction, and splitting them changes the interleaving the " +
                "UI sees",
            listOf("the server transcript" to ctx.getString(R.string.stt_remote_done)),
            events.remote,
        )
        assertEquals(
            "the uploading status must still be announced, and on its own",
            listOf(ctx.getString(R.string.stt_uploading)),
            events.statuses,
        )
        assertTrue("a success is not a fallback", events.fellBack.isEmpty())
        // The settings reach the transport, and exactly one transport is built
        // per call — the seam where SttHttp used to be constructed.
        assertEquals(listOf(SttEndpoints.DEFAULT_BASE_URL), stub.baseUrls)
        assertEquals(listOf("a-bearer-token"), stub.tokens)
        assertTrue("a first-try success must not be slept on", stub.sleeps.isEmpty())
    }

    @Test
    fun theLanguageAndAudioBytesReachTheTransportUnchanged() {
        val stub = Stub(listOf(SttOutcome.Success("x")))
        val file = recordingFile(byteArrayOf(0x11, 0x27, 0x3D, 0x54))
        val settings = configuredSettings().apply { language = "pt" }
        transcriber(settings, stub).transcribe(
            audio = file,
            audioSeconds = 7.5,
            localTranscript = { "" },
            events = Recorder(),
        )

        val attempt = stub.attempts.single()
        assertEquals("pt", attempt.language)
        assertEquals(7.5, attempt.audioSeconds, 0.0001)
        assertTrue(
            "the file's bytes must be what is uploaded, got ${attempt.audio.toList()}",
            attempt.audio.contentEquals(byteArrayOf(0x11, 0x27, 0x3D, 0x54)),
        )
    }

    /**
     * 🔴 The property that keeps a re-transcribe from destroying the history
     * entry it was reading.
     *
     * `DictationSession.destroy()` deletes `audioFile` unconditionally, which
     * is correct for a cache file that session created and catastrophic for a
     * stored recording. This class is handed a file it did not create.
     */
    @Test
    fun theInputFileIsNeverDeleted() {
        val file = recordingFile()
        val stub = Stub(listOf(SttOutcome.Success("the server transcript")))
        transcriber(configuredSettings(), stub).transcribe(
            audio = file,
            audioSeconds = 3.0,
            localTranscript = { "" },
            events = Recorder(),
        )

        assertTrue(
            "the input recording was deleted. For the dictation-history " +
                "re-transcribe that file IS the user's history entry, and " +
                "deleting it is the exact failure the rejected 'reuse " +
                "DictationSession and skip start()' design would have had.",
            file.exists(),
        )
    }

    @Test
    fun anUnrecoverableFailureFallsBackCarryingTheLocalTranscript() {
        val stub = Stub(listOf(SttOutcome.Http(401, "invalid or missing bearer token")))
        val events = Recorder()
        transcriber(configuredSettings(), stub).transcribe(
            audio = recordingFile(),
            audioSeconds = 3.0,
            localTranscript = { "the phone's guess" },
            events = events,
        )

        assertEquals("a 401 must cost exactly one upload", 1, stub.attempts.size)
        val (status, local) = events.fellBack.single()
        assertEquals(
            "the on-device text must be handed over, or the user is left with " +
                "nothing when the server refuses",
            "the phone's guess",
            local,
        )
        assertEquals(
            "a 401 cannot recover, so the status must be the final 'fell back' " +
                "one and not the 'still trying' one",
            ctx.getString(
                R.string.stt_fell_back,
                "transcription service rejected the token — check Settings",
            ),
            status,
        )
        assertTrue("no remote transcript exists to deliver", events.remote.isEmpty())
    }

    @Test
    fun aFallbackWithNoLocalTranscriptHandsOverTheEmptyString() {
        // The isNotBlank decision lives in RemoteTranscriber, so consumers do
        // not each re-make it. A blank local transcript must arrive as "",
        // never as whitespace that a consumer would then render.
        val stub = Stub(listOf(SttOutcome.Http(413, "audio too long")))
        val events = Recorder()
        transcriber(configuredSettings(), stub).transcribe(
            audio = recordingFile(),
            audioSeconds = 3.0,
            localTranscript = { "   " },
            events = events,
        )

        assertEquals("", events.fellBack.single().second)
    }

    @Test
    fun aRecoverableFailureSaysTheTextMayStillBeReplaced() {
        // Three transient failures exhaust the fast ladder with the reserved
        // recovery slot still unspent, which is the one state that reports
        // "retrying".
        val stub = Stub(
            List(3) { SttOutcome.Http(503, "model not loaded yet") },
            healthAnswers = List(40) { false },
        )
        val events = Recorder()
        transcriber(configuredSettings(), stub).transcribe(
            audio = recordingFile(),
            audioSeconds = 3.0,
            localTranscript = { "the phone's guess" },
            events = events,
        )

        assertEquals(
            "the retrying variant of the status must be chosen inside the " +
                "ladder, where recoveryPending exists",
            ctx.getString(
                R.string.stt_fell_back_retrying,
                "HTTP 503: model not loaded yet",
            ),
            events.fellBack.first().first,
        )
    }

    // --- (c) the two refusals, which must not upload -------------------------

    @Test
    fun anUnconfiguredRemoteSaysSoAndUploadsNothing() {
        val stub = Stub(listOf(SttOutcome.Success("never asked for")))
        val events = Recorder()
        // A perfectly good token, but the user turned the remote path off.
        val settings = configuredSettings().apply { remoteEnabled = false }
        transcriber(settings, stub).transcribe(
            audio = recordingFile(),
            audioSeconds = 3.0,
            localTranscript = { "the phone's guess" },
            events = events,
        )

        assertEquals(
            listOf(ctx.getString(R.string.stt_not_configured)),
            events.statuses,
        )
        assertEquals("nothing may go on the wire", 0, stub.attempts.size)
        assertTrue(events.remote.isEmpty() && events.fellBack.isEmpty())
    }

    @Test
    fun aMissingTokenSaysNotConfiguredAndUploadsNothing() {
        val stub = Stub(listOf(SttOutcome.Success("never asked for")))
        val events = Recorder()
        transcriber(settingsWithUndecryptableToken(), stub).transcribe(
            audio = recordingFile(),
            audioSeconds = 3.0,
            localTranscript = { "" },
            events = events,
        )

        assertEquals(
            listOf(ctx.getString(R.string.stt_not_configured)),
            events.statuses,
        )
        assertEquals(0, stub.attempts.size)
    }

    /**
     * The token check *after* `isRemoteConfigured`, which that flag cannot
     * reach: `isRemoteConfigured` is `remoteEnabled && hasToken`, so a settings
     * object with no token stops at the branch above. This drives the second
     * branch with a token that decrypts once and then stops — the real shape of
     * a keystore key that has been invalidated between the two reads.
     */
    @Test
    fun aTokenThatVanishesBetweenTheTwoReadsStillUploadsNothing() {
        val stub = Stub(listOf(SttOutcome.Success("never asked for")))
        val events = Recorder()
        transcriber(settingsWithASingleUseToken(), stub).transcribe(
            audio = recordingFile(),
            audioSeconds = 3.0,
            localTranscript = { "" },
            events = events,
        )

        assertEquals(
            listOf(ctx.getString(R.string.stt_not_configured)),
            events.statuses,
        )
        assertEquals(
            "a token that would not decrypt was sent anyway",
            0,
            stub.attempts.size,
        )
    }

    @Test
    fun anUnreadableFileSaysNothingWasRecordedAndUploadsNothing() {
        val stub = Stub(listOf(SttOutcome.Success("never asked for")))
        val events = Recorder()
        val gone = File.createTempFile("remote-transcriber", ".ogg")
        assertTrue(gone.delete())

        transcriber(configuredSettings(), stub).transcribe(
            audio = gone,
            audioSeconds = 3.0,
            localTranscript = { "" },
            events = events,
        )

        assertEquals(
            listOf(ctx.getString(R.string.stt_no_audio)),
            events.statuses,
        )
        assertEquals(0, stub.attempts.size)
    }

    // --- (c) 🔴 a fresh budget per call -------------------------------------

    /**
     * 🔴 The four-upload ceiling is **per call**, not per [RemoteTranscriber].
     *
     * A budget hoisted to a constructor field would be spent by the first call
     * and would starve every later one — silently, because the first call
     * works. The history feature re-processes one stored recording more than
     * once, so the second attempt would upload nothing at all.
     *
     * Asserted on the transport's own attempt numbers rather than reasoned
     * about: a dense `1..4` twice is only producible by two fresh budgets.
     */
    @Test
    fun everyCallGetsItsOwnFullUploadBudget() {
        val stub = Stub(
            // 40 transient outcomes available: if any path is willing to
            // upload a fifth time within one call, it can.
            List(40) { SttOutcome.Http(503, "model not loaded yet") },
            // Always healthy, so the reserved recovery slot is spent at once
            // and the call ends without a 120 s poll.
            healthAnswers = List(40) { true },
        )
        val subject = transcriber(configuredSettings(), stub)
        val file = recordingFile()

        subject.transcribe(file, 3.0, { "" }, Recorder())
        assertEquals(
            "the first call must spend the whole ceiling: 3 fast uploads plus " +
                "the reserved recovery one",
            listOf(1, 2, 3, 4),
            stub.attempts.map { it.attemptNumber },
        )

        subject.transcribe(file, 3.0, { "" }, Recorder())
        assertEquals(
            "🔴 the second call did not get a fresh UploadBudget. The ceiling " +
                "is per call; a budget shared across calls starves every " +
                "re-process after the first, and does so silently because the " +
                "first one works.",
            listOf(1, 2, 3, 4, 1, 2, 3, 4),
            stub.attempts.map { it.attemptNumber },
        )
    }

    // --- harness ------------------------------------------------------------

    private class Recorder : RemoteTranscriber.Events {
        val statuses = mutableListOf<String>()
        val remote = mutableListOf<Pair<String, String>>()
        val fellBack = mutableListOf<Pair<String, String>>()

        override fun onStatus(text: String) { statuses += text }
        override fun onRemoteTranscript(text: String, status: String) {
            remote += text to status
        }
        override fun onFellBack(status: String, localTranscript: String) {
            fellBack += status to localTranscript
        }
    }

    /**
     * The transport seam, scripted. The same idiom as
     * `TranscriptionCoordinatorTest.Harness`: past the end of the script the
     * last outcome repeats rather than throwing, because a crash on an
     * unexpected extra upload would hide HOW MANY happened and the count is
     * what the budget test asserts.
     */
    private class Stub(
        private val outcomes: List<SttOutcome>,
        private val healthAnswers: List<Boolean> = emptyList(),
    ) {
        val attempts = mutableListOf<TranscriptionCoordinator.UploadAttempt>()
        val sleeps = mutableListOf<Long>()
        val baseUrls = mutableListOf<String>()
        val tokens = mutableListOf<String>()
        var healthCalls = 0
        var now = 0L

        fun factory(): (String, String) -> RemoteTranscriber.Transport = { baseUrl, token ->
            baseUrls += baseUrl
            tokens += token
            RemoteTranscriber.Transport(
                upload = { attempt ->
                    attempts += attempt
                    outcomes.getOrElse(attempts.size - 1) { outcomes.last() }
                },
                health = {
                    val answer = healthAnswers.getOrElse(healthCalls) { true }
                    healthCalls++
                    answer
                },
            )
        }
    }

    private fun transcriber(settings: SttSettings, stub: Stub) = RemoteTranscriber(
        ctx,
        settings,
        locale = java.util.Locale("de", "DE"),
        transportFactory = stub.factory(),
        // No real sleeping, and the injected clock advances by exactly the
        // slept amount so the recovery budget is deterministic.
        sleeper = { ms -> stub.sleeps += ms; stub.now += ms },
        unitRandom = { 0.5 },   // no jitter: pins the delays exactly
        elapsedMs = { stub.now },
    )

    private fun recordingFile(bytes: ByteArray = ByteArray(4096)): File =
        File.createTempFile("remote-transcriber", ".ogg").apply { writeBytes(bytes) }

    /**
     * Settings whose token decrypts, constructed directly rather than via
     * `SttSettings.create` — that needs an `AndroidKeyStore` Robolectric does
     * not have, the same seam `DictationSessionUploadGateTest` uses.
     */
    private fun configuredSettings(): SttSettings {
        val prefs = freshPrefs()
        val store = TokenStore(prefs, identityCipher())
        store.putToken("a-bearer-token")
        return SttSettings(prefs, store)
    }

    private fun settingsWithUndecryptableToken(): SttSettings {
        val prefs = freshPrefs()
        val store = TokenStore(
            prefs,
            object : SecretCipher {
                override fun encrypt(plaintext: String): String = plaintext
                override fun decrypt(blob: String): String? = null
            },
        )
        store.putToken("a-bearer-token")
        return SttSettings(prefs, store)
    }

    private fun settingsWithASingleUseToken(): SttSettings {
        val prefs = freshPrefs()
        var decryptCalls = 0
        val store = TokenStore(
            prefs,
            object : SecretCipher {
                override fun encrypt(plaintext: String): String = plaintext
                override fun decrypt(blob: String): String? =
                    if (decryptCalls++ < 1) blob else null
            },
        )
        store.putToken("a-bearer-token")
        return SttSettings(prefs, store)
    }

    private fun identityCipher() = object : SecretCipher {
        override fun encrypt(plaintext: String): String = plaintext
        override fun decrypt(blob: String): String = blob
    }

    private fun freshPrefs() = ctx
        .getSharedPreferences("remote-transcriber-test", Context.MODE_PRIVATE)
        .apply { edit().clear().commit() }

    private fun transcriberSource(): String = read(
        "app/src/main/java/dev/zacx/dicta/RemoteTranscriber.kt",
        "src/main/java/dev/zacx/dicta/RemoteTranscriber.kt",
        "../app/src/main/java/dev/zacx/dicta/RemoteTranscriber.kt",
        what = "RemoteTranscriber.kt",
    )

    private fun read(vararg paths: String, what: String): String {
        val found = paths.map { File(it) }.firstOrNull { it.exists() }
        assertTrue(
            "could not locate $what from ${File(".").absolutePath} — refusing " +
                "to report a pass on a file that was never read",
            found != null,
        )
        return found!!.readText()
    }

    private companion object {
        /**
         * Every type whose presence would mean this class has a microphone
         * surface: the ownership token, the platform recorder, our own capture
         * wrapper, the on-device recogniser wrapper, and the permission.
         */
        val MICROPHONE_TYPES = listOf(
            "MicrophoneOwner",
            "AudioRecord",
            "MicCapture",
            "SpeechHelper",
            "RECORD_AUDIO",
        )

        fun stripComments(src: String): String = src
            .replace(Regex("//.*"), "")
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
    }
}
