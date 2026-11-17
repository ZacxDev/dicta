package dev.zacx.dicta

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import android.view.View
import android.widget.TextView
import android.widget.Toast

/**
 * The shared-clipboard picker: search what every device has copied, tap a row,
 * the body lands on this phone's clipboard.
 *
 * ## 🔴 A third SUBCLASS, not a mode on an existing picker
 *
 * [FloatingPickerActivity]'s contract is one `titleRes`, one `searchHintRes`
 * and **three distinct** empty-state resources per class, asserted by
 * `PickerShellSeamLedgerTest.everyPickerDeclaresThreeDistinctEmptyStateResources`.
 * A "mode" on `SnipPickerActivity` would have to point several states at one
 * string, which is the silent-empty failure moved into the UI, and it would
 * break that guard. A subclass inherits the shell's five rules —
 * `FloatingWindow.applySize`, the re-filter `TextWatcher`, `setSelection(0)`,
 * the three empty states, and `finish()` after a pick — instead of
 * remembering them.
 *
 * ## 🔴 Search is the CLIENT's own LOCAL matcher, and it is NOT the server's `?q=`
 *
 * Rows are filtered through [SnipMatcher] over clips already fetched. Paste
 * carries the body (settled decision 6), so the picker already holds every clip
 * it displays and local matching is what actually runs when the operator types.
 *
 * The server's `?q=` is a **different matcher with different rules**: FTS5
 * `tokenize='trigram'`, so it matches substrings *across word boundaries*
 * (`com pass` finds `github.com password`), it indexes punctuation, and its
 * case-folding depends on query length — a query whose shortest token is under
 * three runes is served by a `LIKE` scan that folds ASCII only. [SnipMatcher]
 * is per-word (`[a-z0-9]+` over the lowercased string, so hyphens are not word
 * characters) and AND-s its tokens.
 *
 * ⚠ **Whether the two agree on any given query is UNVERIFIED.** It was not
 * measured, and the two known divergences above mean it cannot be assumed. No
 * test here asserts that they agree, deliberately — that would be a claim
 * stronger than the evidence. If this picker ever starts delegating search to
 * the endpoint rather than filtering locally, that is the moment to measure
 * them against each other, not before.
 *
 * ## 🔴 NO reordering. Browse is the server's order, verbatim
 *
 * `SnipPickerActivity.filterRows` applies `SnipUsageStore.order(...)` on browse
 * for most-recently-used ordering. **This does the opposite, three lines from
 * the same override**, and the requirement is why: chronological newest-first
 * is what the operator asked for (settled decision 1), and the store is what
 * decides "newest" — `Clip.createdAtMs` is server-assigned, and dedup bumps it.
 * A client-side reorder would be a second definition of newest that could
 * disagree with the one the desktop shows.
 *
 * ## 🔴 It can never start a recording
 *
 * There is no `DictationSession` here, no `MicCapture`, no permission request
 * and no `BubbleDictation` event raised, and no activity is started at all. The
 * only side effects are a clipboard write and a toast. `ClipSeamLedgerTest`
 * asserts that as a property of the source rather than leaving it to review.
 */
class ClipPickerActivity : FloatingPickerActivity<Clip>() {

    /**
     * Where the rows come from. `null` means a real [ClipHttp] over
     * [ClipSettings].
     *
     * The seam a test uses so that "type a query, then assert" is not also a
     * test of a socket — `SnipPickerActivity.source`'s reason exactly.
     */
    internal var remote: SharedClipSource? = null

    /*
     * 🔴 DELETED: `internal var outbox: ClipOutbox? = null`.
     *
     * It was a seam **no test ever set** — only `CaptureActivity.outbox` is
     * assigned anywhere — whose kdoc claimed a test purpose it did not serve,
     * and it was a `?:` fallback for something the very next line already
     * supplies: [defaultFlush] builds a `ClipOutboxStore(this)` either way, and
     * a test that wants to control the flush sets [flush], which is the seam
     * that exists for that and is used. Deletion candidate D3 of this branch's
     * audit round 0, accepted.
     *
     * `ClipSeamLedgerTest.onlyTheDeclaredFilesTouchTheCaptureQueue` is
     * UNDISTURBED by this, and that was checked rather than assumed: the ledger
     * counts files containing `ClipOutboxStore(`, and [defaultFlush] below
     * still contains one, so this file stays on the ledger exactly as before.
     */

