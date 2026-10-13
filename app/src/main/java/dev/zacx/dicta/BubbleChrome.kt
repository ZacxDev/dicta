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
}
