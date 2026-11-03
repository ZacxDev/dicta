package dev.zacx.dicta

/**
 * How long ago a stored dictation was recorded: **pure, short by construction,
 * no Android**.
 *
 * ## 🔴 `shortLabel` AND `MAX_CHARS` WERE HERE AND ARE DELETED
 *
 * `shortLabel(text, createdAtMs, nowMs)` returned a whole chip label —
 * `"2h · the quick brown fox"` — cut to a `MAX_CHARS = 34` budget. Both are
 * gone with the chip they were sized for: `MAX_CHARS` was the budget of a
 * **220 dp chip** in the bubble's overlay window, and the long-press reveal no
 * longer holds a list at all. History is now a full-width row in
 * [HistoryPickerActivity], which affords the two-line shape [HistoryRow]
 * defines.
 *
 * 🔴 The *character-budget* argument is NOT retracted — it moved. It said: this
 * repo has measured that Robolectric's `TextPaint.measureText` returns a flat
 * 1.0 px per character regardless of text size and that
 * `Layout.getEllipsisCount` returns **0** for a 4000-character string, so any
 * JVM assertion about what *fits* is an assertion about Robolectric. Cutting
 * the string in pure code makes the assertion about the returned string's
 * **length**, which is state the JVM can speak for. That reasoning now lives on
 * [HistoryRow.PREVIEW_MAX]; only the number was wrong.
 *
 * What stayed here is [age], because it is a different kind of thing: not a
 * budget, a clock reading — and it is still the first half of every history
 * row, for the reason [HistoryRow.title] records.
 */
object HistoryLabel {

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
     * the deleted `shortLabel`'s words budget negative and breaks its
     * `MAX_CHARS` guarantee. It could not: that budget was
     * `MAX_CHARS - age.length - 3`, so it took a **31-character** age to reach
     * zero, while the longest string this function can emit without the cap is
     * `"106751991167d"` — 13 characters, from `Long.MAX_VALUE` milliseconds. A
     * decade is `"3652d"`, five characters, not the six that claim asserted.
     *
     * ⚠ That retraction's own *measurement* — "removing the cap fails exactly
     * two assertions, and `everyLabelFitsTheBudget` is not one of them" — was
     * taken against a tree where `shortLabel` still existed, and both named
     * assertions went with it. It is kept because it is the evidence for the
     * sentence above it; it is **not** re-runnable on this tree.
     *
     * What the cap actually buys is that `"99d+ · Server"` reads better than
     * `"3652d · Server"`. That is the whole reason; there is no safety argument
     * underneath it, and `anAbsurdAgeIsCappedRatherThanWidening` is the only
     * thing pinning it.
     */
    fun age(createdAtMs: Long, nowMs: Long): String {
        val elapsed = nowMs - createdAtMs
        if (elapsed < MINUTE_MS) return "now"
        if (elapsed < HOUR_MS) return "${elapsed / MINUTE_MS}m"
        if (elapsed < DAY_MS) return "${elapsed / HOUR_MS}h"
        val days = elapsed / DAY_MS
        return if (days > MAX_AGE_DAYS) "${MAX_AGE_DAYS}d+" else "${days}d"
    }

    /** Above this the age renders as `"99d+"` — see [age]. */
    private const val MAX_AGE_DAYS = 99L

    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60 * MINUTE_MS
    private const val DAY_MS = 24 * HOUR_MS
}
