package dev.zacx.dicta

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
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

    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = SttSettings.create(this)

        urlField = findViewById(R.id.stt_url)
        tokenField = findViewById(R.id.stt_token)
        statusLine = findViewById(R.id.stt_status)
        remoteToggle = findViewById(R.id.stt_remote_enabled)

        urlField.setText(settings.baseUrl)
        remoteToggle.isChecked = settings.remoteEnabled
        renderTokenState()

        findViewById<Button>(R.id.stt_save).setOnClickListener { save() }
        findViewById<Button>(R.id.stt_clear_token).setOnClickListener { clearToken() }
        findViewById<Button>(R.id.stt_test).setOnClickListener { testConnection() }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
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
