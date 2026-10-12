package dev.zacx.dicta

import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.TypedValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * REGRESSION test for "the bubble opens the app instead of a floating panel".
 *
 * The diagnosed mechanism was that Theme.Dicta.Overlay overrode
 * `android:windowBackground` with `@android:color/transparent`. On Android a
 * dialog's screen inset is decor *padding* taken from the window background
 * drawable (PhoneWindow hands it to DecorView.setWindowBackground, and
 * View.setBackgroundDrawable copies Drawable.getPadding into the view padding).
 * A ColorDrawable reports no padding, so the inset was zero and the card sat
 * flush against the screen edges.
 *
 * ## What was actually observed about red-before-green
 *
 * This file cannot be run against the pre-change tree — it did not exist there,
 * and neither did several of the resources it names, so "red at base" would be
 * a compile error rather than a guard watched going red. What WAS observed is a
 * hand-rolled partial revert on this tree: putting `windowBackground` back to
 * `@android:color/transparent` (the exact pre-change value) turned
 * [windowBackgroundReportsAnInsetOnAllFourSides] and
 * [windowBackgroundIsNotAFlatColour] red, each on its own assertion message.
 * The matrix is in the PR body.
 *
 * The *width* side of the fix is NOT asserted here. The four configuration
 * literals (0.92 / 0.62 / 0.70 / 0.50) are asserted end-to-end by
 * [FloatingWindowSizeTest], which reads `attrs.width / displayWidth` off a real
 * activity — a resource-value check of the same four numbers cannot fail while
 * those pass, so it would read as coverage while providing none.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictateOverlayThemeTest {

    private fun overlayAttr(attr: Int): TypedValue {
        val ctx = RuntimeEnvironment.getApplication()
        val theme = ctx.resources.newTheme()
        theme.applyStyle(R.style.Theme_Dicta_Overlay, true)
        val out = TypedValue()
        assertTrue(
            "Theme.Dicta.Overlay does not define attr 0x${Integer.toHexString(attr)}",
            theme.resolveAttribute(attr, out, true)
        )
        return out
    }

    private fun windowBackground(): Drawable {
        val ctx = RuntimeEnvironment.getApplication()
        val theme = ctx.resources.newTheme()
        theme.applyStyle(R.style.Theme_Dicta_Overlay, true)
        val ta = theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowBackground))
        try {
            return requireNotNull(ta.getDrawable(0)) { "windowBackground is null" }
        } finally {
            ta.recycle()
        }
    }

    /** THE regression assertion: the window background must carry an inset. */
    @Test
    fun windowBackgroundReportsAnInsetOnAllFourSides() {
        val background = windowBackground()
        val padding = Rect()
        assertTrue(
            "windowBackground (${background.javaClass.name}) reports no padding — " +
                "the dialog has no screen inset and will render edge to edge",
            background.getPadding(padding)
        )
        assertTrue("left inset was ${padding.left}", padding.left > 0)
        assertTrue("top inset was ${padding.top}", padding.top > 0)
        assertTrue("right inset was ${padding.right}", padding.right > 0)
        assertTrue("bottom inset was ${padding.bottom}", padding.bottom > 0)
    }

    /**
     * Companion to the above, pinning the *shape* of the hazard rather than one
     * spelling of it: a flat colour (of any colour, including an opaque one)
     * can never report padding, so it can never produce a dialog inset.
     */
    @Test
    fun windowBackgroundIsNotAFlatColour() {
        assertFalse(
            "windowBackground is a ColorDrawable; a ColorDrawable cannot report " +
                "padding, so the decor inset would be zero",
            windowBackground() is ColorDrawable
        )
    }

    /**
     * INVARIANT GUARD, not regression coverage — this was already true before
     * the fix (measured: windowIsFloating resolved to true on the old theme),
     * so "the window was not floating" was never the bug. Pinned because a
     * parent-theme change could silently make the activity fullscreen.
     */
    @Test
    fun windowIsFloating() {
        val value = overlayAttr(android.R.attr.windowIsFloating)
        assertEquals(TypedValue.TYPE_INT_BOOLEAN, value.type)
        assertTrue("windowIsFloating resolved false", value.data != 0)
    }

    /** INVARIANT GUARD: the app behind must stay visible and dimmed. */
    @Test
    fun backgroundIsDimmedAndWindowIsTranslucent() {
        assertTrue(
            "backgroundDimEnabled resolved false",
            overlayAttr(android.R.attr.backgroundDimEnabled).data != 0
        )
        assertTrue(
            "windowIsTranslucent resolved false",
            overlayAttr(android.R.attr.windowIsTranslucent).data != 0
        )
        assertTrue(
            "backgroundDimAmount resolved to " +
                "${overlayAttr(android.R.attr.backgroundDimAmount).float}, " +
                "which is not a visible dim",
            overlayAttr(android.R.attr.backgroundDimAmount).float > 0f
        )
    }

    /**
     * SEAM guard, added by the screenshot change — which is what creates the
     * hazard. The capture path clears `FLAG_DIM_BEHIND` and sets
     * `dimAmount = 0` to keep the dim out of the frame, then restores the dim
     * from the Kotlin constant [DictateActivity.DIM_AMOUNT], NOT from the
     * theme. The theme value and the constant are therefore two copies of one
     * number, and a theme-only edit to 0.5 would leave the card permanently
     * mis-dimmed after the first capture with every other test green.
     *
     * Asserting the RELATIONSHIP rather than the literal 0.6 is the whole
     * point: a third copy of the literal in the test moves with neither side
     * and passes whichever one drifted. Editing either value alone turns this
     * red; editing both together is a deliberate change and stays green.
     */
    @Test
    fun theRestoredDimEqualsTheThemeDim() {
        assertEquals(
            "DictateActivity.DIM_AMOUNT (${DictateActivity.DIM_AMOUNT}) and " +
                "android:backgroundDimAmount in Theme.Dicta.Overlay disagree; " +
                "the card would be mis-dimmed after a screenshot capture",
            DictateActivity.DIM_AMOUNT,
            overlayAttr(android.R.attr.backgroundDimAmount).float,
            0.0001f
        )
    }
}
