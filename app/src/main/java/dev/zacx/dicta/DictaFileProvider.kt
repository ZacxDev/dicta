package dev.zacx.dicta

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * Minimal read-only content provider over a **fixed, enumerated** set of app
 * directories, so a share target can read a file we just wrote.
 *
 * ## Two roots, named in the URI
 *
 * Paths are `<root>/<name>` where `<root>` is one of [ROOT_SCREENSHOTS] or
 * [ROOT_SHARE] and nothing else. It served one implicit root (screenshots)
 * until *Save audio* needed to hand out a recording too.
 *
 * 🔴 **Both roots are under `cacheDir`.** The dictation archive is NOT one of
 * them and must not become one — see [ShareStage].
 *
 * 🔴 **The root is an allowlist lookup, never a path fragment.** The obvious
 * widening — join the first segment onto the data dir — would turn this class
 * into an arbitrary-file reader the moment a segment contained `..`, which is
 * exactly the attack [resolveName] already exists to stop one level down.
 * [directoryFor] therefore maps a known constant to a directory and throws on
 * anything else; an unknown root is refused before any file is touched.
 *
 * Deliberately hand-rolled instead of `androidx.core.content.FileProvider`:
 * this app has an empty runtime `dependencies {}` block and the APK is meant to
 * stay framework-only. Pulling in `androidx.core` for one `getUriForFile` call
 * would drag AndroidX (annotation, lifecycle, …) into a dependency-free PoC.
 * The surface we actually need is `openFile` + the two [OpenableColumns] every
 * share target queries, which is what is implemented here.
 *
 * Writes are rejected outright, and [resolve] refuses anything that escapes the
 * screenshots directory, so a hostile `content://dev.zacx.dicta.files/../..`
 * cannot read arbitrary app files.
 */
class DictaFileProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    /**
     * 🔴 The screenshot root declares PNG because this app wrote the PNG. The
     * share root is sniffed from the BYTES — the capture is always named
     * `.ogg` while the encoder may have produced MP4, so the extension is not
     * evidence. See [ShareStage.mimeOf].
     */
    override fun getType(uri: Uri): String {
        val file = resolve(uri)
        return if (uri.pathSegments[0] == ROOT_SCREENSHOTS) MIME_PNG
        else ShareStage.mimeOf(file)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "DictaFileProvider is read-only (mode=$mode)" }
        return ParcelFileDescriptor.open(
            resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY
        )
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val file = resolve(uri)
        // Share targets ask for exactly these two and fall back to ugly
        // defaults (or refuse the attachment) if they are missing.
        val columns = projection?.filter {
            it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE
        }?.toTypedArray()?.takeIf { it.isNotEmpty() }
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns, 1)
        cursor.addRow(
            columns.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> file.name
                    OpenableColumns.SIZE -> file.length()
                    else -> null
                }
            }.toTypedArray()
        )
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException("read-only")

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException("read-only")

    private fun resolve(uri: Uri): File {
        val ctx = requireNotNull(context) { "provider not attached" }
        val segments = uri.pathSegments
        require(segments.size == 2) {
            "expected <root>/<name>, got ${segments.size} segment(s) in $uri"
        }
        return resolveName(directoryFor(ctx, segments[0]), segments[1])
    }

    companion object {
        const val AUTHORITY = "dev.zacx.dicta.files"
        const val MIME_PNG = "image/png"

        /** `cacheDir/screenshots` — transient capture for the share sheet. */
        const val ROOT_SCREENSHOTS = "screenshots"

        /**
         * `cacheDir/share` — ONE staged recording, put there by [ShareStage].
         *
         * 🔴 This is deliberately NOT `noBackupFilesDir/history`. Serving the
         * archive directly would put every dictation AND `index.json` (every
         * transcript) inside the provider's reach, one level from
         * `shared_prefs` and the encrypted token. See [ShareStage]'s kdoc.
         */
        const val ROOT_SHARE = "share"

        /**
         * Fallback type for a recording whose extension we do not recognise.
         *
         * 🔴 Returned when the bytes match no container we recognise. NOT a
         * guess at `audio/ogg`: a wrong type is worse than an unknown one,
         * because the receiving app acts on it.
         */
        const val MIME_UNKNOWN = "application/octet-stream"

        fun uriFor(root: String, name: String): Uri = Uri.Builder()
            .scheme("content")
            .authority(AUTHORITY)
            .appendPath(root)
            .appendPath(name)
            .build()

        /**
         * The directory a root name stands for.
         *
         * 🔴 A `when` over constants, **not** `File(dataDir, root)`. The root
         * arrives from outside the app and joining it onto a path would make
         * `..` a traversal one level above the guard in [resolveName].
         */
        fun directoryFor(context: Context, root: String): File = when (root) {
            ROOT_SCREENSHOTS -> ScreenshotStore.directory(context)
            ROOT_SHARE -> ShareStage.directory(context)
            else -> throw IllegalArgumentException("unknown root <$root>")
        }

        /**
         * The traversal guard itself: [name] must land **directly** inside
         * [root], so `../shared_prefs/dicta_stt.xml` is refused rather than
         * served.
         */
        fun resolveName(root: File, name: String): File {
            val candidate = File(root, name).canonicalFile
            val canonicalRoot = root.canonicalFile
            require(candidate.parentFile == canonicalRoot) {
                "refusing to serve $candidate — outside $canonicalRoot"
            }
            return candidate
        }

    }
}
