package dev.zacx.dicta

import android.app.Activity
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

        urlField.setText(settings.baseUrl)
        remoteToggle.isChecked = settings.remoteEnabled
        bindLanguageSpinner()
        renderTokenState()

        findViewById<Button>(R.id.stt_save).setOnClickListener { save() }
        findViewById<Button>(R.id.stt_clear_token).setOnClickListener { clearToken() }
        findViewById<Button>(R.id.stt_test).setOnClickListener { testConnection() }
    }

    override fun onDestroy() {
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

        languageSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            labels,
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
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
}
