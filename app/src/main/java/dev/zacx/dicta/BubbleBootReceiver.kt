package dev.zacx.dicta

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Brings the floating bubble back after a reboot **or an app upgrade**.
 *
 * A user-toggled overlay that silently disappears on every restart is a
 * half-working feature: before this, the only way back was to open the app and
 * toggle the switch — the same complaint as issue #14's notification path, in a
 * different shape.
 *
 * ## 🔴 Two actions, ONE rule — and that is a correction
 *
 * `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` are now handled identically:
 * restore the bubble if the user wants it and the overlay permission allows it,
 * and **never write the flag**. Both events are "something other than the user
 * took the bubble away", which is the only question this receiver answers.
 *
 * 🔴 **This file used to treat them asymmetrically, and that asymmetry was the
 * defect.** `MY_PACKAGE_REPLACED` had its own branch which called
 * `BubbleEnabled.set(context, false)` and started nothing. Measured on real
 * hardware: after every `adb install -r`, `bubble_enabled` in
 * `shared_prefs/dicta.xml` read `false` and the bubble did not come back until
 * the switch was found and re-toggled. The branch's own rationale said the flag
 * must stop "claiming a bubble that is gone" — a real gap — and then closed it
 * by **deleting the claim instead of restoring the bubble**, which destroys the
 * one piece of state that is nobody's to destroy but the user's.
 *
 * The objection on the record was that "re-raising an overlay as a side effect
 * of installing a new version is not behaviour to ship". It does not hold: the
 * overlay was **already up** when the install began, so this restores what the
 * install removed. That is the boot path's own argument, on an event which
 * differs from a reboot only in what stopped the service.
 *
 * `BootRestoreTest.anAppUpgradeKeepsStoredIntentAndBringsTheBubbleBack` is the
 * regression test, and it asserts both halves — the flag survives *and* the
 * service is asked for — because a mutant that only stopped clearing the flag
 * would leave the upgrade with no bubble and still pass a flag-only assertion.
 *
 * ## 🔴 Whether the start is even allowed
 *
 * [BubbleService] declares `microphone|specialUse`, and the documented platform
 * rules are type-specific, not service-specific:
 *
 *  * Android 12+ refuses background foreground-service starts, **but both of
 *    this receiver's actions are on the documented exemption list, in the same
 *    bullet**: "after the device reboots and receives the
 *    `ACTION_BOOT_COMPLETED`, `ACTION_LOCKED_BOOT_COMPLETED`, or
 *    `ACTION_MY_PACKAGE_REPLACED` intent action in a broadcast receiver"
 *    (`developer.android.com/develop/background-work/services/fgs/restrictions-bg-start`).
 *    So the two paths are exempt on identical terms, which is one more reason
 *    they are one rule.
 *
 *    🔴 **CORRECTION.** This section used to claim `MY_PACKAGE_REPLACED` is
 *    **not** on that list, that the upgrade path was "exempt once" via
 *    `SYSTEM_ALERT_WINDOW`, and that this gave the `canDrawOverlays` check
 *    below a second, independent reason to exist. **All three are false.** The
 *    asserted asymmetry with boot does not exist, and the inversion is worth
 *    recording: from **Android 15** the `SYSTEM_ALERT_WINDOW` exemption
 *    additionally requires the app to *currently have a visible overlay
 *    window* — and across a package replacement it does not, because the
 *    install stopped the service that was drawing it. The route the old text
 *    called "the whole of why this start is legal" is therefore the one that
 *    may not apply, and the broadcast action it dismissed is the one that
 *    does. `canDrawOverlays` keeps its **first** reason, which is the real one
 *    and is unchanged: an overlay service with no overlay permission stops
 *    itself immediately, so starting it would give the user a flickering
 *    foreground notification and no bubble.
 *
 *    Like everything else in this section it is read off the documented
 *    contracts: **no device has been attached to this project**, Robolectric's
 *    `startForegroundService` records the call and never refuses, and if a
 *    platform does refuse it, `BubbleEnabled.foregroundPromotionRefused`
 *    deliberately keeps the flag — so the failure mode is "no bubble until the
 *    next start", never "the user's intent is gone".
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
 *     12+ background-start restriction, and the first bullet above exempts
 *     both of this receiver's actions from it by the broadcast action alone.
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
 * This receiver calls [BubbleEnabled.isOn] and [BubbleEnabled.startService] and
 * open-codes the `canDrawOverlays` check rather than calling
 * [BubbleEnabled.turnOn]. That is deliberate and is **not** a duplicated
 * predicate to consolidate: `turnOn` *writes* the flag, and neither a reboot
 * nor an install may touch the user's stored intent in either direction. A
 * revoked overlay permission in particular is not the user changing their mind,
 * so the flag survives it and the bubble returns once the permission is
 * re-granted.
 *
 * 🔴 This kdoc previously said that argument covered "the boot path only",
 * because the upgrade branch wrote the flag on purpose. That branch is gone and
 * the argument is whole again — it is the receiver's rule, not one of two.
 * Note it reads [BubbleEnabled.isOn] and never `isUp`: this file acts on
 * stored intent and displays nothing, which is why
 * `BubbleEnabledWritersSeamTest.everyDisplayedBubbleStateComesFromOnePredicate`
 * lists it as the one legitimate `isOn` reader.
 */
class BubbleBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        // Explicitly, rather than trusting the manifest filter: a receiver is
        // also reachable by an explicit intent, and these are the only two
        // actions that mean the bubble was taken away by something other than
        // the user.
        //
        // 🔴 MY_PACKAGE_REPLACED used to have its OWN branch here, which called
        // BubbleEnabled.set(context, false) and started nothing. That write is
        // the measured defect: `adb install -r` of a new build silently turned
        // the operator's bubble off, every time, and the only way back was to
        // reopen Settings and re-toggle the switch. The argument it rested on —
        // "the flag would otherwise outlive the bubble and MainActivity's
        // switch would lie" — identified a real gap and then closed it from the
        // wrong end: deleting the user's stored intent makes the surfaces agree
        // by throwing away the thing they are supposed to be reporting.
        // Restarting the bubble makes them agree AND keeps it.
        //
        // 🔴 The counter-argument on the record was "re-raising an overlay as a
        // side effect of installing a new version is not behaviour to ship".
        // It does not survive the measurement: the overlay was already up when
        // the install began, so this restores what the install removed rather
        // than raising something the user did not ask for — which is exactly
        // the boot path's own justification, on an event that differs from a
        // reboot only in what interrupted the service.
        val action = intent?.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        if (!BubbleEnabled.isOn(context)) return
        // The permission survives reboots but not a user who revoked it, and an
        // overlay service with no overlay permission stops itself immediately.
        if (!Settings.canDrawOverlays(context)) return
        // 🔴 userAsked = false is load-bearing, not boilerplate: it is what
        // stops a refused promotion in the service's process clearing the flag
        // and taking the bubble away for good. Nothing the user did at a boot
        // or at an install can have changed their stored intent, including a
        // platform refusal.
        BubbleEnabled.startService(context, userAsked = false)
    }
}
