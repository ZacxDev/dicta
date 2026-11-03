package dev.zacx.dicta

import android.view.View
import android.widget.TextView

/**
 * The history picker: search the stored dictations, tap a row, the card opens
 * on that one.
 *
 * ## What it replaced, and why that was a SCOPE CHANGE
 *
 * A long press on the bubble used to reveal a `ListView` of history **chips**
 * inside the bubble's own overlay window. `BubbleService.REVEAL_SLOTS` stated
 * the intent of that design: *"this is a quick-access affordance, not a
 * browser."* 🔴 **That sentence is retired, not deleted and not still true.** A
 * search field over a scrollable Activity IS the browser it says this is not,
 * and the operator asked for it knowingly (2026-11-03). What it bought and what
 * it cost are both in the pull request; the cost in one line is that reopening a
 * past dictation went from two gestures to three.
 *
 * ## 🔴 Search is [SnipMatcher], unchanged, and NOT a second matcher
 *
 * One matching rule in the app, so a multi-word query behaves identically in
 * both pickers. [SnipMatcher] is a faithful port of `ZacxDev/snip`'s own
 * matcher and its kdoc forbids improving it in place, so the adaptation happens
 * HERE, at the call site: each item is presented to the matcher as a row whose
 * `label` is the transcript and whose `search_terms` are empty
 * ([searchViewOf]).
 *
 * 🔴 **A transcript's punctuation splits words, and that is correct.**
 * `SnipMatcher.labelWords` is `[a-z0-9]+` over the lowercased string, so
 * hyphens are not word characters: `"kubeconfig-homelab"` tokenises to
 * `["kubeconfig", "homelab"]` and a query token containing a hyphen can match
 * no word at all. That is measured upstream history rather than an accident of
 * a regex — a hyphen-keeping tokenizer there invented false findings until it
 * was replaced — and `HistoryPickerActivityTest` pins it on transcript text
 * deliberately, so it does not arrive later as a bug report.
 *
 * ## 🔴 Why [filterRows] ranks ONE ROW AT A TIME
 *
 * [SnipMatcher.rank] returns `Snippet`s, and this picker needs `Item`s back —
 * but a transcript is not unique, so the returned rows cannot be mapped back by
 * value. Ranking each row on its own avoids that entirely and is **exactly**
 * equivalent here, because every row's `search_terms` are empty: `rank`'s
 * "declared" group is then always empty, so its output is its input filtered
 * by `termMatches` with the supplied order preserved. The equivalence is not
 * left as this paragraph —
 * `HistoryPickerActivityTest.rankingRowByRowAgreesWithRankingTheWholeList`
 * asserts it against the whole-list call, so a future change to `rank`'s
 * grouping that breaks it goes red here.
 *
 * It also keeps `rank`'s BROWSE branch as the blank-query rule rather than
 * re-deriving it: a one-element list with a blank query returns that element.
 *
 * ## 🔴 It can never start a recording
 *
 * There is no `DictationSession` here, no `MicCapture`, no permission request
 * and no `BubbleDictation` event raised. The one thing it starts is the card,
 * through [DictateLaunch.historyIntent] — which carries `EXTRA_HISTORY_ID`, so
 * `DictateActivity` populates from history and does **not** capture. See
 * [HistoryLaunch] for why that leaves `StartSurfaceLedgerTest`'s ledger of
 * recording surfaces unchanged, and `PickerShellSeamLedgerTest` for the
 * assertion that this file names no OTHER microphone symbol.
 */
class HistoryPickerActivity : FloatingPickerActivity<DictationHistoryStore.Item>() {

    /**
     * Where the rows come from. `null` means the real [DictationHistoryStore].
     *
     * A seam rather than a direct call because the production read touches the
     * filesystem — so a test driving the picker would otherwise be a test of
     * the store as well, and would be at the mercy of whatever the previous
     * test left in `noBackupFilesDir`. `BubbleService.historyProvider` was the
     * same seam in the same shape and is deleted with the chip reveal; this is
     * where it went.
     *
     * Returning `null` means "could not be read", which is a DIFFERENT empty
     * state from an empty list — see [FloatingPickerActivity.emptyUnreadableRes].
     */
    internal var source: ((Int) -> List<DictationHistoryStore.Item>?)? = null

    override val titleRes: Int = R.string.history_title
    override val searchHintRes: Int = R.string.history_search_hint
    override val emptyUnreadableRes: Int = R.string.history_empty_unreadable
    override val emptyNothingStoredRes: Int = R.string.history_empty_none
    override val emptyNoMatchesRes: Int = R.string.history_empty_no_matches
    override val rowLayoutRes: Int = R.layout.history_row

    override fun readRows(): List<DictationHistoryStore.Item>? {
        val provider = source ?: { limit -> DictationHistoryStore(this).recent(limit) }
        return provider(MAX_ITEMS)
    }

    /**
     * Every row whose transcript matches [query], in store order (newest
     * first). A blank query is BROWSE.
     *
     * See the class kdoc for why this ranks row by row and why that is exact.
     */
    override fun filterRows(
        rows: List<DictationHistoryStore.Item>,
        query: String,
    ): List<DictationHistoryStore.Item> =
        rows.filter { SnipMatcher.rank(listOf(searchViewOf(it)), query).isNotEmpty() }

