package dev.zacx.dicta

/**
 * The text on a history chip: **pure, short by construction, no Android**.
 *
 * ## Why a character budget and not `ellipsize`
 *
 * A chip is a fixed-width control in an overlay window, so the obvious answer
 * is `maxLines=1` plus `ellipsize=end` and let the platform cut the string.
 * That answer cannot be tested here at all: this repo has measured that
 * Robolectric's `TextPaint.measureText` returns a flat 1.0 px per character
 * regardless of text size and that `Layout.getEllipsisCount` returns **0** for
 * a 4000-character string. Any JVM assertion about what fits is therefore an
 * assertion about Robolectric.
 *
 * So the label is cut *here*, where the thing being asserted is the **returned
 * string's length** — which is state, and which the JVM can speak for. Whether
 * the result visually fits at the device's font scale remains a device check,
 * and nothing in this file pretends otherwise. `ellipsize` stays on the chip
 * as a backstop for a font scale large enough to defeat the budget; it is not
 * the mechanism.
 *
 * ## Shape
 *
 * `"<age> · <first words>"`, e.g. `"2h · the quick brown fox"`. The age comes
 * first because it is the part the user scans — four chips differ by *when*
 * far more reliably than by their opening words, and a fixed-width prefix
 * makes the column read as a list rather than as four ragged sentences.
 */
object HistoryLabel {

    /**
     * The longest label [shortLabel] may return, in characters.
     *
     * Not a rendering measurement and not derived from one — it is the budget
     * the chip is *designed* around, and the device check is what confirms the
     * chip can hold it. Changing it changes one assertion in `HistoryLabelTest`
     * and nothing else.
     */
    const val MAX_CHARS: Int = 34

    /** Separates the age from the words. */
    private const val SEPARATOR = " · "

    /**
     * A chip label for a transcript recorded at [createdAtMs], as of [nowMs].
     *
     * Guaranteed: the result is never longer than [MAX_CHARS], never has
     * leading or trailing whitespace, and contains no newline — a transcript
     * is free text and a raw one would otherwise make a chip as tall as the
     * paragraph it holds.
     *
     * A blank transcript yields the age alone rather than an empty chip: an
     * item with no text still has audio and is still re-transcribable, and a
     * chip the user cannot see is an item they cannot reach.
     */
    fun shortLabel(text: String, createdAtMs: Long, nowMs: Long): String {
        val age = age(createdAtMs, nowMs)
        val words = text.replace(WHITESPACE, " ").trim()
        if (words.isEmpty()) return age
        val room = MAX_CHARS - age.length - SEPARATOR.length
        // Degenerate but reachable if MAX_CHARS is ever lowered: no room for
        // any words at all, so the age alone is the whole label.
        if (room <= 0) return age
        return age + SEPARATOR + cut(words, room)
    }

    /**
     * [words] cut to at most [room] characters, at a word boundary when one is
     * available in the second half of the budget.
     *
     * 🔴 "When one is available" is load-bearing: a transcript can be a single
     * 4000-character token (a dictation of a URL, or a recogniser that emitted
     * no spaces), and a word-boundary cut with no boundary to find would
     * return the whole string. The hard cut is the guarantee; the word
     * boundary is the nicety layered on top of it.
     */
    private fun cut(words: String, room: Int): String {
        if (words.length <= room) return words
        val hard = words.substring(0, room)
        val lastSpace = hard.lastIndexOf(' ')
        return if (lastSpace >= room / 2) hard.substring(0, lastSpace) else hard.trimEnd()
    }

    /**
     * How long ago, in at most three characters: `"now"`, `"9m"`, `"3h"`,
     * `"12d"`.
     *
     * No units longer than days, because the retention bound is two weeks —
     * `"13d"` is the oldest value this can produce from a live store, and a
     * "weeks" unit would be dead code shaped like a feature.
     *
     * A [nowMs] **before** [createdAtMs] yields `"now"` rather than a negative
     * age. The clock can move backwards (an NTP correction, a user changing the
     * time zone), and `"-3h ago"` is a bug report where `"now"` is a rounding
     * error.
     *
     * Days are capped at [MAX_AGE_DAYS], so the output is at most four
     * characters (`"99d+"`).
     *
     * 🔴 **The cap is COSMETIC. It carries no length-safety reason, and the
     * claim that it did is RETRACTED** (round 0 of `#17`'s audit, measured).
     * The retracted claim was that without the cap a forward clock jump drives
     * [shortLabel]'s words budget negative and breaks the [MAX_CHARS]
     * guarantee. It cannot. `room` is `MAX_CHARS - age.length - 3`, so it
     * takes a **31-character** age to reach zero, while the longest string
     * this function can emit without the cap is `"106751991167d"` — 13
     * characters, from `Long.MAX_VALUE` milliseconds — leaving `room = 18`.
     * A decade is `"3652d"`, five characters, not the six that claim asserted.
     * The budget holds with the cap and without it.
     *
     * What the cap actually buys is that `"99d+ · …"` reads better than
     * `"3652d · …"` and leaves more room for words. That is the whole reason;
     * there is no safety argument underneath it.
     *
     * **Measured, not reasoned** — removing the cap and running the suite
     * (556 tests) fails exactly two assertions, and `everyLabelFitsTheBudget`
     * is **not** one of them:
     *  - `anAbsurdAgeIsCappedRatherThanWidening` — the cosmetic guard itself;
     *  - `theLabelUsesTheRoomTheShorterAgeLeaves` — which uses `"99d+"` as a
     *    *fixture*, so it moves with the cap without being about it.
     * That the budget test survives is the whole point: it is the direct
     * evidence that the cap is not what keeps labels inside [MAX_CHARS].
     */
    private fun age(createdAtMs: Long, nowMs: Long): String {
        val elapsed = nowMs - createdAtMs
        if (elapsed < MINUTE_MS) return "now"
        if (elapsed < HOUR_MS) return "${elapsed / MINUTE_MS}m"
        if (elapsed < DAY_MS) return "${elapsed / HOUR_MS}h"
        val days = elapsed / DAY_MS
        return if (days > MAX_AGE_DAYS) "${MAX_AGE_DAYS}d+" else "${days}d"
    }

    /** Above this the age renders as `"99d+"` — see [age]. */
    private const val MAX_AGE_DAYS = 99L

    private val WHITESPACE = Regex("""\s+""")

    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60 * MINUTE_MS
    private const val DAY_MS = 24 * HOUR_MS
}
