package dev.zacx.dicta

/**
 * What one **history picker** row says. Pure string work, so it is pinned
 * rather than being device evidence — [SnipRow]'s split exactly, applied to the
 * other picker.
 *
 * ## Why this replaced the chip label
 *
 * [HistoryLabel.shortLabel] used to produce the whole of a row's text: an age
 * plus the opening words, cut to a 34-character budget. That budget was sized
 * for a **220 dp chip** inside the bubble's overlay window, and that chip is
 * gone — history is now a full-width row in a floating Activity, which can
 * afford [SnipRow]'s two-line shape. So the *shape* changed and the
 * **character-budget argument survived**: this project's Robolectric returns a
 * flat 1.0 px per character from `TextPaint.measureText`, so a rendered-width
 * claim could be checked nowhere and a character count is the only honest unit.
 * Only the number was wrong.
 *
 * ## 🔴 [PREVIEW_MAX] is NOT derived from [SnipRow.PREVIEW_MAX]
 *
 * The two are the same number today and that is a coincidence of two
 * independent decisions about two different kinds of content — a snippet body
 * and a dictation transcript — rendered in the same one-line full-width slot. A
 * shared constant would assert a relationship that does not exist, so a future
 * change to one would silently move the other. One rule, one place applies to a
 * rule; this is two rules that agree.
 */
object HistoryRow {

    /**
     * How many characters of transcript a preview keeps.
     *
     * See the object kdoc for why this is a character budget and not a width,
     * and for why it is not shared with [SnipRow.PREVIEW_MAX] despite being
     * equal to it.
     */
    const val PREVIEW_MAX: Int = 72

    /** Separates the two halves of [title]. [SnipRow.title]'s separator. */
    private const val SEPARATOR = " · "

    /**
     * The row's first line: `"2h · Server"`.
     *
     * [age] comes first because it is the part the user scans — rows differ by
     * *when* far more reliably than by which engine produced them, and a
     * fixed-width prefix makes the column read as a list. That is
     * [HistoryLabel]'s own argument for the same ordering, kept.
     *
     * [engine] is the EXISTING vocabulary — `@string/engine_badge_remote` /
     * `@string/engine_badge_local`, i.e. "Server" / "Phone" — resolved at the
     * call site because this object has no Android types. Deliberately not a
     * new word for the same distinction: the transcription card already shows
     * that badge, and a second vocabulary for one fact is how two surfaces come
     * to disagree about it.
     */
    fun title(age: String, engine: String): String = age + SEPARATOR + engine

    /**
     * The row's second line: one collapsed, truncated line of [text].
     *
     * 🔴 Newlines are collapsed **before** truncating, for [SnipRow.preview]'s
     * measured reason: a transcript whose first [PREVIEW_MAX] characters
     * contain a newline would otherwise still render as two lines in a row
     * sized for one, clipping the second mid-glyph.
     *
     * @param whenBlank what a transcript-less item shows instead. An item with
     *   no text still has audio and is still re-transcribable, so it must stay
     *   visible and tappable — a row the user cannot see is an item they cannot
     *   reach. Passed in rather than hardcoded because the string is a resource
     *   and this object has no `Context`.
     */
    fun preview(text: String, whenBlank: String): String {
        val oneLine = text.replace(COLLAPSE, " ").trim()
        if (oneLine.isEmpty()) return whenBlank
        return if (oneLine.length <= PREVIEW_MAX) oneLine
        else oneLine.take(PREVIEW_MAX).trimEnd() + "…"
    }

    private val COLLAPSE = Regex("\\s+")
}
