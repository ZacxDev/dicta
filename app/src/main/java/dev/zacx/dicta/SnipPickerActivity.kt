package dev.zacx.dicta

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The snippet picker: search the imported catalog, tap a row, the body lands on
 * the clipboard.
 *
 * ## 🔴 An Activity, and NOT a view inside the bubble's overlay window
 *
 * Two independent reasons, either of which is sufficient:
 *
 *  1. **Input focus.** The bubble's window is created with
 *     `FLAG_NOT_FOCUSABLE` (`BubbleService.buildWindow`), and this repo argues
 *     the reason explicitly on `BubbleService.showUnreachableWarning`: a
 *     focusable overlay takes input focus from whatever app is underneath,
 *     which closes its IME and can lose a half-typed message. A search field
 *     needs IME focus, so it cannot live in that window — and making that
 *     window focusable to host it would trade the bubble's whole posture for a
 *     picker.
 *  2. **The clipboard.** From Android 10 the clipboard can only be READ by the
 *     app holding input focus (or the default IME), so a `Service` cannot
 *     resolve [SnipClip.CLIP_VAR] at all. See [SnipClip].
 *
 * Launching it from `BubbleService` is the same background-activity start the
 * existing bubble → `DictateActivity` path already relies on
 * (`SYSTEM_ALERT_WINDOW` exempts it); [SnipLaunch] owns the intent and the
 * `taskAffinity` pairing that makes it land over the right thing.
 *
 * ## 🔴 It can never start a recording
 *
 * There is no `DictationSession` here, no `MicCapture`, no permission request
 * and no `BubbleDictation` event raised. The only side effects are a clipboard
 * write and a toast. `SnipSeamLedgerTest` asserts that as a property of the
 * source rather than leaving it to review.
 *
 * ## No androidx
 *
 * `app/build.gradle.kts` has an empty runtime `dependencies {}` block by
 * explicit decision, so this is a platform `Activity` with a framework
 * `ListView` and a hand-rolled `BaseAdapter` — exactly what the bubble's own
 * reveal uses, and for the reason written there.
 */
class SnipPickerActivity : Activity() {

    private lateinit var search: EditText
    private lateinit var list: ListView
    private lateinit var empty: TextView

    /**
     * Where the catalog comes from. `null` means [SnipStore].
     *
     * The seam the tailnet-fetch follow-up drops into, and the seam a test uses
     * so that "type a query, then assert" is not also a test of the filesystem.
     * Mirrors `BubbleService.historyProvider`'s shape deliberately.
     */
    internal var source: SnipSource? = null

    /**
     * Where [source] is read. `null` means [worker].
     *
     * Separate from [source] for `BubbleService.historyExecutor`'s reason: a
     * test wants a *synchronous* executor so the assertion has no race in it,
     * and that is an orthogonal choice from where the rows come from.
     */
    internal var loadExecutor: Executor? = null

    private val worker = Executors.newSingleThreadExecutor()

    /** Every row the catalog holds, or empty. See [loaded]. */
    private var all: List<Snippet> = emptyList()

    /**
     * Whether a catalog was found at all.
     *
     * 🔴 Distinct from `all.isEmpty()`, and the distinction is the whole of the
     * empty-state copy: "no snippets imported yet" and "nothing matches that"
     * are different problems with different fixes, and one message for both is
     * the silent-empty failure [SnipCatalog] exists to prevent, moved into the
     * UI.
     */
    private var loaded = false