    /**
     * The flush the picker performs before fetching. `null` means a real
     * [ClipFlusher] over [outbox] and [remote].
     *
     * Separate from [outbox] and [remote] deliberately: a test that wants to
     * assert the flush HAPPENED should not have to supply a working POST, and a
     * test about filtering should not have to think about the flush at all.
     */
    internal var flush: (() -> Unit)? = null

    /** The clock, injected so a row's age is not at the mercy of wall time. */
    internal var nowMs: () -> Long = System::currentTimeMillis

    override val titleRes: Int = R.string.clip_title
    override val searchHintRes: Int = R.string.clip_search_hint
    override val emptyUnreadableRes: Int = R.string.clip_empty_unreachable
    override val emptyNothingStoredRes: Int = R.string.clip_empty_none
    override val emptyNoMatchesRes: Int = R.string.clip_empty_no_matches
    override val rowLayoutRes: Int = R.layout.clip_row

    /**
     * Flush, then fetch — **in that order, on one worker hop**.
     *
     * 🔴 The order is the point, and it is also the "poll on open" requirement:
     * a user who captured a clip while off-mesh and then opens the picker
     * expects to see it. Fetching first would render a list that is missing the
     * capture they just made, with nothing on screen saying why.
     *
     * One hop because the shell already calls this on
     * [FloatingPickerActivity.loadExecutor] — this is the flush trigger the
     * design names as "picker open", and it needs no scheduler of its own.
     *
     * 🔴 `null` means the store could not be READ and is not "empty": it is the
     * difference between `@string/clip_empty_unreachable` and
     * `@string/clip_empty_none`, which are different problems with different
     * fixes. [SharedClipSource.rows] never returns an empty list for a failure,
     * and that asymmetry is the whole reason the shell has three empty states.
     *
     * 🔴 The flush is wrapped and the fetch is not. A flush that throws must not
     * cost the operator the list — their clips are on the server either way —
     * whereas a fetch that throws is `null` through the shell's own
     * `runCatching`, which is the unreadable state and is correct.
     */
    override fun readRows(): List<Clip>? {
        // 🔴 ACKNOWLEDGED FIRST, before the flush. Opening the shared
        // clipboard is the one moment the operator has demonstrably seen the
        // bubble's DESTROYED indicator and acted on it, so this is where the
        // latch clears — and clearing it BEFORE the drain means a flush in
        // this same open that destroys another capture re-raises it, rather
        // than being wiped by the acknowledgement that preceded it.
        ClipOutboxHealth.acknowledgeDestroyed()
        runCatching { (flush ?: ::defaultFlush).invoke() }
            .onFailure {
                // The drain threw before producing a Report. Same reasoning as
                // `BubbleService.recordClipFlushThrew`: an absence is the
                // observable the most causes share, so a drain that threw is
                // recorded as a drain that failed rather than as silence.
                ClipOutboxHealth.record(
                    ClipFlusher.Report(failure = it.message ?: it.javaClass.simpleName)
                )
            }
        // 🔴 `fetch()`, not `rows()`. `rows()` is the lossy view — a 401, a
        // body that did not parse and a dead socket were one `null`, and this
        // screen rendered all three as "usually being off-mesh". The outcome is
        // recorded BEFORE it is reduced to a nullable list, so
        // [emptyUnreadableText] and [ClipDiagnostics] read the same
        // classification rather than two guesses at it.
        val outcome = (remote ?: defaultRemote()).fetch()
        ClipDiagnostics.recordFetch(outcome)
        return (outcome as? ClipFetch.Rows)?.rows
    }

