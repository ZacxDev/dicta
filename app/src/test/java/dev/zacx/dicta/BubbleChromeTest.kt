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
}
