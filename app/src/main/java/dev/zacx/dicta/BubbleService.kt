package dev.zacx.dicta

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
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
 * ## Two windows, and one sink
 *
 * 🔴 **CORRECTION.** This section read *"One window, not two"* and said that
 * the bubble and the cancel button share a single [WindowManager] view, which
 * they do — but it then generalised to the file, and that is no longer true:
 * the unreachable-server warning ([warning]) is a second
 * `TYPE_APPLICATION_OVERLAY` window added by this same class. The *hazard* the
 * old wording existed for is unchanged and is the reason the correction is
 * worth more than a deletion: [OverlayVisibility] takes Dicta's overlays off
 * the screen for the duration of a MediaProjection capture, and an overlay
 * window nobody remembers to hide is composited into every screenshot the user
 * takes — which this app has already shipped once (PR #4).
 *
 * What replaces "one window" as the actual invariant is **one sink**: the
 * single `visibilitySink` registered in [onCreate] drives *both* windows from
 * one boolean, so they cannot disagree about whether Dicta is on screen, and
 * adding a window without adding it to that sink is what
 * [OverlayWindowLedgerTest] fails on — it counts `addView` calls **per file**
 * rather than listing files, precisely because a file-name set stays green when
 * an existing owner opens a second window.
 *
 * The cancel button and the history row are still children of the bubble's
 * window rather than windows of their own, for the reason each of their fields
 * gives. The warning dialog cannot be: see [warning].
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

    /**
     * The box holding the bubble and its pulse ring.
     *
     * Kept as a field for one reason: both the cancel button and the history
     * row are positioned *relative to the bubble* inside the root, and
     * `indexOfChild(halo)` is the only spelling of "above the bubble" that
     * stays correct as the number of optional children changes. The previous
     * `addView(abort, 0)` meant "above the bubble" only while the bubble was
     * the first child, which the history row ends.
     */
    private var halo: View? = null

    /**
     * The recent-dictations row, revealed by a long press. 🔴 A child of the
     * existing window, **never a second window** — see the class header and
     * [OverlayWindowLedgerTest]. As a window it would need its own `params`,
     * its own alpha, its own add/remove lifecycle and its own place in the
     * clamp arithmetic, and it needs none of those as a child.
     *
     * ⚠ This used to end *"and its own [OverlayVisibility] sink; forgetting the
     * last of those is PR #4's bug"*. The hazard is real and is still PR #4's
     * bug, but "its own sink" is not how it is paid: [warning] is a second
     * window and shares the ONE sink registered in [onCreate], deliberately,
     * so that two windows cannot disagree about whether Dicta is on screen.
     */
    private var historyRow: ListView? = null

    /** One adapter for the service's lifetime; [HistoryAdapter.submit] swaps rows. */
    private val historyAdapter = HistoryAdapter()

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

    /**
     * When the CURRENT recording started, as the chronometer's origin.
     *
     * 🔴 Set in [startRecording] and read by [buildNotification]; it must not be
     * re-stamped on a promote, or the elapsed time resets to zero every time
     * the notification is rebuilt and reads ~0 forever — which is exactly the
     * "nothing reminds you" state the cap exists for, wearing a clock.
     *
     * 🔴 TWO constraints, and satisfying only the first is how this shipped
     * broken: it must also be stamped BEFORE the `promoteForeground(recording =
     * true)` that posts the notification. The stamp originally sat after the
     * session started, 49 lines below the promote, so the posted `setWhen`
     * carried `0L` on the first recording — a chronometer counting from the
     * epoch — and the previous recording's start on every one after. Nothing
     * re-promotes while recording, so the first post is the only clock the user
     * sees. Pinned by
     * `BubbleServiceFlowTest.thePostedRecordingNotificationCarriesThisRecordingsOrigin`,
     * which reads the notification actually handed to `startForeground` rather
     * than rebuilding one afterwards.
     */
    private var recordingStartedAtMs = 0L

    private var bubbleSizePx = 0
    private var haloSizePx = 0
    private var cancelBlockPx = 0

    /**
     * How much taller the window is while the history row is shown — the sum
     * of the chips ON SCREEN (`min(count, REVEAL_SLOTS)`), not of every row the
     * scrollable list holds.
     *
     * 🔴 Feeds [BubbleChrome.clampY]. Leaving it out of the clamp is the
     * mutation that re-opens the unreachable-control defect from the other
     * end: long-press with the bubble near the bottom edge and the chips land
     * off-screen, which is a row of controls the user can see the top pixel of
     * and cannot tap.
     */
    private var revealBlockPx = 0

    /** The history row's fixed width, and therefore the window's when shown. */
    private var revealWidthPx = 0

    /** One chip plus the gap under it. */
    private var chipBlockPx = 0

    /**
     * The chip row's own `bottomMargin`, which is part of the height the
     * window gains while the row is VISIBLE. See [renderReveal].
     */
    private var revealRowMarginPx = 0

    /**
     * Bumped on every reveal and every dismissal, so an in-flight async
     * history load can tell that the reveal it belongs to is gone. See
     * [revealHistory].
     */
    private var revealGeneration = 0

    /** Off-main-thread reads of the history index. See [loadRecentHistory]. */
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()

    /**
     * The **one** holder of what is known about the transcription server's
     * reachability. See [SttReachability] for the fail-open contract and
     * [SttReachabilityLedgerTest] for who may touch it.
     *
     * `internal var` for the same reason as [sessionFactory]: the staleness
     * rule is a function of a clock, and a test that slept through a 60 s
     * window would be a test of `Thread.sleep`.
     */
    internal var reachability: SttReachability = SttReachability()

    /**
     * The warning dialog's own `WindowManager` view, or null when none is up.
     *
     * 🔴 A SECOND overlay window, which this file's class header argues
     * against for the cancel button and the history row. The difference is not
     * that the hazard went away — it is that this surface cannot be a child of
     * the bubble's window. That window is `WRAP_CONTENT` at `Gravity.TOP|END`
     * with a `y` the user drags, and every piece of [clampWindow]'s arithmetic
     * is written in terms of the bubble staying put as children are added
     * above and below it. A ~300 dp card centred on the display is not
     * expressible there: it would have to widen the dragged window to most of
     * the screen, and that window consumes every touch inside its bounds.
     *
     * So the hazard is paid rather than dodged: [visibilitySink] drives BOTH
     * windows, and [OverlayWindowLedgerTest] counts calls per file rather than
     * merely listing files, so this second `addView` had to be declared.
     */
    private var warning: View? = null

    /** [warning]'s layout params, so [visibilitySink] can zero its alpha. */
    private var warningParams: WindowManager.LayoutParams? = null

    /**
     * Registered in [onCreate] and unregistered in [onDestroy]; the flag is
     * what makes the unregister safe when `onCreate` returned early (no
     * overlay permission, or a refused promotion), since
     * `unregisterReceiver` throws for a receiver that was never registered.
     */
    private var screenReceiverRegistered = false

    /**
     * Where the `/healthz` probe runs. `null` means [SttHttp].
     *
     * 🔴 Not a second probe. `SttHttp.healthz()` is the probe — it already
     * carries its own short read timeout and `SttRetryPolicy`'s connect
     * timeout, and it already turns every transport failure into `false`. This
     * seam exists so a test can decide what the server says without opening a
     * socket, and the token it is handed is empty because `healthz()` sends no
     * `Authorization` header: the endpoint is unauthenticated, and attaching
     * one would make a token fault look like an outage.
     */
    internal var healthProbe: ((String) -> Boolean)? = null

    /** Where [healthProbe] runs. `null` means [worker]. As [historyExecutor]. */
    internal var probeExecutor: java.util.concurrent.Executor? = null

    /**
     * Where the base URL and the remote opt-out come from. `null` means
     * [SttSettings.create].
     *
     * A seam because [KeystoreCipher] has no Robolectric implementation, so a
     * token cannot be *stored* off-device — and `isRemoteConfigured` is
     * `remoteEnabled && hasToken`. A test therefore builds a real
     * [SttSettings] over a real `SharedPreferences` with a reversible cipher,
     * which exercises the production predicate rather than a stand-in for it.
     */
    internal var settingsProvider: (() -> SttSettings)? = null

    /**
     * Swappable session construction.
     *
     * The real one needs an `AudioRecord`, which has no usable Robolectric
     * implementation, so without a seam here the only testable tap is the first
     * one — the service would fail to start capture, fall back to idle, and a
     * "second tap" would be indistinguishable from another first tap.
     * `internal` and set before `onCreate`.
     *
     * 🔴 `AudioRecord` is the whole reason, and this used to also name "an
     * Android keystore". That was measured wrong: `SttSettings.create` reaches
     * no keystore at construction and returns a real object under Robolectric
     * — see [realSession], where the catch written for a keystore failure was
     * deleted as unreachable.
     *
     * Only the *field* is nullable, and it means "use [realSession]". The
     * **return** type used to be nullable too, as a seam for a
     * `Failure.SETTINGS_UNAVAILABLE` classification that no longer exists; a
     * test that wants a refused capture returns a handle whose `start()`
     * returns false, which is the path production actually takes.
     */
    internal var sessionFactory: ((DictationSession.Callbacks) -> SessionHandle)? = null

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
     * Where the reveal's chips come from. `null` means the real
     * [DictationHistoryStore].
     *
     * A seam rather than a direct call because the production read touches the
     * filesystem and, when an audio file has gone missing, **rewrites the
     * index** — so a test driving the reveal would otherwise be a test of the
     * store as well as of the gesture, and would be at the mercy of whatever
     * the previous test left in `noBackupFilesDir`.
     */
    internal var historyProvider: ((Int) -> List<DictationHistoryStore.Item>)? = null

    /**
     * Where [historyProvider] runs. `null` means [worker].
     *
     * Separate from the provider seam on purpose: a test wants a *synchronous*
     * executor so that "long press, then assert" has no race in it, and that
     * is an orthogonal choice from where the items come from.
     */
    internal var historyExecutor: java.util.concurrent.Executor? = null

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
            // 🔴 Deliberately does NOT clear the flag, and the asymmetry with
            // the branch below is the whole point. A revoked overlay
            // permission is not the user changing their mind, so their stored
            // intent survives it and the bubble returns once the permission is
            // re-granted — the same policy BubbleBootReceiver argues at its own
            // site. Nothing lies in the meantime because the permission is
            // part of BubbleEnabled.isUp, which is what every surface the user
            // sees reads: with canDrawOverlays false they all render off
            // regardless of the flag.
            stopSelf()
            return
        }
        if (!promoteForeground(recording = false)) {
            // 🔴 Whether the flag changes here depends on WHO asked for this
            // start, and the owner decides — this site does not branch.
            //
            // For a start the USER just made it must be cleared. Unlike the
            // branch above there is no mask: canDrawOverlays is true, so
            // BubbleEnabled.isUp would report the bubble as up while this
            // service stops itself without ever having shown a window. That is
            // issue #14's defect exactly — MainActivity's switch renders ON
            // with nothing running, and a Switch fires its listener only on a
            // CHANGE, so one tap does nothing and the user has to toggle OFF
            // then ON.
            //
            // 🔴 For a BOOT restore it must NOT be cleared, and this used to
            // clear it unconditionally. The flag is stored intent that survives
            // a reboot by design, so clearing it on a refusal reached through
            // BubbleBootReceiver means the bubble does not come back on this
            // boot OR ANY LATER ONE and the user has to rediscover the switch —
            // which is the thing the receiver, BubbleEnabled.isOn and the
            // README all say a reboot must not be able to do.
            BubbleEnabled.foregroundPromotionRefused(this)
            stopSelf()
            return
        }
        wm = getSystemService(WindowManager::class.java)
        buildWindow()
        OverlayVisibility.register(visibilitySink)
        registerScreenReceiver()
        // 🔴 Immediately, not after one interval. The operator's first case is
        // "as soon as the bubble opens": a bubble that has been up for two
        // seconds has no knowledge, which is a PROCEED, so without this the
        // warning is unavailable for the first minute of every service life —
        // and the service is restarted on every boot and every upgrade.
        scheduleProbe(0L)
    }

    // --- the window ---------------------------------------------------------

    private fun buildWindow() {
        val density = resources.displayMetrics.density
        bubbleSizePx = (BUBBLE_DP * density).toInt()
        haloSizePx = (HALO_DP * density).toInt()
        cancelBlockPx = ((CANCEL_DP + CANCEL_GAP_DP) * density).toInt()
        revealWidthPx = (REVEAL_WIDTH_DP * density).toInt()
        chipBlockPx = ((CHIP_DP + CHIP_GAP_DP) * density).toInt()
        revealRowMarginPx = (CHIP_GAP_DP * density).toInt()

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
        val bubbleBox = FrameLayout(this).apply {
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
        bubbleBox.addView(pulse)
        bubbleBox.addView(button)

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

        // 🔴 A VERTICAL stack of chips, not the horizontal strip the design
        // sketch proposed. Four chips side by side, each carrying a relative
        // timestamp plus the opening words of a transcript, is ~600 dp of row
        // — wider than any phone — so a horizontal strip would have to cut
        // each label to a handful of characters and the labels are the whole
        // point of the feature. Stacked, each chip gets the full width the
        // window can afford and the row reads as a menu rising out of the
        // bubble.
        //
        // 🔴 A framework ListView, NOT a RecyclerView and NOT a ScrollView.
        //  - RecyclerView would be the obvious choice and is FORBIDDEN here:
        //    `app/build.gradle.kts` has an EMPTY runtime `dependencies {}`
        //    block by explicit, re-measured decision, and androidx.recyclerview
        //    drags in annotation/lifecycle/collection/customview. ListView is
        //    in the platform at minSdk 26 and costs nothing.
        //  - A ScrollView would scroll but NOT recycle, so 50 chips would be
        //    50 live views. ListView's `convertView` IS the virtualization:
        //    only the ~4 on screen plus a scrap view ever exist.
        //
        // 🔴 The height is set EXPLICITLY in renderReveal, never WRAP_CONTENT.
        // WRAP_CONTENT asks a ListView to measure every row, which both defeats
        // recycling and unbounds the window — the two things this is for.
        val recents = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                revealWidthPx,
                // Replaced per-reveal by renderReveal. Zero, not WRAP_CONTENT,
                // so a reveal that somehow renders before the adapter is set
                // contributes no height rather than an unbounded one.
                0,
            // Same field renderReveal adds to revealBlockPx — one rule, one
            // place. Computing it twice is how F4 got in.
            ).apply { bottomMargin = revealRowMarginPx }
            visibility = View.GONE
            // The chips carry their own separation via chipBlockPx; a divider
            // would add height the clamp arithmetic does not know about.
            divider = null
            dividerHeight = 0
            isVerticalScrollBarEnabled = false
            // Chips are the tap targets and each sets its own click listener;
            // ListView's own selector would draw a second highlight over them.
            selector = android.graphics.drawable.ColorDrawable(0)
            // Overscroll glow on a tiny floating window reads as a glitch.
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // The reveal sits ABOVE the bubble at index 0; see showReveal.
            addView(recents)
            addView(bubbleBox)
            addView(abort)
        }

        // 🔴 ACTION_OUTSIDE is delivered to the WINDOW's root view, not to the
        // halo's DragListener — so dismissing the reveal on an outside tap has
        // to be wired here and cannot live in that listener.
        //
        // Returning false is deliberate: an ACTION_OUTSIDE carries no position
        // we are entitled to act on and the touch still belongs to whatever is
        // underneath, so this observes the event and consumes nothing. The
        // dismissal itself is the machine's — `Event.TouchedOutside` has an
        // empty arm and rides `on()`'s existing HideHistory prefix, so the
        // "when does the row close" predicate stays in one place.
        container.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                applyEffects(machine.on(BubbleDictation.Event.TouchedOutside))
            }
            false
        }

        wm.addView(container, params)
        root = container
        ring = pulse
        cancel = abort
        bubble = button
        halo = bubbleBox
        historyRow = recents
    }

    /**
     * The bubble is an always-on-top overlay, so it is composited into any
     * MediaProjection screenshot *and* drawn over [DictateActivity]'s card.
     * Both mechanisms take it off the screen through [OverlayVisibility],
     * which composes them.
     *
     * 🔴 **FLAG_NOT_TOUCHABLE is not cosmetic belt-and-braces.** An overlay
     * window consumes every touch inside its bounds whether or not a view
     * there handles it — this file's own `buildWindow` comment says so, about
     * the transparent strip a fixed-height window used to leave. A hidden
     * bubble that still takes touches is a hole in whatever is underneath it,
     * and while the transcription card is open that is the card. The capture
     * path got away without this because it lasts a few frames; the card's
     * hold lasts as long as the user reads the transcript.
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
                // Bitwise against the live flags, never an assignment: the
                // reveal toggles FLAG_WATCH_OUTSIDE_TOUCH on this same field.
                params.flags = if (visible) {
                    params.flags and
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                } else {
                    params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                }
                runCatching { wm.updateViewLayout(view, params) }
            }
            // 🔴 The warning dialog is a second overlay window and therefore a
            // second thing MediaProjection composites into the user's
            // screenshot — the omission this file's header calls PR #4's bug.
            // Driven from the same sink and the same boolean so the two
            // windows cannot disagree about whether Dicta is on screen.
            val dialog = warning
            val dialogParams = warningParams
            if (dialog != null && dialogParams != null) {
                dialog.visibility = if (visible) View.VISIBLE else View.INVISIBLE
                dialogParams.alpha = if (visible) 1f else 0f
                dialogParams.flags = if (visible) {
                    dialogParams.flags and
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                } else {
                    dialogParams.flags or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                }
                runCatching { wm.updateViewLayout(dialog, dialogParams) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // 🔴 Clearing the flag is the whole of issue #14's "no way to get
            // the bubble back". The notification's Hide action used to only
            // stopSelf(), so MainActivity's switch — which derives from this
            // flag — went on reading ON with nothing running, and a Switch
            // fires its listener only on a CHANGE: tapping it did nothing and
            // the operator had to toggle OFF then ON. stopSelf, not
            // BubbleEnabled.turnOff: this service is already stopping itself.
            BubbleEnabled.set(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        OverlayVisibility.unregister(visibilitySink)
        unregisterScreenReceiver()
        removeWarning()
        // Pre-existing, and it clears `maxDurationGuard` and the long-press
        // runnable as well as any pending `probeTick`.
        //
        // ⚠ **No test pins this line on its own.** Deleting it leaves the full
        // 691-test suite green, measured — because the `root == null` branch at
        // the top of [runProbe] independently stops a surviving tick, and
        // deleting THAT alone leaves the suite green too. See that branch for
        // the full statement: `theProbeStopsOnDestroy` asserts the outcome and
        // attributes it to neither.
        //
        // 🔴 Two retractions here in two commits, both about this line, both
        // caught by the next mutant. It was first claimed to be insured by a
        // separate `main.removeCallbacks(probeTick)` above it (that line was
        // redundant three ways and is deleted); then claimed to be "what stops
        // the periodic probe" (false); then the `root == null` branch was
        // claimed to be what stops it (also false). Do not write a third
        // mechanism sentence here — the measured fact is that NEITHER
        // mechanism is individually observable, and that is what both sites now
        // say.
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
        halo = null
        historyRow = null
        // shutdownNow, like SettingsActivity: a history read in flight is
        // worth nothing once the window it was going to fill is gone, and the
        // executor is non-daemon.
        runCatching { worker.shutdownNow() }
        super.onDestroy()
    }

    // --- the state machine --------------------------------------------------

    private fun tapped() {
        // 🔴 While the warning dialog is up the machine is already in
        // RECORDING, holding the tap that opened the dialog — see
        // [applyEffects]. A second tap would therefore read as tap 2:
        // stop-and-submit, opening the transcription card for a recording that
        // never started. The bubble is still on screen and still tappable
        // underneath the dialog, so this is reachable by a user who taps the
        // bubble instead of answering.
        if (warning != null) return
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        applyEffects(machine.on(BubbleDictation.Event.BubbleTapped(granted)))
    }

    /**
     * Carries out the machine's decisions, **except** that a start which would
     * record against an unreachable server is handed to the user first.
     *
     * 🔴 The gate sits here, at the one place [BubbleDictation.Effect.StartRecording]
     * is dispatched, rather than in [tapped]: the predicate "this would start a
     * recording" is the machine's and asking it again in the shell would be a
     * second copy of the state, the debounce and the permission branch. It also
     * means a future emitter of that effect inherits the gate.
     *
     * 🔴 When the dialog is shown this returns **without applying any effect
     * and without rendering**. The machine has already moved to RECORDING, but
     * nothing on screen says so — no pulse ring, no cancel button — so the
     * bubble still looks idle while the user decides. Both exits then go
     * through the ordinary paths: the primary button applies the very effect
     * list that was withheld, and the secondary feeds
     * [BubbleDictation.Event.CancelTapped], which is the existing "RECORDING →
     * IDLE, discard, upload nothing" transition and is what puts the machine
     * back where the user thinks it is.
     */
    private fun applyEffects(effects: List<BubbleDictation.Effect>) {
        if (effects.contains(BubbleDictation.Effect.StartRecording) &&
            warnBeforeStarting(effects)
        ) {
            return
        }
        carryOut(effects)
    }

    /** Carries out the machine's decisions. Exhaustive `when` on purpose. */
    private fun carryOut(effects: List<BubbleDictation.Effect>) {
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
                BubbleDictation.Effect.RevealHistory -> revealHistory()
                BubbleDictation.Effect.HideHistory -> showReveal(false)
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
    internal fun messageFor(failure: BubbleDictation.Failure): Int = when (failure) {
        BubbleDictation.Failure.MIC_IN_USE -> R.string.stt_mic_in_use
        BubbleDictation.Failure.FOREGROUND_DENIED -> R.string.bubble_foreground_denied
        BubbleDictation.Failure.CAPTURE_UNAVAILABLE -> R.string.bubble_capture_failed
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
        // 🔴 Stamp the chronometer's origin BEFORE the promote that posts the
        // notification carrying it. This used to sit 49 lines below, next to
        // `session = handle` — which is where the no-re-stamp rule on
        // `recordingStartedAtMs` pushed it, and it is too late: the promote has
        // already built and posted the notification, and NOTHING re-promotes
        // while recording. So the posted `setWhen` carried a stale origin —
        // `0L` on the service's first recording, which Android renders as the
        // elapsed time since the epoch (~497,000 hours), and the PREVIOUS
        // recording's start on every one after that.
        // The no-re-stamp property is unaffected: this is still the only
        // assignment, still reached only from `startRecording`, so a plain
        // promote (both demote paths, and a rebuild for any other reason) reads
        // the value and never writes it.
        recordingStartedAtMs = System.currentTimeMillis()
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
        // An elvis is safe here only because neither side can return null: the
        // factory's return type is non-null, and so is `realSession()`. It was
        // written as `if (factory != null)` while the factory was allowed to
        // return null — a "no session at all" seam that stood in for a
        // `SttSettings.create()` throw, which was deleted as unreachable.
        val handle = sessionFactory?.invoke(serviceCallbacks()) ?: realSession()
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
            // take nothing, so a free mic means one of those.
            // 🔴 If one of those coincides with another opener holding the mic,
            // MIC_IN_USE wins, with no exceptions: both statements are true and
            // that is the one the user can act on. (A carve-out used to sit
            // here for the no-settings cause, which outranked MIC_IN_USE
            // because no retry could succeed. That cause is gone.)
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
     * The production session. **Never null, and no longer guarded.**
     *
     * 🔴 `SttSettings.create(this)` used to be wrapped in
     * `catch (_: Throwable) { return null }`, and a null handle was classified
     * `Failure.SETTINGS_UNAVAILABLE` — *"Dicta cannot open its saved settings
     * on this device"* — written for a device with no usable `AndroidKeyStore`.
     * **No throw path to it was found, and the keystore one is impossible.**
     * The reasoning, read off the code rather than assumed:
     *
     *  - `SttSettings.create` is `getSharedPreferences` plus two constructors,
     *    `SttSettings(prefs, TokenStore(prefs, KeystoreCipher()))`.
     *  - `KeystoreCipher`'s constructor is a parameter list with a default
     *    alias and **no `init` block**; it touches `AndroidKeyStore` lazily, in
     *    `key()`, called only from `encrypt`/`decrypt`. `SttSettings` and
     *    `TokenStore` likewise have no `init` and compute everything in their
     *    accessors. So constructing these three reaches no keystore at all, and
     *    a keystore that refuses cannot be observed on this path.
     *  - That leaves `getSharedPreferences`, which can throw on a
     *    credential-encrypted context before unlock — unreachable here: this
     *    service is not `directBootAware`, and the bubble is tapped by a
     *    present user on an unlocked device.
     *
     * The catch is therefore **gone rather than redirected**. A future throw
     * here crashes with a stack trace naming its own cause, which is
     * diagnosable; routing it into a generic "capture unavailable — try again"
     * would restate the defect that was just removed, naming a wrong cause and
     * a retry we cannot promise, with nothing in the log. It was also asymmetric
     * — the `DictationSession` construction immediately below has never been
     * wrapped, so this function already had no exception contract to keep.
     */
    private fun realSession(): SessionHandle {
        val settings = SttSettings.create(this)
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
        val box = halo ?: return
        if ((abort.visibility == View.VISIBLE) == show) return

        if (show) {
            val below = BubbleChrome.cancelFitsBelow(
                bubbleTopPx = params.y,
                bubbleHeightPx = haloSizePx,
                blockHeightPx = cancelBlockPx,
                screenHeightPx = resources.displayMetrics.heightPixels,
            )
            container.removeView(abort)
            // 🔴 "Above the bubble" is the bubble's own index, not 0. It was 0
            // while the bubble was the first child; the history row is now
            // index 0, and an unchanged `0` here would have put cancel ABOVE
            // the chips — a cancel button that is not adjacent to the control
            // it cancels, and on the far side of four tappable items.
            val wanted = if (below) container.childCount else container.indexOfChild(box)
            container.addView(abort, wanted)
            abort.visibility = View.VISIBLE
            if (!below) params.y -= cancelBlockPx
        } else {
            val wasAbove = container.indexOfChild(abort) < container.indexOfChild(box)
            abort.visibility = View.GONE
            if (wasAbove) params.y += cancelBlockPx
        }
        // 🔴 Call site 2 of 3 for the clamp. `params.y -= cancelBlockPx` above
        // can take y negative on its own, with the bubble near the top edge
        // and nothing else involved — no drag, no rotation.
        clampWindow()
    }

    // --- the history reveal --------------------------------------------------

    /**
     * Loads the newest items off the main thread and shows the row.
     *
     * Off the main thread because [DictationHistoryStore.recent] reads
     * `index.json` and, when an audio file has gone missing, rewrites it —
     * file IO either way, on the gesture that is supposed to feel immediate.
     * The row is shown when the items land, which is the same shape
     * `SettingsActivity` uses for its usage line.
     */
    private fun revealHistory() {
        // 🔴 The store read is ASYNC, so the reveal it belongs to can be
        // dismissed before the items land. Without this the late callback
        // shows a row nobody asked for any more — and that row is then
        // PERMANENT: the machine cleared `historyRevealed` when it dismissed,
        // so no later event emits HideHistory, `Event.TouchedOutside` returns
        // an empty list, and the window keeps FLAG_WATCH_OUTSIDE_TOUCH armed
        // device-wide. Found by audit round 1 of #17; it is the same "a row
        // with no way to close it" defect this PR's outside-touch commit was
        // written to fix, arriving by the async route that commit did not
        // touch.
        val generation = ++revealGeneration
        loadRecentHistory { items ->
            if (generation != revealGeneration) return@loadRecentHistory
            renderReveal(items)
            showReveal(true)
        }
    }

    private fun loadRecentHistory(then: (List<DictationHistoryStore.Item>) -> Unit) {
        val provider = historyProvider
            ?: { limit -> DictationHistoryStore(this).recent(limit) }
        val executor = historyExecutor ?: worker
        runCatching {
            executor.execute {
                // An unreadable store is "no history", never a crash in a
                // foreground service: the reveal degrades to its empty state,
                // which already has copy for exactly this.
                val items = runCatching { provider(REVEAL_MAX_ITEMS) }.getOrDefault(emptyList())
                main.post { if (root != null) then(items) }
            }
        }
    }

    /**
     * Fills the row with one chip per item, newest first, and records the
     * height that creates for [clampWindow].
     *
     * The empty case is a chip-shaped label rather than an empty row: a
     * gesture that produces a window the user cannot distinguish from "nothing
     * happened" is indistinguishable from a broken gesture.
     */
    private fun renderReveal(items: List<DictationHistoryStore.Item>) {
        val row = historyRow ?: return
        val nowMs = System.currentTimeMillis()
        val labelled = items.map { item ->
            val label = HistoryLabel.shortLabel(item.text, item.createdAtMs, nowMs)
            ChipModel(label, getString(R.string.bubble_history_chip, label), item.id)
        }
        // The empty case is a chip-shaped label rather than an empty row, for
        // the reason in the kdoc above. It is one UNCLICKABLE row, so it also
        // occupies exactly one slot of height.
        val rows = labelled.ifEmpty {
            listOf(ChipModel(getString(R.string.bubble_history_empty), null, null))
        }
        historyAdapter.submit(rows)
        if (row.adapter == null) row.adapter = historyAdapter
        // Scrolling is per-reveal, not sticky: a reveal always opens on the
        // newest item, never wherever the last one was left.
        row.setSelection(0)

        // 🔴 ONLY the chips that are ON SCREEN count toward the window. This
        // is the whole reason scrolling is a contained change: for four items
        // or fewer this is identical to the pre-scroll `childCount *
        // chipBlockPx`, so the clamp evidence carries over unchanged.
        val onScreen = minOf(rows.size, REVEAL_SLOTS)
        val listHeightPx = onScreen * chipBlockPx
        row.layoutParams = (row.layoutParams as LinearLayout.LayoutParams).apply {
            height = listHeightPx
        }
        // 🔴 Plus the ROW's own bottomMargin, which counts toward the
        // container's height while the row is VISIBLE and is skipped when it
        // is GONE — so it is part of the block the window grows by, exactly
        // like each chip's topMargin. Omitting it understated the height by
        // one CHIP_GAP (measured 318 vs 300), which both shoved the bubble
        // down on every reveal and told clampY the window was shorter than it
        // is. Audit round 1 of #17, F4.
        revealBlockPx = listHeightPx + revealRowMarginPx
    }

    /** What one chip renders. Pure data so the adapter can rebind on recycle. */
    private data class ChipModel(
        val label: String,
        val describedAs: String?,
        val id: String?,
    )

    /**
     * Recycling adapter for the reveal.
     *
     * 🔴 **Every field a chip carries is rebound on EVERY `getView`, including
     * the ones being set to null.** A recycled convertView arrives carrying the
     * PREVIOUS item's click listener, contentDescription and clickability; the
     * bug that shape produces is a chip that opens the wrong dictation, which
     * looks like a store bug and is not. `bindChip` sets all of them
     * unconditionally for exactly that reason — see its own comment.
     */
    private inner class HistoryAdapter : android.widget.BaseAdapter() {
        private var rows: List<ChipModel> = emptyList()

        fun submit(next: List<ChipModel>) {
            rows = next
            notifyDataSetChanged()
        }

        override fun getCount(): Int = rows.size

        override fun getItem(position: Int): ChipModel = rows[position]

        override fun getItemId(position: Int): Long = position.toLong()

        /** Chips are not uniformly enabled: the empty-state row is not tappable. */
        override fun areAllItemsEnabled(): Boolean = false

        override fun isEnabled(position: Int): Boolean = rows[position].id != null

        override fun getView(
            position: Int,
            convertView: View?,
            parent: android.view.ViewGroup?,
        ): View {
            val view = convertView ?: newChip()
            bindChip(view, rows[position])
            return view
        }
    }

    /**
     * One chip. A [android.widget.TextView] rather than a `Button` so the
     * label is not force-uppercased and not lifted by the platform's
     * `stateListAnimator`, both of which are the stock look the rest of the
     * app is styled away from.
     *
     * @param id the history item to open, or `null` for the inert empty-state
     *   label — which must not be clickable, because a control that accepts a
     *   tap and does nothing is worse than a caption.
     */
    /**
     * A chip with everything that does NOT vary per item.
     *
     * 🔴 Height is [CHIP_DP] plus [CHIP_GAP_DP] of TOP PADDING, not a margin.
     * `AbsListView.LayoutParams` has no margins, so the gap a `LinearLayout`
     * used to contribute has to live inside the row or the rendered pitch
     * stops matching `chipBlockPx` — and `chipBlockPx` is what the window
     * height and therefore `clampY` are computed from. The two must agree by
     * construction; `aChipRowIsExactlyOneChipBlockTall` pins it.
     */
    private fun newChip(): View {
        val density = resources.displayMetrics.density
        val gapPx = (CHIP_GAP_DP * density).toInt()
        val sidePx = (14 * density).toInt()
        return android.widget.TextView(this).apply {
            layoutParams = android.widget.AbsListView.LayoutParams(
                android.widget.AbsListView.LayoutParams.MATCH_PARENT,
                (CHIP_DP * density).toInt() + gapPx,
            )
            maxLines = 1
            // A backstop only. The label is short BY CONSTRUCTION — see
            // HistoryLabel, and the measured reason the JVM cannot assert
            // anything about rendered width.
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(sidePx, gapPx, sidePx, 0)
            textSize = 13f
            setTextColor(getColor(R.color.dicta_on_surface))
            background = getDrawable(R.drawable.history_chip_bg)
        }
    }

    /**
     * Binds one item onto a chip that may be RECYCLED.
     *
     * 🔴 **Every per-item field is set on every call, including when the new
     * value is null.** A recycled view arrives wearing the previous item's
     * click listener, `contentDescription`, `tooltipText` and clickability. An
     * `if (id != null)` that only ever SETS those leaves a scrolled-away chip's
     * listener in place, so tapping the empty-state row — or any chip that
     * reused its view — opens whatever dictation the old one pointed at. That
     * reads as a history-store bug and is not one.
     */
    private fun bindChip(view: View, model: ChipModel) {
        val chip = view as android.widget.TextView
        chip.text = model.label
        chip.contentDescription = model.describedAs
        chip.tooltipText = model.describedAs
        val id = model.id
        if (id == null) {
            chip.setOnClickListener(null)
            chip.isClickable = false
        } else {
            chip.setOnClickListener { openHistoryItem(id) }
        }
    }

    /**
     * Opens the card on a stored dictation.
     *
     * 🔴 Through [DictateLaunch], never a hand-built `Intent`. Same mechanism
     * as [requestMicPermission] above and the same bug (#11) if it is skipped:
     * the overlay must land in its OWN task or the translucent card renders
     * over Dicta's own UI. `DictateLaunchTest`'s construction ledger is what
     * makes that a red test rather than a code review.
     */
    private fun openHistoryItem(id: String) {
        showReveal(false)
        runCatching { startActivity(DictateLaunch.historyIntent(this, id)) }
    }

    /**
     * Shows or hides the chip row, compensating `y` so the **bubble** stays
     * put while the window grows upward — the same arithmetic [showCancel]
     * does, for the same reason: with `Gravity.TOP` the window's top edge is
     * pinned at `y`, so a child added above the bubble would otherwise shove
     * the bubble down by the row's height.
     *
     * ⚠ **"Stays put" is exact on the hide side and approximate on the reveal
     * side, and the difference is the clamp, not a defect.** Hiding always
     * gives back the full `revealBlockPx`, so the bubble returns to wherever
     * it currently is — including after a drag. Revealing can move it: if the
     * row would go off the top of the display, [clampWindow] pins the window
     * and the bubble drops by whatever the clamp absorbed. That is visible at
     * reveal time, is bounded by `revealBlockPx`, and happens once rather
     * than cumulatively. Round 1 of #17 read it as a hide-side bug and
     * "corrected" it into a teleport; see the hide arm.
     *
     * Idempotent. The machine clears its own reveal flag on the next event, so
     * a chip tap (which hides the row directly, outside the machine) is
     * followed by a [BubbleDictation.Effect.HideHistory] for a row that is
     * already away. That is a no-op here rather than a second `y` shift.
     */
    private fun showReveal(show: Boolean) {
        if (root == null) return
        val row = historyRow ?: return
        // 🔴 BEFORE the idempotence check, and that ordering is the whole
        // point: in the race this guards, the row is still GONE when the
        // dismissal arrives, so a bump placed after the early return would
        // never execute in exactly the case it exists for.
        if (!show) revealGeneration++
        if ((row.visibility == View.VISIBLE) == show) return
        if (show) {
            row.visibility = View.VISIBLE
            params.y -= revealBlockPx
        } else {
            // 🔴 ALWAYS the full `revealBlockPx`, never "the shift the clamp
            // left". The bubble sits `revealBlockPx` below the window top
            // while the row is up and AT the top once it is away, so
            // `y += revealBlockPx` is exactly what holds the BUBBLE still —
            // the invariant the user can actually see.
            //
            // Round 1 of #17 changed this to a recorded post-clamp shift, and
            // round 2 measured what that cost: reveal near the top edge, DRAG
            // the bubble down, dismiss — and the bubble teleports back up,
            // because a recorded number pins the window ORIGIN while the drag
            // had since moved it.
            //
            // ⚠ The jump is `revealBlockPx - y_at_reveal`. It does NOT scale
            // with how far the user drags — the drag makes the wrong
            // position VISIBLE, it does not size it.
            //
            // 🔴 No magnitude is quoted here ON PURPOSE. A figure in this
            // paragraph needed correcting in FOUR consecutive audit rounds
            // of #17 (290 → 318 → naming the fixture for 608 vs 308 → the
            // sweep that missed a second site), because every number here is
            // a function of two fixture-dependent operands and reads as a
            // property of the bug. The formula is the durable claim; the
            // guard measures the rest. Round 1 justified the change with "290 px,
            // repeatable, so successive reveals walk it down the screen";
            // that was measured FALSE. The behaviour it replaced is a
            // ONE-TIME bounded drop of `revealBlockPx - y`, stable from the
            // second cycle on, and it is the clamp legitimately keeping the
            // row on screen rather than a hide-side bug.
            row.visibility = View.GONE
            params.y += revealBlockPx
            revealBlockPx = 0
            // 🔴 Empty the ADAPTER; never `row.removeAllViews()`. An
            // AdapterView throws `UnsupportedOperationException` on that, and it
            // would throw from inside the HIDE path — i.e. while dismissing,
            // which is the one moment the user cannot retry. The chips must
            // still be dropped here: each holds a click listener closed over a
            // history id, and a stale one opens a dictation nobody picked.
            historyAdapter.submit(emptyList())
            // The height goes with them, so a row shown again before its next
            // load lands contributes no window height.
            row.layoutParams = (row.layoutParams as LinearLayout.LayoutParams).apply {
                height = 0
            }
        }
        // 🔴 Scoped to the revealed state, NOT set once at window creation.
        // FLAG_WATCH_OUTSIDE_TOUCH delivers an ACTION_OUTSIDE for every touch
        // anywhere on the device, so leaving it on would wake this service on
        // each tap for the ~100% of its life when there is no row to dismiss.
        params.flags = if (show) {
            params.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()
        }
        clampWindow()
    }

    // --- keeping the window on screen ----------------------------------------

    /**
     * 🔴 Call site 3 of 3 for the clamp, and the one that is an actual bug
     * rather than a hardening.
     *
     * `Service` implements `ComponentCallbacks2`, so this is delivered for
     * free and no manifest `configChanges` is involved. Park the bubble at the
     * bottom of a portrait display and rotate: the usable height drops by
     * roughly the difference between the two dimensions, and the whole window
     * — bubble *and* cancel button — is now below the visible area. If a
     * recording is running at that moment it can be neither stopped nor
     * cancelled, which is an open microphone with no reachable control. That
     * is `#14` item 5's closing condition, and it is a device observation by
     * construction; what is pinned off-device is the arithmetic and that this
     * hook calls it.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        clampWindow()
    }

    /** Applies [BubbleChrome.clampY]/[BubbleChrome.clampX] and lays out. */
    private fun clampWindow() {
        val container = root ?: return
        val metrics = resources.displayMetrics
        params.y = BubbleChrome.clampY(params.y, windowHeightPx(), metrics.heightPixels)
        params.x = BubbleChrome.clampX(params.x, windowWidthPx(), metrics.widthPixels)
        runCatching { wm.updateViewLayout(container, params) }
    }

    /**
     * The window's height **as it is right now**, derived from the dp
     * constants rather than from `root.height`.
     *
     * 🔴 Derived, because a measured height is 0 until layout has run: the
     * clamp has to be correct on the *first* `ACTION_MOVE` and on a rotation
     * the view tree has not been laid out for yet, and a 0 there means
     * "clamp to the whole screen", i.e. no clamp at all in exactly the two
     * moments it is needed.
     */
    private fun windowHeightPx(): Int =
        haloSizePx +
            (if (cancel?.visibility == View.VISIBLE) cancelBlockPx else 0) +
            (if (historyRow?.visibility == View.VISIBLE) revealBlockPx else 0)

    /** As [windowHeightPx]: the row is wider than the bubble when shown. */
    private fun windowWidthPx(): Int =
        if (historyRow?.visibility == View.VISIBLE) {
            maxOf(haloSizePx, revealWidthPx)
        } else {
            haloSizePx
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

    // --- the reachability probe ----------------------------------------------

    /**
     * The base URL and the remote opt-out, through [settingsProvider].
     *
     * Constructed per call rather than cached: `SttSettings` reads through to
     * `SharedPreferences` on every access, so the live value is what comes
     * back when the user changes the setting in `SettingsActivity` while the
     * bubble is up.
     *
     * 🔴 **CONSTRUCTING one reaches no keystore; USING one usually does.** The
     * `create` call on the next line is `getSharedPreferences` plus two
     * constructors — see [realSession]. That is all this sentence ever
     * licensed, and it read as a general "no keystore here" at call sites whose
     * very next step reaches one: `isRemoteConfigured` (both [remoteInUse] and
     * [runProbe]) evaluates `tokens.hasToken`, which decrypts through
     * `SecretCipher` and so loads an AndroidKeyStore key. Cheap to build, not
     * cheap to interrogate — budget for the latter at the call site.
     */
    private fun sttSettings(): SttSettings =
        settingsProvider?.invoke() ?: SttSettings.create(this)

    /**
     * 🔴 The one precondition for probing at all, and for warning at all.
     *
     * `isRemoteConfigured` is `remoteEnabled && tokens.hasToken`, and it is
     * exactly what `RemoteTranscriber.transcribe` gates on. So when it is
     * false the app never attempts a remote transcription in the first place:
     * there is no server in the picture, nothing to warn about, and a probe
     * would be a network call made on behalf of a feature the user turned off.
     *
     * 🔴 The TOKEN half is load-bearing here even though `/healthz` is
     * unauthenticated. A probe would succeed against a reachable server with
     * no token stored — and the recording would still go to the phone, because
     * the upload path refuses before it opens a socket. Gating on
     * `remoteEnabled` alone would therefore make the dialog's claim ("recording
     * now will use the phone's own recognizer") the one thing it never said,
     * since a *reachable* server never warns.
     */
    private fun remoteInUse(): Boolean = sttSettings().isRemoteConfigured

    /**
     * `PowerManager.isInteractive` — the screen gate.
     *
     * 🔴 No wakelock anywhere in this feature, and no probe while the phone is
     * asleep. The operator measured the mesh tunnel dropping while the phone
     * sleeps, so a probe then reports a failure that says nothing about
     * whether the server is up — and recording it would arm the warning for
     * the first tap after the screen comes back on. The structural half of the
     * same decision is [SttReachability.forget] on `ACTION_SCREEN_OFF`.
     *
     * `isInteractive` and not `isScreenOn`: the latter is deprecated and, on a
     * device with an always-on display, reports true while the device is
     * dozing.
     */
    private fun screenIsInteractive(): Boolean =
        getSystemService(PowerManager::class.java)?.isInteractive ?: true

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        // 🔴 Both are protected broadcasts that CANNOT be declared in a
        // manifest — the platform only delivers them to a runtime-registered
        // receiver — which is why this is here and not in AndroidManifest.xml,
        // and why it has to be torn down by hand.
        screenReceiverRegistered =
            runCatching { registerReceiver(screenReceiver, filter) }.isSuccess
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return
        screenReceiverRegistered = false
        runCatching { unregisterReceiver(screenReceiver) }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // Stop the chain AND drop what is known. Stopping alone
                    // would leave the last pre-sleep result sitting in the
                    // cache, ageing toward stale on a clock that keeps running
                    // while the probes do not.
                    main.removeCallbacks(probeTick)
                    reachability.forget()
                }
                // Not "resume the chain": probe now. The screen coming back on
                // is the moment before the user's next tap.
                Intent.ACTION_SCREEN_ON -> scheduleProbe(0L)
            }
        }
    }

    private val probeTick = Runnable { runProbe() }

    /** Replaces any pending tick, so the chain can never fork. */
    private fun scheduleProbe(delayMs: Long) {
        main.removeCallbacks(probeTick)
        main.postDelayed(probeTick, delayMs)
    }

    /**
     * One probe, then reschedules itself.
     *
     * 🔴 Every early return reschedules. A branch that returned without
     * posting the next tick would stop the chain for the rest of the service's
     * life — and the two conditions that cause an early return (the remote
     * path off, the screen not interactive) are both things the user changes
     * back, with no event of their own in the "turned it back on" direction
     * for the settings one.
     */
    private fun runProbe() {
        // The window is gone: the service is being torn down, or `onCreate`
        // refused before building one. Deliberately does NOT reschedule — this
        // is the one terminal branch.
        //
        // ⚠ **No test pins this branch on its own, and the reason is worth
        // stating.** It and `onDestroy`'s `main.removeCallbacksAndMessages(null)`
        // are two INDEPENDENT mechanisms for one outcome: the handler clear
        // drops the pending tick, and this returns early if a tick survives.
        // Measured — deleting either one ALONE leaves the full 691-test suite
        // green. So `theProbeStopsOnDestroy` asserts the OUTCOME ("no probe
        // runs after onDestroy", which is the thing that matters, and it is
        // true) and attributes it to neither mechanism; only removing both
        // would fail it.
        //
        // Both are kept, because each covers a case the other does not: the
        // handler clear also drops `maxDurationGuard` and the long-press
        // runnable, and this branch also covers the `onCreate` refusal path,
        // where no tick was ever posted to clear. A THIRD redundant
        // `main.removeCallbacks(probeTick)` stood in `onDestroy` and was
        // deleted — it had no such independent reason.
        if (root == null) return
        val settings = sttSettings()
        // ⚠ KNOWN AND NOT FIXED HERE: when the remote path IS configured, this
        // line does a keystore unwrap plus a GCM decrypt on the MAIN THREAD,
        // once per tick (60 s). `isRemoteConfigured` is
        // `remoteEnabled && tokens.hasToken` and Kotlin's `&&` already
        // short-circuits, so the user who turned the feature OFF pays nothing —
        // but that is the user who would not have cared. No restructure
        // available inside this function preserves behaviour exactly: hoisting
        // the predicate onto `probeExecutor` adds two thread hops before any
        // branch, lets state change between evaluation and decision, and makes
        // a shut-down executor swallow the `forget()` + `scheduleProbe` that
        // currently still run. Splitting the conjunction so `hasToken` is tested
        // after `screenIsInteractive()` changes observable state too: with
        // `remoteEnabled` true, no token, and the screen off, the `forget()`
        // below stops happening. Memoising the decrypt, or the `SecretKey`
        // inside `SecretCipher`, is the real fix and is a change to the crypto
        // layer — its own PR, not a line here. Magnitude is UNMEASURED: nothing
        // in this repo can time Keymaster IPC.
        if (!settings.isRemoteConfigured) {
            // Drop any knowledge from before the user turned it off, so it
            // cannot warn if they turn it back on an hour later.
            reachability.forget()
            scheduleProbe(SttReachability.PROBE_INTERVAL_MS)
            return
        }
        if (!screenIsInteractive()) {
            // A backstop for a screen that went off without the broadcast
            // reaching us; ACTION_SCREEN_ON is the normal re-arm.
            scheduleProbe(SttReachability.PROBE_INTERVAL_MS)
            return
        }
        val token = reachability.beginProbe()
        if (token == null) {
            // One in flight already — nothing to do but come back later.
            scheduleProbe(SttReachability.PROBE_INTERVAL_MS)
            return
        }
        val baseUrl = settings.baseUrl
        val probe = healthProbe ?: { url -> SttHttp(url, token = "").healthz() }
        val executor = probeExecutor ?: worker
        val submitted = runCatching {
            executor.execute {
                val outcome = runCatching { probe(baseUrl) }
                main.post {
                    // 🔴 A THROW records nothing; it does not record a failure.
                    // `SttHttp.healthz` already maps every transport failure —
                    // connect refused, read timeout, reset, a malformed URL —
                    // to `false` and cannot throw, so an exception escaping
                    // here is a fault in this process and carries no
                    // observation about the server. Recording `false` for it
                    // would mint the one input that warns out of a local bug,
                    // and the previous result (including "nothing known")
                    // stands instead.
                    outcome.fold(
                        onSuccess = { ok -> reachability.onProbeResult(token, ok) },
                        onFailure = { reachability.abandonProbe() },
                    )
                    if (root != null) scheduleProbe(SttReachability.PROBE_INTERVAL_MS)
                }
            }
        }.isSuccess
        if (!submitted) {
            // The executor refused — it is shut down, which is `onDestroy`.
            // 🔴 Abandoned rather than recorded as a failure: nothing about the
            // server was observed, and recording `false` here would mint a
            // FRESH DEFINITE FAILURE out of a purely local condition, which is
            // the one input that warns.
            reachability.abandonProbe()
        }
    }

    // --- the unreachable-server warning --------------------------------------

    /**
     * @return true when the dialog is now up and [effects] were withheld.
     *
     * 🔴 Fails open at every step, including the platform one: if
     * `addView` refuses, this returns false and the caller records. A gate
     * whose failure mode is "no recording" would be worse than no gate, since
     * `TranscriptionCoordinator` already falls back to the on-device
     * transcript when the server cannot be reached — so the cost of not
     * warning is an unannounced fallback, and the cost of blocking is the
     * dictation.
     */
    private fun warnBeforeStarting(effects: List<BubbleDictation.Effect>): Boolean {
        // 🔴 `verdict()` FIRST, and the order is load-bearing rather than
        // stylistic. Both terms are pure reads and the conjunction is the same
        // either way, so the returned decision is identical — but they cost
        // very different amounts. `verdict()` reads four in-memory fields under
        // this object's own monitor. `remoteInUse()` is
        // `SttSettings.isRemoteConfigured`, whose `tokens.hasToken` term calls
        // `token()`, which decrypts the stored blob — and that unwraps an
        // AndroidKeyStore key (`SecretCipher.key()` does
        // `KeyStore.getInstance(...).load(null)` on every call) plus an AES-GCM
        // `doFinal`, with nothing memoising either. This function runs
        // synchronously on the main thread from the tap that starts a
        // recording, so checking the cheap term first keeps the keystore unwrap
        // out of tap-to-mic latency for every tap except one already about to
        // open a dialog.
        //
        // ⚠ What that unwrap COSTS is not measured anywhere in this repo: no
        // handset was attached and nothing here can time Keymaster IPC. The
        // claim made by this ordering is only that the work is now SKIPPED on
        // the common path — not that it was slow.
        if (reachability.verdict() != SttReachability.Verdict.WARN) return false
        if (!remoteInUse()) return false
        return showUnreachableWarning(effects)
    }

    /**
     * Builds and adds the dialog window.
     *
     * 🔴 **`FLAG_NOT_FOCUSABLE`, like the bubble's window.** A focusable
     * overlay takes input focus from whatever app is underneath, which closes
     * its IME and can lose a half-typed message — and this dialog appears over
     * an arbitrary app by design. Buttons do not need focus: a click is a
     * touch, and touches are delivered to a non-focusable window inside its
     * own bounds. What it costs is the hardware/gesture Back key, which the
     * platform routes to the focused window; the two explicit buttons are the
     * whole of the dismissal contract and the reason that is acceptable.
     *
     * 🔴 **No `FLAG_WATCH_OUTSIDE_TOUCH`, and this is a decision rather than
     * an omission — an outside tap does nothing.** The three candidate
     * meanings for a tap outside a two-button decision are "cancel", "record"
     * and "nothing", and the first two both commit the user to an outcome they
     * did not pick while their finger was somewhere else entirely. `#17`
     * armed that flag for the history reveal, where it is right because the
     * reveal is a *disclosure* with no outcome attached and dismissing it
     * decides nothing. This window is sized to its own card, so a tap outside
     * it is not delivered here at all and goes to the app underneath; the
     * dialog stays until one of its buttons is pressed. Both buttons are
     * on screen at all times, so there is no state this can strand the user
     * in.
     */
    private fun showUnreachableWarning(effects: List<BubbleDictation.Effect>): Boolean {
        removeWarning()
        val view = buildWarningView(
            onRecord = {
                removeWarning()
                carryOut(effects)
            },
            onDecline = {
                removeWarning()
                // The existing RECORDING → IDLE transition: discard, upload
                // nothing, open no card. `stopRecording(submit = false)` then
                // runs with a null session, which is exactly the state a
                // withheld StartRecording leaves behind.
                carryOut(machine.on(BubbleDictation.Event.CancelTapped))
            },
        )
        val p = warningLayoutParams()
        if (!runCatching { wm.addView(view, p) }.isSuccess) return false
        warning = view
        warningParams = p
        return true
    }

    private fun removeWarning() {
        val view = warning ?: return
        warning = null
        warningParams = null
        runCatching { wm.removeView(view) }
    }

    /**
     * 🔴 The WIDTH is explicit, in pixels, and that is the dimension that
     * matters: it is what decides where the body text wraps. A `WRAP_CONTENT`
     * width asks the window manager to size the window from the widest child,
     * and the widest child here is a paragraph — so the result depends on how
     * the platform chooses to break it, which is the dimension clipped text has
     * been reported on for this project's existing overlay window. (Reported,
     * not measured here: no handset was attached when this was written, and
     * nothing in this environment can measure text.) Pinning it removes the
     * dependency rather than betting on it. Capped against the display so a
     * narrow or landscape screen cannot produce a card wider than the screen
     * it is centred on.
     *
     * ⚠ The HEIGHT is `WRAP_CONTENT`, deliberately. A fixed height is the
     * thing that clips: the body is a translated, user-locale string whose
     * rendered line count is a device fact, and this project's Robolectric
     * cannot measure text at all (`TextPaint.measureText` returns a flat
     * 1.0 px per character), so any constant chosen here would be a guess that
     * no test could check. With the width pinned and the window centred, the
     * wrap is measured against the full display height, which is the one
     * dimension that is never the constraint.
     */
    private fun warningLayoutParams(): WindowManager.LayoutParams {
        val density = resources.displayMetrics.density
        val wanted = (WARNING_WIDTH_DP * density).toInt()
        val available =
            resources.displayMetrics.widthPixels - 2 * (WARNING_MARGIN_DP * density).toInt()
        return WindowManager.LayoutParams(
            minOf(wanted, maxOf(available, 1)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }
    }

    /**
     * The card: a title, the body, and the two buttons.
     *
     * Built in code rather than inflated from a layout for the same reason the
     * bubble is — a `Service` has no `LayoutInflater` theme and the one piece
     * of styling that matters here is the surface ramp, which is two
     * `getColor` calls.
     *
     * 🔴 The primary button RECORDS. It is first in the stack and carries the
     * accent fill because that is this app's "dictate" affordance; the
     * secondary is outlined and starts nothing. Nothing here is `GONE` or
     * conditionally added: both buttons exist for every showing of this
     * dialog, because a decision surface with one reachable exit is the
     * focus-steal trap wearing a card.
     */
    private fun buildWarningView(onRecord: () -> Unit, onDecline: () -> Unit): View {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val gap = (12 * density).toInt()
        val buttonHeight = (48 * density).toInt()

        fun button(textRes: Int, backgroundRes: Int, textColorRes: Int, onClick: () -> Unit) =
            TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    buttonHeight,
                ).apply { topMargin = gap }
                text = getString(textRes)
                // The label says what the button does, so it is also the
                // accessibility name; a separate contentDescription would be a
                // second string to keep in step with it.
                gravity = Gravity.CENTER
                textSize = 15f
                setTextColor(getColor(textColorRes))
                background = getDrawable(backgroundRes)
                isClickable = true
                setOnClickListener { onClick() }
            }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.card_bg)
            setPadding(pad, pad, pad, pad)
            addView(
                TextView(this@BubbleService).apply {
                    text = getString(R.string.stt_unreachable_title)
                    textSize = 17f
                    setTextColor(getColor(R.color.dicta_on_surface))
                }
            )
            addView(
                TextView(this@BubbleService).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = gap }
                    text = getString(R.string.stt_unreachable_body)
                    textSize = 14f
                    setTextColor(getColor(R.color.dicta_on_surface_variant))
                    // 🔴 No maxLines and no ellipsize. The body carries the
                    // whole trade the user is deciding on, and a truncated
                    // sentence here would hide the half that says recording
                    // still works.
                }
            )
            addView(
                button(
                    R.string.stt_unreachable_record,
                    R.drawable.mic_pill_bg,
                    R.color.dicta_on_accent,
                    onRecord,
                )
            )
            addView(
                button(
                    R.string.stt_unreachable_cancel,
                    R.drawable.outline_button_bg,
                    R.color.dicta_on_surface,
                    onDecline,
                )
            )
        }
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
            if (recording) R.string.bubble_recording else R.string.bubble_active,
            recording,
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

    /**
     * @param recording drives the elapsed-time chronometer.
     *
     * 🔴 The chronometer is the mitigation for `#14` item 6, not decoration.
     * That item says a bubble left recording in a pocket uploads its audio, and
     * [BubbleDictation.MAX_RECORDING_MS]'s own rationale for existing is that
     * nothing on screen reminds you the mic is open. Raising the cap to ten
     * minutes multiplies that by five, so the honest answer is to make the one
     * surface that IS always visible say how long it has been running. A static
     * "Recording" string reads the same at 3 s and at 9 min.
     *
     * Platform `setUsesChronometer` + `setWhen`, so it ticks without this
     * service posting an update per second — which on a ten-minute recording
     * would be 600 notification updates and a wakeup each.
     */
    private fun buildNotification(textRes: Int, recording: Boolean = false): Notification {
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
            // 🔴 `setWhen` is the chronometer's ORIGIN, so it must be the
            // moment capture started, not now — rebuilding the notification
            // with `now` would restart the count at every promote and the
            // reading would always be ~0. `recordingStartedAtMs` is set once,
            // in startRecording — and, just as load-bearing, set there BEFORE
            // the promote that calls this, or the value read here is the
            // previous recording's origin (or `0L` on the first). See the
            // field's own kdoc.
            .setWhen(if (recording) recordingStartedAtMs else System.currentTimeMillis())
            .setShowWhen(recording)
            .setUsesChronometer(recording)
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

    /**
     * Tap vs. long press vs. drag.
     *
     * The *decision* is [BubbleGesture]'s and is pure; what is left here is
     * the timer, the pixels and the window move — device evidence only. The
     * `moved` boolean this replaced held the same information in a form that
     * no test could reach and that `ACTION_CANCEL` left stale.
     */
    private inner class DragListener : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var downX = 0
        private var downY = 0
        private val gesture = BubbleGesture()

        /**
         * The pending long press.
         *
         * Posted on `ACTION_DOWN` and removed on up, cancel and the move that
         * becomes a drag. It still asks [BubbleGesture] whether the timeout
         * counts — removing a `Handler` callback is best-effort (one already
         * dispatched cannot be recalled), so "the shell removed it" is not a
         * guarantee that it will not run.
         */
        private val longPress = Runnable {
            if (gesture.onTimeout(android.os.SystemClock.uptimeMillis()) ==
                BubbleGesture.Decision.LONG_PRESS
            ) {
                applyEffects(machine.on(BubbleDictation.Event.BubbleLongPressed))
            }
        }

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val slop = (12 * v.resources.displayMetrics.density).toInt()
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downX = params.x
                    downY = params.y
                    gesture.onDown(event.rawX, event.rawY, event.eventTime)
                    main.removeCallbacks(longPress)
                    main.postDelayed(longPress, BubbleGesture.LONG_PRESS_MS)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val decision = gesture.onMove(
                        event.rawX, event.rawY, slop
                    )
                    if (decision == BubbleGesture.Decision.DRAG) {
                        // Travel past slop means this is a reposition, so the
                        // pending reveal is abandoned: a drag that also opened
                        // the history row would be two actions for one gesture.
                        main.removeCallbacks(longPress)
                        val dx = (event.rawX - downRawX).toInt()
                        val dy = (event.rawY - downRawY).toInt()
                        val metrics = v.resources.displayMetrics
                        // 🔴 Call site 1 of 3 for the clamp. Unbounded before:
                        // the window could be dragged clean off the bottom of
                        // the display and taken with it the only control that
                        // can stop a recording.
                        params.x = BubbleChrome.clampX(
                            downX - dx, // END gravity: x grows leftward
                            windowWidthPx(),
                            metrics.widthPixels,
                        )
                        params.y = BubbleChrome.clampY(
                            downY + dy,
                            windowHeightPx(),
                            metrics.heightPixels,
                        )
                        (root as? ViewGroup)?.let {
                            runCatching { wm.updateViewLayout(it, params) }
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (gesture.onUp() == BubbleGesture.Decision.TAP) {
                        v.performClick()
                    }
                    return true
                }
                // 🔴 Previously fell through to `return false`, which left
                // `moved` stale AND — once there is a timer — left a reveal
                // armed to appear after the finger is gone. The window manager
                // takes a gesture away for reasons the app does not control:
                // another window taking focus, the shade coming down.
                MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    gesture.onCancel()
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

        /**
         * How many chips are **on screen at once**. The reveal SCROLLS, so this
         * is the window's height in chips, not how many items it offers — that
         * is [REVEAL_MAX_ITEMS].
         *
         * 🔴 **Keeping this at 4 is what makes scrolling a contained change.**
         * The window grows by `min(count, REVEAL_SLOTS) * chipBlockPx`, so for
         * any history of 4 or fewer the geometry is identical to the
         * pre-scroll build and every piece of clamp arithmetic — including
         * `#17` audit round 1's F4 fix — keeps its existing evidence. Raising
         * it re-opens all of that, so raise it deliberately or not at all.
         *
         * ⚠ This kdoc used to read *"tying them would put fifty chips on
         * screen. A full history screen is a separate feature and is not this
         * one."* The first half is **retired**: fifty chips are now reachable
         * precisely because only four are ever laid out. The second half
         * stands — this is a quick-access affordance, not a browser.
         */
        private const val REVEAL_SLOTS = 4

        /**
         * How many recent dictations the reveal offers, scrollably.
         *
         * Still deliberately separate from `HistoryRetention.MAX_ITEMS` (1000):
         * how much is *kept* and how much is worth scrolling from a thumb-sized
         * gesture are different questions. 50 is the operator's call
         * (2026-10-17) and is a one-line change — nothing depends on the value,
         * only on it being finite. It is also what bounds the store read:
         * [DictationHistoryStore.recent] stats only as many files as it
         * returns, so this constant is the syscall budget per reveal.
         */
        private const val REVEAL_MAX_ITEMS = 50

        /** One chip's height. 44 dp is the cancel button's, and the floor. */
        private const val CHIP_DP = 44
        private const val CHIP_GAP_DP = 6

        /**
         * The chip row's width. Wide enough for
         * [HistoryLabel.MAX_CHARS] at the chip's text size with margin, narrow
         * enough that the window still fits a short display in landscape —
         * both of which are claims only a device can settle, which is why the
         * reveal is on the device checklist rather than asserted here.
         */
        private const val REVEAL_WIDTH_DP = 220

        /**
         * The warning card's width, and the margin it keeps from the display
         * edges when the display is narrower than that. See
         * [warningLayoutParams] for why the width is explicit and the height
         * is not.
         */
        private const val WARNING_WIDTH_DP = 300
        private const val WARNING_MARGIN_DP = 24

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
