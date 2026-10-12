package dev.zacx.dicta

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Pins the share intent wire format. Expected values are literals, written from
 * the Android `ACTION_SEND` contract rather than read back from
 * [ShareIntents].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareIntentsTest {

    private val dictated = "the quick brown fox jumped"
    private val image: Uri = Uri.parse("content://dev.zacx.dicta.files/dicta-42.png")

    @Test
    fun textWithImageCarriesBothExtrasAndTheUriGrant() {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val intent = ShareIntents.withImage(resolver, dictated, image, "Dicta")

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("image/png", intent.type)

        val stream: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
        assertNotNull("EXTRA_STREAM is null — nothing would be attached", stream)
        assertEquals(image, stream)

        assertEquals(dictated, intent.getStringExtra(Intent.EXTRA_TEXT))

        assertTrue(
            "FLAG_GRANT_READ_URI_PERMISSION not set — the receiver gets a " +
                "SecurityException reading the attachment",
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        )

        // The grant actually travels with the clip data, not with the extra.
        val clip = intent.clipData
        assertNotNull("no clipData — the URI grant would not be forwarded", clip)
        assertEquals(1, clip!!.itemCount)
        assertEquals(image, clip.getItemAt(0).uri)
    }

    /**
     * The image-only share: Send with a screenshot attached and an empty text
     * box. EXTRA_TEXT must be ABSENT, not empty — some targets render an empty
     * caption or body for `EXTRA_TEXT=""`.
     */
    @Test
    fun imageWithNoTextCarriesTheStreamAndNoTextExtra() {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val intent = ShareIntents.withImage(resolver, "", image, "Dicta")

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("image/png", intent.type)
        assertEquals(image, intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertFalse(
            "EXTRA_TEXT is present on an image-only share",
            intent.hasExtra(Intent.EXTRA_TEXT)
        )
        // The grant still has to travel, exactly as in the text+image case.
        assertTrue(
            "FLAG_GRANT_READ_URI_PERMISSION not set on an image-only share",
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        )
        assertEquals(1, intent.clipData!!.itemCount)
        assertEquals(image, intent.clipData!!.getItemAt(0).uri)
    }

    @Test
    fun textOnlyIsPlainTextWithNoStream() {
        val intent = ShareIntents.textOnly(dictated)

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals(dictated, intent.getStringExtra(Intent.EXTRA_TEXT))
        assertNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertNull(intent.clipData)
    }

    @Test
    fun chooserWrapsTheSendIntentAndKeepsTheGrant() {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val inner = ShareIntents.withImage(resolver, dictated, image, "Dicta")
        val chooser = ShareIntents.chooser(inner, "Send via…")

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertTrue(
            "the chooser wrapper must carry the grant too, or the chosen " +
                "target cannot read the attachment",
            chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        )

        val wrapped: Intent? = chooser.getParcelableExtra(Intent.EXTRA_INTENT)
        assertNotNull(wrapped)
        assertEquals(Intent.ACTION_SEND, wrapped!!.action)
        assertEquals("image/png", wrapped.type)
        assertEquals(dictated, wrapped.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(image, wrapped.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
    }

    /**
     * Not a reality check on receivers — nothing in-process can make a third
     * party honour EXTRA_TEXT. What IS checkable is that the app tells the user
     * so: the note string must actually warn about the drop. Pinned as the
     * whole normalised string, because a guard on individual words is walkable
     * by rewording.
     */
    @Test
    fun theUiWarnsThatReceiversMayDropTheText() {
        val res = RuntimeEnvironment.getApplication().resources
        assertEquals(
            "Screenshot attached. Some apps (most chat apps) drop the text " +
                "when an image is attached — use Copy if the text must arrive.",
            res.getString(R.string.screenshot_attached_note)
        )
    }
}
