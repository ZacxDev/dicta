package dev.zacx.dicta

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Whether the capture queue is **stuck** — and the channel that puts that on
 * the bubble.
 *
 * ## 🔴 The defect this closes, which was silent in every direction
 *
 * [ClipFlusher.Report] was built with the failure in it and **discarded at both
 * production call sites** — `BubbleService.defaultClipFlush` and
 * `ClipPickerActivity.defaultFlush`. Paste a typo'd clip token into Settings
 * and the consequence was: every capture lands in the outbox, every toast says
 * "queued", every flush gets a 401 → [ClipPost.Unreachable] → the row is KEPT
 * (correctly — `ClipHttp.PERMANENT_STATUSES` excludes 401 so a five-second
 * repair cannot destroy captures), **forever, with nothing on screen**. The
 * picker meanwhile fetched the server's own empty list and rendered
 * `@string/clip_empty_none`, "nothing stored yet" — so the one surface the
 * operator would open to check reported the opposite of the truth.
 *
 * Operator decision, recorded on PR #48: *a stuck outbox MUST be visible on the
 * phone*. Deleting [ClipFlusher.Report] as unused was the other candidate and
 * it was **rejected**.
 *
 * ## 🔴 "Has pending work" is NOT "cannot drain", and conflating them is the
 * failure mode of this kind of indicator
 *
 * A queue with rows in it is the ordinary state: a capture is appended
 * synchronously and the POST is **always** deferred to the next tick (see
 * [ClipOutbox]'s kdoc — that is the design, not a fallback), so a pending row
 * one second after a capture is the feature working. An indicator that fired on
 * `kept > 0` would be lit after every single capture, and an indicator that is
 * always on is one nobody reads.
 *
 * So two things are required, and both are deliberate:
 *
 *  * the pass must have **failed** rather than merely left work —
 *    [ClipFlusher.Report.cannotDrain], which is `unreachable || failure != null`
 *    and is explicitly not `kept > 0`; and
 *  * it must have failed [STUCK_AFTER_FAILURES] times **in a row**. The mesh
 *    drops for a minute at a time and the tick is a minute, so one failure
 *    carries no information.
 *
 * Any pass that is not a failure resets the count to zero — including a
 * [ClipFlusher.MAX_PER_FLUSH] ceiling hit, which leaves `kept > 0` and is a
 * drain that is working.
 *
 * ## Why an object, and why this shape
 *
 * [OverlayVisibility]'s shape exactly, and for the same reason: the two writers
 * ([BubbleService]'s minute tick and [ClipPickerActivity]'s flush-on-open) and
 * the one reader ([BubbleService]'s clip button) are in one process but have no
 * reference to each other, and a static `Service` reference would leak a
 * destroyed component and could not be unit-tested. Sinks are notified **only
 * when the verdict changes**, so a phone that has been off the mesh for an hour
 * is one window update rather than sixty.
 *
 * 🔴 Deliberately NOT recorded inside [ClipFlusher.flush] itself, even though
 * that would make it impossible to discard. `ClipFlusher`'s own kdoc draws the
 * line: it holds the DECISIONS and is Android-free and allocation-free of
 * global state, so its tests are about the rules. A process-wide singleton
 * write inside it would make every one of those tests order-dependent on every
 * other. The call sites record, and
 * `ClipSeamLedgerTest.everySiteThatDrainsTheOutboxCONSUMESItsReport` is what
 * makes a site that forgets a red test rather than a review catch.
 *
 * ## ⚠ What this cannot see, stated rather than discovered
 *
 * A queue on a handset with **no clip token configured at all** never produces
 * a [ClipFlusher.Report] — both call sites return before building a flusher, by
 * design (a flush then would be a 401 per queued row per minute). So the
 * no-token case is invisible here. That is the ordinary state of a fresh
 * install, where the queue is also empty; and a missing token is visible on the
 * settings screen, which is where it is fixed. The case this exists for is the
 * WRONG token, which looks identical to a working install from every other
 * surface.
 */
object ClipOutboxHealth {

    /**
     * How many consecutive failed drains make the queue *stuck*.
     *
     * 🔴 Three, not one. `BubbleService.CLIP_FLUSH_INTERVAL_MS` is a minute, so
     * this is "the queue has not moved for about three minutes" — long enough
     * that a mesh blip, a sleeping relay or one dropped socket does not raise a
     * fault, short enough that an operator who mistyped a token sees it on the
     * next long press rather than tomorrow.
     *
     * 🔴 Not 1: a single failure is the single most common observation this
     * object will ever make, and treating it as a fault is how the indicator
     * becomes noise and stops being read. Not 10: the picker's flush-on-open
     * also records, and an operator who opens the picker twice has contributed
     * two of the ten without a minute passing.
     */
    const val STUCK_AFTER_FAILURES: Int = 3

    private val sinks = CopyOnWriteArrayList<(Boolean) -> Unit>()

    /** Guarded by this object's monitor. */
    private var consecutiveFailures = 0

    fun register(sink: (Boolean) -> Unit) {
        sinks.add(sink)
    }

    fun unregister(sink: (Boolean) -> Unit) {
        sinks.remove(sink)
    }

    /**
     * Folds one drain's [report] into the verdict.
     *
     * Call this from **every** site that drains the outbox, with the Report
     * that drain produced. Broadcasts only on a change of verdict.
     */
    @Synchronized
    fun record(report: ClipFlusher.Report) {
        val was = isStuckUnsynchronized()
        if (report.cannotDrain) {
            // Saturating rather than unbounded: the only question asked of this
            // counter is whether it has reached the threshold, and a phone left
            // off the mesh for a month would otherwise count to 40,000.
            if (consecutiveFailures < STUCK_AFTER_FAILURES) consecutiveFailures++
        } else {
            consecutiveFailures = 0
        }
        val now = isStuckUnsynchronized()
        if (was != now) broadcast(now)
    }

    /**
     * True when the last [STUCK_AFTER_FAILURES] drains all failed.
     *
     * Read at window-build time as well as pushed through [register], because a
     * service that starts while the queue is already stuck must show it on the
     * first long press rather than wait for a change it has already missed.
     */
    @Synchronized
    fun isStuck(): Boolean = isStuckUnsynchronized()

    private fun isStuckUnsynchronized(): Boolean =
        consecutiveFailures >= STUCK_AFTER_FAILURES

    private fun broadcast(stuck: Boolean) {
        // Never let one misbehaving sink stop the others — OverlayVisibility's
        // reason, and here the sink posts to a looper that may be gone.
        for (sink in sinks) {
            try {
                sink(stuck)
            } catch (_: Throwable) {
            }
        }
    }

    /** Test seam only. */
    @Synchronized
    fun clearForTest() {
        sinks.clear()
        consecutiveFailures = 0
    }
}
