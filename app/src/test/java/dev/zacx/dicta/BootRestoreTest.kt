package dev.zacx.dicta

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import org.junit.After
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
 * decision table and its refusal fallback on the JVM. Whether Android actually
 * *permits* the foreground start from a real `BOOT_COMPLETED` broadcast is a
 * device question and is **not** verified here: Robolectric's
 * `startForegroundService` records the call and never refuses, so the happy
 * path below is evidence about the receiver's logic only. The documented rules
 * and why they are expected to permit it are set out in the receiver's kdoc;
 * the refusal path exists because "expected" is not "measured".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BootRestoreTest {

    @After
    fun tearDown() {
        BubbleEnabled.serviceStarter = null
    }

    private fun app() = RuntimeEnvironment.getApplication()

    private fun setFlag(on: Boolean) = app()
        .getSharedPreferences("dicta", Context.MODE_PRIVATE)
        .edit().putBoolean("bubble_enabled", on).commit()

    private fun boot(action: String = Intent.ACTION_BOOT_COMPLETED) {
        BubbleBootReceiver().onReceive(app(), Intent(action))
    }

    private fun startedService() = shadowOf(app()).nextStartedService

    private fun postedNotifications() = shadowOf(
        app().getSystemService(NotificationManager::class.java)
    ).allNotifications

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

    @Test
    fun aRefusedBootStartOffersTheBubbleBackInsteadOfFailingSilently() {
        ShadowSettings.setCanDrawOverlays(true)
        setFlag(true)
        BubbleEnabled.serviceStarter = {
            // Shape of the real refusal: on Android 12+ startForegroundService
            // throws ForegroundServiceStartNotAllowedException, which is an
            // IllegalStateException and is API 31+, so the test raises the
            // supertype rather than pinning itself to a compileSdk constant.
            throw IllegalStateException("ForegroundServiceStartNotAllowedException")
        }

        boot()

        val posted = postedNotifications()
        assertEquals(
            "a refused boot start posted no notification, so the bubble " +
                "silently never came back and the user has no way to know why",
            1,
            posted.size,
        )
        assertEquals(
            "the restore notification must offer exactly one action to take",
            1,
            posted.first().actions.orEmpty().size,
        )
        assertEquals(
            "the restore action's label changed; it is the only text the user " +
                "has to act on",
            app().getString(R.string.boot_restore_action),
            posted.first().actions.orEmpty().first().title,
        )
        assertTrue(
            "the flag must stand after a refusal the user did not cause, or " +
                "the next boot will not even try",
            BubbleEnabled.isOn(app()),
        )
    }

    @Test
    fun aRefusedBootStartDoesNotCrashTheReceiver() {
        // A throw out of onReceive is "Dicta stopped" on a fresh boot.
        ShadowSettings.setCanDrawOverlays(true)
        setFlag(true)
        BubbleEnabled.serviceStarter = { throw SecurityException("denied") }

        boot()

        assertEquals(
            "the receiver swallowed the refusal without telling the user",
            1,
            postedNotifications().size,
        )
    }

    @Test
    fun theHappyPathPostsNoNotification() {
        // Negative control for the two tests above: if the fallback fired on
        // every boot, their count of 1 would say nothing about a refusal.
        ShadowSettings.setCanDrawOverlays(true)
        setFlag(true)

        boot()

        assertEquals(
            "a successful boot restore posted a restore notification, so the " +
                "refusal assertions above cannot distinguish success from failure",
            0,
            postedNotifications().size,
        )
    }
}
