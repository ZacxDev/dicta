package dev.zacx.dicta

import android.Manifest
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import java.time.Duration

/**
 * The long-press reveal, the drag clamp and the history launch, driven through
 * the real [BubbleService] with real `MotionEvent`s.
 *
 * ## What this file is evidence for, and what it is not
 *
 * The gesture *decision* is [BubbleGestureTest]'s and is pure. What is added
 * here is the **wiring**: that the service posts a timer at all, that the
 * timer is cancelled by a drag and by `ACTION_CANCEL`, that the reveal lands
 * inside the one existing window, that the chip launches through
 * [DictateLaunch], and that the clamp is actually called from the drag and
 * from `onConfigurationChanged` with the *current* window height.
 *
 * 🔴 It is **not** evidence that the reveal stays out of screenshots. That is
 * `OverlayVisibility`'s effect on `params.alpha` during a MediaProjection
 * capture, which Robolectric cannot observe at all, and it is the regression
 * class that shipped once already (PR #4). Only a device screenshot taken with
 * the row on screen speaks to it — checklist item G4.
 *
 * Nor is it evidence about timing on a real hand. 400 ms is exercised here by
 * advancing a paused looper; whether a thumb's drag produces an `ACTION_MOVE`
 * past slop before that timer fires at the speeds a hand actually moves is
 * G2/G5.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-port-xxhdpi")
class BubbleHistoryRevealTest {

    private var controller: org.robolectric.android.controller.ServiceController<BubbleService>? =
        null

    /** A session that records nothing; the bubble's taps must still work. */
    private class FakeSession : BubbleService.SessionHandle {
        var starts = 0
        override fun start(): Boolean { starts++; return true }
        override fun submit() = Unit
        override fun discard() = Unit
        override fun destroy() = Unit
    }

    private val fake = FakeSession()

    @After
    fun tearDown() {
        runCatching { controller?.destroy() }
        OverlayVisibility.clearForTest()
        BubbleSessionBus.clearForTest()
        MicrophoneOwner.clearForTest()
    }

    // --- harness -------------------------------------------------------------

    private fun start(items: List<DictationHistoryStore.Item> = twoItems()): BubbleService {
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
        val c = Robolectric.buildService(BubbleService::class.java)
        controller = c
        c.get().sessionFactory = { fake }
        c.get().historyProvider = { limit -> items.take(limit) }
        // 🔴 Synchronous, so "long press, then assert" has no race in it. The
        // production executor is a real worker thread; what this seam removes
        // from the test is the thread, not the call.
        c.get().historyExecutor = java.util.concurrent.Executor { it.run() }
        return c.create().get()
    }

    private fun item(id: String, text: String, agoMs: Long) = DictationHistoryStore.Item(
        id = id,
        createdAtMs = System.currentTimeMillis() - agoMs,
        audioBytes = 1234L,
        audioFile = "$id.ogg",
        text = text,
        fromRemote = true,
        audioSeconds = 2.5,
    )

    private fun twoItems() = listOf(
        item("alpha", "the quick brown fox", 2 * 60 * 60_000L),
        item("bravo", "another saved dictation", 3 * 24 * 60 * 60_000L),
    )

    private fun <T> field(service: BubbleService, name: String): T {
        val f = BubbleService::class.java.getDeclaredField(name)
        f.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return f.get(service) as T
    }

    private fun bubble(service: BubbleService): View =
        requireNotNull(field<View?>(service, "bubble")) { "no bubble view" }

    private fun row(service: BubbleService): LinearLayout =
        requireNotNull(field<LinearLayout?>(service, "historyRow")) { "no history row" }

    private fun params(service: BubbleService): WindowManager.LayoutParams =
        field(service, "params")

    private fun idle(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    private fun down(v: View) {
        val t = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(
            MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        )
    }

    private fun moveTo(v: View, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(
            MotionEvent.obtain(t, t, MotionEvent.ACTION_MOVE, x, y, 0)
        )
    }

    private fun up(v: View) {
        val t = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_UP, 10f, 10f, 0))
    }

    private fun cancel(v: View) {
        val t = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(
            MotionEvent.obtain(t, t, MotionEvent.ACTION_CANCEL, 10f, 10f, 0)
        )
    }

    private fun longPress(service: BubbleService) {
        val v = bubble(service)
        down(v)
        idle(BubbleGesture.LONG_PRESS_MS + 50)
        up(v)
        idle(0)
    }

    private val screenHeight: Int
        get() = RuntimeEnvironment.getApplication().resources.displayMetrics.heightPixels

    private fun nextActivity(service: BubbleService) =
        shadowOf(service).nextStartedActivity

    // --- the reveal ----------------------------------------------------------

    @Test
    fun holdingTheBubbleRevealsOneChipPerRecentDictation() {
        val service = start()
        assertEquals(
            "precondition: the row is away at rest",
            View.GONE,
            row(service).visibility,
        )

        longPress(service)

        assertEquals(View.VISIBLE, row(service).visibility)
        assertEquals(
            "the reveal must show one chip per recent dictation",
            2,
            row(service).childCount,
        )
        assertEquals(
            "a long press started a recording — it must not; the tap is the " +
                "control that records",
            0,
            fake.starts,
        )
        assertNull(
            "a long press opened the card. The reveal is attached to the " +
                "bubble; opening the overlay is what a CHIP does",
            nextActivity(service),
        )
    }

    @Test
    fun theChipsCarryTheirLabelAndSayWhatTappingThemDoes() {
        val service = start()
        longPress(service)

        val chip = row(service).getChildAt(0) as android.widget.TextView
        assertTrue(
            "the chip's label does not contain the transcript's opening " +
                "words: \"${chip.text}\"",
            chip.text.toString().contains("the quick brown"),
        )
        assertTrue(
            "the chip has no contentDescription — its label is a transcript, " +
                "which a screen reader announces as a piece of text rather " +
                "than as a button",
            !chip.contentDescription.isNullOrBlank(),
        )
        assertTrue(
            "the chip's contentDescription is the bare label, so it says what " +
                "the item IS and never what tapping it DOES: " +
                "\"${chip.contentDescription}\"",
            chip.contentDescription.toString() != chip.text.toString(),
        )
        assertTrue("the chip is not clickable", chip.hasOnClickListeners())
    }

    @Test
    fun aRevealWithNoHistoryStillShowsSomething() {
        val service = start(items = emptyList())
        longPress(service)

        assertEquals(View.VISIBLE, row(service).visibility)
        assertEquals(1, row(service).childCount)
        val label = row(service).getChildAt(0) as android.widget.TextView
        assertEquals(
            RuntimeEnvironment.getApplication().getString(R.string.bubble_history_empty),
            label.text.toString(),
        )
        assertTrue(
            "the empty-state label accepts taps and does nothing, which is " +
                "worse than a caption",
            !label.hasOnClickListeners(),
        )
    }

    @Test
    fun theRevealGoesInsideTheExistingWindowAndNotASecondOne() {
        // 🔴 The §2.7 claim, as far as the JVM can make it: the row's parent
        // is the one view this service hands to the WindowManager.
        // OverlayWindowLedgerTest guards the textual half (no second
        // addView), and G3/G4 on the device guard the half that matters.
        val service = start()
        longPress(service)
        assertSame(
            "the history row is not a child of the bubble's own window, so it " +
                "is a second overlay — which needs its own OverlayVisibility " +
                "sink, and forgetting one is how Dicta's UI ended up inside " +
                "the user's screenshots (PR #4)",
            field<ViewGroup?>(service, "root"),
            row(service).parent,
        )
    }

    @Test
    fun revealingTheHistoryDoesNotShoveTheBubbleDownTheScreen() {
        // With Gravity.TOP the window's TOP edge is pinned at y, so adding
        // children above the bubble moves the bubble down by their height —
        // out from under the finger that is still holding it. The same
        // compensation showCancel does, for the same reason.
        val service = start()
        val before = params(service).y

        longPress(service)
        val reveal: Int = field(service, "revealBlockPx")
        assertTrue("precondition: the reveal has a height", reveal > 0)
        assertEquals(
            "revealing the row moved the bubble ${before - params(service).y}px " +
                "instead of ${reveal}px, so the control the user is holding " +
                "jumps when the chips appear",
            before - reveal,
            params(service).y,
        )

        // ...and putting the row away puts the bubble back exactly where it was.
        val v = bubble(service)
        down(v)
        idle(50)
        up(v)
        idle(0)
        assertEquals(
            "the bubble did not return to where it started after the row was " +
                "dismissed, so every reveal walks it up the screen",
            before,
            params(service).y,
        )
    }

    // --- the gesture wiring --------------------------------------------------

    @Test
    fun aQuickTapStillStartsARecordingAndRevealsNothing() {
        // Regression: the long press is an addition to this control, not a
        // replacement for the tap that is the whole app.
        val service = start()
        val v = bubble(service)
        down(v)
        idle(50)
        up(v)
        idle(0)

        assertEquals("a quick tap no longer starts a recording", 1, fake.starts)
        assertEquals(View.GONE, row(service).visibility)
    }

    @Test
    fun aSlowDragRepositionsTheBubbleAndRevealsNothing() {
        // 🔴 The 600 ms drag: longer than the 400 ms threshold, so a wiring
        // that armed the timer and never cancelled it would reveal the
        // history on top of the reposition.
        val service = start()
        val v = bubble(service)
        val before = params(service).y

        down(v)
        moveTo(v, 10f, 400f)
        idle(600)
        up(v)
        idle(0)

        assertEquals(
            "a 600 ms drag revealed the history — the pending long press was " +
                "not cancelled when the finger passed slop",
            View.GONE,
            row(service).visibility,
        )
        assertEquals("a drag started a recording", 0, fake.starts)
        assertTrue(
            "the drag did not move the window at all, so this test would also " +
                "pass against a listener that ignored ACTION_MOVE entirely",
            params(service).y != before,
        )
    }

    @Test
    fun aCancelledPressRevealsNothingAfterTheFingerIsGone() {
        // ACTION_CANCEL used to fall through to `return false`. The window
        // manager takes gestures away for reasons the app does not control.
        val service = start()
        val v = bubble(service)
        down(v)
        idle(50)
        cancel(v)
        idle(600)

        assertEquals(
            "the history row appeared after the gesture was cancelled",
            View.GONE,
            row(service).visibility,
        )
        assertEquals(0, fake.starts)
    }

    // --- the launch ----------------------------------------------------------

    @Test
    fun tappingAChipOpensTheCardOnThatDictationThroughDictateLaunch() {
        val service = start()
        longPress(service)
        row(service).getChildAt(1).performClick()

        val intent = nextActivity(service)
        assertNotNull("tapping a chip started nothing", intent)
        assertEquals(
            DictateActivity::class.java.name,
            intent!!.component?.className,
        )
        assertEquals(
            "the chip launched the wrong item",
            "bravo",
            intent.getStringExtra(DictateLaunch.EXTRA_HISTORY_ID),
        )
        assertTrue(
            "the chip's intent carries no FLAG_ACTIVITY_NEW_TASK, so it was " +
                "hand-built rather than routed through DictateLaunch — which " +
                "is issue #11 exactly",
            intent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )
        assertEquals(
            "the row stayed on screen behind the card it opened",
            View.GONE,
            row(service).visibility,
        )
    }

    @Test
    fun tappingTheBubbleAfterARevealPutsTheRowAway() {
        val service = start()
        longPress(service)
        assertEquals(View.VISIBLE, row(service).visibility)

        val v = bubble(service)
        down(v)
        idle(50)
        up(v)
        idle(0)

        assertEquals(
            "the history chips are still on screen over a live recording",
            View.GONE,
            row(service).visibility,
        )
        assertEquals(1, fake.starts)
    }

    // --- the clamp (#14 item 5) ----------------------------------------------

    @Test
    fun aDragCannotTakeTheWindowOffTheBottomOfTheDisplay() {
        val service = start()
        val halo: Int = field(service, "haloSizePx")
        val v = bubble(service)

        down(v)
        moveTo(v, 10f, 9000f)
        up(v)
        idle(0)

        assertTrue(
            "the window was dragged to y=${params(service).y} on a " +
                "${screenHeight}px display with a ${halo}px window, so the " +
                "bubble is off-screen and a recording started from it could " +
                "be neither stopped nor cancelled",
            params(service).y + halo <= screenHeight,
        )
        assertTrue("the window was dragged above the display", params(service).y >= 0)
    }

    @Test
    fun aDragWithTheHistoryOpenKeepsTheBOTTOMMOSTChipOnScreen() {
        // 🔴 The assertion that kills "the clamp forgot the reveal's height".
        // With the row shown the window is several chips taller, and a clamp
        // that measured only the bubble would leave the chips below the
        // display: a row of controls the user can see the top pixel of and
        // cannot tap — the same unreachable-control defect the cancel flip
        // exists to prevent, arrived at from the other end.
        val service = start()
        longPress(service)
        val halo: Int = field(service, "haloSizePx")
        val reveal: Int = field(service, "revealBlockPx")
        assertTrue("precondition: the reveal has a height", reveal > 0)

        val v = bubble(service)
        down(v)
        moveTo(v, 10f, 9000f)
        up(v)
        idle(0)

        assertTrue(
            "with the reveal open the window is ${halo + reveal}px tall on a " +
                "${screenHeight}px display, but it was clamped to " +
                "y=${params(service).y} — which is where it would land if the " +
                "clamp had been given the bubble's height alone",
            params(service).y + halo + reveal <= screenHeight,
        )
    }

    @Test
    fun aConfigurationChangePullsAnOffScreenWindowBackIntoView() {
        // 🔴 The rotate case, which is the actual bug rather than a hardening:
        // drag to the portrait bottom, rotate to landscape, and the whole
        // window — bubble AND cancel — is below the visible area. Simulated
        // here by putting the window where a rotation would leave it and
        // delivering the callback the platform delivers.
        val service = start()
        val halo: Int = field(service, "haloSizePx")
        params(service).y = screenHeight * 2

        service.onConfigurationChanged(
            RuntimeEnvironment.getApplication().resources.configuration
        )

        assertTrue(
            "onConfigurationChanged left the window at y=${params(service).y} " +
                "on a ${screenHeight}px display — an open microphone with no " +
                "reachable control",
            params(service).y + halo <= screenHeight,
        )
    }

    @Test
    fun aConfigurationChangeLeavesAnInBoundsWindowWhereItIs() {
        // 🔴 The positive control for the test above: a clamp that slammed the
        // window to 0 on every configuration change would pass it, and would
        // also move the user's bubble on every rotation, keyboard open and
        // font-scale change.
        val service = start()
        params(service).y = 300

        service.onConfigurationChanged(
            RuntimeEnvironment.getApplication().resources.configuration
        )

        assertEquals(300, params(service).y)
    }

    // --- audit round 1 of #17: the async race, the clamp asymmetry, the
    // --- understated height, and the shell-side outside-touch wiring -------

    private val watchOutside = WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

    private fun rootOf(service: BubbleService): LinearLayout =
        requireNotNull(field<LinearLayout?>(service, "root")) { "no root view" }

    /** Starts a service whose history load is QUEUED, not run. */
    private fun startDeferred(
        queue: MutableList<Runnable>,
        items: List<DictationHistoryStore.Item> = twoItems(),
    ): BubbleService {
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
        val c = Robolectric.buildService(BubbleService::class.java)
        controller = c
        c.get().sessionFactory = { fake }
        c.get().historyProvider = { limit -> items.take(limit) }
        c.get().historyExecutor = java.util.concurrent.Executor { queue.add(it) }
        return c.create().get()
    }

    @Test
    fun aRevealDismissedBeforeItsLoadLandsNeverAppears() {
        // 🔴 The blocker. The store read is async; if the reveal is dismissed
        // while it is in flight, the late callback used to show the row
        // anyway — and that row was PERMANENT, because the machine had
        // already cleared `historyRevealed`, so nothing would ever emit
        // HideHistory for it.
        val queue = mutableListOf<Runnable>()
        val service = startDeferred(queue)

        longPress(service)
        assertEquals("precondition: the load is queued, not run", 1, queue.size)
        assertEquals(
            "precondition: nothing is shown yet", View.GONE, row(service).visibility,
        )

        bubble(service).performClick()   // dismiss while the load is in flight
        idle(0)

        queue.forEach { it.run() }
        idle(0)

        assertEquals(
            "the row appeared AFTER it was dismissed — and nothing can close " +
                "it, because the machine already cleared its reveal flag",
            View.GONE,
            row(service).visibility,
        )
        assertEquals(
            "FLAG_WATCH_OUTSIDE_TOUCH was left armed device-wide by a row " +
                "that is not even on screen",
            0,
            params(service).flags and watchOutside,
        )
    }

    @Test
    fun aRevealAndDismissNearTheTopEdgeLeavesTheBubbleWhereItStarted() {
        // 🔴 y is asserted against the value it STARTED at — an independent
        // fact — not against the service's own revealBlockPx field. Deriving
        // the expectation from the implementation is what made the previous
        // round-trip test blind to this.
        val service = start()
        params(service).y = 10

        longPress(service)
        assertEquals("precondition: the row is up", View.VISIBLE, row(service).visibility)
        bubble(service).performClick()
        idle(0)

        assertEquals(
            "reveal+dismiss near the top edge moved the bubble: the clamp " +
                "absorbed part of the upward shift and the hide side gave " +
                "back the full amount",
            10,
            params(service).y,
        )
    }

    @Test
    fun theWindowShiftMatchesTheHeightTheRowActuallyAdds() {
        // 🔴 MEASURES the container both ways instead of trusting
        // revealBlockPx. The old guards reflected that field out and compared
        // it with itself, so an understated height was invisible to them.
        val service = start()
        val root = rootOf(service)
        val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

        root.measure(spec, spec)
        val collapsed = root.measuredHeight
        val before = params(service).y

        longPress(service)
        root.measure(spec, spec)
        val expanded = root.measuredHeight

        assertEquals(
            "the window moved by a different amount than the row actually " +
                "added, so the bubble shifts on reveal and clampY is told " +
                "the window is the wrong height",
            expanded - collapsed,
            before - params(service).y,
        )
    }

    @Test
    fun theOutsideTouchFlagIsArmedOnlyWhileTheRowIsUp() {
        // 🔴 This and the two below exist because #17's own commit message
        // claimed the flag toggle "has no JVM path". It does — this harness
        // already reaches `params` by reflection. Audit round 1, F6.
        val service = start()
        assertEquals(
            "armed before any reveal", 0, params(service).flags and watchOutside,
        )

        longPress(service)
        assertTrue(
            "the row is up but the window is not watching outside touches, " +
                "so nothing can dismiss it",
            params(service).flags and watchOutside != 0,
        )

        bubble(service).performClick()
        idle(0)
        assertEquals(
            "the flag survived the dismissal, so every touch on the device " +
                "keeps waking this service",
            0,
            params(service).flags and watchOutside,
        )
    }

    @Test
    fun anOutsideTouchOnTheWindowRootDismissesTheRow() {
        val service = start()
        longPress(service)
        assertEquals(View.VISIBLE, row(service).visibility)

        val t = SystemClock.uptimeMillis()
        rootOf(service).dispatchTouchEvent(
            MotionEvent.obtain(t, t, MotionEvent.ACTION_OUTSIDE, 0f, 0f, 0)
        )
        idle(0)

        assertEquals(
            "an outside touch did not close the row",
            View.GONE,
            row(service).visibility,
        )
    }

    @Test
    fun anOutsideTouchDoesNotStartARecording() {
        // The hazard if ACTION_OUTSIDE were wired to the tap path: a touch
        // anywhere on the device would begin a dictation.
        val service = start()
        longPress(service)
        val startsBefore = fake.starts

        val t = SystemClock.uptimeMillis()
        rootOf(service).dispatchTouchEvent(
            MotionEvent.obtain(t, t, MotionEvent.ACTION_OUTSIDE, 0f, 0f, 0)
        )
        idle(0)

        assertEquals(
            "an outside touch started a capture", startsBefore, fake.starts,
        )
    }
}
