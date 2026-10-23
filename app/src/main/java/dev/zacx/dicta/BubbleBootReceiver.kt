package dev.zacx.dicta

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
 * The handler could only ever be reached by a test injecting a throw, which is
 * what the deleted `BubbleEnabled.serviceStarter` seam existed to do. 🔴 If a
 * future platform change really does start refusing this, the place that learns
 * about it is `platformPromoteForeground` — fix it there and let it report
 * outward; do not restore a branch here that cannot see it.
 *
 * ## Why this routes around [BubbleEnabled.turnOn]
 *
 * It calls [BubbleEnabled.isOn] and [BubbleEnabled.startService] and open-codes
 * the `canDrawOverlays` check rather than calling [BubbleEnabled.turnOn]. That
 * is deliberate and is **not** a duplicated predicate to consolidate: `turnOn`
 * *writes* the flag, and a reboot must not be able to touch the user's stored
 * intent in either direction. A revoked overlay permission in particular is not
 * the user changing their mind, so the flag survives it and the bubble returns
 * once the permission is re-granted.
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
        BubbleEnabled.startService(context)
    }
}
