package dev.zacx.dicta

import android.content.Intent
import android.net.Uri
import android.widget.EditText
import android.widget.ImageButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * BEHAVIOUR change: Send must work with an image and no text.
 *
 * `shareText()` used to return early whenever the text box was empty, which
 * made "take a screenshot and send just the screenshot" impossible — the
 * Screenshot button would attach a capture and Send would then refuse to do
 * anything with it. The guard now refuses only when there is **neither** text
 * nor an attached image.
 *
 * These assertions go through the real click listener on `share_button` and
 * read the intent the activity actually started, so they cover the activity's
 * branch as well as [ShareIntents]' wire format. Both halves are pinned,
 * because relaxing a guard is exactly the change that can relax it too far:
 *
 *  * [imageWithNoTextStartsAShareCarryingTheStream] — the new capability.
 *  * [nothingAtAllStillRefusesToShare] — the case that must still be refused.
 *
 * Observed red before the change, by hand-rolled partial revert on this tree
 * (restoring `if (text.isEmpty()) { toast; return }` as the first statement of
 * `shareText`): [imageWithNoTextStartsAShareCarryingTheStream] FAILED with "no
 * share intent was started", and [nothingAtAllStillRefusesToShare] stayed
 * green. The matrix is in the PR body.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictateShareTest {

    private val shot: Uri = Uri.parse("content://dev.zacx.dicta.files/dicta-42.png")

    /**
     * `onCreate` requests RECORD_AUDIO, which Robolectric records as a started
     * activity. Drain the queue immediately before the click so that what comes
     * back afterwards is unambiguously the result of THIS click — otherwise a
     * refusal assertion reads the permission intent and fails for the wrong
     * reason, and an acceptance assertion could in principle read a stale one.
     */
    private fun drainStartedActivities(activity: DictateActivity) {
        @Suppress("ControlFlowWithEmptyBody")
        while (shadowOf(activity).nextStartedActivity != null) {
        }
    }

    private fun clickSend(activity: DictateActivity, text: String, image: Uri?) {
        activity.screenshotUri = image
        activity.findViewById<EditText>(R.id.dictated_text).setText(text)
        drainStartedActivities(activity)
        activity.findViewById<ImageButton>(R.id.share_button).performClick()
    }

    @Test
    fun imageWithNoTextStartsAShareCarryingTheStream() {
        val controller = Robolectric.buildActivity(DictateActivity::class.java).setup()
        try {
            val activity = controller.get()
            clickSend(activity, text = "", image = shot)

            val started = shadowOf(activity).nextStartedActivity
            assertNotNull(
                "no share intent was started — Send refused an image-only share",
                started
            )
            assertEquals(Intent.ACTION_CHOOSER, started!!.action)

            val inner: Intent? = started.getParcelableExtra(Intent.EXTRA_INTENT)
            assertNotNull(inner)
            assertEquals(Intent.ACTION_SEND, inner!!.action)
            assertEquals("image/png", inner.type)
            assertEquals(
                "the screenshot is not attached",
                shot,
                inner.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            )
            assertFalse(
                "EXTRA_TEXT is present on an image-only share",
                inner.hasExtra(Intent.EXTRA_TEXT)
            )
            assertTrue(
                "the chooser wrapper lost the URI grant",
                started.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
            )
        } finally {
            controller.close()
        }
    }

    /** Unchanged: text and no image is still a plain-text share. */
    @Test
    fun textWithNoImageIsStillAPlainTextShare() {
        val controller = Robolectric.buildActivity(DictateActivity::class.java).setup()
        try {
            val activity = controller.get()
            clickSend(activity, text = "hello there", image = null)

            val inner: Intent? = shadowOf(activity).nextStartedActivity
                ?.getParcelableExtra(Intent.EXTRA_INTENT)
            assertNotNull("no share intent was started", inner)
            assertEquals("text/plain", inner!!.type)
            assertEquals("hello there", inner.getStringExtra(Intent.EXTRA_TEXT))
            assertNull(inner.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        } finally {
            controller.close()
        }
    }

    /**
     * The half the relaxation must NOT take with it: empty text AND no
     * attachment still refuses, and starts nothing.
     */
    @Test
    fun nothingAtAllStillRefusesToShare() {
        val controller = Robolectric.buildActivity(DictateActivity::class.java).setup()
        try {
            val activity = controller.get()
            clickSend(activity, text = "", image = null)

            assertNull(
                "an empty-handed Send started a share sheet",
                shadowOf(activity).nextStartedActivity
            )
        } finally {
            controller.close()
        }
    }

    /**
     * Whitespace is not content. `currentText()` trims, so a text box holding
     * only spaces with no attachment must also refuse — otherwise the relaxed
     * guard would leak a share of an empty string.
     */
    @Test
    fun whitespaceOnlyWithNoImageStillRefusesToShare() {
        val controller = Robolectric.buildActivity(DictateActivity::class.java).setup()
        try {
            val activity = controller.get()
            clickSend(activity, text = "   \n  ", image = null)

            assertNull(
                "a whitespace-only Send started a share sheet",
                shadowOf(activity).nextStartedActivity
            )
        } finally {
            controller.close()
        }
    }
}
