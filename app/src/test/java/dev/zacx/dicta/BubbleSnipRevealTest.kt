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
 * The snip button inside the long-press reveal, driven through the real
 * [BubbleService] with real `MotionEvent`s — [BubbleHistoryRevealTest]'s
 * harness, applied to issue #38's addition.
 *
 * ## What this file is evidence for
 *
 * That the button exists only while the reveal is up, that tapping it opens the
 * picker **through [SnipLaunch]** and puts the reveal away first, that it
 * starts no recording, and that the window's height accounting includes it so
 * [BubbleChrome.clampY] cannot leave it off-screen.
 *
 * 🔴 It is **not** evidence that the reveal stays out of screenshots — that is
 * `OverlayVisibility`'s effect on `params.alpha` during a MediaProjection
 * capture, which Robolectric cannot observe at all and which shipped broken
 * once (PR #4). The button is inside the window that sink already drives, which
 * is the structural half; the device screenshot is the other half.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-port-xxhdpi")
class BubbleSnipRevealTest {

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

    // --- harness (BubbleHistoryRevealTest's, verbatim in shape) --------------

    private fun start(): BubbleService {
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
        val c = Robolectric.buildService(BubbleService::class.java)
        controller = c
        c.get().sessionFactory = { fake }
        c.get().historyProvider = { limit -> twoItems().take(limit) }
        c.get().historyExecutor = java.util.concurrent.Executor { it.run() }
        return c.create().get()
    }

    private fun twoItems() = listOf(
        DictationHistoryStore.Item(
            id = "alpha",
            createdAtMs = System.currentTimeMillis() - 7_200_000L,
            audioBytes = 1234L,
            audioFile = "alpha.ogg",
            text = "the quick brown fox",
            fromRemote = true,
            audioSeconds = 2.5,
        ),
        DictationHistoryStore.Item(
            id = "bravo",
            createdAtMs = System.currentTimeMillis() - 259_200_000L,
            audioBytes = 2345L,
            audioFile = "bravo.ogg",
            text = "another saved dictation",
            fromRemote = true,
            audioSeconds = 3.5,
        ),
    )

    private fun <T> field(service: BubbleService, name: String): T {
        val f = BubbleService::class.java.getDeclaredField(name)
        f.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return f.get(service) as T
    }

    private fun bubble(service: BubbleService): View =
        requireNotNull(field<View?>(service, "bubble")) { "no bubble view" }

    private fun snip(service: BubbleService): ImageButton =
        requireNotNull(field<ImageButton?>(service, "snip")) { "no snip button" }

    private fun cancel(service: BubbleService): ImageButton =
        requireNotNull(field<ImageButton?>(service, "cancel")) { "no cancel button" }

    private fun row(service: BubbleService): android.widget.ListView =
        requireNotNull(field<android.widget.ListView?>(service, "historyRow")) { "no history row" }

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
    fun theSnipButtonIsGoneBeforeALongPressAndVisibleAfter() {
        val service = start()
        assertEquals(
            "the snip button is on screen with no reveal up — it is a new " +
                "always-on surface, which is the one thing it must not be",
            View.GONE,
            snip(service).visibility,
        )

        longPress(service)

        assertEquals(View.VISIBLE, snip(service).visibility)
        assertEquals(
            "precondition: the reveal itself came up",
            View.VISIBLE,
            row(service).visibility,
        )
    }

    @Test
    fun theSnipButtonGoesAwayWithTheRestOfTheReveal() {
        val service = start()
        longPress(service)
        assertEquals(View.VISIBLE, snip(service).visibility)

        // An outside touch — the reveal's own dismissal, which goes through the
        // machine's one HideHistory rule rather than a second hide path.
        val t = SystemClock.uptimeMillis()
        requireNotNull(field<View?>(service, "root"))
            .dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_OUTSIDE, 0f, 0f, 0))
        idle(0)

        assertEquals(View.GONE, row(service).visibility)
        assertEquals(
            "the chips went away and the snip button stayed, so its " +
                "visibility is owned by something other than showReveal",
            View.GONE,
            snip(service).visibility,
        )
    }

    @Test
    fun theSnipButtonAndTheCancelButtonAreNeverOnScreenTogether() {
        // The reveal opens only in IDLE and cancel shows only while RECORDING,
        // so the two controls below the bubble are mutually exclusive. If they
        // ever are not, the window is taller than either arm of the clamp
        // arithmetic was written for.
        val service = start()
        longPress(service)
        assertEquals(View.VISIBLE, snip(service).visibility)
        assertEquals(View.GONE, cancel(service).visibility)

        bubble(service).performClick()      // dismiss the reveal, start recording
        idle(0)

        assertEquals(1, fake.starts)
        assertEquals(View.VISIBLE, cancel(service).visibility)
        assertEquals(
            "the snip button is still on screen over a live recording",
            View.GONE,
            snip(service).visibility,
        )
    }

    // --- the launch ----------------------------------------------------------

    @Test
    fun tappingTheSnipButtonOpensThePickerThroughSnipLaunch() {
        val service = start()
        longPress(service)
        snip(service).performClick()
        idle(0)

        val intent = shadowOf(service).nextStartedActivity
        assertNotNull("tapping the snip button started nothing", intent)
        assertEquals(
            SnipPickerActivity::class.java.name,
            intent!!.component?.className,
        )
        assertTrue(
            "the picker's intent carries no FLAG_ACTIVITY_NEW_TASK, so it was " +
                "hand-built rather than routed through SnipLaunch — and " +
                "without it the translucent picker stacks on MainActivity, " +
                "which is issue #11 exactly",
            intent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )
        assertNotEquals(
            "the snip button opened the DICTATION card, which auto-starts a " +
                "capture for any plain launch",
            DictateActivity::class.java.name,
            intent.component?.className,
        )
    }

    @Test
    fun tappingTheSnipButtonPutsTheRevealAwayBeforeThePickerOpens() {
        val service = start()
        longPress(service)
        snip(service).performClick()
        idle(0)

        assertEquals(
            "the chips stayed on screen behind the picker they opened",
            View.GONE,
            row(service).visibility,
        )
        assertEquals(View.GONE, snip(service).visibility)
        assertEquals(
            "FLAG_WATCH_OUTSIDE_TOUCH was left armed device-wide by a reveal " +
                "that is not on screen",
            0,
            params(service).flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        )
    }

    @Test
    fun tappingTheSnipButtonStartsNoRecording() {
        // 🔴 Issue #38's own line: opening the picker is not a dictation
        // start. Asserted on the SESSION rather than on an effect list, so a
        // shell that reached the microphone some other way is still caught.
        val service = start()
        longPress(service)
        snip(service).performClick()
        idle(0)

        assertEquals("the snip button opened the microphone", 0, fake.starts)
        assertNull(
            "the snip button left a live session behind in IDLE — an open " +
                "microphone with nothing on screen referring to it",
            field<Any?>(service, "session"),
        )
    }

    @Test
    fun theSnipButtonSaysWhatTappingItDoes() {
        val service = start()
        val button = snip(service)
        assertTrue(
            "the snip button has no contentDescription, so a screen-reader " +
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
            "the snip button announces the same thing as cancel",
            cancel(service).contentDescription?.toString(),
            button.contentDescription.toString(),
        )
    }

    // --- the clamp (the height accounting) -----------------------------------

    @Test
    fun theWindowHeightAccountsForTheSnipButtonSoTheClampKeepsItOnScreen() {
        // 🔴 The assertion that kills "windowHeightPx forgot the snip
        // button". With the reveal up the window is one 50 dp block taller
        // than the chips and the bubble, and a clamp that was not told would
        // leave the bottom-most control below the display: a button the user
        // can see the top pixel of and cannot tap. Same defect
        // `aDragWithTheHistoryOpenKeepsTheBOTTOMMOSTChipOnScreen` closes from
        // the other end.
        val service = start()
        longPress(service)
        val halo: Int = field(service, "haloSizePx")
        val reveal: Int = field(service, "revealBlockPx")
        val snipBlock: Int = field(service, "snipBlockPx")
        assertTrue("precondition: the reveal has a height", reveal > 0)
        assertTrue("precondition: the snip block has a height", snipBlock > 0)

        // Deliberately WAY past the bottom edge rather than near it: a bound
        // that happens to land on a multiple of the block would pass against
        // the wrong operand.
        dragTo(service, 9000f)

        assertTrue(
            "with the reveal open the window is ${halo + reveal + snipBlock}px " +
                "tall on a ${screenHeight}px display, but it was clamped to " +
                "y=${params(service).y} — which is where it would land if the " +
                "clamp had never been told about the snip button",
            params(service).y + halo + reveal + snipBlock <= screenHeight,
        )
        assertTrue("the window was clamped above the display", params(service).y >= 0)
    }

    @Test
    fun revealingTheSnipButtonDoesNotMoveTheBubbleAtAll() {
        // 🔴 The button is BELOW the bubble, so it contributes exactly zero to
        // the `y` compensation and the reveal's shift stays exactly
        // revealBlockPx — which is what lets every existing piece of that
        // arithmetic, and the device evidence behind it, carry over unchanged.
        // Move the button above the bubble without folding its block into the
        // shift and this goes red.
        val service = start()
        val before = params(service).y

        longPress(service)
        val reveal: Int = field(service, "revealBlockPx")
        assertTrue("precondition: the reveal has a height", reveal > 0)
        assertEquals(
            "the reveal moved the bubble by ${before - params(service).y}px " +
                "rather than the chips' own ${reveal}px, so the snip button is " +
                "shifting the window and the bubble jumps under the finger",
            before - reveal,
            params(service).y,
        )
    }

    @Test
    fun theSnipButtonIsInsideTheBubblesOwnWindowAndNotASecondOne() {
        // 🔴 An overlay window is composited into EVERY MediaProjection frame
        // — which is how Dicta's UI ended up inside the user's screenshots
        // (PR #4). OverlayWindowLedgerTest guards the textual half (the
        // per-file addView count is still 2); this is the structural half.
        val service = start()
        assertSame(
            "the snip button is not a child of the bubble's own window, so it " +
                "is a second overlay and needs its own OverlayVisibility sink",
            field<View?>(service, "root"),
            snip(service).parent,
        )
    }
}
