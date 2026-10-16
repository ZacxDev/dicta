package dev.zacx.dicta

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
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

    /**
     * History, read here only through [DictationHistoryStore.usage] and
     * [DictationHistoryStore.clearAll]. This screen makes no retention
     * decision and exposes no bound — see the History comment in
     * `activity_settings.xml`.
     */
    private lateinit var history: DictationHistoryStore

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
        history = DictationHistoryStore(this)

        urlField.setText(settings.baseUrl)
        remoteToggle.isChecked = settings.remoteEnabled
        bindLanguageSpinner()
        renderTokenState()

        findViewById<Button>(R.id.stt_save).setOnClickListener { save() }
        findViewById<Button>(R.id.stt_clear_token).setOnClickListener { clearToken() }
        findViewById<Button>(R.id.stt_test).setOnClickListener { testConnection() }
        findViewById<Button>(R.id.stt_clear_history).setOnClickListener { clearHistory() }

        refreshHistoryUsage()
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
    }
}
