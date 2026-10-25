package dev.zacx.dicta

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import java.time.Duration

/**
 * The pre-recording reachability gate, driven through the real
 * [BubbleService].
 *
 * 🔴 **Most of this file is the FAIL-OPEN contract, and that is the regression
 * coverage.** `TranscriptionCoordinator` already handles an unreachable server
 * after a recording: a fast retry ladder, then the on-device transcript, then a
 * `/healthz` recovery poll that replaces the text if the server comes back. So
 * with the mesh down the user still gets a dictation today, and a gate that
 * blocked the start — or that warned when it did not know — would trade that
 * working degraded path for nothing. A suite that only asserted "the dialog
 * appears" would pass with every fail-open branch inverted.
 *
 * ## 🔴 What this file CANNOT establish, stated so a pass is not over-read
 *
 * The dialog is a `TYPE_APPLICATION_OVERLAY` window. Robolectric records the
 * `addView` and inflates the view tree, so the window's existence, its layout
 * params, its flags and its buttons' behaviour are all observable here —
 * **nothing about how it looks is.** `TextPaint.measureText` returns a flat
 * 1.0 px per character in this environment and `Layout.getEllipsisCount`
 * returns 0 for any string, so whether the body text wraps, fits or is clipped
 * is not assertable at all and is not asserted. That the card is legible over
 * an arbitrary app, that an outside tap really reaches the app underneath, and
 * that `uiautomator` cannot see the window (it cannot see any
 * `TYPE_APPLICATION_OVERLAY` window) are device observations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BubbleUnreachableWarningTest {

    private var controller: ServiceController<BubbleService>? = null

    private val app get() = RuntimeEnvironment.getApplication()

    /** Records what the service asked of the session. As [BubbleServiceFlowTest]. */
    private class FakeSession : BubbleService.SessionHandle {
        var starts = 0
        var submits = 0
        var discards = 0

        override fun start(): Boolean {
            starts++
            return true
        }

        override fun submit() {
            submits++
        }

        override fun discard() {
            discards++
        }

        override fun destroy() = Unit
    }

    /**
     * A reversible stand-in for [KeystoreCipher], which has no Robolectric
     * implementation — so a token cannot be *stored* off-device, and
     * `isRemoteConfigured` is `remoteEnabled && hasToken`. With this the test
     * drives the **production** predicate over real `SharedPreferences` rather
     * than a boolean stand-in for it.
     */
    private class ReversingCipher : SecretCipher {
        override fun encrypt(plaintext: String): String = "enc:" + plaintext.reversed()

        override fun decrypt(blob: String): String? =
            blob.removePrefix("enc:").reversed().takeIf { blob.startsWith("enc:") }
    }

    private val fake = FakeSession()

    /** Every probe the service dispatched, by base URL. */
    private val probed = mutableListOf<String>()

    private var clock = 5_000_000L
    private val reachability = SttReachability(now = { clock })

    @After
    fun tearDown() {
        runCatching { controller?.destroy() }
        OverlayVisibility.clearForTest()
        BubbleSessionBus.clearForTest()
        MicrophoneOwner.clearForTest()
    }

    // --- fixtures -------------------------------------------------------------

    private var prefsSeq = 0

    private fun settings(
        remoteEnabled: Boolean = true,
        withToken: Boolean = true,
        baseUrl: String? = null,
    ): SttSettings {
        val p = app.getSharedPreferences("reach_prefs_${prefsSeq++}", Context.MODE_PRIVATE)
        p.edit().clear().commit()
        val s = SttSettings(p, TokenStore(p, ReversingCipher()))
        if (withToken) s.tokens.putToken("tok-7f3a91c4")
        s.remoteEnabled = remoteEnabled
        if (baseUrl != null) s.baseUrl = baseUrl
        return s
    }

    private fun start(
        settings: SttSettings = settings(),
        probe: (String) -> Boolean = { true },
    ): BubbleService {
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val c = Robolectric.buildService(BubbleService::class.java)
        controller = c
        c.get().apply {
            sessionFactory = { fake }
            reachability = this@BubbleUnreachableWarningTest.reachability
            settingsProvider = { settings }
            healthProbe = { url -> probed.add(url); probe(url) }
            // Synchronous, so "the probe ran" has no race in it. Same split as
            // historyExecutor: where it runs is orthogonal to what it returns.
            probeExecutor = java.util.concurrent.Executor { it.run() }
        }
        return c.create().get()
    }

    /** Records one definite result into the owner at the current [clock]. */
    private fun record(reachable: Boolean) {
        val token = requireNotNull(reachability.beginProbe())
        reachability.onProbeResult(token, reachable)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun idleFor(ms: Long) =
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun field(service: BubbleService, name: String): Any? {
        val f = BubbleService::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(service)
    }

    private fun bubble(service: BubbleService): View =
        requireNotNull(field(service, "bubble") as View?) { "no bubble view" }

    private fun dialog(service: BubbleService): View? = field(service, "warning") as View?

    private fun buttonSaying(service: BubbleService, textRes: Int): View {
        val root = requireNotNull(dialog(service)) { "no warning dialog is up" }
        val label = app.getString(textRes)
        return requireNotNull(findByText(root, label)) {
            "the warning dialog has no control labelled <$label>"
        }
    }

    private fun findByText(view: View, text: String): View? {
        if (view is TextView && view.text?.toString() == text && view.isClickable) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findByText(view.getChildAt(i), text)?.let { return it }
            }
        }
        return null
    }

    // --- the warning path -----------------------------------------------------

    @Test
    fun aFreshDefiniteFailureWarnsInsteadOfRecording() {
        val service = start()
        idle()
        record(false)

        bubble(service).performClick()

        assertNotNull(
            "a tap with a fresh definite failure cached must raise the warning",
            dialog(service),
        )
        assertEquals(
            "and must NOT have opened the microphone — the whole point is that " +
                "the user decides first",
            0,
            fake.starts,
        )
        assertNull(
            "nor started an activity: the transcription card belongs to tap 2",
            shadowOf(service).nextStartedActivity,
        )
    }

    @Test
    fun thePrimaryButtonRecords() {
        val service = start()
        idle()
        record(false)
        bubble(service).performClick()

        buttonSaying(service, R.string.stt_unreachable_record).performClick()

        assertEquals(
            "\"Record anyway\" must start exactly the recording the tap asked " +
                "for. 🔴 Never refusing to start is the operator's first " +
                "decision: with the server down the fallback ladder still " +
                "produces a transcript from the phone's own recognizer.",
            1,
            fake.starts,
        )
        assertNull(
            "and the dialog must come down with it",
            dialog(service),
        )
    }

    @Test
    fun theSecondaryButtonStartsNothingAndLeavesTheBubbleTappable() {
        val service = start()
        idle()
        record(false)
        bubble(service).performClick()

        buttonSaying(service, R.string.stt_unreachable_cancel).performClick()

        assertEquals("\"Don't record\" must start no capture", 0, fake.starts)
        assertEquals("and submit nothing", 0, fake.submits)
        assertNull("and take the dialog down", dialog(service))

        // 🔴 The machine moved to RECORDING when the withheld StartRecording
        // was emitted, so declining has to drive it back to IDLE. If it did
        // not, this next tap would read as tap 2 — stop-and-submit — and would
        // open a transcription card for a recording that never happened.
        reachability.forget()
        bubble(service).performClick()
        assertEquals(
            "after declining, the next tap must start a recording. A tap that " +
                "instead submitted would mean the machine was left in " +
                "RECORDING with no session and nothing on screen saying so.",
            1,
            fake.starts,
        )
        assertEquals(0, fake.submits)
    }

    @Test
    fun aSecondBubbleTapWhileTheDialogIsUpIsIgnored() {
        val service = start()
        idle()
        record(false)
        bubble(service).performClick()
        assertNotNull(dialog(service))

        // 🔴 PAST THE DEBOUNCE, and this line is the whole test.
        //
        // Measured: without it, deleting `if (warning != null) return` from
        // BubbleService.tapped() leaves the full 691-test suite GREEN. Two
        // clicks inside BubbleDictation.MIN_RECORDING_MS are swallowed by the
        // machine's own debounce, which produces exactly the observable this
        // test asserts — so every assertion below held for a reason that had
        // nothing to do with the guard, and the guard was covered by nothing.
        //
        // A real sleep because the machine's clock is System.currentTimeMillis
        // and a Service does not inject it; advancing Robolectric's scheduler
        // does not move it. BubbleServiceFlowTest does the same, for the same
        // reason.
        Thread.sleep(BubbleDictation.MIN_RECORDING_MS + 50)

        bubble(service).performClick()

        assertEquals(
            "the bubble is still on screen and still tappable underneath the " +
                "dialog. The machine already holds the tap that opened it, so a " +
                "second tap would read as stop-and-submit and open the card for " +
                "a recording that never started.",
            0,
            fake.submits,
        )
        assertEquals(0, fake.starts)
        assertNull(
            "🔴 the discriminating assertion. Past the debounce, a second tap " +
                "that reached the machine emits SubmitRecording AND " +
                "OpenOverlayTranscribing — and the submit is INVISIBLE here " +
                "(there is no session to submit, so fake.submits stays 0), " +
                "which leaves the opened card as the only observable that can " +
                "tell the guard from the debounce.",
            shadowOf(service).nextStartedActivity,
        )
        assertNotNull("and the dialog must still be up", dialog(service))
    }

    @Test
    fun theDialogWindowIsSizedExplicitlyInWidthAndIsNotFocusable() {
        val service = start()
        idle()
        record(false)
        bubble(service).performClick()

        @Suppress("UNCHECKED_CAST")
        val p = requireNotNull(
            field(service, "warningParams") as android.view.WindowManager.LayoutParams?
        )

        assertEquals(
            "TYPE_APPLICATION_OVERLAY, so the dialog works over any app",
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            p.type,
        )
        assertTrue(
            "🔴 the WIDTH must be an explicit pixel value, never WRAP_CONTENT " +
                "or MATCH_PARENT. It is what decides where the body text wraps, " +
                "and a WRAP_CONTENT width makes that a function of how the " +
                "platform breaks a paragraph. (The HEIGHT is " +
                "deliberately WRAP_CONTENT — see warningLayoutParams; a fixed " +
                "height is the thing that clips, and nothing in this " +
                "environment can measure text to choose one.)",
            p.width > 0,
        )
        assertTrue(
            "the card must fit the display it is centred on",
            p.width <= app.resources.displayMetrics.widthPixels,
        )
        assertEquals(
            "WRAP_CONTENT height, measured against the full display",
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            p.height,
        )
        assertTrue(
            "FLAG_NOT_FOCUSABLE: a focusable overlay takes input focus from " +
                "whatever app is underneath and closes its IME. Buttons need " +
                "touches, not focus.",
            p.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0,
        )
        assertEquals(
            "🔴 no FLAG_WATCH_OUTSIDE_TOUCH. An outside tap on a two-button " +
                "decision has no honest meaning — \"cancel\" and \"record\" " +
                "both commit the user to an outcome they did not pick. The " +
                "window is sized to its card, so a tap outside goes to the app " +
                "underneath and the dialog stays until a button is pressed.",
            0,
            p.flags and
                android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        )
    }

    // --- fail open: one case per branch ---------------------------------------

    @Test
    fun neverProbedProceedsWithoutAWarning() {
        val service = start(probe = { true })
        // 🔴 A fresh owner installed AFTER create, not "don't idle the looper":
        // `ServiceController.create()` drains the main looper in this
        // environment, so the probe `onCreate` posts has already landed by the
        // time control returns — measured, this test asserted the opposite
        // first and failed. Installing a never-probed owner here reproduces the
        // state a tap can genuinely arrive in: the service starts, the user
        // taps before any probe reports.
        service.reachability = SttReachability(now = { clock })
        assertTrue(
            "precondition: nothing may be known yet, or this is testing some " +
                "other branch",
            !service.reachability.hasKnowledge(),
        )

        bubble(service).performClick()

        assertNull("an owner with no knowledge must not warn", dialog(service))
        assertEquals("it must record", 1, fake.starts)
    }

    @Test
    fun aProbeInFlightProceedsWithoutAWarning() {
        val service = start()
        idle()
        record(false)
        // A probe claimed and not yet reported — the periodic one overlapping
        // the tap.
        assertNotNull(reachability.beginProbe())

        bubble(service).performClick()

        assertNull(
            "a probe in flight must not warn even over a fresh definite " +
                "failure: current knowledge is being revised, and a tap must " +
                "never block waiting on a network call",
            dialog(service),
        )
        assertEquals(1, fake.starts)
    }

    @Test
    fun aStaleFailureProceedsWithoutAWarning() {
        val service = start()
        idle()
        record(false)
        clock += reachability.freshnessWindowMs() + 3_333L

        bubble(service).performClick()

        assertNull(
            "a definite failure older than the freshness window is UNKNOWN, " +
                "not a failure. The clock is injected; nothing here sleeps.",
            dialog(service),
        )
        assertEquals(1, fake.starts)
    }

    @Test
    fun aThrownProbeProceedsWithoutAWarning() {
        val service = start(probe = { throw IllegalStateException("probe blew up") })
        idle()

        assertEquals("the probe must have been attempted", 1, probed.size)
        assertTrue(
            "🔴 a throw must record NOTHING. SttHttp.healthz already maps every " +
                "transport failure to false and cannot throw, so an exception " +
                "escaping the probe is a fault in this process and carries no " +
                "observation about the server — recording false for it would " +
                "mint the one input that warns out of a local bug.",
            !reachability.hasKnowledge(),
        )

        bubble(service).performClick()

        assertNull(dialog(service))
        assertEquals(1, fake.starts)
    }

    @Test
    fun remoteDisabledProceedsAndNeverProbes() {
        val service = start(settings = settings(remoteEnabled = false))
        // Seeded BEFORE any probe, so the only thing that can suppress the
        // dialog is the setting.
        record(false)
        idle()

        assertEquals(
            "🔴 with stt_remote_enabled off there is no server in the picture: " +
                "RemoteTranscriber refuses before it opens a socket, so a probe " +
                "would be a network call on behalf of a feature the user turned " +
                "off. Probes made: $probed",
            emptyList<String>(),
            probed.toList(),
        )

        bubble(service).performClick()

        assertNull(
            "and nothing to warn about, even with a fresh definite failure " +
                "cached from before the setting changed",
            dialog(service),
        )
        assertEquals(1, fake.starts)
    }

    @Test
    fun noStoredTokenProceedsAndNeverProbes() {
        val service = start(settings = settings(withToken = false))
        record(false)
        idle()

        assertEquals(
            "🔴 the TOKEN half of isRemoteConfigured is load-bearing here even " +
                "though /healthz is unauthenticated. With no token the upload " +
                "path refuses and the recording goes to the phone whatever the " +
                "probe says — so warning would be the one claim the dialog " +
                "never makes, since a reachable server never warns.",
            emptyList<String>(),
            probed.toList(),
        )

        bubble(service).performClick()

        assertNull(dialog(service))
        assertEquals(1, fake.starts)
    }

    @Test
    fun theBaseUrlIsNeverBlankSoNoEmptyUrlBranchIsNeeded() {
        // 🔴 There is deliberately no "unset base URL" guard in BubbleService.
        // SttSettings.baseUrl cannot return blank — it falls back to
        // SttEndpoints.DEFAULT_BASE_URL — so such a guard would be unreachable
        // code with a comment claiming it protects something. The precondition
        // is held HERE instead, at the one place that can hold it.
        val s = settings(baseUrl = "   ")
        assertEquals(
            "a blank base URL must fall back to the default, so the probe " +
                "always has a URL to call",
            SttEndpoints.DEFAULT_BASE_URL,
            s.baseUrl,
        )

        start(settings = s)
        idle()
        assertEquals(
            "and that is the URL the probe was given",
            listOf(SttEndpoints.DEFAULT_BASE_URL),
            probed.toList(),
        )
    }

    // --- the schedule and the lifecycle ---------------------------------------

    @Test
    fun theProbeRunsImmediatelyWhenTheServiceStarts() {
        start(settings = settings(baseUrl = "http://198.51.100.7:9999"))
        idle()
        assertEquals(
            "🔴 immediately, not after one interval. A bubble that has been up " +
                "for two seconds holds no knowledge, which is a PROCEED — so " +
                "without this the warning is unavailable for the first minute " +
                "of every service life, and the service restarts on every boot " +
                "and every upgrade.",
            listOf("http://198.51.100.7:9999"),
            probed.toList(),
        )
    }

    @Test
    fun theProbeRepeatsOnTheOrdinaryCadenceWhileTheServiceLives() {
        start()
        idle()
        assertEquals(1, probed.size)

        idleFor(SttReachability.PROBE_INTERVAL_MS)
        assertEquals("one probe per interval", 2, probed.size)
        idleFor(SttReachability.PROBE_INTERVAL_MS)
        assertEquals(3, probed.size)
    }

    @Test
    fun theCadenceBacksOffAfterConsecutiveFailures() {
        start(probe = { false })
        idle()
        repeat(SttReachability.BACKOFF_AFTER_FAILURES - 1) {
            idleFor(SttReachability.PROBE_INTERVAL_MS)
        }
        assertEquals(
            "precondition: the ordinary cadence up to the backoff threshold",
            SttReachability.BACKOFF_AFTER_FAILURES,
            probed.size,
        )

        val before = probed.size
        // Several ordinary intervals, well short of the backed-off one.
        idleFor(SttReachability.PROBE_INTERVAL_MS * 3 + 777L)
        assertEquals(
            "after ${SttReachability.BACKOFF_AFTER_FAILURES} consecutive " +
                "failures the next probe is a backoff interval away, so three " +
                "ordinary intervals must produce none. Hammering a host that " +
                "has refused that many times in a row is a battery cost with " +
                "no information in it.",
            before,
            probed.size,
        )

        idleFor(SttReachability.BACKOFF_INTERVAL_MS)
        assertEquals(
            "and one does arrive a backoff interval on — a backed-off schedule " +
                "must not be a stopped one, or the server coming back is never " +
                "noticed",
            before + 1,
            probed.size,
        )
    }

    @Test
    fun noProbeRunsWhileTheScreenIsOff() {
        val service = start()
        idle()
        val before = probed.size

        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        idleFor(SttReachability.PROBE_INTERVAL_MS * 2)

        assertEquals(
            "🔴 no probe while the phone is not interactive, and no wakelock " +
                "anywhere. The mesh tunnel drops while the phone sleeps, so a " +
                "probe then reports a failure that says nothing about whether " +
                "the server is up — and recording it would arm the warning for " +
                "the first tap after the screen comes back on.",
            before,
            probed.size,
        )

        // And the schedule is not dead: it re-arms when the screen returns.
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        service.onReceiveForTest(Intent(Intent.ACTION_SCREEN_ON))
        idle()
        assertEquals(
            "the screen coming back on is the moment before the user's next " +
                "tap, so it probes immediately rather than waiting out the " +
                "interval",
            before + 1,
            probed.size,
        )
    }

    @Test
    fun theScreenGoingOffDropsWhatIsKnown() {
        val service = start()
        idle()
        record(false)
        assertEquals(SttReachability.Verdict.WARN, reachability.verdict())

        service.onReceiveForTest(Intent(Intent.ACTION_SCREEN_OFF))

        assertTrue(
            "🔴 ACTION_SCREEN_OFF must DROP the cache, not merely stop the " +
                "chain. A result taken before the phone slept would otherwise " +
                "sit there ageing out on a clock that keeps running while the " +
                "probes do not — and it would warn on the first tap after the " +
                "screen comes back.",
            !reachability.hasKnowledge(),
        )

        bubble(service).performClick()
        assertNull(dialog(service))
        assertEquals(1, fake.starts)
    }

    @Test
    fun theScreenReceiverIsRegisteredWhileTheServiceLivesAndUnregisteredOnDestroy() {
        start()
        idle()
        assertEquals(
            "exactly one screen receiver while the service is up. " +
                "ACTION_SCREEN_ON/OFF are protected broadcasts the platform " +
                "delivers only to a runtime-registered receiver, so it cannot " +
                "be declared in the manifest and has to be torn down by hand.",
            1,
            serviceOwnedReceivers(),
        )

        controller?.destroy()
        controller = null

        assertEquals(
            "a receiver left registered past onDestroy is a leak this " +
                "project's audits look for — and it would hold a reference to " +
                "a destroyed service",
            0,
            serviceOwnedReceivers(),
        )
    }

    @Test
    fun theProbeStopsOnDestroy() {
        start()
        idle()
        val before = probed.size

        controller?.destroy()
        controller = null

        idleFor(SttReachability.PROBE_INTERVAL_MS * 4)
        assertEquals(
            "the periodic probe is a SELF-RESCHEDULING chain, so one surviving " +
                "tick keeps probing against a destroyed service for the life " +
                "of the process",
            before,
            probed.size,
        )
    }

    /*
     * ⚠ A LABEL ON THE TEST ABOVE, not a defect in it.
     *
     * `theProbeStopsOnDestroy` asserts an OUTCOME and attributes it to
     * nothing. Two independent mechanisms produce that outcome —
     * `onDestroy`'s `main.removeCallbacksAndMessages(null)` drops the pending
     * tick, and `runProbe`'s `root == null` branch returns early if one
     * survives — and deleting either ONE of them leaves this whole suite
     * green (measured, twice). Only removing both would turn this test red.
     *
     * That is stated rather than fixed. Attributing it would mean a seam whose
     * only purpose is to disable one mechanism so the other can be observed,
     * which tests the seam. The outcome is the thing that matters and it is
     * genuinely pinned; what must not be claimed is that this test proves
     * either line works. Both source sites carry the same sentence.
     */

    @Test
    fun aPendingProbeCannotTouchADestroyedServicesViews() {
        val service = start()
        idle()
        record(false)
        bubble(service).performClick()
        assertNotNull("precondition: the dialog is up", dialog(service))

        controller?.destroy()
        controller = null

        assertNull(
            "onDestroy must take the dialog window down and drop the " +
                "reference. A second overlay window left added is Dicta's own " +
                "UI composited into every MediaProjection frame — PR #4's bug — " +
                "and a retained View holds the destroyed service's context.",
            dialog(service),
        )
        idleFor(SttReachability.PROBE_INTERVAL_MS * 2)
    }

    // --- the dialog is a SECOND overlay window, and must hide for a capture --

    /**
     * 🔴 REGRESSION: **the warning dialog stayed composited into every
     * captured frame.**
     *
     * The dialog is a second `TYPE_APPLICATION_OVERLAY` window, so
     * MediaProjection composites it into the user's screenshot exactly as it
     * did the bubble — PR #4's bug, in a window that arrived long after it.
     * `BubbleService.visibilitySink` drives both windows, and this is the only
     * thing that *observes* the dialog half.
     *
     * 🔴 Why this exists even though [OverlayWindowLedgerTest] already names
     * the hazard: that file counts `addView` call sites per source file and
     * asserts `BubbleService.kt to 2`. The count is satisfied by a second
     * `addView` whether or not the window it opens is ever hidden, so its
     * error text — "drive it from the SAME OverlayVisibility sink" — claimed
     * coverage its implementation did not provide. **Measured:** deleting the
     * whole `val dialog = warning … updateViewLayout(dialog, dialogParams)`
     * block (692 bytes) SURVIVED the full green suite at `03e2d02`, while
     * deleting the bubble half's two lines was killed five times over by
     * [BubbleHiddenWhileTheCardIsUpTest]. Only the bubble was guarded.
     *
     * Driven through `OverlayVisibility.Holder.CAPTURE` — the holder
     * `DictaWindows.hideForCapture()` sets, which is the real capture seam —
     * rather than by calling the sink directly, so this cannot pass by
     * asserting that a private lambda behaves as written.
     *
     * What it asserts is the **window state Dicta asks for**: the dialog's
     * `params.alpha`, its `FLAG_NOT_TOUCHABLE` bit and its root visibility.
     * Nothing here composites, so "the dialog is genuinely absent from the
     * captured frame" remains a device claim — the same boundary this file's
     * header and [BubbleHiddenWhileTheCardIsUpTest] both draw.
     */
    @Test
    fun theWarningDialogLeavesTheScreenForACaptureAndComesBack() {
        val service = start()
        idle()
        record(false)
        bubble(service).performClick()
        val view = requireNotNull(dialog(service)) { "precondition: no dialog is up" }

        // Precondition, asserted: the dialog starts ON screen. Without this the
        // test would pass on a dialog that was never visible in the first
        // place — the vacuous direction.
        assertDialogIsOnScreen(service, view, "precondition — before any capture")

        OverlayVisibility.setHidden(OverlayVisibility.Holder.CAPTURE, true)
        idle()

        assertDialogIsOffScreen(
            service, view,
            "a MediaProjection capture is in flight and the warning dialog is " +
                "STILL being composited into every frame of the user's " +
                "screenshot. It is a second overlay window; the bubble's own " +
                "hide does not cover it",
        )

        OverlayVisibility.setHidden(OverlayVisibility.Holder.CAPTURE, false)
        idle()

        // The other half, and not optional: a dialog left hidden is a decision
        // surface the user can neither see nor dismiss, with the recording
        // withheld behind it.
        assertDialogIsOnScreen(
            service, view,
            "the capture finished and the warning dialog never came back. The " +
                "recording is withheld until one of its buttons is pressed, so " +
                "an invisible dialog strands the user with no way to proceed",
        )
    }

    /**
     * The same wiring, driven the other way round: the dialog is raised *while*
     * a capture is already in flight.
     *
     * A separate case because it exercises a different ordering, and the sink
     * only ever fires on a *transition*: `setHidden` broadcasts when the hider
     * set changes emptiness, so a dialog added after the broadcast was never
     * told. This pins which side of that race the dialog lands on.
     */
    @Test
    fun aDialogRaisedDuringACaptureIsNotLeftCompositedWhenItEnds() {
        val service = start()
        idle()
        record(false)

        OverlayVisibility.setHidden(OverlayVisibility.Holder.CAPTURE, true)
        idle()
        bubble(service).performClick()
        idle()
        val view = requireNotNull(dialog(service)) {
            "the gate must still raise the dialog while a capture is in " +
                "flight — the tap is the user's and the capture is not theirs"
        }

        OverlayVisibility.setHidden(OverlayVisibility.Holder.CAPTURE, false)
        idle()

        assertDialogIsOnScreen(
            service, view,
            "a dialog raised during a capture must be on screen once the " +
                "capture ends, or the user is left with a withheld recording " +
                "behind a window they cannot see",
        )
    }

    private fun warningParams(service: BubbleService): WindowManager.LayoutParams =
        requireNotNull(
            field(service, "warningParams") as WindowManager.LayoutParams?
        ) { "the warning dialog has no layout params" }

    private fun dialogTakesTouches(service: BubbleService): Boolean =
        warningParams(service).flags and
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0

    /** Every observable that says "the dialog is off the screen", in one place. */
    private fun assertDialogIsOffScreen(service: BubbleService, view: View, why: String) {
        assertEquals(
            "$why: the dialog's window alpha is " +
                "${warningParams(service).alpha}, so the window manager is " +
                "still compositing it",
            0f, warningParams(service).alpha, 0.0001f,
        )
        assertTrue(
            "$why: the dialog's root view is still VISIBLE",
            view.visibility != View.VISIBLE,
        )
        assertTrue(
            "$why: the dialog's window still takes touches. An overlay window " +
                "consumes every touch inside its bounds whether or not a view " +
                "there handles it, so an invisible-but-touchable card eats taps " +
                "meant for whatever is underneath — and its buttons are still " +
                "live, so a blind tap can start a recording.",
            !dialogTakesTouches(service),
        )
    }

    private fun assertDialogIsOnScreen(service: BubbleService, view: View, why: String) {
        assertEquals(
            "$why: the dialog's window alpha",
            1f, warningParams(service).alpha, 0.0001f,
        )
        assertEquals(
            "$why: the dialog's root visibility",
            View.VISIBLE, view.visibility,
        )
        assertTrue(
            "$why: the dialog is drawn but cannot be tapped, so neither button " +
                "can be pressed and the withheld recording can never proceed",
            dialogTakesTouches(service),
        )
    }

    /**
     * How many receivers registered against the application belong to
     * [BubbleService].
     *
     * Keyed on the declaring class rather than on the action, so a manifest
     * receiver for an unrelated broadcast cannot be counted and the service's
     * own anonymous receiver cannot be missed.
     */
    private fun serviceOwnedReceivers(): Int =
        shadowOf(app).registeredReceivers.count {
            it.broadcastReceiver.javaClass.name.startsWith(BubbleService::class.java.name)
        }
}

/**
 * Delivers a broadcast to the service's own screen receiver.
 *
 * 🔴 Reaches the registered receiver by reflection rather than calling
 * `app.sendBroadcast`. `ACTION_SCREEN_ON`/`ACTION_SCREEN_OFF` are protected
 * broadcasts: a test that sent one would be asserting on Robolectric's
 * delivery rules, and if those refuse it the test passes while observing
 * nothing. This calls the receiver the service actually registered, so the
 * assertion is about the handler.
 */
private fun BubbleService.onReceiveForTest(intent: Intent) {
    val f = BubbleService::class.java.getDeclaredField("screenReceiver")
    f.isAccessible = true
    (f.get(this) as android.content.BroadcastReceiver).onReceive(this, intent)
}
