package dev.zacx.dicta

import android.content.Context
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
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
 * there is no replacement rationale. **Do not re-add a refusal case here**: the
 * receiver now contains no notifying code for one to assert against, so such a
 * test could only assert zero and would pass vacuously.
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

    @Test
    fun anUnrelatedBroadcastIsIgnored() {
        // A receiver is reachable by an explicit intent whatever its filter
        // says, so the action is checked in code too.
        ShadowSettings.setCanDrawOverlays(true)
        setFlag(true)

        boot(action = Intent.ACTION_MY_PACKAGE_REPLACED)

        assertNull(
            "the receiver acted on a broadcast that is not BOOT_COMPLETED",
            startedService(),
        )
    }
}
