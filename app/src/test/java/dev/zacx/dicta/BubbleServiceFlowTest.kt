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

/**
 * The bubble's two-tap flow driven through the real [BubbleService].
 *
 * [BubbleTapOpensOverlayTest] owns the one assertion that had to compile
 * against the pre-change tree (tap 1 opens nothing). This file is the other
 * half, and it needs the session seam: the real [DictationSession] wants an
 * Android keystore and an `AudioRecord`, neither of which Robolectric provides,
 * so without a stand-in the service's capture would simply fail to start and
 * every tap would be another "first tap" — making a second-tap test green for
 * entirely the wrong reason.
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
        // Levels are what the silence gate reads; without one above the floor
        // tap 2 would (correctly) be gated and open nothing.
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
            "cancel must NOT submit — the level fed above is well over the " +
                "silence floor, so this is the case where an upload would " +
                "otherwise have happened",
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

    /** The session callbacks the service hands a real [DictationSession]. */
    private fun callbacksOf(service: BubbleService): DictationSession.Callbacks {
        val method = BubbleService::class.java
            .getDeclaredMethod("serviceCallbacks")
            .apply { isAccessible = true }
        return method.invoke(service) as DictationSession.Callbacks
    }

    /**
     * Pushes a level sample in through the session callbacks the service hands
     * the session — the same route a real [DictationSession] uses, so the gate
     * and the pulse are being fed from the one source rather than from a
     * fixture invented here.
     */
    private fun feedLevel(service: BubbleService, level: Double) {
        callbacksOf(service).onLevel(level)
    }
}
