package dev.zacx.dicta

import android.view.View
import android.view.WindowManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * REGRESSION test for "the screenshot contains Dicta's own card".
 *
 * ## 🔴 What this file CANNOT see — read this before trusting it
 *
 * Nothing in this repository composites. A unit test can observe what
 * [DictaWindows] *asks the window manager for*; it cannot observe whether the
 * card reached the screen, what SurfaceFlinger composited, or what a
 * MediaProjection mirror actually captured. **The fix's correctness is
 * established only by a real capture on a device, inspected by eye** — PR #4
 * carries that evidence (7/7 clean captures on a Samsung SM-G991W / Android 15,
 * with the calculator behind Dicta visible in every one as a positive control,
 * against a pre-fix capture of the same scene that contained the whole card).
 *
 * This file is therefore deliberately narrow, and its assertions are scoped to
 * what they really check: **the hide is expressed as a window attribute rather
 * than a view flag.** That is the one part of the mechanism that is
 * machine-checkable here, and it is the part the defect turned on.
 *
 * ## The mechanism this guards against coming back
 *
 * The original implementation hid the dialog with
 * `window.decorView.visibility = INVISIBLE`. `ActivityThread.handleResumeActivity()`
 * calls `Activity.makeVisible()`, which unconditionally sets the decor back to
 * `VISIBLE`, and it runs after the app's own `onResume`. Returning from the
 * screen-capture consent dialog is a resume, so the hide was always undone
 * before the capture. Device log, pre-fix:
 *
 * ```
 * HIDE    decorVis=4 (INVISIBLE)
 * RESUME  decorVis=4
 * SETTLED decorVis=0 (VISIBLE)   <- at capture time
 * ```
 *
 * [hideSurvivesTheDecorBeingForcedVisibleAgain] is the guard: it reproduces
 * exactly that interference — forcing the decor visible after the hide — and
 * asserts the window is still transparent. It fails on the pre-fix
 * implementation, where the only hide was the decor flag.
 *
 * Robolectric does not implement `makeVisible()`, so the forcing is done by
 * hand. That makes the guard a check that the hide **does not depend on the
 * decor flag**, which is a weaker claim than "it survives a real resume" — the
 * real resume was measured on the device instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictaWindowsTest {

    private val overlayEvents = mutableListOf<Boolean>()
    private val sink: (Boolean) -> Unit = { overlayEvents.add(it) }

    @After
    fun tearDown() {
        OverlayVisibility.unregister(sink)
        OverlayVisibility.clearForTest()
    }

    private fun windows(dim: Float = DictateActivity.DIM_AMOUNT): Pair<DictaWindows, android.view.Window> {
        val activity = Robolectric.buildActivity(DictateActivity::class.java).setup().get()
        // 🔴 The Activity's own onStart has already taken the CARD hold on
        // [OverlayVisibility] — it hides the bubble for as long as the card is
        // on screen. This file is about the CAPTURE mechanism in isolation, so
        // the holder state is reset to a clean baseline before the sink goes
        // on; otherwise [theBubbleIsHiddenAndRestoredWithTheDialog] would be
        // measuring the composition of the two rather than this class.
        // The composition itself is covered end to end, through both real
        // seams, by BubbleHiddenWhileTheCardIsUpTest.
        OverlayVisibility.clearForTest()
        OverlayVisibility.register(sink)
        return DictaWindows(activity.window, dim) to activity.window
    }

    @Test
    fun hideMakesTheWindowItselfTransparent() {
        val (ui, window) = windows()

        assertEquals("window starts opaque", 1f, window.attributes.alpha, 0.0001f)
        ui.hideForCapture()

        assertEquals(
            "hideForCapture left the window alpha at ${window.attributes.alpha}; " +
                "a window the WM still composites at full alpha is in the capture",
            0f, window.attributes.alpha, 0.0001f
        )
    }

    /**
     * THE regression case. See the class kdoc: `Activity.makeVisible()` forces
     * the decor visible on every resume, and returning from the consent dialog
     * is a resume.
     */
    @Test
    fun hideSurvivesTheDecorBeingForcedVisibleAgain() {
        val (ui, window) = windows()
        ui.hideForCapture()

        // Exactly what ActivityThread.handleResumeActivity -> makeVisible does.
        window.decorView.visibility = View.VISIBLE

        assertEquals(
            "the window became opaque again as soon as the decor was forced " +
                "visible — the hide is a view flag, and Activity.makeVisible() " +
                "undoes it before every capture",
            0f, window.attributes.alpha, 0.0001f
        )
    }

    /** The dim is a separate window attribute; a transparent window over a dim
     *  still darkens the whole capture. */
    @Test
    fun hideAlsoClearsTheBackgroundDim() {
        val (ui, window) = windows()
        ui.hideForCapture()

        assertEquals("dim amount", 0f, window.attributes.dimAmount, 0.0001f)
        assertEquals(
            "FLAG_DIM_BEHIND is still set, so the capture is dimmed",
            0,
            window.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND
        )
    }

    /** The user must never be stranded with an invisible dialog. */
    @Test
    fun restorePutsBackAlphaAndDim() {
        val (ui, window) = windows(dim = 0.6f)
        ui.hideForCapture()
        ui.restoreAfterCapture()

        assertEquals("window alpha", 1f, window.attributes.alpha, 0.0001f)
        assertEquals("dim amount", 0.6f, window.attributes.dimAmount, 0.0001f)
        assertTrue(
            "FLAG_DIM_BEHIND was not restored",
            window.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0
        )
    }

    /** The bubble is a different window in a different component; it is driven
     *  through [OverlayVisibility], and must move with the dialog. */
    @Test
    fun theBubbleIsHiddenAndRestoredWithTheDialog() {
        val (ui, _) = windows()
        ui.hideForCapture()
        assertEquals(listOf(false), overlayEvents)
        ui.restoreAfterCapture()
        assertEquals(listOf(false, true), overlayEvents)
    }

    /**
     * The restore must use the value handed in, not a hard-coded 0.6 — the
     * theme's dim is the source of truth and `DictateOverlayThemeTest` pins
     * [DictateActivity.DIM_AMOUNT] to it.
     */
    @Test
    fun restoreUsesTheSuppliedDimNotAConstant() {
        val (ui, window) = windows(dim = 0.25f)
        ui.hideForCapture()
        ui.restoreAfterCapture()
        assertEquals(0.25f, window.attributes.dimAmount, 0.0001f)
        assertFalse(
            "restore ignored its argument and used 0.6",
            window.attributes.dimAmount == 0.6f
        )
    }
}
