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
    private lateinit var levelMeter: android.widget.ProgressBar
    private lateinit var engineBadge: TextView

    private var speech: SpeechHelper? = null
    private val finalText = StringBuilder()
    private var lastPartial = ""

    /**
     * The remote-first dictation session: one microphone, the phone's
     * recogniser fed from it through a pipe, and the self-hosted service
     * producing the authoritative transcript.
     */
    private var session: DictationSession? = null
    private var settings: SttSettings? = null

    /**
     * True once the server's transcript has landed.
     *
     * Guards the one ordering hazard in the handover: the phone's *final*
     * result can arrive after the server's, and without this flag it would
     * overwrite the better transcript with the worse one.
     */
    private var haveRemoteTranscript = false

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
        levelMeter = findViewById(R.id.level_meter)
        engineBadge = findViewById(R.id.engine_badge)

        // Robolectric can create the real Activity but cannot provide an
        // AndroidKeyStore, so settings construction is allowed to fail here
        // without taking the whole overlay down with it. In that state the
        // remote path reports "not configured" and the phone's recogniser is
        // used — which is exactly the documented degradation.
        settings = try {
            SttSettings.create(this)
        } catch (_: Throwable) {
            null
        }

        micButton.setOnClickListener { toggleDictation() }
        // Copy/Screenshot/Send/Close are ImageButtons (ImageView subclasses, NOT
        // Buttons) — findViewById<Button> on them would throw
        // ClassCastException. Pinned by DictateActionViewTypesTest.
        findViewById<ImageButton>(R.id.copy_button).setOnClickListener { copyToClipboard() }
        findViewById<ImageButton>(R.id.screenshot_button)
            .setOnClickListener { requestScreenshot() }
        findViewById<ImageButton>(R.id.share_button).setOnClickListener { shareText() }
        findViewById<ImageButton>(R.id.close_button).setOnClickListener { finish() }

        wireOnDemandActions()

        // 🔴 THE auto-start, and the single highest-risk line in the bubble
        // redesign. It used to be unconditional, because opening this card and
        // starting to record were the same event. They are not any more: when
        // the bubble opens the card, capture has ALREADY happened and the
        // upload is already in flight, so starting again here would construct a
        // second DictationSession — a second AudioRecord — while the first
        // one's file is being POSTed.
        //
        // That failure is silent. Android resolves concurrent capture by
        // feeding the loser bit-exact zeros: startRecording() succeeds, read()
        // returns the full byte count, and the only observable is a transcript
        // that comes back empty or hallucinated. It reads as a server bug and
        // gets investigated as one. DictationSessionLedgerTest is the ledger
        // that keeps the set of session owners honest.
        if (intent?.getBooleanExtra(DictateLaunch.EXTRA_ATTACH_SESSION, false) == true) {
            attachToBubbleSession()
        } else if (hasMicPermission()) {
            startDictation()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    /**
     * Binds the card to the recording the bubble already made.
     *
     * The status line is set up front rather than waiting for the session's own
     * first message: by the time this activity is on screen the upload may
     * already be several hundred milliseconds old, and a card that opens blank
     * reads as a card that did nothing.
     *
     * [BubbleSessionBus] then replays anything said while nothing was attached
     * — which can include the transcript itself on a short recording — so the
     * engine badge and the status line stay honest about which engine produced
     * what is on screen.
     */
    private fun attachToBubbleSession() {
        statusText.text = getString(R.string.stt_uploading)
        micButton.text = getString(R.string.start_dictation)
        levelMeter.visibility = View.INVISIBLE
        val sink = uiCallbacks()
        busSink = sink
        BubbleSessionBus.attach(sink)
    }

    /** Non-null only while this card is attached to a bubble session. */
    private var busSink: DictationSession.Callbacks? = null

    override fun onDestroy() {
        busSink?.let { BubbleSessionBus.detach(it) }
        busSink = null
        // Before speech: the session owns the microphone and the recogniser's
        // pipe, and closing the pipe's write end is what ends the recognition
        // session. Tearing the recogniser down first leaves a writer with
        // nowhere to write.
        session?.destroy()
        session = null
        speech?.destroy()
        speech = null
        captureWorker?.shutdown()
        captureWorker = null
        // If we are torn down mid-capture the bubble must not stay hidden.
        OverlayVisibility.setVisible(true)
        super.onDestroy()
    }

    // --- the on-demand action row -------------------------------------------

    /**
     * Wires the two routes that reveal `@id/action_row`, which the layout
     * starts GONE so that the resting card is the transcript and the mic and
     * nothing else.
     *
     * 🔴 Discoverability is the whole risk of hiding a control, so there are
     * deliberately TWO mechanisms here plus a third in the copy, and none of
     * them depends on the others:
     *
     *  1. [R.id.more_button] — always visible next to the mic, toggles the row.
     *     Its `contentDescription` and `tooltipText` are BOTH swapped with the
     *     row's visibility, so the toggle state is announced rather than only
     *     drawn (the `state_selected` fill in `ghost_button_bg` is the visual
     *     half, and would be colour-only on its own).
     *  2. A [android.text.TextWatcher] on the transcript — the row appears by
     *     itself the moment there is text to act on, so after the first
     *     utterance the user is never asked to find anything. It is hidden only
     *     in the state where every action in it would refuse ("Nothing to copy
     *     yet…").
     *  3. `@string/dictate_hint`, the resting status line, names the route in
     *     words.
     *
     * The watcher only ever reveals, never hides: a user who types, deletes and
     * retypes should not have the row flicker, and once revealed the row has
     * been seen — re-hiding it would be the app forgetting what the user
     * already learned. [R.id.more_button] remains the way to put it away.
     */
    private fun wireOnDemandActions() {
        val row = findViewById<View>(R.id.action_row)
        val more = findViewById<ImageButton>(R.id.more_button)

        more.setOnClickListener {
            setActionsRevealed(row, more, row.visibility != View.VISIBLE)
        }
        setActionsRevealed(row, more, currentText().isNotEmpty())

        textInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                if (!s.isNullOrBlank() && row.visibility != View.VISIBLE) {
                    setActionsRevealed(row, more, true)
                }
            }

            override fun beforeTextChanged(
                s: CharSequence?, start: Int, count: Int, after: Int
            ) = Unit

            override fun onTextChanged(
                s: CharSequence?, start: Int, before: Int, count: Int
            ) = Unit
        })
    }

    private fun setActionsRevealed(row: View, more: ImageButton, revealed: Boolean) {
        row.visibility = if (revealed) View.VISIBLE else View.GONE
        more.isSelected = revealed
        val label = getString(
            if (revealed) R.string.hide_actions else R.string.more_actions
        )
        more.contentDescription = label
        more.tooltipText = label
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
        session?.takeIf { it.isRecording }?.let {
            it.stop()
            micButton.text = getString(R.string.start_dictation)
            return
        }
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

    /**
     * Starts the remote-first session, falling back to the phone-only path
     * when capture cannot start at all.
     *
     * The two paths are kept distinct rather than unified: the remote path
     * owns the microphone and pipes the recogniser, while the phone-only path
     * lets the recogniser open the microphone itself. 🔴 Doing both at once is
     * the one thing that must never happen — Android would hand one of them
     * exact zeros with no error.
     */
    private fun startDictation() {
        haveRemoteTranscript = false
        val config = settings
        if (config != null && startRemoteSession(config)) return
        startPhoneOnlyDictation()
    }

    private fun startRemoteSession(config: SttSettings): Boolean {
        val started = DictationSession(
            context = this,
            settings = config,
            callbacks = uiCallbacks(),
        )
        return if (started.start()) {
            session?.destroy()
            session = started
            micButton.text = getString(R.string.stop_dictation)
            levelMeter.visibility = View.VISIBLE
            true
        } else {
            started.destroy()
            false
        }
    }

    /** The original behaviour: the phone's recogniser, opening its own mic. */
    private fun startPhoneOnlyDictation() {
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
                showEngine(fromRemote = false)
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

    /**
     * The one construction site for the view-binding callbacks — the real
     * session at [startRemoteSession] and `OverlayMinimalLayoutTest` both go
     * through here.
     *
     * That shared route is the point. The status strip's level meter and engine
     * badge are only reachable through these callbacks, so a test that built its
     * own stand-in would be asserting against a fixture rather than against the
     * object the activity actually hands to [DictationSession] — and would stay
     * green if this method started returning something else. [SessionCallbacks]
     * holds no state of its own (it writes the activity's views), so a fresh
     * instance behaves identically to the session's.
     *
     * Deliberately NOT annotated `@VisibleForTesting`: that annotation lives in
     * androidx.annotation, and this module ships no androidx at runtime on
     * purpose (see the long note in app/build.gradle.kts, and the dex scan that
     * checks it rather than asserting it). `internal` plus this comment is the
     * whole visibility contract.
     */
    internal fun uiCallbacks(): DictationSession.Callbacks = SessionCallbacks()

    /** Bridges [DictationSession] to the card's views. */
    private inner class SessionCallbacks : DictationSession.Callbacks {

        override fun onTranscript(text: String, fromRemote: Boolean, isFinal: Boolean) {
            // 🔴 The remote transcript wins, and keeps winning: the phone's
            // final result can arrive after it, and overwriting the server's
            // text with the phone's would silently downgrade the result.
            if (haveRemoteTranscript && !fromRemote) return
            if (fromRemote) haveRemoteTranscript = true

            if (isFinal) {
                finalText.setLength(0)
                finalText.append(text.trim())
                lastPartial = ""
            } else {
                lastPartial = text
            }
            renderPreview()
            showEngine(fromRemote)
        }

        override fun onStatus(text: String) {
            statusText.text = text
        }

        override fun onLevel(level: Double) {
            levelMeter.progress = (level * 100).toInt().coerceIn(0, 100)
        }

        override fun onTier(tier: AudioCaptureLogic.Tier) {
            // The meter matters most in the remote-only tiers, where no
            // partial text ever appears.
            levelMeter.visibility = View.VISIBLE
        }

        override fun onClientSilenced(silenced: Boolean) {
            // Surfaced as a real state rather than logged: the user can act
            // on "another app has the microphone", and cannot act on a dead
            // level meter.
            levelMeter.visibility = if (silenced) View.INVISIBLE else View.VISIBLE
        }

        override fun onRecordingStopped() {
            micButton.text = getString(R.string.start_dictation)
            levelMeter.progress = 0
        }
    }

    /** Names the engine behind the text currently in the box. */
    private fun showEngine(fromRemote: Boolean) {
        engineBadge.visibility = View.VISIBLE
        engineBadge.text = getString(
            if (fromRemote) R.string.stt_remote_done else R.string.stt_local_label
        )
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
        // Same reasoning as the recogniser below, and for the same reason it
        // applies to the capture session too: the consent dialog takes window
        // focus and moves Dicta out of the top-app state that wins microphone
        // arbitration, so the capture would be fed silence mid-utterance.
        // Stopping explicitly means the recording is finalised and uploaded
        // rather than abandoned.
        session?.takeIf { it.isRecording }?.let {
            it.stop()
            micButton.text = getString(R.string.start_dictation)
        }
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
            startActivityForResult(screenCaptureIntent(mpm), REQ_PROJECTION)
        } catch (_: ActivityNotFoundException) {
            statusText.text = getString(R.string.screenshot_unsupported)
        }
    }

    /**
     * The consent intent, pinned to **whole-display** capture where the
     * platform allows it.
     *
     * Android 14 (API 34) added a scope chooser to the system consent dialog
     * with *A single app* as the **default**. Dicta's feature is "capture what
     * is behind this card", and [ProjectionService] sizes its VirtualDisplay
     * from the real display metrics — a single-app projection is neither what
     * the user asked for nor what the mirror is sized for (it arrives scaled
     * and letterboxed inside a full-screen surface, with the rest blank).
     *
     * `MediaProjectionConfig.createConfigForDefaultDisplay()` tells the
     * framework this app only ever wants the default display, which removes the
     * single-app option from the dialog. Measured on Android 15 / SM-G991W: the
     * scope spinner is gone and the dialog reads "Entire screen" only.
     *
     * Below API 34 there is no chooser at all — the one-argument overload
     * always means the whole display — so the fallback is not a degradation.
     */
    private fun screenCaptureIntent(mpm: MediaProjectionManager): Intent =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mpm.createScreenCaptureIntent(
                android.media.projection.MediaProjectionConfig
                    .createConfigForDefaultDisplay()
            )
        } else {
            mpm.createScreenCaptureIntent()
        }

    private fun runScreenshotSequence(resultCode: Int, data: Intent) {
        val worker = captureWorker
            ?: Executors.newSingleThreadExecutor().also { captureWorker = it }
        val main = Executor { command -> runOnUiThread(command) }

        ScreenshotSequencer(
            ui = DictaWindows(window, DIM_AMOUNT),
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
