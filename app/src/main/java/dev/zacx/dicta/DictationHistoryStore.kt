package dev.zacx.dicta

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

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
 * `index.json` is written to `index.json.tmp`, `fsync`'d, and then
 * [File.renameTo]'d over the real file, which is atomic within a directory. On
 * that path a process death mid-write loses *the newest item*, never the whole
 * index — the old index is either fully there or fully replaced.
 *
 * 🔴 **And when `index.json` is lost anyway, the temp file is the recovery.**
 * [load] falls back to `index.json.tmp` whenever `index.json` is missing or
 * unparseable. A temp file that still exists is, by construction, a complete
 * and `fsync`'d index that was never renamed into place — there is no other way
 * for one to survive a call, because the normal path consumes it in the rename
 * and the fallback path deletes it. So reading it recovers all three ways the
 * commit can be lost: a kill between the write and the rename, a rename the
 * filesystem *refused* followed by a torn in-place write, and a power cut in
 * either window. Every one of those used to land in the paragraph below —
 * **every recording the user has, deleted** — and now lands on the previous
 * index instead.
 *
 * 🔴 **The honest remaining loss cases**, because the sentence this replaces
 * was flattering and the one before that was wrong:
 *
 *  * **Both files unparseable.** The sweep below still runs, and it is still a
 *    wipe. Needs two independent faults, or one fault big enough to damage two
 *    files (a corrupt block, a truncated filesystem).
 *  * **Neither file present.** Same outcome, same paragraph. [writeIndex] has
 *    no path that produces it.
 *  * **Possibly the newest item, after a power cut — stated as UNKNOWN rather
 *    than as a bound.** `java.io` cannot `fsync` a directory (see
 *    [writeIndex]), so the durability of the rename, and of the temp's own
 *    directory entry, is the filesystem's business and not ours. Whether the
 *    post-crash directory shows the temp (recoverable, newest item kept) or
 *    shows neither name (the bullet above) is not something this code decides,
 *    and this comment does not claim it either way. What it does claim is that
 *    whatever names ARE there get read in the right order.
 *
 * What is NOT a remaining case, and was the costliest one: a kill during
 * recovery itself. The restore writes `index.json` in place from bytes it has
 * already validated and leaves the temp alone, so there is no instant at which
 * both files are bad. See [recoverFromTemp].
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
 * [add], [clearAll], [updateText], [delete] and the index sweeps do file IO,
 * so callers run them off the main thread.
 *
 * 🔴 The monitor is a **process-wide object on the companion, not the
 * instance**, because production builds one store PER OWNER over one
 * directory: [DictationSession] constructs a fresh store per session, and
 * `SettingsActivity` constructs its own. `@Synchronized` on the instance —
 * what this class shipped with — therefore excluded a session from its own
 * upload worker and from nothing else. Settings' `clearAll()` could interleave
 * with a session's `add`; a previous session's late `updateText` (its
 * `destroy()` does not unblock a socket read) could interleave with the next
 * session's `add`. The index is a read-modify-write, so the loser's row is
 * simply dropped — and a dropped row is silent *and* permanent: the audio
 * stays on disk with no index entry, `visible()` only prunes rows whose file
 * is missing (never files with no row), and [sweepAndReset] runs only on a
 * missing or corrupt index. The orphan counts against no retention bound and
 * lives on the device forever.
 *
 * What the monitor does **not** cover: another process. Dicta is
 * single-process — no `android:process` anywhere in the manifest — so today it
 * covers every writer that exists, and the temp-file name being shared is
 * safe for the same reason. A second process would be outside it on both
 * counts.
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
     *
     * The temp file is in here so the orphan sweep does not mistake it for
     * audio and so [load]'s "is there anything to sweep" test does not fire on
     * a directory that holds only bookkeeping.
     *
     * ⚠ It is NOT what protects [recoverFromTemp]'s input, which an earlier
     * draft of this comment claimed. The ordering in [load] is: the recovery
     * runs before any sweep, and a sweep only happens once the recovery has
     * already failed. Flipping that order is the mutation that loses the
     * recovery copy, not editing this predicate.
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
    fun add(
        audio: File,
        text: String,
        fromRemote: Boolean,
        audioSeconds: Double,
    ): String = synchronized(LOCK) {
        dir.mkdirs()
        // Loaded BEFORE the copy, so the orphan sweep a corrupt index triggers
        // cannot possibly delete the file this call is about to write.
        val existing = load()
        val createdAtMs = now()
        val id = newId(createdAtMs)
        val name = audioNameFor(id, audio.name)
        val destination = File(dir, name)
        // 🔴 Everything from the copy to the index write is undone on a throw,
        // and this is not defensive noise: `add` HAS throwing steps after the
        // audio is on disk — `copyTo` itself can run out of space part-way,
        // and `writeIndex`'s `fd.sync()` throws `SyncFailedException`. The
        // caller ([DictationSession]) wraps `add` in `runCatching` and treats a
        // throw as "no history entry", so without this the copy would stay in
        // the directory with no index row pointing at it, and NOTHING collects
        // that: `visible()` prunes rows whose file is missing, never files with
        // no row, and `sweepAndReset()` runs only on an unreadable index. A
        // recording of the user's voice would live on the device forever,
        // counting against no retention bound.
        try {
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
        } catch (t: Throwable) {
            // Deleted, then rethrown unchanged: the caller still has to learn
            // the add failed. Not `runCatching` on the delete — `File.delete`
            // returns false rather than throwing, and a false here leaves the
            // pre-existing orphan hazard, which it did not create.
            destination.delete()
            throw t
        }
        id
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
    fun recent(limit: Int): List<Item> = synchronized(LOCK) {
        visible().sortedWith(
            compareByDescending<Item> { it.createdAtMs }.thenBy { it.id },
        ).take(limit.coerceAtLeast(0))
    }

    /** The audio for [id], or null when the entry or its file is gone. */
    fun audioFor(id: String): File? = synchronized(LOCK) {
        val item = load().firstOrNull { it.id == id }
            ?: return@synchronized null
        File(dir, item.audioFile).takeIf { it.isFile }
    }

    /**
     * Replaces the stored transcript for [id].
     *
     * Called when the remote transcript arrives, so what history shows is the
     * text the user actually saw rather than the local first guess. A no-op for
     * an unknown id — by the time the upload returns the entry may have been
     * evicted, and that is not an error.
     */
    fun updateText(id: String, text: String, fromRemote: Boolean) = synchronized(LOCK) {
        val items = load()
        if (items.none { it.id == id }) return@synchronized
        writeIndex(
            items.map {
                if (it.id == id) it.copy(text = text, fromRemote = fromRemote) else it
            },
        )
    }

    /**
     * Removes the entry [id] and its audio file. A no-op for an unknown id.
     *
     * 🔴 Exists for exactly one caller, and is not a user-facing delete: the
     * undo in [DictationSession]'s capture hook, for the window where the
     * session is destroyed *while* `add` is copying the recording. Without it
     * "a destroyed session keeps nothing" is a claim about timing — the hook's
     * gate ran before the destroy and the file lands after it — and there is
     * no other way to take a filed entry back out.
     *
     * Per-item deletion from the history UI is a separate feature and does not
     * exist yet; when it does, it wants its own tests rather than this one's.
     */
    fun delete(id: String) = synchronized(LOCK) {
        val items = load()
        val item = items.firstOrNull { it.id == id } ?: return@synchronized
        File(dir, item.audioFile).delete()
        writeIndex(items.filterNot { it.id == id })
    }

    /**
     * Deletes all history.
     *
     * 🔴 Deletes **every file in the directory**, not only the indexed ones. A
     * "delete all history" that leaves unreachable audio on the device is a
     * privacy control that does not do what its label says — and unreachable
     * audio is exactly what a corrupt index or an interrupted [add] leaves
     * behind.
     *
     * 🔴 "Every file" includes `index.json.tmp`, and that now matters for the
     * same privacy reason rather than only for tidiness: since [load] recovers
     * from the temp, a temp left holding rows would resurrect the transcripts
     * of recordings the user asked to delete the next time the index could not
     * be read. `listFiles()` covers it with no special case — the point is
     * that narrowing this to indexed files would break the promise twice over.
     */
    fun clearAll() = synchronized(LOCK) {
        dir.listFiles()?.forEach { it.delete() }
        Unit
    }

    /** Item count and total audio bytes, over items whose audio still exists. */
    fun usage(): Usage = synchronized(LOCK) {
        val items = visible()
        Usage(items.size, items.sumOf { it.audioBytes })
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
     * A *parseable* `index.json` is returned as-is and nothing is written.
     * Otherwise — missing, truncated, corrupt — [recoverFromTemp] gets the
     * next word, because `index.json.tmp` surviving a call means it is a
     * complete index that never made it into place (class kdoc). Only when
     * that fails too is the directory treated as corrupt: reset, orphans
     * swept. See the class kdoc for why resetting beats throwing, and for what
     * resetting costs.
     */
    private fun load(): List<Item> {
        if (!dir.isDirectory) return emptyList()
        if (indexFile.isFile) {
            try {
                return parse(indexFile.readText())
            } catch (_: Exception) {
                // Torn or corrupt. The temp, if there is one, is the write
                // this index was supposed to have become.
                return recoverFromTemp() ?: run { sweepAndReset(); emptyList() }
            }
        }
        // No index at all. Recovery FIRST and the sweep second: sweepAndReset
        // deletes every audio file, which is precisely the set the temp still
        // has rows for, so the other order would destroy what it is about to
        // read.
        recoverFromTemp()?.let { return it }
        // Nothing to recover from, so any audio here is already unreachable:
        // sweep it on the same reasoning as a corrupt index — but only when
        // there is something to sweep, so an empty store does no IO.
        if (dir.listFiles()?.any { !isBookkeeping(it) } == true) sweepAndReset()
        return emptyList()
    }

    /**
     * Reads `index.json.tmp` as the index, restoring `index.json` from it, or
     * returns null when there is no usable temp.
     *
     * 🔴 The restore writes `index.json` **in place, from the bytes just
     * parsed**, and deliberately does NOT go through [writeIndex]. [writeIndex]
     * truncates the temp as its very first step, so routing the restore through
     * it would destroy the only good copy while the committed index was still
     * torn — a kill in that window is the total loss this whole path exists to
     * remove. Writing in place can only fail forward: `index.json` stays torn,
     * the temp is untouched, and the next [load] recovers again.
     *
     * The temp is left in place for the same reason. It stops being a
     * duplicate the moment any normal write happens: the rename consumes it,
     * and [writeIndex]'s fallback deletes it.
     *
     * `runCatching` on the restore because recovery must not turn a readable
     * history into a throw — the items are already in hand, and the caller is
     * a read.
     */
    private fun recoverFromTemp(): List<Item>? {
        if (!tempFile.isFile) return null
        val text = try {
            tempFile.readText()
        } catch (_: Exception) {
            return null
        }
        val items = try {
            parse(text)
        } catch (_: Exception) {
            return null
        }
        runCatching { indexFile.writeText(text) }
        return items
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
     *
     * 🔴 **No path here may end with neither file present.** The fallback used
     * to be `indexFile.delete()`, retry, and `temp.delete()` if the retry
     * failed too — which is exactly that state, and it is not a lost write: a
     * directory holding audio and no index is what [load] calls corrupt, and
     * its recovery is [sweepAndReset], i.e. **every recording the user has is
     * deleted**. The old comment weighed this as "better than silently keeping
     * a stale index"; the costs are the other way round by a wide margin. A
     * stale index loses the newest row. Losing the index loses everything.
     *
     * The data `fsync` is deliberate and is the narrow half that `java.io` can
     * express: it makes the temp file's *contents* durable before the rename,
     * so a power cut cannot leave a renamed-into-place index that is a hole.
     * It does **not** make the rename itself durable — fsync'ing a directory
     * needs a descriptor `java.io.File` will not give us — so after a power
     * cut the index may still be the previous one. That direction is the safe
     * one: a stale index is readable. A torn one is no longer a wipe either,
     * but only because [recoverFromTemp] exists; it was one when this comment
     * was first written.
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
        FileOutputStream(temp).use { out ->
            out.write(payload.toByteArray())
            out.fd.sync()
        }
        if (temp.renameTo(indexFile)) return
        // renameTo can refuse when the target exists on some filesystems. The
        // index is NOT deleted to make room: write the payload in place
        // instead.
        //
        // 🔴 This write is NOT atomic, and the comment here used to call its
        // failure "a stale index", which was wrong in the direction that
        // matters: `writeText` is `FileOutputStream(this).use { … }`, which
        // truncates on open, so a throw mid-write leaves `index.json`
        // zero-length or torn — not stale. Unparseable, in other words, which
        // used to mean load() → sweepAndReset() → every recording deleted.
        //
        // What makes it survivable is `temp`, which still holds this exact
        // payload, already `fsync`'d, and which load() now reads when
        // `index.json` will not parse. So the cost of losing atomicity here is
        // bounded by the recovery rather than by the wipe.
        indexFile.writeText(payload)
        // Only now, with a parseable index definitely in place, is the temp
        // redundant. Left behind on the throwing path ON PURPOSE, and the
        // reason has inverted: it is not that a stale .tmp is inert —
        // load() reads it — it is that it is the recovery copy for exactly the
        // index this line may have just torn. isBookkeeping() keeps the orphan
        // sweep off it.
        temp.delete()
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
        /**
         * 🔴 The monitor every instance shares — see "Threading" on the class.
         * Process-wide because the stores are not: production builds one per
         * session plus one in `SettingsActivity`, all over one directory.
         */
        private val LOCK = Any()

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
