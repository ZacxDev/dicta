package dev.zacx.dicta

import android.Manifest
import android.view.View
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowToast

/**
 * The bubble's two-tap flow driven through the real [BubbleService].
 *
 * [BubbleTapOpensOverlayTest] owns the one assertion that had to compile
 * against the pre-change tree (tap 1 opens nothing). This file is the other
 * half, and it needs the session seam: the real [DictationSession] wants an
 * `AudioRecord`, which Robolectric does not usefully provide, so without a
 * stand-in the service's capture would simply fail to start and every tap would
 * be another "first tap" — making a second-tap test green for entirely the
 * wrong reason.
 *
 * (This used to name an Android keystore as a second reason. It is not one:
 * `SttSettings.create` reaches no keystore at construction and succeeds here —
 * see [BubbleService.realSession].)
 *
 * What is pinned here is the *sequencing* the redesign changed: which tap
 * records, which tap opens the card, that cancel does neither, and that the
 * card is told not to start its own recording. The pixels — the pulse, the
 * window growing, where the cancel lands on screen — are device evidence and
 * are not asserted anywhere.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BubbleServiceFlowTest {

    private var controller: ServiceController<BubbleService>? = null

    /** Records what the service asked of the session. */
    private class FakeSession : BubbleService.SessionHandle {
        var starts = 0
        var submits = 0
        var discards = 0
        var destroys = 0
        var startResult = true

        override fun start(): Boolean {
            starts++
            return startResult
        }

        override fun submit() {
            submits++
        }

        override fun discard() {
            discards++
        }

        override fun destroy() {
            destroys++
        }
    }

    private val fake = FakeSession()

    @After
    fun tearDown() {
        runCatching { controller?.destroy() }
        OverlayVisibility.clearForTest()
        BubbleSessionBus.clearForTest()
        MicrophoneOwner.clearForTest()
    }

    private fun start(micGranted: Boolean = true): BubbleService {
        ShadowSettings.setCanDrawOverlays(true)
        if (micGranted) {
            shadowOf(RuntimeEnvironment.getApplication())
                .grantPermissions(Manifest.permission.RECORD_AUDIO)
        }
        val c = Robolectric.buildService(BubbleService::class.java)
        controller = c
        c.get().sessionFactory = { fake }
        return c.create().get()
    }

    private fun viewNamed(service: BubbleService, name: String): View? {
        val field = BubbleService::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(service) as View?
    }

    private fun bubble(service: BubbleService): View =
        requireNotNull(viewNamed(service, "bubble")) { "no bubble view" }

    private fun cancel(service: BubbleService): View =
        requireNotNull(viewNamed(service, "cancel")) { "no cancel view" }

    // --- the flow -----------------------------------------------------------

    @Test
    fun theFirstTapStartsCaptureAndTheSecondSubmitsAndOpensTheCard() {
        val service = start()
        bubble(service).performClick()

        assertEquals(
            "tap 1 must start exactly one capture",
            1,
            fake.starts,
        )
        assertNull(
            "tap 1 started an activity — the card must not appear until tap 2",
            shadowOf(service).nextStartedActivity,
        )

        // Past the debounce window. The machine's clock is the real one here,
        // so the recording has to actually be old enough — advancing
        // Robolectric's scheduler does not move System.currentTimeMillis.
        Thread.sleep(BubbleDictation.MIN_RECORDING_MS + 50)
        // A level sample on the way through. It no longer influences anything
        // the machine decides — it drives the pulse and the card's meter — but
        // feeding one keeps that path exercised, and pins that a sample arriving
        // mid-recording cannot disturb the tap sequencing asserted below.
        feedLevel(service, 0.37)

        bubble(service).performClick()

        assertEquals("tap 2 must submit the recording", 1, fake.submits)
        assertEquals("tap 2 must not discard it", 0, fake.discards)
        assertEquals("tap 2 must not start a second capture", 1, fake.starts)

        val started = shadowOf(service).nextStartedActivity
        assertNotNull("tap 2 must open the card", started)
        assertEquals(
            DictateActivity::class.java.name,
            started!!.component?.className,
        )
        assertTrue(
            "the card must be told a session is already in flight, or its " +
                "onCreate starts a SECOND DictationSession and Android feeds " +
                "one of the two exact zeros",
            started.getBooleanExtra(DictateLaunch.EXTRA_ATTACH_SESSION, false),
        )
    }

    @Test
    fun theCancelButtonAbortsWithoutSubmittingOrOpeningTheCard() {
        val service = start()
        bubble(service).performClick()
        feedLevel(service, 0.37)

        cancel(service).performClick()

        assertEquals("cancel must discard the recording", 1, fake.discards)
        assertEquals(
            "cancel must NOT submit — a stop tap here would have submitted, so " +
                "this is the case where an upload would otherwise have happened",
            0,
            fake.submits,
        )
        assertNull(
            "cancel must not open the card",
            shadowOf(service).nextStartedActivity,
        )
    }

    @Test
    fun theCancelButtonIsHiddenAtRestAndShownWhileRecording() {
        val service = start()
        assertEquals(
            "the cancel button must be GONE at rest: the window is " +
                "WRAP_CONTENT, and a visible button would keep the overlay " +
                "window tall enough to swallow taps meant for the app beneath",
            View.GONE,
            cancel(service).visibility,
        )

        bubble(service).performClick()
        assertEquals(
            "no cancel button appeared while recording, so an inline recording " +
                "cannot be aborted without submitting it",
            View.VISIBLE,
            cancel(service).visibility,
        )
    }

    @Test
    fun aDeniedMicrophoneOpensTheCardInsteadOfRecording() {
        val service = start(micGranted = false)
        bubble(service).performClick()

        assertEquals(
            "capture must not be attempted without RECORD_AUDIO",
            0,
            fake.starts,
        )
        val started = shadowOf(service).nextStartedActivity
        assertNotNull(
            "a Service cannot call requestPermissions, so a denied microphone " +
                "must hand the ask to DictateActivity",
            started,
        )
        assertEquals(
            DictateActivity::class.java.name,
            started!!.component?.className,
        )
        assertTrue(
            "the permission fallback must NOT carry the attach flag, or the " +
                "card will decline to start the dictation the user asked for",
            !started.getBooleanExtra(DictateLaunch.EXTRA_ATTACH_SESSION, false),
        )
    }

    @Test
    fun captureThatFailsToStartLeavesTheBubbleUsable() {
        fake.startResult = false
        val service = start()
        bubble(service).performClick()

        assertEquals(1, fake.starts)
        assertEquals(
            "a failed start must leave the cancel button hidden — showing it " +
                "would offer to cancel a recording that never began",
            View.GONE,
            cancel(service).visibility,
        )

        // And the next tap is a fresh attempt, not a "stop".
        fake.startResult = true
        bubble(service).performClick()
        assertEquals(2, fake.starts)
        assertEquals(0, fake.submits)
    }

    /**
     * 🔴 The executor leak. `DictationSession` holds a non-daemon
     * single-thread executor and shuts it down only in `destroy()`;
     * `DictateActivity` called that from `onDestroy`, but this service cannot,
     * because the submitted session deliberately OUTLIVES the tap — the upload
     * is still running when the card opens. Dropping the reference at submit
     * time leaked one live thread per recording, forever.
     */
    @Test
    fun aSubmittedRecordingIsEventuallyTornDownRatherThanLeaked() {
        val first = FakeSession()
        val second = FakeSession()
        val handed = mutableListOf<FakeSession>()
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
        val c = Robolectric.buildService(BubbleService::class.java)
        controller = c
        c.get().sessionFactory = {
            val s = if (handed.isEmpty()) first else second
            handed += s
            s
        }
        val service = c.create().get()

        bubble(service).performClick()
        Thread.sleep(BubbleDictation.MIN_RECORDING_MS + 50)
        feedLevel(service, 0.37)
        bubble(service).performClick()

        assertEquals(1, first.submits)
        assertEquals(
            "the submitted session must NOT be destroyed at submit time — its " +
                "upload is still running and tearing it down would cancel the " +
                "transcription the card is waiting for",
            0,
            first.destroys,
        )

        // Starting the next recording is where the previous one is reclaimed.
        Thread.sleep(BubbleDictation.TRANSCRIBE_GRACE_MS + 50)
        bubble(service).performClick()

        assertEquals(
            "the previously submitted session was never destroyed, so its " +
                "executor thread leaks — one per recording, for the life of " +
                "the process",
            1,
            first.destroys,
        )
        assertEquals("the new recording must have started", 1, second.starts)
    }

    @Test
    fun aDiscardedRecordingIsTornDownImmediately() {
        // Nothing is in flight for a discarded recording, so there is no reason
        // to hold its executor until the next tap.
        val service = start()
        bubble(service).performClick()
        feedLevel(service, 0.37)
        cancel(service).performClick()

        assertEquals(1, fake.discards)
        assertEquals("a discarded session's executor must be reclaimed", 1, fake.destroys)
    }

    /**
     * A FINAL transcript from the PHONE must settle the bubble too.
     *
     * The fallback ladder hands over the phone's transcript when the server
     * cannot be reached, and the first version of this gated on `fromRemote` —
     * which left the bubble stuck in `TRANSCRIBING` for the whole of a server
     * outage, i.e. for every recording until the service came back.
     */
    @Test
    fun aLocalFinalTranscriptAlsoReturnsTheBubbleToIdle() {
        val service = start()
        bubble(service).performClick()
        Thread.sleep(BubbleDictation.MIN_RECORDING_MS + 50)
        feedLevel(service, 0.37)
        bubble(service).performClick()
        assertEquals(1, fake.submits)

        callbacksOf(service).onTranscript(
            "what the phone heard", fromRemote = false, isFinal = true
        )
        shadowOf(android.os.Looper.getMainLooper()).idle()

        // Observable: the bubble is idle again, so the very next tap STARTS a
        // recording rather than being swallowed as a transcribing-state tap.
        bubble(service).performClick()
        assertEquals(
            "a phone-produced final transcript left the bubble in " +
                "TRANSCRIBING, so it is dead for the duration of a server outage",
            2,
            fake.starts,
        )
    }

    // --- finding 2: a dead session must not drive the live machine ----------

    /**
     * 🔴 The defect this PR shipped with, and the test it lacked.
     *
     * `serviceCallbacks()` carried no notion of which recording it belonged to,
     * and `DictationSession.destroy()` only drops messages **already queued** —
     * a worker blocked in `http.transcribe()` is blocked in a socket read,
     * which `shutdownNow`'s interrupt does not unblock. So:
     *
     * record #1 → stop (TRANSCRIBING, upload in flight) → wait past the 1.5 s
     * grace → tap (the documented wedge recovery) → `startRecording()` destroys
     * #1 and starts #2 → **#1's late transcript lands** → `TranscriptSettled` →
     * `toIdle()`.
     *
     * The machine is then IDLE while the microphone is open, and every
     * observable is wrong at once: the bubble announces `bubble_idle`, the pulse
     * ring and the cancel button are hidden (recording with no indicator and no
     * way to abort), the FGS is never demoted, and `onMaxDuration()` returns
     * `emptyList()` in IDLE so the two-minute cap is inert and the recording
     * never stops.
     *
     * Asserted through observables rather than by reading `machine.state`: the
     * cancel button stays on screen, and the next tap **submits** recording #2
     * instead of starting a third.
     */
    @Test
    fun aLateTranscriptFromADestroyedSessionDoesNotIdleTheLiveRecording() {
        val first = FakeSession()
        val second = FakeSession()
        val service = startWithSessions(first, second)

        // Recording #1, submitted.
        bubble(service).performClick()
        Thread.sleep(BubbleDictation.MIN_RECORDING_MS + 50)
        bubble(service).performClick()
        assertEquals(1, first.submits)

        // The callbacks object recording #1 was handed. Captured BEFORE the
        // next recording starts, which is exactly what a session whose upload
        // is still in flight is holding.
        val stale = callbacksOf(service)

        // Past the grace window: the documented wedge recovery. Recording #2.
        Thread.sleep(BubbleDictation.TRANSCRIBE_GRACE_MS + 50)
        bubble(service).performClick()
        assertEquals("recording #2 must have started", 1, second.starts)
        assertEquals(
            "the mic indicator must be up for recording #2",
            View.VISIBLE,
            cancel(service).visibility,
        )

        // #1's upload finally returns, long after it was destroyed.
        stale.onTranscript("the late transcript", fromRemote = true, isFinal = true)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(
            "a destroyed session's late transcript hid the cancel button of a " +
                "LIVE recording. The microphone is open with no indicator on " +
                "screen and no way to abort.",
            View.VISIBLE,
            cancel(service).visibility,
        )
        assertNotNull(
            "the live session was dropped while its AudioRecord is open — the " +
                "next tap then opens a SECOND capture and Android feeds one of " +
                "them exact zeros",
            sessionFieldOf(service),
        )

        // And the machine is still RECORDING: the next tap STOPS #2 rather
        // than starting a third capture.
        Thread.sleep(BubbleDictation.MIN_RECORDING_MS + 50)
        bubble(service).performClick()
        assertEquals(
            "the machine had gone IDLE, so the next tap started a THIRD " +
                "capture on top of the live one instead of stopping it",
            1,
            second.submits,
        )
        assertEquals("no third capture may be started", 1, second.starts)
    }

    /**
     * The same gate must not silence the session the open card is waiting for.
     *
     * A submitted recording's callbacks stay current until the *next* recording
     * starts — its transcript is the one thing the card exists to show. A
     * generation gate that cut it off would pass the test above and break every
     * transcript on the happy path.
     */
    @Test
    fun aSubmittedSessionsTranscriptStillSettlesTheBubble() {
        val service = start()
        bubble(service).performClick()
        Thread.sleep(BubbleDictation.MIN_RECORDING_MS + 50)
        bubble(service).performClick()
        assertEquals(1, fake.submits)

        callbacksOf(service).onTranscript("the real one", fromRemote = true, isFinal = true)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        // Observable: the bubble is idle, so the very next tap STARTS a
        // recording rather than being swallowed as a transcribing-state tap.
        bubble(service).performClick()
        assertEquals(
            "the submitted recording's own transcript was ignored as stale, so " +
                "the bubble never leaves TRANSCRIBING and the card never fills",
            2,
            fake.starts,
        )
    }

    /**
     * 🔴 `state == IDLE ⇒ session == null` — relied on by `startRecording()`
     * and previously enforced nowhere.
     *
     * A session still assigned in IDLE is one nothing on screen refers to and
     * nothing can stop: no pulse, no cancel, and the duration cap inert. The
     * state is injected here because the gate above makes it unreachable
     * through the public flow — which is the point: the invariant has to hold
     * even when something else has gone wrong.
     */
    @Test
    fun aSessionLeftAssignedWhileIdleIsReclaimed() {
        val service = start()
        val orphan = FakeSession()
        setSessionField(service, orphan)

        // Any event at all re-runs the invariant. CancelTapped in IDLE is a
        // no-op decision, so nothing but the invariant can explain the result.
        cancel(service).performClick()

        assertEquals(
            "a session was left assigned while the machine is IDLE. Its " +
                "AudioRecord is open, nothing on screen says so, and the " +
                "two-minute cap is inert because onMaxDuration() returns " +
                "emptyList() in IDLE — the recording never stops.",
            1,
            orphan.destroys,
        )
        assertNull(
            "the orphaned session is still assigned",
            sessionFieldOf(service),
        )
    }

    /**
     * 🔴 `startRecording()` must reclaim any existing `session` before
     * assigning. It used to overwrite it outright, losing the reference while
     * the `AudioRecord` was still open — two live captures, one leaked
     * non-daemon executor, and no error anywhere.
     */
    @Test
    fun startingARecordingReclaimsAnyStillAssignedSession() {
        val service = start()
        val orphan = FakeSession()
        setSessionField(service, orphan)

        bubble(service).performClick()

        assertEquals(
            "startRecording() overwrote a live session instead of reclaiming " +
                "it: two concurrent AudioRecords, which Android answers by " +
                "feeding one of them exact zeros",
            1,
            orphan.destroys,
        )
        assertEquals(
            "a reclaimed session must be DISCARDED, not submitted — nothing " +
                "on screen refers to it, so its transcript would have no card",
            1,
            orphan.discards,
        )
        assertEquals("the new recording must have started", 1, fake.starts)
    }

    // --- finding 3: a failed capture must be visible -------------------------

    /**
     * 🔴 `CaptureFailed` emitted no effects, so the failure was completely
     * silent: no toast, no notification change, and — because tap 1 no longer
     * opens the card — no status line either. With no usable encoder, with the
     * microphone held exclusively by a call, or when
     * `startForeground(TYPE_MICROPHONE)` is refused, the tap did **nothing at
     * all**, which is indistinguishable from the app being dead. Pre-redesign
     * it showed up in the card's status line by accident, because every tap
     * opened the card.
     *
     * 🔴 Also the only assertion that a refused capture **demotes** the
     * microphone-typed FGS. `startRecording` promotes *before* opening the mic,
     * so every path out that did not capture owes a demotion; without one the
     * device is left a foreground service claiming the microphone with nothing
     * capturing — the policy violation the promote-first ordering exists to
     * avoid, arrived at from the other side. That assertion used to live on the
     * deleted no-settings branch, which is why it is here now: this is the
     * promote-then-fail path that still exists.
     */
    @Test
    fun aCaptureThatCannotStartIsReportedToTheUser() {
        fake.startResult = false
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
        val c = Robolectric.buildService(BubbleService::class.java)
        controller = c
        c.get().sessionFactory = { fake }
        // Recorded, not just allowed — the demotion is the thing asserted.
        val promotions = mutableListOf<Boolean>()
        c.get().foregroundPromoter = { recording ->
            promotions += recording
            true
        }
        val service = c.create().get()
        ShadowToast.reset()

        bubble(service).performClick()

        assertEquals(
            "a failed capture produced no user-visible report. The tap did " +
                "nothing observable whatsoever, which reads as a dead app.",
            RuntimeEnvironment.getApplication()
                .getString(R.string.bubble_capture_failed),
            ShadowToast.getTextOfLatestToast(),
        )
        assertEquals(
            "the capture was never actually attempted, so this test says " +
                "nothing about the promote-then-fail path",
            1,
            fake.starts,
        )
        assertTrue(
            "the microphone-typed foreground promotion was never demoted " +
                "after the capture refused to start, so the device is left an " +
                "FGS claiming the mic with nothing capturing: $promotions",
            promotions.contains(true) && promotions.last() == false,
        )
    }

    /**
     * 🔴 The microphone held **by Dicta itself** must say so.
     *
     * One fixed string was toasted for every cause — *"the microphone may be in
     * use by a call or another app"* — so the one cause the user can actually
     * fix was reported as somebody else's fault, and `stt_mic_in_use` ("stop
     * that recording first"), which already existed and says what to do, was
     * never shown. The discriminator in production is `MicrophoneOwner`:
     * `DictationSession.start()` releases on all of its own failures, so a mic
     * still held after a refused start is held by another opener.
     */
    @Test
    fun aCaptureRefusedBecauseDictaItselfHoldsTheMicSaysThat() {
        fake.startResult = false
        val service = start()
        val otherOpener = Any()
        MicrophoneOwner.acquire(otherOpener)
        ShadowToast.reset()

        bubble(service).performClick()

        assertEquals(
            "the user was told another app had the microphone while DICTA " +
                "held it, and was given nothing to act on",
            RuntimeEnvironment.getApplication().getString(R.string.stt_mic_in_use),
            ShadowToast.getTextOfLatestToast(),
        )
    }

    /**
     * 🔴 A refused `startForeground(TYPE_MICROPHONE)` must not be reported as a
     * microphone problem: the microphone was never touched. Driven through
     * [BubbleService.foregroundPromoter] because Robolectric's
     * `startForeground` cannot be made to refuse — see that field.
     */
    @Test
    fun aRefusedForegroundPromotionSaysSoWithoutBlamingTheMicrophone() {
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
        val c = Robolectric.buildService(BubbleService::class.java)
        controller = c
        c.get().sessionFactory = { fake }
        // At rest the promotion succeeds (or onCreate stops the service); the
        // microphone-typed one is the one being refused.
        c.get().foregroundPromoter = { recording -> !recording }
        val service = c.create().get()
        ShadowToast.reset()

        bubble(service).performClick()

        assertEquals(
            "a refused foreground promotion was reported as the microphone " +
                "being busy, which it was not — the mic is never reached on " +
                "this path",
            RuntimeEnvironment.getApplication()
                .getString(R.string.bubble_foreground_denied),
            ShadowToast.getTextOfLatestToast(),
        )
        assertEquals(
            "the microphone was opened despite the refused promotion — the " +
                "promotion is supposed to happen FIRST so an FGS is never " +
                "capturing without the microphone type",
            0,
            fake.starts,
        )
    }

    /**
     * 🔴 Control for the failure-message tests above: every `Failure` must map
     * to a **distinct string**.
     *
     * Without this they could be asserting identical values and pass whatever
     * `messageFor` does — the same shape as the `MIN_RECORDING_MS = 0` fixture
     * that survived a green suite on this PR.
     *
     * 🔴 Enumerated from `Failure.values()` and the real `messageFor`, not from
     * a hand-written list of string resources. The hand-written version went
     * blind exactly once already on this PR: `CAPTURE_UNAVAILABLE` was split in
     * two, the no-settings cause shared the capture-unavailable string, and a
     * list with only three entries could not see the sharing. Driving the enum
     * means the next split is caught without anybody remembering to extend a
     * list.
     *
     * That split has since been **deleted** — the no-settings cause was
     * unreachable — so this now enumerates three members again, and it still
     * fails if any two of them come to share a string. Nothing to extend.
     */
    @Test
    fun theCaptureFailureMessagesAreDistinct() {
        val app = RuntimeEnvironment.getApplication()
        val service = start()
        val messages = BubbleDictation.Failure.values()
            .map { app.getString(service.messageFor(it)) }
        assertEquals(
            "two capture-failure causes map to the same string, so the tests " +
                "that tell them apart cannot see a wrong mapping",
            BubbleDictation.Failure.values().size,
            messages.toSet().size,
        )
        assertTrue(
            "a blank failure message is a silent failure with extra steps",
            messages.none { it.isBlank() },
        )
    }

    /**
     * Positive control for the toast harness: a *successful* capture must NOT
     * report a failure. Without this, the assertion above could be reading a
     * toast the service shows unconditionally.
     */
    @Test
    fun aCaptureThatStartsReportsNothing() {
        val service = start()
        ShadowToast.reset()

        bubble(service).performClick()

        assertNull(
            "a successful capture showed the failure toast, so the report is " +
                "unconditional and says nothing about the failure path",
            ShadowToast.getTextOfLatestToast(),
        )
    }

    // --- harness ------------------------------------------------------------

    private fun startWithSessions(vararg sessions: FakeSession): BubbleService {
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
        val queue = sessions.toMutableList()
        val c = Robolectric.buildService(BubbleService::class.java)
        controller = c
        c.get().sessionFactory = { queue.removeFirst() }
        return c.create().get()
    }

    private fun sessionField() = BubbleService::class.java
        .getDeclaredField("session")
        .apply { isAccessible = true }

    private fun sessionFieldOf(service: BubbleService): Any? =
        sessionField().get(service)

    private fun setSessionField(service: BubbleService, handle: BubbleService.SessionHandle?) {
        sessionField().set(service, handle)
    }

    /** The session callbacks the service hands a real [DictationSession]. */
    private fun callbacksOf(service: BubbleService): DictationSession.Callbacks {
        val method = BubbleService::class.java
            .getDeclaredMethod("serviceCallbacks")
            .apply { isAccessible = true }
        return method.invoke(service) as DictationSession.Callbacks
    }

    /**
     * Pushes a level sample in through the session callbacks the service hands
     * the session — the same route a real [DictationSession] uses, rather than
     * a fixture invented here. The sample reaches the bus and the pulse and
     * nothing else; [BubbleDictation] is deliberately not told about levels.
     */
    private fun feedLevel(service: BubbleService, level: Double) {
        callbacksOf(service).onLevel(level)
    }
}
