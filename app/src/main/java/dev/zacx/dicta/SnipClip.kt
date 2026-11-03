package dev.zacx.dicta

/**
 * `{{clip}}` — the **only** variable a snippet body may carry.
 *
 * ## Why this exists at all, rather than copying the body verbatim
 *
 * Measured from `ZacxDev/snip`'s `internal/paste/paste.go:34`: `{{clip}}` is
 * resolved **at expand time** from the clipboard (`snip expand LABEL`), while
 * `snip get LABEL` deliberately returns it unresolved. A real catalog uses it —
 * the row whose whole body is `{{clip}}` is a pass-through "paste what I already
 * copied" entry — so a picker that copied bodies verbatim would put the seven
 * literal characters `{{clip}}` on the user's clipboard. That is not a cosmetic
 * defect: the user pastes it into a message and sends it.
 *
 * ## 🔴 Why resolving it forces the picker to be an Activity
 *
 * From Android 10 the clipboard can only be READ by the app that holds input
 * focus (or the default IME). `BubbleService` is a `Service` behind a
 * `FLAG_NOT_FOCUSABLE` overlay window and can never satisfy that, so the
 * resolution has to happen in the focused Activity. This is a second,
 * independent reason for [SnipPickerActivity] on top of the IME-focus one —
 * see that class's header.
 */
object SnipClip {

    /** The literal token, exactly as upstream spells it. */
    const val CLIP_VAR: String = "{{clip}}"

    /**
     * Whether [body] needs the clipboard at all.
     *
     * Exists so the caller can skip the clipboard read for the overwhelming
     * majority of rows that carry no variable, **without** spelling the token a
     * second time at that call site. One rule, one place: the token's spelling
     * lives here and nowhere else.
     */
    fun needsClip(body: String): Boolean = body.contains(CLIP_VAR)

    /**
     * [body] with every occurrence of [CLIP_VAR] replaced by [clipboard].
     *
     * `replace`, not `replaceFirst`: a body may use the variable more than
     * once, and upstream's `strings.ReplaceAll` resolves all of them.
     *
     * An empty [clipboard] yields an empty expansion rather than the literal
     * token — which is the point. "The clipboard was empty" must not surface as
     * `{{clip}}` in the text the user pastes.
     */
    fun resolve(body: String, clipboard: String): String = body.replace(CLIP_VAR, clipboard)
}
