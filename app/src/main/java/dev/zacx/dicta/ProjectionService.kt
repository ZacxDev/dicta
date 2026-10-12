package dev.zacx.dicta

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.IBinder
import android.util.DisplayMetrics
import java.util.concurrent.atomic.AtomicReference

/**
 * Carries one screen-capture request from [DictateActivity] to
 * [ProjectionService] without a binder.
 *
 * The round trip through a service exists for one reason: on Android 14+ a
 * `mediaProjection`-typed foreground service must already be running before
 * `MediaProjectionManager.getMediaProjection()` is called, or the framework
 * throws. The consent result therefore has to be handed to a service rather
 * than consumed in the Activity.
 */
object ProjectionRequests {

    class Request(
        val resultCode: Int,
        val data: Intent,
        val onResult: (Result<Uri>) -> Unit,
    ) {
        private val delivered = java.util.concurrent.atomic.AtomicBoolean(false)
        fun deliver(result: Result<Uri>) {
            if (delivered.compareAndSet(false, true)) onResult(result)
        }
    }

    private val pending = AtomicReference<Request?>(null)

    /** Replaces any pending request, failing the old one so no caller hangs. */
    fun submit(request: Request) {
        pending.getAndSet(request)?.deliver(
            Result.failure(IllegalStateException("superseded by a newer capture request"))
        )
    }

    fun take(): Request? = pending.getAndSet(null)

    /** Fails whatever is pending — used when the service cannot proceed. */
    fun abort(cause: Throwable) {
        pending.getAndSet(null)?.deliver(Result.failure(cause))
    }
}

/**
 * The `mediaProjection` foreground service. It starts itself in the foreground
 * first, *then* acquires the [MediaProjection] and performs the capture, then
 * stops.
 */
class ProjectionService : Service() {

    /** The capture blocks on a composited frame — never on the main thread. */
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must be in the foreground BEFORE getMediaProjection(). Do it
        // unconditionally and before touching the request.
        try {
            startForeground(NOTIF_ID, buildNotification())
        } catch (t: Throwable) {
            ProjectionRequests.abort(t)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val request = ProjectionRequests.take()
        if (request == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        try {
            worker.execute {
                request.deliver(runCatching { runCapture(request) })
                stopSelf(startId)
            }
        } catch (t: Throwable) {
            request.deliver(Result.failure(t))
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun runCapture(request: ProjectionRequests.Request): Uri {
        val mpm = getSystemService(MediaProjectionManager::class.java)
            ?: throw IllegalStateException("no MediaProjectionManager")
        val projection: MediaProjection =
            mpm.getMediaProjection(request.resultCode, request.data)
                ?: throw IllegalStateException("getMediaProjection returned null")

        val bitmap: Bitmap = ScreenCapturer(captureTarget()).capture(projection)
        try {
            return ScreenshotStore.writePng(this, bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Real display geometry, including the parts a window never sees. The
     * mirror must match the physical display or the capture is letterboxed or
     * scaled.
     */
    private fun captureTarget(): CaptureTarget {
        val metrics = DisplayMetrics()
        val display = getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        display?.getRealMetrics(metrics)
        if (metrics.widthPixels == 0 || metrics.heightPixels == 0) {
            val fallback = resources.displayMetrics
            return CaptureTarget(
                fallback.widthPixels, fallback.heightPixels, fallback.densityDpi
            )
        }
        return CaptureTarget(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.projection_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_screenshot)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.projection_active))
            .build()
    }

    companion object {
        private const val NOTIF_ID = 2002
        private const val CHANNEL_ID = "dicta_projection"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ProjectionService::class.java))
        }
    }
}
