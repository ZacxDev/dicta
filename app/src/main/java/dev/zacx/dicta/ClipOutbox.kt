package dev.zacx.dicta

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * The local queue of captures that have not reached the store yet.
 *
 * ## 🔴 The outbox is the PRIMARY path, not a fallback
 *
 * A capture is *append here, synchronously, then toast and finish*. The upload
 * is **always** deferred, even with the mesh up and the server answering. It is
 * never a branch gated on a reachability check, and two things force that:
 *
 *  * An executor in a finishing activity's process can be killed before the
 *    socket completes, so a POST from [CaptureActivity] loses the capture
 *    silently — the user was told it was taken and it was not.
 *  * `SttReachabilityLedgerTest.exactlyOneSiteInTheWholeTreeReadsTheVerdict`
 *    counts `.verdict(` across this directory and asserts **exactly one**, and
 *    `onlyTheDeclaredFilesPerformAHealthProbe` pins the zero-argument
 *    `.healthz()` callers to three named files. So this file may read neither.
 *    It attempts the POST and keeps the item on failure. Those are tests to
 *    obey, not to widen.
 *
 * 🔴 **Both of those ledgers are NON-RECURSIVE directory listings**, so they
 * see this file only because it lives in the flat
 * `app/src/main/java/dev/zacx/dicta` directory. **Flat placement is a
 * requirement of this design, not a style choice**: in a `…/dicta/clip/`
 * subpackage both ledgers go blind and the constraint above silently stops
 * applying. Moving the clip files into a subpackage means widening both
 * ledgers in the same commit.
 *
 * ## 🔴 The OPPOSITE failure posture from [SnipUsageStore], in the same directory
 *
 * `SnipUsageStore` is explicit that *"a usage file is an OPTIMISATION, never a
 * gate"* and swallows every write failure. **This file is the only copy of the
 * user's capture.** A failed write must therefore SURFACE — [append] throws
 * [Failed] — because a swallowed exception means the capture is gone while the
 * user was told it was taken, and the user has by then dismissed the selection
 * they took it from.
 *
 * The two stores sit in the same `noBackupFilesDir` subtree with opposite
 * postures, and a reader will assume the wrong one. That is why it is said
 * here, loudly, rather than left to the diff.
 *
 * ## 🔴 `noBackupFilesDir`, like [SnipStore] and [DictationHistoryStore]
 *
 * A clipboard capture is arbitrary text the user highlighted anywhere on the
 * device — a password-manager field with its masking toggled off, an API key,
 * a message. Putting it on a path the platform excludes from cloud auto-backup
 * and from device-to-device transfer *by property of the path itself* means the
 * decision survives losing `android:allowBackup="false"`. Deliberately NOT
 * `cacheDir`: that can be emptied at any moment with no event, and this queue
 * is the only copy.
 */
interface ClipOutbox {

    /**
     * Appends [body] and returns its local id.
     *
     * 🔴 **Synchronous, and it throws.** See the interface kdoc: the caller's
     * next act is to tell the user the capture was taken, and it may only do
     * that if this returned.
     *
     * @throws Failed when the queue could not be written. The caller must say
     *   so rather than toasting success.
     */
    fun append(body: String): Long

    /**
     * Everything still waiting, **oldest first**.
     *
     * Oldest first because the flush walks this list and stops at the first
     * transport failure (see [ClipFlusher]); newest-first would mean a long
     * off-mesh queue drains in reverse, and the store's `created_at` is
     * assigned on arrival (see [Clip.createdAtMs]), so the other devices would
     * see the operator's clipboard history backwards.
     *
     * @throws Failed when the queue file exists and cannot be read. 🔴 It does
     *   **not** degrade to an empty list: an empty list is what a drained
     *   outbox looks like, so returning one for a damaged file would make the
     *   flusher report "nothing to do" forever over captures that are still
     *   on disk.
     */
    fun pending(): List<Pending>

    /**
     * Drops the item with local id [id]. A no-op when it is already gone.
     *
     * Silent on failure, and that is the one place this file agrees with
     * `SnipUsageStore`: a removal that fails leaves the item queued, so the
     * worst case is one clip posted twice — which the server's
     * `UNIQUE(body_sha256)` dedup collapses into a bumped timestamp. Losing
     * the capture is the failure that matters; posting it twice is not.
     */
    fun remove(id: Long)

