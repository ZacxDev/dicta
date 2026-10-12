package dev.zacx.dicta

import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-process channel for "take Dicta's overlay windows off the screen".
 *
 * [BubbleService] and [DictateActivity] live in the same process, so this is a
 * plain listener registry rather than a binder or a broadcast — and, unlike a
 * static reference to the Service instance, it is unit-testable and cannot leak
 * a destroyed Service.
 */
object OverlayVisibility {

    private val sinks = CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun register(sink: (Boolean) -> Unit) {
        sinks.add(sink)
    }

    fun unregister(sink: (Boolean) -> Unit) {
        sinks.remove(sink)
    }

    /** True = show the overlay windows, false = hide them for a capture. */
    fun setVisible(visible: Boolean) {
        // Never let one misbehaving sink stop the others from being restored.
        for (sink in sinks) {
            try {
                sink(visible)
            } catch (_: Throwable) {
            }
        }
    }

    /** Test seam only. */
    fun clearForTest() = sinks.clear()
}
