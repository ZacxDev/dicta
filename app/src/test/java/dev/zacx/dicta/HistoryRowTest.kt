package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What one **history picker** row says — [HistoryRow].
 *
 * [SnipRowTest]'s file applied to the other picker, which is the point: the two
 * rows are deliberately separate objects (a snippet row and a dictation
 * transcript are different content) and so each gets its own pinning.
 *
 * Pure string work, which is the only part of a row that can be pinned: what it
 * LOOKS like is a pixel claim, and this project's Robolectric cannot measure
 * text at all (`TextPaint.measureText` returns a flat 1.0 px per character,
 * `Layout.getEllipsisCount` returns 0 for a 4000-character string). That is why
 * the budget is a CHARACTER count enforced in pure code — a returned `String`'s
 * `.length` is state the JVM can speak for.
 *
 * 🔴 **These are INVARIANT GUARDS, not regression coverage.** [HistoryRow]
 * arrives with this change, so there is no pre-change tree on which any of them
 * could have been watched failing — a run against the base commit is a compile
 * error. What stands in for red-at-base is the mutant batch in
 * `scripts/mutants/history-picker.tsv`.
 *
 * The claims they carry over from the deleted `HistoryLabelTest` — whose
 * subject was a 34-character chip label — are the budget family and the
 * transcript-shape family. Only the NUMBER changed; see [HistoryRow]'s kdoc.
 */
class HistoryRowTest {

    // --- the title ----------------------------------------------------------

    @Test
    fun theTitleNamesTheAgeThenTheEngine() {
        // 🔴 Age FIRST. Rows differ by WHEN far more reliably than by which
        // engine produced them, so the age is the part the user scans and a
        // short leading column is what makes a list read as a list. Asserting
        // the whole string rather than `contains` pins the ORDER, which a
        // `contains`-style check would be walkable by reversing.
        assertEquals("2h · Server", HistoryRow.title(age = "2h", engine = "Server"))
        assertEquals("now · Phone", HistoryRow.title(age = "now", engine = "Phone"))
        assertEquals("99d+ · Server", HistoryRow.title(age = "99d+", engine = "Server"))
    }

    @Test
    fun theTitleUsesBothOfItsArgumentsAndNeitherIsAConstant() {
        // Positive control. Every assertion above is satisfied by a function
        // that ignores one argument and happens to agree on the fixtures, so
        // the control feeds values that cannot collide: four distinct pairs
        // must produce four distinct titles.
        val titles = setOf(
            HistoryRow.title(age = "2h", engine = "Server"),
            HistoryRow.title(age = "2h", engine = "Phone"),
            HistoryRow.title(age = "3h", engine = "Server"),
            HistoryRow.title(age = "3h", engine = "Phone"),
        )
        assertEquals(
            "two of the four (age, engine) pairs produced the same title, so " +
                "one of the two arguments is not reaching the output",
            4,
            titles.size,
        )
    }

