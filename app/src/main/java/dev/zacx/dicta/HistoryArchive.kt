package dev.zacx.dicta

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The dictation-history archive: one zip holding every history item's metadata
 * and audio plus a checksummed manifest. The format is specified in
 * `docs/history-archive-format.md`, which is written so that a DIFFERENT app
 * can implement import from it alone — keep the two in step.
 *
 * ## Why zip, and not tar
 *
 * `java.util.zip` ships in every Android runtime and in the JVM the unit tests
 * run on. Android has no tar reader or writer in its platform APIs, so tar
 * would mean either a new runtime dependency (this APK ships none, by
 * decision — see `app/build.gradle.kts`) or a hand-rolled tar codec, which is
 * more code to get wrong than the feature itself. Zip also gives each entry a
 * CRC-32 that [ZipInputStream] checks, a second, independent corruption check
 * underneath the manifest's SHA-256.
 *
 * ## This object does no history IO
 *
 * It writes an archive from items it is handed, and reads an archive into a
 * staging directory it is handed. Deciding what to export, and committing a
 * verified archive into history, is [DictationHistoryStore]'s job, under its
 * lock. That split is what makes "verify everything BEFORE writing anything"
 * structural: [read] has no reference to the history directory at all.
 */
object HistoryArchive {

    /** The manifest's `format` value. Anything else is not this format. */
    const val FORMAT = "dicta-history-archive"

    /** The only `formatVersion` this build reads and the one it writes. */
    const val FORMAT_VERSION = 1

    const val MANIFEST = "manifest.json"
    const val ITEMS = "items.json"
    const val AUDIO_PREFIX = "audio/"

    /** The MIME type the archive is created and opened as. */
    const val MIME = "application/zip"

    /**
     * Item ids: what [DictationHistoryStore] generates (`%013d-%08x`) fits
     * well inside this. No `.`, so `<id>.<ext>` splits unambiguously, and no
     * `/`, so an id can never name a path.
     */
    private val ID = Regex("[A-Za-z0-9_-]{1,128}")

    /** Audio file extensions — the same "letters or digits" rule the store applies. */
    private val EXT = Regex("[A-Za-z0-9]{1,16}")

    private val SHA256_HEX = Regex("[0-9a-f]{64}")

    /** No more entries than this. History holds at most 1000 items. */
    internal const val MAX_ENTRIES = 10_000

    /** `manifest.json` and `items.json` are read into memory, so they are capped. */
    internal const val MAX_JSON_BYTES = 64L * 1024 * 1024

    /** Total uncompressed bytes — a zip-bomb bound, twice history's 2 GiB budget. */
    internal const val MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024

    /** Why an archive was refused. Every one of these means nothing was written. */
    enum class Reason {
        /** Not a readable zip, or a zip that ends part-way through an entry. */
        MALFORMED,
        /** No `manifest.json` — not this format, or truncated before it. */
        NO_MANIFEST,
        /** `format` is not [FORMAT]. */
        WRONG_FORMAT,
        /** `formatVersion` is not one this build reads. */
        UNSUPPORTED_VERSION,
        /** A file the manifest lists is not in the archive. */
        MISSING_FILE,
        /** A file in the archive that the manifest does not list, or a duplicate entry. */
        UNEXPECTED_FILE,
        /** A file's SHA-256 or byte count is not what the manifest says. */
        CHECKSUM_MISMATCH,
        /** Manifest or items metadata that is not valid for this format. */
        INVALID_METADATA,
        /** Past [MAX_ENTRIES], [MAX_JSON_BYTES] or [MAX_TOTAL_BYTES]. */
        TOO_LARGE,
    }

    class Rejected(val reason: Reason, detail: String) : Exception("$reason: $detail")

    /** One item to export: its index row, and the audio file it names. */
    data class Source(val item: DictationHistoryStore.Item, val audio: File)

    /**
     * One verified item: its metadata, the staged audio file, and the file
     * name it takes in the history directory. Only [read] constructs these,
     * and only after every check has passed.
     */
    data class Staged(
        val item: DictationHistoryStore.Item,
        val staged: File,
        val sha256: String,
    )

