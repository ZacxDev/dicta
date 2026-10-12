package dev.zacx.dicta

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Dicta's own windows, as far as the screenshot flow is concerned. */
interface HideableUi {
    /** Take every Dicta-owned window off the screen. */
    fun hideForCapture()

    /** Put them back. Must be idempotent — the sequencer guarantees one call,
     *  but a last-resort path may run on an unexpected thread. */
    fun restoreAfterCapture()
}

/**
 * Waits until the compositor has actually produced frames that no longer
 * contain the windows [HideableUi.hideForCapture] just took down.
 */
fun interface FrameSettler {
    fun afterFramesDrawn(action: () -> Unit)
}

/**
 * The hide -> settle -> capture -> restore sequence, extracted from the Activity
 * so it can be tested without a MediaProjection, a display, or a looper.
 *
 * The contract this type exists to guarantee:
 *
 *  1. [HideableUi.hideForCapture] happens before [capture] is ever invoked.
 *  2. [HideableUi.restoreAfterCapture] happens after [capture] returns.
 *  3. **[HideableUi.restoreAfterCapture] happens even when [capture] throws, and
 *     even when the settler or the executors fail.** Leaving the dialog hidden
 *     and the bubble gone would strand the user with a dead-looking app, so the
 *     failure path is the one that matters.
 *  4. Restore runs exactly once, and [onDone] exactly once.
 *
 * @param worker runs [capture] off the main thread (it blocks on a frame).
 * @param main   posts restore + [onDone] back to the UI thread.
 */
class ScreenshotSequencer<T>(
    private val ui: HideableUi,
    private val settler: FrameSettler,
    private val worker: Executor,
    private val main: Executor,
    private val capture: () -> T,
    private val onDone: (Result<T>) -> Unit,
) {

    private val started = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)

    fun start() {
        // Re-entrancy guard: a double tap must not hide twice, and must not
        // produce two restores racing a single capture.
        if (!started.compareAndSet(false, true)) return

        ui.hideForCapture()
        try {
            settler.afterFramesDrawn { onSettled() }
        } catch (t: Throwable) {
            finish(Result.failure(t))
        }
    }

    private fun onSettled() {
        try {
            worker.execute {
                val result = runCatching { capture() }
                try {
                    main.execute { finish(result) }
                } catch (_: Throwable) {
                    // The main executor is gone (activity torn down). Restoring
                    // off-thread is still better than never restoring.
                    finish(result)
                }
            }
        } catch (t: Throwable) {
            finish(Result.failure(t))
        }
    }

    private fun finish(result: Result<T>) {
        if (!finished.compareAndSet(false, true)) return
        try {
            ui.restoreAfterCapture()
        } finally {
            onDone(result)
        }
    }
}
