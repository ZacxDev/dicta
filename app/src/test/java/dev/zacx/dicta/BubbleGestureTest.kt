package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [BubbleGesture]: which of tap / long press / drag one touch sequence is.
 *
 * ## What these can and cannot establish
 *
 * Everything here is pure arithmetic over a synthetic event stream, so it is
 * real evidence about the **decision** and none at all about the gesture. That
 * a thumb at the speeds a hand actually moves produces an `ACTION_MOVE` past
 * slop before the 400 ms timer fires, and that 400 ms is distinguishable in
 * practice from a slow tap, are device claims (`#5` G2/G5) and are not made
 * anywhere in this file.
 *
 * ## The fixtures deliberately overshoot
 *
 * Times are 0 / 120 / 600 ms against a 400 ms threshold and travel is 0 / 4 /
 * 40 px against a slop of 12 — never a value sitting exactly on its own
 * boundary, except in the two tests whose *subject* is the boundary. A fixture
 * that lands on the boundary makes the guarded branch unreachable, and a
 * mutant inside it then survives for a reason that has nothing to do with the
 * code being right.
 */
class BubbleGestureTest {

    private val slop = 12

    private fun gesture() = BubbleGesture(longPressMs = THRESHOLD)

    // --- the three outcomes --------------------------------------------------

    @Test
    fun aPressReleasedBeforeTheThresholdIsATap() {
        val g = gesture()
        g.onDown(100f, 200f, 0L)
        assertEquals(
            "a quick press with no movement must still be the tap that starts " +
                "and stops a dictation — the long press is an addition to that " +
                "control, not a replacement for it",
            BubbleGesture.Decision.TAP,
            g.onUp(),
        )
    }

    @Test
    fun aPressHeldPastTheThresholdIsALongPress() {
        val g = gesture()
        g.onDown(100f, 200f, 0L)
        assertEquals(
            BubbleGesture.Decision.LONG_PRESS,
            g.onTimeout(600L),
        )
    }

    @Test
    fun travelPastSlopIsADrag() {
        val g = gesture()
        g.onDown(100f, 200f, 0L)
        assertEquals(
            BubbleGesture.Decision.DRAG,
            g.onMove(130f, 210f, slop),
        )
    }

    // --- the orderings that matter -------------------------------------------

    @Test
    fun aLongPressDoesNotAlsoFireTheTapItWasPressedOver() {
        val g = gesture()
        g.onDown(100f, 200f, 0L)
        assertEquals(
            "precondition: the long press fired",
            BubbleGesture.Decision.LONG_PRESS,
            g.onTimeout(600L),
        )
        assertEquals(
            "the finger lifting after a long press produced a TAP as well, so " +
                "holding the bubble would reveal the history AND start a " +
                "recording — the reveal would be covered by the thing it opened",
            BubbleGesture.Decision.NONE,
            g.onUp(),
        )
    }

    @Test
    fun aSlowDragDoesNotAlsoRevealTheHistory() {
        // 🔴 THE assertion of this file. A 600 ms drag beats the 400 ms timer,
        // so without the terminal-DRAG rule the user repositioning the bubble
        // gets a row of history chips on top of the reposition.
        val g = gesture()
        g.onDown(100f, 200f, 0L)
        assertEquals(
            "precondition: travel past slop is a drag",
            BubbleGesture.Decision.DRAG,
            g.onMove(140f, 200f, slop),
        )
        assertEquals(
            "a drag that lasted longer than the long-press threshold ALSO " +
                "produced a LONG_PRESS, so dragging the bubble slowly reveals " +
                "the history on top of the reposition",
            BubbleGesture.Decision.NONE,
            g.onTimeout(600L),
        )
    }

    @Test
    fun aDragDoesNotEndInATap() {
        // Pins the pre-existing `moved` behaviour through the refactor: the
        // finger lifting at the end of a reposition must not start a dictation.
        val g = gesture()
        g.onDown(100f, 200f, 0L)
        g.onMove(100f, 240f, slop)
        assertEquals(
            "lifting after a drag produced a TAP, so every reposition of the " +
                "bubble also starts or stops a recording",
            BubbleGesture.Decision.NONE,
            g.onUp(),
        )
    }

