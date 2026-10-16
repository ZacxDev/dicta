package dev.zacx.dicta

/**
 * The two bits of bubble presentation that are decisions rather than pixels:
 * which label announces each state, and which side of the bubble the cancel
 * button goes on.
 *
 * Both are `Int` resource ids and plain arithmetic, so neither needs a platform
 * object and both can be pinned. Everything genuinely visual — the pulsing
 * ring, the window's measured size — is device evidence only.
 */
object BubbleChrome {

    /**
     * 🔴 The bubble's `contentDescription` for [state].
     *
     * This repo's standing rule is that state is never carried by colour alone
     * (see the long header in `res/values/colors.xml`). In the bubble flow the
     * *only* visual difference between idle and recording is a ring that pulses
     * in `dicta_live` — so without a changing label, "Dicta is recording you
     * right now" would be conveyed by hue and motion and nothing else. That is
     * a 1.4.1 failure, and for a microphone it is also a privacy problem: a
     * screen-reader user would have no way to tell whether the mic is open.
     *
     * The three labels are required to be mutually distinct by
     * `BubbleChromeTest`, asserted as a *set* rather than by matching words, so
     * a copy edit is free and a copy-paste that leaves two states announcing
     * the same thing is not.
     */
    fun labelFor(state: BubbleDictation.State): Int = when (state) {
        BubbleDictation.State.IDLE -> R.string.bubble_idle
        BubbleDictation.State.RECORDING -> R.string.bubble_recording
        BubbleDictation.State.TRANSCRIBING -> R.string.bubble_transcribing
    }

    /**
     * Whether the cancel button fits **below** the bubble.
     *
     * ## The known gap this closes, and why it was not left alone
     *
     * The bubble is draggable anywhere, so "put cancel underneath it" breaks as
     * soon as the user parks the bubble near the bottom edge: the button lands
     * off-screen and the recording becomes **unabortable** except by tapping the
     * bubble, which submits — the opposite of what the user wanted. Accepting
     * that was the cheaper option; it was rejected because an unreachable
     * cancel on a *microphone* control is the one failure here with a privacy
     * cost, and the user is most likely to want cancel precisely when they have
     * just realised they are being recorded.
     *
     * So the button flips above the bubble when there is not room below. The
     * flip is the whole of the fix and it is asserted here; what the flip *looks
     * like*, and whether the window's own repositioning keeps the bubble still
     * while it happens, are pixels and are device-unverified.
     *
     * @param bubbleTopPx the bubble's top edge, in pixels from the top of the
     *   usable display area.
     * @param bubbleHeightPx the bubble's own height.
     * @param blockHeightPx the gap plus the cancel button's height — the extra
     *   space the window needs when the button is shown.
     * @param screenHeightPx the usable display height.
     */
    fun cancelFitsBelow(
        bubbleTopPx: Int,
        bubbleHeightPx: Int,
        blockHeightPx: Int,
        screenHeightPx: Int,
    ): Boolean = bubbleTopPx + bubbleHeightPx + blockHeightPx <= screenHeightPx

    /**
     * Keeps the overlay window's top edge inside the display (issue #14,
     * item 5).
     *
     * ## The defect this closes, which is worse than "the bubble looks wrong"
     *
     * The drag was unbounded: `ACTION_MOVE` assigned `params.y = downY + dy`
     * with no limit at all, so the window could be parked below the bottom of
     * the screen. Three call sites feed this function, and the third is the one
     * that actually hurts:
     *
     *  1. the drag itself, which is how the window gets out of bounds;
     *  2. showing the cancel button, whose `y -= blockHeight` compensation can
     *     push `y` negative on its own when the bubble is near the top;
     *  3. **a configuration change.** Park the bubble at the portrait bottom
     *     edge and rotate to landscape: the display is suddenly ~900 px
     *     shorter, and the whole window — bubble *and* cancel — is below the
     *     visible area. That is an open microphone that can be neither stopped
     *     nor cancelled, which is the same privacy failure
     *     [cancelFitsBelow] exists to prevent, arrived at by a different route.
     *
     * ## The degenerate case, which is a real one
     *
     * When the window is **taller than the screen** there is no position that
     * satisfies the bound, and the honest answer is `0` — show the top of the
     * window, since the bubble is its anchor and the controls hang off it. The
     * obvious `wantedY.coerceIn(0, screenHeightPx - windowHeightPx)` does not
     * return `0` there: it throws `IllegalArgumentException` on an empty range,
     * from a touch handler, on a device with a short display and a large font
     * scale. Hence the explicit branch rather than a one-liner.
     *
     * @param windowHeightPx the window's height **including whatever is
     *   currently shown inside it** — the cancel button, the history reveal, or
     *   neither. Passing only the bubble's own height is the mutation that
     *   re-opens the unreachable-control defect for the bottom-most control.
     */
    fun clampY(wantedY: Int, windowHeightPx: Int, screenHeightPx: Int): Int {
        val maxY = screenHeightPx - windowHeightPx
        return if (maxY <= 0) 0 else wantedY.coerceIn(0, maxY)
    }

    /**
     * The horizontal twin of [clampY].
     *
     * The bubble's window uses `Gravity.TOP or Gravity.END`, so `x` is measured
     * **from the right edge and grows leftward** — which changes nothing about
     * the arithmetic (the valid band is still `0..screen - window`) and
     * everything about reading the call site, so it is said here once rather
     * than guessed at there.
     */
    fun clampX(wantedX: Int, windowWidthPx: Int, screenWidthPx: Int): Int {
        val maxX = screenWidthPx - windowWidthPx
        return if (maxX <= 0) 0 else wantedX.coerceIn(0, maxX)
    }
}
