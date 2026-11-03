package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What one picker row says — [SnipRow].
 *
 * Pure string work, which is the only part of the row that can be pinned: what
 * it LOOKS like is a pixel claim, and this project's Robolectric cannot measure
 * text at all (`TextPaint.measureText` returns a flat 1.0 px per character).
 */
class SnipRowTest {

    private fun snip(label: String, body: String, category: String) =
        Snippet(label = label, body = body, searchTerms = emptyList(), category = category)

    @Test
    fun theTitleNamesTheSnippetAndItsCategory() {
        assertEquals(
            "Quince jam · prompt",
            SnipRow.title(snip("Quince jam", "boil", "prompt")),
        )
    }

    @Test
    fun aRowWithNoCategoryShowsNoTrailingSeparator() {
        // A row from a catalog that omits the field must not read
        // "Quince jam · ", which looks like a rendering fault.
        assertEquals("Quince jam", SnipRow.title(snip("Quince jam", "boil", "")))
        assertEquals("Quince jam", SnipRow.title(snip("Quince jam", "boil", "   ")))
    }

    @Test
    fun aMultiLineBodyIsCollapsedToOneLineBeforeItIsTruncated() {
        // 🔴 The ordering is the assertion. Collapsing AFTER truncating leaves
        // a newline inside a row sized for one line, which clips the second
        // line mid-glyph — and the fixture's newline sits well inside the
        // budget so a collapse-last implementation would keep it.
        assertEquals(
            "first second third",
            SnipRow.preview("first\n  second\n\n\tthird\n"),
        )
    }

    @Test
    fun aLongBodyIsTruncatedWithAnEllipsisAndAnExactBudget() {
        // The fixture OVERSHOOTS the budget rather than landing on it: a body
        // of exactly PREVIEW_MAX characters would pass against an off-by-one
        // boundary, and a multiple of it would pass against the wrong operand.
        val body = "x".repeat(SnipRow.PREVIEW_MAX + 17)
        val preview = SnipRow.preview(body)
        assertEquals("x".repeat(SnipRow.PREVIEW_MAX) + "…", preview)
        assertTrue(
            "the preview is as long as the body, so nothing was truncated",
            preview.length < body.length,
        )
    }

    @Test
    fun aBodyInsideTheBudgetIsShownWholeWithNoEllipsis() {
        // The negative control for the test above: a truncator that always
        // appended an ellipsis would pass it.
        val body = "x".repeat(SnipRow.PREVIEW_MAX - 1)
        assertEquals(body, SnipRow.preview(body))
    }
}