    @Test
    fun movementInsideSlopLeavesTheLongPressArmed() {
        // 🔴 The other side of the drag rule, and the one a mutant that
        // cancelled the long press on ANY movement would break: a thumb resting
        // on a 60 dp target is never perfectly still.
        val g = gesture()
        g.onDown(100f, 200f, 0L)
        assertEquals(
            "precondition: 4 px of travel against a slop of $slop is not a drag",
            BubbleGesture.Decision.NONE,
            g.onMove(102f, 202f, slop),
        )
        assertEquals(
            "a finger that wobbled 4 px inside a ${slop}px slop lost its long " +
                "press, so the reveal would need an unnaturally still thumb",
            BubbleGesture.Decision.LONG_PRESS,
            g.onTimeout(600L),
        )
    }

    @Test
    fun theDragDecisionStaysDragForEveryLaterMove() {
        // The shell repositions the window on each DRAG, so a decision that
        // reverted to NONE after the first move would freeze the bubble at
        // wherever it happened to be when slop was crossed.
        val g = gesture()
        g.onDown(100f, 200f, 0L)
        g.onMove(140f, 200f, slop)
        assertEquals(
            BubbleGesture.Decision.DRAG,
            g.onMove(141f, 201f, slop),
        )
    }

    // --- the slop boundary ---------------------------------------------------

    @Test
    fun theSlopComparisonIsStrictlyGreaterThan() {
        // A one-character mutation (`>` to `>=`) that no test above can see,
        // and which would make a tap at exactly the slop distance a drag — so
        // some fraction of real taps would silently stop starting dictations.
        val exactly = gesture()
        exactly.onDown(100f, 200f, 0L)
        assertEquals(
            "travel of exactly the slop must still be a tap",
            BubbleGesture.Decision.NONE,
            exactly.onMove(100f + slop, 200f, slop),
        )

        val oneMore = gesture()
        oneMore.onDown(100f, 200f, 0L)
        assertEquals(
            "travel of one pixel past the slop must be a drag",
            BubbleGesture.Decision.DRAG,
            oneMore.onMove(100f + slop + 1f, 200f, slop),
        )
    }

    @Test
    fun slopIsMeasuredAsTheSumOfTheTwoAxesNotEachAlone() {
        // Pins the L1 metric the previous implementation used. Nine pixels on
        // each axis is under the slop on either axis alone and over it summed;
        // a version comparing the axes separately would call this a tap.
        val g = gesture()
        g.onDown(100f, 200f, 0L)
        assertEquals(
            BubbleGesture.Decision.DRAG,
            g.onMove(109f, 209f, slop),
        )
    }

    @Test
    fun slopCountsTravelInEitherDirection() {
        // abs(), not a raw difference: dragging the bubble up and to the left
        // is as much a drag as down and to the right.
        val g = gesture()
        g.onDown(100f, 200f, 0L)
        assertEquals(
            BubbleGesture.Decision.DRAG,
            g.onMove(60f, 180f, slop),
        )
    }

    // --- the threshold boundary ----------------------------------------------

    @Test
    fun theThresholdIsInclusiveAtTheMillisecondItIsReached() {
        val atIt = gesture()
        atIt.onDown(100f, 200f, 0L)
        assertEquals(
            "a timeout delivered exactly at the threshold must fire",
            BubbleGesture.Decision.LONG_PRESS,
            atIt.onTimeout(THRESHOLD),
        )

        val justUnder = gesture()
        justUnder.onDown(100f, 200f, 0L)
        assertEquals(
            "a timeout delivered one millisecond early must NOT fire — a timer " +
                "running early would turn fast taps into reveals",
            BubbleGesture.Decision.NONE,
            justUnder.onTimeout(THRESHOLD - 1),
        )
    }

    @Test
    fun theThresholdIsMeasuredFromThisGesturesOwnDown() {
        // A stale timer from a previous gesture arriving during a new one must
        // not fire: the elapsed time is `now - down`, not `now`.
        val g = gesture()
        g.onDown(100f, 200f, 10_000L)
        assertEquals(
            "the timeout fired 120 ms into a gesture that began at t=10000, so " +
                "elapsed time is being read as absolute time",
            BubbleGesture.Decision.NONE,
            g.onTimeout(10_120L),
        )
    }