    /**
     * The unreadable-state sentence, **specific to why the read failed**.
     *
     * ## 🔴 The defect this closes, which misled a human and an agent alike
     *
     * This screen said one thing for four different failures:
     * *"Dicta could not reach the shared clipboard. It is mesh-only, so this is
     * usually being off-mesh"*. A **401 read as off-mesh** — the store
     * reachable, answering, and refusing the token — so the only surface that
     * reports on this feature stated the opposite of what was wrong. The
     * collapse happened in [ClipHttp] and is fixed there ([ClipFetch]); this is
     * where the distinction becomes words.
     *
     * 🔴 Read off [ClipDiagnostics.lastFetch] rather than from a field of this
     * activity, so the sentence on screen and the machine-readable readout
     * cannot disagree about one fetch. [readRows] records before returning, and
     * `render()` runs after it on the main thread.
     *
     * The off-mesh wording is now reached **only** by
     * [ClipFetch.Transport] — the one outcome it is true of — and by
     * [ClipFetch.Unclassified], where nothing better is known.
     */
    override fun emptyUnreadableText(): String =
        when (val outcome = ClipDiagnostics.lastFetch()) {
            is ClipFetch.Status ->
                if (outcome.isAuthFault) {
                    getString(R.string.clip_empty_unauthorized, outcome.status)
                } else {
                    getString(R.string.clip_empty_http, outcome.status)
                }
            is ClipFetch.Malformed -> getString(R.string.clip_empty_malformed)
            else -> getString(emptyUnreadableRes)
        }

    /**
     * [query] filtered, with **no reordering whatsoever**.
     *
     * Ranked row by row for `HistoryPickerActivity`'s measured reason:
     * [SnipMatcher.rank] returns `Snippet`s, and a clip body is not unique, so
     * the returned rows could not be mapped back by value. It is exactly
     * equivalent here because every synthetic row's `searchTerms` are empty —
     * `rank`'s "declared" group is then always empty, so its output is its
     * input filtered with the supplied order preserved.
     *
     * 🔴 And see the class kdoc: unlike `SnipPickerActivity`, browse is NOT
     * reordered. There is no `SnipMatcher.isBrowse` branch here at all, because
     * there is nothing for it to gate — the blank-query case is `rank`'s own
     * BROWSE branch returning the row unchanged.
     */
    override fun filterRows(rows: List<Clip>, query: String): List<Clip> =
        rows.filter { SnipMatcher.rank(listOf(searchViewOf(it)), query).isNotEmpty() }

    /**
     * 🔴 Every per-item field is set on every call, including the
     * `contentDescription`. A recycled `convertView` arrives wearing the
     * previous row's values, and the bug that shape produces — a row announcing
     * a clip it no longer shows, or copying one the user did not pick — is the
     * hazard [FloatingPickerActivity]'s kdoc names.
     *
     * 🔴 [HistoryRow] and [HistoryLabel] are REUSED rather than copied.
     * `HistoryRow.title(age, engine)` is `age + " · " + engine` and
     * `HistoryLabel.age` is pure and clock-injected, so `"3h · pixel-7"` is
     * structurally identical to `"3h · Server"`. `HistoryRow.kt:51-57` argues
     * exactly this against inventing a second age vocabulary for one fact, and
     * the argument transfers without change.
     */
    override fun bindRow(view: View, row: Clip) {
        val title = HistoryRow.title(
            age = HistoryLabel.age(row.createdAtMs, nowMs()),
            engine = row.device.ifBlank { getString(R.string.clip_device_unknown) },
        )
        val preview = HistoryRow.preview(row.body, getString(R.string.clip_no_body))
        view.findViewById<TextView>(R.id.clip_row_title).text = title
        view.findViewById<TextView>(R.id.clip_row_preview).text = preview
        // 🔴 Says what tapping DOES, not only what the row IS — the argument
        // HistoryPickerActivity.bindRow records: a screen-reader user meeting a
        // row of free text has no way to know it is a button.
        view.contentDescription = getString(R.string.clip_row_desc, preview)
    }

