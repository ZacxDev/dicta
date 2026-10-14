package dev.zacx.dicta

import android.content.Context
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * 🔴 The **other half** of the cancel button.
 *
 * [BubbleDictationTest] proves the state machine *decides* not to upload — it
 * emits `DiscardRecording` rather than `SubmitRecording`. That decision is
 * worth nothing if [DictationSession] uploads anyway, and the two were tested
 * in isolation: the machine with no session, the session never at all. The
 * defect would live exactly in the seam — "verified in isolation" on both sides
 * while the combined behaviour is broken.
 *
 * So this drives the session's real `onFinished` path, the one `MicCapture`
 * calls when a recording is complete, and counts whether the upload ladder was
 * entered. [aFinishedRecordingNormallyDoesReachTheUploadPath] is the positive
 * control: a zero from a harness that never reaches the path at all is
 * indistinguishable from a zero caused by the suppression under test, so the
 * pair is what carries the claim.
 *
 * `AudioRecord` and `MediaCodec` are still out of reach here — nothing below
 * records anything. What is exercised is the branch between "the recording
 * finished" and "the bytes are handed to the uploader".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictationSessionUploadGateTest {

    /** Counts status lines, which is how the upload ladder announces itself. */
    private class Counter : DictationSession.Callbacks {
        val statuses = mutableListOf<String>()
        override fun onTranscript(text: String, fromRemote: Boolean, isFinal: Boolean) = Unit
        override fun onStatus(text: String) { statuses += text }
        override fun onLevel(level: Double) = Unit
        override fun onTier(tier: AudioCaptureLogic.Tier) = Unit
        override fun onClientSilenced(silenced: Boolean) = Unit
        override fun onRecordingStopped() = Unit
    }

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    /**
     * Settings with no usable token, so the ladder stops at "not configured"
     * instead of opening a socket. Reaching that message is still proof that
     * the upload path was entered, which is the whole question here.
     *
     * Constructed directly rather than via `SttSettings.create`, which needs an
     * `AndroidKeyStore` Robolectric does not have.
     */
    private fun settings(): SttSettings {
        val prefs = ctx.getSharedPreferences("gate-test", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        val cipher = object : SecretCipher {
            override fun encrypt(plaintext: String): String = plaintext
            override fun decrypt(blob: String): String? = null
        }
        return SttSettings(prefs, TokenStore(prefs, cipher))
    }

    private fun recordingFile(): File =
        File.createTempFile("gate", ".ogg").apply { writeBytes(ByteArray(4096)) }

    /**
     * Calls the session's own `MicCapture.Listener.onFinished` — the one real
     * entry point into the upload path — by reflection, because the listener is
     * a private inner class and `MicCapture` cannot be started here.
     */
    private fun finishRecording(session: DictationSession, file: File) {
        val listenerClass = Class.forName("dev.zacx.dicta.DictationSession\$MicListener")
        val ctor = listenerClass.getDeclaredConstructor(DictationSession::class.java)
            .apply { isAccessible = true }
        val listener = ctor.newInstance(session) as MicCapture.Listener
        listener.onFinished(file, 3.0)
    }

    /** Lets the worker thread run and drains what it posts to the main looper. */
    private fun settle(counter: Counter): List<String> {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (counter.statuses.isNotEmpty()) break
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return counter.statuses.toList()
    }

    @Test
    fun aFinishedRecordingNormallyDoesReachTheUploadPath() {
        // 🔴 Positive control. Without this, the zero in the test below could
        // just as easily mean this harness never reaches the upload path at all.
        val counter = Counter()
        val session = DictationSession(ctx, settings(), counter)
        finishRecording(session, recordingFile())

        val statuses = settle(counter)
        assertTrue(
            "a finished recording produced no status at all, so this test " +
                "cannot observe the upload path and the suppression test below " +
                "proves nothing. Statuses: $statuses",
            statuses.isNotEmpty(),
        )
        session.destroy()
    }

    @Test
    fun aSuppressedRecordingNeverReachesTheUploadPath() {
        val counter = Counter()
        val session = DictationSession(ctx, settings(), counter)
        session.suppressUpload()
        finishRecording(session, recordingFile())

        assertEquals(
            "a suppressed recording entered the upload path. This is the " +
                "cancel button: the machine deciding not to upload is worth " +
                "nothing if the session uploads anyway, and 'nothing left the " +
                "phone' is a promise the user acts on when they cancel a " +
                "recording they did not mean to make.",
            emptyList<String>(),
            settle(counter),
        )
        session.destroy()
    }

    @Test
    fun theSuppressionIsClearedByTheNextStart() {
        // A one-shot flag that stuck would silently disable uploading for the
        // rest of the session's life after a single cancel.
        val session = DictationSession(ctx, settings(), Counter())
        session.suppressUpload()
        val field = DictationSession::class.java
            .getDeclaredField("uploadSuppressed")
            .apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val flag = field.get(session) as java.util.concurrent.atomic.AtomicBoolean
        assertTrue("suppressUpload did not set the flag", flag.get())

        // start() cannot complete here (no AudioRecord), but the reset happens
        // before any platform call, so the clearing is still observable.
        runCatching { session.start() }
        assertEquals(
            "the suppression survived a new recording, so every recording " +
                "after a single cancel would be silently dropped",
            false,
            flag.get(),
        )
        session.destroy()
    }
}
