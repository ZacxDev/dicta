package dev.zacx.dicta

import android.Manifest
import android.content.Context
import android.widget.Switch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * 🔴 The regression for issue #14: **"Hide bubble" from the notification left
 * no way back except toggling the switch off and on again.**
 *
 * ## The mechanism, traced rather than assumed
 *
 * `BubbleService.buildNotification` adds a *Hide bubble* action whose
 * `PendingIntent` carries `ACTION_STOP`; `onStartCommand` handled it with a bare
 * `stopSelf()` and **never touched the persisted flag**. `MainActivity` derives
 * the switch from that flag, so after the hide the switch rendered `checked`
 * with no service running — and `setOnCheckedChangeListener` fires only on a
 * *change*, so assigning `true` to an already-`true` switch is a no-op. The
 * user's only route back was off, then on.
 *
 * ## Why this test is written against the raw SharedPreferences
 *
 * It reads `"dicta"` / `"bubble_enabled"` directly instead of through
 * [BubbleEnabled], for two reasons:
 *
 *  1. **It has to compile on the pre-change tree.** `BubbleEnabled` does not
 *     exist at `475118f`, so a test that referenced it could not have been
 *     watched red there, and "watched red" would be a claim about an API rather
 *     than about the behaviour.
 *  2. It pins the **file and key** as a compatibility contract. Existing
 *     installs already hold `bubble_enabled` in the `dicta` prefs; an owner that
 *     "tidied" either name would silently reset every user's bubble to off.
 *
 * Red/green matrix: **red at `475118f`** (the flag stayed `true`, and the single
 * `isChecked = true` started nothing), green at `HEAD`.
 *
 * The hide action is not synthesised here either: the intent delivered to
 * `onStartCommand` is extracted from the `PendingIntent` of the notification the
 * service actually handed to `startForeground`, so the test exercises the
 * operator's real click path rather than a hardcoded `ACTION_STOP` string that
 * could drift from it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BubbleHideFromNotificationTest {

    private var controller: org.robolectric.android.controller.ServiceController<BubbleService>? =
        null

    @After
    fun tearDown() {
        runCatching { controller?.destroy() }
        OverlayVisibility.clearForTest()
    }

    private fun prefs() = RuntimeEnvironment.getApplication()
        .getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    private fun flag(): Boolean = prefs().getBoolean(PREF_KEY, false)

    private fun startServiceWithTheBubbleEnabled(): BubbleService {
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
        // The state the operator is in: they turned the bubble on in the app.
        prefs().edit().putBoolean(PREF_KEY, true).commit()
        val c = Robolectric.buildService(BubbleService::class.java)
        controller = c
        return c.create().get()
    }

    /**
     * Posts the hide action's own intent back to the service, exactly as the
     * platform would when the notification button is tapped.
     */
    private fun tapHideBubble(service: BubbleService) {
        val posted = requireNotNull(shadowOf(service).lastForegroundNotification) {
            "the service posted no foreground notification, so there was no " +
                "Hide bubble button to tap — every assertion below would be vacuous"
        }
        val hideLabel = RuntimeEnvironment.getApplication().getString(R.string.hide_bubble)
        val action = posted.actions.orEmpty().firstOrNull { it.title == hideLabel }
        assertNotNull(
            "the foreground notification carried no action titled " +
                "\"$hideLabel\"; the actions present were " +
                posted.actions.orEmpty().map { it.title },
            action,
        )
        val saved = shadowOf(action!!.actionIntent).savedIntent
        assertNotNull("the Hide bubble PendingIntent wrapped no intent", saved)
        service.onStartCommand(saved, 0, 1)
    }

    @Test
    fun theServiceReallyPostsAHideActionWiredToItself() {
        // 🔴 Positive control. Everything below rests on there BEING a hide
        // action that reaches this service; if the notification carried none,
        // or it targeted some other component, the assertions after it would
        // pass or fail for reasons unrelated to the flag.
        val service = startServiceWithTheBubbleEnabled()
        val posted = requireNotNull(shadowOf(service).lastForegroundNotification) {
            "the service posted no foreground notification at all"
        }
        val hideLabel = RuntimeEnvironment.getApplication().getString(R.string.hide_bubble)
        val action = posted.actions.orEmpty().firstOrNull { it.title == hideLabel }
        assertNotNull("no \"$hideLabel\" action on the foreground notification", action)
        val saved = shadowOf(action!!.actionIntent).savedIntent
        assertEquals(
            "the hide action must be delivered to BubbleService itself",
            BubbleService::class.java.name,
            saved.component?.className,
        )
        assertTrue(
            "the hide action's intent carried no action, so onStartCommand " +
                "cannot distinguish it from a plain start",
            !saved.action.isNullOrEmpty(),
        )
    }

    @Test
    fun hidingFromTheNotificationClearsThePersistedFlag() {
        val service = startServiceWithTheBubbleEnabled()
        assertTrue("the fixture failed to enable the bubble", flag())

        tapHideBubble(service)

        assertFalse(
            "\"Hide bubble\" stopped the service but left bubble_enabled=true. " +
                "MainActivity derives its switch from that flag, so the switch " +
                "reads ON with nothing running — and because a Switch only " +
                "fires its listener on a CHANGE, tapping it does nothing and " +
                "the operator has to toggle OFF then ON to get the bubble back. " +
                "The stop path must clear the flag through BubbleEnabled.",
            flag(),
        )
    }

    @Test
    fun afterHidingASingleTapOfTheSwitchBringsTheBubbleBack() {
        val service = startServiceWithTheBubbleEnabled()
        tapHideBubble(service)
        runCatching { controller?.destroy() }
        controller = null

        // Now the operator opens the app and taps the switch ONCE.
        val activityController = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = activityController.get()
        val switch = activity.findViewById<Switch>(R.id.bubble_switch)
        assertTrue(
            "the bubble switch was disabled, so this test cannot tap it — " +
                "the overlay permission fixture is wrong, not the app",
            switch.isEnabled,
        )
        assertFalse(
            "the switch rendered ON after the notification hid the bubble. " +
                "That is the bug: it disagrees with reality, and assigning " +
                "true to an already-true Switch fires no listener.",
            switch.isChecked,
        )

        switch.isChecked = true

        val started = shadowOf(activity).nextStartedService
        assertNotNull(
            "one tap of the switch after \"Hide bubble\" started no service, " +
                "so the bubble cannot come back without an off-then-on toggle",
            started,
        )
        assertEquals(
            "the switch started something other than BubbleService",
            BubbleService::class.java.name,
            started.component?.className,
        )
        assertTrue(
            "the switch restarted the service but left bubble_enabled=false, " +
                "so the next launch of the app would show the switch OFF again",
            flag(),
        )
        activityController.pause().stop().destroy()
    }

    private companion object {
        /**
         * Spelled out rather than taken from the production constant on
         * purpose — see this class's kdoc. These two strings are a
         * compatibility contract with every installed copy of the app.
         */
        const val PREF_FILE = "dicta"
        const val PREF_KEY = "bubble_enabled"
    }
}
