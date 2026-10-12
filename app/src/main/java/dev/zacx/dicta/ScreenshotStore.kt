package dev.zacx.dicta

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * Where captured screenshots live: a single directory under `cacheDir`, holding
 * at most the most recent capture.
 *
 * `cacheDir` and not `filesDir` on purpose — a screenshot of whatever was on
 * screen is sensitive, it is only needed until the share sheet finishes reading
 * it, and the OS is free to reclaim it.
 */
object ScreenshotStore {

    private const val DIR = "screenshots"

    fun directory(context: Context): File =
        File(context.cacheDir, DIR).apply { mkdirs() }

    /**
     * Writes [bitmap] as a PNG and returns its provider URI. Any previous
     * capture is deleted first, so one stale screenshot cannot be attached to a
     * later share, and the cache cannot grow without bound.
     */
    fun writePng(context: Context, bitmap: Bitmap, now: Long = System.currentTimeMillis()): Uri {
        val dir = directory(context)
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "dicta-$now.png")
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "PNG encode failed"
            }
        }
        return DictaFileProvider.uriFor(file.name)
    }
}
