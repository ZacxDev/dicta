package dev.zacx.dicta

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Brings the floating bubble back after a reboot — and, on an app update,
 * stops the flag claiming a bubble that is gone.
 *
 * A user-toggled overlay that silently disappears on every restart is a
 * half-working feature: before this, the only way back was to open the app and
 * toggle the switch — the same complaint as issue #14's notification path, in a
 * different shape.
 *
 * ## 🔴 Two actions, deliberately asymmetric
 *
 * `BOOT_COMPLETED` **restores** the bubble and never writes the flag.
 * `MY_PACKAGE_REPLACED` **clears** the flag and starts nothing. The asymmetry
 * is a decision, not an unfinished half:
 *
 *  * An app update stops the service and Android does not restart one across a
 *    package replacement, so without this branch the flag outlives the bubble.
 *    Nothing is running, yet `MainActivity`'s switch renders ON — and a `Switch`
 *    fires its listener only on a **change**, so the first tap does nothing and
 *    the user has to toggle OFF then ON. Issue #14 verbatim, no reboot
 *    involved.
 *  * Re-raising an overlay as a side effect of installing a new version is not
 *    behaviour to ship, so the bubble is **not** restored. The only claim being
 *    fixed is that the surfaces stop lying, so that ONE tap brings it back.
 *
 * `BootRestoreTest.aPackageReplacementClearsTheStaleFlagAndStartsNothing`
 * asserts both halves together, because a fix that *did* start the service
 * would also make the flag honest and would pass a flag-only assertion.
 *
 * ## 🔴 Whether a boot start is even allowed
 *
 * [BubbleService] declares `microphone|specialUse`, and the documented platform
 * rules are type-specific, not service-specific:
 *
 *  * Android 12+ refuses background foreground-service starts, **but
 *    `ACTION_BOOT_COMPLETED` is on the documented exemption list** — as is
 *    holding `SYSTEM_ALERT_WINDOW`, which this app does and which is the very
 *    permission checked below. This path is therefore exempt twice over.
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
 * ## 🔴 There is no refusal fallback, and that is not an omission
 *
 * An earlier version of this receiver posted a low-importance "the bubble could
 * not start after the restart" notification when [BubbleEnabled.startService]
 * reported a refusal. **It was deleted, because the condition it handled cannot
 * occur and the signal it read could not carry it** — and it has no replacement
 * rationale. Two independent reasons, either one sufficient:
 *
 *  1. The only refusal `startForegroundService` itself raises is the Android
 *     12+ background-start restriction, which the two bullets above exempt
 *     twice on exactly this path.
 *  2. The FGS-*type* refusal the bullets above discuss is thrown out of
 *     `Service.startForeground`, not `startForegroundService` — in the
 *     service's own process, after the call here has already returned.
 *     `BubbleService.platformPromoteForeground` catches it there and answers it
 *     with `stopSelf()`. Nothing about it was ever observable from here, so the
 *     fallback could not have fired even on a device that refused the start.
 *
 * 🔴 That second bullet is also where this file's "a reboot must not touch the
 * user's stored intent" policy nearly came undone, and it is worth knowing
 * about here because the site that broke it is not in this file. The refused
 * promotion is answered in the service's process, and for a while it cleared
 * the flag there unconditionally — so a boot restore the platform refused
 * destroyed the stored intent, and the bubble then did not return on that boot
 * or any later one. [BubbleEnabled.startService] now carries the origin of the
 * start it was asked to make and `BubbleEnabled.foregroundPromotionRefused`
 * clears the flag only for a start the user made, which is what keeps the
 * policy true end to end rather than only as far as this file's own two
 * checks. `BootRestoreTest` drives both directions through this receiver.
 *
 * The handler could only ever be reached by a test injecting a throw, which is
 * what the deleted `BubbleEnabled.serviceStarter` seam existed to do. 🔴 If a
 * future platform change really does start refusing this, the place that learns
 * about it is `platformPromoteForeground` — fix it there and let it report
 * outward; do not restore a branch here that cannot see it.
 *
 * ## Why this routes around [BubbleEnabled.turnOn]
 *
 * The **boot** path calls [BubbleEnabled.isOn] and [BubbleEnabled.startService]
 * and open-codes the `canDrawOverlays` check rather than calling
 * [BubbleEnabled.turnOn]. That is deliberate and is **not** a duplicated
 * predicate to consolidate: `turnOn` *writes* the flag, and a reboot must not
 * be able to touch the user's stored intent in either direction. A revoked
 * overlay permission in particular is not the user changing their mind, so the
 * flag survives it and the bubble returns once the permission is re-granted.
 *
 * 🔴 That argument is about the **boot** path only, and this kdoc used to state
 * it as though it covered the whole receiver ("must not write the flag at
 * all"). The update path above writes it on purpose: there, the user's stored
 * intent has already been overtaken by reality — the service is gone and will
 * not come back on its own — so leaving the flag alone is what makes the app
 * lie. Note it reads [BubbleEnabled.isOn] and never `isUp`: this file acts on
 * stored intent and displays nothing, which is why
 * `BubbleEnabledWritersSeamTest.everyDisplayedBubbleStateComesFromOnePredicate`
 * lists it as the one legitimate `isOn` reader.
 */
class BubbleBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        // 🔴 An app update stops the service and Android does NOT restart one
        // across a package replacement, so without this the flag outlives the
        // bubble: nothing is running, MainActivity's switch renders ON, and
        // because a Switch fires its listener only on a CHANGE the first tap
        // does nothing — the user has to toggle OFF then ON. That is issue #14
        // verbatim, reached by a route no reboot is involved in.
        //
        // 🔴 It clears the flag and starts NOTHING, and that is a decision, not
        // an unfinished restore: an update is not a reboot, and silently
        // re-raising an overlay as a side effect of installing a new version is
        // not behaviour to ship. The point is only that every surface stops
        // claiming a bubble that is gone, so ONE tap brings it back.
        // BootRestoreTest keeps ACTION_MY_PACKAGE_REPLACED as a negative
        // control for the start, which still holds.
        if (intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            BubbleEnabled.set(context, false)
            return
        }
        // Explicitly, rather than trusting the manifest filter: a receiver is
        // also reachable by an explicit intent, and only this action means the
        // device has just booted.
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!BubbleEnabled.isOn(context)) return
        // The permission survives reboots but not a user who revoked it, and an
        // overlay service with no overlay permission stops itself immediately.
        if (!Settings.canDrawOverlays(context)) return
        // 🔴 userAsked = false is load-bearing, not boilerplate: it is what
        // stops a refused promotion in the service's process clearing the flag
        // and taking the bubble away for good. Nothing the user did at boot
        // can have changed their stored intent, including a platform refusal.
        BubbleEnabled.startService(context, userAsked = false)
    }
}
