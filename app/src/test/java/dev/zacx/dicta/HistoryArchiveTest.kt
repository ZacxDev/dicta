package dev.zacx.dicta

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * History export and import ([DictationHistoryStore.exportArchive],
 * [DictationHistoryStore.importArchive], [HistoryArchive]) against real temp
 * directories.
 *
 * ## 🔴 "Writes nothing" is asserted as a SNAPSHOT, not as a count
 *
 * Every rejection test imports into a store that already holds an item, and
 * compares the target directory's complete contents — every file name and
 * every byte, index included — before and after. A count of items would pass
 * an import that wrote an orphan audio file, or rewrote the index with the same
 * rows; the snapshot cannot. The staging directory's absence is asserted too.
 *
 * ## 🔴 Tampering RESEALS the zip, so the guard under test is the one reached
 *
 * Flipping a byte in a raw zip is caught by the zip's own CRC-32 before the
 * manifest's SHA-256 is ever consulted — a test that did that would credit
 * the checksum guard with a kill it never made. [rezip] rewrites the archive
 * with fresh CRCs, so a changed audio byte reaches the SHA-256 comparison; and
 * [reseal] also recomputes the manifest's hashes, so a metadata change reaches
 * the metadata checks instead of dying at the checksum.
 *
 * Fixtures are synthetic byte patterns and invented sentences; no real
 * dictation data is used anywhere in this repository.
 *
 * Robolectric for `org.json` only — see [DictationHistoryStoreTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryArchiveTest {

    @get:Rule
    val temp = TemporaryFolder()

    private var clock = 1_700_000_000_000L

    private val sourceDir: File by lazy { temp.newFolder("sources") }

    private fun store(name: String): DictationHistoryStore =
        DictationHistoryStore(File(temp.root, name), { clock })

    private var serial = 0

    /** A source recording whose bytes are distinct per call (no two fixtures share content). */
    private fun recording(ext: String = "ogg", bytes: Int = 300): File {
        val n = ++serial
        val name = if (ext.isEmpty()) "rec-$n" else "rec-$n.$ext"
        return File(sourceDir, name).apply {
            writeBytes(ByteArray(bytes) { ((it * 31 + n * 17) and 0xff).toByte() })
        }
    }

    /** Three items whose every field differs pairwise. */
    private fun seeded(name: String): DictationHistoryStore {
        val s = store(name)
        clock += 1_000
        s.add(recording("ogg", 300), "the heron stood very still", fromRemote = true, audioSeconds = 3.217)
        clock += 1_000
        s.add(recording("m4a", 517), "ünïcödé — and \"quotes\"\nnewline", fromRemote = false, audioSeconds = 0.5)
        clock += 1_000
        s.add(recording("", 1), "", fromRemote = false, audioSeconds = 12.0)
        return s
    }

    private fun exportBytes(s: DictationHistoryStore): ByteArray =
        ByteArrayOutputStream().also { s.exportArchive(it) }.toByteArray()

    // Not Int.MAX_VALUE: recent() pre-sizes its result list to the limit.
    private fun all(s: DictationHistoryStore) = s.recent(1_000)

    /** Every file under [dir], relative path to bytes. */
    private fun snapshot(dir: File): Map<String, List<Byte>> =
        dir.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(dir).path to it.readBytes().toList() }

    private fun stagingDir(s: DictationHistoryStore) =
        File(s.dir.absoluteFile.parentFile, s.dir.name + "-import-staging")

    // --- zip surgery --------------------------------------------------------

    private fun entries(zip: ByteArray): LinkedHashMap<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                out[e.name] = z.readBytes()
            }
        }
        return out
    }

    /** Writes [entries] as a fresh zip — new CRCs, so only the manifest can object. */
    private fun rezip(entries: Map<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun sha(b: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun manifest(e: Map<String, ByteArray>) = JSONObject(String(e.getValue(HistoryArchive.MANIFEST)))

    /** Recomputes every listed file's sha256 and bytes, so tampering reaches the metadata checks. */
    private fun reseal(e: LinkedHashMap<String, ByteArray>): ByteArray {
        val m = manifest(e)
        val files = m.getJSONArray("files")
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            val data = e[f.getString("path")] ?: continue
            f.put("sha256", sha(data)).put("bytes", data.size)
        }
        e[HistoryArchive.MANIFEST] = m.toString().toByteArray()
        return rezip(e)
    }

    private fun editItems(e: LinkedHashMap<String, ByteArray>, edit: (JSONObject) -> Unit) {
        val items = JSONObject(String(e.getValue(HistoryArchive.ITEMS)))
        edit(items)
        e[HistoryArchive.ITEMS] = items.toString().toByteArray()
    }

    /**
     * The LARGEST audio entry. Not the first: the newest fixture is one byte
     * long, and a one-byte file reversed is itself — which made the
     * same-length tamper below a no-op on its first run.
     */
    private fun firstAudio(e: Map<String, ByteArray>) =
        e.keys.filter { it.startsWith(HistoryArchive.AUDIO_PREFIX) }.maxBy { e.getValue(it).size }

    /**
     * Imports [archive] into a target that already holds one item and asserts
     * it is refused for [reason] with the target byte-for-byte unchanged.
     */
    private fun assertRefused(archive: ByteArray, reason: HistoryArchive.Reason, detail: String? = null) {
        val target = store("target-${++serial}")
        target.add(recording("ogg", 40), "already here", fromRemote = false, audioSeconds = 1.0)
        val before = snapshot(target.dir)
        try {
            target.importArchive(ByteArrayInputStream(archive))
            fail("an archive that should be refused for $reason was imported")
        } catch (r: HistoryArchive.Rejected) {
            assertEquals(r.message, reason, r.reason)
            // Several metadata guards share INVALID_METADATA, so the reason
            // alone cannot tell which one fired; the detail can.
            if (detail != null) assertTrue("refused by the wrong guard: ${r.message}", r.message!!.contains(detail))
        }
        assertEquals("a refused import changed the history directory", before, snapshot(target.dir))
        assertFalse("staging was left behind", stagingDir(target).exists())
    }

    // --- round trip ---------------------------------------------------------

    @Test
    fun anExportImportedIntoAnEmptyStoreIsByteIdenticalWithEqualMetadata() {
        val source = seeded("source")
        val archive = exportBytes(source)
        val target = store("target")

        val result = target.importArchive(ByteArrayInputStream(archive))

        assertEquals(DictationHistoryStore.ImportResult(imported = 3, skipped = 0, rejected = 0), result)
        val want = all(source)
        assertEquals(3, want.size)
        assertEquals(want, all(target))
        for (item in want) {
            assertArrayEquals(
                "audio for ${item.id} is not byte-identical",
                source.audioFor(item.id)!!.readBytes(),
                target.audioFor(item.id)!!.readBytes(),
            )
        }
        assertFalse(stagingDir(target).exists())
    }

    @Test
    fun anExportIsAReadAndCountsOnlyItemsWhoseAudioIsPresent() {
        val source = seeded("source")
        val gone = all(source).last()
        source.audioFor(gone.id)!!.delete()
        val before = snapshot(source.dir)

        val out = ByteArrayOutputStream()
        val count = source.exportArchive(out)

        assertEquals(2, count)
        assertEquals("an export wrote to the history directory", before, snapshot(source.dir))
        val target = store("target")
        assertEquals(2, target.importArchive(ByteArrayInputStream(out.toByteArray())).imported)
        assertTrue(all(target).none { it.id == gone.id })
    }

    @Test
    fun verifyReadsTheWholeArchiveBackAndWritesNothing() {
        val source = seeded("source")
        val target = store("target")
        assertEquals(3, target.verifyArchive(ByteArrayInputStream(exportBytes(source))))
        assertFalse("verify created the history directory", target.dir.exists())
        assertFalse(stagingDir(target).exists())
    }

    // --- merge --------------------------------------------------------------

    @Test
    fun importIntoANonEmptyStoreKeepsItsItemsAndNeverOverwritesADuplicate() {
        val source = seeded("source")
        val archive = exportBytes(source)
        val target = store("target")
        clock += 5_000
        val ownId = target.add(recording("ogg", 77), "the target's own", fromRemote = false, audioSeconds = 2.0)
        val ownAudio = target.audioFor(ownId)!!.readBytes()
        assertEquals(3, target.importArchive(ByteArrayInputStream(archive)).imported)

        // The same id now carries different text on the target. A second
        // import must skip it, not restore the archive's text over it.
        val dup = all(source).first()
        target.updateText(dup.id, "edited on the target", fromRemote = false)

        val again = target.importArchive(ByteArrayInputStream(archive))

        assertEquals(DictationHistoryStore.ImportResult(imported = 0, skipped = 3, rejected = 0), again)
        assertEquals("edited on the target", target.item(dup.id)!!.text)
        assertEquals(4, all(target).size)
        assertArrayEquals(ownAudio, target.audioFor(ownId)!!.readBytes())
        assertEquals("the target's own", target.item(ownId)!!.text)
    }

    @Test
    fun aPartlyOverlappingArchiveImportsOnlyTheNewIds() {
        val source = seeded("source")
        val target = store("target")
        target.importArchive(ByteArrayInputStream(exportBytes(source)))
        clock += 1_000
        val newId = source.add(recording("ogg", 90), "added after the first export", fromRemote = true, audioSeconds = 4.0)

        val result = target.importArchive(ByteArrayInputStream(exportBytes(source)))

        assertEquals(DictationHistoryStore.ImportResult(imported = 1, skipped = 3, rejected = 0), result)
        assertEquals(source.item(newId), target.item(newId))
    }

    @Test
    fun anOrphanAudioFileIsAdoptedOnlyWhenItsBytesMatchAndIsNeverReplaced() {
        val source = seeded("source")
        val archive = exportBytes(source)
        val items = all(source)
        val target = store("target")
        // An index must exist: a directory holding audio and NO index is
        // swept by the store's existing corrupt-index recovery before any
        // import logic runs, which is not the case under test.
        target.add(recording("ogg", 33), "the target's own", fromRemote = false, audioSeconds = 1.0)
        // An interrupted earlier import: audio moved in, index never written.
        source.audioFor(items[0].id)!!.copyTo(File(target.dir, items[0].audioFile))
        // A same-named unindexed file with DIFFERENT bytes.
        val foreign = byteArrayOf(9, 9, 9)
        File(target.dir, items[1].audioFile).writeBytes(foreign)

        val result = target.importArchive(ByteArrayInputStream(archive))

        assertEquals(DictationHistoryStore.ImportResult(imported = 2, skipped = 0, rejected = 1), result)
        assertEquals(items[0], target.item(items[0].id))
        assertArrayEquals("an unindexed file was overwritten", foreign, File(target.dir, items[1].audioFile).readBytes())
        assertEquals(null, target.item(items[1].id))
    }

    @Test
    fun aCommitThatFailsAtTheIndexWriteTakesBackTheAudioItMovedIn() {
        val archive = exportBytes(seeded("source"))
        val target = store("target")
        target.add(recording("ogg", 40), "already here", fromRemote = false, audioSeconds = 1.0)
        // The index write opens index.json.tmp for writing; a DIRECTORY of that
        // name makes it throw after every audio file has been moved in.
        val blocker = File(target.dir, DictationHistoryStore.INDEX + ".tmp").apply { mkdirs() }
        val before = snapshot(target.dir)

        try {
            target.importArchive(ByteArrayInputStream(archive))
            fail("the import reported success over a failed index write")
        } catch (_: java.io.IOException) {
        }

        assertEquals("a failed commit left audio behind", before, snapshot(target.dir))
        blocker.delete()
        assertEquals(1, all(target).size)
    }

    // --- rejection: nothing written ----------------------------------------

    @Test
    fun aChangedAudioByteIsRefusedByItsChecksum() {
        val e = entries(exportBytes(seeded("source")))
        val path = firstAudio(e)
        e[path] = e.getValue(path).copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertRefused(rezip(e), HistoryArchive.Reason.CHECKSUM_MISMATCH)
    }

    @Test
    fun aChangedTranscriptIsRefusedByTheItemsChecksum() {
        val e = entries(exportBytes(seeded("source")))
        editItems(e) { it.getJSONArray("items").getJSONObject(0).put("text", "tampered") }
        assertRefused(rezip(e), HistoryArchive.Reason.CHECKSUM_MISMATCH)
    }

    @Test
    fun aSameLengthAudioWithTheRightSizeButWrongHashIsRefused() {
        // Same size, different bytes: a size check alone would pass this.
        val e = entries(exportBytes(seeded("source")))
        val path = firstAudio(e)
        e[path] = e.getValue(path).reversedArray()
        assertRefused(rezip(e), HistoryArchive.Reason.CHECKSUM_MISMATCH)
    }

    @Test
    fun everyTruncationBeforeTheCentralDirectoryIsRefused() {
        val archive = exportBytes(seeded("source"))
        // The central directory starts at the offset the end record names.
        val eocd = (archive.size - 22 downTo 0).first {
            archive[it] == 0x50.toByte() && archive[it + 1] == 0x4b.toByte() &&
                archive[it + 2] == 0x05.toByte() && archive[it + 3] == 0x06.toByte()
        }
        val cdStart = (archive[eocd + 16].toInt() and 0xff) or
            ((archive[eocd + 17].toInt() and 0xff) shl 8) or
            ((archive[eocd + 18].toInt() and 0xff) shl 16) or
            ((archive[eocd + 19].toInt() and 0xff) shl 24)
        assertTrue("fixture too small to sweep", cdStart > 200)
        val cuts = (0 until cdStart step 37).toList() + listOf(cdStart - 1, cdStart / 2)
        val refusedFor = HashSet<HistoryArchive.Reason>()
        for (cut in cuts) {
            val target = store("cut-$cut")
            target.add(recording("ogg", 20), "already here", fromRemote = false, audioSeconds = 1.0)
            val before = snapshot(target.dir)
            try {
                target.importArchive(ByteArrayInputStream(archive.copyOf(cut)))
                fail("an archive truncated to $cut of ${archive.size} bytes was imported")
            } catch (r: HistoryArchive.Rejected) {
                refusedFor += r.reason
            }
            assertEquals("truncation at $cut changed history", before, snapshot(target.dir))
        }
        // Positive control on the sweep: it really reached past the start
        // (mid-entry and missing-manifest are both exercised).
        assertTrue(refusedFor.toString(), HistoryArchive.Reason.MALFORMED in refusedFor)
        assertTrue(refusedFor.toString(), HistoryArchive.Reason.NO_MANIFEST in refusedFor)
    }

    @Test
    fun anUnknownFormatVersionIsRefused() {
        val e = entries(exportBytes(seeded("source")))
        e[HistoryArchive.MANIFEST] = manifest(e).put("formatVersion", 2).toString().toByteArray()
        assertRefused(rezip(e), HistoryArchive.Reason.UNSUPPORTED_VERSION)
    }

    @Test
    fun aDifferentFormatNameIsRefused() {
        val e = entries(exportBytes(seeded("source")))
        e[HistoryArchive.MANIFEST] = manifest(e).put("format", "some-other-archive").toString().toByteArray()
        assertRefused(rezip(e), HistoryArchive.Reason.WRONG_FORMAT)
    }

    @Test
    fun aListedFileMissingFromTheArchiveIsRefused() {
        val e = entries(exportBytes(seeded("source")))
        e.remove(firstAudio(e))
        assertRefused(rezip(e), HistoryArchive.Reason.MISSING_FILE)
    }

    @Test
    fun anUnlistedFileIsRefusedAndAHostileNameWritesNowhere() {
        val e = entries(exportBytes(seeded("source")))
        e["../../escaped.ogg"] = byteArrayOf(1, 2, 3)
        assertRefused(rezip(e), HistoryArchive.Reason.UNEXPECTED_FILE)
        assertTrue(temp.root.walkTopDown().none { it.name == "escaped.ogg" })
    }

    @Test
    fun aFileThatIsNotAZipIsRefused() {
        assertRefused(ByteArray(4096) { (it * 7).toByte() }, HistoryArchive.Reason.NO_MANIFEST)
    }

    @Test
    fun aDuplicateItemIdIsRefusedEvenWithValidChecksums() {
        // 🔴 The duplicate's AUDIO is renamed to match its new id too. The
        // first draft changed only the id, so the audio-path check refused the
        // archive first with the same reason — and a mutant that deleted the
        // duplicate-id guard SURVIVED. Items 0 and 1 have different
        // extensions (.ogg / .m4a), so the renamed path stays unique.
        val e = entries(exportBytes(seeded("source")))
        val items = JSONObject(String(e.getValue(HistoryArchive.ITEMS))).getJSONArray("items")
        val keepId = items.getJSONObject(0).getString("id")
        val oldPath = items.getJSONObject(1).getString("audio")
        val newPath = "audio/$keepId." + oldPath.substringAfterLast('.')
        assertTrue(newPath != items.getJSONObject(0).getString("audio"))
        e[newPath] = e.remove(oldPath)!!
        val m = manifest(e)
        val files = m.getJSONArray("files")
        for (i in 0 until files.length()) {
            if (files.getJSONObject(i).getString("path") == oldPath) files.getJSONObject(i).put("path", newPath)
        }
        e[HistoryArchive.MANIFEST] = m.toString().toByteArray()
        editItems(e) {
            it.getJSONArray("items").getJSONObject(1).put("id", keepId).put("audio", newPath)
        }
        assertRefused(reseal(e), HistoryArchive.Reason.INVALID_METADATA, detail = "appears twice")
    }

    @Test
    fun anAudioPathThatEscapesTheAudioDirectoryIsRefused() {
        val e = entries(exportBytes(seeded("source")))
        val path = firstAudio(e)
        val data = e.remove(path)!!
        val hostile = "audio/../../${path.removePrefix("audio/")}"
        e[hostile] = data
        val m = manifest(e)
        val files = m.getJSONArray("files")
        for (i in 0 until files.length()) {
            if (files.getJSONObject(i).getString("path") == path) files.getJSONObject(i).put("path", hostile)
        }
        e[HistoryArchive.MANIFEST] = m.toString().toByteArray()
        editItems(e) {
            val items = it.getJSONArray("items")
            for (i in 0 until items.length()) {
                if (items.getJSONObject(i).getString("audio") == path) items.getJSONObject(i).put("audio", hostile)
            }
        }
        assertRefused(reseal(e), HistoryArchive.Reason.INVALID_METADATA, detail = "is not audio/<id>")
    }

    @Test
    fun audioBytesThatDisagreeWithTheManifestAreRefused() {
        val e = entries(exportBytes(seeded("source")))
        editItems(e) { it.getJSONArray("items").getJSONObject(0).put("audioBytes", 1_234_567) }
        assertRefused(reseal(e), HistoryArchive.Reason.INVALID_METADATA, detail = "audioBytes disagrees")
    }

    @Test
    fun theWrittenManifestIsVersionOneAndHashesEveryFile() {
        val e = entries(exportBytes(seeded("source")))
        val m = manifest(e)
        assertEquals("dicta-history-archive", m.getString("format"))
        assertEquals(1, m.getInt("formatVersion"))
        assertEquals(3, m.getInt("itemCount"))
        val files = m.getJSONArray("files")
        val listed = (0 until files.length()).associate {
            files.getJSONObject(it).getString("path") to files.getJSONObject(it).getString("sha256")
        }
        assertEquals(e.keys - HistoryArchive.MANIFEST, listed.keys)
        for ((path, hash) in listed) assertEquals(path, sha(e.getValue(path)), hash)
    }
}
