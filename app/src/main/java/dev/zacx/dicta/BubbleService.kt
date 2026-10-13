package dev.zacx.dicta

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import kotlin.math.abs

/**
 * Hosts the floating mic bubble: an always-on-top overlay view
 * (TYPE_APPLICATION_OVERLAY) that opens [DictateActivity] from anywhere.
 * The bubble is draggable; a tap opens dictation.
 */
class BubbleService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var bubble: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        startForeground(NOTIF_ID, buildNotification())
        wm = getSystemService(WindowManager::class.java)

        val size = (60 * resources.displayMetrics.density).toInt()
        params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 0
            y = (220 * resources.displayMetrics.density).toInt()
        }

        val button = ImageButton(this).apply {
            setImageResource(R.drawable.ic_mic)
            // ic_mic ships a flat white fill, because it doubles as the QS tile
            // icon where the platform requires solid white. Tint it to the
            // signature accent so the bubble carries Dicta's identity over an
            // arbitrary wallpaper. bubble_bg.xml documents why the pressed
            // state lifts instead of inverting to an accent fill, which this
            // tint would disappear into.
            imageTintList = android.content.res.ColorStateList
                .valueOf(getColor(R.color.dicta_accent))
            background = getDrawable(R.drawable.bubble_bg)
            contentDescription = getString(R.string.dictate)
            setOnClickListener { openDictation() }
            setOnTouchListener(DragListener())
        }
        wm.addView(button, params)
        bubble = button
        OverlayVisibility.register(visibilitySink)
    }

    /**
     * The bubble is an always-on-top overlay, so MediaProjection composites it
     * into any screenshot. [DictateActivity] hides it for the duration of a
     * capture through [OverlayVisibility].
     */
    private val visibilitySink: (Boolean) -> Unit = { visible ->
        bubble?.post {
            val view = bubble
            if (view != null) {
                view.visibility = if (visible) View.VISIBLE else View.INVISIBLE
                // Belt and braces: INVISIBLE already makes the view draw
                // nothing into a TRANSLUCENT window, but zeroing the window
                // alpha removes it from composition outright.
                params.alpha = if (visible) 1f else 0f
                runCatching { wm.updateViewLayout(view, params) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        OverlayVisibility.unregister(visibilitySink)
        bubble?.let {
            try {
                wm.removeView(it)
            } catch (_: Throwable) {
            }
        }
        bubble = null
        super.onDestroy()
    }

    private fun openDictation() {
        // 🔴 Via DictateLaunch, never a hand-built Intent: the overlay must
        // land in its OWN task. Built here with NEW_TASK alone it joined
        // MainActivity's task, and because the overlay theme is translucent the
        // card rendered over the whole app UI rather than over the screen the
        // user was actually on (issue #11).
        startActivity(DictateLaunch.intent(this))
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.bubble_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val stopIntent = Intent(this, BubbleService::class.java)
            .setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_mic)
            // The one styling hook a platform notification offers: the shade
            // tints the small icon and the app name with this. Without it
            // Dicta's notifications render in the system's default accent and
            // are the one surface that does not look like the app.
            .setColor(getColor(R.color.dicta_accent))
            .setColorized(false)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.bubble_active))
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.hide_bubble),
                    stopPi
                ).build()
            )
            .build()
    }

    /** Distinguishes a tap (open dictation) from a drag (reposition). */
    private inner class DragListener : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var downX = 0
        private var downY = 0
        private var moved = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val slop = (12 * v.resources.displayMetrics.density).toInt()
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downX = params.x
                    downY = params.y
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) + abs(dy) > slop) {
                        moved = true
                        params.x = downX - dx // END gravity: x grows leftward
                        params.y = downY + dy
                        wm.updateViewLayout(bubble, params)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) v.performClick()
                    return true
                }
            }
            return false
        }
    }

    companion object {
        const val ACTION_STOP = "dev.zacx.dicta.STOP_BUBBLE"
        private const val NOTIF_ID = 2001
        private const val CHANNEL_ID = "dicta_service"
    }
}
