package dev.zacx.dicta

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * Minimal read-only content provider over `cacheDir/screenshots`, so a share
 * target can read the PNG we just wrote.
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

    override fun getType(uri: Uri): String = MIME_PNG

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
        return resolveIn(ScreenshotStore.directory(ctx), uri)
    }

    companion object {
        const val AUTHORITY = "dev.zacx.dicta.files"
        const val MIME_PNG = "image/png"

        fun uriFor(name: String): Uri = Uri.Builder()
            .scheme("content")
            .authority(AUTHORITY)
            .appendPath(name)
            .build()

        /**
         * Maps a provider URI back to a file, refusing anything that does not
         * land directly inside [root]. Pulled out of the instance so the
         * traversal guard is testable.
         */
        fun resolveIn(root: File, uri: Uri): File {
            val segments = uri.pathSegments
            require(segments.size == 1) {
                "expected exactly one path segment, got ${segments.size} in $uri"
            }
            val candidate = File(root, segments[0]).canonicalFile
            val canonicalRoot = root.canonicalFile
            require(candidate.parentFile == canonicalRoot) {
                "refusing to serve $candidate — outside $canonicalRoot"
            }
            return candidate
        }
    }
}