    @Test
    fun aLongPressFiresOnlyOnce() {
        val g = gesture()
        g.onDown(100f, 200f, 0L)
        assertEquals(BubbleGesture.Decision.LONG_PRESS, g.onTimeout(600L))
        assertEquals(
            "a second timeout for the same press revealed the history again",
            BubbleGesture.Decision.NONE,
            g.onTimeout(700L),
        )
    }

    // --- cancellation and events outside a gesture ---------------------------

    @Test
    fun aCancelledGestureFiresNothingAfterwards() {
        // 🔴 ACTION_CANCEL used to fall through to `return false`, leaving the
        // drag flag stale. With a timer in play it also leaves a reveal armed
        // to appear after the finger is gone — the window manager takes
        // gestures away for reasons the app does not control.
        val g = gesture()
        g.onDown(100f, 200f, 0L)
        g.onCancel()
        assertEquals(
            "a cancelled press still produced a long press, so the history row " +
                "appears after the finger has left the screen",
            BubbleGesture.Decision.NONE,
            g.onTimeout(600L),
        )
        assertEquals(
            "a cancelled press still produced a tap",
            BubbleGesture.Decision.NONE,
            g.onUp(),
        )
    }

    @Test
    fun aTimeoutAfterTheFingerLeftFiresNothing() {
        // `Handler.removeCallbacks` is best effort — a callback already
        // dispatched cannot be recalled — so this is a live case, not a
        // defensive one.
        val g = gesture()
        g.onDown(100f, 200f, 0L)
        assertEquals(BubbleGesture.Decision.TAP, g.onUp())
        assertEquals(
            "a long-press timer that outran its own ACTION_UP revealed the " +
                "history over a dictation the tap had just started",
            BubbleGesture.Decision.NONE,
            g.onTimeout(600L),
        )
    }

    @Test
    fun eventsWithNoGestureInProgressDecideNothing() {
        val g = gesture()
        assertEquals(BubbleGesture.Decision.NONE, g.onMove(999f, 999f, slop))
        assertEquals(BubbleGesture.Decision.NONE, g.onUp())
        assertEquals(BubbleGesture.Decision.NONE, g.onTimeout(999L))
    }

    @Test
    fun aSecondDownStartsACleanGesture() {
        // The view can receive a new ACTION_DOWN without the previous gesture
        // having ended cleanly. The new press must be a press, not the tail of
        // the old one.
        val g = gesture()
        g.onDown(100f, 200f, 0L)
        g.onMove(200f, 200f, slop)
        g.onDown(100f, 200f, 1_000L)
        assertEquals(
            "a fresh press inherited the previous gesture's drag state, so the " +
                "tap after any drag is swallowed",
            BubbleGesture.Decision.TAP,
            g.onUp(),
        )
    }

    // --- the production constant ---------------------------------------------

    @Test
    fun theDefaultThresholdIsTheProductionOne() {
        // 🔴 Every test above runs at an injected threshold, which is exactly
        // the shape that lets the shipped constant drift to anything at all
        // while the suite stays green. The service posts its timer at
        // BubbleGesture.LONG_PRESS_MS, so that constant is what the device sees.
        assertEquals(
            "the default threshold no longer equals LONG_PRESS_MS, so the " +
                "gesture tested here is not the gesture shipped",
            BubbleGesture.Decision.NONE,
            BubbleGesture().also { it.onDown(0f, 0f, 0L) }
                .onTimeout(BubbleGesture.LONG_PRESS_MS - 1),
        )
        assertEquals(
            BubbleGesture.Decision.LONG_PRESS,
            BubbleGesture().also { it.onDown(0f, 0f, 0L) }
                .onTimeout(BubbleGesture.LONG_PRESS_MS),
        )
    }

    private companion object {
        /**
         * 🔴 Deliberately **not** [BubbleGesture.LONG_PRESS_MS]. If the
         * fixture threshold equalled the production one, every test above
         * would pass just as well against an implementation that ignored its
         * constructor argument and hardcoded the constant — and
         * [theDefaultThresholdIsTheProductionOne], the one test that is
         * supposed to tie the two together, would be the only one measuring
         * anything.
         */
        const val THRESHOLD = 250L
    }
}
