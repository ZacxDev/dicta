package dev.zacx.dicta

import android.content.Context
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

/**
 * [BubbleBootReceiver]: the bubble comes back after a reboot, and only when it
 * should.
 *
 * 🔴 **What these tests can and cannot establish.** They pin the receiver's
 * decision table — which combination of stored flag, overlay permission and
 * broadcast action starts the service — and nothing more. Whether Android
 * actually *permits* the foreground start from a real `BOOT_COMPLETED`
 * broadcast is a device question and is **not** verified here: Robolectric's
 * `startForegroundService` records the call and never refuses. The documented
 * rules, and why they exempt this path twice over, are set out in the
 * receiver's kdoc.
 *
 * 🔴 Three tests were deleted from this class together with the receiver's
 * refusal fallback: two that injected a throw through the former
 * `BubbleEnabled.serviceStarter` seam in order to reach that fallback, and the
 * happy-path negative control that existed only to give their counts meaning.
 * The fallback was unreachable without the seam, and the condition it handled
 * cannot occur — `BubbleEnabled.startService`'s kdoc carries both reasons, and
 * there is no replacement rationale. **Do not re-add a refusal-NOTIFICATION
 * case here**: the receiver contains no notifying code for one to assert
 * against, so such a test could only assert zero and would pass vacuously.
 *
 * 🔴 That sentence previously read "do not re-add a refusal case here", which
 * was wider than its own reason and would have forbidden the one refusal case
 * this file has to carry:
 * [aRefusedPromotionKeepsBootIntentButClearsAUserStart]. The reason given is
 * about a *notification* the receiver no longer has; it says nothing about the
 * flag, which is real state, lives outside the receiver, and is the thing a
 * refused boot restore was silently destroying. The prohibition is narrowed to
 * what its argument actually supports.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BootRestoreTest {

    private fun app() = RuntimeEnvironment.getApplication()

    private fun setFlag(on: Boolean) = app()
        .getSharedPreferences("dicta", Context.MODE_PRIVATE)
        .edit().putBoolean("bubble_enabled", on).commit()

    private fun boot(action: String = Intent.ACTION_BOOT_COMPLETED) {
        BubbleBootReceiver().onReceive(app(), Intent(action))
    }

    private fun startedService() = shadowOf(app()).nextStartedService

    @Test
    fun theBubbleIsRestoredWhenTheFlagIsOnAndTheOverlayIsStillPermitted() {
        ShadowSettings.setCanDrawOverlays(true)
        setFlag(true)

        boot()

        val started = startedService()
        assertNotNull(
            "boot did not restore the bubble although the user had it on and " +
                "the overlay permission is still granted",
            started,
        )
        assertEquals(
            "boot started something other than BubbleService",
            BubbleService::class.java.name,
            started.component?.className,
        )
        assertTrue(
            "the restore must not disturb the user's stored intent",
            BubbleEnabled.isOn(app()),
        )
    }

    @Test
    fun nothingHappensWhenTheUserHadTheBubbleOff() {
        ShadowSettings.setCanDrawOverlays(true)
        setFlag(false)

        boot()

        assertNull(
            "boot started the bubble for a user who had switched it off",
            startedService(),
        )
    }

    @Test
    fun nothingHappensWhenTheOverlayPermissionWasRevoked() {
        ShadowSettings.setCanDrawOverlays(false)
        setFlag(true)

        boot()

        assertNull(
            "boot started an overlay service with no overlay permission. " +
                "BubbleService stops itself immediately in that state, so the " +
                "user gets a foreground notification that flickers and no bubble.",
            startedService(),
        )
        assertTrue(
            "a revoked permission is not the user changing their mind — the " +
                "flag must survive so the bubble returns once it is re-granted",
            BubbleEnabled.isOn(app()),
        )
    }

    /**
     * 🔴 An app update must stop the flag claiming a bubble — and must **not**
     * bring the bubble back.
     *
     * Android does not restart a service across a package replacement, so after
     * an update there is no bubble. While the flag survived that, every surface
     * went on reading ON: `MainActivity`'s switch rendered checked, and because
     * a `Switch` fires its listener only on a **change** the first tap did
     * nothing and the user had to toggle OFF then ON. That is issue #14's
     * complaint reached without any reboot.
     *
     * 🔴 **The `assertNull` here is the load-bearing half and it is a negative
     * control on a decision, not a leftover.** Restoring the overlay as a side
     * effect of installing a new version is deliberately not done; this action
     * is a *correction* path, not a second restore path. Both halves are
     * asserted together because a fix that started the service here would still
     * make the flag honest and would pass a flag-only assertion.
     *
     * This test used to be `anUnrelatedBroadcastIsIgnored`, which asserted only
     * the null. The name became false the moment the receiver started acting on
     * this action — [anActionTheReceiverKnowsNothingAboutIsIgnored] is the
     * genuinely-unrelated control that replaces it.
     */
    @Test
    fun aPackageReplacementClearsTheStaleFlagAndStartsNothing() {
        ShadowSettings.setCanDrawOverlays(true)
        setFlag(true)

        boot(action = Intent.ACTION_MY_PACKAGE_REPLACED)

        assertNull(
            "the receiver restored the bubble after an app update. That is a " +
                "deliberate non-behaviour: re-raising an overlay as a side " +
                "effect of an install is not something to ship. It must only " +
                "clear the flag so ONE tap brings the bubble back.",
            startedService(),
        )
        assertFalse(
            "an app update left bubble_enabled set with no service running. " +
                "MainActivity's switch renders from this, a Switch fires only " +
                "on a CHANGE, so the user's first tap does nothing and they " +
                "have to toggle OFF then ON — issue #14 verbatim.",
            BubbleEnabled.isOn(app()),
        )
    }

    /**
     * 🔴 **The seam nobody owned: a refused foreground promotion reached
     * through THIS receiver must not take the user's stored intent with it.**
     *
     * ## The defect
     *
     * `BubbleService.onCreate` cleared `bubble_enabled` whenever
     * `promoteForeground` returned `false`, and `platformPromoteForeground`
     * turns **any** throw out of `buildNotification`/`startForeground` into
     * that `false`. So: device boots → this receiver restores the bubble → the
     * platform refuses the promotion → the flag is gone. The bubble then does
     * not come back on that boot *or any later one*, and the only route back is
     * rediscovering the switch in the app — which contradicts this receiver's
     * own kdoc ("a reboot must not be able to touch the user's stored intent in
     * either direction"), `BubbleEnabled.isOn`'s, and the README's.
     *
     * ## Why no existing test could see it
     *
     * It needs **two** surfaces in one fixture, and every test loaded one. This
     * file drove the receiver and never touched
     * `BubbleService.foregroundPromoter`; `BubbleServiceFlowTest` and
     * `BubbleEnabledWritersSeamTest` drive that seam but build `BubbleService`
     * by hand, so the receiver — the thing that makes the start a *restore* —
     * was never in the picture. The combined state existed only on a device.
     *
     * ## What it pins, and what it cannot
     *
     * It pins a **relationship, not one side**: the same refusal must leave the
     * flag intact when the receiver asked for the start and must clear it when
     * `turnOn` did. Either half alone passes for a mutant that hard-codes the
     * other answer.
     *
     * 🔴 **Cannot observe**: whether Android ever refuses the promotion on a
     * real boot — Robolectric's `startForegroundService` records the call and
     * never refuses, and no device has been attached to this project. Nor does
     * it prove that the service instance the *platform* would create is the one
     * the recorded origin belongs to: this builds the service itself rather
     * than letting the platform create it from the recorded start request. What
     * it does establish is that the origin the receiver records survives to the
     * refusal and decides it, in both directions, with the overlay permission
     * granted so nothing masks the flag.
     *
     * ⚠ This is **not** the refusal case this class's kdoc forbids re-adding.
     * That prohibition is about a *notification* fallback inside the receiver:
     * the receiver contains no notifying code, so such a test could only assert
     * zero. This asserts the flag — real state that two surfaces disagreed
     * about — and the receiver is the only way to establish the boot origin.
     */
    @Test
    fun aRefusedPromotionKeepsBootIntentButClearsAUserStart() {
        ShadowSettings.setCanDrawOverlays(true)

        // --- the boot half ---------------------------------------------------
        setFlag(true)
        boot()
        assertNotNull(
            "the fixture failed: boot did not ask for the service, so no " +
                "boot-origin start was ever recorded and the assertion below " +
                "would be about the fail-safe default instead",
            startedService(),
        )
        refuseTheForegroundPromotion()
        assertTrue(
            "a boot restore whose foreground promotion the platform refused " +
                "CLEARED bubble_enabled. That destroys the user's stored " +
                "intent: the flag is what brings the bubble back, so it does " +
                "not return on this boot or any later one and the user has to " +
                "rediscover the switch in the app. A reboot must not be able " +
                "to touch stored intent in either direction — see this " +
                "receiver's kdoc and BubbleEnabled.foregroundPromotionRefused.",
            BubbleEnabled.isOn(app()),
        )

        // --- the user half, same refusal -------------------------------------
        setFlag(false)
        assertEquals(
            "the fixture failed: turnOn must report STARTED, or the user path " +
                "was never taken and the assertion below is vacuous",
            BubbleEnabled.Start.STARTED,
            BubbleEnabled.turnOn(app()),
        )
        assertTrue(
            "the fixture failed: turnOn must have written the flag",
            BubbleEnabled.isOn(app()),
        )
        refuseTheForegroundPromotion()
        assertFalse(
            "a start the USER just asked for was refused and left " +
                "bubble_enabled set. Nothing masks it — the overlay permission " +
                "is granted — so MainActivity's switch renders ON with no " +
                "bubble, and a Switch fires its listener only on a CHANGE, so " +
                "one tap does nothing: issue #14 verbatim. Preserving the flag " +
                "is correct ONLY for a start the user did not make.",
            BubbleEnabled.isOn(app()),
        )
    }

    /**
     * Brings up the real [BubbleService] with its foreground promotion refused,
     * through the `foregroundPromoter` seam — Robolectric's `startForeground`
     * cannot be made to refuse. Deliberately does **not** reset
     * [BubbleEnabled]: the origin recorded by whoever asked for the start is
     * the input under test.
     */
    private fun refuseTheForegroundPromotion() {
        val c = Robolectric.buildService(BubbleService::class.java)
        c.get().foregroundPromoter = { false }
        runCatching { c.create() }
        runCatching { c.destroy() }
        OverlayVisibility.clearForTest()
        BubbleSessionBus.clearForTest()
        MicrophoneOwner.clearForTest()
    }

    @Test
    fun anActionTheReceiverKnowsNothingAboutIsIgnored() {
        // A receiver is reachable by an explicit intent whatever its filter
        // says, so the action is checked in code too. Uses an action the
        // receiver has no branch for, which is what MY_PACKAGE_REPLACED used to
        // be before it acquired one.
        ShadowSettings.setCanDrawOverlays(true)
        setFlag(true)

        boot(action = Intent.ACTION_SCREEN_ON)

        assertNull(
            "the receiver acted on a broadcast it has no branch for",
            startedService(),
        )
        assertTrue(
            "the receiver wrote the flag for a broadcast it has no branch for, " +
                "so some fall-through is reaching a mutator",
            BubbleEnabled.isOn(app()),
        )
    }
}
