package dev.zacx.dicta

import android.Manifest
import android.content.Context
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 🔴 **Two microphones at once**, closed by an enforced runtime invariant
 * rather than by a flag on one intent.
 *
 * ## The hole this covers
 *
 * [DictateLaunch.EXTRA_ATTACH_SESSION] suppresses the card's own
 * `startDictation()` only on the intent **the bubble itself sends**.
 * `DictateLaunch.intent()` carries no extra, and both the QS tile
 * ([DictaTileService]) and the persistent notification ([MainActivity]) go
 * through `pendingIntent()`, which wraps that bare intent — so:
 *
 *  * **Path A.** The bubble is RECORDING and, by design, nothing is on screen.
 *    The user pulls down the shade and taps the Dicta tile (or the
 *    notification's Dictate action, or the launcher icon → "Try dictation
 *    now"). No card exists, `singleTop` creates one, `onCreate` finds no extra,
 *    `hasMicPermission()` is true → `startDictation()` → a second
 *    `AudioRecord` while the bubble's is live.
 *  * **Path B.** The attached "transcribing…" card leaves `micButton` fully
 *    enabled — `attachToBubbleSession` only changes its *text*. Past the 1.5 s
 *    grace the bubble is tappable too, so card-mic plus bubble-tap in either
 *    order gives two live sessions with both controls on screen.
 *
 * 🔴 Neither path errors. Android resolves microphone contention by feeding the
 * loser **bit-exact zeros** — `startRecording()` succeeds, `read()` returns the
 * full byte count, every byte is 0 — so it surfaces as "the transcript came
 * back empty", or as invented prose from the ASR service, and is investigated
 * as a server bug. That is also precisely why no green unit suite could see it:
 * nothing below `AudioRecord` exists here.
 *
 * ## What each test below can and cannot claim
 *
 * [aSecondSessionIsRefusedBeforeItEverTouchesTheMicrophone] carries the
 * behavioural claim — **no second capture is attempted** — and it carries it
 * with a positive control, because a zero from a harness that never reaches
 * `MicCapture` is indistinguishable from a zero caused by the guard. The
 * activity-level tests carry the *surface* claim: that the tile/notification
 * path and the card's own mic button refuse and report, instead of starting
 * something. They discriminate on the status line, which is a genuine
 * behavioural tell for "which branch of `startDictation()` ran" — the same
 * discriminator [DictateAttachSessionTest] uses and for the same reason.
 *
 * Whether the platform really hands the loser zeros is device/OS behaviour and
 * is **not** asserted anywhere; it is the premise, not the claim.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MicrophoneOwnershipTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        MicrophoneOwner.clearForTest()
        BubbleSessionBus.clearForTest()
        OverlayVisibility.clearForTest()
    }

    // --- the holder itself --------------------------------------------------

    @Test
    fun ownershipIsExclusiveAndReleaseIsScopedToTheOwner() {
        val first = Any()
        val second = Any()

        assertTrue("the first acquire on a free mic must succeed", MicrophoneOwner.acquire(first))
        assertFalse(
            "a SECOND owner acquired a microphone that was already held — the " +
                "whole point of the holder is that this cannot happen",
            MicrophoneOwner.acquire(second),
        )
        assertFalse(
            "acquire must be a plain compare-and-set: a holder asking again " +
                "gets false. It used to be re-entrant, justified by a case " +
                "that cannot reach it (start() returns at its recording check " +
                "BEFORE acquire), so the clause was removed rather than " +
                "re-justified. Nothing in production asks twice — every opener " +
                "releases first — and this pins that nobody quietly puts the " +
                "re-entrancy back and starts relying on it.",
            MicrophoneOwner.acquire(first),
        )
        assertTrue(
            "a refused re-acquire must not have disturbed the holder",
            MicrophoneOwner.isHeldBy(first),
        )

        MicrophoneOwner.release(second)
        assertTrue(
            "release() by a NON-owner freed the microphone, so a late teardown " +
                "can steal the mic from the recording that replaced it",
            MicrophoneOwner.isHeldBy(first),
        )

        MicrophoneOwner.release(first)
        assertFalse("release by the owner must free it", MicrophoneOwner.isHeld)
        assertTrue(
            "a freed microphone must be acquirable, or one recording poisons " +
                "the process for good",
            MicrophoneOwner.acquire(second),
        )
    }

    @Test
    fun isHeldByOtherThanIsFalseForEveryTokenTheCallerOwns() {
        val owner = Any()
        val secondOpener = Any()
        val stranger = Any()
        assertFalse(
            "a free microphone is held by nobody",
            MicrophoneOwner.isHeldByOtherThan(owner),
        )
        MicrophoneOwner.acquire(owner)
        assertFalse(
            "the holder must not be refused its own microphone, or toggling " +
                "the card's mic button off and on again would be refused",
            MicrophoneOwner.isHeldByOtherThan(owner),
        )
        assertFalse(
            "a caller with TWO openers must not be refused by its own second " +
                "token — DictateActivity passes its session AND itself, and " +
                "refusing either would make the card unable to restart a " +
                "dictation it owns",
            MicrophoneOwner.isHeldByOtherThan(secondOpener, owner),
        )
        assertTrue(
            "a mic held by someone else must read as taken, however many of " +
                "the caller's own tokens are listed",
            MicrophoneOwner.isHeldByOtherThan(stranger, secondOpener),
        )
        assertTrue(
            "null is not the holder, so an un-started surface must be refused",
            MicrophoneOwner.isHeldByOtherThan(null),
        )
    }

    // --- the session refuses, before the platform is touched ----------------

    /**
     * 🔴 The behavioural claim: the second `start()` does **not** open capture.
     *
     * `capture` is `DictationSession`'s own `MicCapture` field, so a null there
     * after `start()` means no capture object was ever built. On its own that
     * proves nothing — Robolectric has no `MediaCodec`, so an *unguarded*
     * `start()` also ends with a null `capture`. The discriminator is the
     * status line the two paths produce: entering `MicCapture.start()` reaches
     * `AudioEncoderSink.chooseEncoder()`, which fails here with its own
     * message, while the guarded path reports `stt_mic_in_use` and nothing
     * else. [theUnguardedStartDoesReachTheRealCapturePath] is that positive
     * control.
     */
    @Test
    fun aSecondSessionIsRefusedBeforeItEverTouchesTheMicrophone() {
        val held = Any()
        MicrophoneOwner.acquire(held)

        val counter = Counter()
        val session = DictationSession(ctx, settings(), counter)

        assertFalse(
            "a second session started while another owner held the microphone. " +
                "Android does not refuse that — it feeds one of the two exact " +
                "zeros and the user sees an empty or invented transcript.",
            session.start(),
        )
        assertFalse("a refused session must not believe it is recording", session.isRecording)
        assertNull(
            "a MicCapture was constructed despite the refusal",
            captureFieldOf(session),
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            "the refusal must be reported, and must be the ONLY thing reported " +
                "— a capture error here would mean MicCapture was entered",
            listOf(ctx.getString(R.string.stt_mic_in_use)),
            counter.statuses,
        )
        session.destroy()
    }

    /**
     * 🔴 Positive control for the test above: with the microphone free, the
     * same harness genuinely does reach `MicCapture`, so the zero above is the
     * guard's work and not the harness's limits.
     */
    @Test
    fun theUnguardedStartDoesReachTheRealCapturePath() {
        val counter = Counter()
        val session = DictationSession(ctx, settings(), counter)

        assertFalse(
            "with a free microphone the session must still FAIL to start here " +
                "— Robolectric has no AudioRecord. If this returns true, the " +
                "platform has grown one and this control needs rewriting.",
            session.start(),
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertNotEquals(
            "the unguarded path reported the mic-busy refusal, so the two " +
                "branches are indistinguishable and the guard test above " +
                "proves nothing",
            listOf(ctx.getString(R.string.stt_mic_in_use)),
            counter.statuses,
        )
        assertTrue(
            "the unguarded path produced no status at all, so this control " +
                "cannot see MicCapture being entered. Statuses: ${counter.statuses}",
            counter.statuses.isNotEmpty(),
        )
        assertFalse(
            "a failed capture must not leave the microphone held, or every " +
                "later recording in the process is refused",
            MicrophoneOwner.isHeld,
        )
        session.destroy()
    }

    @Test
    fun stoppingARecordingReleasesTheMicrophoneWithoutWaitingForTheUpload() {
        // The upload outlives the capture by design (the card is waiting on
        // it), so ownership must end with the capture and not with the session.
        val counter = Counter()
        val session = DictationSession(ctx, settings(), counter)
        // start() cannot succeed here, so acquire/release is driven through the
        // two real entry points rather than simulated.
        MicrophoneOwner.clearForTest()
        assertTrue(MicrophoneOwner.acquire(session))
        session.stop()
        assertFalse(
            "stop() left the microphone held, so the next recording is refused " +
                "for as long as the upload ladder keeps retrying",
            MicrophoneOwner.isHeld,
        )
        session.destroy()
    }

    /**
     * A capture error must free the microphone.
     *
     * Driven through the session's own `MicCapture.Listener.onError` — the one
     * route a capture failure takes — rather than through `start()`, because
     * `MicCapture.start()` calls `onError` on **every** false return, so a
     * `start()`-level test cannot tell the release in `start()`'s failure
     * branch apart from this one. (Measured: a mutation removing *either*
     * alone leaves a green suite. This test owns this one; the pair's combined
     * behaviour is owned by [theUnguardedStartDoesReachTheRealCapturePath].)
     */
    @Test
    fun aCaptureErrorReleasesTheMicrophone() {
        val counter = Counter()
        val session = DictationSession(ctx, settings(), counter)
        assertTrue(MicrophoneOwner.acquire(session))

        micListenerOf(session).onError("microphone could not be initialised")

        assertFalse(
            "a capture error left the microphone held by a session that is not " +
                "recording, so every later recording in the process is refused",
            MicrophoneOwner.isHeld,
        )
        session.destroy()
    }

    @Test
    fun destroyingASessionReleasesTheMicrophone() {
        val counter = Counter()
        val session = DictationSession(ctx, settings(), counter)
        assertTrue(MicrophoneOwner.acquire(session))
        session.destroy()
        assertFalse(
            "destroy() left the microphone held by a dead session, which " +
                "refuses every later recording in the process",
            MicrophoneOwner.isHeld,
        )
    }

    // --- Path A: the tile / notification / launcher intent -------------------

    /**
     * 🔴 Path A end to end, from the card's side.
     *
     * The intent is [DictateLaunch.intent] — literally what the QS tile and the
     * notification action wrap (`pendingIntent()`), and what MainActivity's
     * "Try dictation now" uses. It carries no `EXTRA_ATTACH_SESSION`, so the
     * attach branch does not fire and `onCreate` goes on to `startDictation()`.
     */
    @Test
    fun theTileIntentCannotStartASecondDictationWhileTheMicIsHeld() {
        grantMic()
        val bubbleSession = Any()
        MicrophoneOwner.acquire(bubbleSession)

        val activity = Robolectric
            .buildActivity(DictateActivity::class.java, DictateLaunch.intent(ctx))
            .setup()
            .get()

        assertEquals(
            "the card opened from the tile/notification ran its own " +
                "startDictation() while the bubble held the microphone. That " +
                "is a second AudioRecord against a live one, and Android " +
                "answers it by feeding one of them exact zeros — no exception, " +
                "no error callback, just an empty or invented transcript.",
            ctx.getString(R.string.stt_mic_in_use),
            statusOf(activity),
        )
        assertNull(
            "a DictationSession was constructed anyway",
            sessionFieldOf(activity),
        )
        assertTrue(
            "the refusal released somebody else's microphone",
            MicrophoneOwner.isHeldBy(bubbleSession),
        )
    }

    /**
     * The same guard must NOT fire when the microphone is free — otherwise the
     * tile, the notification and the in-app button all open a card that
     * silently refuses to record, which is a worse bug than the one being
     * fixed.
     */
    @Test
    fun theTileIntentStillStartsADictationWhenNothingHoldsTheMic() {
        grantMic()
        val activity = Robolectric
            .buildActivity(DictateActivity::class.java, DictateLaunch.intent(ctx))
            .setup()
            .get()

        assertNotEquals(
            "the card refuses to record even with a free microphone, so every " +
                "entry point outside the bubble is now dead",
            ctx.getString(R.string.stt_mic_in_use),
            statusOf(activity),
        )
    }

    /**
     * Path A on the device-realistic branch: with `settings` present the card
     * takes the **remote** path, whose refusal must not fall through to the
     * phone's own recogniser — that recogniser opens the microphone itself.
     */
    @Test
    fun aRefusedRemoteSessionDoesNotFallBackToThePhoneRecogniser() {
        grantMic()
        val controller = Robolectric
            .buildActivity(DictateActivity::class.java, DictateLaunch.intent(ctx))
        val activity = controller.setup().get()

        // Robolectric has no AndroidKeyStore, so onCreate's own
        // SttSettings.create() returned null and the remote branch was skipped.
        // Inject a real-but-tokenless SttSettings and drive the branch.
        setField(activity, "settings", settings())
        MicrophoneOwner.clearForTest()
        val bubbleSession = Any()
        MicrophoneOwner.acquire(bubbleSession)
        invoke(activity, "startDictation")

        assertEquals(
            "the remote session's MIC_BUSY fell through to " +
                "startPhoneOnlyDictation(), which hands the microphone to " +
                "SpeechRecognizer — the same double capture, reached by the " +
                "error path",
            ctx.getString(R.string.stt_mic_in_use),
            statusOf(activity),
        )
        assertNull("a session was kept despite the refusal", sessionFieldOf(activity))
    }

    /**
     * 🔴 Opener two. With `settings` unavailable — no `AndroidKeyStore`, which
     * is a real device state and the documented degradation — the card goes
     * straight to `startPhoneOnlyDictation()`, where **SpeechRecognizer opens
     * the microphone itself**. No `DictationSession` exists on that path, so
     * the check has to be at the call site; this is the case the remote
     * branch's refusal cannot cover.
     *
     * 🔴 This is the *refusing* direction only. That the phone-only path also
     * **registers**, so an opener elsewhere is refused while it listens, is
     * the other direction and lives in [MicOpenerSeamTest] — it was missing
     * entirely, and a note here claiming the holder had "nothing to refuse"
     * read as though it did not need to exist.
     */
    @Test
    fun withNoSettingsThePhoneRecogniserIsStillRefusedTheHeldMicrophone() {
        grantMic()
        val activity = Robolectric
            .buildActivity(
                DictateActivity::class.java,
                DictateLaunch.transcribingIntent(ctx),
            )
            .setup()
            .get()
        setField(activity, "settings", null)

        val bubbleSession = Any()
        MicrophoneOwner.acquire(bubbleSession)
        invoke(activity, "startDictation")

        assertEquals(
            "with no settings the card fell through to the phone's own " +
                "recogniser while another session held the microphone. " +
                "SpeechRecognizer opens the mic itself, so that is a second " +
                "capture with no DictationSession for the holder to refuse.",
            ctx.getString(R.string.stt_mic_in_use),
            statusOf(activity),
        )
    }

    /**
     * Positive control for the test above: with the microphone free the
     * no-settings path must still reach the phone recogniser, or the refusal
     * asserted there is indistinguishable from the card simply never trying.
     */
    @Test
    fun withNoSettingsAndAFreeMicrophoneThePhoneRecogniserIsStillTried() {
        grantMic()
        val activity = Robolectric
            .buildActivity(
                DictateActivity::class.java,
                DictateLaunch.transcribingIntent(ctx),
            )
            .setup()
            .get()
        setField(activity, "settings", null)

        invoke(activity, "startDictation")

        assertNotEquals(
            "the no-settings path refuses even a FREE microphone, so the " +
                "phone-only fallback is dead and the guard above proves nothing",
            ctx.getString(R.string.stt_mic_in_use),
            statusOf(activity),
        )
        assertNotEquals(
            "nothing happened at all on the no-settings path — the status line " +
                "is still the attach-mode one, so this control cannot see the " +
                "phone recogniser being tried",
            ctx.getString(R.string.stt_uploading),
            statusOf(activity),
        )
    }

    // --- Path B: the attached card's own mic button --------------------------

    /**
     * 🔴 Path B. `attachToBubbleSession` changes the mic button's *text* and
     * nothing else, so the control is live while the bubble records. Tapping it
     * must refuse rather than open a second capture.
     */
    @Test
    fun theAttachedCardsMicButtonCannotStartASecondCapture() {
        grantMic()
        val activity = Robolectric
            .buildActivity(
                DictateActivity::class.java,
                DictateLaunch.transcribingIntent(ctx),
            )
            .setup()
            .get()
        // Attach mode: the card is showing the bubble's recording as uploading.
        assertEquals(ctx.getString(R.string.stt_uploading), statusOf(activity))

        val bubbleSession = Any()
        MicrophoneOwner.acquire(bubbleSession)
        activity.findViewById<Button>(R.id.mic_button).performClick()

        assertEquals(
            "the attached card's mic button started a dictation while the " +
                "bubble still held the microphone — card-mic plus bubble-tap " +
                "in either order then gives two live sessions with both " +
                "controls on screen",
            ctx.getString(R.string.stt_mic_in_use),
            statusOf(activity),
        )
        assertNull(
            "the tap constructed a DictationSession anyway",
            sessionFieldOf(activity),
        )
    }

    // --- a destroyed session is silent --------------------------------------

    /**
     * 🔴 Finding 2's half that belongs to the session: `destroy()` only drops
     * messages **already queued**. A worker blocked in `http.transcribe()` is
     * blocked in a socket read, which `shutdownNow`'s interrupt does not
     * unblock, so it can return afterwards and still post a final transcript.
     */
    @Test
    fun aDestroyedSessionDeliversNothingThroughItsCallbacks() {
        val counter = Counter()
        val session = DictationSession(ctx, settings(), counter)
        val listener = micListenerOf(session)

        // Positive control first: while alive, this very route delivers.
        listener.onLevel(0.42)
        listener.onClientSilenced(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(
            "the live session delivered nothing, so the silence asserted below " +
                "would be a fact about this harness and not about destroy()",
            counter.levels.isNotEmpty() && counter.statuses.isNotEmpty(),
        )

        counter.clear()
        session.destroy()
        assertTrue("destroy() must be observable on the session", session.isDestroyed)
        listener.onLevel(0.99)
        listener.onClientSilenced(true)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "a destroyed session still delivered levels. Delivered to " +
                "BubbleService those animate the LIVE recording's pulse with " +
                "audio from a recording that is over.",
            emptyList<Double>(),
            counter.levels,
        )
        assertEquals(
            "a destroyed session still delivered status/transcript callbacks. " +
                "A late isFinal drives TranscriptSettled -> toIdle() on a " +
                "DIFFERENT, live recording: IDLE with the microphone open, no " +
                "pulse ring, no cancel button, the FGS left promoted, and the " +
                "two-minute cap inert because onMaxDuration() returns " +
                "emptyList() in IDLE.",
            emptyList<String>(),
            counter.statuses,
        )
    }

    @Test
    fun aDestroyedSessionDoesNotEnterTheUploadPath() {
        val counter = Counter()
        val session = DictationSession(ctx, settings(), counter)
        val listener = micListenerOf(session)
        val file = java.io.File.createTempFile("mic-own", ".ogg")
            .apply { writeBytes(ByteArray(4096)) }

        session.destroy()
        listener.onFinished(file, 3.0)
        // Give the worker a real chance to produce something.
        val deadline = System.currentTimeMillis() + 1_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (counter.statuses.isNotEmpty()) break
            Thread.sleep(20)
        }
        assertEquals(
            "a destroyed session enqueued an upload. Its executor is already " +
                "shutdownNow(), so this is either a RejectedExecutionException " +
                "on the capture thread or a POST for a recording nobody is " +
                "waiting for.",
            emptyList<String>(),
            counter.statuses,
        )
        assertFalse(
            "the destroyed session's audio file was left on disk",
            file.exists(),
        )
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
     * Settings with no usable token, so the upload ladder stops at "not
     * configured" rather than opening a socket. Constructed directly rather
     * than via `SttSettings.create`, which needs an `AndroidKeyStore`
     * Robolectric does not have — the same seam
     * [DictationSessionUploadGateTest] uses.
     */
    private fun settings(): SttSettings {
        val prefs = ctx.getSharedPreferences("mic-owner-test", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        val cipher = object : SecretCipher {
            override fun encrypt(plaintext: String): String = plaintext
            override fun decrypt(blob: String): String? = null
        }
        return SttSettings(prefs, TokenStore(prefs, cipher))
    }

    private fun grantMic() {
        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    private fun statusOf(activity: DictateActivity): String =
        activity.findViewById<TextView>(R.id.status_text).text.toString()

    private fun captureFieldOf(session: DictationSession): Any? =
        readField(session, DictationSession::class.java, "capture")

    private fun sessionFieldOf(activity: DictateActivity): Any? =
        readField(activity, DictateActivity::class.java, "session")

    private fun readField(target: Any, owner: Class<*>, name: String): Any? =
        owner.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun setField(target: Any, name: String, value: Any?) {
        DictateActivity::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
            .set(target, value)
    }

    private fun invoke(target: Any, name: String) {
        target.javaClass.getDeclaredMethod(name)
            .apply { isAccessible = true }
            .invoke(target)
    }

    /**
     * The session's own `MicCapture.Listener` — the one real route a recording
     * uses to reach the callbacks. A fixture invented here would be asserting
     * against itself.
     */
    private fun micListenerOf(session: DictationSession): MicCapture.Listener {
        val cls = Class.forName("dev.zacx.dicta.DictationSession\$MicListener")
        val ctor = cls.getDeclaredConstructor(DictationSession::class.java)
            .apply { isAccessible = true }
        return ctor.newInstance(session) as MicCapture.Listener
    }
}
