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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * The history button inside the long-press reveal, driven through the real
 * [BubbleService] with real `MotionEvent`s — `BubbleSnipRevealTest`'s file,
 * applied to the control that replaced the chip list.
 *
 * ## What this file is evidence for
 *
 * That the button exists only while the reveal is up, that tapping it opens the
 * picker **through [HistoryLaunch]** and puts the reveal away first, that it
 * starts no recording, and that the window's height accounting includes it so
 * [BubbleChrome.clampY] cannot leave it off-screen.
 *
 * ## 🔴 These are INVARIANT GUARDS, not regression coverage
 *
 * Nothing here was watched failing on pre-change code, and nothing could have
 * been: the button, `Event.HistoryTapped`, `HistoryLaunch` and `historyBlockPx`
 * all arrive with this change, so a run against the base commit is a compile
 * error rather than a red test. What stands in for red-at-base is the mutant
 * batch in `scripts/mutants/history-picker.tsv`; the verdicts are in the pull
 * request, stamped with the commit they were measured on.
 *
 * 🔴 It is **not** evidence that the reveal stays out of screenshots — that is
 * `OverlayVisibility`'s effect on `params.alpha` during a MediaProjection
 * capture, which Robolectric cannot observe at all and which shipped broken
 * once (PR #4). The button is inside the window that sink already drives, which
 * is the structural half; the device screenshot is the other half.
 *
 * Nor is it evidence about what the two-button stack LOOKS like, which is the
 * one thing a reader is most likely to assume a reveal test covers. Robolectric
 * lays nothing out for real.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-port-xxhdpi")
class BubbleHistoryButtonRevealTest {

    private var controller: org.robolectric.android.controller.ServiceController<BubbleService>? =
        null

    /** A session that records nothing; the bubble's taps must still work. */
    private class FakeSession : BubbleService.SessionHandle {
        var starts = 0
        override fun start(): Boolean { starts++; return true }
        override fun submit() = Unit
        override fun discard() = Unit
        override fun destroy() = Unit
    }

    private val fake = FakeSession()

    @After
    fun tearDown() {
        runCatching { controller?.destroy() }
        OverlayVisibility.clearForTest()
        BubbleSessionBus.clearForTest()
        MicrophoneOwner.clearForTest()
    }

    // --- harness (BubbleSnipRevealTest's, verbatim in shape) -----------------

    private fun start(): BubbleService {
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
        val c = Robolectric.buildService(BubbleService::class.java)
        controller = c
        c.get().sessionFactory = { fake }
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

    private fun history(service: BubbleService): ImageButton =
        requireNotNull(field<ImageButton?>(service, "history")) { "no history button" }

    private fun snip(service: BubbleService): ImageButton =
        requireNotNull(field<ImageButton?>(service, "snip")) { "no snip button" }

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
    fun theHistoryButtonIsGoneBeforeALongPressAndVisibleAfter() {
        val service = start()
        assertEquals(
            "the history button is on screen with no reveal up — it is a new " +
                "always-on surface, which is the one thing it must not be",
            View.GONE,
            history(service).visibility,
        )

        longPress(service)

        assertEquals(View.VISIBLE, history(service).visibility)
        assertEquals(
            "precondition: the reveal itself came up, i.e. both buttons",
            View.VISIBLE,
            snip(service).visibility,
        )
    }

    @Test
    fun theHistoryButtonGoesAwayWithTheRestOfTheReveal() {
        val service = start()
        longPress(service)
        assertEquals(View.VISIBLE, history(service).visibility)

        // An outside touch — the reveal's own dismissal, which goes through the
        // machine's one HideActions rule rather than a second hide path.
        val t = SystemClock.uptimeMillis()
        requireNotNull(field<View?>(service, "root"))
            .dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_OUTSIDE, 0f, 0f, 0))
        idle(0)

        assertEquals(
            "the snip button went away and the history button stayed, so its " +
                "visibility is owned by something other than showActions",
            View.GONE,
            history(service).visibility,
        )
        assertEquals(View.GONE, snip(service).visibility)
    }

    @Test
    fun theThreeControlsBelowTheBubbleAreNeverAllOnScreenTogether() {
        // 🔴 The THIRD-CONTROL sibling of
        // BubbleSnipRevealTest.theSnipButtonAndTheCancelButtonAreNeverOnScreenTogether,
        // and the reason that test needed one: the reveal used to add ONE
        // control below the bubble and now adds TWO, so "cancel and the reveal
        // are mutually exclusive" is a claim about three controls and the
        // pairwise version of it can be satisfied while all three are up.
        //
        // The reveal opens only in IDLE and cancel shows only while RECORDING,
        // so the two reveal buttons are co-visible with each other and neither
        // is ever co-visible with cancel. If that stops holding, the window is
        // taller than any arm of the clamp arithmetic was written for.
        val service = start()
        longPress(service)
        assertEquals(View.VISIBLE, history(service).visibility)
        assertEquals(View.VISIBLE, snip(service).visibility)
        assertEquals(View.GONE, cancel(service).visibility)

        bubble(service).performClick()      // dismiss the reveal, start recording
        idle(0)

        assertEquals(1, fake.starts)
        assertEquals(View.VISIBLE, cancel(service).visibility)
        assertEquals(
            "the history button is still on screen over a live recording",
            View.GONE,
            history(service).visibility,
        )
        assertEquals(
            "the snip button is still on screen over a live recording",
            View.GONE,
            snip(service).visibility,
        )
    }

    // --- the launch ----------------------------------------------------------

    @Test
    fun tappingTheHistoryButtonOpensThePickerThroughHistoryLaunch() {
        val service = start()
        longPress(service)
        history(service).performClick()
        idle(0)

        val intent = shadowOf(service).nextStartedActivity
        assertNotNull("tapping the history button started nothing", intent)
        assertEquals(
            HistoryPickerActivity::class.java.name,
            intent!!.component?.className,
        )
        assertTrue(
            "the picker's intent carries no FLAG_ACTIVITY_NEW_TASK, so it was " +
                "hand-built rather than routed through HistoryLaunch — and " +
                "without it the translucent picker stacks on MainActivity, " +
                "which is issue #11 exactly",
            intent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )
        assertNotEquals(
            "the history button opened the DICTATION card, which auto-starts a " +
                "capture for any plain launch",
            DictateActivity::class.java.name,
            intent.component?.className,
        )
        assertNotEquals(
            "the history button opened the SNIPPET picker. The two arms of the " +
                "machine are the same shape by design, so the copy-paste that " +
                "emits the other one's effect is the live hazard — and every " +
                "assertion above would still pass for it.",
            SnipPickerActivity::class.java.name,
            intent.component?.className,
        )
        assertNull(
            "the launch carries a history id, so the bubble decided WHICH " +
                "dictation to open. It must not: the picker is the surface that " +
                "chooses, and an id here would mean the button opens a card " +
                "rather than a picker.",
            intent.getStringExtra(DictateLaunch.EXTRA_HISTORY_ID),
        )
    }

    @Test
    fun tappingTheHistoryButtonPutsTheRevealAwayBeforeThePickerOpens() {
        val service = start()
        longPress(service)
        history(service).performClick()
        idle(0)

        assertEquals(
            "the history button stayed on screen behind the picker it opened",
            View.GONE,
            history(service).visibility,
        )
        assertEquals(
            "the snip button stayed on screen behind the picker the OTHER " +
                "button opened",
            View.GONE,
            snip(service).visibility,
        )
        assertEquals(
            "FLAG_WATCH_OUTSIDE_TOUCH was left armed device-wide by a reveal " +
                "that is not on screen",
            0,
            params(service).flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        )
    }

    @Test
    fun tappingTheHistoryButtonStartsNoRecording() {
        // 🔴 Opening the picker is not a dictation start. Asserted on the
        // SESSION rather than on an effect list, so a shell that reached the
        // microphone some other way is still caught.
        val service = start()
        longPress(service)
        history(service).performClick()
        idle(0)

        assertEquals("the history button opened the microphone", 0, fake.starts)
        assertNull(
            "the history button left a live session behind in IDLE — an open " +
                "microphone with nothing on screen referring to it",
            field<Any?>(service, "session"),
        )
    }

    @Test
    fun theHistoryButtonSaysWhatTappingItDoes() {
        val service = start()
        val button = history(service)
        assertTrue(
            "the history button has no contentDescription, so a screen-reader " +
                "user meets an unlabelled image button on an overlay",
            !button.contentDescription.isNullOrBlank(),
        )
        assertEquals(
            "the description and the tooltip disagree, which is two labels " +
                "for one control",
            button.contentDescription.toString(),
            button.tooltipText?.toString(),
        )
        assertNotEquals(
            "the history button announces the same thing as cancel",
            cancel(service).contentDescription?.toString(),
            button.contentDescription.toString(),
        )
        assertNotEquals(
            "the two reveal buttons announce the same thing, so a " +
                "screen-reader user cannot tell them apart — and they are the " +
                "only two controls the long press offers",
            snip(service).contentDescription?.toString(),
            button.contentDescription.toString(),
        )
    }

    // --- the clamp (the height accounting) -----------------------------------

    @Test
    fun theWindowHeightAccountsForBothRevealButtonsSoTheClampKeepsThemOnScreen() {
        // 🔴 The assertion that kills "windowHeightPx forgot a reveal button".
        // With the reveal up the window is TWO 50 dp blocks taller than the
        // bubble, and a clamp that was told about only one would leave the
        // bottom-most control below the display: a button the user can see the
        // top pixel of and cannot tap.
        val service = start()
        longPress(service)
        val halo: Int = field(service, "haloSizePx")
        val historyBlock: Int = field(service, "historyBlockPx")
        val snipBlock: Int = field(service, "snipBlockPx")
        assertTrue("precondition: the history block has a height", historyBlock > 0)
        assertTrue("precondition: the snip block has a height", snipBlock > 0)

        // 🔴 Deliberately WAY past the bottom edge rather than near it, and the
        // drag target is not a multiple of either block: a bound that happened
        // to land exactly on one would pass against the wrong operand.
        dragTo(service, 9000f)

        assertTrue(
            "with the reveal open the window is " +
                "${halo + historyBlock + snipBlock}px tall on a " +
                "${screenHeight}px display, but it was clamped to " +
                "y=${params(service).y} — which is where it would land if the " +
                "clamp had been told about fewer buttons than are on screen",
            params(service).y + halo + historyBlock + snipBlock <= screenHeight,
        )
        assertTrue("the window was clamped above the display", params(service).y >= 0)
    }

    @Test
    fun theHistoryButtonIsInsideTheBubblesOwnWindowAndNotASecondOne() {
        // 🔴 An overlay window is composited into EVERY MediaProjection frame
        // — which is how Dicta's UI ended up inside the user's screenshots
        // (PR #4). OverlayWindowLedgerTest guards the textual half (the
        // per-file addView count is still 2, because this replaced a view
        // inside the window rather than adding a window); this is the
        // structural half.
        val service = start()
        assertSame(
            "the history button is not a child of the bubble's own window, so " +
                "it is a second overlay and needs its own OverlayVisibility sink",
            field<View?>(service, "root"),
            history(service).parent,
        )
    }

    @Test
    fun theHistoryButtonSitsNearerTheBubbleThanTheSnipButton() {
        // 🔴 The operator's ordering decision, pinned as the container's child
        // ORDER rather than as a pixel: Robolectric lays nothing out, so a
        // y-coordinate assertion here would be a fact about Robolectric. The
        // root is a VERTICAL LinearLayout and the bubble is child 0, so index
        // order IS top-to-bottom order.
        //
        // Why it matters enough to pin: history is what the long press meant
        // before this change, so the finger that just performed it should find
        // history nearest. A silent swap is invisible to every other test here
        // and is exactly the kind of thing a later edit reorders by accident.
        val service = start()
        val root = requireNotNull(field<android.view.ViewGroup?>(service, "root"))
        val halo = requireNotNull(field<View?>(service, "halo"))

        assertEquals(
            "the bubble is no longer the first child of the window. Something " +
                "was added ABOVE it, which re-opens the `y` compensation the " +
                "chip-list deletion removed — see BubbleService.showActions.",
            0,
            root.indexOfChild(halo),
        )
        assertTrue(
            "the snip button sits between the bubble and the history button, " +
                "so the long press offers snip nearest the finger",
            root.indexOfChild(history(service)) < root.indexOfChild(snip(service)),
        )
    }
}