    /**
     * Writes [sources] as an archive to [out], newest first. [out] is not
     * closed — the caller owns it.
     *
     * The manifest is written LAST, so each file is hashed while it is copied
     * and read only once. A reader therefore cannot verify anything until it
     * has seen the whole stream, which [read] never tries to: it stages
     * everything first.
     *
     * 🔴 Refuses to write an archive [read] would reject, rather than handing
     * the user a file that only fails on the other phone. An id outside [ID]
     * cannot come from the store, but this is the last point at which a bad
     * one is cheap to report.
     */
    fun write(sources: List<Source>, out: OutputStream, createdAtMs: Long) {
        val ids = HashSet<String>()
        for (s in sources) {
            require(ID.matches(s.item.id)) { "id is not archive-safe: ${s.item.id}" }
            require(ids.add(s.item.id)) { "duplicate id: ${s.item.id}" }
        }
        val zip = ZipOutputStream(out)
        val files = JSONArray()
        val items = JSONArray()
        for (s in sources) {
            val path = AUDIO_PREFIX + audioName(s.item.id, s.item.audioFile)
            val digest = MessageDigest.getInstance("SHA-256")
            zip.putNextEntry(ZipEntry(path))
            var bytes = 0L
            s.audio.inputStream().use { input ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    zip.write(buf, 0, n)
                    bytes += n
                }
            }
            zip.closeEntry()
            files.put(fileEntry(path, hex(digest.digest()), bytes))
            // audioBytes is what was actually read, not the index's copy: the
            // reader requires the two to agree, and the bytes are the truth.
            items.put(
                JSONObject()
                    .put("id", s.item.id)
                    .put("createdAtMs", s.item.createdAtMs)
                    .put("audioBytes", bytes)
                    .put("audio", path)
                    .put("text", s.item.text)
                    .put("fromRemote", s.item.fromRemote)
                    .put("audioSeconds", s.item.audioSeconds),
            )
        }
        val itemsBytes = JSONObject().put("items", items).toString().toByteArray(Charsets.UTF_8)
        zip.putNextEntry(ZipEntry(ITEMS))
        zip.write(itemsBytes)
        zip.closeEntry()
        files.put(fileEntry(ITEMS, sha256(itemsBytes), itemsBytes.size.toLong()))

