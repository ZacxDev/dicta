package dev.zacx.dicta

import android.net.Uri
import android.provider.OpenableColumns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * *Save audio* hands a file out of the app, so this covers the boundary that
 * widening created rather than only the happy path.
 *
 * 🔴 **What changed is the blast radius, not just the feature.**
 * [DictaFileProvider] served one root — `cacheDir/screenshots`, holding a
 * single transient PNG. It now also serves `noBackupFilesDir/history`, which
 * sits **beside `shared_prefs`**, where the encrypted bearer-token blob lives.
 * A traversal that was previously worth one throwaway screenshot is now worth
 * the token and the user's entire dictation archive, so the refusals below are
 * the point of this file and the round trip is the control.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SaveAudioSeamTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    private fun historyDir(): File =
        DictationHistoryStore.directory(ctx).apply { mkdirs() }

    private fun writeRecording(name: String, bytes: ByteArray = BYTES): File =
        File(historyDir(), name).apply { writeBytes(bytes) }

    // --- the control: it must actually serve a recording -------------------

    /**
     * 🔴 Positive control. Without this, every refusal below is satisfied by a
     * provider that refuses *everything* — which would pass this file while
     * shipping a dead button.
     */
    @Test
    fun aStoredRecordingIsServedThroughTheProvider() {
        writeRecording("dicta-7.ogg")
        val uri = DictaFileProvider.uriFor(DictaFileProvider.ROOT_HISTORY, "dicta-7.ogg")

        val cursor = requireNotNull(
            ctx.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null,
            )
        )
        cursor.use {
            assertTrue("the provider returned no row for a file that exists", it.moveToFirst())
            assertEquals("dicta-7.ogg", it.getString(0))
            assertEquals(BYTES.size.toLong(), it.getLong(1))
        }

        ctx.contentResolver.openInputStream(uri).use { stream ->
            assertEquals(
                "the bytes served are not the bytes stored",
                BYTES.toList(),
                requireNotNull(stream).readBytes().toList(),
            )
        }
    }

    // --- the refusals ------------------------------------------------------

    /**
     * 🔴 The one that matters most. `shared_prefs` is a SIBLING of the history
     * directory and holds `stt_token_ciphertext_v1`.
     */
    @Test
    fun aTraversalOutOfTheHistoryRootIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            DictaFileProvider.resolveName(historyDir(), "../shared_prefs/dicta_stt.xml")
        }
    }

    @Test
    fun aDeeperTraversalOutOfTheHistoryRootIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            DictaFileProvider.resolveName(historyDir(), "../../../../etc/passwd")
        }
    }

    /**
     * 🔴 A subdirectory is refused too, not merely an escape. The guard is
     * "lands DIRECTLY inside the root", and a provider that allowed descent
     * would serve anything reachable below it.
     */
    @Test
    fun aPathIntoASubdirectoryIsRefused() {
        File(historyDir(), "nested").mkdirs()
        File(historyDir(), "nested/secret.ogg").writeBytes(BYTES)
        assertThrows(IllegalArgumentException::class.java) {
            DictaFileProvider.resolveName(historyDir(), "nested/secret.ogg")
        }
    }

    /**
     * 🔴 An unknown root is refused rather than ignored. The hazard this
     * closes is the *other* implementation — joining the segment onto the data
     * dir — which would make `..` a root-level traversal, one level above the
     * guard above.
     */
    @Test
    fun anUnknownRootIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            DictaFileProvider.directoryFor(ctx, "shared_prefs")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DictaFileProvider.directoryFor(ctx, "..")
        }
    }

    /**
     * 🔴 A one-segment URI is refused rather than defaulting to screenshots.
     * A default would make the history root reachable by OMITTING the root,
     * which is the shape nobody writes a test for.
     */
    @Test
    fun aUriWithNoRootIsRefused() {
        val rootless = Uri.Builder()
            .scheme("content")
            .authority(DictaFileProvider.AUTHORITY)
            .appendPath("dicta-7.ogg")
            .build()
        assertThrows(IllegalArgumentException::class.java) {
            DictaFileProvider.mimeFor(rootless)
        }
    }

    /**
     * The two roots are genuinely different directories — otherwise every
     * refusal above is about one directory wearing two names.
     */
    @Test
    fun theTwoRootsAreDifferentDirectories() {
        assertNotEquals(
            DictaFileProvider.directoryFor(ctx, DictaFileProvider.ROOT_SCREENSHOTS)
                .canonicalPath,
            DictaFileProvider.directoryFor(ctx, DictaFileProvider.ROOT_HISTORY)
                .canonicalPath,
        )
    }

    // --- the declared type -------------------------------------------------

    @Test
    fun theTypeFollowsTheStoredExtension() {
        assertEquals("audio/ogg", mime("dicta-1.ogg"))
        assertEquals("audio/mp4", mime("dicta-2.m4a"))
        assertEquals(
            "a screenshot must still declare PNG",
            DictaFileProvider.MIME_PNG,
            DictaFileProvider.mimeFor(
                DictaFileProvider.uriFor(DictaFileProvider.ROOT_SCREENSHOTS, "shot.png")
            ),
        )
    }

    /**
     * 🔴 An unrecognised extension is NOT guessed as Ogg. The encoder is
     * chosen at runtime (Opus *or* AAC), so a container we have not read must
     * not be asserted — a share target handed an `.m4a` labelled `audio/ogg`
     * fails to open it, and the failure surfaces in the other app.
     */
    @Test
    fun anUnknownExtensionIsNotGuessed() {
        assertEquals(DictaFileProvider.MIME_UNKNOWN, mime("dicta-3.weird"))
        assertEquals(DictaFileProvider.MIME_UNKNOWN, mime("dicta-4"))
        assertNotEquals(
            "'no extension' must not resolve to a real audio type",
            "audio/ogg",
            mime("dicta-4"),
        )
    }

    private fun mime(name: String): String =
        DictaFileProvider.mimeFor(
            DictaFileProvider.uriFor(DictaFileProvider.ROOT_HISTORY, name)
        )

    private companion object {
        val BYTES = byteArrayOf(1, 2, 3, 4, 5, 6, 7)
    }
}
