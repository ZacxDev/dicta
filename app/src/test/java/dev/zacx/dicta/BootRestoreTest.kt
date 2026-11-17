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
     * 🔴 **THE REGRESSION TEST.** An app upgrade must keep the user's stored
     * intent **and** bring the bubble back.
     *
     * ## The measured defect
     *
     * `bubble_enabled` in `shared_prefs/dicta.xml` flipped from `true` to
     * `false` by itself after an `adb install -r`, repeatedly, on real
     * hardware. The writer was this receiver: it had a separate
     * `ACTION_MY_PACKAGE_REPLACED` branch that called
     * `BubbleEnabled.set(context, false)` and started nothing. So every
     * reinstall silently switched the floating bubble off and the operator had
     * to reopen Settings and re-toggle it.
     *
     * ## Why the old behaviour looked defensible
     *
     * Its argument was real: Android does not restart a service across a
     * package replacement, so the flag outlived the bubble, `MainActivity`'s
     * switch rendered ON with nothing running, and a `Switch` fires its
     * listener only on a **change** — so one tap did nothing (issue #14's
     * shape). That gap exists. Clearing the flag closed it **from the wrong
     * end**: it made the surfaces agree by destroying the one piece of state
     * only the user may change. Restoring the bubble makes them agree and keeps
     * it.
     *
     * The predecessor of this test, `aPackageReplacementClearsTheStaleFlagAndStartsNothing`,
     * asserted the defect — a `false` flag and a null start — which is why a
     * fully green suite never saw it.
     *
     * ## 🔴 Both halves, together
     *
     * A mutant that merely deleted the write would leave the upgrade with no
     * bubble, no service and a flag claiming otherwise — and would pass a
     * flag-only assertion. A mutant that started the service while still
     * clearing the flag would pass a start-only one.
     */
    @Test
    fun anAppUpgradeKeepsStoredIntentAndBringsTheBubbleBack() {
        ShadowSettings.setCanDrawOverlays(true)
        setFlag(true)

        boot(action = Intent.ACTION_MY_PACKAGE_REPLACED)

        assertTrue(
            "an app upgrade CLEARED bubble_enabled. That is the measured " +
                "defect: `adb install -r` silently switched the operator's " +
                "bubble off, and the only way back was to find the switch in " +
                "Settings. The flag means \"the user wants the bubble\", never " +
                "\"the service is currently alive\" — an install is not the " +
                "user changing their mind.",
            BubbleEnabled.isOn(app()),
        )
        val started = startedService()
        assertNotNull(
            "an app upgrade left the flag set and started nothing, so there is " +
                "no bubble and every surface claims there is one. Android does " +
                "not restart a service across a package replacement, so this " +
                "receiver is the only thing that can put it back.",
            started,
        )
        assertEquals(
            "the upgrade started something other than BubbleService",
            BubbleService::class.java.name,
            started.component?.className,
        )
    }

    /**
     * The upgrade path obeys the same two conditions as the boot path.
     *
     * 🔴 Not redundant with the boot cases: the fix merged the two actions into
     * one rule, and a mutant that merged them *badly* — restoring on
     * `MY_PACKAGE_REPLACED` unconditionally, with the action check moved but
     * the guards left behind the boot branch — would pass every other test
     * here. The `canDrawOverlays` half carries extra weight on this path: it is
     * also the Android 12+ foreground-service-start exemption this action
     * relies on, since unlike `BOOT_COMPLETED` the action itself is not one.
     */
    @Test
    fun anAppUpgradeStartsNothingWhenTheUserHadItOffOrRevokedTheOverlay() {
        ShadowSettings.setCanDrawOverlays(true)
        setFlag(false)
        boot(action = Intent.ACTION_MY_PACKAGE_REPLACED)
        assertNull(
            "an upgrade raised the bubble for a user who had switched it off",
            startedService(),
        )

        ShadowSettings.setCanDrawOverlays(false)
        setFlag(true)
        boot(action = Intent.ACTION_MY_PACKAGE_REPLACED)
        assertNull(
            "an upgrade started an overlay service with no overlay permission. " +
                "BubbleService stops itself immediately in that state, and the " +
                "start is not exempt from the Android 12+ background " +
                "foreground-service restriction without it.",
            startedService(),
        )
        assertTrue(
            "a revoked permission is not the user changing their mind — the " +
                "flag must survive an upgrade in that state too",
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
