package dev.zacx.dicta

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Brings the floating bubble back after a reboot.
 *
 * A user-toggled overlay that silently disappears on every restart is a
 * half-working feature: before this, the only way back was to open the app and
 * toggle the switch — the same complaint as issue #14's notification path, in a
 * different shape.
 *
 * ## 🔴 Whether a boot start is even allowed — measured, not assumed
 *
 * [BubbleService] declares `microphone|specialUse`, and the documented platform
 * rules are type-specific, not service-specific:
 *
 *  * Android 12+ refuses background foreground-service starts, **but
 *    `ACTION_BOOT_COMPLETED` is on the documented exemption list** — as is
 *    holding `SYSTEM_ALERT_WINDOW`, which this app does and which is the very
 *    permission checked below.
 *  * A `microphone`-typed start from a `BOOT_COMPLETED` receiver is refused
 *    from Android 14 on. This does not apply to the start made here: the
 *    service promotes to `microphone` only while *recording* and uses
 *    `specialUse` at rest (`BubbleService.platformPromoteForeground`), and a
 *    boot restore is at rest. `specialUse` is not on the disallowed list.
 *  * The wider Android 15 version of that restriction is gated on
 *    `targetSdk >= 35`; this app targets 34 (`app/build.gradle.kts`), so it
 *    does not apply at all today. 🔴 It *will* the day targetSdk moves — and
 *    `specialUse` is still not on the list even then.
 *
 * So the start is expected to be permitted. "Expected" is as far as a JVM test
 * can take it, which is why the refusal is handled rather than assumed away:
 * [BubbleEnabled.startService] catches it and this receiver offers the bubble
 * back through a low-importance notification instead of leaving the user with
 * nothing. That is also the path a future platform change lands on, rather than
 * a crash in a boot receiver.
 *
 * The flag is **not** cleared on a refusal here, and that asymmetry with
 * [BubbleEnabled.turnOn] is deliberate: the user did ask for the bubble and
 * nothing they did has changed that, so their stored intent stands and the next
 * boot will try again.
 */
class BubbleBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        // Explicitly, rather than trusting the manifest filter: a receiver is
        // also reachable by an explicit intent, and only this action means the
        // device has just booted.
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!BubbleEnabled.isOn(context)) return
        // The permission survives reboots but not a user who revoked it, and an
        // overlay service with no overlay permission stops itself immediately.
        if (!Settings.canDrawOverlays(context)) return
        if (!BubbleEnabled.startService(context)) {
            offerRestore(context)
        }
    }

    /**
     * The fallback when the platform refused the boot start.
     *
     * A notification action's `PendingIntent` is itself on the exemption list
     * for background foreground-service starts, so the tap has a route the boot
     * broadcast did not. It targets [BubbleService] directly — that service is
     * not exported, so nothing outside the app can send it.
     */
    private fun offerRestore(context: Context) {
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.boot_restore_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
            val pi = PendingIntent.getForegroundService(
                context,
                0,
                Intent(context, BubbleService::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            nm.notify(
                NOTIF_ID,
                Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_tile_mic)
                    .setColor(context.getColor(R.color.dicta_accent))
                    .setColorized(false)
                    .setContentTitle(context.getString(R.string.app_name))
                    .setContentText(context.getString(R.string.boot_restore_text))
                    .setContentIntent(pi)
                    .addAction(
                        Notification.Action.Builder(
                            null,
                            context.getString(R.string.boot_restore_action),
                            pi,
                        ).build()
                    )
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    internal companion object {
        const val CHANNEL_ID = "dicta_boot_restore"
        const val NOTIF_ID = 3002
    }
}
