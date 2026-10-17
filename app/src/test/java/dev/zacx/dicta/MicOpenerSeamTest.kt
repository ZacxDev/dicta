package dev.zacx.dicta

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.File

/**
 * 🔴 The **seam between the two microphone openers** — the defect neither
 * opener's own tests could see, because each was only ever exercised alone.
 *
 * ## What was broken
 *
 * [MicrophoneOwner] was a **one-way** check. `DictateActivity.startDictation`
 * refused to start anything while somebody *else* held the microphone, and
 * `DictationSession.start()` registered as the holder — but
 * `startPhoneOnlyDictation` called `SpeechHelper.start()` with **no acquire at
 * all**. On that path `SpeechRecognizer` opens the microphone itself, so while
 * the card's own recogniser was listening `MicrophoneOwner.isHeld` was `false`.
 *
 * It is reached by the error route — `settings == null` (no `AndroidKeyStore`,
 * a documented device state here) or a remote session that would not start —
 * and from there the bubble overlay, the QS tile and the notification all open
 * an `AudioRecord` straight into the live `SpeechRecognizer`. 🔴 Android does
 * not refuse that: it feeds one of the two **bit-exact zeros**, with a
 * successful `startRecording()` and no error callback, so the symptom is an
 * empty or invented transcript and it gets investigated as a server bug.
 *
 * Every test in this file therefore builds the *combined* state — one opener
 * live, the other one trying — rather than either opener in isolation. Each
 * carries a positive control, because a refusal is a zero and a zero from a
 * harness that never reached the platform is indistinguishable from a zero
 * caused by a guard.
 *
 * ## What is NOT claimed here
 *
 * That the platform really hands the loser zeros is device/OS behaviour, and
 * is the premise rather than the claim. Nothing below `AudioRecord`,
 * `MediaCodec` or a real `SpeechRecognizer` exists under Robolectric: the
 * recogniser here is the shadow, driven through its own listener.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MicOpenerSeamTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private var controller: ActivityController<DictateActivity>? = null
    private val temps = mutableListOf<File>()

    @After
    fun tearDown() {
        runCatching { controller?.destroy() }
        MicrophoneOwner.clearForTest()
        BubbleSessionBus.clearForTest()
        OverlayVisibility.clearForTest()
        temps.forEach { runCatching { it.delete() } }
    }

    // --- 🔴 1: the phone-only recogniser holds the microphone ----------------

    /**
     * 🔴 The behavioural claim: while the card's **phone-only** recogniser is
     * listening, a `DictationSession` started from anywhere else is refused.
     *
     * Asserted through what the refused session does, not through a flag: it
     * reports `stt_mic_in_use` and **nothing else**, and never constructs a
     * `MicCapture`. The status line is the discriminator because a refused
     * `start()` and an unguarded one both return false here (Robolectric has no
     * `AudioRecord`) — only the guarded path says `stt_mic_in_use`;
     * [aSessionIsNotRefusedWhenNoRecogniserIsListening] is that control.
     */
    @Test
    fun aPhoneOnlyDictationHoldsTheMicrophoneSoAConcurrentSessionIsRefused() {
        val activity = cardOnThePhoneOnlyPath()

        // Positive control: the recogniser really is listening, so the mic
        // really is open. Without this the refusal below could be the card
        // having quietly done nothing at all.
        assertTrue(
            "the phone-only recogniser never started listening, so this test " +
                "is not about a live microphone at all",
            phoneRecogniserIsListening(activity),
        )

        val counter = Counter()
        val intruder = DictationSession(ctx, settings(), counter)

        assertFalse(
            "a DictationSession started while the card's own SpeechRecognizer " +
                "was listening. That is two microphone clients at once, which " +
                "Android answers by feeding one of them exact zeros.",
            intruder.start(),
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            "the second opener must be refused with the mic-in-use message and " +
                "nothing else — a capture error here would mean it entered " +
                "MicCapture and opened the microphone",
            listOf(ctx.getString(R.string.stt_mic_in_use)),
            counter.statuses,
        )
        assertNull(
            "a MicCapture was constructed despite the refusal",
            readField(intruder, DictationSession::class.java, "capture"),
        )
        intruder.destroy()
    }

    /**
     * Positive control for the test above: with no recogniser listening, the
     * same session reaches the real capture path and fails there with its own
     * message. Without this, the refusal asserted above is indistinguishable
     * from Robolectric simply having no microphone.
     */
    @Test
    fun aSessionIsNotRefusedWhenNoRecogniserIsListening() {
        val counter = Counter()
        val session = DictationSession(ctx, settings(), counter)

        assertFalse(
            "with a free microphone the session must still fail here — " +
                "Robolectric has no AudioRecord. If this returns true the " +
                "platform has grown one and this control needs rewriting.",
            session.start(),
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(
            "no status at all, so this control cannot see MicCapture being " +
                "entered and proves nothing",
            counter.statuses.isNotEmpty(),
        )
        assertNotEquals(
            "the unguarded path reported the mic-in-use refusal, so the two " +
                "branches are indistinguishable",
            listOf(ctx.getString(R.string.stt_mic_in_use)),
            counter.statuses,
        )
        session.destroy()
    }

    /**
     * The other half of ownership: the phone-only path must **give the
     * microphone back**, or the first card to use it refuses every recording
     * in the process for the life of the app.
     *
     * Asserted behaviourally — a session started afterwards gets through to the
     * capture path — rather than by reading `isHeld`.
     */
    @Test
    fun tearingDownTheCardReturnsTheMicrophoneToTheNextOpener() {
        val activity = cardOnThePhoneOnlyPath()
        assertTrue(phoneRecogniserIsListening(activity))

        controller!!.destroy()
        controller = null

        val counter = Counter()
        val next = DictationSession(ctx, settings(), counter)
        next.start()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotEquals(
            "the card kept the microphone after it was destroyed, so every " +
                "later recording in the process is refused",
            listOf(ctx.getString(R.string.stt_mic_in_use)),
            counter.statuses,
        )
        assertTrue(
            "no status at all — this assertion cannot tell a released " +
                "microphone from a session that never tried",
            counter.statuses.isNotEmpty(),
        )
        next.destroy()
    }

    /**
     * And the refusal is symmetric: the card on the phone-only path must
     * itself be refused when something else already holds the microphone.
     *
     * This is the direction that already worked, kept here because the two
     * directions are what the seam is made of — a fix to one has repeatedly
     * been written as though it covered the other.
     */
    @Test
    fun thePhoneOnlyPathIsStillRefusedAMicrophoneSomebodyElseHolds() {
        grantMic()
        installRecogniser()
        val activity = buildAttachedCard()
        setField(activity, "settings", null)
        val bubble = Any()
        MicrophoneOwner.acquire(bubble)

        invoke(activity, "startDictation")

        assertEquals(
            "the card started the phone's recogniser while another capture " +
                "held the microphone",
            ctx.getString(R.string.stt_mic_in_use),
            statusOf(activity),
        )
        assertFalse(
            "the refused path started listening anyway",
            phoneRecogniserIsListening(activity),
        )
        assertTrue(
            "the refusal stole somebody else's microphone",
            MicrophoneOwner.isHeldBy(bubble),
        )
    }

    // --- 🟡 1: a destroyed session's LOCAL final transcript -----------------

    /**
     * 🔴 The recogniser's own callbacks used to bypass the `destroyed` gate.
     *
     * `startRecognizer`'s listener called `callbacks.onTranscript(...)`
     * **directly** — including `isFinal = true` — rather than through
     * `postToMain`, so a destroyed session's late local final reached whatever
     * was listening. [BubbleService] has a generation gate of its own and
     * happens to drop it; `DictateActivity.SessionCallbacks` now has one (`cardGeneration`, added by #17 round 3) but did not when this was written, and and
     * writes the card's views. The consumer here deliberately has no gate,
     * which is the case the session's own flag is supposed to cover.
     */
    @Test
    fun aDestroyedSessionsLocalFinalTranscriptNeverReachesAnUngatedConsumer() {
        installRecogniser()
        val counter = Counter()
        val session = DictationSession(ctx, settings(), counter)
        val listener = recognizerListenerOf(session)

        // Positive control: alive, this very route delivers.
        listener.onPartial("while alive, partial")
        listener.onFinal("while alive, final")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            "the live session delivered nothing through the recogniser route, " +
                "so the silence asserted below would be a fact about this " +
                "harness and not about destroy()",
            listOf("while alive, partial", "while alive, final"),
            counter.transcripts,
        )

        counter.clear()
        session.destroy()
        listener.onPartial("after death, partial")
        listener.onFinal("after death, final")
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "a destroyed session delivered a local transcript. A late " +
                "isFinal = true rewrites the card of whatever recording " +
                "replaced it, and on the bubble it drives TranscriptSettled -> " +
                "toIdle() on a LIVE recording.",
            emptyList<String>(),
            counter.transcripts,
        )
    }

    // --- 🟡 3: a failed remote start must not destroy the previous session ---

    /**
     * 🔴 `startRemoteSession` destroyed the previous session **before** knowing
     * the replacement could start.
     *
     * `destroy()` silences the callbacks *and* deletes the audio file, so when
     * `start()` then failed — and the card fell through to the phone-only
     * recogniser — the transcript the user was waiting for was gone, with no
     * bytes left to retry from.
     */
    @Test
    fun aFailedRemoteStartLeavesThePreviousSessionsUploadIntact() {
        grantMic()
        val activity = buildAttachedCard()

        val counter = Counter()
        val previous = DictationSession(ctx, settings(), counter)
        val audio = temp("in-flight").apply { writeBytes(ByteArray(2048)) }
        setField(previous, DictationSession::class.java, "audioFile", audio)
        setField(activity, "session", previous)
        // With settings present the card takes the remote path, which cannot
        // start here (no AudioRecord) — the exact failure the old ordering
        // turned into data loss.
        setField(activity, "settings", settings())

        invoke(activity, "startDictation")

        assertFalse(
            "the previous session was destroyed before the replacement was " +
                "known to work. Its in-flight upload is cancelled and its " +
                "audio deleted, so the transcript the user is waiting for is " +
                "unrecoverable.",
            previous.isDestroyed,
        )
        assertTrue(
            "the previous recording's audio file was deleted, so even a retry " +
                "has nothing to send",
            audio.exists(),
        )

        // Behavioural: it can still speak to the card it belongs to.
        micListenerOf(previous).onClientSilenced(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(
            "the previous session's callbacks were silenced, so its transcript " +
                "could never land even if the POST returned",
            counter.statuses.isNotEmpty(),
        )
        previous.destroy()
    }

    /**
     * The same failure must also leave the previous session **reachable**.
     *
     * Surviving `destroy()` is not enough on its own: the old code also
     * cleared the field, so the card had no handle left to stop the recording
     * with, to destroy on `onDestroy`, or to pass to the next
     * `isHeldByOtherThan` check — a session alive and unreferenced is the
     * leaked-executor shape, just reached from the other side.
     *
     * ⚠ What is NOT pinned anywhere: that the *successful* branch does destroy
     * the previous session. `DictationSession.start()` cannot succeed under
     * Robolectric (no `AudioRecord`), and a fake session would be asserting
     * against a fixture rather than against `startRemoteSession`. It is read,
     * not measured.
     */
    @Test
    fun aFailedRemoteStartKeepsThePreviousSessionReachable() {
        grantMic()
        val activity = buildAttachedCard()
        val previous = DictationSession(ctx, settings(), Counter())
        setField(activity, "session", previous)
        setField(activity, "settings", settings())

        invoke(activity, "startDictation")

        assertTrue(
            "the card dropped its reference to a session it had not destroyed, " +
                "so nothing can stop it, tear it down or count it as the " +
                "card's own holder any more",
            readField(activity, DictateActivity::class.java, "session") === previous,
        )
        previous.destroy()
    }

    // --- harness ------------------------------------------------------------

    private class Counter : DictationSession.Callbacks {
        val statuses = mutableListOf<String>()
        val levels = mutableListOf<Double>()
        val transcripts = mutableListOf<String>()

        fun clear() {
            statuses.clear()
            levels.clear()
            transcripts.clear()
        }

        override fun onTranscript(text: String, fromRemote: Boolean, isFinal: Boolean) {
            transcripts += text
        }

        override fun onStatus(text: String) {
            statuses += text
        }

        override fun onLevel(level: Double) {
            levels += level
        }

        override fun onTier(tier: AudioCaptureLogic.Tier) = Unit
        override fun onClientSilenced(silenced: Boolean) = Unit
        override fun onRecordingStopped() = Unit
    }

    /**
     * A card sitting on the phone-only path with its recogniser listening —
     * the state `settings == null` produces on a device with no usable
     * `AndroidKeyStore`, and the state a failed remote start falls through to.
     */
    private fun cardOnThePhoneOnlyPath(): DictateActivity {
        grantMic()
        installRecogniser()
        val activity = buildAttachedCard()
        setField(activity, "settings", null)
        invoke(activity, "startDictation")
        return activity
    }

    /**
     * The attach-mode card: built from the bubble's own intent so `onCreate`
     * does **not** run `startDictation()` itself, leaving each test to drive
     * the path it is about.
     */
    private fun buildAttachedCard(): DictateActivity {
        val c = Robolectric.buildActivity(
            DictateActivity::class.java,
            DictateLaunch.transcribingIntent(ctx),
        )
        controller = c
        return c.setup().get()
    }

    /**
     * Makes `SpeechRecognizer.isRecognitionAvailable` true.
     *
     * 🔴 Required, and the reason it is a named step: the real
     * `isRecognitionAvailable` queries the package manager for a
     * [RecognitionService], Robolectric registers none, and
     * `startPhoneOnlyDictation` returns at its `!helper.isAvailable` check
     * before opening anything. A test of the phone-only opener without this is
     * green because nothing ever opened a microphone.
     */
    private fun installRecogniser() {
        val info = ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply {
                packageName = ctx.packageName
                name = "dev.zacx.dicta.NotARealRecognitionService"
            }
        }
        shadowOf(ctx.packageManager).addResolveInfoForIntent(
            Intent(RecognitionService.SERVICE_INTERFACE),
            info,
        )
    }

    private fun grantMic() {
        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    private fun settings(): SttSettings {
        val prefs = ctx.getSharedPreferences("mic-seam-test", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        val cipher = object : SecretCipher {
            override fun encrypt(plaintext: String): String = plaintext
            override fun decrypt(blob: String): String? = null
        }
        return SttSettings(prefs, TokenStore(prefs, cipher))
    }

    private fun statusOf(activity: DictateActivity): String =
        activity.findViewById<android.widget.TextView>(R.id.status_text).text.toString()

    /** True while the card's own [SpeechHelper] has the microphone open. */
    private fun phoneRecogniserIsListening(activity: DictateActivity): Boolean {
        val helper = readField(activity, DictateActivity::class.java, "speech")
            as SpeechHelper?
        return helper?.isListening == true
    }

    /**
     * The real `SpeechHelper.Listener` the session builds in
     * `startRecognizer` — reached by driving that method with a [MicCapture]
     * whose recogniser pipe is open, rather than by inventing a listener here.
     * A fixture would be asserting against itself.
     */
    private fun recognizerListenerOf(session: DictationSession): SpeechHelper.Listener {
        val pipe = ParcelFileDescriptor.createPipe()
        val mic = MicCapture(SilentMicListener(), temp("recogniser-seam"))
        setField(mic, MicCapture::class.java, "pipeRead", pipe[0])
        DictationSession::class.java
            .getDeclaredMethod("startRecognizer", MicCapture::class.java)
            .apply { isAccessible = true }
            .invoke(session, mic)
        val helper = readField(session, DictationSession::class.java, "speech")
        assertNotNull(
            "startRecognizer kept no SpeechHelper, so no recogniser listener " +
                "exists and this test is about nothing. isAvailable was false " +
                "— installRecogniser() did not take.",
            helper,
        )
        return readField(helper!!, SpeechHelper::class.java, "listener")
            as SpeechHelper.Listener
    }

    /** The session's own `MicCapture.Listener`, the real callback route. */
    private fun micListenerOf(session: DictationSession): MicCapture.Listener {
        val cls = Class.forName("dev.zacx.dicta.DictationSession\$MicListener")
        val ctor = cls.getDeclaredConstructor(DictationSession::class.java)
            .apply { isAccessible = true }
        return ctor.newInstance(session) as MicCapture.Listener
    }

    private class SilentMicListener : MicCapture.Listener {
        override fun onLevel(level: Double) = Unit
        override fun onClientSilenced(silenced: Boolean) = Unit
        override fun onFirstAudio() = Unit
        override fun onFinished(file: File?, audioSeconds: Double) = Unit
        override fun onError(message: String) = Unit
    }

    private fun temp(prefix: String): File =
        File.createTempFile(prefix, ".ogg").also { temps += it }

    private fun readField(target: Any, owner: Class<*>, name: String): Any? =
        owner.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun setField(target: Any, owner: Class<*>, name: String, value: Any?) {
        owner.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun setField(target: DictateActivity, name: String, value: Any?) {
        setField(target, DictateActivity::class.java, name, value)
    }

    private fun invoke(target: Any, name: String) {
        target.javaClass.getDeclaredMethod(name)
            .apply { isAccessible = true }
            .invoke(target)
    }
}
