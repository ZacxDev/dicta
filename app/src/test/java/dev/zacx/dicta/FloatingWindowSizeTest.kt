package dev.zacx.dicta

import android.view.Gravity
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * REGRESSION test for the second half of the fullscreen bug: with a WRAP_CONTENT
 * floating window and no width cap, the decor is measured at
 * `AT_MOST(displayWidth)` and content that wants to be wider simply clamps to
 * the whole display.
 *
 * ## What was actually observed about red-before-green
 *
 * This file cannot be run against the pre-change tree — it did not exist there,
 * and neither did `R.fraction.overlay_width_fraction` or `R.id.dictate_card`,
 * so "red at base" would be a compile error rather than a guard watched going
 * red. What WAS observed is a hand-rolled partial revert on this tree: deleting
 * the `applyFloatingWindowSize()` call from `onCreate` — which is exactly the
 * pre-change state, since [DictateActivity] never touched `window.setLayout`
 * there. The matrix and the observed failure messages are in the PR body.
 *
 * Measured at four configurations, because a layout verified at one size is a
 * claim about one size. These four assertions are also the end-to-end check on
 * the 0.92 / 0.62 / 0.70 / 0.50 literals — they read `attrs.width /
 * displayWidth` off a real activity, so there is deliberately no separate
 * resource-value test of the same four numbers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FloatingWindowSizeTest {

    @Test
    @Config(qualifiers = "w411dp-h914dp-port-xxhdpi")
    fun phonePortrait() = assertCappedAt(0.92f)

    @Test
    @Config(qualifiers = "w914dp-h411dp-land-xxhdpi")
    fun phoneLandscape() = assertCappedAt(0.62f)

    @Test
    @Config(qualifiers = "sw600dp-w800dp-h1200dp-port-xhdpi")
    fun tabletPortrait() = assertCappedAt(0.70f)

    @Test
    @Config(qualifiers = "sw600dp-w1280dp-h800dp-land-xhdpi")
    fun tabletLandscape() = assertCappedAt(0.50f)

    private fun assertCappedAt(expectedFraction: Float) {
        val controller = Robolectric.buildActivity(DictateActivity::class.java).setup()
        try {
            val activity = controller.get()
            val displayWidth = activity.resources.displayMetrics.widthPixels
            val attrs = activity.window.attributes

            assertTrue("display width came back as $displayWidth", displayWidth > 0)

            // Not WRAP_CONTENT / MATCH_PARENT — an explicit pixel cap.
            assertTrue(
                "window width is ${attrs.width}; the window was never sized, so " +
                    "the decor is still measured at AT_MOST(display) and the card " +
                    "can clamp to the full screen width",
                attrs.width > 0
            )
            assertTrue(
                "window width ${attrs.width} is not narrower than the " +
                    "$displayWidth px display — the card still spans edge to edge",
                attrs.width < displayWidth
            )
            assertEquals(
                "window/display ratio",
                expectedFraction,
                attrs.width.toFloat() / displayWidth,
                0.005f
            )

            // Height stays content-driven: a fixed height would either clip the
            // card or leave it tall and empty.
            assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, attrs.height)

            // Centred, so the margin is on all sides rather than one.
            assertEquals(Gravity.CENTER, attrs.gravity)
        } finally {
            controller.close()
        }
    }

    /**
     * The card must be inside a scroll container, or a landscape phone clips the
     * action row off the bottom of a WRAP_CONTENT dialog.
     */
    @Test
    @Config(qualifiers = "w914dp-h411dp-land-xxhdpi")
    fun theCardScrollsRatherThanClipping() {
        val controller = Robolectric.buildActivity(DictateActivity::class.java).setup()
        try {
            val card = controller.get().findViewById<android.view.View>(R.id.dictate_card)
            var parent = card.parent
            var found = false
            while (parent is android.view.View) {
                if (parent is android.widget.ScrollView) {
                    found = true
                    break
                }
                parent = parent.getParent()
            }
            assertTrue(
                "the dictate card has no ScrollView ancestor; on a short " +
                    "landscape window the bottom action row would be clipped",
                found
            )
        } finally {
            controller.close()
        }
    }
}
