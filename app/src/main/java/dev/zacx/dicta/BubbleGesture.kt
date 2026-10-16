package dev.zacx.dicta

import kotlin.math.abs

/**
 * Turns one touch gesture on the bubble into exactly one decision: **pure, with
 * no Android types**.
 *
 * ## Why this is not folded into [BubbleDictation]
 *
 * [BubbleDictation] decides what a tap *means*. It has no notion of
 * milliseconds-since-touch-down and no notion of pixels, and giving it one
 * would make it the first pure logic class in this repo that consumes motion
 * events — a second kind of thing to reason about inside the one class whose
 * value is that it only reasons about dictation. So recognition lives here and
 * produces a [Decision]; the shell translates a [Decision.LONG_PRESS] into
 * [BubbleDictation.Event.BubbleLongPressed] and a [Decision.TAP] into the
 * existing click.
 *
 * ## What the shell still owns
 *
 * The `Handler.postDelayed` that calls [onTimeout], the slop constant's pixel
 * value (it is density-scaled, which needs `resources`), the window move a
 * [Decision.DRAG] implies, and the animation. None of those can be tested off
 * the device; everything in this file can.
 *
 * ## Only two of the five entry points take a clock
 *
 * [onDown] and [onTimeout] do; [onMove], [onUp] and [onCancel] do not, and the
 * asymmetry is the design rather than an oversight. The **long press is the
 * only time-based decision here** — a drag is decided by distance and a tap by
 * the absence of either — so a `tMs` on the other three would be a parameter
 * every caller has to supply and nothing can read, which reads as "the time
 * matters here" to the next person to change it.
 *
 * ## The rules, and why each one is here
 *
 *  * **[Decision.DRAG] is terminal.** Once the finger has travelled past slop,
 *    no later [onUp] yields a tap and no later [onTimeout] yields a long press.
 *    A user who drags the bubble for 600 ms is repositioning it, and handing
 *    them a reveal *on top of* the reposition is two actions for one gesture.
 *    This is the single most important rule in the file and it is the one a
 *    mutant removing the terminal check walks straight through.
 *  * **Movement past slop cancels the pending long press** — the same statement
 *    as above, read from the other end, and the reason the two live in one
 *    class rather than two.
 *  * **[onUp] before the timeout is a [Decision.TAP].** The long press
 *    therefore fires *during* the press rather than on release, which is the
 *    platform convention and is what lets the reveal feel attached to the
 *    finger.
 *  * **[onCancel] exists because the window manager can take a gesture away.**
 *    Before this class the bubble's touch listener returned `false` for
 *    `ACTION_CANCEL`, which left the drag flag stale; with a pending long-press
 *    timer that becomes a reveal that appears after the finger is gone.
 *
 * Not thread-safe, and does not need to be: every caller is the main thread's
 * touch dispatch or a `Handler` callback posted to it.
 */
class BubbleGesture(
    /** Overridable so a test need not sleep. Production uses [LONG_PRESS_MS]. */
    private val longPressMs: Long = LONG_PRESS_MS,
) {

    /**
     * What one gesture turned out to be.
     *
     * [NONE] is returned by every call that does not *conclude* something — an
     * `ACTION_MOVE` inside slop, an up after the gesture already resolved, a
     * timeout for a gesture that was cancelled. Callers act only on the other
     * three, so "nothing happened" needs no special case anywhere.
     */
    enum class Decision { NONE, TAP, LONG_PRESS, DRAG }

    private var down = false
    private var downX = 0f
    private var downY = 0f
    private var downMs = 0L

    /**
     * What this gesture has already resolved to, or [Decision.NONE] while it is
     * still open. Only [Decision.DRAG] and [Decision.LONG_PRESS] are ever
     * stored: a tap is decided by the finger leaving, so there is nothing left
     * to remember afterwards.
     */
    private var settled = Decision.NONE

    /** Begins a gesture. The shell arms its long-press timer here. */
    fun onDown(x: Float, y: Float, tMs: Long) {
        down = true
        downX = x
        downY = y
        downMs = tMs
        settled = Decision.NONE
    }

    /**
     * A move to ([x], [y]).
     *
     * @param slopPx the travel, measured as `|dx| + |dy|`, past which this is a
     *   drag rather than a jittery finger. 🔴 The **L1** metric and the existing
     *   12 dp constant are both kept deliberately: changing the discriminator
     *   and adding a gesture in the same change would make any device
     *   regression in the *tap* unattributable to either.
     * @return [Decision.DRAG] once slop has been exceeded — on this call and on
     *   every later move in the same gesture, because the shell needs to keep
     *   repositioning the window — and [Decision.NONE] otherwise.
     */
    fun onMove(x: Float, y: Float, slopPx: Int): Decision {
        if (!down) return Decision.NONE
        if (settled == Decision.DRAG) return Decision.DRAG
        // Strictly greater than, matching the behaviour this replaces: travel
        // of exactly the slop is still a tap.
        if (abs(x - downX) + abs(y - downY) > slopPx) {
            settled = Decision.DRAG
            return Decision.DRAG
        }
        return Decision.NONE
    }

    /**
     * The finger left.
     *
     * @return [Decision.TAP] unless this gesture already resolved to a drag or
     *   a long press, in which case [Decision.NONE] — a long press that has
     *   already fired must not also fire the tap it was pressed over.
     */
    fun onUp(): Decision {
        if (!down) return Decision.NONE
        down = false
        val resolved = settled
        settled = Decision.NONE
        return if (resolved == Decision.NONE) Decision.TAP else Decision.NONE
    }

    /**
     * The shell's long-press timer fired.
     *
     * The elapsed check is not redundant with the timer: a `Handler` callback
     * can be delivered late, and it can be delivered for a gesture that has
     * since been replaced by another `ACTION_DOWN` on the same view. Reading
     * the clock here makes the answer a function of this class's inputs rather
     * than of scheduling.
     *
     * @return [Decision.LONG_PRESS] exactly once per gesture, and only while
     *   the finger is still down and has not travelled past slop.
     */
    fun onTimeout(tMs: Long): Decision {
        if (!down) return Decision.NONE
        if (settled != Decision.NONE) return Decision.NONE
        if (tMs - downMs < longPressMs) return Decision.NONE
        settled = Decision.LONG_PRESS
        return Decision.LONG_PRESS
    }

    /** The gesture was taken away. Everything pending is dropped. */
    fun onCancel() {
        down = false
        settled = Decision.NONE
    }

    companion object {
        /**
         * How long the bubble must be held before the history reveal fires.
         *
         * 🔴 A tunable, and the one number here most likely to need a device
         * adjustment. Deliberately **under** the platform's typical 500 ms
         * long-press timeout so Dicta's own reveal wins any system long-press
         * affordance the overlay window might otherwise pick up, and
         * comfortably over the dwell of a fast double tap — which matters
         * because start and stop are the *same* control, so an ambiguous
         * threshold would turn a hurried stop into a reveal.
         */
        const val LONG_PRESS_MS: Long = 400L
    }
}