    private val adapter = SnippetAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_snip_picker)
        search = findViewById(R.id.snip_search)
        list = findViewById(R.id.snip_list)
        empty = findViewById(R.id.snip_empty)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> copy(adapter.getItem(position)) }
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
     * 🔴 **Without this the picker serves a STALE catalog.** The manifest
     * declares `launchMode="singleTop"` with the picker's own `taskAffinity`
     * and no `noHistory`, so the activity survives being stopped: open it from
     * the bubble, press HOME rather than Back (so nothing calls [finish]),
     * import a different `snippets.json` from Settings, then tap snip again.
     * The system delivers `onNewIntent` to the existing instance, [onCreate]
     * does not run, and a catalog loaded only there would list rows the user
     * has already replaced — tapping one copies a body that no longer exists,
     * with nothing on screen saying so.
     *
     * `onNewIntent` rather than `onResume`: the resume callback also fires on
     * the way up from [onCreate], so putting the reload there would load the
     * catalog twice on every fresh open — two file reads and two JSON parses
     * for one launch. `onNewIntent` fires only for re-entry, which is exactly
     * the case that is stale.
     *
     * The query is reset because a filter the user cannot see the origin of
     * reads as a short catalog: a re-opened picker showing one row out of
     * forty, because of something typed before HOME was pressed, is the
     * silent-empty failure again in a new shape.
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
     * Reads the catalog off the main thread and renders it.
     *
     * Off the main thread because the read is file IO plus a JSON parse of the
     * whole catalog — the same reason the bubble's reveal and the settings
     * screen's usage line are both async. An unreadable catalog is "nothing
     * imported", never a crash: see [SnipStore]'s failure posture.
     */
    private fun load() {
        val from = source ?: SnipStore(this)
        val executor = loadExecutor ?: worker
        runCatching {
            executor.execute {
                val catalog = runCatching { from.catalog() }.getOrNull()
                runOnUiThread {
                    all = catalog?.snippets ?: emptyList()
                    loaded = catalog != null
                    render()
                }
            }
        }
    }

    /**
     * Applies the query and shows either the rows or the reason there are none.
     *
     * The ranking is [SnipMatcher.rank] and nothing else — including for a
     * blank query, which is its BROWSE case and is how the picker opens.
     */
    private fun render() {
        adapter.submit(SnipMatcher.rank(all, search.text?.toString() ?: ""))
        val hasRows = adapter.count > 0
        list.visibility = if (hasRows) View.VISIBLE else View.GONE
        empty.visibility = if (hasRows) View.GONE else View.VISIBLE
        empty.text = getString(
            when {
                !loaded -> R.string.snip_empty_no_catalog
                all.isEmpty() -> R.string.snip_empty_catalog_empty
                else -> R.string.snip_empty_no_matches
            }
        )
    }

    /**
     * Puts [snippet]'s **resolved** body on the clipboard and says so.
     *
     * 🔴 Resolved, not verbatim — see [SnipClip] for why a verbatim copy puts
     * the literal characters `{{clip}}` into the user's next message. The
     * clipboard is read only when the body actually carries the variable, so
     * the common row costs no clipboard access at all.
     *
     * The label is the clip's own label, which is what a clipboard-history UI
     * shows; the body is the text. `finish()` because the picker's whole job is
     * done — leaving it up would put a search field over the app the user is
     * trying to paste into.
     */
    private fun copy(snippet: Snippet) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val body = if (SnipClip.needsClip(snippet.body)) {
            SnipClip.resolve(snippet.body, currentClipText(clipboard))
        } else {
            snippet.body
        }
        runCatching {
            clipboard?.setPrimaryClip(sensitive(ClipData.newPlainText(snippet.label, body)))
        }
        runCatching {
            Toast.makeText(
                this,
                getString(R.string.snip_copied, snippet.label),
                Toast.LENGTH_SHORT,
            ).show()
        }
        finish()
    }

    /**
     * Marks [clip] as sensitive, so the platform does not display it.
     *
     * 🔴 The same argument [SnipStore] makes for `noBackupFilesDir`: a snippet
     * body is arbitrary operator text — paths, hostnames, prompt templates —
     * and [Snippet] names `ssh` and `kubeconfig` among its categories. From
     * Android 13 a copy raises a preview toast that renders the copied TEXT
     * over whatever app the user is in, and the text is kept in clipboard
     * history; `EXTRA_IS_SENSITIVE` is what suppresses both. Without it, this
     * feature would put a kubeconfig on screen to confirm that it had been
     * copied — which is precisely what `@string/snip_copied` is worded to
     * avoid for the toast Dicta raises itself.
     *
     * `minSdk` is 26 and the extra is API 33, so the write is guarded on the
     * running version rather than on the compile one. Below 33 the platform has
     * neither the preview nor the history, so there is nothing to suppress and
     * nothing to fall back to.
     *
     * Scoped to the snip write deliberately: `DictateActivity`'s transcript
     * copy has the same gap and is pre-existing, and widening this change to it
     * would put a second, independently-arguable decision in #38's diff.
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
     * The clipboard's current text, or empty.
     *
     * `coerceToText` rather than `text`: a clip may be a URI or an intent, and
     * the user's expectation for `{{clip}}` is "whatever I last copied, as
     * text" — which is exactly what coercion gives. Wrapped because a read can
     * throw when focus was lost between the tap and here.
     */
    private fun currentClipText(clipboard: ClipboardManager?): String =
        runCatching {
            val clip = clipboard?.primaryClip ?: return@runCatching ""
            if (clip.itemCount == 0) "" else clip.getItemAt(0).coerceToText(this).toString()
        }.getOrDefault("")

    /**
     * One row per snippet.
     *
     * 🔴 Every per-item field is set on every `getView`, including the
     * `contentDescription`. A recycled `convertView` arrives wearing the
     * previous row's values, and the bug that shape produces — a row announcing
     * a snippet it no longer shows — is the same one `BubbleService.bindChip`
     * documents at length.
     */
    private inner class SnippetAdapter : BaseAdapter() {
        private var rows: List<Snippet> = emptyList()

        fun submit(next: List<Snippet>) {
            rows = next
            notifyDataSetChanged()
        }

        override fun getCount(): Int = rows.size

        override fun getItem(position: Int): Snippet = rows[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup?): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.snip_row, parent, false)
            val snippet = rows[position]
            val title = view.findViewById<TextView>(R.id.snip_row_title)
            val preview = view.findViewById<TextView>(R.id.snip_row_preview)
            title.text = SnipRow.title(snippet)
            preview.text = SnipRow.preview(snippet.body)
            view.contentDescription =
                getString(R.string.snip_row_desc, SnipRow.title(snippet))
            return view
        }
    }
}