    @Test
    fun theEngineVocabularyIsNotInventedHere() {
        // 🔴 The engine word is RESOLVED AT THE CALL SITE from
        // @string/engine_badge_remote / @string/engine_badge_local — the badge
        // the transcription card already shows. This object must therefore
        // contain no engine word of its own: a second vocabulary for one fact
        // is how two surfaces come to disagree about it.
        //
        // Pinned as a property of the SOURCE, because a behavioural test cannot
        // see a hardcoded fallback that the fixtures never reach.
        val src = java.io.File("app/src/main/java/dev/zacx/dicta/HistoryRow.kt")
            .takeIf { it.isFile }
            ?: java.io.File("../app/src/main/java/dev/zacx/dicta/HistoryRow.kt")
        assertTrue(
            "could not read HistoryRow.kt, so this result is a fact about a " +
                "path rather than about the source",
            src.isFile,
        )
        val code = src.readText()
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("//[^\\n]*"), " ")
        for (word in listOf("Server", "Phone", "On-device", "Remote", "Local")) {
            assertFalse(
                "HistoryRow.kt hardcodes the engine word \"$word\" outside a " +
                    "comment. The badge belongs to the existing string " +
                    "resources and is passed in; a copy here is a second " +
                    "vocabulary for one fact.",
                code.contains(word),
            )
        }
        // POSITIVE CONTROL: a zero above is indistinguishable from reading an
        // empty string, so prove the scan is looking at real source.
        assertTrue(
            "the stripped source is empty, so the five absences above say " +
                "nothing",
            code.contains("fun title") && code.contains("fun preview"),
        )
    }

    // --- the preview --------------------------------------------------------

    @Test
    fun aMultiLineTranscriptIsCollapsedToOneLineBeforeItIsTruncated() {
        // 🔴 The ORDERING is the assertion, for SnipRow.preview's measured
        // reason: collapsing AFTER truncating leaves a newline inside a row
        // sized for one line, which clips the second line mid-glyph. The
        // fixture's newlines sit well inside the budget, so a collapse-last
        // implementation keeps them and goes red here.
        assertEquals(
            "first second third",
            HistoryRow.preview("first\n  second\n\n\tthird\n", "nothing"),
        )
    }

    @Test
    fun aLongTranscriptIsTruncatedWithAnEllipsisAndAnExactBudget() {
        // 🔴 The fixture OVERSHOOTS the budget by a non-multiple (+17) rather
        // than landing on it: a transcript of exactly PREVIEW_MAX characters
        // passes against an off-by-one boundary, and a multiple of it passes
        // against the wrong operand.
        val text = "x".repeat(HistoryRow.PREVIEW_MAX + 17)
        val preview = HistoryRow.preview(text, "nothing")
        assertEquals("x".repeat(HistoryRow.PREVIEW_MAX) + "…", preview)
        assertTrue(
            "the preview is as long as the transcript, so nothing was truncated",
            preview.length < text.length,
        )
    }

    @Test
    fun aTranscriptInsideTheBudgetIsShownWholeWithNoEllipsis() {
        // The negative control for the test above: a truncator that always
        // appended an ellipsis would pass it.
        val text = "x".repeat(HistoryRow.PREVIEW_MAX - 1)
        assertEquals(text, HistoryRow.preview(text, "nothing"))
        assertFalse(text.contains("…"))
    }

    @Test
    fun aSingleEnormousWordIsCutHard() {
        // 🔴 The case a word-boundary-only truncator returns WHOLE: a dictation
        // of a URL, or a recogniser that emitted no spaces at all. Carried over
        // from the deleted HistoryLabelTest, where it was the same claim
        // against a 34-character chip budget.
        val preview = HistoryRow.preview("x".repeat(4000), "nothing")
        assertEquals(HistoryRow.PREVIEW_MAX + 1, preview.length)   // +1 for the "…"
        assertTrue(preview.startsWith("xxx"))
        assertTrue(preview.endsWith("…"))
    }

    @Test
    fun aTruncationLeavesNoTrailingSpaceBeforeTheEllipsis() {
        // "… " renders as a gap the user reads as a rendering fault. The
        // fixture puts a space exactly ON the cut, which is the only input
        // that distinguishes a trimEnd from its absence.
        val text = "a".repeat(HistoryRow.PREVIEW_MAX - 1) + " " + "b".repeat(40)
        val preview = HistoryRow.preview(text, "nothing")
        assertFalse(
            "the cut left a space before the ellipsis: \"$preview\"",
            preview.contains(" …"),
        )
        assertEquals("a".repeat(HistoryRow.PREVIEW_MAX - 1) + "…", preview)
    }

    @Test
    fun aBlankTranscriptShowsTheSuppliedFallbackAndNeverNothing() {
        // 🔴 An item with no text still has AUDIO and is still
        // re-transcribable, so the row must stay visible and tappable: a row
        // the user cannot see is an item they cannot reach. The fallback is
        // passed in rather than hardcoded because it is a string resource and
        // this object has no Context.
        assertEquals("nothing", HistoryRow.preview("", "nothing"))
        assertEquals("nothing", HistoryRow.preview("   ", "nothing"))
        assertEquals("nothing", HistoryRow.preview("  \n\t  ", "nothing"))
        // And the fallback is genuinely the ARGUMENT, not a constant that
        // happens to match it.
        assertEquals("(no transcript)", HistoryRow.preview("", "(no transcript)"))
    }

    @Test
    fun aPreviewIsOneLineWithNoSurroundingSpace() {
        // A transcript is free text. Pasted raw, the row is as tall as the
        // paragraph it holds and stops being a row. Carried over from the
        // deleted HistoryLabelTest.aLabelIsOneLineWithNoSurroundingSpace.
        val preview = HistoryRow.preview("first line\n\n  second   line\t third ", "nothing")
        assertFalse("the preview contains a newline: \"$preview\"", preview.contains('\n'))
        assertFalse("the preview contains a tab: \"$preview\"", preview.contains('\t'))
        assertEquals("the preview has surrounding space", preview.trim(), preview)
        assertFalse(
            "the preview has a run of repeated spaces: \"$preview\"",
            preview.contains("  "),
        )
    }

    @Test
    fun twoDifferentTranscriptsProduceTwoDifferentPreviews() {
        // Positive control against an implementation that returns a constant:
        // the blankness and length assertions above are all satisfied by
        // `fun preview(t, w) = "…"`.
        assertNotEquals(
            HistoryRow.preview("alpha bravo", "nothing"),
            HistoryRow.preview("charlie delta", "nothing"),
        )
    }

    // --- the budget is NOT shared with the snip row --------------------------

    @Test
    fun theTwoPreviewBudgetsAreIndependentConstantsEvenWhileTheyAgree() {
        // 🔴 [HistoryRow.PREVIEW_MAX] and [SnipRow.PREVIEW_MAX] are equal TODAY
        // and that is a coincidence of two independent decisions about two
        // kinds of content. A shared constant would assert a relationship that
        // does not exist, so a future change to one would silently move the
        // other.
        //
        // Pinned on the SOURCE, because no behavioural test can tell a
        // duplicated literal from an aliased one: `const val PREVIEW_MAX =
        // SnipRow.PREVIEW_MAX` is observationally identical to `= 72` and is
        // exactly the coupling this refuses.
        val src = listOf(
            java.io.File("app/src/main/java/dev/zacx/dicta/HistoryRow.kt"),
            java.io.File("../app/src/main/java/dev/zacx/dicta/HistoryRow.kt"),
        ).first { it.isFile }.readText()
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("//[^\\n]*"), " ")
        assertTrue(
            "HistoryRow.kt declares no PREVIEW_MAX, so this test read the " +
                "wrong thing",
            Regex("""const\s+val\s+PREVIEW_MAX""").containsMatchIn(src),
        )
        assertFalse(
            "HistoryRow.PREVIEW_MAX is DERIVED from SnipRow's. The two are " +
                "equal by coincidence, not by rule — see HistoryRow's kdoc — " +
                "so deriving it would make a change to the snippet row " +
                "silently move the transcript row.",
            src.contains("SnipRow"),
        )
    }
}
