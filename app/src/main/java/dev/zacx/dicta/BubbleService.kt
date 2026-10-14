package dev.zacx.dicta

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import kotlin.math.abs

/**
 * Hosts the floating mic bubble — and, since the inline-dictation redesign,
 * **owns the microphone session**.
 *
 * ## The flow
 *
 * Tap 1 starts recording with **no overlay at all**: the bubble pulses with the
 * live level and a cancel button appears next to it. Tap 2 stops capture,
 * submits the recording, and opens [DictateActivity] already in its
 * "transcribing…" state, where the transcript fills in when it arrives. Cancel
 * aborts outright — nothing uploaded, no card.
 *
 * Opening the UI and starting capture used to be the same event (tap → open the
 * card → `onCreate` starts recording). They are now separate, which is the
 * whole change, and it moves three things into this class:
 *
 *  1. **The session.** [DictationSession] takes a plain `Context`, so a Service
 *     can own one. [DictationSessionLedgerTest] is the ledger of who may.
 *  2. **The FGS type.** The manifest declares `microphone|specialUse` and this
 *     service promotes to the *specific* type while recording and demotes after
 *     — see [promoteForeground] for why the simpler "just make it microphone"
 *     is wrong.
 *  3. **The decisions.** All of them are in [BubbleDictation], which has no
 *     Android types in it and is therefore the only part of this that can be
 *     tested. What is left here is window handling, animation and platform
 *     calls: device evidence only.
 *
 * ## One window, not two
 *
 * The bubble and the cancel button share a single [WindowManager] view. 🔴 Not
 * tidiness: [OverlayVisibility] hides Dicta's own overlay for the duration of a
 * MediaProjection capture by zeroing `params.alpha` on **one** `params`, and a
 * second window would be a second thing to remember to hide. Missing one
 * composites Dicta's own UI into every screenshot the user takes, which this
 * app has already shipped once.
 */
