package dev.zacx.dicta

/**
 * What one picker row says. Pure string work, so it is pinned rather than being
 * device evidence — the same split [HistoryLabel] makes for the history chips.
 *
 * ## Why a row must NOT show the whole body
 *
 * Sized against a real catalog: bodies reach 747 characters and are multi-line
 * (long prompt snippets). A row rendering the body would be a screenful per
 * entry, so the row shows the label and its category, plus a **truncated,
 * single-line** preview that exists only to disambiguate two similarly-named
 * entries.
 */
object SnipRow {

    /**
     * How many characters of body a preview keeps.
     *
     * A character budget rather than a width: this project's Robolectric cannot
     * measure text at all (`TextPaint.measureText` returns a flat 1.0 px per
     * character), so a rendered-width claim could not be checked anywhere. The
     * view's own `ellipsize` is the backstop for whatever the device does with
     * these characters.
     */
    const val PREVIEW_MAX: Int = 72

    /** `"Open the mesh dashboard · path"`, or just the label with no category. */
    fun title(snippet: Snippet): String =
        if (snippet.category.isBlank()) snippet.label
        else "${snippet.label} · ${snippet.category}"

    /**
     * One line of [body], collapsed and truncated.
     *
     * 🔴 Newlines are collapsed **before** truncating, not after. A body whose
     * first 72 characters contain a newline would otherwise still render as two
     * lines in a row sized for one, which clips the second one mid-glyph.
     */
    fun preview(body: String): String {
        val oneLine = body.replace(COLLAPSE, " ").trim()
        return if (oneLine.length <= PREVIEW_MAX) oneLine
        else oneLine.take(PREVIEW_MAX).trimEnd() + "…"
    }

    private val COLLAPSE = Regex("\\s+")
}
