package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HistoryLabel]: the text on a history chip.
 *
 * 🔴 **Length only. Never "does it fit".** This repo has measured that
 * Robolectric's `TextPaint.measureText` returns a flat 1.0 px per character
 * and that `Layout.getEllipsisCount` returns 0 for a 4000-character string, so
 * every assertion about rendered width in this project is an assertion about
 * Robolectric. The budget is enforced here, where the subject is a returned
 * `String` and the measurement is `.length`; whether 34 characters actually
 * fit a 220 dp chip at the device's font scale is a screenshot.
 */
class HistoryLabelTest {

    private val t0 = 1_700_000_000_000L

    private fun label(text: String, agoMs: Long = 0L): String =
        HistoryLabel.shortLabel(text, t0, t0 + agoMs)

    // --- the budget, which is the whole point -------------------------------

    @Test
    fun everyLabelFitsTheBudget() {
        // The inputs that break a naive implementation, each for a different
        // reason: a long ordinary transcript, a single enormous token with no
        // word boundary to cut at, one that is all whitespace, an empty one,
        // and an age large enough to need its own cap.
        val cases = mapOf(
            "an ordinary long transcript" to LOREM,
            "one 4000-character word" to "x".repeat(4000),
            "a newline-ridden transcript" to "first line\n\nsecond line\tthird",
            "whitespace only" to "   \n\t  ",
            "empty" to "",
            "exactly at the budget" to "y".repeat(HistoryLabel.MAX_CHARS),
        )
        for ((name, text) in cases) {
            for (agoMs in listOf(0L, MINUTE, HOUR, DAY, 400L * DAY)) {
                val out = HistoryLabel.shortLabel(text, t0, t0 + agoMs)
                assertTrue(
                    "<$name> at ${agoMs}ms ago produced a ${out.length}-character " +
                        "label, over the ${HistoryLabel.MAX_CHARS} budget: \"$out\"",
                    out.length <= HistoryLabel.MAX_CHARS,
                )
            }
        }
    }

    @Test
    fun aLabelIsOneLineWithNoSurroundingSpace() {
        // A transcript is free text. Pasted raw, a chip is as tall as the
        // paragraph it holds and the row stops being a row.
        val out = label("first line\n\n  second   line\t third ", HOUR)
        assertFalse("the label contains a newline: \"$out\"", out.contains('\n'))
        assertFalse("the label contains a tab: \"$out\"", out.contains('\t'))
        assertEquals("the label has surrounding space: \"$out\"", out.trim(), out)
        assertFalse(
            "the label has a run of repeated spaces: \"$out\"",
            out.contains("  "),
        )
    }

    // --- the age ------------------------------------------------------------

    @Test
    fun theAgeUnitFollowsHowLongAgoItWas() {
        assertEquals("now · hi", label("hi", 0L))
        assertEquals("now · hi", label("hi", MINUTE - 1))
        assertEquals("1m · hi", label("hi", MINUTE))
        assertEquals("59m · hi", label("hi", 59 * MINUTE))
        assertEquals("1h · hi", label("hi", HOUR))
        assertEquals("23h · hi", label("hi", 23 * HOUR))
        assertEquals("1d · hi", label("hi", DAY))
        assertEquals("13d · hi", label("hi", 13 * DAY))
    }

    @Test
    fun aClockThatMovedBackwardsReadsAsNow() {
        // NTP corrections and time-zone changes are real; "-3h" is a bug
        // report where "now" is a rounding error.
        assertEquals("now · hi", HistoryLabel.shortLabel("hi", t0, t0 - 5 * HOUR))
    }