    /**
     * One queued capture.
     *
     * @param id a **local** id, from this file's own monotone counter. Not the
     *   server's: the store assigns that on acceptance, and until then there is
     *   nothing to key on. The two id spaces are deliberately different types
     *   of thing in the same `Long`, which is why [Clip] documents its own.
     *
     * ## 🔴 There is deliberately no `capturedAtMs`, and it was DELETED
     *
     * It held when the user actually took the capture, was written into the
     * queue file as `created_at`, was parsed back out — and was **consumed by
     * no caller at all**. Its kdoc kept it *"for anyone who later wants it"*,
     * which is the justification that makes a field permanent: nothing can ever
     * disprove it. Deletion candidate D5 of this branch's audit round 0,
     * accepted. [Clip.createdAtMs] is server-assigned and is what every surface
     * actually renders.
     *
     * 🔴 **An outbox file written by the PREVIOUS build still reads correctly**,
     * which was checked rather than assumed. The old envelope's rows carry a
     * `created_at` key; `ClipOutboxStore.readState` reads only `id` and `body`
     * and ignores every other key, so an upgrade over a queue of pending
     * captures loses nothing — the first rewrite simply drops the stale key.
     * `ClipOutboxStoreTest.aQueueFileFromThePreviousBuildIsReadInFull` pins it,
     * and that is not a nicety: this file is the ONLY copy of the user's
     * capture, so a parse that rejected or skipped an old row would destroy
     * captures on upgrade.
     */
    data class Pending(val id: Long, val body: String)

    /** The queue could not be written, or could not be read. */
    class Failed(reason: String, cause: Throwable? = null) : Exception(reason, cause)
}

/**
 * [ClipOutbox] over one JSON file under `noBackupFilesDir`.
 *
 * `SnipStore`'s mechanics exactly — injected `dir` for tests, a **process-wide**
 * `LOCK`, write-temp-then-`renameTo` with an in-place fallback, `out.fd.sync()`
 * — because the same two writers-in-one-process shape applies: `CaptureActivity`
 * appends while `BubbleService`'s flush removes, over one directory.
 *
 * 🔴 The whole queue is rewritten on every mutation rather than appended to.
 * That is the right trade at this size (a handful of rows, each a clipboard
 * selection) and it is what makes the rename atomic: an append-only log would
 * need its own torn-last-record rule, which is a second failure mode for a file
 * that is the only copy of the user's capture.
 */
