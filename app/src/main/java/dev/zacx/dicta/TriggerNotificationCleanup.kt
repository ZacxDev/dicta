package dev.zacx.dicta

import android.app.NotificationManager
import android.content.Context

/**
 * Removes the **persistent trigger notification** that builds up to and
 * including the one before this change left behind.
 *
 * ## The upgrade hazard, concretely
 *
 * `MainActivity` used to offer a "Notification" trigger switch. With it ON it
 * posted notification id [NOTIF_ID] on channel [CHANNEL_ID] with
 * `setOngoing(true)` — a notification the user cannot swipe away — carrying a
 * *Dictate* action whose `PendingIntent` opened the overlay and started a
 * recording. That whole surface is deleted: the floating bubble is the only way
 * to start a recording now.
 *
 * Deleting the code is not enough. An `setOngoing(true)` notification outlives
 * the code that posted it: it stays in the shade, un-dismissable, with an
 * action whose `PendingIntent` now resolves to nothing. The user's only escape
 * would be to clear the app's data. So the new build cancels it, and deletes
 * the channel as well — a channel survives a reboot and would otherwise sit in
 * the app's notification settings forever as a category nothing can post to.
 *
 * ## 🔴 Stateless on purpose — no "have I migrated yet" flag
 *
 * The obvious shape for a one-time migration is a pref flag. This has none, and
 * that is the stronger design here:
 *
 *  * Both platform calls are **already** no-ops when their target is absent.
 *    `NotificationManager.cancel` on an id that is not posted and
 *    `deleteNotificationChannel` on a channel that does not exist both return
 *    without doing anything. Idempotence is a property of the operation, not
 *    something this object has to arrange.
 *  * A flag introduces a failure mode statelessness does not have: written
 *    before the cancel actually took effect, it would retire the migration
 *    permanently and leave the notification up for good. There is no way to
 *    observe that and no second chance.
 *  * The cost it would save is two binder calls, on an app upgrade and on an
 *    app open. That is not a cost worth a piece of persistent state.
 *
 * ## Where it is called from — and why those two places
 *
 * [PackageReplacedReceiver] is the primary: `ACTION_MY_PACKAGE_REPLACED` is
 * delivered to the new build immediately after the install that replaced the
 * old one, which is *exactly* the event that creates the hazard, and it needs
 * neither the user to open the app nor the device to reboot.
 *
 * [MainActivity] is the backstop, for a device that does not deliver that
 * broadcast. It is the one place a user who went looking for the vanished
 * switch will land.
 *
 * `BubbleBootReceiver` deliberately does **not** call this. A reboot clears
 * posted notifications anyway, so by the time it runs the notification is gone;
 * the only thing left for it to find would be the channel, which the two call
 * sites above already remove.
 * `TriggerNotificationCleanupTest.exactlyTwoProductionFilesRunTheCleanup` pins
 * the caller set so a third copy cannot appear without a decision.
 */
object TriggerNotificationCleanup {

    /**
     * 🔴 The id and channel the DELETED code used. These are not names a new
     * notification may reuse — they exist only so this object can find what an
     * older build posted, and they must keep those exact literal values or the
     * cleanup silently cancels nothing.
     */
    internal const val NOTIF_ID: Int = 3001
    internal const val CHANNEL_ID: String = "dicta_trigger"

    /**
     * Cancels the stale trigger notification and deletes its channel.
     *
     * Scoped to [NOTIF_ID] and [CHANNEL_ID] and nothing else: `cancelAll()`
     * would take `BubbleService`'s own foreground-service notification with it,
     * and that one carries the *Hide bubble* action — the only way to put the
     * bubble away from outside the app.
     */
    fun run(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        // Never throws for a missing id or channel, but a notification call in a
        // broadcast receiver is not worth a crash on an OEM that disagrees.
        runCatching { nm.cancel(NOTIF_ID) }
        runCatching { nm.deleteNotificationChannel(CHANNEL_ID) }
    }
}