    @Test
    fun anAbsurdAgeIsCappedRatherThanWidening() {
        // 🔴 INVARIANT GUARD, NOT REGRESSION COVERAGE — and the claim that it
        // was the latter is RETRACTED (round 0 of #17's audit, measured).
        // It used to say this guard makes the budget a guarantee, because a
        // decade-long clock jump would yield a six-character age and drive the
        // words budget negative. Both halves are false: a decade is "3652d"
        // (five chars), and `room` needs a 31-character age to reach zero
        // while the longest age representable is "106751991167d" (13 chars,
        // from Long.MAX_VALUE ms). `everyLabelFitsTheBudget` cannot be broken
        // by removing the cap, so this test has never protected it.
        //
        // What it does pin is the COSMETIC choice: an absurd age renders
        // "99d+" rather than widening.
        //
        // 🔴 Scope, MEASURED by removing the cap and running all 556 tests —
        // an earlier draft of this comment said "deleting the cap fails THIS
        // assertion and nothing else" and that was FALSE. It fails TWO:
        // this one, and `theLabelUsesTheRoomTheShorterAgeLeaves`, which uses
        // "99d+" only as a fixture. `everyLabelFitsTheBudget` stays GREEN,
        // which is the direct evidence that the cap does not protect the
        // length guarantee.
        val out = label("hi", 4000L * DAY)
        assertTrue("an absurd age was not capped: \"$out\"", out.startsWith("99d+"))
        assertTrue(out.length <= HistoryLabel.MAX_CHARS)
    }

    // --- the words ----------------------------------------------------------

    @Test
    fun aShortTranscriptIsNotTouched() {
        assertEquals("2h · the quick brown fox", label("the quick brown fox", 2 * HOUR))
    }

    @Test
    fun aBlankTranscriptStillProducesAChipTheUserCanSee() {
        // The item still has audio and is still re-transcribable, so a chip
        // that renders as nothing is an item the user cannot reach.
        assertEquals("3h", label("", 3 * HOUR))
        assertEquals("3h", label("   \n ", 3 * HOUR))
    }

    @Test
    fun aLongTranscriptIsCutAtAWordBoundary() {
        val out = label(LOREM, 2 * HOUR)
        assertTrue("\"$out\" does not start with the age", out.startsWith("2h · "))
        val words = out.removePrefix("2h · ")
        assertTrue(
            "\"$words\" is not a prefix of the transcript at all",
            LOREM.startsWith(words),
        )
        assertTrue(
            "the cut landed mid-word: the transcript continues \"" +
                LOREM.substring(words.length).take(12) + "\" after \"$words\"",
            words.length == LOREM.length || LOREM[words.length] == ' ',
        )
        assertEquals("the cut left surrounding space", words.trim(), words)
    }

    @Test
    fun aSingleEnormousWordIsCutHard() {
        // 🔴 The case a word-boundary-only implementation returns whole: a
        // dictation of a URL, or a recogniser that emitted no spaces at all.
        val out = label("x".repeat(4000), 2 * HOUR)
        assertEquals(HistoryLabel.MAX_CHARS, out.length)
        assertTrue(out.startsWith("2h · xxx"))
    }

    @Test
    fun theLabelUsesTheRoomTheShorterAgeLeaves() {
        // 🔴 Isolates the "room = budget - age - separator" arithmetic, and it
        // needs a transcript with NO word boundaries: with ordinary prose the
        // word-boundary cut lands on the same space for both ages, so the two
        // labels come out identical and a version ignoring the age's length
        // survives. A single long token makes the cut exact.
        val token = "x".repeat(4000)
        val shortAge = label(token, 2 * HOUR).removePrefix("2h · ")
        val longAge = label(token, 4000L * DAY).removePrefix("99d+ · ")
        assertEquals(
            "the two-character age did not leave the budget minus \"2h · \"",
            HistoryLabel.MAX_CHARS - "2h · ".length,
            shortAge.length,
        )
        assertTrue(
            "the \"99d+\" label carries ${longAge.length} characters of " +
                "transcript and the \"2h\" one carries ${shortAge.length}, so " +
                "the age's own length is not being subtracted from the budget",
            longAge.length < shortAge.length,
        )
    }

    @Test
    fun twoDifferentTranscriptsProduceTwoDifferentLabels() {
        // Positive control against an implementation that returns a constant:
        // every assertion above is satisfied by `fun shortLabel(...) = "now"`.
        assertFalse(
            "two different transcripts at two different ages produced the " +
                "same label",
            label("alpha bravo", HOUR) == label("charlie delta", 2 * HOUR),
        )
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR

        const val LOREM =
            "the quick brown fox jumps over the lazy dog and keeps on going " +
                "for considerably longer than any chip could hold"
    }
}
