package dev.zacx.dicta

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * When each snippet was last picked, so the picker can open on the ones the
 * operator actually uses.
 *
 * ## 🔴 `noBackupFilesDir`, for [SnipStore]'s reason
 *
 * This file is a list of snippet LABELS the operator reaches for most. That is
 * not the snippet bodies — no `kubeconfig` contents, no hostnames — but it is
 * still a usage profile of operator tooling, and [Snippet] names `ssh` and
 * `kubeconfig` among its categories. It lives beside the catalog under
 * `noBackupFilesDir` so it is excluded from cloud backup and from
 * `adb backup`, exactly as the catalog it describes is.
 *
 * ## 🔴 Keyed on LABEL, which is the catalog's own unique key
 *
 * `SnipCatalog.parse` already refuses a catalog whose labels are not unique and
 * non-empty, so the label is a key the data model guarantees — not a convention
 * this file invents. It is also the key that **survives a re-import**, which is
 * the point: the catalog is replaced wholesale by a settings import (and, soon,
 * by a fetch over the mesh), so a key derived from position or from body text
 * would silently reset the ordering on every refresh.
 *
 * The cost of that choice, stated: renaming a snippet in the source catalog
 * reads here as a different snippet, and the renamed one starts unused. That is
 * the correct outcome for a rename that changes what the operator types to find
 * it, and there is no way to tell a rename from a replacement at this layer.
 *
 * ## 🔴 Bounded by the live catalog, not by time
 *
 * [recordAndPrune] drops every label absent from the catalog it is given, so
 * the file cannot outgrow the catalog — 28 rows today. There is deliberately no
 * retention window: a snippet used once a year is exactly the one worth keeping
 * a timestamp for, and an eviction rule here would quietly restore the
 * catalog-order browse this feature exists to replace.
 *
 * ## Failure posture — a usage file is an OPTIMISATION, never a gate
 *
 * Every read returns an empty map on any failure and every write swallows its
 * own exception. A corrupt or unreadable usage file must degrade to
 * catalog-order browse, which is the behaviour that shipped in 0.5.0 — it must
 * never make the picker look empty or refuse to open. 🔴 This is the OPPOSITE
 * posture from the mesh catalog endpoint, which fails loud with a 503 rather
 * than serve `[]`, and the asymmetry is deliberate: there, an empty result is
 * indistinguishable from a working feature with nothing in it; here, the rows
 * are still all present and only their order is affected.
 */
