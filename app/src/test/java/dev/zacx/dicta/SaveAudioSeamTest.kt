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
 * *Save audio* hands a file out of the app, so this covers the boundary rather
 * than only the happy path.
 *
 * ## What the boundary IS, after round 0
 *
 * An earlier draft pointed [DictaFileProvider] at `noBackupFilesDir/history`
 * and served the stored recording where it lies. That put the user's **entire
 * archive**, `index.json` included — every transcript — inside the provider's
 * reach, one level from `shared_prefs` and the encrypted bearer token, with
 * nothing but the absence of a caller between them.
 *
 * It now stages a copy into `cacheDir/share`. 🔴 **So the strongest assertion
 * in this file is a STRUCTURAL one — [theProviderCannotReachTheArchiveAtAll] —
 * because the archive is now unreachable by CONSTRUCTION rather than by a
 * guard that has to be right.** The traversal cases below still matter (the
 * staged file is still served through the same resolver) but they are no
 * longer the only thing standing between a share target and the transcripts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SaveAudioSeamTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    private fun shareDir(): File = ShareStage.directory(ctx)

    private fun archiveDir(): File =
        File(ctx.noBackupFilesDir, "history").apply { mkdirs() }

    private fun stagedRecording(name: String = "dicta-7.ogg"): Uri {
        val source = File(archiveDir(), name).apply { writeBytes(OGG) }
        return ShareStage.stage(ctx, source)
    }

    // --- the structural guarantee -----------------------------------------

    /**
     * 🔴 The point of the whole redesign: no root the provider knows resolves
     * anywhere near the dictation archive. Fails if a future change adds the
     * archive back as a root — which is what round 0 caught.
     */
    @Test
    fun theProviderCannotReachTheArchiveAtAll() {
        val archive = archiveDir().canonicalPath
        for (root in listOf(DictaFileProvider.ROOT_SCREENSHOTS, DictaFileProvider.ROOT_SHARE)) {
            val dir = DictaFileProvider.directoryFor(ctx, root).canonicalPath
            assertNotEquals("root <$root> IS the dictation archive", archive, dir)
            assertTrue(
                "root <$root> resolves to $dir, which is inside the archive " +
                    "$archive — the provider must not be able to reach it",
                !dir.startsWith("$archive/"),
            )
            assertTrue(
                "root <$root> resolves to $dir, which is OUTSIDE cacheDir — " +
                    "every servable root must be transient cache",
                dir.startsWith(ctx.cacheDir.canonicalPath + "/"),
            )
        }
    }

    /** Staging copies rather than moves: the archive keeps its recording. */
    @Test
    fun stagingLeavesTheOriginalInTheArchive() {
        val source = File(archiveDir(), "dicta-9.ogg").apply { writeBytes(OGG) }
        ShareStage.stage(ctx, source)
        assertTrue("staging destroyed the user's stored recording", source.isFile)
        assertEquals(OGG.toList(), source.readBytes().toList())
    }

    /** One staged file at a time, so the cache cannot grow without bound. */
    @Test
    fun stagingASecondRecordingDropsTheFirst() {
        ShareStage.stage(ctx, File(archiveDir(), "a.ogg").apply { writeBytes(OGG) })
        ShareStage.stage(ctx, File(archiveDir(), "b.ogg").apply { writeBytes(OGG) })
        assertEquals(
            "a stale recording stayed staged and could be attached to a later share",
            listOf("b.ogg"),
            shareDir().listFiles().orEmpty().map { it.name }.sorted(),
        )
    }

    // --- the control: it must actually serve the staged recording ---------

    /**
     * 🔴 Positive control. Without it every refusal below is satisfied by a
     * provider that refuses *everything* — green here while shipping a dead
     * button.
     */
    @Test
    fun aStagedRecordingIsServedThroughTheProvider() {
        val uri = stagedRecording()

        val cursor = requireNotNull(
            ctx.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null,
            )
        )
        cursor.use {
            assertTrue("the provider returned no row for a staged file", it.moveToFirst())
            assertEquals("dicta-7.ogg", it.getString(0))
            assertEquals(OGG.size.toLong(), it.getLong(1))
        }

        ctx.contentResolver.openInputStream(uri).use { stream ->
            assertEquals(
                "the bytes served are not the bytes staged",
                OGG.toList(),
                requireNotNull(stream).readBytes().toList(),
            )
        }
    }

    // --- the refusals ------------------------------------------------------

    @Test
    fun aTraversalOutOfTheShareRootIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            DictaFileProvider.resolveName(shareDir(), "../../shared_prefs/dicta_stt.xml")
        }
    }

    @Test
    fun aPathIntoASubdirectoryIsRefused() {
        File(shareDir(), "nested").mkdirs()
        assertThrows(IllegalArgumentException::class.java) {
            DictaFileProvider.resolveName(shareDir(), "nested/secret.ogg")
        }
    }

    /**
     * 🔴 An unknown root is refused rather than joined onto a path. The
     * rejected implementation — `File(dataDir, root)` — would make `..` a
     * traversal one level ABOVE the guard above.
     */
    @Test
    fun anUnknownRootIsRefused() {
        for (bad in listOf("history", "shared_prefs", "..")) {
            assertThrows("root <$bad> was accepted", IllegalArgumentException::class.java) {
                DictaFileProvider.directoryFor(ctx, bad)
            }
        }
    }

    /**
     * A one-segment URI is refused rather than defaulting to a root. A default
     * would make the other root reachable by OMISSION.
     */
    @Test
    fun aUriWithNoRootIsRefused() {
        val rootless = Uri.Builder()
            .scheme("content").authority(DictaFileProvider.AUTHORITY)
            .appendPath("dicta-7.ogg").build()
        assertThrows(IllegalArgumentException::class.java) {
            ctx.contentResolver.getType(rootless)
        }
    }

    // --- the declared type, read from BYTES --------------------------------

    /**
     * 🔴 The defect round 0 surfaced. `DictationSession` names every capture
     * `.ogg` unconditionally, but `AudioCaptureLogic.pickEncoder` falls back
     * to AAC-LC in MPEG-4 when Opus will not initialise — so a file called
     * `.ogg` can hold MP4, and trusting the extension hands a share target a
     * type it cannot open.
     */
    @Test
    fun theTypeComesFromTheBytesNotTheName() {
        val mp4UnderAnOggName = File(archiveDir(), "dicta-lying.ogg")
            .apply { writeBytes(MP4) }
        assertEquals(
            "an MP4 recording stored under a .ogg name must NOT be declared " +
                "audio/ogg — the receiving app acts on the type",
            ShareStage.MIME_MP4,
            ShareStage.mimeOf(mp4UnderAnOggName),
        )

        val realOgg = File(archiveDir(), "dicta-honest.ogg").apply { writeBytes(OGG) }
        assertEquals(ShareStage.MIME_OGG, ShareStage.mimeOf(realOgg))
    }

    @Test
    fun anUnrecognisedContainerIsNotGuessed() {
        val junk = File(archiveDir(), "dicta-junk.ogg")
            .apply { writeBytes(ByteArray(12) { 0x7A }) }
        assertEquals(DictaFileProvider.MIME_UNKNOWN, ShareStage.mimeOf(junk))

        val tooShort = File(archiveDir(), "dicta-short.ogg")
            .apply { writeBytes(byteArrayOf(1, 2)) }
        assertEquals(
            "a truncated file must not be guessed at either",
            DictaFileProvider.MIME_UNKNOWN,
            ShareStage.mimeOf(tooShort),
        )
    }

    /** A screenshot still declares PNG — that root is written by this app. */
    @Test
    fun theScreenshotRootStillDeclaresPng() {
        val shot = File(ScreenshotStore.directory(ctx), "shot.png")
            .apply { writeBytes(ByteArray(12)) }
        val uri = DictaFileProvider.uriFor(DictaFileProvider.ROOT_SCREENSHOTS, shot.name)
        assertEquals(DictaFileProvider.MIME_PNG, ctx.contentResolver.getType(uri))
    }

    private companion object {
        /** `OggS` capture pattern plus filler to reach the 12-byte read. */
        val OGG = "OggS".toByteArray() + ByteArray(8) { it.toByte() }

        /** ISO-BMFF: four size bytes, then `ftyp` at offset 4. */
        val MP4 = byteArrayOf(0, 0, 0, 0x18) + "ftyp".toByteArray() + "M4A ".toByteArray()
    }
}
