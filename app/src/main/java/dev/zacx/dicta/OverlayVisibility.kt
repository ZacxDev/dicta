package dev.zacx.dicta

import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-process channel for "take Dicta's overlay windows off the screen".
 *
 * [BubbleService] and [DictateActivity] live in the same process, so this is a
 * plain listener registry rather than a binder or a broadcast — and, unlike a
 * static reference to the Service instance, it is unit-testable and cannot leak
 * a destroyed Service.
 *
 * ## 🔴 TWO mechanisms hide the same overlay, so this is a SET, not a boolean
 *
 * There are two independent reasons to take the bubble off the screen, and
 * they overlap in the ordinary flow:
 *
 *  * [Holder.CARD] — [DictateActivity] is on screen. The bubble is an
 *    always-on-top overlay, so it draws over the transcription card and its
 *    mic button starts a *second* dictation from a view that is about the one
 *    just finished.
 *  * [Holder.CAPTURE] — a MediaProjection capture is in flight
 *    ([DictaWindows]). MediaProjection composites the whole screen, so an
 *    overlay ends up inside the user's own screenshot (PR #4).
 *
 * The screenshot is taken *from* the card, so both hold at once. With the
 * single boolean this used to be, `restoreAfterCapture()`'s unconditional
 * "show" put the bubble back **on top of the still-open card** — one
 * mechanism silently undoing the other.
 *
 * 🔴 **A SET of holders rather than a depth counter, deliberately.** A counter
 * composes only while every acquire is matched by exactly one release, and the
 * call sites here cannot promise that: `DictateActivity.onDestroy` releases
 * unconditionally (it must — a card torn down mid-capture must not strand the
 * bubble off screen), `onStart`/`onStop` can run more than once per capture,
 * and `ScreenshotSequencer` guarantees a restore even when the capture throws.
 * Every one of those is an unbalanced call that would drive a counter
 * permanently off zero, and the failure is invisible: the bubble simply never
 * comes back, or never goes away. Membership of a set is **idempotent** — a
 * double hide or a double release is a no-op — so the contract each caller has
 * to keep is only "say what YOUR mechanism wants", which each of them can.
 *
 * Sinks are notified only when the aggregate actually changes, so a capture
 * starting and finishing under an open card is zero window updates rather than
 * two.
 */
object OverlayVisibility {

    /**
     * Who can be holding the overlay off the screen.
     *
     * Deliberately a closed enum rather than an arbitrary key: these are the
     * two mechanisms that exist, and a new one should be a considered addition
     * here (and a line in the kdoc above) rather than an ad-hoc token.
     */
    enum class Holder {
        /** The transcription card is on screen. */
        CARD,

        /** A MediaProjection capture is in flight. */
        CAPTURE,
    }

    private val sinks = CopyOnWriteArrayList<(Boolean) -> Unit>()

    /** Guarded by this object's monitor. */
    private val hiders = java.util.EnumSet.noneOf(Holder::class.java)

    fun register(sink: (Boolean) -> Unit) {
        sinks.add(sink)
    }

    fun unregister(sink: (Boolean) -> Unit) {
        sinks.remove(sink)
    }

    /**
     * Declares whether [holder] wants the overlay windows off the screen.
     *
     * Idempotent in both directions. The overlay is visible exactly when no
     * holder is hiding it.
     */
    @Synchronized
    fun setHidden(holder: Holder, hidden: Boolean) {
        val wasVisible = hiders.isEmpty()
        if (hidden) hiders.add(holder) else hiders.remove(holder)
        val isVisible = hiders.isEmpty()
        if (wasVisible != isVisible) broadcast(isVisible)
    }

    /** True when no holder is hiding the overlay windows. */
    @Synchronized
    fun isVisible(): Boolean = hiders.isEmpty()

    private fun broadcast(visible: Boolean) {
        // Never let one misbehaving sink stop the others from being restored.
        for (sink in sinks) {
            try {
                sink(visible)
            } catch (_: Throwable) {
            }
        }
    }

    /** Test seam only. */
    @Synchronized
    fun clearForTest() {
        sinks.clear()
        hiders.clear()
    }
}
