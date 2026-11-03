package dev.zacx.dicta

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * The imported catalog, and **the one seam for where a catalog comes from**.
 *
 * ## Why an interface rather than calling the store directly
 *
 * Issue #38 offers a second import route — fetch the catalog from the host over
 * the tailnet — as a follow-up. That follow-up must not touch the picker, so
 * the picker depends on [SnipSource] and nothing else: a fetcher drops in as a
 * second implementation behind it. 🔴 The fetch itself is deliberately **not
 * built here**; one seam is the whole of the preparation for it.
 */
fun interface SnipSource {
    /** The catalog, or `null` when nothing has been imported (or it is unreadable). */
    fun catalog(): SnipStore.Catalog?
}

/**
 * The imported snippet catalog on disk.
 *
 * ## 🔴 `noBackupFilesDir`, like [DictationHistoryStore]
 *
 * Two reasons, and only one of them is consistency. The snippets are a
 * **re-importable cache** — the authority is the `snip` catalog on the
 * operator's machine — so there is nothing here worth carrying to a new device.
 * And a snippet body is arbitrary operator text: paths, hostnames, prompt
 * templates. Putting it on a path the platform excludes from cloud auto-backup
 * and from device-to-device transfer by property of the path itself means the
 * decision survives losing `android:allowBackup="false"`, which is the same
 * argument [DictationHistoryStore] makes at length for recordings.
 *
 * 🔴 And deliberately NOT `cacheDir`, for [DictationHistoryStore]'s reason
 * exactly: `cacheDir` can be emptied at any moment with no event, so a user who
 * has built a habit on the picker would find it silently and inexplicably
 * empty — strictly worse than not having the feature.
 *
 * ## Failure posture: the opposite of the history store's
 *
 * An unreadable or corrupt catalog reads as **"nothing imported"** and is left
 * on disk untouched. There is no sweep and no reset, because the two stores
 * hold opposite kinds of thing: history is the only copy of the user's voice,
 * so a corrupt index there has to be resolved destructively to keep the app
 * startable. A corrupt catalog costs one re-import, so the safe answer is to
 * report the empty state and change nothing. [SnipPickerActivity]'s empty copy
 * is what tells the user.
 */
class SnipStore internal constructor(
    /** The catalog directory. Injected so tests can use a temp dir. */
    internal val dir: File,
) : SnipSource {

    /** The production constructor, rooted under [Context.getNoBackupFilesDir]. */
    constructor(context: Context) : this(File(context.noBackupFilesDir, DIR))

    /**
     * What was imported, and when.
     *
     * [importedAtMs] is stored rather than taken from the file's mtime because
     * the settings screen states it to the user, and an mtime is whatever the
     * last write happened to be — including a write this app made for an
     * unrelated reason.
     */
    data class Catalog(val snippets: List<Snippet>, val importedAtMs: Long)

    private val file: File get() = File(dir, FILE)
    private val temp: File get() = File(dir, "$FILE$TMP_SUFFIX")

    /** The stored catalog, or null when there is none or it cannot be read. */
    override fun catalog(): Catalog? = synchronized(LOCK) {
        if (!file.isFile) return@synchronized null
        try {
            val root = JSONObject(file.readText())
            Catalog(
                snippets = SnipCatalog.parse(root.getJSONArray(KEY_SNIPPETS).toString()),
                importedAtMs = root.optLong(KEY_IMPORTED_AT, 0L),
            )
        } catch (_: Exception) {
            // See "Failure posture" on the class: report nothing imported, and
            // leave the file alone for a re-import to overwrite.
            null
        }
    }

    /**
     * Replaces the stored catalog.
     *
     * Write-temp-then-rename for the same reason [DictationHistoryStore] does:
     * a rename within one directory is atomic, so a kill mid-write leaves the
     * PREVIOUS catalog rather than a torn one. The in-place fallback covers the
     * filesystems whose `renameTo` refuses an existing target.
     */
    fun save(snippets: List<Snippet>, importedAtMs: Long) = synchronized(LOCK) {
        dir.mkdirs()
        val payload = JSONObject()
            .put(KEY_VERSION, VERSION)
            .put(KEY_IMPORTED_AT, importedAtMs)
            .put(KEY_SNIPPETS, SnipCatalog.toJson(snippets))
            .toString()
        val scratch = temp
        FileOutputStream(scratch).use { out ->
            out.write(payload.toByteArray())
            out.fd.sync()
        }
        if (scratch.renameTo(file)) return@synchronized
        file.writeText(payload)
        scratch.delete()
        Unit
    }

    companion object {
        /**
         * 🔴 Process-wide, not per-instance, for [DictationHistoryStore]'s
         * measured reason: production builds one store per owner over one
         * directory (`SettingsActivity` writes, `SnipPickerActivity` reads), so
         * an instance monitor would exclude each from itself and nothing else.
         */
        private val LOCK = Any()

        /** The directory name under `noBackupFilesDir`. */
        const val DIR: String = "snippets"

        /** The catalog file name, inside [DIR]. */
        const val FILE: String = "catalog.json"

        private const val TMP_SUFFIX = ".tmp"
        private const val KEY_SNIPPETS = "snippets"
        private const val KEY_IMPORTED_AT = "importedAtMs"
        private const val KEY_VERSION = "version"
        private const val VERSION = 1
    }
}