    /**
     * Puts [row]'s body on this phone's clipboard and says so.
     *
     * 🔴 **The body is carried, never re-fetched.** Settled decision 6, and the
     * reason is a race: a second lookup between the tap and the paste could
     * return a different row, or nothing, and the user would paste something
     * they did not choose. [Clip.body] is already in hand.
     *
     * 🔴 **No `{{clip}}` resolution.** [SnipClip] exists because a *snippet*
     * body is a template authored by the operator; a clipboard capture is
     * literal text that was already on a clipboard, and expanding a token
     * inside it would corrupt the one thing this feature promises to move
     * byte-exactly. The absence is deliberate, not an omission.
     *
     * 🔴 No `finish()` here: the shell finishes after every pick, for every
     * picker, from one place.
     */
    override fun onRowPicked(row: Clip) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        runCatching {
            clipboard?.setPrimaryClip(sensitive(ClipData.newPlainText(null, row.body)))
        }
        runCatching {
            Toast.makeText(this, R.string.clip_copied, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Marks [clip] as sensitive, so the platform does not display it.
     *
     * 🔴 `SnipPickerActivity.sensitive`'s argument, and **stronger here**. From
     * Android 13 a copy raises a preview toast that renders the copied TEXT over
     * whatever app the user is in, and keeps the text in clipboard history. A
     * snippet body is operator-authored text; a shared-clipboard row is whatever
     * was highlighted on any of the operator's devices — which is exactly the
     * material §6 of the design is about. Without this, picking a row would put
     * it on screen in order to confirm that it had been copied.
     *
     * 🔴 This is a clipboard-WRITE suppression on an outbound paste. It is **not**
     * the receiver-side sensitivity check §6 forbids, and it must not be read as
     * one: it says nothing about whether the captured text was sensitive, only
     * that Dicta does not put it on screen on the way out.
     *
     * `minSdk` is 26 and the extra is API 33, so the write is guarded on the
     * running version. Below 33 the platform has neither the preview nor the
     * history, so there is nothing to suppress and nothing to fall back to.
     *
     * The label is `null` rather than a summary of the body: a clipboard-history
     * UI renders the label, so a label derived from the text would reintroduce
     * the display this suppresses.
     */
    private fun sensitive(clip: ClipData): ClipData {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        return clip
    }

    /**
     * [row] as a row [SnipMatcher] can rank: the body IS the label.
     *
     * `searchTerms` empty, so the matcher's "declared" group is always empty
     * here and ranking collapses to "does every query token appear in some word
     * of the body" — which is requirement 1's full-text search.
     * `HistoryPickerActivity.searchViewOf` is the precedent, applied to a
     * second foreign row type.
     */
    private fun searchViewOf(row: Clip): Snippet =
        Snippet(label = row.body, body = "", searchTerms = emptyList(), category = "")

    private fun defaultRemote(): SharedClipSource {
        val settings = ClipSettings.create(this)
        return ClipHttp(settings.baseUrl, settings.tokens.token().orEmpty())
    }

    /**
     * The production flush: drain the outbox, then let [readRows] fetch.
     *
     * Built here rather than held as a field so that a picker opened with no
     * token configured costs no keystore unwrap at all — `isConfigured` is
     * checked first, and `TokenStore.hasToken` is the only thing that decrypts.
     */
    private fun defaultFlush() {
        val settings = ClipSettings.create(this)
        if (!settings.isConfigured) {
            // 🔴 The same blind spot as [BubbleService.defaultClipFlush], at
            // the second of the two drain sites. A token that is STORED but
            // will not decrypt reads as "unconfigured" here, and this `return`
            // used to produce no Report at all — on a handset whose queue is
            // full of days of captures. See [ClipSettings.tokenIsUnusable].
            if (settings.tokenIsUnusable) {
                ClipOutboxHealth.record(
                    ClipFlusher.Report(failure = ClipSettings.UNUSABLE_TOKEN_REASON)
                )
            }
            return
        }
        val token = settings.tokens.token() ?: return
        val http = ClipHttp(settings.baseUrl, token)
        val report = ClipFlusher(
            outbox = ClipOutboxStore(this),
            device = ClipDevice.of(),
            post = { body, device -> http.post(body, device) },
        ).flush()
        // 🔴 CONSUMED, not discarded — round 0's finding, at the second of the
        // two sites that had it. The picker has nowhere of its own to show a
        // stuck queue (its three empty states are about the SERVER's list, not
        // about the local one), so what it does is feed the verdict the BUBBLE
        // renders. A flush-on-open that fails is also the most informative
        // sample there is: the operator is looking at the feature right now.
        // See [ClipOutboxHealth] and
        // `ClipSeamLedgerTest.everySiteThatDrainsTheOutboxCONSUMESItsReport`.
        ClipOutboxHealth.record(report)
    }
}
