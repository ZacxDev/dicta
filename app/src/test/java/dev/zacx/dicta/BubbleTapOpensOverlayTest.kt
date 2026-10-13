package dev.zacx.dicta

import android.Manifest
import android.view.View
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

/**
 * 🔴 The actual behaviour change: **the first tap on the bubble must not open
 * the overlay.**
 *
 * Before this work, tapping the bubble and starting to record were the same
 * event — `BubbleService.openDictation()` ran `startActivity(...)` on every tap
 * and `DictateActivity.onCreate` began capture. The new flow records *in the
 * bubble* on tap 1 and only opens the card on tap 2, so this test was **watched
 * red on the pre-change tree** (it found a started `DictateActivity` intent)
 * and green after.
 *
 * ## Why reflection, and why it is not gratuitous
 *
 * The assertion has to be expressible against **both** trees, or "watched red"
 * is a claim about a test that could not compile before the change. The only
 * handle present in both is the service's private `bubble` field — the view the
 * user actually taps. Reaching it by reflection keeps this test's source
 * identical across the change, so its red-then-green really is evidence about
 * the behaviour and not about the API.
 *
 * [theServiceActuallyBuiltATappableBubble] is the positive control: a `null`
 * bubble (overlay permission refused, resources missing, Robolectric declining
 * to add the window) would make `assertNull(nextStartedActivity)` pass for a
 * reason that has nothing to do with the feature. A reassuring "no activity was
 * started" from a service that never built a button is indistinguishable from
 * the behaviour under test, so the control is load-bearing, not decoration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BubbleTapOpensOverlayTest {

    private var controller: org.robolectric.android.controller.ServiceController<BubbleService>? =
        null

    @After
    fun tearDown() {
        // The bubble registers an OverlayVisibility sink; a leaked sink from one
        // test would be invoked by the next and throw on a destroyed window.
        runCatching { controller?.destroy() }
        OverlayVisibility.clearForTest()
    }

    /**
     * Brings up the service with the two things it refuses to run without:
     * the overlay app-op, and (for the new flow) RECORD_AUDIO — without the
     * microphone permission tap 1 legitimately *does* open the overlay, because
     * a Service cannot call `requestPermissions` and `DictateActivity` is where
     * that ask lives. Granting it here is what makes this test about the new
     * inline-recording path rather than about the permission fallback.
     */
    private fun startService(): BubbleService {
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
        val c = Robolectric.buildService(BubbleService::class.java)
        controller = c
        return c.create().get()
    }

    private fun bubbleViewOf(service: BubbleService): View? {
        val field = BubbleService::class.java.getDeclaredField("bubble")
        field.isAccessible = true
        return field.get(service) as View?
    }

    @Test
    fun theServiceActuallyBuiltATappableBubble() {
        val service = startService()
        assertNotNull(
            "BubbleService did not build a bubble view, so every assertion " +
                "about tapping it is vacuous — a test that never taps anything " +
                "trivially starts no activity",
            bubbleViewOf(service),
        )
    }

    @Test
    fun theFirstTapDoesNotOpenTheOverlay() {
        val service = startService()
        val bubble = bubbleViewOf(service)
        assertNotNull("no bubble view to tap", bubble)

        bubble!!.performClick()

        val started = shadowOf(service).nextStartedActivity
        assertNull(
            "the FIRST tap on the bubble started an activity " +
                "(${started?.component?.className}). Under the inline-dictation " +
                "flow tap 1 starts recording and shows NO overlay; the card " +
                "opens on tap 2, already transcribing. A tap that opens the " +
                "card here is the pre-change behaviour.",
            started,
        )
    }
}
