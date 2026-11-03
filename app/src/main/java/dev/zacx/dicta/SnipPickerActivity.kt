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
 * The snippet picker: search the imported catalog, tap a row, the body lands on
 * the clipboard.
 *
 * ## 🔴 What moved OUT of this file, and why
 *
 * The window sizing, the search field, the filter-on-type loop, the three-way
 * empty state and the `finish()` are now [FloatingPickerActivity]'s. They are
 * the rules a picker must obey, and this file's own history is the argument:
 * `FloatingWindow.applySize` was the one obligation an activity had to
 * *remember*, and in 0.4.0 this activity did not — it rendered at roughly a
 * fifth of the screen width on a real Galaxy S21. A second picker is four more
 * such obligations. The shell owns them; this class says only WHAT it lists.
 *
 * 🔴 **The behaviour here did not change.** Every test in
 * `SnipPickerActivityTest` passes unmodified except for the three view ids,
 * which genuinely moved into the shared layout — and that is the assertion the
 * refactor rests on.
 *
 * ## 🔴 An Activity, and NOT a view inside the bubble's overlay window
 *
 * The general reasons are on [FloatingPickerActivity]. One is specific to this
 * picker and is on its own sufficient: **the clipboard.** From Android 10 the
 * clipboard can only be READ by the app holding input focus (or the default
 * IME), so a `Service` cannot resolve [SnipClip.CLIP_VAR] at all. See
 * [SnipClip].
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
 */
class SnipPickerActivity : FloatingPickerActivity<Snippet>() {

    /**
     * Where the catalog comes from. `null` means [SnipStore].
     *
     * The seam the tailnet-fetch follow-up drops into, and the seam a test uses
     * so that "type a query, then assert" is not also a test of the filesystem.
     */
    internal var source: SnipSource? = null

    override val titleRes: Int = R.string.snip_title
    override val searchHintRes: Int = R.string.snip_search_hint
    override val emptyUnreadableRes: Int = R.string.snip_empty_no_catalog
    override val emptyNothingStoredRes: Int = R.string.snip_empty_catalog_empty
    override val emptyNoMatchesRes: Int = R.string.snip_empty_no_matches
    override val rowLayoutRes: Int = R.layout.snip_row

    /**
     * The catalog's rows, or `null` when nothing has been imported.
     *
     * 🔴 `null` is load-bearing and is not "empty": it is the difference
     * between `@string/snip_empty_no_catalog` ("import one") and
     * `@string/snip_empty_catalog_empty` ("the one you imported has none"),
     * which are different problems with different fixes. An unreadable catalog
     * reads as "nothing imported" — [SnipStore]'s failure posture — and the
     * shell turns a throw into the same `null`, so this needs no wrapping of
     * its own.
     */
    override fun readRows(): List<Snippet>? =
        (source ?: SnipStore(this)).catalog()?.snippets

    /**
     * The ranking is [SnipMatcher.rank] and nothing else — including for a
     * blank query, which is its BROWSE case and is how the picker opens.
     */
    override fun filterRows(rows: List<Snippet>, query: String): List<Snippet> =
        SnipMatcher.rank(rows, query)

    /**
     * 🔴 Every per-item field is set on every call, including the
     * `contentDescription`. A recycled `convertView` arrives wearing the
     * previous row's values, and the bug that shape produces — a row announcing
     * a snippet it no longer shows — is the hazard
     * [FloatingPickerActivity]'s kdoc names.
     */
    override fun bindRow(view: View, row: Snippet) {
        view.findViewById<TextView>(R.id.snip_row_title).text = SnipRow.title(row)
        view.findViewById<TextView>(R.id.snip_row_preview).text = SnipRow.preview(row.body)
        view.contentDescription = getString(R.string.snip_row_desc, SnipRow.title(row))
    }

    /**
     * Puts [row]'s **resolved** body on the clipboard and says so.
     *
     * 🔴 Resolved, not verbatim — see [SnipClip] for why a verbatim copy puts
     * the literal characters `{{clip}}` into the user's next message. The
     * clipboard is read only when the body actually carries the variable, so
     * the common row costs no clipboard access at all.
     *
     * The label is the clip's own label, which is what a clipboard-history UI
     * shows; the body is the text.
     *
     * 🔴 No `finish()` here any more, and that is not an omission: the shell
     * finishes after every pick, for both pickers, from one place. The reason
     * is unchanged — leaving the picker up would put a search field over the
     * app the user is trying to paste into.
     */
    override fun onRowPicked(row: Snippet) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val body = if (SnipClip.needsClip(row.body)) {
            SnipClip.resolve(row.body, currentClipText(clipboard))
        } else {
            row.body
        }
        runCatching {
            clipboard?.setPrimaryClip(sensitive(ClipData.newPlainText(row.label, body)))
        }
        runCatching {
            Toast.makeText(
                this,
                getString(R.string.snip_copied, row.label),
                Toast.LENGTH_SHORT,
            ).show()
        }
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
}
