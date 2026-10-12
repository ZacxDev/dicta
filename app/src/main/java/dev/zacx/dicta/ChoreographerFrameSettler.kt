package dev.zacx.dicta

import android.view.Choreographer

/**
 * Real [FrameSettler]: chains [frames] Choreographer callbacks before running
 * the action.
 *
 * Why a frame chain and not a fixed sleep: a `sleep` is a guess that is
 * simultaneously too long on a 120 Hz panel and too short under load, whereas a
 * Choreographer callback is tied to an actual vsync, so the wait scales with
 * the device. It gives the WindowManager relayout that
 * [HideableUi.hideForCapture] triggers a few vsyncs to be applied before the
 * mirror is created.
 *
 * 🔴 **What this class does NOT establish.** A Choreographer callback only says
 * *this process* ticked; it says nothing about what SurfaceFlinger composited,
 * and nothing about what a freshly created VirtualDisplay emits first. An
 * earlier version of this comment argued that the first frame of a new mirror
 * "cannot" contain an already-removed window, and called the settle sufficient.
 * That argument was never measured. It also was not what the one shipped defect
 * turned on: the card appeared in captures because it was still **fully
 * visible** at capture time — `Activity.makeVisible()` had reverted a
 * view-level hide — not because of any frame timing. See
 * `DictateActivity.DictaWindows` for the measurement. The hide being a window
 * attribute is what makes the frame clean; this settle is slack on top.
 *
 * Whether a shorter or longer chain would also work has not been measured; 3 is
 * the value the device evidence (7/7 clean captures, SM-G991W / Android 15) was
 * collected with.
 */
class ChoreographerFrameSettler(
    private val frames: Int = 3,
    private val choreographer: Choreographer = Choreographer.getInstance(),
) : FrameSettler {

    override fun afterFramesDrawn(action: () -> Unit) {
        post(frames, action)
    }

    private fun post(remaining: Int, action: () -> Unit) {
        if (remaining <= 0) {
            action()
            return
        }
        choreographer.postFrameCallback { post(remaining - 1, action) }
    }
}