        val manifest = JSONObject()
            .put("format", FORMAT)
            .put("formatVersion", FORMAT_VERSION)
            .put("createdAtMs", createdAtMs)
            .put("itemCount", sources.size)
            .put("files", files)
        zip.putNextEntry(ZipEntry(MANIFEST))
        zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        zip.finish()
        out.flush()
    }

    /**
     * Reads an archive from [input] into [staging] and verifies ALL of it,
     * returning the items only when every check has passed.
     *
     * 🔴 Nothing outside [staging] is touched, and the caller deletes
     * [staging] whatever happens. Entry names are never used as file paths —
     * every entry is staged under a counter name — so a hostile name
     * (`../x`, an absolute path) cannot write anywhere; it is simply a name
     * the manifest does not list, and the archive is refused for it.
     *
     * @throws Rejected for anything wrong with the archive's CONTENT.
     * @throws java.io.IOException for a failure to READ it that says nothing
     *   about its content (a provider error, a full disk while staging).
     */
    fun read(input: InputStream, staging: File): List<Staged> {
        staging.mkdirs()
        val entries = stage(input, staging)

        val manifestEntry = entries[MANIFEST]
            ?: throw Rejected(Reason.NO_MANIFEST, "no $MANIFEST in the archive")
        val manifest = parseObject(manifestEntry.file, MANIFEST)
        val format = manifest.optString("format", "")
        if (format != FORMAT) throw Rejected(Reason.WRONG_FORMAT, "format is '$format'")
        val version = manifest.opt("formatVersion")
        if (version !is Int || version != FORMAT_VERSION) {
            throw Rejected(Reason.UNSUPPORTED_VERSION, "formatVersion is $version, this app reads $FORMAT_VERSION")
        }

        // --- every listed file is present, every present file is listed,
        // --- and every one hashes to what the manifest says.
        val listed = HashMap<String, Listed>()
        val filesArray = manifest.optJSONArray("files")
            ?: throw Rejected(Reason.INVALID_METADATA, "manifest has no files array")
        for (i in 0 until filesArray.length()) {
            val f = filesArray.optJSONObject(i)
                ?: throw Rejected(Reason.INVALID_METADATA, "files[$i] is not an object")
            val path = f.opt("path") as? String
                ?: throw Rejected(Reason.INVALID_METADATA, "files[$i] has no path")
            val sha = f.opt("sha256") as? String
            if (sha == null || !SHA256_HEX.matches(sha)) {
                throw Rejected(Reason.INVALID_METADATA, "files[$i] sha256 is not 64 lowercase hex digits")
            }
            val bytes = exactLong(f.opt("bytes"))
            if (bytes == null || bytes < 0) throw Rejected(Reason.INVALID_METADATA, "files[$i] bytes is not a count")
            if (path == MANIFEST) throw Rejected(Reason.INVALID_METADATA, "the manifest lists itself")
            if (listed.put(path, Listed(sha, bytes)) != null) {
                throw Rejected(Reason.INVALID_METADATA, "$path is listed twice")
            }
        }
        for ((path, want) in listed) {
            val got = entries[path] ?: throw Rejected(Reason.MISSING_FILE, "$path is listed but not in the archive")
            if (got.bytes != want.bytes || got.sha256 != want.sha256) {
                throw Rejected(Reason.CHECKSUM_MISMATCH, "$path does not match its manifest checksum")
            }
        }
        for (path in entries.keys) {
            if (path != MANIFEST && path !in listed) {
                throw Rejected(Reason.UNEXPECTED_FILE, "$path is in the archive but not in the manifest")
            }
        }
        if (ITEMS !in listed) throw Rejected(Reason.MISSING_FILE, "the manifest does not list $ITEMS")

        // --- the items: strict types, safe ids, one item per audio file.
        val itemsArray = parseObject(entries.getValue(ITEMS).file, ITEMS).optJSONArray("items")
            ?: throw Rejected(Reason.INVALID_METADATA, "$ITEMS has no items array")
        val out = ArrayList<Staged>(itemsArray.length())
        val ids = HashSet<String>()
        val claimed = HashSet<String>()
        for (i in 0 until itemsArray.length()) {
            val row = itemsArray.optJSONObject(i)
                ?: throw Rejected(Reason.INVALID_METADATA, "items[$i] is not an object")
            val id = row.opt("id") as? String
            if (id == null || !ID.matches(id)) throw Rejected(Reason.INVALID_METADATA, "items[$i] id is not valid")
            if (!ids.add(id)) throw Rejected(Reason.INVALID_METADATA, "id $id appears twice")
            val createdAtMs = exactLong(row.opt("createdAtMs"))
                ?: throw Rejected(Reason.INVALID_METADATA, "items[$i] createdAtMs is not an integer")
            val audioBytes = exactLong(row.opt("audioBytes"))
                ?: throw Rejected(Reason.INVALID_METADATA, "items[$i] audioBytes is not an integer")
            val text = row.opt("text") as? String
                ?: throw Rejected(Reason.INVALID_METADATA, "items[$i] text is not a string")
            val fromRemote = row.opt("fromRemote") as? Boolean
                ?: throw Rejected(Reason.INVALID_METADATA, "items[$i] fromRemote is not a boolean")
            val seconds = (row.opt("audioSeconds") as? Number)?.toDouble()
            if (seconds == null || seconds.isNaN() || seconds.isInfinite() || seconds < 0) {
                throw Rejected(Reason.INVALID_METADATA, "items[$i] audioSeconds is not a non-negative number")
            }
            val audio = row.opt("audio") as? String
                ?: throw Rejected(Reason.INVALID_METADATA, "items[$i] has no audio path")
            val name = audio.removePrefix(AUDIO_PREFIX)
            val ext = name.removePrefix(id)
            val nameOk = audio.startsWith(AUDIO_PREFIX) && name.startsWith(id) &&
                (ext.isEmpty() || (ext.startsWith('.') && EXT.matches(ext.substring(1))))
            if (!nameOk) throw Rejected(Reason.INVALID_METADATA, "items[$i] audio path $audio is not audio/<id>[.<ext>]")
            val listedAudio = listed[audio]
                ?: throw Rejected(Reason.MISSING_FILE, "items[$i] audio $audio is not in the manifest")
            if (!claimed.add(audio)) throw Rejected(Reason.INVALID_METADATA, "$audio belongs to two items")
            if (audioBytes != listedAudio.bytes) {
                throw Rejected(Reason.INVALID_METADATA, "items[$i] audioBytes disagrees with the manifest")
            }
            out += Staged(
                item = DictationHistoryStore.Item(
                    id = id,
                    createdAtMs = createdAtMs,
                    audioBytes = audioBytes,
                    audioFile = name,
                    text = text,
                    fromRemote = fromRemote,
                    audioSeconds = seconds,
                ),
                staged = entries.getValue(audio).file,
                sha256 = listedAudio.sha256,
            )
        }
        // Every listed file is either items.json or some item's audio. An
        // audio file no item claims would be imported by nothing — refuse it
        // rather than silently drop part of what the user exported.
        for (path in listed.keys) {
            if (path != ITEMS && path !in claimed) {
                throw Rejected(Reason.UNEXPECTED_FILE, "$path is listed but belongs to no item")
            }
        }
        val count = exactLong(manifest.opt("itemCount"))
        if (count != null && count != out.size.toLong()) {
            throw Rejected(Reason.INVALID_METADATA, "manifest itemCount $count, $ITEMS holds ${out.size}")
        }
        return out
    }

    /** SHA-256 of a file, lowercase hex. */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return hex(digest.digest())
    }

    /** `<id>` plus the extension of [audioFile], by the store's own naming rule. */
    internal fun audioName(id: String, audioFile: String): String {
        val ext = audioFile.substringAfterLast('.', "")
        return if (ext.isNotEmpty() && EXT.matches(ext)) "$id.$ext" else id
    }

    // --- internals ----------------------------------------------------------

    private class Entry(val file: File, val sha256: String, val bytes: Long)
    private class Listed(val sha256: String, val bytes: Long)

    /** Copies every entry into [staging], hashing as it goes. Names are keys, never paths. */
    private fun stage(input: InputStream, staging: File): Map<String, Entry> {
        val entries = HashMap<String, Entry>()
        var total = 0L
        try {
            val zip = ZipInputStream(input)
            val buf = ByteArray(BUFFER)
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                if (entries.size >= MAX_ENTRIES) throw Rejected(Reason.TOO_LARGE, "more than $MAX_ENTRIES entries")
                if (entries.containsKey(entry.name)) {
                    throw Rejected(Reason.UNEXPECTED_FILE, "${entry.name} appears twice")
                }
                val cap = if (entry.name == MANIFEST || entry.name == ITEMS) MAX_JSON_BYTES else Long.MAX_VALUE
                val file = File(staging, "e${entries.size}")
                val digest = MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                file.outputStream().use { out ->
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        bytes += n
                        total += n
                        if (bytes > cap || total > MAX_TOTAL_BYTES) {
                            throw Rejected(Reason.TOO_LARGE, "${entry.name} exceeds the size bound")
                        }
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
                entries[entry.name] = Entry(file, hex(digest.digest()), bytes)
            }
        } catch (e: EOFException) {
            throw Rejected(Reason.MALFORMED, "the archive ends part-way through an entry")
        } catch (e: ZipException) {
            throw Rejected(Reason.MALFORMED, e.message ?: "not a valid zip")
        }
        return entries
    }

    private fun parseObject(file: File, name: String): JSONObject = try {
        JSONObject(file.readText(Charsets.UTF_8))
    } catch (e: JSONException) {
        throw Rejected(Reason.INVALID_METADATA, "$name is not a JSON object")
    }

    /** An integral JSON number as a Long, or null — a `1.5` is not a count. */
    private fun exactLong(v: Any?): Long? = when (v) {
        is Int -> v.toLong()
        is Long -> v
        else -> null
    }

    private fun fileEntry(path: String, sha: String, bytes: Long): JSONObject =
        JSONObject().put("path", path).put("sha256", sha).put("bytes", bytes)

    private fun sha256(bytes: ByteArray): String =
        hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private const val BUFFER = 64 * 1024
}
