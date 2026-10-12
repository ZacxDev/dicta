package dev.zacx.dicta

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Screen geometry to mirror. Separated so it can be supplied in a test. */
data class CaptureTarget(val widthPx: Int, val heightPx: Int, val densityDpi: Int)

/**
 * One-shot screen capture through a [MediaProjection].
 *
 * The ImageReader and VirtualDisplay are created *inside* [capture] and torn
 * down before it returns, which is both how resources are released
 * deterministically and how we know the frame is clean: see
 * [ChoreographerFrameSettler] for why the first frame of a freshly created
 * mirror cannot contain a window that was removed before the mirror existed.
 *
 * Blocking. Call it off the main thread.
 */
class ScreenCapturer(private val target: CaptureTarget) {

    /** Signals that the projection was stopped while we were waiting. */
    private object Revoked

    /**
     * @throws IllegalStateException if the virtual display produces no frame
     *   within [timeoutMs], or the projection is stopped underneath us.
     */
    fun capture(projection: MediaProjection, timeoutMs: Long = 5_000L): Bitmap {
        require(target.widthPx > 0 && target.heightPx > 0) {
            "bad capture target: $target"
        }

        val thread = HandlerThread("dicta-capture")
        thread.start()
        val handler = Handler(thread.looper)

        // maxImages = 2: one in flight to us, one for the producer to fill, so
        // the VirtualDisplay never stalls waiting for a free buffer.
        val reader = ImageReader.newInstance(
            target.widthPx, target.heightPx, PixelFormat.RGBA_8888, 2
        )
        // Holds either the first Image or the Revoked sentinel. ArrayBlockingQueue
        // rejects nulls, hence the sentinel rather than offer(null).
        val signals = ArrayBlockingQueue<Any>(2)
        var virtualDisplay: VirtualDisplay? = null
        var callback: MediaProjection.Callback? = null

        try {
            reader.setOnImageAvailableListener({ r ->
                val image = try {
                    r.acquireLatestImage()
                } catch (_: Throwable) {
                    null
                } ?: return@setOnImageAvailableListener
                // Keep only the first; anything later is surplus and must be
                // closed or the reader runs out of buffers.
                if (!signals.offer(image)) image.close()
            }, handler)

            // Required from API 34: createVirtualDisplay throws if no callback
            // is registered. onStop also fires when the user revokes the
            // session from the status bar mid-capture.
            callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    // Unblock the waiter rather than let it sit out the timeout.
                    signals.offer(Revoked)
                }
            }
            projection.registerCallback(callback, handler)

            virtualDisplay = projection.createVirtualDisplay(
                "dicta-screenshot",
                target.widthPx,
                target.heightPx,
                target.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            ) ?: throw IllegalStateException("createVirtualDisplay returned null")

            when (val signal = signals.poll(timeoutMs, TimeUnit.MILLISECONDS)) {
                is Image -> try {
                    return toBitmap(signal)
                } finally {
                    signal.close()
                }
                Revoked -> throw IllegalStateException(
                    "screen capture permission was revoked"
                )
                else -> throw IllegalStateException(
                    "no frame from the virtual display within ${timeoutMs}ms"
                )
            }
        } finally {
            // Deterministic teardown, in dependency order.
            virtualDisplay?.release()
            runCatching { reader.setOnImageAvailableListener(null, null) }
            while (true) {
                val leftover = signals.poll() ?: break
                (leftover as? Image)?.close()
            }
            reader.close()
            callback?.let { runCatching { projection.unregisterCallback(it) } }
            runCatching { projection.stop() }
            thread.quitSafely()
        }
    }

    companion object {

        /**
         * RGBA_8888 planes are commonly padded so each row starts on a hardware
         * alignment boundary, i.e. `rowStride > width * pixelStride`. Copying
         * the buffer straight into a width-sized bitmap then walks into the
         * padding and shears the image diagonally. Absorb the padding by
         * allocating a bitmap `rowStride / pixelStride` pixels wide, then crop
         * back to the real width.
         */
        fun toBitmap(image: Image): Bitmap {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val width = image.width
            val height = image.height

            // The arithmetic is NOT duplicated here: paddedWidthFor is the one
            // implementation and owns both validity checks, so the tests that
            // exercise it are exercising the shipping path.
            val paddedWidth = paddedWidthFor(width, rowStride, pixelStride)
            val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            if (paddedWidth == width) return padded
            val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
            if (cropped !== padded) padded.recycle()
            return cropped
        }

        /**
         * Width, in pixels, of the bitmap [toBitmap] must allocate before
         * cropping — and the only place this arithmetic exists. [toBitmap]
         * calls it; it is NOT a test-only copy of the shipping calculation.
         * (It used to be: `toBitmap` recomputed the same expression inline, and
         * the two copies had already drifted apart — different exception types
         * for the same bad plane, and only one of them guarded the divisor.)
         *
         * Both failure modes throw [IllegalArgumentException], not
         * [IllegalStateException]: a plane whose `pixelStride` is <= 0, or whose
         * `rowStride` is narrower than one row, is a bad *argument* handed to us
         * by the ImageReader rather than a bad state of this object — and one
         * exception type for both means a caller never has to tell them apart.
         *
         * `pixelStride` is checked **before** the padding, because the padding
         * division would otherwise divide by zero.
         */
        fun paddedWidthFor(width: Int, rowStride: Int, pixelStride: Int): Int {
            require(pixelStride > 0) { "pixelStride($pixelStride) must be > 0" }
            val rowPadding = rowStride - pixelStride * width
            require(rowPadding >= 0) {
                "rowStride($rowStride) < width($width) * pixelStride($pixelStride)"
            }
            return width + rowPadding / pixelStride
        }
    }
}
