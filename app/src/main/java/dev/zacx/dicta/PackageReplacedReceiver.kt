package dev.zacx.dicta

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Runs [TriggerNotificationCleanup] on the first start of a newly-installed
 * build.
 *
 * `ACTION_MY_PACKAGE_REPLACED` is delivered to the *new* package right after an
 * install that replaced an existing one — the precise moment the stale trigger
 * notification described in [TriggerNotificationCleanup] becomes orphaned. It
 * is also the only entry point that requires nothing of the user: the
 * notification is un-dismissable, so waiting for them to open the app would
 * leave it in the shade indefinitely, and waiting for a reboot would leave the
 * channel behind.
 *
 * A fresh install does not get this broadcast, which is correct — there is
 * nothing from an older build to clean up.
 *
 * The action is checked explicitly rather than trusted from the manifest
 * filter, for the same reason [BubbleBootReceiver] does: a manifest receiver is
 * also reachable by an explicit intent, and only this action means the package
 * was just replaced.
 */
class PackageReplacedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        TriggerNotificationCleanup.run(context)
    }
}
