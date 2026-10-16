package dev.zacx.dicta

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Where dictation history lives: one directory holding the audio files plus a
 * JSON index, and nothing else.
 *
 * ## Deliberately thin — it makes no retention decisions
 *
 * Every "what should be deleted" question is answered by [HistoryRetention],
 * which is pure, clockless and tested on its own. This class is the half that
 * does IO and is allowed to partially fail: copy a file, delete a file, rewrite
 * an index. Keeping the two apart is why a retention bug is never a flaky file
 * test. The only thing this class decides is *when* to ask (on every [add]),
 * and it supplies the clock [HistoryRetention.evict] refuses to read.
 *
 * ## 🔴 Why `noBackupFilesDir`, and why that is not a duplicate of the manifest
 *
 * The root is [Context.getNoBackupFilesDir], i.e.
 * `/data/data/<pkg>/no_backup/history/`. That directory is excluded from cloud
 * auto-backup and from device-to-device transfer **by the platform**, as a
 * property of the path itself — independent of any manifest attribute.
 *
 * `android:allowBackup="false"` in the manifest is the other half of the same
 * security fix, and the two are not redundant: the attribute is one edit away
 * from being flipped back (by a merged library manifest, a future "we want
 * settings backup" change, or a careless revert), and the moment it is, every
 * path under `filesDir` starts leaving the device. Recordings of the user's
 * voice are the last thing in this app that should ride a backup off the phone.
 * Rooting history here means the fix survives losing the attribute.
 *
 * ## 🔴 And deliberately NOT `cacheDir`
 *
 * [ScreenshotStore] uses `cacheDir` and is right to: a screenshot is needed
 * only until the share sheet has read it, so letting the OS reclaim it is a
 * feature. History is the opposite choice for the opposite reason. `cacheDir`
 * can be emptied at any moment with no notification and no event, so a user who
 * has built a habit on history would find it silently and inexplicably empty.
 * That is strictly worse than not having the feature, because they will have
 * stopped copying transcripts out.
 *
 * ## The index, and what survives a kill
 *
 * `index.json` is written to `index.json.tmp` and then [File.renameTo]'d over
 * the real file, which is atomic within a directory. A process death mid-write
 * therefore loses *the newest item*, never the whole index — the old index is
 * either fully there or fully replaced.
 *
 * A corrupt or unparseable index is treated as **empty and overwritten**, after
 * deleting every audio file in the directory. Both halves matter. Crashing or
 * retrying forever turns one bad write into an app that can never start again;
 * and an audio file with no index entry is reachable by no code path at all, so
 * if the sweep did not delete it, it would count against no budget and live on
 * the device forever.
 *
 * ## Threading
 *
 * [add], [clearAll], [updateText] and the index sweeps do file IO, so callers
 * run them off the main thread. Methods are `synchronized` on the instance:
 * the capture thread adds while the upload worker updates text, and the index
 * is a read-modify-write.
 */
class DictationHistoryStore internal constructor(
    /** The history directory itself. Injected so tests can use a temp dir. */
    internal val dir: File,
    private val now: () -> Long,
    /**
     * The retention policy this store enforces, defaulting to the real one.
     *
     * 🔴 Injected for a performance reason that is really a correctness one.
     * [add] rewrites the whole index on every call, so a test that drove
     * eviction at the production [HistoryRetention.MAX_ITEMS] would do ~1001
     * file copies and ~1001 full-index serialisations against a real
     * filesystem — quadratic, on the order of half a million JSON object
     * writes. The alternatives are all worse than injecting: deleting the test
     * loses the only coverage that eviction reaches *disk*, shrinking the
     * constant makes the product worse to make a test fast, and `@Ignore`
     * reads as coverage while providing none. The eviction *mechanism* does
     * not depend on the size of the bound, so tests drive it with small ones.
     *
     * 🔴 Which makes this default the ONLY thing tying the store to the real
     * policy. `DictationHistoryBoundsSeamTest` asserts it IS
     * [HistoryRetention.Bounds.DEFAULT]'s three values; without that guard the
     * mechanism would be tested at 5 items while the shipped app used anything
     * at all, and every test here would still be green.
     */
    internal val bounds: HistoryRetention.Bounds = HistoryRetention.Bounds.DEFAULT,
) {

    /**
     * The production constructor: rooted under [Context.getNoBackupFilesDir],
     * for the reasons on the class, and on the real retention policy.
     */
    constructor(
        context: Context,
        now: () -> Long = System::currentTimeMillis,
    ) : this(File(context.noBackupFilesDir, DIR), now)

    /**
     * One history item, which is exactly the index row.
     *
     * 🔴 There is no `mime` and no `sampleRateHz`, which an earlier design
     * sketch listed. Measured, not assumed: [SttHttp.transcribe] builds its
     * multipart from fixed `AUDIO_FILE_NAME` / `AUDIO_CONTENT_TYPE` constants,
     * so a re-transcribe never consults the stored file's declared type; and
     * [MicCapture.Listener.onFinished] carries `(file, audioSeconds)` only, not
     * the [AudioCaptureLogic.EncoderChoice], so recording the sample rate would
     * mean widening that interface for a field no consumer reads. The source
     * file's extension is preserved in [audioFile], which is all any consumer
     * needs. Do not re-add them.
     *
     * @param audioFile the file *name* inside the history directory, not a
     *   path. Storing a bare name keeps the index valid across the data-dir
     *   relocations the platform is free to make.
     */
    data class Item(
        val id: String,
        val createdAtMs: Long,
        val audioBytes: Long,
        val audioFile: String,
        val text: String,
        val fromRemote: Boolean,
        val audioSeconds: Double,
    )

    /** What history currently occupies, for the settings screen. */
    data class Usage(val itemCount: Int, val totalBytes: Long)

    private val indexFile: File get() = File(dir, INDEX)
    private val tempFile: File get() = File(dir, "$INDEX$TMP_SUFFIX")

    /**
     * Files the store owns but which are not audio. Anything in the directory
     * that is not one of these and not referenced by the index is an orphan.
     */
    private fun isBookkeeping(file: File): Boolean =
        file.name == INDEX || file.name == "$INDEX$TMP_SUFFIX"

    /**
     * Copies [audio] into history and returns the new item's id.
     *
     * 🔴 **Copies. Never moves.** The caller — [DictationSession] — still owns
     * the recording in `cacheDir` and still deletes it in `destroy()`. A store
     * that moved the file would be reaching into another object's life cycle to
     * take its file, and the visible consequence is a `destroy()` that deletes
     * a history entry the user is looking at.
     *
     * Order is copy → evict → rewrite index, so the index never references a
     * file that is not yet on disk.
     */
    @Synchronized
    fun add(audio: File, text: String, fromRemote: Boolean, audioSeconds: Double): String {
        dir.mkdirs()
        // Loaded BEFORE the copy, so the orphan sweep a corrupt index triggers
        // cannot possibly delete the file this call is about to write.
        val existing = load()
        val createdAtMs = now()
        val id = newId(createdAtMs)
        val name = audioNameFor(id, audio.name)
        val destination = File(dir, name)
        audio.copyTo(destination, overwrite = true)
        val item = Item(
            id = id,
            createdAtMs = createdAtMs,
            audioBytes = destination.length(),
            audioFile = name,
            text = text,
            fromRemote = fromRemote,
            audioSeconds = audioSeconds,
        )
        writeIndex(evict(existing + item, createdAtMs))
        return id
    }

    /**
     * The newest [limit] items, newest first.
     *
     * 🔴 Items whose audio file is **absent are dropped and swept from the
     * index**. This is the designed mitigation for a real soft spot rather than
     * defensive noise: the capture hook in [DictationSession] runs before
     * `destroy()` deletes the cache recording, but that ordering lives in
     * control flow and is enforced by nothing, so a future change can produce
     * an entry whose audio has gone. The degradation has to be "fewer items" —
     * not a crash, and not a row in the list that silently does nothing when
     * tapped.
     */
    @Synchronized
    fun recent(limit: Int): List<Item> =
        visible().sortedWith(
            compareByDescending<Item> { it.createdAtMs }.thenBy { it.id },
        ).take(limit.coerceAtLeast(0))

    /** The audio for [id], or null when the entry or its file is gone. */
    @Synchronized
    fun audioFor(id: String): File? {
        val item = load().firstOrNull { it.id == id } ?: return null
        return File(dir, item.audioFile).takeIf { it.isFile }
    }

    /**
     * Replaces the stored transcript for [id].
     *
     * Called when the remote transcript arrives, so what history shows is the
     * text the user actually saw rather than the local first guess. A no-op for
     * an unknown id — by the time the upload returns the entry may have been
     * evicted, and that is not an error.
     */
    @Synchronized
    fun updateText(id: String, text: String, fromRemote: Boolean) {
        val items = load()
        if (items.none { it.id == id }) return
        writeIndex(
            items.map {
                if (it.id == id) it.copy(text = text, fromRemote = fromRemote) else it
            },
        )
    }

    /**
     * Deletes all history.
     *
     * 🔴 Deletes **every file in the directory**, not only the indexed ones. A
     * "delete all history" that leaves unreachable audio on the device is a
     * privacy control that does not do what its label says — and unreachable
     * audio is exactly what a corrupt index or an interrupted [add] leaves
     * behind.
     */
    @Synchronized
    fun clearAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    /** Item count and total audio bytes, over items whose audio still exists. */
    @Synchronized
    fun usage(): Usage {
        val items = visible()
        return Usage(items.size, items.sumOf { it.audioBytes })
    }

    // --- internals ----------------------------------------------------------

    /**
     * The indexed items whose audio is present, sweeping the index when any
     * were not. The sweep is the write half of [recent]'s contract, shared with
     * [usage] so both report the same set.
     */
    private fun visible(): List<Item> {
        val items = load()
        val present = items.filter { File(dir, it.audioFile).isFile }
        if (present.size != items.size) writeIndex(present)
        return present
    }

    /**
     * Asks [HistoryRetention] what to drop, deletes those files, and returns
     * the survivors. The condemned list is in deletion order; all of it goes.
     */
    private fun evict(items: List<Item>, nowMs: Long): List<Item> {
        val condemned = HistoryRetention.evict(
            items.map { HistoryRetention.Entry(it.id, it.createdAtMs, it.audioBytes) },
            nowMs,
            bounds,
        ).toSet()
        if (condemned.isEmpty()) return items
        items.filter { it.id in condemned }.forEach { File(dir, it.audioFile).delete() }
        return items.filterNot { it.id in condemned }
    }

    /**
     * Reads the index, or treats it as empty.
     *
     * Three cases, and only one of them writes: a missing index on a
     * never-used (or just-cleared) directory is simply empty; a *parseable*
     * index is returned as-is; anything else is a corrupt index, which is
     * reset and whose orphans are swept. See the class kdoc for why resetting
     * beats throwing.
     */
    private fun load(): List<Item> {
        if (!dir.isDirectory) return emptyList()
        if (!indexFile.isFile) {
            // No index at all. Any audio here is already unreachable, so sweep
            // it on the same reasoning as a corrupt index — but only when
            // there is something to sweep, so an empty store does no IO.
            if (dir.listFiles()?.any { !isBookkeeping(it) } == true) sweepAndReset()
            return emptyList()
        }
        return try {
            parse(indexFile.readText())
        } catch (_: Exception) {
            sweepAndReset()
            emptyList()
        }
    }

    /** Deletes every non-bookkeeping file and writes a fresh empty index. */
    private fun sweepAndReset() {
        dir.listFiles()?.forEach { if (!isBookkeeping(it)) it.delete() }
        writeIndex(emptyList())
    }

    /**
     * Parses the index, throwing on anything it does not recognise so [load]
     * can take the reset path. A row missing a required field is a corrupt
     * index, not an item with defaults: a silently defaulted `createdAtMs` or
     * `audioFile` produces an entry that retention mis-ages or cannot find.
     */
    private fun parse(text: String): List<Item> {
        val root = JSONObject(text)
        val array = root.getJSONArray(KEY_ITEMS)
        val items = ArrayList<Item>(array.length())
        for (i in 0 until array.length()) {
            val row = array.getJSONObject(i)
            items += Item(
                id = row.getString("id"),
                createdAtMs = row.getLong("createdAtMs"),
                audioBytes = row.getLong("audioBytes"),
                audioFile = row.getString("audioFile"),
                text = row.getString("text"),
                fromRemote = row.getBoolean("fromRemote"),
                audioSeconds = row.getDouble("audioSeconds"),
            )
        }
        return items
    }

    /**
     * Write-temp-then-rename, so the index is never observed half-written.
     *
     * [File.renameTo] within one directory is atomic, and the temp file lives
     * in the same directory precisely so it is a rename and not a cross-device
     * copy.
     */
    private fun writeIndex(items: List<Item>) {
        dir.mkdirs()
        val array = JSONArray()
        for (item in items) {
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("createdAtMs", item.createdAtMs)
                    .put("audioBytes", item.audioBytes)
                    .put("audioFile", item.audioFile)
                    .put("text", item.text)
                    .put("fromRemote", item.fromRemote)
                    .put("audioSeconds", item.audioSeconds),
            )
        }
        val payload = JSONObject().put(KEY_VERSION, VERSION).put(KEY_ITEMS, array).toString()
        val temp = tempFile
        temp.writeText(payload)
        if (!temp.renameTo(indexFile)) {
            // renameTo can refuse when the target exists on some filesystems.
            // Falling back to delete-then-rename gives up atomicity for this
            // one write, which is still better than silently keeping a stale
            // index and leaving a .tmp behind forever.
            indexFile.delete()
            if (!temp.renameTo(indexFile)) temp.delete()
        }
    }

    /**
     * Preserves the source extension, which is the only thing about the audio's
     * type the index records — see the note on [Item].
     */
    private fun audioNameFor(id: String, sourceName: String): String {
        val extension = sourceName.substringAfterLast('.', "")
        val safe = extension.takeIf { it.isNotEmpty() && it.all(Char::isLetterOrDigit) }
        return if (safe == null) id else "$id.$safe"
    }

    /**
     * A filename-safe, sortable, collision-resistant id.
     *
     * The timestamp prefix makes it sort in creation order, which is only a
     * convenience — [HistoryRetention] sorts on `createdAtMs` and tie-breaks on
     * the id, so correctness does not depend on the format. The random suffix
     * is what prevents two recordings finishing in the same millisecond from
     * sharing an id, which would make them one entry and lose one recording.
     */
    private fun newId(createdAtMs: Long): String =
        "%013d-%08x".format(createdAtMs, RANDOM.nextInt())

    companion object {
        /** The directory name under `noBackupFilesDir`. */
        const val DIR = "history"

        /** The index file name, inside [DIR]. */
        const val INDEX = "index.json"

        private const val TMP_SUFFIX = ".tmp"
        private const val KEY_ITEMS = "items"
        private const val KEY_VERSION = "version"
        private const val VERSION = 1

        private val RANDOM = java.util.Random()
    }
}