class BubbleService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams

    /**
     * The tappable bubble.
     *
     * Kept under this name because [BubbleTapOpensOverlayTest] reaches it by
     * reflection — that is what lets the "tap 1 opens no overlay" guard compile
     * against the pre-change tree as well as this one, so its red-then-green is
     * evidence about the behaviour rather than about the API.
     */
    private var bubble: View? = null

    /** The single window's root: bubble above (or below) the cancel button. */
    private var root: LinearLayout? = null
    private var ring: View? = null
    private var cancel: ImageButton? = null

    private val main = Handler(Looper.getMainLooper())
    private val machine = BubbleDictation()
    private var session: SessionHandle? = null

    /**
     * The handle whose upload is still in flight, kept only so it can be
     * torn down.
     *
     * 🔴 `DictationSession` owns a non-daemon single-thread executor and
     * shuts it down only in `destroy()`. `DictateActivity` called that from
     * `onDestroy`; this service cannot, because the whole point of the
     * redesign is that the session OUTLIVES the tap that submitted it — the
     * upload is still running when the card opens. Dropping the reference at
     * submit time therefore leaked one live thread per recording, forever.
     *
     * Bounded at one: the previous submission is destroyed when the next
     * recording starts (by which point its ladder has either finished or is
     * no longer wanted) and on service destroy.
     */
    private var submitted: SessionHandle? = null

    /**
     * 🔴 Which recording the callbacks belong to.
     *
     * `serviceCallbacks()` used to carry no notion of identity, and
     * `DictationSession` has no post-`destroy()` gate of its own that the
     * *service* can see — so a dead session's late callback drove the **live**
     * state machine. The sequence: record #1 → stop (TRANSCRIBING, upload in
     * flight) → wait past the grace window → tap (the documented wedge
     * recovery) → `startRecording()` destroys #1 and starts #2 → #1's late
     * transcript lands → `TranscriptSettled` → `toIdle()`.
     *
     * The machine is then IDLE while `session` is non-null **and the microphone
     * is open**, and every observable is wrong at once: `renderState()`
     * announces `bubble_idle`, hides the pulse ring and hides the cancel
     * button (recording with no indicator and no way to abort); the FGS is
     * never demoted; `onMaxDuration()` returns `emptyList()` in IDLE so the
     * two-minute cap is **inert and the recording never stops**; and the next
     * tap overwrites `session` without destroying the old one — two live
     * `AudioRecord`s plus a leaked non-daemon executor.
     *
     * Incremented in [startRecording]; each callbacks object captures the value
     * it was built with and ignores everything once it is stale. The *submitted*
     * session deliberately keeps its generation — its transcript is the one the
     * open card is waiting for — and only loses it when a new recording starts.
     */
    private var generation = 0

    private var bubbleSizePx = 0
    private var haloSizePx = 0
    private var cancelBlockPx = 0

    /**
     * Swappable session construction.
     *
     * The real one needs `SttSettings` (an Android keystore) and `AudioRecord`,
     * neither of which exists under Robolectric, so without a seam here the
     * only testable tap is the first one — the service would fail to start
     * capture, fall back to idle, and a "second tap" would be indistinguishable
     * from another first tap. `internal` and set before `onCreate`.
     *
     * 🔴 The **return** type is nullable as well as the field, and the two
     * nulls mean different things. A null *field* means "use [realSession]". A
     * null *return* stands in for [realSession] itself returning null — the
     * no-settings case — which is otherwise unreachable under Robolectric,
     * since it needs a keystore that refuses. That is a seam for the
     * *classification*, exactly like [foregroundPromoter]: it makes
     * "no session ⇒ `SETTINGS_UNAVAILABLE`" observable. It does **not**
     * reproduce a keystore failure, and no test here is evidence that
     * `SttSettings.create` throws on any real device.
     */
    internal var sessionFactory: ((DictationSession.Callbacks) -> SessionHandle?)? = null

    /**
     * Swappable foreground promotion.
     *
     * The one failure the platform cannot be made to produce here: Robolectric's
     * `startForeground` records the call and never refuses, so without this seam
     * `Failure.FOREGROUND_DENIED` is unreachable from a test and the message it
     * maps to is pinned by nothing — which is exactly how all three causes came
     * to share one wrong string. `internal` and set before `onCreate`, like
     * [sessionFactory]; the argument is the `recording` flag, so a test can
     * refuse the microphone promotion while letting the at-rest one through.
     */
    internal var foregroundPromoter: ((Boolean) -> Boolean)? = null

    /**
     * What the service needs from a recording, and nothing more.
     *
     * [submit] and [discard] are separate operations rather than
     * `stop(upload: Boolean)` because the difference between them is the one
     * thing the cancel button exists to decide, and a boolean argument at a
     * call site is easy to get backwards.
     */
    internal interface SessionHandle {
        fun start(): Boolean

        /** Stop capture; the finished recording **is** uploaded. */
        fun submit()

        /** Stop capture; the finished recording is **not** uploaded. */
        fun discard()

        fun destroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        if (!promoteForeground(recording = false)) {
            stopSelf()
            return
        }
        wm = getSystemService(WindowManager::class.java)
        buildWindow()
        OverlayVisibility.register(visibilitySink)
    }

    // --- the window ---------------------------------------------------------

    private fun buildWindow() {
        val density = resources.displayMetrics.density
        bubbleSizePx = (BUBBLE_DP * density).toInt()
        haloSizePx = (HALO_DP * density).toInt()
        cancelBlockPx = ((CANCEL_DP + CANCEL_GAP_DP) * density).toInt()

        params = WindowManager.LayoutParams(
            // 🔴 WRAP_CONTENT, not the old fixed 60 dp square. The window has
            // to grow when the cancel button appears, and it must SHRINK back:
            // a permanently tall window keeps a transparent strip of itself
            // under the bubble, and an overlay window consumes every touch
            // inside its bounds whether or not a view there handles it. That
            // strip would silently eat taps meant for the app underneath.
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 0
            y = (220 * density).toInt()
        }

        // The ring grows past the bubble's rim, so it needs room inside the
        // window or the pulse is clipped to a square.
        val halo = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(haloSizePx, haloSizePx)
        }
        val pulse = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(bubbleSizePx, bubbleSizePx).apply {
                gravity = Gravity.CENTER
            }
            background = getDrawable(R.drawable.bubble_pulse)
            visibility = View.GONE
            // Decorative: the state it indicates is announced by the bubble's
            // own contentDescription, so a second announcement would be noise.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val button = ImageButton(this).apply {
            layoutParams = FrameLayout.LayoutParams(bubbleSizePx, bubbleSizePx).apply {
                gravity = Gravity.CENTER
            }
            setImageResource(R.drawable.ic_mic)
            // ic_mic ships a flat white fill, because it doubles as the QS tile
            // icon where the platform requires solid white. Tint it to the
            // signature accent so the bubble carries Dicta's identity over an
            // arbitrary wallpaper. bubble_bg.xml documents why the pressed
            // state lifts instead of inverting to an accent fill, which this
            // tint would disappear into.
            imageTintList = android.content.res.ColorStateList
                .valueOf(getColor(R.color.dicta_accent))
            background = getDrawable(R.drawable.bubble_bg)
            contentDescription = getString(BubbleChrome.labelFor(machine.state))
            setOnClickListener { tapped() }
            setOnTouchListener(DragListener())
        }
        halo.addView(pulse)
        halo.addView(button)

        val abort = ImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (CANCEL_DP * resources.displayMetrics.density).toInt(),
                (CANCEL_DP * resources.displayMetrics.density).toInt(),
            ).apply { topMargin = (CANCEL_GAP_DP * density).toInt() }
            setImageResource(R.drawable.ic_close)
            imageTintList = android.content.res.ColorStateList
                .valueOf(getColor(R.color.dicta_on_surface))
            background = getDrawable(R.drawable.bubble_bg)
            val label = getString(R.string.bubble_cancel)
            contentDescription = label
            tooltipText = label
            visibility = View.GONE
            // No DragListener. The bubble is the drag handle; a drag that
            // started on this button would be a drag that cancels the
            // recording when the finger lifts, which is not what a drag means.
            setOnClickListener {
                applyEffects(machine.on(BubbleDictation.Event.CancelTapped))
            }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(halo)
            addView(abort)
        }

        wm.addView(container, params)
        root = container
        ring = pulse
        cancel = abort
        bubble = button
    }

    /**
     * The bubble is an always-on-top overlay, so MediaProjection composites it
     * into any screenshot. [DictateActivity] hides it for the duration of a
     * capture through [OverlayVisibility].
     */
    private val visibilitySink: (Boolean) -> Unit = { visible ->
        root?.post {
            val view = root
            if (view != null) {
                view.visibility = if (visible) View.VISIBLE else View.INVISIBLE
                // Belt and braces: INVISIBLE already makes the view draw
                // nothing into a TRANSLUCENT window, but zeroing the window
                // alpha removes it from composition outright.
                params.alpha = if (visible) 1f else 0f
                runCatching { wm.updateViewLayout(view, params) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        OverlayVisibility.unregister(visibilitySink)
        main.removeCallbacksAndMessages(null)
        // Discard rather than submit: the service going away is not the user
        // asking for a transcript, and an upload whose card can never open
        // would be a silent one.
        session?.let { runCatching { it.discard() }; runCatching { it.destroy() } }
        session = null
        submitted?.let { runCatching { it.destroy() } }
        submitted = null
        BubbleSessionBus.reset()
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        bubble = null
        ring = null
        cancel = null
        super.onDestroy()
    }

    // --- the state machine --------------------------------------------------

    private fun tapped() {
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        applyEffects(machine.on(BubbleDictation.Event.BubbleTapped(granted)))
    }

    /** Carries out the machine's decisions. Exhaustive `when` on purpose. */
    private fun applyEffects(effects: List<BubbleDictation.Effect>) {
        for (effect in effects) {
            when (effect) {
                BubbleDictation.Effect.StartRecording -> startRecording()
                BubbleDictation.Effect.RequestMicPermission -> requestMicPermission()
                BubbleDictation.Effect.SubmitRecording -> stopRecording(submit = true)
                BubbleDictation.Effect.DiscardRecording -> stopRecording(submit = false)
                BubbleDictation.Effect.OpenOverlayTranscribing -> openCard()
                BubbleDictation.Effect.ReportMaxDuration ->
                    toast(R.string.stt_max_duration)
                // 🔴 The one effect whose absence WAS the bug: see
                // BubbleDictation.Effect.ReportCaptureFailed. A tap that
                // silently does nothing is indistinguishable from a dead app.
                // The cause decides the message — one string for all three
                // named the wrong cause on two of them.
                is BubbleDictation.Effect.ReportCaptureFailed ->
                    toast(messageFor(effect.failure))
                // Nothing on screen, deliberately: the user's second tap landed
                // inside the debounce window, which means they did not mean to
                // make two. A toast here would announce their own slip.
                BubbleDictation.Effect.IgnoredAsTooFast -> Unit
            }
        }
        // 🔴 `state == IDLE ⇒ session == null` is relied on by startRecording()
        // and was enforced nowhere. Enforced here rather than asserted in a
        // comment, because the consequence of it not holding is an open
        // microphone with no indicator: a session still alive in IDLE is one
        // nothing on screen refers to and nothing can stop.
        if (machine.state == BubbleDictation.State.IDLE) reclaimSession()
        renderState()
    }

    /**
     * The one mapping from a cause to what the user is told.
     *
     * 🔴 Exhaustive `when` over [BubbleDictation.Failure] on purpose: a new
     * cause then cannot compile until somebody decides what it says. The
     * previous version toasted `bubble_capture_failed` — "the microphone may be
     * in use by a call or another app" — for every cause, so a user whose own
     * bubble held the mic was told another app was at fault and given nothing
     * to act on, and a refused `startForeground` was reported as a microphone
     * problem. `stt_mic_in_use` already existed, with the accurate copy, and
     * was never reached from here.
     *
     * The alternative considered and rejected: have `applyEffects` prefer the
     * last [BubbleSessionBus] status. The bus is reset at the start of every
     * recording and nothing renders it while no card is open — which is the
     * defect `ReportCaptureFailed` exists to fix — so that would make the
     * message depend on whether a card happened to be on screen.
     */
    private fun messageFor(failure: BubbleDictation.Failure): Int = when (failure) {
        BubbleDictation.Failure.MIC_IN_USE -> R.string.stt_mic_in_use
        BubbleDictation.Failure.FOREGROUND_DENIED -> R.string.bubble_foreground_denied
        BubbleDictation.Failure.CAPTURE_UNAVAILABLE -> R.string.bubble_capture_failed
        BubbleDictation.Failure.SETTINGS_UNAVAILABLE ->
            R.string.bubble_settings_unavailable
    }

    /**
     * Tears down [session] if there is one, and clears it.
     *
     * Discard, not submit: a session being reclaimed is one no state on screen
     * refers to any more, so uploading it would produce a transcript for a card
     * that will never open.
     */
    private fun reclaimSession() {
        val handle = session ?: return
        session = null
        main.removeCallbacks(maxDurationGuard)
        runCatching { handle.discard() }
        runCatching { handle.destroy() }
        BubbleSessionBus.reset()
        promoteForeground(recording = false)
    }

    private fun startRecording() {
        // 🔴 Unconditionally reclaim any session still assigned. This used to
        // be absent, and `session = handle` below simply overwrote it: a
        // reference lost while its `AudioRecord` was still open, so the next
        // recording ran with TWO live captures — and Android resolves that by
        // feeding one of them exact zeros, with no error anywhere. The
        // `state == IDLE ⇒ session == null` invariant is supposed to make this
        // unreachable; doing it anyway is what makes a violation harmless
        // instead of silent.
        reclaimSession()
        // 🔴 Promote BEFORE opening the microphone. An FGS that is capturing
        // without the microphone type is the violation; doing it in this order
        // means a refused promotion costs us the recording rather than the
        // process.
        if (!promoteForeground(recording = true)) {
            applyEffects(
                machine.on(
                    BubbleDictation.Event.CaptureFailed(
                        BubbleDictation.Failure.FOREGROUND_DENIED
                    )
                )
            )
            return
        }
        // Reclaim the previous recording's executor before opening another.
        submitted?.let { runCatching { it.destroy() } }
        submitted = null
        BubbleSessionBus.reset()
        // Everything the previous recording's callbacks could still say is now
        // stale — see `generation`.
        generation++
        // 🔴 `if (factory != null)` rather than `?: realSession()`: the factory
        // is allowed to return null — that is how a test reaches the branch
        // below — and an elvis would silently fall through to the real session
        // instead, which under Robolectric means a keystore that is not there.
        val factory = sessionFactory
        val handle =
            if (factory != null) factory(serviceCallbacks()) else realSession()
        // 🔴 No session at all is its OWN cause, decided structurally rather
        // than by asking MicrophoneOwner. `realSession()` returns null for
        // exactly one reason — `SttSettings.create()` threw, i.e. no usable
        // keystore — and that reason never touches the microphone, so routing
        // it through the `isHeld` discriminator below classified it
        // CAPTURE_UNAVAILABLE and told the user the device "would not open the
        // microphone" and to "try again". Both false. The mic was never
        // opened; the keystore will refuse every retry.
        if (handle == null) {
            promoteForeground(recording = false)
            applyEffects(
                machine.on(
                    BubbleDictation.Event.CaptureFailed(
                        BubbleDictation.Failure.SETTINGS_UNAVAILABLE
                    )
                )
            )
            return
        }
        if (!handle.start()) {
            runCatching { handle.destroy() }
            promoteForeground(recording = false)
            // 🔴 Which of the two failures this was, decided from the state
            // that distinguishes them rather than from a returned enum nothing
            // branched on. Our own session cannot be the holder by the time we
            // get here: a refused acquire never took the mic, a capture failure
            // releases it, and the `destroy()` above releases it again. So a mic
            // still held HERE is held by somebody else — the card's session or
            // its phone-only recogniser — which is exactly MIC_IN_USE. The other
            // causes (no encoder, an AudioRecord that would not initialise)
            // take nothing, so a free mic means one of those. The no-settings
            // cause cannot arrive here at all: it produces no handle, and is
            // classified SETTINGS_UNAVAILABLE above.
            // If one of those coincides with another opener holding the mic,
            // MIC_IN_USE wins: both statements are true and that is the one the
            // user can act on.
            val failure = if (MicrophoneOwner.isHeld) {
                BubbleDictation.Failure.MIC_IN_USE
            } else {
                BubbleDictation.Failure.CAPTURE_UNAVAILABLE
            }
            applyEffects(machine.on(BubbleDictation.Event.CaptureFailed(failure)))
            return
        }
        session = handle
        main.postDelayed(maxDurationGuard, BubbleDictation.MAX_RECORDING_MS)
    }

    private val maxDurationGuard = Runnable {
        applyEffects(machine.on(BubbleDictation.Event.MaxDurationReached))
    }

    private fun stopRecording(submit: Boolean) {
        main.removeCallbacks(maxDurationGuard)
        val handle = session
        session = null
        if (submit) {
            handle?.submit()
            // Held, not dropped: the upload is still running. See `submitted`.
            submitted = handle
        } else {
            // 🔴 Discard means the bytes are never uploaded AND the buffered
            // status lines are dropped: replaying a cancelled recording's
            // "Transcribing on the server…" into the next card the user opens
            // would be a lie about a recording that no longer exists.
            handle?.discard()
            runCatching { handle?.destroy() }
            BubbleSessionBus.reset()
        }
        promoteForeground(recording = false)
    }

    /** A Service cannot `requestPermissions`; the card can. */
    private fun requestMicPermission() {
        // 🔴 Via DictateLaunch, never a hand-built Intent: the overlay must
        // land in its OWN task. Built here with NEW_TASK alone it joined
        // MainActivity's task, and because the overlay theme is translucent the
        // card rendered over the whole app UI rather than over the screen the
        // user was actually on (issue #11).
        //
        // This is also the mic-denied fallback path in full: DictateActivity
        // asks for RECORD_AUDIO and already carries the copy for a refusal.
        runCatching { startActivity(DictateLaunch.intent(this)) }
    }

    private fun openCard() {
        runCatching { startActivity(DictateLaunch.transcribingIntent(this)) }
    }

    // --- what the session says back -----------------------------------------

    /**
     * The service's own view of the session, layered under
     * [BubbleSessionBus.sink] so the card sees everything too.
     *
     * [DictationSession.Callbacks.onLevel] feeds two things and no more: the
     * card, through the bus, and the bubble's own pulse. It deliberately does
     * NOT reach [BubbleDictation] — the machine makes no decision from a level,
     * so routing samples (tens per second) into it would be pure churn.
     */
    private fun serviceCallbacks(): DictationSession.Callbacks =
        object : DictationSession.Callbacks {
            private val bus = BubbleSessionBus.sink

            /**
             * The recording this object belongs to, captured at construction.
             * [startRecording] bumps [generation] before building these, so
             * everything a previous recording still has to say is stale — and
             * stale events are **dropped**, not applied. See [generation].
             */
            private val mine = generation

            private fun isCurrent(): Boolean = mine == generation

            override fun onTranscript(text: String, fromRemote: Boolean, isFinal: Boolean) {
                // 🔴 Dropped, not forwarded: a dead session's late transcript
                // reaching the bus would also overwrite the card belonging to
                // the recording that replaced it.
                if (!isCurrent()) return
                bus.onTranscript(text, fromRemote, isFinal)
                // 🔴 Any FINAL transcript settles the bubble, not only a remote
                // one. The fallback ladder hands over the PHONE's transcript
                // when the server cannot be reached, and gating on `fromRemote`
                // left the bubble stuck in TRANSCRIBING for the whole of an
                // outage. (The remaining no-transcript-at-all path — server down
                // AND no local text — is covered by the grace window in
                // BubbleDictation, because no callback arrives to cover it.)
                if (isFinal) {
                    main.post { applyEffects(machine.on(BubbleDictation.Event.TranscriptSettled)) }
                }
            }

            override fun onStatus(text: String) {
                if (!isCurrent()) return
                bus.onStatus(text)
            }

            override fun onLevel(level: Double) {
                // A dead session's level would animate the live recording's
                // pulse ring with audio from a recording that is over.
                if (!isCurrent()) return
                bus.onLevel(level)
                renderPulse(level)
            }

            override fun onTier(tier: AudioCaptureLogic.Tier) {
                if (!isCurrent()) return
                bus.onTier(tier)
            }

            override fun onClientSilenced(silenced: Boolean) {
                if (!isCurrent()) return
                bus.onClientSilenced(silenced)
            }

            override fun onRecordingStopped() {
                if (!isCurrent()) return
                bus.onRecordingStopped()
            }
        }

    /**
     * The production session, or **null for exactly one reason**: the settings
     * store could not be built.
     *
     * 🔴 That single null path is load-bearing — `startRecording` reads a null
     * handle as `Failure.SETTINGS_UNAVAILABLE` without asking why. A second
     * null path added here would silently be reported as a keystore problem,
     * so return a handle whose `start()` fails instead.
     *
     * ⚠ **This refusal is a known behaviour gap, not the intended end state.**
     * The comment here used to read *"Same degradation the card documents …
     * It is NOT a reason to refuse to record"* — and then refused to record,
     * one line later, with `?: return null`. The card really does degrade on a
     * keystore-less device (no settings ⇒ the remote path reports "not
     * configured" ⇒ the phone's own recogniser is used); the bubble does not.
     * Making it degrade the same way means giving the bubble a phone-only
     * capture path, which is a larger change and deliberately out of scope
     * here. What was fixed is the *message*: the user is no longer told the
     * microphone failed and to retry. See
     * [BubbleDictation.Failure.SETTINGS_UNAVAILABLE].
     */
    private fun realSession(): SessionHandle? {
        val settings = try {
            SttSettings.create(this)
        } catch (_: Throwable) {
            return null
        }
        val real = DictationSession(
            context = this,
            settings = settings,
            callbacks = serviceCallbacks(),
        )
        return object : SessionHandle {
            // 🔴 Both failure modes — the microphone already held, and capture
            // unable to start — are "no recording" here, and both must reach
            // the user: either way the machine goes back to IDLE via
            // Event.CaptureFailed, and `startRecording` tells the two apart
            // from MicrophoneOwner so each gets its own message. (It used to
            // say "the session has already put the specific reason on the bus"
            // — true, and useless: nothing renders the bus while no card is
            // open, which is the whole case this report exists for.)
            override fun start(): Boolean = real.start()

            override fun submit() = real.stop()

            override fun discard() {
                real.suppressUpload()
                real.stop()
            }

            override fun destroy() = real.destroy()
        }
    }

    // --- rendering (device evidence only) -----------------------------------

    private fun renderState() {
        val recording = machine.state == BubbleDictation.State.RECORDING
        bubble?.contentDescription = getString(BubbleChrome.labelFor(machine.state))
        ring?.visibility = if (recording) View.VISIBLE else View.GONE
        if (!recording) renderPulse(0.0)
        showCancel(recording)
    }

    /**
     * Shows or hides the cancel button, flipping it above the bubble when there
     * is no room below — see [BubbleChrome.cancelFitsBelow] for why the simpler
     * "always below" was rejected.
     *
     * The `y` adjustment is what keeps the *bubble* still while the window
     * grows. With `Gravity.TOP` the window's top edge is pinned at `y`, so
     * adding a child above the bubble would otherwise shove the bubble down by
     * the button's height at the moment recording starts.
     */
    private fun showCancel(show: Boolean) {
        val container = root ?: return
        val abort = cancel ?: return
        if ((abort.visibility == View.VISIBLE) == show) return

        if (show) {
            val below = BubbleChrome.cancelFitsBelow(
                bubbleTopPx = params.y,
                bubbleHeightPx = haloSizePx,
                blockHeightPx = cancelBlockPx,
                screenHeightPx = resources.displayMetrics.heightPixels,
            )
            val wanted = if (below) container.childCount - 1 else 0
            if (container.indexOfChild(abort) != wanted) {
                container.removeView(abort)
                container.addView(abort, wanted)
            }
            abort.visibility = View.VISIBLE
            if (!below) params.y -= cancelBlockPx
        } else {
            val wasAbove = container.indexOfChild(abort) == 0
            abort.visibility = View.GONE
            if (wasAbove) params.y += cancelBlockPx
        }
        runCatching { wm.updateViewLayout(container, params) }
    }

    /** The ring, scaled and faded from the live level. */
    private fun renderPulse(level: Double) {
        val view = ring ?: return
        val clamped = level.coerceIn(0.0, 1.0).toFloat()
        view.scaleX = 1f + clamped * PULSE_SCALE
        view.scaleY = 1f + clamped * PULSE_SCALE
        view.alpha = PULSE_ALPHA_FLOOR + clamped * (1f - PULSE_ALPHA_FLOOR)
    }

    private fun toast(message: Int) {
        runCatching { Toast.makeText(this, getString(message), Toast.LENGTH_LONG).show() }
    }

    // --- the foreground service ---------------------------------------------

    /**
     * Promotes to the `microphone` FGS type while recording and back to
     * `specialUse` at rest.
     *
     * 🔴 Why not simply declare `microphone` and be done with it: Android 14
     * throws `SecurityException` out of `startForeground` when a
     * `microphone`-typed FGS is promoted without `RECORD_AUDIO`, and this
     * service **must** keep running when the microphone is denied — that is
     * exactly the state in which a tap falls back to opening
     * [DictateActivity], the only place an app can call `requestPermissions`
     * from. A microphone-only type would kill the bubble for precisely the
     * users who have not granted the mic. So the manifest declares the union
     * and this picks one, which [MicrophoneFgsManifestTest] pins.
     *
     * @return false when the platform refused the promotion, which the caller
     *   turns into a failed recording rather than a dead service.
     */
    private fun promoteForeground(recording: Boolean): Boolean =
        foregroundPromoter?.invoke(recording) ?: platformPromoteForeground(recording)

    private fun platformPromoteForeground(recording: Boolean): Boolean = runCatching {
        val notif = buildNotification(
            if (recording) R.string.bubble_recording else R.string.bubble_active
        )
        when {
            recording && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                startForeground(
                    NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )

            // FOREGROUND_SERVICE_TYPE_SPECIAL_USE is API 34; below that a
            // specialUse service has no type constant to pass and none is
            // required, so the two-argument call IS the correct one.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                startForeground(
                    NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )

            else -> startForeground(NOTIF_ID, notif)
        }
    }.isSuccess

    private fun buildNotification(textRes: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.bubble_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val stopIntent = Intent(this, BubbleService::class.java)
            .setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_mic)
            // The one styling hook a platform notification offers: the shade
            // tints the small icon and the app name with this. Without it
            // Dicta's notifications render in the system's default accent and
            // are the one surface that does not look like the app.
            .setColor(getColor(R.color.dicta_accent))
            .setColorized(false)
            .setContentTitle(getString(R.string.app_name))
            // Tracks the state: while recording this notification is the only
            // place outside the bubble itself that says the mic is open.
            .setContentText(getString(textRes))
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.hide_bubble),
                    stopPi
                ).build()
            )
            .build()
    }

    /** Distinguishes a tap (start/stop dictation) from a drag (reposition). */
    private inner class DragListener : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var downX = 0
        private var downY = 0
        private var moved = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val slop = (12 * v.resources.displayMetrics.density).toInt()
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downX = params.x
                    downY = params.y
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) + abs(dy) > slop) {
                        moved = true
                        params.x = downX - dx // END gravity: x grows leftward
                        params.y = downY + dy
                        (root as? ViewGroup)?.let { wm.updateViewLayout(it, params) }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) v.performClick()
                    return true
                }
            }
            return false
        }
    }

    companion object {
        const val ACTION_STOP = "dev.zacx.dicta.STOP_BUBBLE"
        private const val NOTIF_ID = 2001
        private const val CHANNEL_ID = "dicta_service"

        /** The bubble's own diameter, unchanged by the redesign. */
        private const val BUBBLE_DP = 60

        /**
         * The box the bubble sits in. Larger than the bubble so the pulse ring
         * has somewhere to grow — scaled past the window's bounds it would be
         * clipped to a rectangle, which looks like a rendering bug rather than
         * a ring.
         */
        private const val HALO_DP = 84

        private const val CANCEL_DP = 44
        private const val CANCEL_GAP_DP = 6

        /** The ring grows to 1.4× at full level. */
        private const val PULSE_SCALE = 0.4f

        /**
         * The ring stays faintly visible at zero level: it is the "the mic is
         * open" indicator, and one that vanishes during a pause would read as
         * the recording having stopped.
         */
        private const val PULSE_ALPHA_FLOOR = 0.35f
    }
}
