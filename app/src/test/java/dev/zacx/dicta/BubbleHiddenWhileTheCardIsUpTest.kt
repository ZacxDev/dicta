package dev.zacx.dicta

import android.Manifest
import android.os.Looper
import android.view.View
import android.view.WindowManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

/**
 * REGRESSION: **the floating bubble was still on top of the transcription
 * card, and still tappable.**
 *
 * Driven on the device: with [DictateActivity] up, [BubbleService]'s overlay
 * window is composited over the card and its mic button starts a *second*
 * dictation from a view that is supposed to be about the one just finished.
 * `@id/mic_button` was already `GONE` on a history card, so the control the
 * operator could see was never the card's own — it was the bubble.
 *
 * ## What is asserted, and what only a device can say
 *
 * This file asserts the **window state** Dicta asks the window manager for:
 * the bubble's root visibility, its `params.alpha`, and whether its window
 * still takes touches. Nothing here composites, so "the bubble is not in the
 * picture" remains a device claim — the same boundary [DictaWindowsTest] draws
 * for the capture path.
 *
 * ## 🔴 The composition case is the one that bites
 *
 * Two independent mechanisms hide the same overlay: this one (for the card's
 * lifetime) and [DictaWindows] (for the few frames of a MediaProjection
 * capture). They overlap in the ordinary flow — the card is open while the
 * screenshot is taken — so with one shared boolean,
 * `restoreAfterCapture()`'s unconditional "show" would put the bubble back
 * **over the still-open card**.
 * [aCaptureRestoreDoesNotPutTheBubbleBackOverAnOpenCard] is that sequence, end
 * to end, through the real seams rather than through [OverlayVisibility]'s
 * own API — so it would have compiled and run red before the holder set
 * existed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BubbleHiddenWhileTheCardIsUpTest {

    private var service: ServiceController<BubbleService>? = null
    private var card: ActivityController<DictateActivity>? = null

    private class FakeSession : BubbleService.SessionHandle {
        override fun start(): Boolean = true
        override fun submit() = Unit
        override fun discard() = Unit
        override fun destroy() = Unit
    }

    @After
    fun tearDown() {
        runCatching { card?.close() }
        runCatching { service?.destroy() }
        OverlayVisibility.clearForTest()
        BubbleSessionBus.clearForTest()
        MicrophoneOwner.clearForTest()
    }

    // --- harness -------------------------------------------------------------

    private fun bubbleService(): BubbleService {
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
        val c = Robolectric.buildService(BubbleService::class.java)
        service = c
        c.get().sessionFactory = { FakeSession() }
        c.get().historyProvider = { emptyList() }
        c.get().historyExecutor = java.util.concurrent.Executor { it.run() }
        return c.create().get()
    }

    private fun openCard(): DictateActivity {
        val c = Robolectric.buildActivity(DictateActivity::class.java).setup()
        card = c
        idle()
        return c.get()
    }

    /** The sink posts to the bubble's own view; nothing is observable until it runs. */
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun <T> field(service: BubbleService, name: String): T {
        val f = BubbleService::class.java.getDeclaredField(name)
        f.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return f.get(service) as T
    }

    private fun params(s: BubbleService): WindowManager.LayoutParams = field(s, "params")

    private fun root(s: BubbleService): View =
        requireNotNull(field<View?>(s, "root")) { "the bubble has no window root" }

    private fun takesTouches(s: BubbleService): Boolean =
        params(s).flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0

    /** Every observable that says "the bubble is off the card", in one place. */
    private fun assertBubbleIsOffTheScreen(s: BubbleService, why: String) {
        assertEquals(
            "$why: the bubble's window alpha is ${params(s).alpha}, so the " +
                "window manager is still compositing it over the card",
            0f, params(s).alpha, 0.0001f
        )
        assertNotEquals(
            "$why: the bubble's root view is still VISIBLE",
            View.VISIBLE, root(s).visibility
        )
        assertTrue(
            "$why: the bubble's window still takes touches. An overlay window " +
                "consumes every touch inside its bounds whether or not a view " +
                "there handles it, so an invisible-but-touchable bubble eats " +
                "taps meant for the card underneath — and on a device whose " +
                "sink has not run yet it is still a live mic button.",
            !takesTouches(s)
        )
    }

    private fun assertBubbleIsBack(s: BubbleService, why: String) {
        assertEquals("$why: window alpha", 1f, params(s).alpha, 0.0001f)
        assertEquals("$why: root visibility", View.VISIBLE, root(s).visibility)
        assertTrue("$why: the bubble is drawn but cannot be tapped", takesTouches(s))
    }

    // --- the guards ----------------------------------------------------------

    /**
     * THE regression case: the card is on screen, so the bubble is not.
     */
    @Test
    fun theBubbleIsOffTheScreenWhileTheCardIsUp() {
        val s = bubbleService()
        assertBubbleIsBack(s, "precondition — the bubble starts on screen")

        openCard()

        assertBubbleIsOffTheScreen(s, "the card is open")
    }

    /**
     * 🔴 THE COMPOSITION CASE. See the class kdoc: the screenshot path toggles
     * the same overlay, and its restore is unconditional.
     *
     * Deliberately driven through [DictaWindows] — the real capture seam, the
     * one `ScreenshotSequencer` calls — rather than through
     * [OverlayVisibility]. A test written against the holder API would only
     * be able to assert that the holder API behaves as written.
     */
    @Test
    fun aCaptureRestoreDoesNotPutTheBubbleBackOverAnOpenCard() {
        val s = bubbleService()
        val activity = openCard()
        assertBubbleIsOffTheScreen(s, "precondition — the card is open")

        val windows = DictaWindows(activity.window, DictateActivity.DIM_AMOUNT)
        windows.hideForCapture()
        idle()
        assertBubbleIsOffTheScreen(s, "mid-capture")

        windows.restoreAfterCapture()
        idle()

        assertBubbleIsOffTheScreen(
            s,
            "a screenshot finished and the bubble came back ON TOP OF the " +
                "still-open card. The capture's restore must give back only " +
                "what the capture took — the card's own hide outlives it"
        )
        // ...and the card itself IS back, so this is not passing because
        // everything stayed hidden.
        assertEquals(
            "the capture's restore did not put the CARD back; the user is " +
                "left staring at an invisible dialog",
            1f, activity.window.attributes.alpha, 0.0001f
        )
    }

    /**
     * The other half, and not optional: a user who closes the card must get
     * the bubble back, or Dicta's only always-available trigger is gone until
     * the service is restarted.
     *
     * The precondition is asserted, so this cannot pass by the bubble never
     * having been hidden at all.
     */
    @Test
    fun closingTheCardPutsTheBubbleBack() {
        val s = bubbleService()
        val c = Robolectric.buildActivity(DictateActivity::class.java).setup()
        card = c
        idle()
        assertBubbleIsOffTheScreen(s, "precondition — the card is open")

        c.pause().stop().destroy()
        idle()

        assertBubbleIsBack(s, "the card was closed")
    }

    /**
     * 🔴 The card does not have to be DESTROYED to stop covering the bubble.
     *
     * Without this case the `onStop` release is unreachable: `onDestroy`
     * releases the same hold, so every test that closes the card would pass
     * with `onStop` deleted. The state this pins is the ordinary one —
     * the user presses Home, or another app comes to the front, and the
     * Activity is merely stopped. Dicta's always-available trigger must come
     * back, and must go away again when the card returns.
     */
    @Test
    fun aCardMerelyStoppedPutsTheBubbleBackAndTakesItAgainOnReturn() {
        val s = bubbleService()
        val c = Robolectric.buildActivity(DictateActivity::class.java).setup()
        card = c
        idle()
        assertBubbleIsOffTheScreen(s, "precondition — the card is open")

        c.pause().stop()
        idle()
        assertBubbleIsBack(
            s,
            "the card was stopped but not destroyed and the bubble stayed " +
                "off screen — Dicta's always-available trigger is gone while " +
                "the user is in another app"
        )

        c.restart().resume()
        idle()
        assertBubbleIsOffTheScreen(s, "the card came back to the foreground")
    }

    /**
     * A capture that is still "in flight" when the card goes away must not
     * strand the bubble off screen. `onDestroy` releases the capture's hold
     * too — the behaviour the pre-change `OverlayVisibility.setVisible(true)`
     * in `onDestroy` provided, kept rather than inherited.
     */
    @Test
    fun aCardTornDownMidCaptureStillPutsTheBubbleBack() {
        val s = bubbleService()
        val c = Robolectric.buildActivity(DictateActivity::class.java).setup()
        card = c
        idle()
        DictaWindows(c.get().window, DictateActivity.DIM_AMOUNT).hideForCapture()
        idle()
        assertBubbleIsOffTheScreen(s, "precondition — mid-capture")

        c.pause().stop().destroy()
        idle()

        assertBubbleIsBack(s, "the card was destroyed mid-capture")
    }
}
