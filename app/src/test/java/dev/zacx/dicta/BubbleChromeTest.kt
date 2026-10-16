package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The bubble's accessibility labels and the cancel button's placement.
 *
 * ## Why the labels are a test
 *
 * This repo's documented principle is that state is never carried by colour
 * alone (`res/values/colors.xml`, "State is never carried by colour alone").
 * In the bubble flow the only *visual* difference between idle and recording is
 * a ring pulsing in `dicta_live` — hue and motion. An animating bubble with a
 * static `contentDescription` would therefore leave a screen-reader user with
 * no way to tell whether the microphone is open, which for a microphone is a
 * privacy failure and not a styling one.
 *
 * The assertion is on the **set**: three distinct, non-blank labels. Not on the
 * words. A guard that matched "Recording" would be walkable by rewording and
 * would also break on every copy edit; what has to hold is that the states are
 * *distinguishable*, and that is a property of the set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BubbleChromeTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    @Test
    fun everyStateHasItsOwnNonBlankLabel() {
        val states = BubbleDictation.State.entries
        val labels = states.map { ctx.getString(BubbleChrome.labelFor(it)) }

        for ((state, label) in states.zip(labels)) {
            assertTrue(
                "the bubble's contentDescription for $state is blank, so that " +
                    "state is announced as nothing at all",
                label.isNotBlank(),
            )
        }

        assertEquals(
            "two or more bubble states announce the SAME text, so a screen " +
                "reader cannot tell them apart and 'the microphone is open' is " +
                "left to a pulsing colour. Labels: $labels",
            states.size,
            labels.toSet().size,
        )
    }

    @Test
    fun theCancelButtonHasItsOwnLabelDistinctFromTheBubbleStates() {
        val cancel = ctx.getString(R.string.bubble_cancel)
        val stateLabels = BubbleDictation.State.entries
            .map { ctx.getString(BubbleChrome.labelFor(it)) }
            .toSet()

        assertTrue("the cancel button's label is blank", cancel.isNotBlank())
        assertFalse(
            "the cancel button announces the same text as one of the bubble's " +
                "own states, so the two controls are indistinguishable by " +
                "label and the only thing telling them apart is their icon",
            stateLabels.contains(cancel),
        )
    }

    @Test
    fun theLabelLookupIsNotReturningOneConstantForEverything() {
        // 🔴 Positive control. The distinctness assertion above would also pass
        // if `labelFor` were correct and `getString` were broken in a way that
        // returned the resource name — so pin that the ids themselves differ,
        // which is the property `labelFor` is actually responsible for.
        val ids = BubbleDictation.State.entries.map { BubbleChrome.labelFor(it) }
        assertEquals(
            "labelFor maps two states to the same string resource: $ids",
            ids.size,
            ids.toSet().size,
        )
        assertFalse("labelFor returned a 0 resource id", ids.contains(0))
    }

    // --- where the cancel button goes ---------------------------------------

    @Test
    fun theCancelButtonSitsBelowTheBubbleWhenThereIsRoom() {
        assertTrue(
            BubbleChrome.cancelFitsBelow(
                bubbleTopPx = 200,
                bubbleHeightPx = 180,
                blockHeightPx = 150,
                screenHeightPx = 2400,
            )
        )
    }

    @Test
    fun theCancelButtonFlipsAboveWhenTheBubbleIsParkedAtTheBottom() {
        // The gap this closes: dragged to the bottom edge, a cancel button
        // below the bubble is off-screen, and the recording becomes
        // unabortable except by tapping the bubble — which submits it.
        assertFalse(
            BubbleChrome.cancelFitsBelow(
                bubbleTopPx = 2200,
                bubbleHeightPx = 180,
                blockHeightPx = 150,
                screenHeightPx = 2400,
            )
        )
    }

    @Test
    fun theFitDecisionIsExactAtTheEdge() {
        // Exactly flush with the bottom still fits; one pixel more does not.
        // `<=` versus `<` here is a one-character mutation that neither test
        // above can see, and getting it wrong flips the button for a whole band
        // of bubble positions.
        assertTrue(
            "a block ending exactly at the screen edge fits",
            BubbleChrome.cancelFitsBelow(
                bubbleTopPx = 2070,
                bubbleHeightPx = 180,
                blockHeightPx = 150,
                screenHeightPx = 2400,
            )
        )
        assertFalse(
            "a block ending one pixel past the screen edge does not fit",
            BubbleChrome.cancelFitsBelow(
                bubbleTopPx = 2071,
                bubbleHeightPx = 180,
                blockHeightPx = 150,
                screenHeightPx = 2400,
            )
        )
    }

    @Test
    fun theFitDecisionReadsEveryDimensionItIsGiven() {
        // 🔴 A guard against the narrowest real mutation: an implementation
        // that ignores one of its inputs. Each assertion moves exactly ONE
        // dimension across the boundary from the same fitting baseline, so a
        // version that dropped any single term would survive the tests above
        // but not these. The baseline deliberately overshoots rather than
        // sitting on a multiple of any term.
        val fits = { top: Int, h: Int, block: Int, screen: Int ->
            BubbleChrome.cancelFitsBelow(top, h, block, screen)
        }
        assertTrue("baseline must fit", fits(1000, 170, 130, 2400))

        assertFalse("moving only bubbleTopPx did not change the answer",
            fits(2300, 170, 130, 2400))
        assertFalse("moving only bubbleHeightPx did not change the answer",
            fits(1000, 1470, 130, 2400))
        assertFalse("moving only blockHeightPx did not change the answer",
            fits(1000, 170, 1430, 2400))
        assertFalse("moving only screenHeightPx did not change the answer",
            fits(1000, 170, 130, 1100))
    }

    // --- keeping the window on screen (#14 item 5) --------------------------

    @Test
    fun aPositionInsideTheDisplayIsLeftAlone() {
        assertEquals(900, BubbleChrome.clampY(900, 250, 2400))
        assertEquals(300, BubbleChrome.clampX(300, 230, 1080))
    }

    @Test
    fun aPositionPastTheBottomEdgeIsPulledBackSoTheWholeWindowIsVisible() {
        // The defect: the drag was unbounded, so the window — bubble AND
        // cancel button — could be parked below the display. With a recording
        // running that is an open microphone with no reachable control.
        assertEquals(
            "the clamped position must leave the window's BOTTOM on screen, " +
                "not merely its top",
            2150,
            BubbleChrome.clampY(5000, 250, 2400),
        )
        assertEquals(850, BubbleChrome.clampX(5000, 230, 1080))
    }

    @Test
    fun aNegativePositionIsPulledBackToTheTopEdge() {
        // Reachable with no drag at all: showCancel's `y -= cancelBlockPx`
        // compensation takes y negative on its own when the bubble is near the
        // top, and so does revealing the history row.
        assertEquals(0, BubbleChrome.clampY(-400, 250, 2400))
        assertEquals(0, BubbleChrome.clampX(-400, 230, 1080))
    }

    @Test
    fun aWindowTallerThanTheScreenIsPinnedToTheTopRatherThanThrowing() {
        // 🔴 The degenerate case, and a real one: a short display in landscape
        // with the history row shown at a large font scale. There is no
        // position satisfying the bound, and the honest answer is 0 — show the
        // top of the window, since the bubble is its anchor.
        //
        // The obvious one-liner, `wantedY.coerceIn(0, screen - window)`, does
        // not return 0 here: it throws IllegalArgumentException on an empty
        // range, out of a touch handler.
        assertEquals(0, BubbleChrome.clampY(500, 3000, 2400))
        assertEquals(0, BubbleChrome.clampY(-500, 3000, 2400))
        assertEquals(0, BubbleChrome.clampX(500, 1400, 1080))
    }

    @Test
    fun aWindowExactlyTheHeightOfTheScreenHasOneLegalPosition() {
        // The boundary between the ordinary branch and the degenerate one. A
        // `<` instead of `<=` on the guard leaves `coerceIn(0, 0)` — which
        // happens to work — so this pins the ANSWER rather than the branch.
        assertEquals(0, BubbleChrome.clampY(900, 2400, 2400))
        assertEquals(0, BubbleChrome.clampX(900, 1080, 1080))
    }

    @Test
    fun theClampReadsEveryDimensionItIsGiven() {
        // 🔴 The narrowest real mutation: an implementation that drops one of
        // its three inputs. Each case moves exactly ONE dimension from the
        // same unclamped baseline, and the baseline deliberately overshoots
        // rather than sitting on any boundary — a baseline sitting ON its
        // bound makes the clamped branch unreachable and the mutant survives
        // for the wrong reason.
        assertEquals(
            "baseline must be inside the display and therefore untouched",
            900, BubbleChrome.clampY(900, 250, 2400),
        )
        assertEquals(
            "a larger wantedY did not change the result, so wantedY is ignored",
            2150, BubbleChrome.clampY(3000, 250, 2400),
        )
        assertEquals(
            "a taller window did not change the result, so windowHeightPx is " +
                "ignored — which is the mutation that drops the history row's " +
                "height and pushes the chips off the bottom of the display",
            700, BubbleChrome.clampY(900, 1700, 2400),
        )
        assertEquals(
            "a shorter screen did not change the result, so screenHeightPx is " +
                "ignored — which is the rotation case",
            850, BubbleChrome.clampY(900, 250, 1100),
        )
    }
}
