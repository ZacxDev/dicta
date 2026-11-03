package dev.zacx.dicta

import android.Manifest
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import org.junit.After
import org.junit.Assert.assertEquals
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
 * The long-press **gesture**, the drag clamp and the outside-touch flag, driven
 * through the real [BubbleService] with real `MotionEvent`s.
 *
 * ## 🔴 This file WAS `BubbleHistoryRevealTest` and is most of the way deleted
 *
 * It held 24 tests about a `ListView` of history chips inside the bubble's
 * overlay window. That list is gone — the reveal is two icon buttons, and the
 * history list, its search field and its scrolling live in
 * [HistoryPickerActivity]. **Thirteen of its tests were deleted rather than
 * adapted**, because each asserted a property of a surface that no longer
 * exists, and a test asserting a deleted surface is how a suite grows
 * green-but-meaningless:
 *
 *  * `holdingTheBubbleRevealsOneChipPerRecentDictation`,
 *    `theChipsCarryTheirLabelAndSayWhatTappingThemDoes`,
 *    `aRevealWithNoHistoryStillShowsSomething`,
 *    `theRevealGoesInsideTheExistingWindowAndNotASecondOne` — the chips
 *    themselves. The last one's claim survives, re-made about the buttons, in
 *    [BubbleHistoryButtonRevealTest] and `BubbleSnipRevealTest`.
 *  * `theWindowGrowsByTheChipsONSCREENNotByEveryItemTheListHolds`,
 *    `allTheOfferedItemsAreScrollableEvenThoughOnlyFourAreTall`,
 *    `theRevealAsksForMoreThanItCanShow` — `REVEAL_SLOTS`, `chipBlockPx`,
 *    `revealRowMarginPx` and `REVEAL_MAX_ITEMS`, all deleted. The *limit* claim
 *    is re-made against the store in
 *    `HistoryPickerActivityTest.thePickerAsksTheStoreForEverythingRetentionKeeps`.
 *  * `arecycledChipCarriesTheNewItemsIdAndNotThePreviousOnes`,
 *    `theEmptyStateRowIsNotClickableEvenWhenItRecyclesARealChip` — the
 *    recycling hazard, re-made about the picker's rows in
 *    `HistoryPickerActivityTest.aRecycledRowCarriesTheNewDictationAndNotThePreviousOne`.
 *  * `aRevealDismissedBeforeItsLoadLandsNeverAppears` — the async race. 🔴 It
 *    is deleted because the DEFECT IS GONE BY CONSTRUCTION, not because it
 *    became inconvenient: the reveal no longer loads anything, so there is no
 *    late callback to guard against. See `BubbleService.history`.
 *  * `revealingTheHistoryDoesNotShoveTheBubbleDownTheScreen`,
 *    `dismissingTheRowAfterADragLeavesTheBubbleWhereTheUserDraggedIt`,
 *    `aRevealNearTheTopEdgeDropsTheBubbleOnceAndThenStopsMoving` — the `y`
 *    compensation and the drag-then-dismiss teleport it caused. All three are
 *    **unnecessary** rather than changed: nothing is above the bubble any more,
 *    so `showActions` performs no `y` arithmetic at all and there is no shift
 *    to get wrong. [theRevealGrowsTheWindowDownwardAndDoesNotMoveTheBubble]
 *    pins that, from both sides.
 *  * `aDragWithTheHistoryOpenKeepsTheBOTTOMMOSTChipOnScreen` — the clamp with
 *    the reveal open. Re-made per button, at its full two-button height, in
 *    [BubbleHistoryButtonRevealTest] and `BubbleSnipRevealTest`.
 *
 * ## What is left here, and what it is evidence for
 *
 * The gesture *decision* is `BubbleGestureTest`'s and is pure. What is added
 * here is the **wiring**: that the service posts a long-press timer at all,
 * that the timer is cancelled by a drag and by `ACTION_CANCEL`, that the clamp
 * is actually called from the drag and from `onConfigurationChanged` with the
 * *current* window height, and that `FLAG_WATCH_OUTSIDE_TOUCH` is armed only
 * while there is something to dismiss.
 *
 * 🔴 It is **not** evidence that the reveal stays out of screenshots. That is
 * `OverlayVisibility`'s effect on `params.alpha` during a MediaProjection
 * capture, which Robolectric cannot observe at all, and it is the regression
 * class that shipped once already (PR #4). Only a device screenshot taken with
 * the buttons on screen speaks to it.
 *
 * Nor is it evidence about timing on a real hand. 400 ms is exercised here by
 * advancing a paused looper; whether a thumb's drag produces an `ACTION_MOVE`
 * past slop before that timer fires at the speeds a hand actually moves is a
 * device check.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-port-xxhdpi")
class BubbleRevealGestureTest {

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

    private fun start(): BubbleService {
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
        val c = Robolectric.buildService(BubbleService::class.java)
        controller = c
        c.get().sessionFactory = { fake }
        // 🔴 No history seams. The reveal reads nothing — it shows two buttons
        // — which is why `historyProvider` and `historyExecutor` are deleted
        // from the service and why "long press, then assert" has no race left
        // in it to remove.
        return c.create().get()
    }

    private fun <T> field(service: BubbleService, name: String): T {
        val f = BubbleService::class.java.getDeclaredField(name)
        f.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return f.get(service) as T
    }

    private fun bubble(service: BubbleService): View =
        requireNotNull(field<View?>(service, "bubble")) { "no bubble view" }

    private fun history(service: BubbleService): ImageButton =
        requireNotNull(field<ImageButton?>(service, "history")) { "no history button" }

    private fun snip(service: BubbleService): ImageButton =
        requireNotNull(field<ImageButton?>(service, "snip")) { "no snip button" }

    private fun rootOf(service: BubbleService): LinearLayout =
        requireNotNull(field<LinearLayout?>(service, "root")) { "no root view" }

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

    // --- the reveal is two buttons and no list -------------------------------

    @Test
    fun holdingTheBubbleRevealsBothActionButtonsAndNoList() {
        val service = start()
        assertEquals(
            "precondition: the history button is away at rest",
            View.GONE,
            history(service).visibility,
        )
        assertEquals(
            "precondition: the snip button is away at rest",
            View.GONE,
            snip(service).visibility,
        )

        longPress(service)

        assertEquals(View.VISIBLE, history(service).visibility)
        assertEquals(View.VISIBLE, snip(service).visibility)
        assertEquals(
            "a long press started a recording — it must not; the tap is the " +
                "control that records",
            0,
            fake.starts,
        )
        assertEquals(
            "a long press started an activity. The reveal is two buttons " +
                "attached to the bubble; opening a picker is what a BUTTON does",
            null,
            shadowOf(service).nextStartedActivity,
        )

        // 🔴 And no list of any kind inside the window. Asserted as a property
        // of the view TREE rather than of a named field, because the field is
        // what was deleted: a `historyRow` reference would not compile, so it
        // could not catch a list added back under a different name.
        assertEquals(
            "the bubble's window contains an AdapterView again. The chip list " +
                "was deleted deliberately: it had to be bounded, recycled and " +
                "folded into the clamp arithmetic, and its async load could " +
                "leave a permanent row with FLAG_WATCH_OUTSIDE_TOUCH armed " +
                "device-wide (#17 audit round 1). A list belongs in " +
                "HistoryPickerActivity.",
            emptyList<String>(),
            adapterViewsIn(rootOf(service)),
        )
    }

    private fun adapterViewsIn(group: android.view.ViewGroup): List<String> {
        val found = mutableListOf<String>()
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child is android.widget.AdapterView<*>) found += child.javaClass.simpleName
            if (child is android.view.ViewGroup) found += adapterViewsIn(child)
        }
        return found
    }

    @Test
    fun theRevealGrowsTheWindowDownwardAndDoesNotMoveTheBubble() {
        // 🔴 MEASURES the container, rather than trusting the two block
        // fields. This is the widened descendant of
        // `theWindowShiftMatchesTheHeightTheRowActuallyAdds`, which computed
        // both sides independently so that an understated height was visible;
        // the two sides here are (a) the container's own measured growth and
        // (b) the sum of the two dp-derived blocks the clamp uses.
        //
        // 🔴 And `y` MUST NOT MOVE. With Gravity.TOP the window's top edge is
        // pinned at `y`, so a child added BELOW the bubble cannot move the
        // bubble — which is why the whole `params.y -= revealBlockPx`
        // compensation is deleted. A reintroduced shift fails this, and that is
        // the mutation the three deleted `y` tests used to cover between them.
        val service = start()
        val root = rootOf(service)
        val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

        root.measure(spec, spec)
        val collapsed = root.measuredHeight
        val yBefore = params(service).y

        longPress(service)
        root.measure(spec, spec)
        val expanded = root.measuredHeight

        val historyBlock: Int = field(service, "historyBlockPx")
        val snipBlock: Int = field(service, "snipBlockPx")
        assertTrue("precondition: the history block has a height", historyBlock > 0)
        assertTrue("precondition: the snip block has a height", snipBlock > 0)

        assertEquals(
            "the container grew by ${expanded - collapsed}px while the clamp " +
                "was told the reveal adds ${historyBlock + snipBlock}px. One of " +
                "the two blocks is understated, which is what tells clampY the " +
                "window is shorter than it is and leaves the lower button " +
                "off-screen on a bubble near the bottom edge.",
            historyBlock + snipBlock,
            expanded - collapsed,
        )
        assertEquals(
            "revealing the buttons moved the window origin by " +
                "${yBefore - params(service).y}px. Nothing is above the bubble " +
                "any more, so `y` must not move at all — a shift here is the " +
                "deleted compensation coming back, and it shoves the bubble out " +
                "from under the finger that is still holding it.",
            yBefore,
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
        assertEquals(View.GONE, history(service).visibility)
        assertEquals(View.GONE, snip(service).visibility)
    }

    @Test
    fun aSlowDragRepositionsTheBubbleAndRevealsNothing() {
        // 🔴 The 600 ms drag: longer than the 400 ms threshold, so a wiring
        // that armed the timer and never cancelled it would reveal the
        // buttons on top of the reposition.
        val service = start()
        val v = bubble(service)
        val before = params(service).y

        down(v)
        moveTo(v, 10f, 400f)
        idle(600)
        up(v)
        idle(0)

        assertEquals(
            "a 600 ms drag revealed the action buttons — the pending long " +
                "press was not cancelled when the finger passed slop",
            View.GONE,
            history(service).visibility,
        )
        assertEquals(View.GONE, snip(service).visibility)
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
            "the action buttons appeared after the gesture was cancelled",
            View.GONE,
            history(service).visibility,
        )
        assertEquals(View.GONE, snip(service).visibility)
        assertEquals(0, fake.starts)
    }

    @Test
    fun tappingTheBubbleAfterARevealPutsTheButtonsAway() {
        val service = start()
        longPress(service)
        assertEquals(View.VISIBLE, history(service).visibility)

        val v = bubble(service)
        down(v)
        idle(50)
        up(v)
        idle(0)

        assertEquals(
            "the history button is still on screen over a live recording",
            View.GONE,
            history(service).visibility,
        )
        assertEquals(
            "the snip button is still on screen over a live recording",
            View.GONE,
            snip(service).visibility,
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

    // --- the outside-touch flag ----------------------------------------------

    private val watchOutside = WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

    @Test
    fun theOutsideTouchFlagIsArmedOnlyWhileTheButtonsAreUp() {
        // 🔴 This and the two below exist because #17's own commit message
        // claimed the flag toggle "has no JVM path". It does — this harness
        // already reaches `params` by reflection. Audit round 1, F6.
        val service = start()
        assertEquals(
            "armed before any reveal", 0, params(service).flags and watchOutside,
        )

        longPress(service)
        assertTrue(
            "the buttons are up but the window is not watching outside " +
                "touches, so nothing can dismiss them",
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
    fun anOutsideTouchOnTheWindowRootDismissesTheButtons() {
        val service = start()
        longPress(service)
        assertEquals(View.VISIBLE, history(service).visibility)

        val t = SystemClock.uptimeMillis()
        rootOf(service).dispatchTouchEvent(
            MotionEvent.obtain(t, t, MotionEvent.ACTION_OUTSIDE, 0f, 0f, 0)
        )
        idle(0)

        assertEquals(
            "an outside touch did not close the history button",
            View.GONE,
            history(service).visibility,
        )
        assertEquals(
            "an outside touch did not close the snip button",
            View.GONE,
            snip(service).visibility,
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