class ClipOutboxStore internal constructor(
    /** The outbox directory. Injected so tests can use a temp dir. */
    internal val dir: File,
) : ClipOutbox {

    /** The production constructor, rooted under [Context.getNoBackupFilesDir]. */
    constructor(context: Context) : this(File(context.noBackupFilesDir, DIR))

    private val file: File get() = File(dir, FILE)
    private val temp: File get() = File(dir, "$FILE$TMP_SUFFIX")

    override fun append(body: String): Long = synchronized(LOCK) {
        if (body.isEmpty()) {
            // 🔴 Refused rather than queued. The service answers an empty body
            // with a 400 (`error: empty_body`, measured), so queuing one would
            // put an item in the outbox that can never leave it — and the user
            // would have been told it was taken.
            throw ClipOutbox.Failed("there was nothing to capture")
        }
        val state = readState()
        val id = state.nextId
        val items = state.items + ClipOutbox.Pending(id, body)
        write(nextId = id + 1, items = items)
        id
    }

    override fun pending(): List<ClipOutbox.Pending> = synchronized(LOCK) {
        readState().items
    }

    override fun remove(id: Long) = synchronized(LOCK) {
        try {
            val state = readState()
            val kept = state.items.filterNot { it.id == id }
            if (kept.size != state.items.size) write(state.nextId, kept)
        } catch (_: Exception) {
            // See [ClipOutbox.remove]: a failed removal costs one duplicate
            // POST, which the server's dedup collapses. It must not throw into
            // the flush loop and stop the rest of the queue draining.
        }
        Unit
    }

    private data class State(val nextId: Long, val items: List<ClipOutbox.Pending>)

    /**
     * The one parse of the queue file. Every public method goes through it, so
     * there is a single definition of what the file means.
     *
     * 🔴 Assumes [LOCK] is held — every caller takes it. Kotlin's
     * `synchronized` is reentrant, so a nested take would not deadlock; this is
     * factored for one-definition rather than to dodge one.
     *
     * 🔴 `nextId` is read from the file and never derived from `items.size` or
     * from `max(id) + 1`. Deriving it would **reuse an id** after a removal:
     * drain the queue and the next capture is id 1 again, which is only
     * harmless until two surfaces hold a `Pending` across a flush.
     */
    private fun readState(): State {
        if (!file.isFile) return State(nextId = 1L, items = emptyList())
        val root = try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            throw ClipOutbox.Failed("the outbox file could not be read", e)
        }
        val array = root.optJSONArray(ClipJson.KEY_CLIPS)
            ?: throw ClipOutbox.Failed("the outbox file has no items array")
        val items = ArrayList<ClipOutbox.Pending>(array.length())
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            val id = row.optLong(ClipJson.KEY_ID, 0L)
            val body = row.optString(ClipJson.KEY_BODY, "")
            // A row with no id or no body carries nothing that can be posted,
            // and keeping it would be an item that fails forever. Dropped on
            // READ rather than trusted, as SnipUsageStore filters a
            // non-positive stamp.
            //
            // 🔴 `id` and `body` are the only keys read, which is what makes a
            // queue file written by the PREVIOUS build — whose rows also carry
            // a `created_at` — read back in full rather than being rejected or
            // skipped. See [ClipOutbox.Pending]: that is upgrade safety for the
            // only copy of the user's capture, not tolerance for its own sake.
            if (id > 0L && body.isNotEmpty()) {
                items += ClipOutbox.Pending(id, body)
            }
        }
        val stored = root.optLong(KEY_NEXT_ID, 0L)
        val floor = (items.maxOfOrNull { it.id } ?: 0L) + 1L
        return State(nextId = maxOf(stored, floor), items = items)
    }

    /**
     * Replaces the queue file.
     *
     * Write-temp-then-rename for [DictationHistoryStore]'s reason: a rename
     * within one directory is atomic, so a kill mid-write leaves the PREVIOUS
     * queue rather than a torn one. The in-place fallback covers the
     * filesystems whose `renameTo` refuses an existing target.
     *
     * ⚠ That fallback is **not reachable in the suite** — the tests run on a
     * filesystem where `renameTo` succeeds — which is the same thing
     * `SnipUsageStore` records measuring about its own copy. Stated so it is
     * not read as covered.
     */
    private fun write(nextId: Long, items: List<ClipOutbox.Pending>) {
        try {
            dir.mkdirs()
            val payload = JSONObject()
                .put(KEY_NEXT_ID, nextId)
                .put(ClipJson.KEY_CLIPS, ClipJson.toJsonArray(items))
                .toString()
            val scratch = temp
            FileOutputStream(scratch).use { out ->
                out.write(payload.toByteArray())
                out.fd.sync()
            }
            if (!scratch.renameTo(file)) {
                file.writeText(payload)
                scratch.delete()
            }
        } catch (e: Exception) {
            // 🔴 Rethrown, NOT swallowed. See the interface kdoc: this file is
            // the only copy of the user's capture, so the one thing that must
            // not happen is a success report over a failed write.
            throw ClipOutbox.Failed("the capture could not be saved", e)
        }
    }

    companion object {

        /**
         * 🔴 Process-wide, not per-instance, for [SnipStore]'s measured reason:
         * production builds one store per owner over one directory
         * (`CaptureActivity` appends, `BubbleService` and `ClipPickerActivity`
         * remove), so an instance monitor would exclude each from itself and
         * nothing else.
         */
        private val LOCK = Any()

        /**
         * The directory name under `noBackupFilesDir`.
         *
         * 🔴 Its own directory rather than `SnipStore.DIR`, deliberately. The
         * two have opposite failure postures (see the interface kdoc), and a
         * shared directory is how a future `clear the snippets` sweep would
         * take the user's unsent captures with it.
         */
        const val DIR: String = "clips"

        /** The queue file name, inside [DIR]. */
        const val FILE: String = "outbox.json"

        private const val TMP_SUFFIX = ".tmp"
        private const val KEY_NEXT_ID = "next_id"
    }
}
