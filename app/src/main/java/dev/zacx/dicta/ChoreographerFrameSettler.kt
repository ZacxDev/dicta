package dev.zacx.dicta

import android.view.Choreographer

/**
 * Real [FrameSettler]: chains [frames] Choreographer callbacks before running
 * the action.
 *
 * Why a frame chain and not a fixed sleep:
 *
 *  * A `sleep` is a guess that is simultaneously too long on a 120 Hz panel and
 *    too short under load. A Choreographer callback is tied to an actual vsync,
 *    so the wait scales with the device.
 *  * Hiding a window is a WindowManager relayout. The first vsync after
 *    `setVisibility(INVISIBLE)` is when *our* process submits the frame that no
 *    longer draws it; the surface removal reaches SurfaceFlinger's next
 *    composition after that. Two chained callbacks therefore put us past the
 *    composition that still contained our windows; the third is slack for a
 *    dropped frame.
 *
 * Why that is *sufficient* rather than merely likely: the capture step does not
 * reuse an existing mirror. It creates the ImageReader and VirtualDisplay only
 * after this settle completes, and takes the **first** image that display ever
 * produces. A VirtualDisplay mirror is composited at acquisition time from the
 * screen as it is then, so its first frame cannot contain a window that was
 * already removed before the display existed. The frame chain is what makes
 * "already removed" true rather than "requested"; it is not load-bearing for
 * the content of the frame itself.
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