class SnipUsageStore internal constructor(
    /** The usage directory. Injected so tests can use a temp dir. */
    internal val dir: File,
    /** The clock, injected so a test is not at the mercy of wall-clock time. */
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /** The production constructor, rooted under [Context.getNoBackupFilesDir]. */
    constructor(context: Context) : this(File(context.noBackupFilesDir, SnipStore.DIR))

    private val file: File get() = File(dir, FILE)
    private val temp: File get() = File(dir, "$FILE$TMP_SUFFIX")

    /**
     * Label to last-picked epoch millis. Empty when there is no usage yet, and
     * empty when the file cannot be read — see "Failure posture".
     */
    fun lastUsed(): Map<String, Long> = synchronized(LOCK) { read() }

    /**
     * Stamps [label] as picked now, and drops every label not in
     * [liveLabels].
     *
     * 🔴 The prune is in the same write as the stamp on purpose. A separate
     * `prune()` would be a second thing a caller has to remember, and the
     * measured shape of that mistake in this repo is `SnipPickerActivity`
     * shipping 0.4.0 without the one sizing call it was supposed to make. The
     * pick is the only event that writes this file, so it is the only place a
     * prune can be guaranteed to run.
     *
     * [liveLabels] is the catalog the picker just rendered from, so a label
     * that is absent from it is a snippet that no longer exists. Passing an
     * EMPTY set would therefore drop everything — which is why the caller
     * passes the rows it read, and why a failed catalog read never reaches
     * here: the picker cannot pick a row it did not render.
     */
    fun recordAndPrune(label: String, liveLabels: Set<String>) = synchronized(LOCK) {
        if (label.isEmpty()) return@synchronized
        try {
            val merged = HashMap(read())
            merged.keys.retainAll(liveLabels)
            merged[label] = nowMs()

            val used = JSONObject()
            for ((k, v) in merged) used.put(k, v)
            val payload = JSONObject().put(KEY_USED, used).toString()

            dir.mkdirs()
            val scratch = temp
            FileOutputStream(scratch).use { out ->
                out.write(payload.toByteArray())
                out.fd.sync()
            }
            // 🔴 The in-place fallback covers filesystems whose `renameTo`
            // refuses an existing target, as SnipStore's does.
            //
            // 🔴 THIS BRANCH IS UNREACHABLE IN THE SUITE, AND MEASURED SO:
            // mutant m-mru19 removes the `scratch.delete()` and SURVIVES,
            // because the tests run on a filesystem where `renameTo` succeeds
            // and the fallback never executes. `theWriteLeavesNoTemporaryFileBehind`
            // therefore pins the RENAME path only. Stated so the fallback is
            // not read as covered: it is inherited from SnipStore, where the
            // same shape is in production use.
            if (!scratch.renameTo(file)) {
                file.writeText(payload)
                scratch.delete()
            }
        } catch (_: Exception) {
            // See "Failure posture". A pick whose usage write fails must still
            // have put the body on the clipboard, which it already has — this
            // runs after that.
        }
        Unit
    }

    /**
     * The one parse of the usage file. [lastUsed] and [recordAndPrune] both go
     * through it, so there is a single definition of what the file means and of
     * what a damaged one degrades to.
     *
     * 🔴 Assumes [LOCK] is held — both callers take it. Kotlin's `synchronized`
     * is reentrant, so a nested take would not deadlock; this is factored for
     * one-definition rather than to dodge one.
     */
    private fun read(): Map<String, Long> {
        if (!file.isFile) return emptyMap()
        return try {
            val used = JSONObject(file.readText()).getJSONObject(KEY_USED)
            val out = HashMap<String, Long>(used.length())
            for (label in used.keys()) {
                val at = used.optLong(label, 0L)
                // 🔴 A non-positive stamp carries no ordering information, and
                // keeping one would be worse than dropping it: it sorts as
                // "used in 1970", i.e. ahead of the never-used tail, whereas
                // absent correctly means "never used" and falls into catalog
                // order. Reachable from a hand-edited file or a truncated
                // write, so it is filtered on READ rather than trusted.
                if (at > 0L) out[label] = at
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    companion object {

        /**
         * 🔴 Process-wide, for [SnipStore]'s measured reason: production builds
         * one store per owner over one directory, so an instance monitor would
         * exclude each from itself and nothing else.
         */
        private val LOCK = Any()

        /** The usage file name, inside [SnipStore.DIR] beside the catalog. */
        const val FILE: String = "usage.json"

        private const val TMP_SUFFIX = ".tmp"
        private const val KEY_USED = "last_used_ms"

        /**
         * [rows] reordered most-recently-picked first, then everything never
         * picked in the order supplied.
         *
         * 🔴 **BROWSE ONLY.** `SnipMatcher.rank` preserves its input order
         * within the declared and labeled groups, so applying this before
         * `rank` would reorder a TYPED query's results too — which is a
         * different product decision, and not the one that was taken. The
         * caller gates on `SnipMatcher.isBrowse`.
         *
         * 🔴 **Stable, and that is load-bearing for the unused tail.** A
         * snippet never picked has no stamp, and `sortedByDescending` on a
         * stable sort leaves equal keys in their input order — so the unused
         * rows stay in catalog order rather than being shuffled into an
         * arbitrary one. `List.sortedBy` on the JVM is documented stable;
         * `SnipUsageOrderTest` pins the property rather than trusting the doc.
         */
        fun order(rows: List<Snippet>, lastUsed: Map<String, Long>): List<Snippet> =
            rows.sortedByDescending { lastUsed[it.label] ?: Long.MIN_VALUE }
    }
}
