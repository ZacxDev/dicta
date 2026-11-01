package dev.zacx.dicta

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Stages ONE recording in the cache so it can be handed to a share target,
 * and keeps [DictaFileProvider] away from the dictation archive.
 *
 * ## Why a copy, rather than serving the archive directly
 *
 * 🔴 The obvious implementation is to point the provider at
 * `noBackupFilesDir/history` and serve the stored `.ogg` where it lies. It
 * works, it copies nothing, and it was rejected on blast radius: that
 * directory holds **every** dictation the user has ever taken **and
 * `index.json`, which is every transcript**, and it sits one level from
 * `shared_prefs` and the encrypted bearer-token blob. Serving it means the
 * only thing between the whole archive and a share target is that no caller
 * currently builds such a URI — which is one line away, and which no test can
 * pin, because it is an absence.
 *
 * Staging a copy makes the provider's reach a **cache directory containing
 * exactly the file the user asked to share**. The archive is then not
 * reachable through the provider at all, by construction rather than by
 * guard. Operator decision, 2026-10-31, on an audit finding.
 *
 * The cost is honest and bounded: one duplicate of up to ~2.3 MiB per share,
 * in `cacheDir`, which the platform may evict freely — and [stage] clears the
 * directory first, so at most one staged recording exists at a time.
 */
object ShareStage {

    /** The directory name under `cacheDir`. */
    const val DIR = "share"

    fun directory(context: Context): File =
        File(context.cacheDir, DIR).apply { mkdirs() }

    /**
     * Copies [source] into the share directory and returns its provider URI.
     *
     * Any previously staged file is deleted first: one stale recording must
     * not be attachable to a later share, and the cache must not grow without
     * bound. Same contract as `ScreenshotStore.writePng`.
     *
     * @return the URI to put in the share intent.
     */
    fun stage(context: Context, source: File): Uri {
        val dir = directory(context)
        dir.listFiles()?.forEach { it.delete() }
        val staged = File(dir, source.name)
        source.copyTo(staged, overwrite = true)
        return DictaFileProvider.uriFor(DictaFileProvider.ROOT_SHARE, staged.name)
    }

    /**
     * The media type of [file], read from its **first bytes** rather than its
     * name.
     *
     * 🔴 The name cannot be trusted here, and this is measured rather than
     * defensive. `DictationSession` names every capture `dictation-<ms>.ogg`
     * unconditionally, while `AudioCaptureLogic.pickEncoder` falls back to
     * **AAC-LC in MPEG-4** whenever Opus cannot be initialised — and
     * `AudioEncoderSink.extensionFor` exists to spell the right suffix but has
     * **no callers**. So on a handset without Opus the stored bytes are MP4
     * inside a file called `.ogg`, and trusting the extension hands a share
     * target MP4 declared as `audio/ogg`, which it then fails to open. That is
     * the exact "`.m4a` labelled as Ogg" failure an earlier draft's kdoc
     * claimed to prevent, arriving through the other door.
     *
     * Ogg starts with the capture pattern `OggS`; ISO-BMFF (MP4/M4A) carries
     * `ftyp` at offset 4. Anything else degrades to
     * [DictaFileProvider.MIME_UNKNOWN] rather than being guessed — a wrong
     * type is worse than an unknown one, because the receiver acts on it.
     */
    fun mimeOf(file: File): String {
        val head = ByteArray(12)
        val read = try {
            file.inputStream().use { it.read(head) }
        } catch (_: Exception) {
            return DictaFileProvider.MIME_UNKNOWN
        }
        if (read < 12) return DictaFileProvider.MIME_UNKNOWN
        if (head.matches(0, "OggS")) return MIME_OGG
        if (head.matches(4, "ftyp")) return MIME_MP4
        return DictaFileProvider.MIME_UNKNOWN
    }

    const val MIME_OGG = "audio/ogg"
    const val MIME_MP4 = "audio/mp4"

    private fun ByteArray.matches(offset: Int, ascii: String): Boolean =
        ascii.indices.all { i -> this[offset + i] == ascii[i].code.toByte() }
}
