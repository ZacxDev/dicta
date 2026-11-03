package dev.zacx.dicta

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Where the operator pastes the bearer token and sets the service URL.
 *
 * ## What is and is not shown
 *
 * 🔴 The token is **never displayed back**. The field starts empty with a hint
 * saying whether one is stored; leaving it empty keeps the existing token, and
 * "Clear token" removes it. Rendering the stored token into an `EditText`
 * would put it on screen, in the view hierarchy, in an accessibility dump and
 * in any screenshot of this activity — and the whole point of
 * [KeystoreCipher] is that it is not casually readable.
 *
 * 🔴 It is also never logged, never put into a `Toast`, and never included in
 * an exception message. [TranscriptionCoordinator] builds its user-facing
 * reasons from the status code and the service's own `detail` field only.
 */
class SettingsActivity : Activity() {

    private lateinit var settings: SttSettings
    private lateinit var urlField: EditText
    private lateinit var tokenField: EditText
    private lateinit var statusLine: TextView
    private lateinit var remoteToggle: CheckBox
    private lateinit var languageSpinner: Spinner
    private lateinit var historyUsage: TextView
    private lateinit var snippetsState: TextView

    /**
     * History, read here only through [DictationHistoryStore.usage] and
     * [DictationHistoryStore.clearAll]. This screen makes no retention
     * decision and exposes no bound — see the History comment in
     * `activity_settings.xml`.
     */
    private lateinit var history: DictationHistoryStore

    /**
     * The snip catalog (issue #38). This screen is the only **writer**; the
     * picker is the only reader.
     *
     * `internal` so a test can point it at a temp directory: the production
     * store roots itself under `noBackupFilesDir`, which is process-wide under
     * Robolectric, so a test that used it would be at the mercy of whatever the
     * previous test left behind. Assigned before `onCreate` when a test wants
     * that; `onCreate` only fills it in if it is still unset.
     */
    internal var snippets: SnipStore? = null

    /**
     * The last usage actually rendered, so the delete confirmation can name the
     * count without doing disk IO on the main thread. Null until the first read
     * lands; see [clearHistory] for what the dialog says then.
     */
    private var lastUsage: DictationHistoryStore.Usage? = null

    /**
     * The delete-all confirmation, while it is up, so [onDestroy] can dismiss
     * it.
     *
     * 🔴 Not optional hygiene. This activity declares no `configChanges` and no
     * `screenOrientation`, so a rotation with the dialog showing destroys the
     * activity under it: the window is leaked (`WindowLeaked` in logcat, and a
     * dialog attached to a dead activity), and the confirmation the user was
     * part-way through is silently dropped rather than re-offered.
     */
    private var confirmDialog: AlertDialog? = null

    /**
     * The spinner's rows, by position. `null` is "follow the device locale" and
     * is always position 0 — see [languageCodes].
     */
    private var languageCodes: List<String?> = emptyList()

    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = SttSettings.create(this)

        urlField = findViewById(R.id.stt_url)
        tokenField = findViewById(R.id.stt_token)
        statusLine = findViewById(R.id.stt_status)
        remoteToggle = findViewById(R.id.stt_remote_enabled)
        languageSpinner = findViewById(R.id.stt_language)
        historyUsage = findViewById(R.id.stt_history_usage)
        snippetsState = findViewById(R.id.stt_snippets_state)
        history = DictationHistoryStore(this)
        if (snippets == null) snippets = SnipStore(this)

        urlField.setText(settings.baseUrl)
        remoteToggle.isChecked = settings.remoteEnabled
        bindLanguageSpinner()
        renderTokenState()

        findViewById<Button>(R.id.stt_save).setOnClickListener { save() }
        findViewById<Button>(R.id.stt_clear_token).setOnClickListener { clearToken() }
        findViewById<Button>(R.id.stt_test).setOnClickListener { testConnection() }
        findViewById<Button>(R.id.stt_clear_history).setOnClickListener { clearHistory() }
        findViewById<Button>(R.id.stt_import_snippets)
            .setOnClickListener { importSnippets() }

