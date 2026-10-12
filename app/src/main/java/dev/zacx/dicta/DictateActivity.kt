package dev.zacx.dicta

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    private lateinit var attachmentNote: TextView

    private var speech: SpeechHelper? = null
    private val finalText = StringBuilder()
    private var lastPartial = ""

    /**
     * Set once a capture succeeds. `internal` rather than `private` only so a
     * unit test can put the activity into the "a screenshot is attached" state
     * without a real MediaProjection — see DictateShareTest.
     */
    internal var screenshotUri: Uri? = null

    /** Off-main-thread worker for the blocking capture round trip. */
    private var captureWorker: java.util.concurrent.ExecutorService? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dictate)
        // Must run AFTER setContentView: PhoneWindow.generateLayout() resets a
        // floating window to WRAP_CONTENT x WRAP_CONTENT while inflating.
        applyFloatingWindowSize()

        statusText = findViewById(R.id.status_text)
        textInput = findViewById(R.id.dictated_text)
        micButton = findViewById(R.id.mic_button)
        attachmentNote = findViewById(R.id.attachment_note)

        micButton.setOnClickListener { toggleDictation() }
        // Copy/Screenshot/Send/Close are ImageButtons (ImageView subclasses, NOT
        // Buttons) — findViewById<Button> on them would throw
        // ClassCastException. Pinned by DictateActionViewTypesTest.
        findViewById<ImageButton>(R.id.copy_button).setOnClickListener { copyToClipboard() }
        findViewById<ImageButton>(R.id.screenshot_button)
            .setOnClickListener { requestScreenshot() }
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
        captureWorker?.shutdown()
        captureWorker = null
        // If we are torn down mid-capture the bubble must not stay hidden.
        OverlayVisibility.setVisible(true)
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
            return
        }
        if (requestCode == REQ_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                runScreenshotSequence(resultCode, data)
            } else {
                statusText.text = getString(R.string.screenshot_consent_declined)
            }
        }
    }

    // --- screenshot ---------------------------------------------------------

    /**
     * Mid-dictation behaviour, decided deliberately: tapping Screenshot **stops
     * the recogniser first** and keeps whatever has been transcribed so far.
     *
     * The alternative — leave the mic running — does not work: the consent
     * dialog takes window focus, and both the system consent UI and the capture
     * flow move Dicta out of the top-app state that wins microphone
     * arbitration, so the recogniser would error out mid-utterance and lose the
     * partial anyway. Stopping explicitly means the partial is committed to the
     * text box instead of being dropped. The user can tap the mic again after
     * the capture.
     */
    private fun requestScreenshot() {
        speech?.takeIf { it.isListening }?.let {
            it.stop()
            micButton.text = getString(R.string.start_dictation)
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        if (mpm == null) {
            statusText.text = getString(R.string.screenshot_unsupported)
            return
        }
        // Per-session consent. On Android 14+ this CANNOT be made permanent —
        // the prompt is expected on every capture, and the UI does not pretend
        // otherwise.
        statusText.text = getString(R.string.screenshot_requesting)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
        } catch (_: ActivityNotFoundException) {
            statusText.text = getString(R.string.screenshot_unsupported)
        }
    }

    private fun runScreenshotSequence(resultCode: Int, data: Intent) {
        val worker = captureWorker
            ?: Executors.newSingleThreadExecutor().also { captureWorker = it }
        val main = Executor { command -> runOnUiThread(command) }

        ScreenshotSequencer(
            ui = DictaWindows(),
            settler = ChoreographerFrameSettler(),
            worker = worker,
            main = main,
            capture = { captureViaService(resultCode, data) },
            onDone = { result -> onScreenshotDone(result) },
        ).start()
    }

    /**
     * Blocks on the [ProjectionService] round trip. Runs on the capture worker,
     * never the main thread.
     */
    private fun captureViaService(resultCode: Int, data: Intent): Uri {
        val latch = CountDownLatch(1)
        val outcome = java.util.concurrent.atomic.AtomicReference<Result<Uri>>()
        ProjectionRequests.submit(
            ProjectionRequests.Request(resultCode, data) { result ->
                outcome.set(result)
                latch.countDown()
            }
        )
        ProjectionService.start(this)
        if (!latch.await(SERVICE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            ProjectionRequests.abort(IllegalStateException("capture timed out"))
            throw IllegalStateException(
                "no screenshot after ${SERVICE_TIMEOUT_SECONDS}s"
            )
        }
        return outcome.get().getOrThrow()
    }

    private fun onScreenshotDone(result: Result<Uri>) {
        result.fold(
            onSuccess = { uri ->
                screenshotUri = uri
                attachmentNote.visibility = View.VISIBLE
                statusText.text = getString(R.string.screenshot_ready)
            },
            onFailure = { error ->
                screenshotUri = null
                attachmentNote.visibility = View.GONE
                statusText.text = getString(
                    R.string.screenshot_failed,
                    error.message ?: error.javaClass.simpleName
                )
            }
        )
    }

    /**
     * Every Dicta-owned window: this dialog plus, if the bubble service is
     * running, its overlay. MediaProjection composites the whole screen, so
     * both would otherwise appear in our own screenshot.
     */
    private inner class DictaWindows : HideableUi {

        override fun hideForCapture() {
            // The 0.6 background dim is a WINDOW attribute, applied by the
            // window manager regardless of whether the decor draws anything.
            // Hiding the decor alone would leave the whole screen darkened in
            // the capture, so the dim has to be cleared too.
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setDimAmount(0f)
            window.decorView.visibility = View.INVISIBLE
            OverlayVisibility.setVisible(false)
        }

        override fun restoreAfterCapture() {
            window.decorView.visibility = View.VISIBLE
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setDimAmount(DIM_AMOUNT)
            OverlayVisibility.setVisible(true)
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

    /**
     * Send. Refuses only when there is **neither** text nor an attached image.
     *
     * An image with an empty text box is a legitimate share — "screenshot this
     * and send just the screenshot" — and the previous `if (text.isEmpty())
     * return` made it impossible: the Screenshot button would attach a capture
     * and Send would then refuse to do anything with it. The empty-handed
     * refusal still exists, but only for the genuinely-empty case.
     */
    private fun shareText() {
        val text = currentText()
        val image = screenshotUri
        if (text.isEmpty() && image == null) {
            Toast.makeText(this, getString(R.string.nothing_to_share), Toast.LENGTH_SHORT)
                .show()
            return
        }
        val send = if (image == null) {
            ShareIntents.textOnly(text)
        } else {
            ShareIntents.withImage(
                contentResolver, text, image, getString(R.string.app_name)
            )
        }
        val title = getString(
            when {
                image == null -> R.string.share_via
                text.isEmpty() -> R.string.share_image_via
                else -> R.string.share_with_image_via
            }
        )
        startActivity(ShareIntents.chooser(send, title))
    }

    companion object {
        private const val REQ_MIC = 1001
        private const val REQ_VOICE_FALLBACK = 1002
        private const val REQ_PROJECTION = 1003

        /**
         * The dim restored after a capture. It MUST equal
         * `android:backgroundDimAmount` in Theme.Dicta.Overlay: the hide path
         * clears the dim and the restore path puts it back from this constant,
         * so if the theme and this number disagree, the card is permanently
         * mis-dimmed after the first capture.
         *
         * That relationship is enforced, not just asserted in prose —
         * `DictateOverlayThemeTest.theRestoredDimEqualsTheThemeDim` resolves
         * the theme attribute and compares it to this constant, so a theme-only
         * edit to 0.5 turns the suite red. `internal` rather than `private` for
         * exactly that reason.
         */
        internal const val DIM_AMOUNT = 0.6f

        /**
         * Consent is already granted by the time we get here, so this only has
         * to cover service start + one composited frame.
         */
        private const val SERVICE_TIMEOUT_SECONDS = 15L
    }
}
