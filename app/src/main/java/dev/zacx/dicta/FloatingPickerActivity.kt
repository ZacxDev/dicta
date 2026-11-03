package dev.zacx.dicta

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * 🔴 The ONE owner of a floating **picker**: its window geometry, its search
 * field, its filter-on-type loop, its three-way empty state and its
 * finish-on-selection.
 *
 * ## Why this exists rather than two activities that each do it
 *
 * `SnipPickerActivity` shipped in 0.4.0 without calling what `DictateActivity`
 * already did, and rendered at roughly a **fifth** of the screen width on a
 * real Galaxy S21 (issue #38; see [FloatingWindow]). [FloatingWindow] closed
 * the geometry half of that by making the sizing one object — but a *second*
 * picker then has four more rules to get right, each invisible if omitted: the
 * `TextWatcher` that re-filters, the `setSelection(0)` that opens on the newest
 * row, the three distinct empty-state strings, and the `finish()` that stops a
 * search field being left over the app the user is acting on. A silently empty
 * list is the failure mode that reads as "the feature is broken", and it is
 * reachable by forgetting any one of them.
 *
 * So the subclass declares WHAT it lists and nothing about HOW a picker
 * behaves. `PickerShellSeamLedgerTest` derives the membership of this shell
 * **structurally** — from the declared superclass, not from a name prefix —
 * and fails when the set grows or shrinks.
 *
 * ## 🔴 What is deliberately NOT here: the row
 *
 * The adapter's *list plumbing* (count, item, id, `notifyDataSetChanged`,
 * `convertView` reuse) is here, because it is identical for any list and a
 * second copy is the N-sites duplication this class exists to remove. The
 * **row view and the row's content are not**: a snippet row
 * (`label · category` + body preview) and a history row (age + engine +
 * transcript preview) genuinely differ, and one row type for both would be
 * premature coupling. Each subclass names its own [rowLayoutRes] and binds it
 * in [bindRow], and each has its own recycling test.
 *
 * ## 🔴 A row bind must set EVERY per-item field, including to null
 *
 * [bindRow] is handed a `convertView` that arrives wearing the PREVIOUS row's
 * text, `contentDescription` and `tooltipText`. The bug that shape produces — a
 * row announcing an item it no longer shows, or opening one the user did not
 * pick — is the one `BubbleService.bindChip` documented at length before the
 * chip reveal was deleted, and it is the subclass's contract because the
 * subclass owns the fields.
 *
 * ## Why an Activity at all, and not a view in the bubble's window
 *
 * Two independent reasons, either sufficient, and both are why BOTH pickers are
 * activities:
 *
 *  1. **Input focus.** The bubble's window carries `FLAG_NOT_FOCUSABLE`
 *     (`BubbleService.buildWindow`), because a focusable overlay takes input
 *     focus from whatever app is underneath, closing its IME and possibly
 *     losing a half-typed message. A search field needs IME focus, so it cannot
 *     live in that window, and making that window focusable would trade the
 *     bubble's whole posture for a picker.
 *  2. **An Activity has a lifecycle the overlay did not.** The chip reveal
 *     needed a `revealGeneration` counter because a late async load could show
 *     a row nobody had asked for any more — and that row was then PERMANENT,
 *     with `FLAG_WATCH_OUTSIDE_TOUCH` armed device-wide (found by audit round 1
 *     of #17). An Activity that is finishing simply stops rendering, so that
 *     whole defect class goes away by construction.
 *
 * ## No androidx
 *
 * `app/build.gradle.kts` has an empty runtime `dependencies {}` block by
 * explicit decision, so this is a platform `Activity` with a framework
 * `ListView` and a hand-rolled `BaseAdapter`. A `RecyclerView` would drag in
 * annotation/lifecycle/collection/customview; a `ScrollView` would scroll
 * without recycling, so a thousand rows would be a thousand live views.
 */
abstract class FloatingPickerActivity<T> : Activity() {

    private lateinit var search: EditText
    private lateinit var list: ListView
    private lateinit var empty: TextView

    /**
     * Where [readRows] runs. `null` means [worker].
     *
     * Separate from each subclass's own source seam on purpose: a test wants a
     * *synchronous* executor so that "open, then assert" has no race in it, and
     * that is an orthogonal choice from where the rows come from. The seam
     * removes the thread, not the call.
     */
    internal var loadExecutor: Executor? = null

    private val worker = Executors.newSingleThreadExecutor()

    /** Every row the source holds, or empty. See [loaded]. */
    private var all: List<T> = emptyList()

    /**
     * Whether the source could be READ at all.
     *
     * 🔴 Distinct from `all.isEmpty()`, and the distinction is the whole of the
     * empty-state copy: "nothing stored yet" and "nothing matches that" are
     * different problems with different fixes, and "the store could not be
     * read" is a third. One message for several of them is the silent-empty
     * failure moved into the UI.
     */
    private var loaded = false

    private val adapter = RowAdapter()

    // --- what a subclass must say -------------------------------------------

    /** The heading. */
    protected abstract val titleRes: Int

    /** The search field's hint. */
    protected abstract val searchHintRes: Int

    /** Shown when the source could not be read at all. See [loaded]. */
    protected abstract val emptyUnreadableRes: Int

    /** Shown when the source was read and holds nothing. */
    protected abstract val emptyNothingStoredRes: Int

    /** Shown when the source holds rows and the QUERY matches none. */
    protected abstract val emptyNoMatchesRes: Int

    /** The layout one row inflates from. */
    protected abstract val rowLayoutRes: Int

    /**
     * Every row, in the order the picker should BROWSE them in.
     *
     * `null` means "could not be read", which is [emptyUnreadableRes] and is
     * NOT the same as an empty list. A throw is treated as `null` by [load], so
     * an unreadable store degrades to an explained empty state and never to a
     * crash — there is no need to wrap anything here.
     *
     * Called on [loadExecutor], i.e. off the main thread: both sources are file
     * IO plus a parse or a directory walk.
     */
    protected abstract fun readRows(): List<T>?

    /**
     * [rows] reduced and ordered by [query].
     *
     * 🔴 A blank query must be **BROWSE** — every row, in the order [readRows]
     * supplied — and not a degenerate case of filtering. It is how the picker
     * opens, and a filter that returned nothing for it would look like an empty
     * store. [SnipMatcher.rank] names that branch; both subclasses go through
     * it.
     */
    protected abstract fun filterRows(rows: List<T>, query: String): List<T>

    /** Puts [row] onto [view], which **may be recycled** — see the class kdoc. */
    protected abstract fun bindRow(view: View, row: T)

    /**
     * What tapping [row] does. The shell calls [finish] immediately afterwards
     * — see [onRowClicked].
     */
    protected abstract fun onRowPicked(row: T)

    // --- the shell ----------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_picker)
        // 🔴 AFTER setContentView, never before — and that constraint, the
        // defect it closes and why there is one owner are all in
        // FloatingWindow's kdoc. Deliberately not restated here: the mechanism
        // had been copied into six tracked files, and the copy nobody reads is
        // the copy that goes stale.
        //
        // 🔴 Here rather than in each subclass, which is the point of the
        // shell: a third picker inherits the call instead of having to
        // remember it. FloatingOverlaySeamLedgerTest resolves the obligation
        // through the declared superclass for exactly this reason.
        FloatingWindow.applySize(this)
        findViewById<TextView>(R.id.picker_title).setText(titleRes)
        search = findViewById(R.id.picker_search)
        list = findViewById(R.id.picker_list)
        empty = findViewById(R.id.picker_empty)
        search.setHint(searchHintRes)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> onRowClicked(position) }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = render()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
        render()
        load()
    }

    /**
     * Re-entry into an instance that was never destroyed: reload, and clear the
     * query.
     *
     * 🔴 Without this a picker serves STALE rows. Both are declared
     * `launchMode="singleTop"` with their own `taskAffinity` and no
     * `noHistory`, so the activity survives being stopped: open it, press HOME
     * rather than Back (so nothing calls [finish]), change what is stored, then
     * open it again. The system delivers `onNewIntent` to the existing
     * instance, [onCreate] does not run, and rows loaded only there would be
     * the ones the user has already replaced — tapping one acts on something
     * that no longer exists, with nothing on screen saying so.
     *
     * `onNewIntent` rather than `onResume`: the resume callback also fires on
     * the way up from [onCreate], so putting the reload there would read the
     * source twice on every fresh open. `onNewIntent` fires only for re-entry,
     * which is exactly the case that is stale.
     *
     * The query is reset because a filter the user cannot see the origin of
     * reads as a short list: a re-opened picker showing one row out of forty,
     * because of something typed before HOME was pressed, is the silent-empty
     * failure again in a new shape.
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        search.setText("")
        load()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    /**
     * Reads the source off the main thread and renders it.
     *
     * A throw is `null`, i.e. "could not be read" — never a crash. See
     * [readRows].
     */
    private fun load() {
        val executor = loadExecutor ?: worker
        runCatching {
            executor.execute {
                val rows = runCatching { readRows() }.getOrNull()
                runOnUiThread {
                    all = rows ?: emptyList()
                    loaded = rows != null
                    render()
                }
            }
        }
    }

    /**
     * Applies the query and shows either the rows or the reason there are none.
     *
     * 🔴 `setSelection(0)` on every render, so the list always shows the top of
     * what it currently holds. `BubbleService.renderReveal` carried the same
     * rule — *"a reveal always opens on the newest item, never wherever the
     * last one was left"* — and it transfers: after typing a query, the first
     * match must be the row the user sees, not whatever offset the previous
     * result set was scrolled to.
     */
    private fun render() {
        adapter.submit(filterRows(all, search.text?.toString() ?: ""))
        val hasRows = adapter.count > 0
        list.visibility = if (hasRows) View.VISIBLE else View.GONE
        empty.visibility = if (hasRows) View.GONE else View.VISIBLE
        if (hasRows) list.setSelection(0)
        empty.text = getString(
            when {
                !loaded -> emptyUnreadableRes
                all.isEmpty() -> emptyNothingStoredRes
                else -> emptyNoMatchesRes
            }
        )
    }

    /**
     * Hands the tapped row to the subclass, then finishes.
     *
     * 🔴 [finish] is the shell's, called unconditionally and from one place.
     * The picker's whole job is done once a row is picked, and leaving it up
     * would put a search field over the app the user is trying to act on. For
     * history it is also what makes Back return the user to where they were
     * rather than to a stale picker behind the card that just opened.
     */
    private fun onRowClicked(position: Int) {
        val row = adapter.getItem(position)
        onRowPicked(row)
        finish()
    }

    /**
     * One row per item, with the subclass doing the binding.
     *
     * Pure list plumbing — see the class kdoc for why this half is shared and
     * [bindRow] is not.
     */
    private inner class RowAdapter : BaseAdapter() {
        private var rows: List<T> = emptyList()

        fun submit(next: List<T>) {
            rows = next
            notifyDataSetChanged()
        }

        override fun getCount(): Int = rows.size

        override fun getItem(position: Int): T = rows[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: layoutInflater.inflate(rowLayoutRes, parent, false)
            bindRow(view, rows[position])
            return view
        }
    }
}