    /**
     * 🔴 Every per-item field is set on every call, including the ones being
     * set to a fallback. A recycled `convertView` arrives wearing the PREVIOUS
     * row's text, `contentDescription` and `tooltipText`, and a bind that only
     * sets some of them leaves a row announcing a dictation it no longer shows
     * — which reads as a history-store bug and is not one. See
     * [FloatingPickerActivity]'s kdoc; the hazard is the one
     * `BubbleService.bindChip` documented before it was deleted.
     */
    override fun bindRow(view: View, row: DictationHistoryStore.Item) {
        val title = HistoryRow.title(
            age = HistoryLabel.age(row.createdAtMs, System.currentTimeMillis()),
            engine = getString(
                if (row.fromRemote) R.string.engine_badge_remote
                else R.string.engine_badge_local
            ),
        )
        val preview = HistoryRow.preview(row.text, getString(R.string.history_no_transcript))
        view.findViewById<TextView>(R.id.history_row_title).text = title
        view.findViewById<TextView>(R.id.history_row_preview).text = preview
        // 🔴 Says what tapping DOES, not only what the row IS. "2h · Server"
        // plus a transcript announces the item; a screen-reader user meeting a
        // row of free text has no way to know it is a button.
        view.contentDescription = getString(R.string.history_row_desc, preview)
    }

    /**
     * Opens the card on a stored dictation.
     *
     * 🔴 Through [DictateLaunch.historyIntent], never a hand-built `Intent`.
     * Same mechanism as `BubbleService.requestMicPermission` and the same bug
     * (#11) if it is skipped: the overlay must land in its OWN task or the
     * translucent card renders over Dicta's own UI.
     * `DictateLaunchTest.onlyDictateLaunchConstructsOverlayIntents` is the
     * ledger that makes that a red test rather than a code review — *calling*
     * this from here is fine, *constructing* an overlay intent here is not.
     *
     * 🔴 And `historyIntent`, never `intent` or `transcribingIntent`. A plain
     * `DictateActivity` launch auto-starts a capture, which is why
     * `StartSurfaceLedgerTest` counts those call sites; the history extra is
     * what makes this a read of something already recorded.
     *
     * Nothing finishes the activity here — the shell does that for both
     * pickers, unconditionally. See [FloatingPickerActivity].
     */
    override fun onRowPicked(row: DictationHistoryStore.Item) {
        runCatching { startActivity(DictateLaunch.historyIntent(this, row.id)) }
    }

    /**
     * [row] as a row [SnipMatcher] can rank: the transcript IS the label.
     *
     * `searchTerms` is empty because a dictation declares nothing about itself
     * — it is free text — so the matcher's "declared" group is always empty
     * here and ranking collapses to "does every query token appear in some
     * word of the transcript". `body` and `category` are unused by the matcher
     * (its own kdoc says `category` is displayed, never matched on) so they are
     * empty rather than carrying a copy of data that nothing reads.
     */
    private fun searchViewOf(row: DictationHistoryStore.Item): Snippet =
        Snippet(label = row.text, body = "", searchTerms = emptyList(), category = "")

    companion object {
        /**
         * How many stored dictations the picker loads, searchably.
         *
         * 🔴 **[HistoryRetention.MAX_ITEMS], not a number of its own, and that
         * is the whole decision.** `BubbleService.REVEAL_MAX_ITEMS` was 50
         * because the overlay list was a thumb-sized affordance and 50 was a
         * syscall budget per long-press ([DictationHistoryStore.recent] stats
         * one file per item it returns). A SEARCH FIELD over the newest 50 of
         * up to 1000 retained items returns a confident wrong answer: the
         * operator's device already holds 94, so a silent 50 would hide 44 of
         * them and report "No saved dictations match that" for a dictation that
         * is right there on disk.
         *
         * Derived from the retention policy rather than restated, so the picker
         * cannot search less than the app keeps — there is no value of this
         * constant that hides a retained item. `HistoryPickerActivityTest`
         * asserts the limit the store is actually asked for, so a reintroduced
         * cap goes red.
         *
         * What it costs, stated rather than discovered: up to 1000 `isFile`
         * stats and one index parse per picker open. That is the budget
         * argument `recent`'s own kdoc raises — but it raised it against
         * *"1000 syscalls to render four chips, on every long-press"*, and a
         * picker open is a deliberate Activity launch rather than a thumb
         * gesture. It also runs on [FloatingPickerActivity.loadExecutor], i.e.
         * off the main thread. Nothing here has MEASURED it; the claim is that
         * the budget moved to a rarer, backgrounded event, not that it is
         * cheap.
         *
         * The alternative considered and rejected: a store method that filters
         * BEFORE statting. It would be the right answer if the read were on the
         * gesture path, and `DictationHistoryStore.item(id)` is the precedent
         * for adding one — but it would put the matching rule inside the store,
         * i.e. a second place that decides what a query matches, which is the
         * one thing reusing [SnipMatcher] is for.
         */
        internal const val MAX_ITEMS: Int = HistoryRetention.MAX_ITEMS
    }
}
