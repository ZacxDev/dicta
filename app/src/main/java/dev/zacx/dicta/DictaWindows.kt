package dev.zacx.dicta

import android.view.Window
import android.view.WindowManager

/**
 * Takes every Dicta-owned window off the screen for a capture, and puts it
 * back: the dictation dialog's own [window] plus, through [OverlayVisibility],
 * the floating bubble if [BubbleService] is running. MediaProjection composites
 * the whole screen, so both would otherwise appear in Dicta's own screenshot.
 *
 * Separate from `DictateActivity` so the window state transitions can be
 * asserted without the Activity's callback soup — see `DictaWindowsTest`.
 *
 * ## 🔴 The hide is a WINDOW ATTRIBUTE, not a view flag — that is the whole fix
 *
 * The first version of this used `window.decorView.visibility = INVISIBLE`, and
 * every capture contained the card. Measured on a Samsung SM-G991W / Android 15
 * driving the real consent path with the window state logged at each step:
 *
 * ```
 * HIDE    decorVis=4 (INVISIBLE)   <- what hideForCapture() had just set
 * RESUME  decorVis=4               <- DictateActivity.onResume, still hidden
 * SETTLED decorVis=0 (VISIBLE)     <- 37 ms later, at capture time
 * ```
 *
 * Nothing in Dicta set it back. `ActivityThread.handleResumeActivity()` calls
 * `Activity.makeVisible()`, which does an unconditional
 * `mDecor.setVisibility(View.VISIBLE)`, and it runs *after*
 * `performResumeActivity()` — i.e. after the app's own `onResume`. Returning
 * from the consent dialog *is* a resume, so a decor-visibility hide set from
 * `onActivityResult` is always undone before the capture happens. The card in
 * the capture was genuinely visible and genuinely composited; it was never a
 * stale SurfaceFlinger buffer.
 *
 * (The reading that pointed at a stale frame — "the status string inside the
 * image predates consent" — was a false lead. That string is set before the
 * consent dialog and is not changed again until the capture finishes, so it
 * reads exactly the same at hide time.)
 *
 * `WindowManager.LayoutParams.alpha` is applied by the window manager to the
 * window's layer and `makeVisible()` does not touch it, so it survives the
 * resume. The bubble was already hidden this way
 * (`BubbleService.visibilitySink`) and was correctly **absent** from the same
 * defective capture that contained the card — both halves of the control were
 * in one frame.
 *
 * The decor-visibility flag is deliberately NOT kept alongside as belt and
 * braces: it is *known* to be reverted here, and a defeated mechanism left in
 * place reads as a guard while providing none.
 */
internal class DictaWindows(
    private val window: Window,
    /** The dim to restore. Must match `android:backgroundDimAmount` in the theme. */
    private val dimAmount: Float,
) : HideableUi {

    override fun hideForCapture() {
        // The background dim is a WINDOW attribute too, applied regardless of
        // what the decor draws — a zero-alpha window sitting over a dim would
        // still darken the whole capture.
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0f)
        setWindowAlpha(0f)
        OverlayVisibility.setVisible(false)
    }

    override fun restoreAfterCapture() {
        setWindowAlpha(1f)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(dimAmount)
        OverlayVisibility.setVisible(true)
    }

    /**
     * Re-*assigning* `window.attributes` is what pushes the change through
     * `Window.setAttributes` -> `ViewRootImpl` -> `WindowManagerService`.
     * Mutating the object returned by `getAttributes()` in place does not,
     * because nothing notifies the window.
     */
    private fun setWindowAlpha(alpha: Float) {
        val lp = window.attributes
        lp.alpha = alpha
        window.attributes = lp
    }
}