        refreshHistoryUsage()
        refreshSnippetsState()
    }

    override fun onDestroy() {
        // Before super, and before the worker: a dialog still attached to an
        // activity that has finished destroying is the leak itself.
        confirmDialog?.let { runCatching { it.dismiss() } }
        confirmDialog = null
        worker.shutdownNow()
        super.onDestroy()
    }

    /**
     * Builds the dictation-language picker (issue #7).
     *
     * Position 0 is always "follow the device", and it *names* the language
     * that currently means — a bare "Follow device language" would leave an
     * operator on an `es-CO` handset no way to discover that this is why their
     * English came back in Spanish.
     *
     * The remaining rows come from [SttLanguage.DISPLAY_NAMES], sorted by the
     * label so the list reads alphabetically rather than by ISO code. The codes
     * are kept in [languageCodes] positionally, so nothing has to parse a label
     * back into a code.
     */
    private fun bindLanguageSpinner() {
        val deviceResolved = SttLanguage.fromLocale(Locale.getDefault())
        val followLabel = getString(
            R.string.stt_language_follow_device,
            SttLanguage.DISPLAY_NAMES[deviceResolved] ?: deviceResolved,
        )

        val named = SttLanguage.DISPLAY_NAMES.entries
            .sortedBy { it.value }

        languageCodes = listOf<String?>(null) + named.map { it.key }
        val labels = listOf(followLabel) + named.map { it.value }

        // 🔴 Issue #10: these two were android.R.layout.simple_spinner_item and
        // android.R.layout.simple_spinner_dropdown_item, i.e. the platform's own
        // rows with the platform's metrics and colours — which is why adding
        // this picker RAISED the screen's unthemed-control count. The
        // replacements are the app's own layouts; each explains, in its own
        // header, the cue it has to carry that the platform row used to supply
        // (the caret, and the selected-row mark). The adapter itself, the
        // labels and the positional languageCodes list below are deliberately
        // unchanged — SettingsLanguageTest/DictationLanguageTest drive them.
        languageSpinner.adapter = ArrayAdapter(
            this,
            R.layout.spinner_item,
            labels,
        ).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }

        // settings.language is null when following the device, which is
        // position 0 — and indexOf returns the same 0 for null, so this is one
        // lookup rather than a special case.
        languageSpinner.setSelection(
            languageCodes.indexOf(settings.language).coerceAtLeast(0)
        )
    }

    private fun renderTokenState() {
        tokenField.setText("")
        tokenField.hint = getString(
            if (settings.tokens.hasToken) {
                R.string.stt_token_hint_stored
            } else {
                R.string.stt_token_hint_empty
            }
        )
    }

    private fun save() {
        settings.baseUrl = urlField.text.toString()
        settings.remoteEnabled = remoteToggle.isChecked
        // 🔴 Without this line the picker renders, moves, and changes nothing —
        // a control that looks like a setting and is not one. SettingsLanguageTest
        // drives the spinner and reads the value back out of SttSettings for
        // exactly that reason.
        settings.language = languageCodes.getOrNull(languageSpinner.selectedItemPosition)

        val typed = tokenField.text.toString()
        if (typed.isNotBlank()) {
            try {
                settings.tokens.putToken(typed)
            } catch (e: Exception) {
                // Fail closed and say so. A "Saved" toast over a failed
                // encryption would leave the user sending no token and seeing
                // an unexplained 401 on every recording.
                statusLine.text = getString(
                    R.string.stt_token_save_failed,
                    e.javaClass.simpleName
                )
                return
            }
        }
        // Clear the field immediately so the pasted token does not linger in
        // the view (and so it is not in a screenshot taken a moment later).
        renderTokenState()
        urlField.setText(settings.baseUrl)
        statusLine.text = getString(R.string.stt_saved)
        Toast.makeText(this, R.string.stt_saved, Toast.LENGTH_SHORT).show()
    }

    private fun clearToken() {
        settings.tokens.clearToken()
        renderTokenState()
        statusLine.text = getString(R.string.stt_token_cleared)
    }

    // --- history ------------------------------------------------------------

    /**
     * Reads [DictationHistoryStore.usage] and renders it.
     *
     * 🔴 On [worker], not here. `usage()` reads `index.json` off disk and, when
     * an audio file has gone missing, rewrites it — file IO either way, and the
     * history directory is as large as the retention bounds allow. The read is
     * the reason the usage line's layout text is
     * `@string/stt_history_usage_pending` rather than a zero: a hard-coded
     * "0 dictations" would be a wrong number shown as a real one.
     */
    private fun refreshHistoryUsage() {
        worker.execute {
            val usage = runCatching { history.usage() }.getOrNull()
            runOnUiThread { renderUsage(usage) }
        }
    }

    /**
     * The one place the usage line is written, and the one place [lastUsage] is
     * set — so the number in the delete confirmation is always the number on
     * screen rather than a second, independently-read count.
     *
     * A store that cannot be read leaves the pending text alone rather than
     * claiming "0 dictations", the one number that would tell the user their
     * history is gone when it is not.
     */
    private fun renderUsage(usage: DictationHistoryStore.Usage?) {
        if (usage == null) return
        lastUsage = usage
        historyUsage.text = usageText(usage)
    }

    /**
     * "History: 3 dictations, 1.4 MB".
     *
     * MB here is mebibytes (2^20), matching every file manager on the device;
     * one decimal place, because a second one on a figure the user can only
     * act on wholesale is noise.
     *
     * 🔴 Formatted in `resources.configuration.locales[0]`, **not**
     * `Locale.getDefault()`. The two halves of this one sentence are formatted
     * by different code: `%1$d` by `getQuantityString`, which uses the resource
     * configuration's locale, and `%2$s` here. Those are the same value on an
     * ordinary handset and are NOT the same under a per-app locale (Android
     * 13's `LocaleManager`, or any `createConfigurationContext` override), where
     * the result is one sentence carrying two locales' number conventions — a
     * count formatted one way and a decimal separator from somewhere else.
     * Reading the locale off `resources` makes that disagreement
     * unrepresentable rather than merely unlikely.
     */
    private fun usageText(usage: DictationHistoryStore.Usage): String = resources
        .getQuantityString(
            R.plurals.stt_history_usage,
            usage.itemCount,
            usage.itemCount,
            String.format(
                resources.configuration.locales[0],
                "%.1f",
                usage.totalBytes.toDouble() / HISTORY_BYTES_PER_MB,
            ),
        )

    /**
     * Asks first. Deletes nothing.
     *
     * 🔴 The button's destructive styling and its `contentDescription` are
     * **cues**, and a cue is a label rather than a gate: before this dialog
     * existed, one mis-tap on a scrolling settings screen irreversibly
     * destroyed every recording and transcript on the device. There is no undo
     * and deliberately no soft delete — recordings the user asked to delete
     * have to actually be gone, which is the posture
     * [DictationHistoryStore]'s kdoc commits to — so the gate has to be in
     * front of the action, not behind it.
     *
     * The message names the count and the irreversibility rather than asking
     * "Are you sure?". The count comes from [lastUsage], i.e. the number the
     * usage line is showing, so the dialog cannot quote a different figure to
     * the one on screen — and it is read from a field rather than from
     * `usage()` because `usage()` is disk IO and this runs on the main thread.
     *
     * ⚠ Which makes the count a **stale read**. [lastUsage] is written only by
     * `onCreate`'s worker read and the read after a delete — there is no
     * `onResume` refresh — so the window lasts as long as this activity stays
     * backgrounded.
     *
     * 🔴 It can drift in **either** direction. An earlier version of this
     * comment said only *new* items could appear in the gap and concluded the
     * dialog could therefore **understate** the loss and never overstate it.
     * That is false. A route to an overstatement, established from the call
     * sites rather than reasoned about — [HistoryRetention.evict] has exactly
     * one caller in the whole app, `DictationHistoryStore.add` — is that
     * retention is never applied on the `usage()` read, so
     * items already past [HistoryRetention.MAX_AGE_MS] are still counted — and
     * one bubble dictation firing `add()` while this screen is backgrounded
     * evicts them, leaving the dialog naming more items than
     * [DictationHistoryStore.clearAll] then removes.
     *
     * Kept deliberately: the only way to make the number exact is a `usage()`
     * read, i.e. disk IO, on the main thread. It is tolerable because the count
     * is an aid and **not** the promise — the user is agreeing to "every saved
     * recording … this cannot be undone", which stays true at any count. No
     * claim is made here about the drift's sign or its size.
     *
     * Cancel, back and a tap outside all do exactly nothing: the negative
     * button has no listener and the dialog is cancelable. There is no partial
     * clear to get wrong.
     *
     * ⚠ Deliberately NOT applied to "Clear token", which stays unconfirmed: a
     * token is re-pasteable from the password manager it came from, a recording
     * of the user's voice is not. Consistency between the two controls is worth
     * less than keeping the gate meaningful on the one that needs it.
     */
    private fun clearHistory() {
        val count = lastUsage?.itemCount ?: 0
        confirmDialog = AlertDialog.Builder(this)
            .setTitle(R.string.stt_clear_history_confirm_title)
            .setMessage(
                if (count > 0) {
                    resources.getQuantityString(
                        R.plurals.stt_clear_history_confirm,
                        count,
                        count,
                    )
                } else {
                    // Nothing to count: an empty history, or the first usage
                    // read not back yet. "Delete all 0 saved recordings" is
                    // worse copy than naming no number at all.
                    getString(R.string.stt_clear_history_confirm_any)
                },
            )
            .setPositiveButton(R.string.stt_clear_history_confirm_delete) { _, _ ->
                deleteAllHistory()
            }
            .setNegativeButton(R.string.stt_clear_history_confirm_keep, null)
            .show()
    }

    /**
     * Deletes every recording and transcript, then re-reads what is left.
     * Reached only from the confirmation dialog's positive button.
     *
     * 🔴 `clearAll()` unlinks every file in the history directory, so it runs on
     * [worker] — on the main thread a full history is a visible freeze on the
     * one control whose whole job is to reassure.
     *
     * The refresh is NOT optional and NOT cosmetic: this screen's only report
     * of what the app is keeping is the usage line, so a delete that leaves it
     * reading "12 dictations" tells the user their voice recordings are still
     * on the phone. It is also the only confirmation the action gives.
     */
    private fun deleteAllHistory() {
        worker.execute {
            runCatching { history.clearAll() }
            val usage = runCatching { history.usage() }.getOrNull()
            runOnUiThread { renderUsage(usage) }
        }
    }

    // --- snippets (issue #38) -----------------------------------------------

    /**
     * Starts the document picker for a snip `snippets.json`.
     *
     * ## 🔴 An in-app picker, and NO new exported surface
     *
     * The operator's decision over the issue's share-sheet wording:
     * `ZacxDev/dicta` is a public app, and an `<intent-filter>` that accepts a
     * catalog is an entry point **any installed app can invoke**. An
     * `ACTION_OPEN_DOCUMENT` started from a button on this screen can be
     * reached only by the person holding the phone.
     *
     * ## 🔴 Hand-rolled `startActivityForResult`, deliberately
     *
     * `ActivityResultContracts` is androidx, and this app's runtime
     * `dependencies {}` block is empty by explicit, re-measured decision (see
     * `app/build.gradle.kts`). Importing androidx.activity to save six lines
     * would put a dependency tree in the shipped dex for a button.
     *
     * ## 🔴 The type filter is a HINT; the CONTENT is what is validated
     *
     * `type` is `application/json` with [IMPORT_MIME_TYPES] alongside it,
     * because a document provider's declared type is a claim about a file
     * rather than a fact — a `snippets.json` served as `text/plain`, or as
     * `application/octet-stream`, is still the file the user meant. The
     * fallback entry keeps such a provider's files selectable; [SnipCatalog]
     * then decides, from the bytes, whether this is a catalog. Nothing here
     * looks at the file's NAME.
     */
    private fun importSnippets() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(IMPORT_MIME)
            .putExtra(Intent.EXTRA_MIME_TYPES, IMPORT_MIME_TYPES)
        // A device with no documents provider at all raises
        // ActivityNotFoundException. Saying so beats a crash on a settings
        // screen, and beats a silent no-op even more.
        if (!runCatching { startActivityForResult(picker, REQUEST_IMPORT_SNIPPETS) }.isSuccess) {
            snippetsState.text = getString(R.string.stt_snippets_no_picker)
        }
    }

    /**
     * Reads the chosen document, validates it, and replaces the catalog.
     *
     * 🔴 **A refused file changes nothing.** [SnipStore.save] is reached only
     * after [SnipCatalog.parse] has returned, so a malformed document leaves
     * the previous catalog exactly as it was and the reason goes on screen. The
     * alternative — clear first, then import — turns one bad file into a lost
     * catalog, which is the shape [DictationHistoryStore] spends four
     * paragraphs avoiding for recordings.
     *
     * On [worker] because it is file IO plus a parse of the whole catalog, and
     * a `ContentResolver` read can be a network fetch behind a cloud provider's
     * `DocumentsProvider`.
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMPORT_SNIPPETS) return
        // A cancelled picker is not a failure and must not report one: the
        // state line keeps saying whatever is actually stored.
        if (resultCode != RESULT_OK) return
        // 🔴 RESULT_OK with no URI, which is NOT a cancellation: the user went
        // through the picker and chose something, and the provider handed back
        // a result with no document in it. Returning silently here is the
        // silent no-op this feature argues against everywhere else — the
        // operator taps Import, watches nothing happen, and has no way to learn
        // whether the file was rejected or the tap was lost. It is a read
        // failure, so it gets the read-failure wording.
        val uri = data?.data ?: run {
            snippetsState.text = getString(
                R.string.stt_snippets_import_unreadable,
                getString(R.string.stt_snippets_import_no_file),
            )
            return
        }
        val store = snippets ?: return
        worker.execute {
            val outcome = runCatching {
                val text = contentResolver.openInputStream(uri)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }
                    // 🔴 An IOException, NOT SnipCatalog.Invalid. A provider
                    // that cannot open its own document says nothing about the
                    // file's contents, and routing it through the "that file is
                    // not a snippets.json" frame tells the operator to re-export
                    // a file that is probably fine.
                    ?: throw IOException("the provider returned no stream")
                val parsed = SnipCatalog.parse(text)
                store.save(parsed, System.currentTimeMillis())
                parsed
            }
            runOnUiThread {
                outcome.fold(
                    onSuccess = { renderSnippets(SnipStore.Catalog(it, System.currentTimeMillis())) },
                    onFailure = { snippetsState.text = importFailureText(it) },
                )
            }
        }
    }

    /**
     * The one place an import failure is worded — in **two** frames, because
     * there are two different failures and they have different next steps.
     *
     * 🔴 A refusal must name what is actually wrong ([SnipCatalog]'s own
     * doctrine). [SnipCatalog.Invalid] genuinely means "this is not a
     * snippets.json", and [SnipCatalog.Invalid.reason] names which way — not an
     * array, a row with no label, a duplicate label — all of them things the
     * operator fixes by editing or re-exporting the file.
     *
     * Everything else is a READ failure over a file that may be perfectly
     * valid: a `SecurityException` from a URI whose grant was revoked, an
     * `IOException`, a timeout from a cloud provider's `DocumentsProvider`. One
     * `runCatching` wraps the whole read, so these arrive here by the same
     * path — and wording them as "that file is not a snippets.json" sends the
     * operator off to re-export a good file when the right action is to try
     * again. They get their own frame; the exception's class name is the honest
     * stand-in for a reason, since there is none to quote.
     */
    private fun importFailureText(t: Throwable): String {
        val invalid = t as? SnipCatalog.Invalid
        return if (invalid != null) {
            getString(R.string.stt_snippets_import_failed, invalid.reason)
        } else {
            getString(R.string.stt_snippets_import_unreadable, t.javaClass.simpleName)
        }
    }

    /** Reads the stored catalog on [worker] and renders it. See [refreshHistoryUsage]. */
    private fun refreshSnippetsState() {
        val store = snippets ?: return
        worker.execute {
            val catalog = runCatching { store.catalog() }.getOrNull()
            runOnUiThread { renderSnippets(catalog) }
        }
    }

    /**
     * The one place the snippets line is written.
     *
     * 🔴 `null` renders the **named** empty state, never a blank line and
     * never "0 snippets": "nothing imported yet" tells the operator what to do
     * next, and it is the same reasoning the picker's own three distinct empty
     * strings rest on. A silently empty surface is indistinguishable from a
     * broken feature.
     */
    private fun renderSnippets(catalog: SnipStore.Catalog?) {
        snippetsState.text = when {
            catalog == null || catalog.snippets.isEmpty() ->
                getString(R.string.stt_snippets_none)
            else -> resources.getQuantityString(
                R.plurals.stt_snippets_count,
                catalog.snippets.size,
                catalog.snippets.size,
                importedAtText(catalog.importedAtMs),
            )
        }
    }

    /**
     * When the catalog was imported.
     *
     * Formatted in `resources.configuration.locales[0]` for [usageText]'s
     * measured reason: the count beside it is formatted by
     * `getQuantityString`, which uses the resource configuration's locale, and
     * the two disagreeing under a per-app locale produces one sentence
     * carrying two locales' conventions.
     */
    private fun importedAtText(importedAtMs: Long): String =
        java.text.DateFormat
            .getDateTimeInstance(
                java.text.DateFormat.SHORT,
                java.text.DateFormat.SHORT,
                resources.configuration.locales[0],
            )
            .format(java.util.Date(importedAtMs))

    /**
     * Probes `GET /healthz` against the URL currently in the field.
     *
     * 🔴 Uses the *unauthenticated* health endpoint, so a successful test says
     * "the service is reachable and its model is loaded" and deliberately
     * says **nothing** about whether the token is right. The two are reported
     * separately because conflating them is how a token fault gets diagnosed
     * as an outage.
     */
    private fun testConnection() {
        val url = urlField.text.toString().trim().ifEmpty {
            SttEndpoints.DEFAULT_BASE_URL
        }
        statusLine.text = getString(R.string.stt_testing)
        worker.execute {
            // The token is irrelevant to /healthz; pass empty rather than
            // reading it, so a reachability test cannot leak it anywhere.
            val ok = SttHttp(url, token = "").healthz()
            runOnUiThread {
                statusLine.text = getString(
                    if (ok) R.string.stt_test_ok else R.string.stt_test_failed
                )
            }
        }
    }

    private companion object {
        /** Mebibytes, per the note on [usageText]. */
        const val HISTORY_BYTES_PER_MB = 1024.0 * 1024.0

        /**
         * `startActivityForResult` request code for the snippets import.
         *
         * Hand-rolled rather than `ActivityResultContracts` — see
         * [importSnippets] for why androidx is not an option here. It is the
         * only request code this activity uses, and [onActivityResult] branches
         * on it rather than assuming so.
         */
        const val REQUEST_IMPORT_SNIPPETS = 1001

        /** The type a `snippets.json` ought to be served as. */
        const val IMPORT_MIME = "application/json"

        /**
         * The types a document provider is allowed to be wrong about.
         *
         * 🔴 Not permissiveness: the content is validated either way (see
         * [importSnippets]). This only decides which files the picker lets the
         * user SELECT, and a provider that serves a `.json` as `text/plain` or
         * `application/octet-stream` would otherwise grey out the very file
         * they came to choose.
         */
        val IMPORT_MIME_TYPES = arrayOf(
            IMPORT_MIME,
            "text/plain",
            "application/octet-stream",
        )
    }
}
