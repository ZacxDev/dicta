package dev.zacx.dicta

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast

/**
 * The dictation overlay: floating dialog-style activity opened from the QS
 * tile, the floating bubble, or the notification action — i.e. from anywhere.
 *
 * Flow: tap mic → speak → live transcription → edit → Copy / Send.
 */
class DictateActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var textInput: EditText
    private lateinit var micButton: Button

    private var speech: SpeechHelper? = null
    private val finalText = StringBuilder()
    private var lastPartial = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dictate)
        // Must run AFTER setContentView: PhoneWindow.generateLayout() resets a
        // floating window to WRAP_CONTENT x WRAP_CONTENT while inflating.
        applyFloatingWindowSize()

        statusText = findViewById(R.id.status_text)
        textInput = findViewById(R.id.dictated_text)
        micButton = findViewById(R.id.mic_button)

        micButton.setOnClickListener { toggleDictation() }
        // Copy/Send/Close are ImageButtons (ImageView subclasses, NOT Buttons) —
        // findViewById<Button> on them would throw ClassCastException.
        findViewById<ImageButton>(R.id.copy_button).setOnClickListener { copyToClipboard() }
        findViewById<ImageButton>(R.id.share_button).setOnClickListener { shareText() }
        findViewById<ImageButton>(R.id.close_button).setOnClickListener { finish() }

        if (hasMicPermission()) {
            startDictation()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    override fun onDestroy() {
        speech?.destroy()
        speech = null
        super.onDestroy()
    }

    // --- floating window geometry -------------------------------------------

    /**
     * Caps the floating window at [R.fraction.overlay_width_fraction] of the
     * display width and centres it.
     *
     * Why in code rather than in the theme: the dialog window is WRAP_CONTENT,
     * so without a cap the decor is measured at `AT_MOST(displayWidth)` and any
     * content that wants to be wider than the screen — a long hint, a
     * match_parent row — simply clamps to the full display width, which is how
     * the "it looks like a full app screen" report happens. The only theme
     * attributes that can make the window *narrower* than its content
     * (`windowFixedWidthMinor` / `windowFixedWidthMajor`) are private platform
     * attributes and fail aapt2 linking from an app theme; `windowMinWidth*`
     * can only widen. `Window.setLayout` is the public equivalent and is
     * applied on every create, so a rotation (which recreates the activity)
     * re-reads the orientation-qualified fraction.
     */
    internal fun applyFloatingWindowSize() {
        val metrics = resources.displayMetrics
        val fraction = resources.getFraction(R.fraction.overlay_width_fraction, 1, 1)
        val width = (metrics.widthPixels * fraction).toInt()
        if (width <= 0) return
        window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.CENTER)
    }

    // --- dictation ----------------------------------------------------------

    private fun hasMicPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQ_MIC) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                startDictation()
            } else {
                // Fallback: system voice-typing UI needs no RECORD_AUDIO from us.
                statusText.text = getString(R.string.mic_denied_fallback)
                micButton.text = getString(R.string.use_system_voice_typing)
            }
        }
    }

    private fun toggleDictation() {
        val helper = speech
        if (helper != null && helper.isListening) {
            helper.stop()
            micButton.text = getString(R.string.start_dictation)
            statusText.text = getString(R.string.dictate_hint)
            return
        }
        if (!hasMicPermission()) {
            fallbackSystemVoiceInput()
            return
        }
        startDictation()
    }

    private fun startDictation() {
        val helper = SpeechHelper(this, object : SpeechHelper.Listener {
            override fun onReady() {
                statusText.text = getString(R.string.listening)
            }

            override fun onPartial(text: String) {
                lastPartial = text
                renderPreview()
            }

            override fun onFinal(text: String) {
                appendFinal(text)
                micButton.text = getString(R.string.start_dictation)
                statusText.text = getString(R.string.dictate_hint)
            }

            override fun onError(message: String) {
                lastPartial = ""
                renderPreview()
                micButton.text = getString(R.string.start_dictation)
                statusText.text = message
            }
        })
        if (!helper.isAvailable) {
            statusText.text = getString(R.string.no_recognizer)
            return
        }
        speech?.destroy()
        speech = helper
        helper.start()
        micButton.text = getString(R.string.stop_dictation)
    }

    private fun appendFinal(text: String) {
        if (finalText.isNotEmpty()) finalText.append(" ")
        finalText.append(text.trim())
        lastPartial = ""
        renderPreview()
    }

    private fun renderPreview() {
        val preview = buildString {
            append(finalText)
            if (lastPartial.isNotBlank()) {
                if (isNotEmpty()) append(" ")
                append(lastPartial)
            }
        }
        // Don't clobber the cursor while the user is editing manually.
        if (!textInput.hasFocus()) {
            textInput.setText(preview)
            textInput.setSelection(preview.length)
        } else if (textInput.text.toString() != preview && lastPartial.isNotBlank()) {
            textInput.setText(preview)
            textInput.setSelection(preview.length)
        }
    }

    /** Permission-free fallback: delegate capture to the system voice UI. */
    private fun fallbackSystemVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.speak_now))
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_VOICE_FALLBACK)
        } catch (_: ActivityNotFoundException) {
            statusText.text = getString(R.string.no_voice_input)
        }
    }

    @Deprecated("PoC keeps the classic API to avoid an AndroidX dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VOICE_FALLBACK && resultCode == RESULT_OK) {
            val text = data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) appendFinal(text)
        }
    }

    // --- copy / share -------------------------------------------------------

    private fun currentText(): String = textInput.text.toString().trim()

    private fun copyToClipboard() {
        val text = currentText()
        if (text.isEmpty()) {
            Toast.makeText(this, getString(R.string.nothing_to_copy), Toast.LENGTH_SHORT)
                .show()
            return
        }
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Dicta", text))
        Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show()
    }

    private fun shareText() {
        val text = currentText()
        if (text.isEmpty()) {
            Toast.makeText(this, getString(R.string.nothing_to_share), Toast.LENGTH_SHORT)
                .show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_via)))
    }

    companion object {
        private const val REQ_MIC = 1001
        private const val REQ_VOICE_FALLBACK = 1002
    }
}
