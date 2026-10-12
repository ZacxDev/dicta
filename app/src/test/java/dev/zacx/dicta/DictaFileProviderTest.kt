package dev.zacx.dicta

import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The hand-rolled provider that serves the captured PNG to a share target.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictaFileProviderTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    @Test
    fun writePngRoundTripsThroughTheProvider() {
        val bitmap = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888)
        val uri = ScreenshotStore.writePng(ctx, bitmap, now = 1234567890L)

        assertEquals("content", uri.scheme)
        assertEquals("dev.zacx.dicta.files", uri.authority)
        assertEquals(listOf("dicta-1234567890.png"), uri.pathSegments)

        val cursor = requireNotNull(
            ctx.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null
            )
        )
        cursor.use {
            assertTrue("empty cursor — share targets would reject the attachment", it.moveToFirst())
            assertEquals(
                "dicta-1234567890.png",
                it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            )
            assertTrue(
                "reported size was 0",
                it.getLong(it.getColumnIndexOrThrow(OpenableColumns.SIZE)) > 0
            )
        }

        assertEquals("image/png", ctx.contentResolver.getType(uri))

        // And the bytes are a real PNG: 89 50 4E 47.
        val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertTrue("payload is not a PNG (${bytes.take(4)})",
            bytes.size > 8 &&
                bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
                bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
        )
    }

    @Test
    fun onlyTheLatestCaptureIsKept() {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        ScreenshotStore.writePng(ctx, bitmap, now = 1L)
        ScreenshotStore.writePng(ctx, bitmap, now = 2L)

        val names = ScreenshotStore.directory(ctx).listFiles()!!.map { it.name }
        assertEquals(listOf("dicta-2.png"), names)
    }

    /**
     * The *shape* guard: anything that is not exactly one path segment.
     * Asserted on its own message, because the escape guard below throws the
     * same exception type and a test that cannot tell them apart would go green
     * off whichever one happens to fire.
     */
    @Test
    fun nonSingleSegmentUrisAreRefused() {
        val root = File(ctx.cacheDir, "screenshots").apply { mkdirs() }
        for (hostile in listOf(
            "content://dev.zacx.dicta.files/../shared_prefs/secrets.xml",
            "content://dev.zacx.dicta.files/nested/deeper.png",
            "content://dev.zacx.dicta.files/",
        )) {
            val thrown = assertThrows(
                "accepted $hostile",
                IllegalArgumentException::class.java
            ) {
                DictaFileProvider.resolveIn(root, Uri.parse(hostile))
            }
            assertTrue(
                "wrong guard fired for $hostile: ${thrown.message}",
                thrown.message!!.contains("expected exactly one path segment")
            )
        }
    }

    /**
     * The *escape* guard, reached with URIs the shape guard cannot reject.
     *
     * This case exists because the first version of this test only used
     * multi-segment URIs, every one of which the `segments.size == 1` check
     * rejects first — so the parent-directory guard never executed and a
     * mutation that deleted it entirely still passed. These URIs decode to a
     * SINGLE segment, so the escape guard is the only thing standing between a
     * share target and an arbitrary app-private file.
     */
    @Test
    fun singleSegmentEscapesAreRefused() {
        val root = File(ctx.cacheDir, "screenshots").apply { mkdirs() }
        for (hostile in listOf(
            "content://dev.zacx.dicta.files/%2e%2e",              // ".."
            "content://dev.zacx.dicta.files/%2e%2e%2fdatabases",  // "../databases"
            "content://dev.zacx.dicta.files/%2fetc%2fpasswd",     // "/etc/passwd"
        )) {
            val uri = Uri.parse(hostile)
            // Reachability precondition: if this ever stops being 1, the test
            // has silently stopped exercising the escape guard.
            assertEquals(
                "$hostile no longer decodes to a single segment — this test " +
                    "would exercise the shape guard instead",
                1,
                uri.pathSegments.size
            )
            val thrown = assertThrows(
                "accepted $hostile",
                IllegalArgumentException::class.java
            ) {
                DictaFileProvider.resolveIn(root, uri)
            }
            assertTrue(
                "wrong guard fired for $hostile: ${thrown.message}",
                thrown.message!!.contains("refusing to serve")
            )
        }
    }

    /**
     * Positive control for the guard above: the same code path must ACCEPT a
     * legitimate name, or "everything is refused" would read as a working
     * guard.
     */
    @Test
    fun aLegitimateNameIsAccepted() {
        val root = File(ctx.cacheDir, "screenshots").apply { mkdirs() }
        val resolved = DictaFileProvider.resolveIn(
            root, Uri.parse("content://dev.zacx.dicta.files/dicta-7.png")
        )
        assertEquals("dicta-7.png", resolved.name)
        assertEquals(root.canonicalFile, resolved.parentFile)
    }
}
