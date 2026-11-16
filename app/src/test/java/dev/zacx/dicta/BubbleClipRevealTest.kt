package dev.zacx.dicta

import android.Manifest
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import java.time.Duration

/**
 * The shared-clipboard button inside the long-press reveal, driven through the
 * real [BubbleService] with real `MotionEvent`s.
 *
 * [BubbleSnipRevealTest]'s harness, applied to the third button. Its notes on
 * what Robolectric cannot observe — the MediaProjection alpha, the IME — apply
 * unchanged, and the witness for "is the reveal up?" is again a **sibling
 * control** rather than the private `actionsShown` flag.
 *
 * ## 🔴 The one genuinely mutation-killable guard in this feature
 *
 * [theWindowHeightAccountsForTheClipButtonSoTheClampKeepsItOnScreen]. Three
 * 44dp buttons at 6dp gaps under an 84dp halo is **234dp of window**, and the
 * clip button is the BOTTOM-MOST of the three — so dropping its term from
 * `windowHeightPx()` leaves exactly that control off the bottom of the screen
 * for a bubble parked low. The user sees its top edge and cannot tap it. Every
 * other assertion in this file is a structural invariant; this one fails on a
 * real arithmetic mistake that is one deleted line away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-port-xxhdpi")
class BubbleClipRevealTest {

    private var controller: org.robolectric.android.controller.ServiceController<BubbleService>? =
        null

    private class FakeSession : BubbleService.SessionHandle {
        var starts = 0
        override fun start(): Boolean { starts++; return true }
        override fun submit() = Unit
        override fun discard() = Unit
        override fun destroy() = Unit
    }

    private val fake = FakeSession()

    @org.junit.Before
    fun setUp() {
        // 🔴 Process-wide, like the three cleared below: without this the
        // drains one test records are the next test's starting verdict — and
        // the service reads that verdict at window-build time, so the
        // contamination would arrive before the test's first assertion.
        ClipOutboxHealth.clearForTest()
    }

    @After
    fun tearDown() {
        runCatching { controller?.destroy() }
        OverlayVisibility.clearForTest()
        BubbleSessionBus.clearForTest()
        MicrophoneOwner.clearForTest()
        ClipOutboxHealth.clearForTest()
    }

    private fun start(): BubbleService {
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
        val c = Robolectric.buildService(BubbleService::class.java)
        controller = c
        c.get().sessionFactory = { fake }
        // 🔴 The outbox drain is stubbed to a no-op. Left real it would build a
        // ClipSettings, unwrap a keystore key that does not exist under
        // Robolectric and construct a live HTTP client on the first tick —
        // turning a reveal test into a test of the crypto layer, and a failure
        // there would read as a button defect.
        c.get().clipFlush = {}
        c.get().clipFlushExecutor = java.util.concurrent.Executor { }
        return c.create().get()
    }

    private fun <T> field(service: BubbleService, name: String): T {
        val f = BubbleService::class.java.getDeclaredField(name)
        f.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return f.get(service) as T
    }

    private fun bubble(service: BubbleService): View =
        requireNotNull(field<View?>(service, "bubble")) { "no bubble view" }

    private fun clip(service: BubbleService): ImageButton =
        requireNotNull(field<ImageButton?>(service, "clip")) { "no clip button" }

    private fun snip(service: BubbleService): ImageButton =
        requireNotNull(field<ImageButton?>(service, "snip")) { "no snip button" }

    private fun history(service: BubbleService): ImageButton =
        requireNotNull(field<ImageButton?>(service, "history")) { "no history button" }

    private fun cancel(service: BubbleService): ImageButton =
        requireNotNull(field<ImageButton?>(service, "cancel")) { "no cancel button" }

    private fun params(service: BubbleService): WindowManager.LayoutParams =
        field(service, "params")

    private fun idle(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    private fun longPress(service: BubbleService) {
        val v = bubble(service)
        val t = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 10f, 10f, 0))
        idle(BubbleGesture.LONG_PRESS_MS + 50)
        val u = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(MotionEvent.obtain(u, u, MotionEvent.ACTION_UP, 10f, 10f, 0))
        idle(0)
    }

    private fun dragTo(service: BubbleService, y: Float) {
        val v = bubble(service)
        val t = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 10f, 10f, 0))
        v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_MOVE, 10f, y, 0))
        v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_UP, 10f, 10f, 0))
        idle(0)
    }

    private val screenHeight: Int
        get() = RuntimeEnvironment.getApplication().resources.displayMetrics.heightPixels

    // --- the button's visibility is the reveal's ------------------------------

    @Test
    fun theClipButtonIsGoneBeforeALongPressAndVisibleAfter() {
        val service = start()
        assertEquals(
            "the clip button is on screen with no reveal up — it is a new " +
                "always-on surface, which is the one thing it must not be",
            View.GONE,
            clip(service).visibility,
        )

        longPress(service)

        assertEquals(View.VISIBLE, clip(service).visibility)
        assertEquals(
            "precondition: the reveal itself came up",
            View.VISIBLE,
            history(service).visibility,
        )
    }

    @Test
    fun theClipButtonGoesAwayWithTheRestOfTheReveal() {
        val service = start()
        longPress(service)
        assertEquals(View.VISIBLE, clip(service).visibility)

        val t = SystemClock.uptimeMillis()
        requireNotNull(field<View?>(service, "root"))
            .dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_OUTSIDE, 0f, 0f, 0))
        idle(0)

        assertEquals(View.GONE, history(service).visibility)
        assertEquals(
            "its two siblings went away and the clip button stayed, so its " +
                "visibility is owned by something other than showActions",
            View.GONE,
            clip(service).visibility,
        )
    }

    @Test
    fun theThreeRevealButtonsAreRaisedAndLoweredTOGETHER() {
        // 🔴 A RELATIONSHIP, which is what no single button's test can see:
        // showActions sets three visibilities in one call, and a missed line
        // there leaves one control permanently up or permanently down. Asserted
        // as "all three agree", in both directions.
        val service = start()
        val all = { listOf(history(service), snip(service), clip(service)).map { it.visibility } }

        assertEquals(listOf(View.GONE, View.GONE, View.GONE), all())
        longPress(service)
        assertEquals(listOf(View.VISIBLE, View.VISIBLE, View.VISIBLE), all())

        bubble(service).performClick()
        idle(0)
        assertEquals(listOf(View.GONE, View.GONE, View.GONE), all())
    }

    @Test
    fun theFourControlsBelowTheBubbleAreNeverALLOnScreenTogether() {
        // The reveal opens only in IDLE and cancel shows only while RECORDING,
        // so the three reveal buttons and cancel are mutually exclusive. If
        // they ever are not, the window is taller than either arm of the clamp
        // arithmetic was written for.
        val service = start()
        longPress(service)
        assertEquals(View.VISIBLE, clip(service).visibility)
        assertEquals(View.GONE, cancel(service).visibility)

        bubble(service).performClick()   // dismiss the reveal, start recording
        idle(0)

        assertEquals(1, fake.starts)
        assertEquals(View.VISIBLE, cancel(service).visibility)
        assertEquals(
            "the clip button is still on screen over a live recording",
            View.GONE,
            clip(service).visibility,
        )
    }

    // --- the launch ----------------------------------------------------------

    @Test
    fun tappingTheClipButtonOpensThePickerThroughClipLaunch() {
        val service = start()
        longPress(service)
        clip(service).performClick()
        idle(0)

        val intent = shadowOf(service).nextStartedActivity
        assertNotNull("tapping the clip button started nothing", intent)
        assertEquals(ClipPickerActivity::class.java.name, intent!!.component?.className)
        assertTrue(
            "the picker's intent carries no FLAG_ACTIVITY_NEW_TASK, so it was " +
                "hand-built rather than routed through ClipLaunch — and " +
                "without it the translucent picker stacks on MainActivity, " +
                "which is issue #11 exactly",
            intent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )
        assertNotEquals(
            "the clip button opened the DICTATION card, which auto-starts a " +
                "capture for any plain launch",
            DictateActivity::class.java.name,
            intent.component?.className,
        )
    }

    @Test
    fun tappingTheClipButtonPutsTheRevealAwayBeforeThePickerOpens() {
        val service = start()
        longPress(service)
        clip(service).performClick()
        idle(0)

        assertEquals(View.GONE, history(service).visibility)
        assertEquals(View.GONE, snip(service).visibility)
        assertEquals(View.GONE, clip(service).visibility)
        assertEquals(
            "FLAG_WATCH_OUTSIDE_TOUCH was left armed device-wide by a reveal " +
                "that is not on screen",
            0,
            params(service).flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        )
    }

    @Test
    fun tappingTheClipButtonStartsNoRecording() {
        // Asserted on the SESSION rather than on an effect list, so a shell
        // that reached the microphone some other way is still caught.
        val service = start()
        longPress(service)
        clip(service).performClick()
        idle(0)

        assertEquals("the clip button opened the microphone", 0, fake.starts)
        assertEquals(
            "the clip button left a live session behind in IDLE — an open " +
                "microphone with nothing on screen referring to it",
            null,
            field<Any?>(service, "session"),
        )
    }

    @Test
    fun theThreeRevealButtonsAnnounceTHREEDIFFERENTThings() {
        // 🔴 Three unlabelled image buttons on an overlay is the accessibility
        // failure; three IDENTICALLY labelled ones is the same failure wearing
        // labels. Fails if any two agree.
        val service = start()
        val buttons = listOf(history(service), snip(service), clip(service))
        for (b in buttons) {
            assertTrue(
                "a reveal button has no contentDescription",
                !b.contentDescription.isNullOrBlank(),
            )
            assertEquals(
                "a button's description and tooltip disagree, which is two " +
                    "labels for one control",
                b.contentDescription.toString(),
                b.tooltipText?.toString(),
            )
        }
        assertEquals(
            "two reveal buttons announce the same thing: " +
                buttons.map { it.contentDescription },
            3,
            buttons.map { it.contentDescription.toString() }.toSet().size,
        )
    }

    // --- a queue that cannot drain -------------------------------------------

    /** Drives [ClipOutboxHealth] with [times] drains that [report] describes. */
    private fun drains(report: ClipFlusher.Report, times: Int) {
        repeat(times) { ClipOutboxHealth.record(report) }
        idle(0)
    }

    /** A failed drain: the 401-forever shape, and a dead mesh's. */
    private val failedDrain = ClipFlusher.Report(kept = 3, unreachable = true)

    /**
     * A drain that is WORKING and left rows behind — a
     * [ClipFlusher.MAX_PER_FLUSH] ceiling hit. `kept > 0`, `unreachable` false.
     */
    private val pendingDrain = ClipFlusher.Report(stored = 25, kept = 40)

    private fun string(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    @Test
    fun aQueueThatKEEPSFAILINGToDrainIsVISIBLEOnTheClipButton() {
        // 🔴 THE RELATIONSHIP audit round 0 found missing, asserted end to end:
        // a ClipFlusher.Report carrying a persistent failure ⇒ the bubble's
        // clip affordance reflects it. The three links either side of this are
        // each pinned separately — ClipFlusherTest (a failed drain produces a
        // Report that says so), ClipOutboxHealthTest (a RUN of those is a
        // fault), ClipSeamLedgerTest (both drain sites hand the Report over) —
        // and the defect lived in the join, so this drives the whole chain.
        //
        // 🔴 Asserted on the STATE, not on a word: the description is compared
        // against the RESOURCE and the tint against the colour resource, both
        // together. A guard on either alone is satisfiable while the other
        // lies, and a guard on the text "not reaching" would be walkable by a
        // copy edit.
        val service = start()
        drains(failedDrain, times = ClipOutboxHealth.STUCK_AFTER_FAILURES)
        longPress(service)

        val button = clip(service)
        assertEquals(
            "the shared clipboard cannot drain and the button says exactly " +
                "what it says when everything is fine — which is the silent " +
                "failure this feature shipped with",
            string(R.string.bubble_clip_stuck),
            button.contentDescription?.toString(),
        )
        assertEquals(
            "the description and the tooltip disagree, which is two labels " +
                "for one control",
            button.contentDescription?.toString(),
            button.tooltipText?.toString(),
        )
        assertEquals(
            "a stuck shared clipboard is not drawn in @color/dicta_error",
            RuntimeEnvironment.getApplication().getColor(R.color.dicta_error),
            button.imageTintList?.defaultColor,
        )
    }

    @Test
    fun aQueueThatIsMERELYPENDINGLooksEXACTLYLikeAHealthyOne() {
        // 🔴 THE OTHER DIRECTION, and the one that decides whether this
        // indicator survives contact with the operator. A pending row is the
        // ORDINARY state — the POST is always deferred by design — so a
        // ceiling-hit drain repeated ten times must leave the button
        // indistinguishable from an untouched one. An indicator keyed on
        // `kept > 0` would be lit after every multi-item capture.
        val service = start()
        drains(pendingDrain, times = 10)
        longPress(service)

        val button = clip(service)
        assertEquals(
            "a queue that is draining a batch at a time is being reported as " +
                "a FAULT, so the bubble cries wolf on normal use",
            string(R.string.bubble_clip),
            button.contentDescription?.toString(),
        )
        assertEquals(
            RuntimeEnvironment.getApplication().getColor(R.color.dicta_on_surface),
            button.imageTintList?.defaultColor,
        )

        // 🔴 And the two readings must actually differ, or both assertions here
        // and in the test above are satisfied by one constant label. This is
        // the control that makes the pair evidence rather than a tautology.
        assertNotEquals(
            "the healthy and stuck labels are the same string, so neither of " +
                "these two tests can distinguish anything",
            string(R.string.bubble_clip),
            string(R.string.bubble_clip_stuck),
        )
    }

    @Test
    fun aREPAIREDQueuePutsTheButtonBack() {
        // One successful drain is proof the queue CAN drain, so the fault must
        // clear — including on a reveal that is already on screen, which is the
        // case a render-at-build-time-only implementation would miss.
        val service = start()
        drains(failedDrain, times = ClipOutboxHealth.STUCK_AFTER_FAILURES)
        longPress(service)
        assertEquals(
            "precondition: the fault is on the button",
            string(R.string.bubble_clip_stuck),
            clip(service).contentDescription?.toString(),
        )

        drains(ClipFlusher.Report(stored = 3), times = 1)
        assertEquals(
            "the mesh came back and the button is still ember, so the only " +
                "way to clear a fault is to restart the service",
            string(R.string.bubble_clip),
            clip(service).contentDescription?.toString(),
        )
    }

    @Test
    fun aServiceThatSTARTSIntoAStuckQueueShowsItOnTheFirstLongPress() {
        // 🔴 The sink only fires on a CHANGE, and the service is restarted on
        // every boot and every upgrade — so a window built after the verdict
        // was already `true` would never be told, and the fault would be
        // invisible until the queue recovered and failed again. The initial
        // read in buildWindow is what closes that, and deleting it leaves every
        // other assertion in this file green.
        drains(failedDrain, times = ClipOutboxHealth.STUCK_AFTER_FAILURES)
        val service = start()
        longPress(service)
        assertEquals(
            "the bubble started with the queue already stuck and the button " +
                "reads healthy — the verdict predates the window, and nothing " +
                "will re-announce it",
            string(R.string.bubble_clip_stuck),
            clip(service).contentDescription?.toString(),
        )
    }

    @Test
    fun theFaultDoesNotSLAMTheRevealSHUTUnderTheOperatorsFinger() {
        // 🔴 Why this is NOT routed through BubbleDictation, asserted rather
        // than argued. `on()` prefixes Effect.HideActions to every event that
        // is not a long press, so an event raised by a background flush would
        // dismiss the reveal each time the mesh blipped — and the one moment
        // the operator is looking at this button is the moment it would vanish.
        val service = start()
        longPress(service)
        assertEquals(View.VISIBLE, clip(service).visibility)

        drains(failedDrain, times = ClipOutboxHealth.STUCK_AFTER_FAILURES + 2)

        assertEquals(
            "a background drain closed the reveal, which is what routing this " +
                "through the state machine would do",
            View.VISIBLE,
            clip(service).visibility,
        )
        assertEquals(View.VISIBLE, history(service).visibility)
        assertEquals(View.VISIBLE, snip(service).visibility)
        assertEquals(
            "and the fault did reach the button while it was up",
            string(R.string.bubble_clip_stuck),
            clip(service).contentDescription?.toString(),
        )
    }

    @Test
    fun aDrainRecordedAfterTheServiceIsGoneHarmsNothing() {
        // ClipOutboxHealth is process-wide and the PICKER records into it with
        // no bubble running at all, so drains arrive after a bubble is torn
        // down as a matter of routine. The sink's `root?.post` is what makes
        // that a no-op rather than a view touch on a dead window.
        //
        // ⚠ **What this does NOT pin:** the `onDestroy` unregister itself. A
        // sink left registered would hold the destroyed Service and its view
        // tree for the life of the process, and that leak is a REFERENCE rather
        // than an observable — nothing goes red for it here, and the
        // `root?.post` means nothing throws either. Stated so the line is not
        // read as covered; `BubbleService.onDestroy`'s own comment carries the
        // reason it is there.
        start()
        drains(failedDrain, times = ClipOutboxHealth.STUCK_AFTER_FAILURES)
        controller?.destroy()
        controller = null

        drains(ClipFlusher.Report(stored = 1), times = 1)
        assertFalse(
            "a successful drain after the bubble was destroyed left the " +
                "verdict stuck",
            ClipOutboxHealth.verdict() != ClipOutboxHealth.Verdict.OK,
        )
    }

    // --- the clamp (the height accounting) -----------------------------------

    @Test
    fun theWindowHeightAccountsForTheClipButtonSoTheClampKeepsItOnScreen() {
        // 🔴 THE assertion that kills "windowHeightPx forgot the clip button",
        // and the only genuinely mutation-killable guard this feature adds.
        //
        // The sum is built from the service's OWN dp fields rather than from a
        // literal, so it stays a cross-check of two independently-computed
        // sides (`windowHeightPx` vs this) rather than a restatement of one.
        val service = start()
        longPress(service)
        val halo: Int = field(service, "haloSizePx")
        val historyBlock: Int = field(service, "historyBlockPx")
        val snipBlock: Int = field(service, "snipBlockPx")
        val clipBlock: Int = field(service, "clipBlockPx")
        assertTrue("precondition: the history block has a height", historyBlock > 0)
        assertTrue("precondition: the snip block has a height", snipBlock > 0)
        assertTrue("precondition: the clip block has a height", clipBlock > 0)

        // 🔴 Deliberately WAY past the bottom edge rather than near it, and NOT
        // a multiple of any block: a bound that happened to land on a multiple
        // of the step would pass against the wrong operand, and a drag that
        // stopped just short of the edge would never execute the clamp at all.
        dragTo(service, 9000f)

        val needed = halo + historyBlock + snipBlock + clipBlock
        assertTrue(
            "with the reveal open the window is ${needed}px tall on a " +
                "${screenHeight}px display, but it was clamped to " +
                "y=${params(service).y} — which is where it would land if the " +
                "clamp had never been told about the clip button",
            params(service).y + needed <= screenHeight,
        )
        assertTrue("the window was clamped above the display", params(service).y >= 0)
    }

    @Test
    fun theThirdButtonActuallyMADETheClampTIGHTERThanTwoWouldHave() {
        // 🔴 ISOLATION OF THE MUTATION. The assertion above would still pass
        // if `clipBlockPx` were dropped AND the display happened to be tall
        // enough that two blocks and three clamp to the same `y` — the guard
        // would never execute its own claim while production's taller reveal
        // did. This is the control: the clamped position with three blocks must
        // be strictly HIGHER up the screen than a two-block clamp would put it,
        // so the third term is observably load-bearing on THIS display.
        val service = start()
        longPress(service)
        val halo: Int = field(service, "haloSizePx")
        val historyBlock: Int = field(service, "historyBlockPx")
        val snipBlock: Int = field(service, "snipBlockPx")
        val clipBlock: Int = field(service, "clipBlockPx")

        dragTo(service, 9000f)
        val y = params(service).y

        val twoBlockBottom = screenHeight - (halo + historyBlock + snipBlock)
        assertTrue(
            "the clamp put the window at y=$y, which is exactly where a " +
                "TWO-block reveal would have left it ($twoBlockBottom) — so " +
                "this display cannot tell a three-term clamp from a two-term " +
                "one and the guard above is unreachable. Pick a shorter " +
                "qualifier.",
            y < twoBlockBottom,
        )
        assertEquals(
            "the clamped position is not the three-block bottom either, so " +
                "something other than windowHeightPx decided it",
            screenHeight - (halo + historyBlock + snipBlock + clipBlock),
            y,
        )
    }

    @Test
    fun revealingTheClipButtonDoesNotMoveTheBubbleAtAll() {
        // Nothing sits above the bubble, so the window grows downward only and
        // the correct compensation is ZERO. Exact equality, which is strictly
        // stronger than a bound: any surviving `y` adjustment in either
        // direction, of any magnitude, fails here.
        val service = start()
        val before = params(service).y

        longPress(service)
        assertEquals(
            "the reveal moved the bubble by ${before - params(service).y}px",
            before,
            params(service).y,
        )
        assertEquals(
            "precondition: the reveal actually came up, so the zero above is " +
                "a measurement and not a no-op",
            View.VISIBLE,
            clip(service).visibility,
        )
    }

    @Test
    fun theClipButtonIsInsideTheBubblesOwnWindowAndNotASecondOne() {
        // 🔴 An overlay window is composited into EVERY MediaProjection frame
        // — which is how Dicta's UI ended up inside the user's screenshots
        // (PR #4). This is the structural half.
        val service = start()
        assertSame(
            "the clip button is not a child of the bubble's own window, so it " +
                "is a second overlay and needs its own OverlayVisibility sink",
            field<View?>(service, "root"),
            clip(service).parent,
        )
    }
}
