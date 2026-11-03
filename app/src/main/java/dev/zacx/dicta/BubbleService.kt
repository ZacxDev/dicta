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
 * The cancel button and the two reveal buttons are still children of the
 * bubble's window rather than windows of their own, for the reason each of
 * their fields gives. The warning dialog cannot be: see [warning].
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
     * The snip button inside the long-press reveal (issue #38).
     *
     * 🔴 A child of the existing window, added with `ViewGroup.addView` and
     * **never** a second `WindowManager.addView`: an overlay window is
     * composited into every MediaProjection frame, and
     * [OverlayWindowLedgerTest] asserts a per-FILE count of `addView` calls for
     * that reason. A third one fails it, correctly.
     *
     * 🔴 Positioned **below** the bubble, like [cancel] and [history], and
     * that is arithmetic rather than taste. With `Gravity.TOP` the window's top
     * edge is pinned at `y`, so a child added below the bubble needs no `y`
     * compensation at all and cannot move the bubble. **Since the chip list was
     * deleted, EVERY optional child is below the bubble**, which is what
     * removed the reveal's `y` arithmetic outright — see [showActions].
     *
     * What it DOES change is the window's height, so [snipBlockPx] is in
     * [windowHeightPx] exactly as `cancelBlockPx` and [historyBlockPx] are —
     * see that function.
     *
     * 🔴 Visibility is owned by [showActions] and by nothing else. This is not
     * a new always-on surface: it is visible while the reveal is up and gone
     * otherwise.
     */
    private var snip: ImageButton? = null

    /**
     * The history button inside the long-press reveal.
     *
     * 🔴 It REPLACED a scrolling `ListView` of history chips that lived in this
     * same window. That list, its adapter, its slot and width constants and all
     * of its height arithmetic are deleted — and the deletion closes a real
     * defect class by construction rather than merely removing lines: the chips
     * were loaded ASYNCHRONOUSLY, so a reveal dismissed while its store read
     * was in flight could leave a PERMANENT row on screen with
     * `FLAG_WATCH_OUTSIDE_TOUCH` armed device-wide (found by audit round 1 of
     * #17, and the whole reason a `revealGeneration` counter existed here). A
     * button needs no load, so there is nothing left to arrive late.
     *
     * Everything [snip]'s field says about being a child of the existing
     * window, about sitting below the bubble and about [showActions] owning its
     * visibility applies here identically — which is why it is said once,
     * there.
     *
     * 🔴 FIRST of the two, i.e. nearest the bubble. History is what the long
     * press meant before this change, so it is the affordance most likely to be
     * wanted by the finger that just performed it. Order relative to [cancel]
     * is bookkeeping rather than layout — see `buildWindow`.
     */
    private var history: ImageButton? = null

    /**
     * The box holding the bubble and its pulse ring.
     *
     * Kept as a field for one reason: the cancel button is positioned
     * *relative to the bubble* inside the root, and `indexOfChild(halo)` is the
     * only spelling of "above the bubble" that stays correct as the number of
     * optional children changes.
     *
     * ⚠ **The bubble IS the first child again**, now that the chip list is
     * gone, so `indexOfChild(halo)` and a literal `0` happen to agree today.
     * The spelling is kept anyway and that is deliberate: the previous
     * `addView(abort, 0)` was correct right up until a child was added above
     * the bubble, and it failed silently when one was — see [showCancel].
     */
    private var halo: View? = null

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
     * How much taller the window is while the snip button is shown — the
     * button plus the gap above it.
     *
     * 🔴 Feeds [BubbleChrome.clampY] through [windowHeightPx]. Leaving it out
     * is the mutation that re-opens the unreachable-control defect for the
     * bottom-most control: long-press with the bubble near the bottom edge and
     * the snip button lands off-screen, visible and untappable. That is the
     * same failure `cancelBlockPx` and [historyBlockPx] are each in the clamp
     * for, arrived at by a third route.
     */
    private var snipBlockPx = 0

    /**
     * How much taller the window is while the history button is shown — the
     * button plus the gap above it. [snipBlockPx]'s twin, and in the clamp for
     * the same reason.
     *
     * 🔴 **BOTH blocks, not one doubled value**, even though the two buttons
     * are the same size today. A single `actionsBlockPx = 2 * …` would be a
     * term whose correctness depends on the two dp constants staying equal,
     * with nothing saying so; two terms summed in [windowHeightPx] stay right
     * if either changes.
     */
    private var historyBlockPx = 0

    /**
     * 🔴 Whether the long-press action buttons are on screen.
     *
     * **An explicit flag, because there is no longer a view to read it off.**
     * "Is the reveal up?" used to be `historyRow.visibility == VISIBLE` — the
     * chip `ListView` WAS the source of truth, and the buttons' visibility was
     * derived from it. With the list deleted, the only candidates were "ask one
     * of the two buttons" or this. Nominating a button would make the other
     * one's state a derived fact about its sibling, so that a future third
     * control, or a button hidden for any other reason, silently redefines the
     * predicate. A field cannot be hidden by accident.
     *
     * 🔴 Written in [showActions] and nowhere else, which is the discipline
     * that function's own comment names: a second predicate elsewhere —
     * `renderState`, a click handler — is how a reveal-only button becomes an
     * always-on surface.
     */
    private var actionsShown = false

    /** Off-main-thread work: today the `/healthz` probe. See [runProbe]. */
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

    /** Where [healthProbe] runs. `null` means [worker]. */
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

    /*
     * 🔴 `historyProvider` AND `historyExecutor` WERE HERE AND ARE DELETED.
     *
     * They were the seams the chip reveal's async store read needed: a
     * provider, so a test driving the gesture was not also a test of the
     * filesystem, and a synchronous executor, so "long press, then assert" had
     * no race in it. This service no longer reads the history store at all —
     * the long press shows a button and the button starts an Activity.
     *
     * Both seams still exist, in the same shape, on the surface that does the
     * reading now: `HistoryPickerActivity.source` and
     * `FloatingPickerActivity.loadExecutor`. Deleted rather than left for a
     * future caller, because a seam with no production consumer is a
     * test-visible hook onto nothing.
     */

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
        snipBlockPx = ((SNIP_DP + SNIP_GAP_DP) * density).toInt()
        historyBlockPx = ((HISTORY_DP + HISTORY_GAP_DP) * density).toInt()

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

        // The reveal's second affordance (issue #38). Built mirroring `abort`
        // above for size and style — same bubble_bg, same on-surface tint, same
        // contentDescription-and-tooltip pairing — because it is the same KIND
        // of thing: a small round control hanging off the bubble.
        //
        // 🔴 No DragListener, for `abort`'s reason: the bubble is the drag
        // handle, and a drag that started here would open a picker when the
        // finger lifted.
        //
        // 🔴 The click goes through the machine, never straight to
        // startActivity — see BubbleDictation.Event.SnipTapped. That is what
        // puts the reveal away first, in the one place the hide predicate
        // lives.
        val snipButton = ImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (SNIP_DP * density).toInt(),
                (SNIP_DP * density).toInt(),
            ).apply { topMargin = (SNIP_GAP_DP * density).toInt() }
            setImageResource(R.drawable.ic_snip)
            imageTintList = android.content.res.ColorStateList
                .valueOf(getColor(R.color.dicta_on_surface))
            background = getDrawable(R.drawable.bubble_bg)
            val snipLabel = getString(R.string.bubble_snip)
            contentDescription = snipLabel
            tooltipText = snipLabel
            visibility = View.GONE
            setOnClickListener {
                applyEffects(machine.on(BubbleDictation.Event.SnipTapped))
            }
        }

        // The reveal's FIRST affordance, built mirroring `snipButton` above in
        // every respect — same 44 dp square, same bubble_bg, same on-surface
        // tint, same contentDescription-and-tooltip pairing, no DragListener,
        // the click through the machine. They are two of the same KIND of
        // thing, so a difference in any of those would read as a difference in
        // meaning.
        //
        // 🔴 It REPLACED the chip ListView that used to be here. See the
        // `history` field for the defect class that deletion closes.
        val historyButton = ImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (HISTORY_DP * density).toInt(),
                (HISTORY_DP * density).toInt(),
            ).apply { topMargin = (HISTORY_GAP_DP * density).toInt() }
            setImageResource(R.drawable.ic_history)
            imageTintList = android.content.res.ColorStateList
                .valueOf(getColor(R.color.dicta_on_surface))
            background = getDrawable(R.drawable.bubble_bg)
            val historyLabel = getString(R.string.bubble_history)
            contentDescription = historyLabel
            tooltipText = historyLabel
            visibility = View.GONE
            setOnClickListener {
                applyEffects(machine.on(BubbleDictation.Event.HistoryTapped))
            }
        }

        // 🔴 THE CHIP `ListView` WAS BUILT HERE AND IS DELETED, along with its
        // adapter, its chip views, `REVEAL_SLOTS`, `REVEAL_WIDTH_DP`,
        // `CHIP_DP`/`CHIP_GAP_DP`, `REVEAL_WIDTH_DP`'s width arithmetic and the
        // whole of `renderReveal`. History is a BUTTON now; the list, the
        // search field and the scrolling live in `HistoryPickerActivity`.
        //
        // Three arguments died with it and are recorded because they were real:
        // that a vertical stack beat a horizontal strip (four chips side by
        // side is ~600 dp, wider than any phone); that a framework `ListView`
        // beat both a `RecyclerView` (forbidden — `app/build.gradle.kts` has an
        // EMPTY runtime `dependencies {}` block by explicit decision) and a
        // `ScrollView` (scrolls without recycling); and that the list's height
        // had to be set EXPLICITLY, never `WRAP_CONTENT`, or the window
        // unbounds. 🔴 The second of those is NOT retracted — it is why the
        // picker's own list is a framework `ListView` too, and it is restated
        // on `FloatingPickerActivity`.
        //
        // 🔴 What the deletion BUYS, beyond the lines: the chip list was loaded
        // asynchronously, so the reveal needed `revealGeneration` to stop a
        // late load showing a permanent row with `FLAG_WATCH_OUTSIDE_TOUCH`
        // armed device-wide (#17 audit round 1). See the `history` field.

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // 🔴 NOTHING IS ABOVE THE BUBBLE ANY MORE. The chip row used to sit
            // at index 0, which is what `showReveal`'s `y` compensation existed
            // for and what made "above the bubble" a real position in this
            // container. With it gone the bubble is the first child and all
            // three optional controls hang BELOW it, in this order: history,
            // snip, cancel.
            //
            // History before snip because history is nearest the bubble — see
            // the `history` field. Cancel last because `showCancel`'s "below"
            // arm appends at childCount.
            //
            // 🔴 Cancel's order relative to the other two is bookkeeping rather
            // than layout, and the argument now has to cover THREE controls
            // rather than two: cancel is RECORDING-only while history and snip
            // are reveal-only, and the reveal opens only in IDLE, so cancel is
            // never co-visible with either. The two reveal buttons ARE
            // co-visible with each other, which is why both their blocks are in
            // windowHeightPx.
            addView(bubbleBox)
            addView(historyButton)
            addView(snipButton)
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
        // empty arm and rides `on()`'s existing HideActions prefix, so the
        // "when do the buttons close" predicate stays in one place.
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
        snip = snipButton
        history = historyButton
        bubble = button
        halo = bubbleBox
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
        snip = null
        history = null
        halo = null
        // shutdownNow, like SettingsActivity: a probe in flight is worth
        // nothing once the window it belongs to is gone, and the executor is
        // non-daemon.
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
                BubbleDictation.Effect.ShowActions -> showActions(true)
                BubbleDictation.Effect.HideActions -> showActions(false)
                // 🔴 Issue #38. Not a recording path: this starts an activity
                // that reads the clipboard and writes it, and nothing else.
                // The HideActions the machine prefixes has already put the
                // reveal away by the time this runs — ordering the machine
                // owns, not this loop.
                BubbleDictation.Effect.OpenSnipPicker -> openSnipPicker()
                // 🔴 Also not a recording path. The picker it opens lists
                // dictations that are already recorded, and the one activity it
                // can start is the card through DictateLaunch.historyIntent —
                // which carries EXTRA_HISTORY_ID, so DictateActivity populates
                // from history instead of capturing. Same HideActions ordering
                // as the snip arm above.
                BubbleDictation.Effect.OpenHistoryPicker -> openHistoryPicker()
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

    /**
     * Opens the snippet picker (issue #38).
     *
     * 🔴 Through [SnipLaunch], never a hand-built `Intent` — the same mechanism
     * as [requestMicPermission] above, and the same bug
     * (#11) if it is skipped: a floating activity must land in its OWN task or
     * the translucent window renders over Dicta's own UI instead of over the
     * app the user is in. `SnipSeamLedgerTest`'s construction ledger is what
     * makes that a red test rather than a code review.
     *
     * 🔴 Deliberately NOT through [DictateLaunch]. That object builds
     * `DictateActivity` intents, a plain one of which auto-starts a capture —
     * which is why `DictateLaunchTest` keeps a one-file ledger of who may
     * construct them and `StartSurfaceLedgerTest` counts the plain ones. The
     * picker is a different activity with a different affinity and no
     * microphone, so it gets its own launcher and neither ledger grows.
     */
    private fun openSnipPicker() {
        runCatching { startActivity(SnipLaunch.intent(this)) }
    }

    /**
     * Opens the history picker.
     *
     * 🔴 Through [HistoryLaunch], never a hand-built `Intent`, and never
     * through [SnipLaunch] either — the three floating surfaces hold three
     * DIFFERENT `taskAffinity` values on purpose, and sharing one with
     * `singleTop` on both would stack them so that Back walks through a surface
     * the user never opened. [HistoryLaunch] owns that pairing with the
     * manifest.
     *
     * 🔴 Deliberately NOT through [DictateLaunch]. That object builds
     * `DictateActivity` intents, a plain one of which auto-starts a capture —
     * which is why `DictateLaunchTest` keeps a one-file ledger of who may
     * construct them and `StartSurfaceLedgerTest` counts the plain ones. This
     * opens a picker, which has no microphone; the picker itself later calls
     * `DictateLaunch.historyIntent`, which carries an extra and starts nothing.
     *
     * ⚠ This REPLACED `revealHistory()`, which read the store asynchronously
     * and then showed a row. There is no load here at all, which is why
     * `revealGeneration` and the async-race guard it armed are gone — see the
     * [history] field.
     */
    private fun openHistoryPicker() {
        runCatching { startActivity(HistoryLaunch.intent(this)) }
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

            /**
             * Forwarded to the bus like every other card-bound event, and
             * gated by [isCurrent] for the same reason: a superseded session's
             * id would arm the card's *Retry* against the PREVIOUS recording's
             * audio while the current recording's transcript is on screen.
             */
            override fun onHistorySaved(id: String) {
                if (!isCurrent()) return
                bus.onHistorySaved(id)
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
            // 🔴 "Above the bubble" is the bubble's own index, not 0 — and the
            // correction this comment used to carry is now HISTORY rather than
            // a live constraint, so it is stated as history. It read: "it was 0
            // while the bubble was the first child; the history row is now
            // index 0, and an unchanged `0` here would have put cancel ABOVE
            // the chips". That row is deleted, so the bubble IS the first child
            // again and `indexOfChild(box)` evaluates to 0 today.
            //
            // The spelling is kept rather than simplified back to `0`, because
            // `0` was correct until it silently was not: the failure mode is a
            // cancel button that is not adjacent to the control it cancels, on
            // the far side of whatever else got added above the bubble, and
            // nothing goes red for it. `indexOfChild` cannot be wrong that way.
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

    // --- the long-press action buttons ---------------------------------------

    /*
     * 🔴 WHAT WAS HERE AND IS DELETED, in one place so the next reader does not
     * look for it: `revealHistory`, `loadRecentHistory`, `renderReveal`,
     * `ChipModel`, `HistoryAdapter`, `newChip`, `bindChip`, `openHistoryItem`,
     * `revealGeneration`, `revealBlockPx`, `revealWidthPx`, `chipBlockPx` and
     * `revealRowMarginPx`. That was ~200 lines whose entire job was to put a
     * bounded, scrollable, recycling list of history chips inside a dragged
     * overlay window and keep `BubbleChrome.clampY` honest about its height.
     *
     * 🔴 THE `y` COMPENSATION WENT WITH IT, and that is the largest single
     * simplification in this change rather than an incidental one. `showReveal`
     * did `params.y -= revealBlockPx` on show and `+= revealBlockPx` on hide,
     * because the chip row sat ABOVE the bubble and `Gravity.TOP` pins the
     * window's TOP edge at `y` — so without it the bubble shifted down by the
     * row's height, out from under the finger still holding it. Four audit
     * rounds of #17 corrected the magnitudes in that argument, and round 1
     * turned the hide side into a recorded post-clamp shift that teleported the
     * bubble after a drag.
     *
     * None of it applies now: every optional child is BELOW the bubble, so the
     * window is monotonically downward-growing and `y` never moves on a reveal.
     * Three tests became UNNECESSARY rather than merely changed and are deleted
     * with it — `revealingTheHistoryDoesNotShoveTheBubbleDownTheScreen`,
     * `dismissingTheRowAfterADragLeavesTheBubbleWhereTheUserDraggedIt` and
     * `aRevealNearTheTopEdgeDropsTheBubbleOnceAndThenStopsMoving`.
     *
     * 🔴 THE CLAMP IS STILL MANDATORY. Two 44 dp buttons with 6 dp gaps under
     * an 84 dp halo is ~184 dp of window, and a bubble parked near the bottom
     * edge still puts the lower button off-screen without [BubbleChrome.clampY]
     * — the unreachable-control defect, unchanged, from a shorter window.
     */

    /**
     * Shows or hides the long-press action buttons — history and snip.
     *
     * 🔴 **One predicate, written here and read nowhere else.** [actionsShown]
     * is this function's field; both buttons' visibilities are set in BOTH arms,
     * together, every time. A second predicate elsewhere — in `renderState`, in
     * a click handler — is how a reveal-only control becomes an always-on
     * surface, which is the one thing issue #38 and this repo's "everything the
     * user is shown derives from one predicate" discipline both forbid.
     *
     * 🔴 **No `y` compensation, and that is not an omission.** Both buttons are
     * below the bubble and `Gravity.TOP` pins the window's top edge, so a child
     * added below cannot move the bubble. What the reveal DOES change is the
     * window's HEIGHT, which [windowHeightPx] reports and [clampWindow] acts
     * on. See the deletion note above for the arithmetic this replaced.
     *
     * Idempotent, because the machine's own dismissal can arrive for a reveal
     * that is already away: a button tap hides the buttons through the machine's
     * [BubbleDictation.Effect.HideActions] prefix, and a later event emits that
     * prefix again only if the flag is still set — but `showActions(false)` is
     * reached from the effect loop either way.
     */
    private fun showActions(show: Boolean) {
        if (root == null) return
        if (actionsShown == show) return
        actionsShown = show
        val visibility = if (show) View.VISIBLE else View.GONE
        history?.visibility = visibility
        snip?.visibility = visibility
        // 🔴 Scoped to the revealed state, NOT set once at window creation.
        // FLAG_WATCH_OUTSIDE_TOUCH delivers an ACTION_OUTSIDE for every touch
        // anywhere on the device, so leaving it on would wake this service on
        // each tap for the ~100% of its life when there is nothing to dismiss.
        //
        // Bitwise against the live flags, never an assignment: `visibilitySink`
        // toggles FLAG_NOT_TOUCHABLE on this same field.
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
            // 🔴 Both reveal buttons grow the window DOWNWARD, so neither ever
            // shifts `y` — but each is every bit as much part of the height
            // clampY has to know about. Omitting EITHER is the mutation that
            // leaves the bottom-most control off-screen on a bubble parked near
            // the bottom edge: a button the user can see the top pixel of and
            // cannot tap.
            //
            // 🔴 Read off each button's OWN visibility rather than off
            // `actionsShown`, deliberately. The flag is what DECIDES the
            // reveal; this is what the window actually CONTAINS, so a button
            // left behind by a partial update is visible to the clamp rather
            // than masked by the intention that it should not be there.
            (if (history?.visibility == View.VISIBLE) historyBlockPx else 0) +
            (if (snip?.visibility == View.VISIBLE) snipBlockPx else 0)

    /**
     * The window's width.
     *
     * 🔴 **Unconditionally the halo, and that is a SIMPLIFICATION the chip
     * deletion bought.** This used to be
     * `if (historyRow visible) maxOf(haloSizePx, revealWidthPx) else haloSizePx`
     * because the chip row was a fixed 220 dp — far wider than the 84 dp halo —
     * so revealing it widened the window and [BubbleChrome.clampX] had to know.
     * Every optional child is now a 44 dp button, narrower than the halo, so
     * the window's width never changes and there is nothing left to branch on.
     *
     * Kept as a function rather than inlined at the two `clampX` call sites:
     * what is being named is "the width the clamp must use", and a future child
     * wider than the halo then has one place to say so.
     */
    private fun windowWidthPx(): Int = haloSizePx

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
         * The snip button's size and the gap above it — the cancel button's
         * numbers, on purpose.
         *
         * 44 dp is this project's tap-target floor (it is `CANCEL_DP` and
         * `HISTORY_DP` as well), and the controls hang off the bubble in the
         * same place, so a different size would read as a different kind of
         * thing. Their sum is `snipBlockPx`, which is in the clamp.
         */
        private const val SNIP_DP = 44
        private const val SNIP_GAP_DP = 6

        /**
         * The history button's size and the gap above it — [SNIP_DP] and
         * [SNIP_GAP_DP], for the reason given there.
         *
         * 🔴 Its OWN pair of constants rather than reusing the snip ones, and
         * that is the same decision `historyBlockPx` records: the two buttons
         * being the same size is a fact about today's design, not a rule, and
         * spelling it as one shared constant would make a future change to
         * either silently change both.
         */
        private const val HISTORY_DP = 44
        private const val HISTORY_GAP_DP = 6

        /*
         * 🔴 `REVEAL_SLOTS`, `REVEAL_MAX_ITEMS`, `CHIP_DP`, `CHIP_GAP_DP` AND
         * `REVEAL_WIDTH_DP` WERE HERE AND ARE DELETED.
         *
         * All five existed to serve a bounded, scrolling chip list inside this
         * window. Two of their arguments are worth carrying forward because
         * they were loudly stated and are now FALSE:
         *
         *  * `REVEAL_SLOTS`'s kdoc said *"this is a quick-access affordance,
         *    not a browser"*, and that was the design's own statement of
         *    intent. 🔴 **The scope changed, knowingly, on 2026-11-03**: a
         *    search field over a scrollable Activity IS the browser that
         *    sentence says it is not. Retired here rather than deleted
         *    silently, so the next reader does not think it still holds. See
         *    `HistoryPickerActivity`.
         *  * `REVEAL_MAX_ITEMS` was **50**, the operator's call of 2026-10-17,
         *    and its argument was a syscall budget: `recent(limit)` stats one
         *    file per item returned, and 50 was what a thumb gesture should
         *    pay. 🔴 **That became a silent wrong answer the moment history got
         *    a SEARCH FIELD** — searching the newest 50 of up to 1000 retained
         *    items reports "nothing matches" for a dictation that is on disk,
         *    and the operator's device already holds 94. The replacement is
         *    `HistoryPickerActivity.MAX_ITEMS`, which is
         *    `HistoryRetention.MAX_ITEMS` rather than a number of its own, so
         *    no value of it can hide a retained item.
         */

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
