package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HistoryLabel.age]: how long ago a stored dictation was recorded.
 *
 * ## 🔴 THIS FILE LOST MOST OF ITSELF, AND THE SUBJECT IS WHY
 *
 * It used to be nine tests about `HistoryLabel.shortLabel` — a whole chip
 * label, `"2h · the quick brown fox"`, cut to a `MAX_CHARS = 34` budget. Both
 * the function and the constant are DELETED with the chip they were sized for:
 * `MAX_CHARS` was the budget of a 220 dp chip inside the bubble's overlay
 * window, and the long-press reveal no longer holds a list at all.
 *
 * The tests that went with them, and where each one's claim went:
 *
 *  - `everyLabelFitsTheBudget`, `aSingleEnormousWordIsCutHard`,
 *    `aLongTranscriptIsCutAtAWordBoundary`, `theLabelUsesTheRoomTheShorterAgeLeaves`
 *    — the character-budget family. The ARGUMENT survives and the number did
 *    not; it is now [HistoryRowTest]'s, over [HistoryRow.PREVIEW_MAX].
 *  - `aLabelIsOneLineWithNoSurroundingSpace`, `aShortTranscriptIsNotTouched`,
 *    `aBlankTranscriptStillProducesAChipTheUserCanSee`,
 *    `twoDifferentTranscriptsProduceTwoDifferentLabels` — the transcript's
 *    shape. Also [HistoryRowTest]'s, over `HistoryRow.preview`.
 *  - `anAbsurdAgeIsCappedRatherThanWidening` — about the age, so it STAYED,
 *    minus the `MAX_CHARS` half of its assertion, which no longer exists.
 *
 * 🔴 Nothing here was watched failing on pre-change code, and nothing could be:
 * [HistoryLabel.age] is untouched by this change. These are **invariant
 * guards**, carried over verbatim in claim if not in spelling.
 *
 * 🔴 **Length and value only. Never "does it fit".** This repo has measured
 * that Robolectric's `TextPaint.measureText` returns a flat 1.0 px per
 * character and that `Layout.getEllipsisCount` returns 0 for a 4000-character
 * string, so every assertion about rendered width in this project is an
 * assertion about Robolectric. Whether `"99d+"` actually fits the row at the
 * device's font scale is a screenshot.
 */
class HistoryLabelTest {

    private val t0 = 1_700_000_000_000L

    private fun age(agoMs: Long): String = HistoryLabel.age(t0, t0 + agoMs)

    @Test
    fun theAgeUnitFollowsHowLongAgoItWas() {
        // Every boundary and one value inside each band, so a `<` that became
        // a `<=` — or a band dropped outright — is visible. The pairs at
        // MINUTE-1/MINUTE and 59m/1h are the ones that catch an off-by-one.
        assertEquals("now", age(0L))
        assertEquals("now", age(MINUTE - 1))
        assertEquals("1m", age(MINUTE))
        assertEquals("9m", age(9 * MINUTE))
        assertEquals("59m", age(59 * MINUTE))
        assertEquals("1h", age(HOUR))
        assertEquals("3h", age(3 * HOUR))
        assertEquals("23h", age(23 * HOUR))
        assertEquals("1d", age(DAY))
        assertEquals("12d", age(12 * DAY))
        assertEquals("13d", age(13 * DAY))
    }

    @Test
    fun aClockThatMovedBackwardsReadsAsNow() {
        // NTP corrections and time-zone changes are real; "-3h" is a bug
        // report where "now" is a rounding error.
        assertEquals("now", HistoryLabel.age(t0, t0 - 5 * HOUR))
        assertEquals("now", HistoryLabel.age(t0, t0 - 4000L * DAY))
    }

    @Test
    fun anAbsurdAgeIsCappedRatherThanWidening() {
        // 🔴 INVARIANT GUARD, NOT REGRESSION COVERAGE — and the claim that it
        // was the latter is RETRACTED (round 0 of #17's audit, measured).
        // It used to say this guard makes a length budget a guarantee, because
        // a decade-long clock jump would yield a six-character age and drive
        // the deleted `shortLabel`'s words budget negative. Both halves were
        // false: a decade is "3652d" (five chars), and that budget needed a
        // 31-character age to reach zero while the longest age representable is
        // "106751991167d" (13 chars, from Long.MAX_VALUE ms).
        //
        // ⚠ That retraction's own measurement — "removing the cap fails
        // exactly two assertions, and `everyLabelFitsTheBudget` is not one of
        // them" — was taken against a tree where `shortLabel` still existed,
        // and both named assertions went with it. It is kept as the evidence
        // for the sentence above; it is NOT re-runnable on this tree, and the
        // assertion below is now the ONLY thing pinning the cap.
        //
        // What it pins is the COSMETIC choice: an absurd age renders "99d+"
        // rather than widening, because "99d+ · Server" reads better than
        // "3652d · Server". There is no safety argument underneath it.
        assertEquals("99d+", age(4000L * DAY))
        assertEquals("99d+", age(100L * DAY))
        // The boundary itself, from the inside: 99 days is not capped.
        assertEquals("99d", age(99L * DAY))
    }

    @Test
    fun theAgeIsShortEnoughToPrefixARowWithoutDominatingIt() {
        // The reason `age` exists at all rather than a formatted date: it is
        // the fixed-width-ish prefix that makes a column of rows scannable
        // (HistoryRow.title's argument). Four characters is the most it can
        // emit, which is the claim worth pinning — a "weeks" or "months" unit
        // added later would break it and should be a deliberate decision.
        //
        // Swept across every band plus the cap, so this is not satisfied by
        // one lucky value.
        val probes = listOf(
            0L, MINUTE - 1, MINUTE, 59 * MINUTE, HOUR, 23 * HOUR,
            DAY, 13 * DAY, 99L * DAY, 4000L * DAY, Long.MAX_VALUE / 2,
        )
        for (ago in probes) {
            val out = age(ago)
            assertTrue(
                "the age at ${ago}ms ago is ${out.length} characters (\"$out\"), " +
                    "over the four \"99d+\" needs",
                out.length <= 4,
            )
            assertFalse("the age at ${ago}ms ago is blank", out.isBlank())
        }
    }

    @Test
    fun twoDifferentAgesProduceTwoDifferentStrings() {
        // Positive control against an implementation that returns a constant:
        // every assertion above about blankness and length is satisfied by
        // `fun age(a: Long, b: Long) = "now"`, and most of this file would
        // still be green. Three distinct values, pairwise, so a function that
        // collapsed two bands is caught too.
        val distinct = setOf(age(0L), age(9 * MINUTE), age(3 * HOUR), age(12 * DAY))
        assertEquals(
            "two of \"now\"/9m/3h/12d came out the same, so the age is not a " +
                "function of the elapsed time",
            4,
            distinct.size,
        )
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
