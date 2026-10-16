package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The file-level behaviour of [DictationHistoryStore], against a real temp
 * directory and a fake clock.
 *
 * No `Context` is used — the store is constructed on an injected directory, and
 * the one thing that genuinely needs a `Context`, that production roots history
 * under `noBackupFilesDir`, is pinned separately in
 * [DictationHistoryLocationTest]. That is the same split the store itself
 * makes: IO behaviour here, platform wiring there.
 *
 * ⚠ Robolectric all the same, and not by preference — `org.json` is a *stub*
 * in the JVM `android.jar` the unit-test classpath gets, so every method throws
 * `"Method put in org.json.JSONObject not mocked"`. Robolectric supplies the
 * real implementation. Measured, not assumed: this class was written as plain
 * JUnit first (the [ScreenshotSequencerTest] idiom) and all 13 tests failed on
 * exactly that. The index format is hand-rolled `org.json` because the APK
 * ships zero runtime dependencies — see [DictaFileProvider] — so this is the
 * cost of that decision, paid in the test harness rather than in the APK.
 *
 * Retention arithmetic is NOT re-tested here — [HistoryRetentionTest] owns it.
 * What is tested is that the store asks, and then actually deletes the files it
 * was told to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictationHistoryStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private var clock = 1_700_000_000_000L

    /**
     * The history directory and the source directory are SIBLINGS, never the
     * same folder: the store sweeps unindexed files out of its own directory,
     * so a source recording sitting there would be deleted by the thing under
     * test and `add` would be copying a file to itself.
     */
    private val historyDir: File by lazy { temp.newFolder("history") }
    private val sourceDir: File by lazy { temp.newFolder("sources") }

    private fun store(
        dir: File = historyDir,
        bounds: HistoryRetention.Bounds = HistoryRetention.Bounds.DEFAULT,
    ): DictationHistoryStore = DictationHistoryStore(dir, { clock }, bounds)

    /** A source recording, outside the history directory, as production has. */
    private fun recording(name: String = "dictation-1.ogg", bytes: Int = 64): File =
        File(sourceDir, name).apply { writeBytes(ByteArray(bytes) { 7 }) }

    private fun indexText(dir: File = historyDir): String =
        File(dir, DictationHistoryStore.INDEX).readText()

    private fun audioFiles(dir: File = historyDir): List<String> =
        (dir.listFiles() ?: emptyArray())
            .map { it.name }
            .filterNot { it.startsWith(DictationHistoryStore.INDEX) }
            .sorted()

    // --- the happy path -----------------------------------------------------

    @Test
    fun anAddedRecordingComesBackFromRecent() {
        val store = store()
        val id = store.add(recording(), "hello there", fromRemote = false, audioSeconds = 2.5)

        val items = store.recent(10)
        assertEquals("expected exactly the one item just added, got $items", 1, items.size)
        val item = items.single()
        assertEquals(id, item.id)
        assertEquals("hello there", item.text)
        assertEquals(false, item.fromRemote)
        assertEquals(2.5, item.audioSeconds, 0.0)
        assertEquals(64L, item.audioBytes)
        assertEquals(
            "the source extension must be preserved in the stored name, " +
                "because it is the ONLY thing the index records about the " +
                "audio's type — see the note on Item. Got ${item.audioFile}",
            "ogg",
            item.audioFile.substringAfterLast('.', ""),
        )
    }

    @Test
    fun recentReturnsNewestFirst() {
        val store = store()
        clock = 1_000
        val oldest = store.add(recording("a.ogg"), "a", fromRemote = false, audioSeconds = 1.0)
        clock = 2_000
        val middle = store.add(recording("b.ogg"), "b", fromRemote = false, audioSeconds = 1.0)
        clock = 3_000
        val newest = store.add(recording("c.ogg"), "c", fromRemote = false, audioSeconds = 1.0)

        assertEquals(
            listOf(newest, middle, oldest),
            store.recent(10).map { it.id },
        )
    }

    @Test
    fun recentHonoursTheLimit() {
        val store = store()
        repeat(4) {
            clock += 1_000
            store.add(recording("r$it.ogg"), "t$it", fromRemote = false, audioSeconds = 1.0)
        }
        assertEquals(2, store.recent(2).size)
    }

    @Test
    fun audioForReturnsTheStoredCopy() {
        val store = store()
        val id = store.add(recording(bytes = 128), "x", fromRemote = false, audioSeconds = 1.0)

        val audio = store.audioFor(id)
        assertTrue("audioFor returned $audio", audio != null && audio.isFile)
        assertEquals(128L, audio!!.length())
        assertEquals(
            "the audio must live INSIDE the history directory",
            historyDir.canonicalFile,
            audio.canonicalFile.parentFile,
        )
        assertNull("an unknown id must be null, not a phantom file", store.audioFor("nope"))
    }

    @Test
    fun updateTextReplacesTheStoredTranscript() {
        val store = store()
        val id = store.add(recording(), "local guess", fromRemote = false, audioSeconds = 1.0)

        store.updateText(id, "the server's text", fromRemote = true)

        val item = store.recent(10).single()
        assertEquals("the server's text", item.text)
        assertTrue("fromRemote was not updated alongside the text", item.fromRemote)
    }

    @Test
    fun updateTextOnAnUnknownIdIsANoOp() {
        // An entry can be evicted while its upload is in flight; that must not
        // throw on the worker thread, and must not resurrect the entry.
        val store = store()
        store.add(recording(), "kept", fromRemote = false, audioSeconds = 1.0)
        store.updateText("not-a-real-id", "ghost", fromRemote = true)

        assertEquals(listOf("kept"), store.recent(10).map { it.text })
    }

    @Test
    fun usageCountsItemsAndBytes() {
        val store = store()
        clock += 1_000
        store.add(recording("a.ogg", bytes = 100), "a", fromRemote = false, audioSeconds = 1.0)
        clock += 1_000
        store.add(recording("b.ogg", bytes = 250), "b", fromRemote = false, audioSeconds = 1.0)

        assertEquals(DictationHistoryStore.Usage(2, 350L), store.usage())
    }

    // --- 🔴 copy, never move ------------------------------------------------

    @Test
    fun addCopiesTheRecordingAndLeavesTheSourceAlone() {
        // DictationSession still owns the cache recording and still deletes it
        // in destroy(). A store that MOVED the file would be taking another
        // object's file, and the visible failure is a destroy() that deletes a
        // history entry the user is looking at.
        val store = store()
        val source = recording(bytes = 32)
        val id = store.add(source, "t", fromRemote = false, audioSeconds = 1.0)

        assertTrue(
            "add() did not leave the source recording in place, so it MOVED " +
                "the file instead of copying it. DictationSession still owns " +
                "that file and deletes it in destroy(); a move means destroy() " +
                "deletes the user's history entry.",
            source.isFile,
        )
        assertEquals("the source was truncated as well as taken", 32L, source.length())
        val stored = store.audioFor(id)
        assertTrue("the history copy is missing", stored != null && stored.isFile)
        assertFalse(
            "the stored copy IS the source file — that is a move, not a copy",
            stored!!.canonicalPath == source.canonicalPath,
        )
    }

    // --- retention is delegated, and then actually enforced on disk ---------
    //
    // 🔴 These run at INJECTED SMALL BOUNDS, and that is not a shortcut.
    //
    // add() rewrites the whole JSON index on every call, so driving eviction
    // at the production MAX_ITEMS = 1000 would be ~1001 real file copies and
    // ~1001 full-index serialisations — quadratic, about half a million JSON
    // object writes for one test. The eviction mechanism does not depend on
    // the size of the bound: what is under test is that the store asks
    // HistoryRetention, deletes the files it is told to, and rewrites the
    // index to match. Five items exercises every one of those.
    //
    // What small bounds CANNOT see is whether the shipped store uses the real
    // policy at all — DictationHistoryBoundsSeamTest owns exactly that, and is
    // the reason this file is allowed to be fast.
    //
    // All three bounds are driven through the real add() path, not just the
    // count: a store that read HistoryRetention's constants directly for bytes
    // or age — ignoring two thirds of what it was handed — would otherwise be
    // green here and merely slow.

    private val tinyBounds = HistoryRetention.Bounds(
        maxItems = 5,
        // 203 bytes against 64-byte recordings: three fit (192), four do not
        // (256). Deliberately not a multiple of 64, so the bound is straddled
        // rather than landed on and a mutant that moves the comparison by one
        // still has somewhere to be wrong.
        maxBytes = 203L,
        maxAgeMs = 30_000L,
    )

    @Test
    fun addingPastTheItemBoundDeletesTheOldestFromDiskAndFromTheIndex() {
        // Byte and age bounds lifted clear so the COUNT is the only thing in
        // play; six 64-byte recordings is 384 bytes and six seconds.
        val bounds = tinyBounds.copy(maxBytes = HistoryRetention.MAX_BYTES, maxAgeMs = 600_000L)
        val store = store(bounds = bounds)
        val ids = mutableListOf<String>()
        val names = mutableListOf<String>()
        repeat(bounds.maxItems + 1) { i ->
            clock += 1_000
            val id = store.add(recording("r$i.ogg"), "t$i", fromRemote = false, audioSeconds = 1.0)
            ids += id
            names += store.recent(1).single().audioFile
        }

        val oldest = ids.first()
        val oldestName = names.first()
        assertEquals(
            "history kept more than maxItems entries",
            bounds.maxItems,
            store.recent(1_000).size,
        )
        assertFalse(
            "the evicted entry is still in the index",
            store.recent(1_000).any { it.id == oldest },
        )
        assertFalse(
            "the evicted entry's audio file $oldestName is still on disk. An " +
                "index-only eviction leaves the recording on the device " +
                "forever, counting against no budget and reachable by no code " +
                "path. Files: ${audioFiles()}",
            File(historyDir, oldestName).exists(),
        )
        assertEquals(
            "the number of audio files on disk does not match the index",
            bounds.maxItems,
            audioFiles().size,
        )
    }

    @Test
    fun addingPastTheByteBoundDeletesTheOldestFromDiskAndFromTheIndex() {
        // 🔴 This path now RUNS IN PRODUCTION. At MAX_BYTES = 200 MiB against
        // 50 items the byte stage was unreachable on a real device; at 2 GiB
        // against 1000 items a long-dictation user can get there. So the store
        // must honour the byte bound it is handed, not only the count.
        val store = store(bounds = tinyBounds.copy(maxItems = 100, maxAgeMs = 600_000L))
        val ids = mutableListOf<String>()
        val names = mutableListOf<String>()
        repeat(4) { i ->
            clock += 1_000
            val id = store.add(
                recording("b$i.ogg", bytes = 64),
                "t$i",
                fromRemote = false,
                audioSeconds = 1.0,
            )
            ids += id
            names += store.recent(1).single().audioFile
        }

        assertEquals(
            "4 x 64 = 256 bytes against a 203-byte budget must keep the three " +
                "newest, dropping the oldest. More survivors means the store " +
                "ignored the byte bound it was given (probably by reading " +
                "HistoryRetention.MAX_BYTES directly); fewer means it is " +
                "evicting inside the budget. Index: ${indexText()}",
            ids.drop(1),
            store.recent(10).map { it.id }.reversed(),
        )
        assertFalse(
            "the byte-evicted entry's audio ${names.first()} is still on disk: " +
                "${audioFiles()}",
            File(historyDir, names.first()).exists(),
        )
        assertEquals(
            "disk does not match the index after a byte eviction",
            3,
            audioFiles().size,
        )
    }

    @Test
    fun addingAfterTheAgeBoundDeletesTheExpiredEntryFromDiskAndFromTheIndex() {
        val store = store(bounds = tinyBounds.copy(maxItems = 100, maxBytes = HistoryRetention.MAX_BYTES))
        val stale = store.add(recording("stale.ogg"), "stale", fromRemote = false, audioSeconds = 1.0)
        val staleName = store.recent(1).single().audioFile

        // One millisecond past the 30 s age bound, so the next add expires it.
        clock += tinyBounds.maxAgeMs + 1
        val fresh = store.add(recording("fresh.ogg"), "fresh", fromRemote = false, audioSeconds = 1.0)

        assertEquals(
            "the expired entry survived, so the store ignored the age bound " +
                "it was given. Age is absolute: it must evict with count and " +
                "byte room to spare. Index: ${indexText()}",
            listOf(fresh),
            store.recent(10).map { it.id },
        )
        assertFalse("the expired entry is still in the index", indexText().contains(stale))
        assertFalse(
            "the expired entry's audio $staleName is still on disk: ${audioFiles()}",
            File(historyDir, staleName).exists(),
        )
    }

    // --- 🔴 clearAll is a privacy control, so it clears EVERYTHING ----------

    @Test
    fun clearAllEmptiesTheDirectoryIncludingUnindexedAudio() {
        val store = store()
        clock += 1_000
        store.add(recording("a.ogg"), "a", fromRemote = false, audioSeconds = 1.0)
        clock += 1_000
        store.add(recording("b.ogg"), "b", fromRemote = false, audioSeconds = 1.0)
        // An orphan: what a corrupt index or an interrupted add leaves behind.
        val orphan = File(historyDir, "orphan.ogg").apply { writeBytes(ByteArray(16)) }

        store.clearAll()

        assertEquals(
            "clearAll left files behind. 'Delete all history' that leaves " +
                "unreachable audio — $orphan among them — on the device is a " +
                "privacy control that does not do what its label says. " +
                "Remaining: ${historyDir.list()?.toList()}",
            emptyList<String>(),
            (historyDir.listFiles() ?: emptyArray()).map { it.name }.sorted(),
        )
        assertEquals(emptyList<DictationHistoryStore.Item>(), store.recent(10))
        assertEquals(DictationHistoryStore.Usage(0, 0L), store.usage())
    }

    // --- corruption, absence, and interrupted writes ------------------------

    @Test
    fun aCorruptIndexReadsAsEmptyAndItsOrphansAreDeleted() {
        val store = store()
        store.add(recording(), "will be lost", fromRemote = false, audioSeconds = 1.0)
        val orphan = File(historyDir, "extra.ogg").apply { writeBytes(ByteArray(8)) }
        File(historyDir, DictationHistoryStore.INDEX).writeText("{\"items\": [ truncated")

        assertEquals(
            "a corrupt index must read as empty rather than throw — one bad " +
                "write must not be a permanently broken app",
            emptyList<DictationHistoryStore.Item>(),
            store.recent(10),
        )
        assertEquals(
            "the orphaned audio survived the reset. An audio file with no " +
                "index entry is reachable by no code path, so it counts " +
                "against nothing and lives on the device forever. Left: " +
                "${audioFiles()}, orphan was $orphan",
            emptyList<String>(),
            audioFiles(),
        )
        // And the reset index is usable again, not still corrupt.
        clock += 1_000
        val id = store.add(recording("after.ogg"), "after", fromRemote = false, audioSeconds = 1.0)
        assertEquals(listOf(id), store.recent(10).map { it.id })
    }

    @Test
    fun aLeftoverTempFileLeavesTheRealIndexIntact() {
        // Write-temp-then-rename means a kill mid-write leaves a .tmp behind.
        // The committed index must be untouched, so the loss is the newest
        // item rather than the whole history.
        val store = store()
        val id = store.add(recording(), "committed", fromRemote = false, audioSeconds = 1.0)
        val committed = indexText()
        File(historyDir, "${DictationHistoryStore.INDEX}.tmp")
            .writeText("{\"items\": [ half-written")

        assertEquals(
            "a stale .tmp file was mistaken for the index",
            listOf(id),
            store.recent(10).map { it.id },
        )
        assertEquals("the committed index was modified", committed, indexText())
    }

    @Test
    fun anIndexedItemWhoseAudioIsGoneIsDroppedAndSweptFromTheIndex() {
        // The soft spot this mitigates: the capture hook runs before destroy()
        // deletes the cache file, but that ordering is control flow and is
        // enforced by nothing. Degrade to "fewer items", never to a crash or a
        // row that does nothing when tapped.
        val store = store()
        clock += 1_000
        val kept = store.add(recording("keep.ogg"), "keep", fromRemote = false, audioSeconds = 1.0)
        clock += 1_000
        val lost = store.add(recording("lose.ogg"), "lose", fromRemote = false, audioSeconds = 1.0)
        val lostName = store.recent(10).first { it.id == lost }.audioFile
        assertTrue("fixture setup: the audio was never written", File(historyDir, lostName).delete())

        assertEquals(
            "recent() returned an item whose audio file is gone, so the UI " +
                "would show a row that cannot be played or re-transcribed",
            listOf(kept),
            store.recent(10).map { it.id },
        )
        assertFalse(
            "the dangling entry is still in the index, so it will be " +
                "re-offered on every read: ${indexText()}",
            indexText().contains(lost),
        )
        assertEquals(DictationHistoryStore.Usage(1, 64L), store.usage())
    }

    @Test
    fun aFreshStoreReadsAsEmptyWithoutCreatingAnything() {
        val dir = File(temp.root, "never-used")
        val store = store(dir)

        assertEquals(emptyList<DictationHistoryStore.Item>(), store.recent(10))
        assertEquals(DictationHistoryStore.Usage(0, 0L), store.usage())
        assertFalse("reading an empty store created the directory", dir.exists())
    }
}
