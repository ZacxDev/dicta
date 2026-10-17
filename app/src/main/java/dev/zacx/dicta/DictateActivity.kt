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

    /**
     * Shown only on a history card, where [micButton] is hidden. See the
     * layout's comment on why this is a second control and not the mic pill
     * wearing a different label.
     */
    private lateinit var retranscribeButton: Button

    /**
     * The stored dictation this card is showing, or null when it is showing a
     * live one. Written on the main thread only (the worker hands its result
     * back through `runOnUiThread`).
     */
    private var historyItem: DictationHistoryStore.Item? = null

    /**
     * Which revision of this card the views belong to.
     *
     * 🔴 Bumped whenever the card is re-purposed — today only by
     * [populateFromHistory]. A [SessionCallbacks] captures it at construction
     * and refuses once the two differ, so a late callback from a session the
     * card has abandoned cannot write views that now belong to something
     * else. See [SessionCallbacks.stale] for the sequence that a
     * "is this a history card" flag could not survive.
     *
     * Bumped SYNCHRONOUSLY, before the async store read: between
     * `populateFromHistory` and that read landing, the stopped session is
     * still finishing its upload and is still wired.
     */
    private var cardGeneration = 0

    /**
     * Reads and re-uploads of stored dictations.
     *
     * Separate from [captureWorker], which is created on demand for a single
     * blocking screenshot round trip and shut down with it. A re-transcribe
     * can be minutes long (the ladder sleeps between retries and polls
     * `/healthz`), so sharing one executor would mean a screenshot queued
     * behind an upload.
     */
    private val historyWorker: java.util.concurrent.ExecutorService =
        Executors.newSingleThreadExecutor()

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
        retranscribeButton = findViewById(R.id.retranscribe_button)

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
        retranscribeButton.setOnClickListener { retranscribe() }
        // Copy/Screenshot/Send/Close are ImageButtons (ImageView subclasses, NOT
        // Buttons) — findViewById<Button> on them would throw
        // ClassCastException. Pinned by DictateActionViewTypesTest.
        findViewById<ImageButton>(R.id.copy_button).setOnClickListener { copyToClipboard() }
        findViewById<ImageButton>(R.id.screenshot_button)
            .setOnClickListener { requestScreenshot() }
        findViewById<ImageButton>(R.id.share_button).setOnClickListener { shareText() }
        findViewById<ImageButton>(R.id.close_button).setOnClickListener { finish() }

        wireOnDemandActions()

        // Which of the three launch modes this is — see applyLaunchIntent,
        // which holds the reasoning that used to sit here.
        applyLaunchIntent(intent)
    }

    /**
     * The three ways this card can be opened, in one place.
     *
     * 🔴 **The auto-start in the last arm is the single highest-risk line in
     * the bubble redesign.** It used to be unconditional, because opening this
     * card and starting to record were the same event. They are not any more:
     * when the bubble opens the card, capture has ALREADY happened and the
     * upload is already in flight, so starting again would construct a second
     * `DictationSession` — a second `AudioRecord` — while the first one's file
     * is being POSTed.
     *
     * That failure is silent. Android resolves concurrent capture by feeding
     * the loser bit-exact zeros: `startRecording()` succeeds, `read()` returns
     * the full byte count, no exception, no error callback. What reaches the
     * user is an empty or invented transcript, which reads as a broken server
     * and gets investigated as one. `DictationSessionLedgerTest` is the ledger
     * that keeps the set of session owners honest.
     *
     * 🔴 **The history arm is a third route to that same hazard**, so it must
     * not reach [startDictation] either. A history card has no capture of its
     * own to protect, but the bubble's may well be running — the bubble stays
     * reachable while this card is up.
     *
     * @param launch the intent to read. Passed rather than read from
     *   `getIntent()` so that [onNewIntent] cannot accidentally branch on the
     *   *original* intent — which is precisely the bug `setIntent` exists to
     *   prevent, and writing it this way means forgetting `setIntent` cannot
     *   silently change what this function sees.
     */
    private fun applyLaunchIntent(launch: Intent?) {
        val historyId = launch?.getStringExtra(DictateLaunch.EXTRA_HISTORY_ID)
        if (launch?.getBooleanExtra(DictateLaunch.EXTRA_ATTACH_SESSION, false) == true) {
            attachToBubbleSession()
        } else if (historyId != null) {
            populateFromHistory(historyId)
        } else if (hasMicPermission()) {
            startDictation()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    /**
     * 🔴 A second intent arriving at a card that is already on top.
     *
     * This activity is `launchMode="singleTop"` and **overrode this nowhere**.
     * So with the card open, picking a history chip never reached `onCreate`,
     * the new extra was silently discarded, and the card went on showing
     * whatever it already had: the user picks item 3 and sees item 1, with no
     * error anywhere. The reveal makes that a routine sequence rather than an
     * exotic one, because the bubble is reachable while the card is up.
     *
     * 🔴 **[setIntent] is half the fix and is not optional.** Without it
     * `getIntent()` keeps returning the *original* intent for the rest of this
     * activity's life, so every later read of an extra — here or in any code
     * added afterwards — answers for a launch that is two taps stale.
     *
     * ## Why only the history arm is re-run
     *
     * Deliberately narrower than [applyLaunchIntent]. Re-running the whole
     * branch would mean a Quick Settings tap (or the notification action) on
     * an already-open card now starts a dictation where today it does nothing
     * — a behaviour change to three entry points, in a change about a fourth.
     * The attach arm is likewise left alone: it survives a dropped extra today
     * only because `busSink` stays registered with [BubbleSessionBus] from the
     * first attach, which is luck rather than design, is adjacent to `#14`
     * item 1, and is **not** fixed here.
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val historyId = intent?.getStringExtra(DictateLaunch.EXTRA_HISTORY_ID) ?: return
        populateFromHistory(historyId)
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

    // --- the history card ----------------------------------------------------

    /**
     * Shows a **stored** dictation: its transcript, which engine produced it,
     * and an offer to send the audio again.
     *
     * 🔴 Constructs no [DictationSession] and touches no microphone — not even
     * to check it. [micButton] is hidden rather than left enabled, because the
     * one thing this screen must not do is open a capture, and a mic button
     * that is on screen and wired to `toggleDictation()` is exactly that, one
     * tap away. `DictateHistoryCardTest` asserts both the hidden button and
     * that [MicrophoneOwner] is still free afterwards.
     *
     * The read is on [historyWorker]: it hits `index.json`, and
     * [DictationHistoryStore.recent] rewrites that index when an audio file
     * has gone missing. The card therefore fills in a moment after it opens,
     * which is the same shape `SettingsActivity`'s usage line uses.
     */
    private fun populateFromHistory(id: String) {
        // 🔴 CLOSE THE MICROPHONE FIRST. This hides `micButton`, which is the
        // card's ONLY stop control — so without this a history intent
        // arriving at a card with a live dictation left the recording running
        // with no reachable way to stop it, its partials interleaving into
        // the text area, while the status line read "the microphone is off."
        //
        // Reachable because the bubble's machine and the card's session are
        // independent: open the card from the QS tile (which starts a
        // dictation), the bubble machine stays IDLE so a long press still
        // reveals, and a chip tap delivers onNewIntent to the card on top.
        // Audit round 1 of #17, F3.
        //
        // Stop rather than discard, for the reason requestScreenshot already
        // gives: a finalised recording is uploaded, an abandoned one is lost.
        stopAnyLiveCapture()
        // 🔴 Immediately after the stop and BEFORE the async read: the
        // session we just stopped is still alive finishing its upload, and
        // its transcript/status/level callbacks are still wired. See
        // [cardGeneration].
        cardGeneration++
        historyItem = null
        levelMeter.visibility = View.INVISIBLE
        micButton.visibility = View.GONE
        retranscribeButton.visibility = View.VISIBLE
        retranscribeButton.isEnabled = false
        statusText.text = getString(R.string.history_saved)
        historyWorker.execute {
            // 🔴 `recent` with no practical limit, not a lookup by id, because
            // the store offers none — and adding one is a change to the
            // storage layer from the PR that consumes it. The index is bounded
            // by HistoryRetention.MAX_ITEMS, so this is a bounded read.
            val item = runCatching {
                historyStore().recent(Int.MAX_VALUE).firstOrNull { it.id == id }
            }.getOrNull()
            runOnUiThread { renderHistoryItem(item) }
        }
    }

    private fun renderHistoryItem(item: DictationHistoryStore.Item?) {
        historyItem = item
        if (item == null) {
            // Not an error state: retention is allowed to remove things, and
            // the chip the user tapped may have been evicted or cleared since
            // the row was drawn.
            statusText.text = getString(R.string.history_missing)
            retranscribeButton.isEnabled = false
            return
        }
        finalText.setLength(0)
        finalText.append(item.text)
        lastPartial = ""
        renderPreview()
        showEngine(item.fromRemote)
        statusText.text = getString(R.string.history_saved)
        retranscribeButton.isEnabled = true
    }

    /**
     * Sends a stored recording to the service again.
     *
     * 🔴 Through [RemoteTranscriber], which structurally **cannot** open a
     * microphone: it holds no `MicCapture`, no `SpeechHelper` and no
     * [MicrophoneOwner] token, and it does not delete the file it is handed —
     * that file is the user's data. The near-miss design (a `DictationSession`
     * with `start()` skipped) would delete it in `destroy()`.
     *
     * 🔴 The result's status line is whatever [RemoteTranscriber] hands back,
     * which it derives with `statusLabel(truncated, unreliable)`. **No second
     * derivation here.** That predicate decides when the server's own
     * "possibly fabricated" / "cut off" verdicts are surfaced, and a copy of
     * it at this call site is a copy that can disagree — including by
     * reassuring the user on a `null`, which means the server could not check
     * at all.
     *
     * The button disables itself for the duration. Nothing serialises two
     * re-transcribes (a fresh `SingleFlightUploader` per call means two
     * genuinely concurrent uploads), and this is the accepted mitigation: a
     * UI guard, device-verified, rather than an architectural one.
     */
    private fun retranscribe() {
        val item = historyItem ?: return
        val config = settings
        if (config == null) {
            statusText.text = getString(R.string.stt_not_configured)
            return
        }
        retranscribeButton.isEnabled = false
        statusText.text = getString(R.string.stt_uploading)
        historyWorker.execute {
            val audio = runCatching { historyStore().audioFor(item.id) }.getOrNull()
            if (audio == null) {
                runOnUiThread {
                    statusText.text = getString(R.string.history_audio_missing)
                    retranscribeButton.isEnabled = false
                }
                return@execute
            }
            runCatching {
                RemoteTranscriber(this, config).transcribe(
                    audio = audio,
                    audioSeconds = item.audioSeconds,
                    // The stored transcript IS the local fallback: if the
                    // server cannot be reached, what the user already had is
                    // the best text available, and blanking the box would be
                    // a re-transcribe that destroyed the transcript it was
                    // asked to improve.
                    localTranscript = { item.text },
                    events = historyEvents(item.id),
                )
            }
            runOnUiThread { retranscribeButton.isEnabled = true }
        }
    }

    /**
     * [RemoteTranscriber.Events] bound to this card.
     *
     * Delivered on [historyWorker] — the transcriber documents that events
     * arrive on the caller's thread — so every view touch marshals, and the
     * store write deliberately does **not**: it is file IO and belongs off the
     * main thread.
     */
    private fun historyEvents(id: String): RemoteTranscriber.Events =
        object : RemoteTranscriber.Events {
            override fun onStatus(text: String) {
                runOnUiThread { statusText.text = text }
            }

            override fun onRemoteTranscript(text: String, status: String) {
                // Persisted so the chip label and a later re-open show the
                // text the user actually ended up with, rather than the first
                // guess this re-transcribe was run to replace.
                runCatching { historyStore().updateText(id, text, fromRemote = true) }
                runOnUiThread {
                    haveRemoteTranscript = true
                    finalText.setLength(0)
                    finalText.append(text.trim())
                    lastPartial = ""
                    renderPreview()
                    showEngine(fromRemote = true)
                    statusText.text = status
                }
            }

            override fun onFellBack(status: String, localTranscript: String) {
                runOnUiThread {
                    statusText.text = status
                    if (localTranscript.isNotBlank()) {
                        finalText.setLength(0)
                        finalText.append(localTranscript.trim())
                        lastPartial = ""
                        renderPreview()
                        showEngine(fromRemote = false)
                    }
                }
            }
        }

    /**
     * The store, built on demand.
     *
     * Not a field initialised in `onCreate`: the constructor resolves
     * `noBackupFilesDir`, and every caller here is already on
     * [historyWorker], so there is nothing to gain by moving that to the main
     * thread and one more thing to tear down.
     */
    private fun historyStore(): DictationHistoryStore = DictationHistoryStore(this)

    override fun onDestroy() {
        busSink?.let { BubbleSessionBus.detach(it) }
        busSink = null
        // Before speech: the session owns the microphone and the recogniser's
        // pipe, and closing the pipe's write end is what ends the recognition
        // session. Tearing the recogniser down first leaves a writer with
        // nowhere to write.
        session?.destroy()
        session = null
        // Releases the microphone if the phone-only recogniser held it: a card
        // torn down mid-dictation must not leave ownership stranded, or every
        // later recording in the process is refused.
        destroyPhoneRecogniser()
        captureWorker?.shutdown()
        captureWorker = null
        // shutdownNow: a re-transcribe in flight is sleeping between retries,
        // and the card it would render into is going away. The executor is
        // non-daemon, so leaving it running leaks a thread per card.
        runCatching { historyWorker.shutdownNow() }
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

    /**
     * Stops any capture this card owns — the upload session and the phone
     * recogniser — and resets the mic button's label.
     *
     * One rule, one place: both callers need "the microphone must not still
     * be open after this", and a second open-coded copy is how the two drift.
     * `stop()` rather than `discard()`: a finalised recording is uploaded, an
     * abandoned one is lost.
     */
    private fun stopAnyLiveCapture() {
        session?.takeIf { it.isRecording }?.let {
            it.stop()
            micButton.text = getString(R.string.start_dictation)
        }
        speech?.takeIf { it.isListening }?.let {
            stopPhoneRecogniser()
            micButton.text = getString(R.string.start_dictation)
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
            stopPhoneRecogniser()
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
        // 🔴 The path this method has to refuse: the bubble is RECORDING and
        // by design nothing is on screen. The user pulls down the shade and
        // taps the QS tile (or the notification's Dictate action, or the
        // launcher icon → "Try dictation now"). All three go through
        // DictateLaunch.intent(), which carries no EXTRA_ATTACH_SESSION — so
        // `singleTop` creates a card, `onCreate` finds no extra,
        // `hasMicPermission()` is true, and a second capture starts against a
        // live one. Same for the attached card's own mic button, which
        // `attachToBubbleSession` leaves fully enabled. Android does not error
        // on either: it feeds one of the two captures exact zeros.
        //
        // 🔴 ONE check, up front, covering BOTH of this method's two
        // microphone openers:
        //
        //  * `startRemoteSession` → `DictationSession` → `AudioRecord`, which
        //    `MicrophoneOwner` would refuse on its own; and
        //  * `startPhoneOnlyDictation` → `SpeechHelper` with no shared source,
        //    where **SpeechRecognizer opens the microphone itself**. It is
        //    reached whenever `settings` is unavailable (no AndroidKeyStore) or
        //    capture failed — i.e. by the error path, which is exactly how a
        //    guard placed only on the remote branch would be walked around.
        //
        // 🔴 That second opener now REGISTERS as well as being checked, with
        // the activity as the token — see `startPhoneOnlyDictation`. Until it
        // did, this check was the only thing on that path, and it was one-way:
        // the card refused when somebody else held the mic, but while the
        // card's own recogniser was listening `MicrophoneOwner.isHeld` was
        // FALSE, so the bubble (or the tile, or the notification) opened an
        // `AudioRecord` straight into a live `SpeechRecognizer` — the exact
        // double capture, with the exact silent symptom. So the two statements
        // this comment has to keep apart are: this check stops a capture from
        // starting HERE, and the acquire below stops one starting ELSEWHERE.
        // Both directions are needed; neither implies the other.
        //
        // Both of this card's tokens are passed, because both are "mine": the
        // session for the remote path, `this` for the phone-only one. Being
        // refused by our own recogniser would mean the mic button could never
        // restart a dictation the card itself owns.
        //
        // Deliberately NOT also branched on inside the remote arm. That was the
        // first version: two guards, each one a complete backstop for the
        // other, so each killed the other's mutant and NEITHER was pinned by
        // any test. One rule, one place, one reachable case per branch below.
        //
        // Check-then-act, but not a race: this and
        // `BubbleService.startRecording()` both run on the main thread.
        if (MicrophoneOwner.isHeldByOtherThan(session, this)) {
            reportMicBusy()
            return
        }
        val config = settings
        if (config != null && startRemoteSession(config)) return
        startPhoneOnlyDictation()
    }

    /** The one place the "another session holds the mic" refusal is rendered. */
    private fun reportMicBusy() {
        statusText.text = getString(R.string.stt_mic_in_use)
        micButton.text = getString(R.string.start_dictation)
        levelMeter.visibility = View.INVISIBLE
    }

    private fun startRemoteSession(config: SttSettings): Boolean {
        // 🔴 The previous session is destroyed only once the NEW one is
        // recording. `destroy()` deletes the audio file and silences the
        // callbacks, so destroying it before knowing the replacement can start
        // threw away a transcript the user was waiting for: on a failed
        // `start()` this method returns false, `startDictation` falls through
        // to the phone-only recogniser, and the in-flight upload of the
        // previous recording is gone — unrecoverably, the bytes with it.
        //
        // There is no acquire race in this order. `MicrophoneOwner.release` is
        // owner-scoped (a `compareAndSet(owner, null)`), so the old session's
        // late `destroy()` cannot take the mic away from the new one, and the
        // old session holds nothing anyway by the time we get here: `stop()`
        // releases as its first statement, and a session that never started
        // either never took the mic (a refused acquire) or released what it
        // took (a capture failure). (The previous rationale here claimed the
        // opposite and was the reason the destroy was moved up; it was unsound.)
        val previous = session
        val started = DictationSession(
            context = this,
            settings = config,
            callbacks = uiCallbacks(),
        )
        return if (started.start()) {
            previous?.destroy()
            session = started
            micButton.text = getString(R.string.stop_dictation)
            levelMeter.visibility = View.VISIBLE
            true
        } else {
            started.destroy()
            false
        }
    }

    /**
     * The original behaviour: the phone's recogniser, opening its own mic.
     *
     * 🔴 Registers with [MicrophoneOwner] for the duration, with the activity
     * as the token. `SpeechRecognizer` is a microphone client like any other —
     * it just is not one this process can see — so while it listens, ownership
     * has to read as taken or a `DictationSession` started from the bubble, the
     * QS tile or the notification opens an `AudioRecord` against it. Android
     * does not refuse that: it feeds one of the two bit-exact zeros, and the
     * user sees an empty or invented transcript.
     */
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
                // The recogniser closed the microphone on its own; ownership
                // must end with it, not with the activity.
                releasePhoneMic()
                appendFinal(text)
                showEngine(fromRemote = false)
                micButton.text = getString(R.string.start_dictation)
                statusText.text = getString(R.string.dictate_hint)
            }

            override fun onError(message: String) {
                releasePhoneMic()
                lastPartial = ""
                renderPreview()
                micButton.text = getString(R.string.start_dictation)
                statusText.text = message
            }
        })
        if (!helper.isAvailable) {
            // Nothing was opened, so nothing is acquired here: the acquire
            // belongs to the line that actually starts listening, below.
            statusText.text = getString(R.string.no_recognizer)
            return
        }
        // 🔴 Before the acquire, not after: `destroyPhoneRecogniser()` calls
        // `releasePhoneMic()` **unconditionally**, and that release is scoped to
        // the activity's own token — the same token the acquire below takes. So
        // reversed, the teardown would release the token the acquire had just
        // taken, and `helper.start()` would leave the recogniser **listening
        // while MicrophoneOwner says the mic is free** — which lets a
        // concurrent DictationSession open a second capture, and Android
        // answers two captures by feeding one of them exact zeros.
        //
        // 🔴 MEASURED, not reasoned: mutant M20 reverses exactly these two
        // statements and `MicOpenerSeamTest`
        // .aPhoneOnlyDictationHoldsTheMicrophoneSoAConcurrentSessionIsRefused
        // goes red — its positive control still shows the recogniser listening,
        // and the intruder session's `start()` then *succeeds*. That is this
        // hazard, observed.
        //
        // ⚠ An audit asked for this comment to be rewritten on the grounds that
        // the hazard "was the behaviour of the re-entrant `acquire` that
        // 60a363c removed", and that reversed the order would instead refuse
        // itself (`holder === this` ⇒ `compareAndSet` false ⇒ `reportMicBusy`).
        // **That is wrong and the rewrite was reverted.** The mechanism named
        // here is release-on-teardown, not re-entrancy, and it needs none: on a
        // start with no previous recogniser the holder is `null`, so the
        // reversed acquire succeeds on a plain compare-and-set and the teardown
        // then frees it. The self-refusal the audit describes is a real but
        // *second* consequence, reachable only when a previous recogniser is
        // still live; it is not what the suite catches, and it is not why the
        // order is this way round.
        destroyPhoneRecogniser()
        if (!MicrophoneOwner.acquire(this)) {
            // Only reachable if something took the mic between
            // `startDictation`'s check and here. Same refusal, one renderer.
            reportMicBusy()
            return
        }
        speech = helper
        helper.start()
        micButton.text = getString(R.string.stop_dictation)
    }

    /**
     * Gives the microphone back if this card's phone-only recogniser holds it.
     *
     * The one place the phone-only release is spelled. Owner-scoped, so it is a
     * no-op when the holder is the card's [DictationSession] or the bubble's —
     * which is what makes it safe to call from every teardown route rather than
     * only from the ones that can prove the recogniser was listening.
     */
    private fun releasePhoneMic() {
        MicrophoneOwner.release(this)
    }

    /** Stops the phone-only recogniser, keeping its final result. */
    private fun stopPhoneRecogniser() {
        speech?.stop()
        releasePhoneMic()
    }

    /** Tears the phone-only recogniser down, abandoning any result. */
    private fun destroyPhoneRecogniser() {
        speech?.destroy()
        speech = null
        releasePhoneMic()
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

        /** The card revision this instance was built for. See [stale]. */
        private val bornAt = cardGeneration

        /**
         * Whether this callbacks object belongs to a card revision that has
         * since been replaced.
         *
         * **Six call sites, one predicate** — written out in each override
         * because the interface offers no single choke point.
         * [SessionCallbacksGateTest] is what keeps the set honest; without a
         * ledger a seventh method would be ungated silently, which round 3 of
         * #17 demonstrated by deleting the gate from `onClientSilenced` and
         * watching all 568 tests stay green.
         *
         * 🔴 Keyed to a GENERATION, not to "is this a history card". Round 2
         * of #17 used the latter and round 3 measured the cost: attach →
         * history → start another bubble dictation left the card muted **for
         * the rest of its life**, because the attach intent carries no
         * history id, `onNewIntent` returns early by design, and nothing ever
         * cleared the flag. `micButton` is GONE on a history card, so the
         * only recovery was closing it.
         *
         * A generation cannot wedge that way: [attachToBubbleSession] and
         * [startDictation] build a NEW callbacks object, which is born
         * current by construction. Same shape as `BubbleService.generation`,
         * which `MicOpenerSeamTest` already names as the thing this class
         * lacked.
         */
        private fun stale(): Boolean = bornAt != cardGeneration

        override fun onTranscript(text: String, fromRemote: Boolean, isFinal: Boolean) {
            if (stale()) return
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
            if (stale()) return
            statusText.text = text
        }

        override fun onLevel(level: Double) {
            if (stale()) return
            levelMeter.progress = (level * 100).toInt().coerceIn(0, 100)
        }

        override fun onTier(tier: AudioCaptureLogic.Tier) {
            if (stale()) return
            // The meter matters most in the remote-only tiers, where no
            // partial text ever appears.
            levelMeter.visibility = View.VISIBLE
        }

        override fun onClientSilenced(silenced: Boolean) {
            if (stale()) return
            // Surfaced as a real state rather than logged: the user can act
            // on "another app has the microphone", and cannot act on a dead
            // level meter.
            levelMeter.visibility = if (silenced) View.INVISIBLE else View.VISIBLE
        }

        override fun onRecordingStopped() {
            if (stale()) return
            micButton.text = getString(R.string.start_dictation)
            levelMeter.progress = 0
        }
    }

    /**
     * Names the engine behind the text currently in the box.
     *
     * 🔴 **Deliberately flag-blind, and this is not the second copy of
     * [RemoteTranscriber.statusLabel]'s predicate it resembles.** The badge
     * answers one question — *which engine produced this text* — and no value
     * of `truncated` or `unreliable` changes that answer. The caveats go on
     * the status line, which is a full-width sentence; this is an 11sp pill
     * that cannot carry one, and duplicating the warning in two places on one
     * screen would make both easier to ignore.
     *
     * 🔴 It nevertheless **shares** `stt_remote_done` with the status line's
     * plain case, which is safe for exactly one reason: that string is pinned
     * as a whole, normalised string by
     * `RemoteTranscriberTest.theRemoteDoneStatusMakesNoVerificationClaim`. If
     * anyone makes it case-specific — the concrete trigger an audit named — that
     * test goes red and this call site has to be revisited rather than quietly
     * inheriting a sentence the badge has no room for and no basis to claim.
     * `OverlayMinimalLayoutTest` additionally pins that the badge is never
     * either warning string.
     */
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
        stopAnyLiveCapture()
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
