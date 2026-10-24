package dev.zacx.dicta

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * 🔴 **The upgrade path.** An install that had the trigger notification switched
 * on must not be left with it stuck in the shade.
 *
 * The deleted switch posted notification `3001` on channel `dicta_trigger` with
 * `setOngoing(true)` — un-dismissable — carrying a *Dictate* action whose
 * `PendingIntent` this change makes dead. Deleting the code does not delete the
 * notification: it outlives the build that posted it, and the user's only
 * escape would be clearing the app's data.
 *
 * ## What must be true, and the shape of each claim
 *
 *  * `3001` is cancelled and `dicta_trigger` deleted.
 *  * **`BubbleService`'s own foreground notification is untouched.** It carries
 *    the *Hide bubble* action — the only way to put the bubble away from
 *    outside the app — so a `cancelAll()` here would be a worse bug than the
 *    one being fixed.
 *  * Exactly ONE notification and ONE channel are removed. Asserted as the
 *    surviving SET rather than as a count of calls: a count of one is also what
 *    a `cancelAll()` produces when only one notification happens to be posted,
 *    so the thing that distinguishes them is what is left behind.
 *
 * ## The negative control is load-bearing, not decoration
 *
 * [theCleanupIsScopedToTheDeletedNotificationAndNothingElse] seeds **three**
 * notifications on three channels — the stale one, `BubbleService`'s, and one
 * synthetic unrelated sibling — and asserts the two siblings survive. Without
 * them, "3001 is gone" is satisfied by any wiring at all, including one that
 * cancels everything, and "one removal" is a constant rather than a reading.
 * (The third seed was `BubbleBootReceiver`'s `3002` restore offer until that
 * notification was deleted as unreachable; a literal replaced it rather than
 * the seed being dropped, because the claim is about arbitrary notifications,
 * not about that feature.)
 *
 * ## Statelessness, deliberately
 *
 * There is no "already migrated" pref: both platform calls are already no-ops
 * when their target is absent, so idempotence is a property of the operation.
 * [theCleanupIsIdempotent] runs it twice over and asserts the second run
 * changes nothing and does not throw — which is the behaviour a flag would have
 * been added to buy, measured instead of assumed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TriggerNotificationCleanupTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    private val nm: NotificationManager
        get() = ctx.getSystemService(NotificationManager::class.java)

    /**
     * 🔴 The literal id and channel an OLDER build posted under.
     *
     * Spelled out here rather than read from [TriggerNotificationCleanup], for
     * the reason `BubbleHideFromNotificationTest` reads the pref names
     * literally: these values are a compatibility contract with an already-
     * installed APK. Taking them from the object under test would make this
     * file agree with any value it chose, including a typo, and the cleanup
     * would then cancel nothing on a real upgrade while staying green here.
     */
    private val staleId = 3001
    private val staleChannel = "dicta_trigger"

    /** `BubbleService`'s own foreground notification, which must survive. */
    private val serviceId = 2001
    private val serviceChannel = "dicta_service"

    /**
     * A second must-survive sibling, deliberately **synthetic**.
     *
     * 🔴 This read `BubbleBootReceiver.NOTIF_ID` / `.CHANNEL_ID` — the
     * boot-restore offer — and those constants were deleted along with that
     * notification, which cannot occur (see `BubbleEnabled.startService`'s
     * kdoc). The sibling is not dropped with them: a cleanup that cancels one
     * of *two* is much weaker evidence of scoping than one that cancels one of
     * *three*, and the claim under test is "exactly the stale id and nothing
     * else", which is about arbitrary other notifications rather than about any
     * particular feature. So the values are literals belonging to no production
     * symbol, and this fixture cannot be invalidated again by a surface going
     * away.
     */
    private val otherId = 4242
    private val otherChannel = "dicta_some_other_category"

    // --- the fixture: an install that had the notification switched on -------

    private fun postedBy(channel: String, id: Int, ongoing: Boolean) {
        nm.createNotificationChannel(
            NotificationChannel(channel, channel, NotificationManager.IMPORTANCE_LOW)
        )
        nm.notify(
            id,
            Notification.Builder(ctx, channel)
                .setSmallIcon(R.drawable.ic_tile_mic)
                .setContentTitle(ctx.getString(R.string.app_name))
                .setOngoing(ongoing)
                .build(),
        )
    }

    /** The shade, as a set of posted ids. */
    private fun shade(): Set<Int> =
        shadowOf(nm).activeNotifications.map { it.id }.toSet()

    /** The app's notification categories, as a set of channel ids. */
    private fun channels(): Set<String> =
        shadowOf(nm).notificationChannels.map { (it as NotificationChannel).id }.toSet()

    // --- the claims ---------------------------------------------------------

    @Test
    fun theCleanupIsScopedToTheDeletedNotificationAndNothingElse() {
        postedBy(staleChannel, staleId, ongoing = true)
        postedBy(serviceChannel, serviceId, ongoing = true)
        postedBy(otherChannel, otherId, ongoing = false)

        // 🔴 Negative control, before anything is cleaned: all three really are
        // posted and all three channels really exist. A "3001 is gone"
        // afterwards means nothing unless 3001 was there to begin with — and
        // Robolectric's NotificationManager is exactly the kind of shadow that
        // can silently record nothing.
        assertEquals(
            "precondition: the fixture did not post all three notifications, " +
                "so nothing below is a measurement",
            setOf(staleId, serviceId, otherId),
            shade(),
        )
        assertTrue(
            "precondition: the stale channel was never created",
            staleChannel in channels(),
        )

        TriggerNotificationCleanup.run(ctx)

        assertEquals(
            "the cleanup removed the wrong set of notifications. It must take " +
                "exactly the stale trigger notification ($staleId) and leave " +
                "BubbleService's foreground notification ($serviceId) — which " +
                "carries the only Hide bubble action — and an unrelated " +
                "notification ($otherId) alone. A cancelAll() fails here, and " +
                "so does a cleanup pointed at the wrong id.",
            setOf(serviceId, otherId),
            shade(),
        )
        assertEquals(
            "the cleanup removed the wrong set of channels. The stale " +
                "\"$staleChannel\" category must go — it survives a reboot and " +
                "would sit in the app's notification settings forever with " +
                "nothing able to post to it — and the other two must stay.",
            setOf(serviceChannel, otherChannel),
            channels(),
        )
    }

    @Test
    fun theCleanupIsIdempotent() {
        postedBy(staleChannel, staleId, ongoing = true)
        postedBy(serviceChannel, serviceId, ongoing = true)

        TriggerNotificationCleanup.run(ctx)
        val afterFirst = shade() to channels()

        // Twice more, on a tree where there is nothing left to find. A stateless
        // cleanup must be a no-op here rather than throwing or taking a sibling.
        TriggerNotificationCleanup.run(ctx)
        TriggerNotificationCleanup.run(ctx)

        assertEquals(
            "running the cleanup again changed the shade, so it is not " +
                "idempotent and every app open would disturb something",
            afterFirst.first,
            shade(),
        )
        assertEquals(
            "running the cleanup again changed the channels",
            afterFirst.second,
            channels(),
        )
        assertTrue(
            "BubbleService's foreground notification did not survive repeated " +
                "cleanups",
            serviceId in shade(),
        )
    }

    @Test
    fun aCleanInstallWithNothingToCleanIsUnharmed() {
        postedBy(serviceChannel, serviceId, ongoing = true)

        // No stale notification and no stale channel: the fresh-install case.
        TriggerNotificationCleanup.run(ctx)

        assertEquals(setOf(serviceId), shade())
        assertEquals(setOf(serviceChannel), channels())
    }

    // --- the entry points ---------------------------------------------------

    /**
     * 🔴 The receiver is the primary call site, and the only one that needs
     * nothing of the user: the stale notification is un-dismissable, so waiting
     * for them to open the app would leave it up indefinitely.
     *
     * Driven through `onReceive` with the real broadcast, not by calling the
     * cleanup directly — the claim is that the wiring works, and a receiver
     * registered in the manifest with an unreachable body is the standard way
     * this kind of migration ships inert.
     */
    @Test
    fun thePackageReplacedBroadcastRunsTheCleanupWithoutTheAppBeingOpened() {
        postedBy(staleChannel, staleId, ongoing = true)
        postedBy(serviceChannel, serviceId, ongoing = true)
        assertTrue("precondition: the stale notification is posted", staleId in shade())

        PackageReplacedReceiver().onReceive(
            ctx,
            Intent(Intent.ACTION_MY_PACKAGE_REPLACED),
        )

        assertFalse(
            "ACTION_MY_PACKAGE_REPLACED did not clear the stale notification. " +
                "This is the only entry point that runs on an upgrade without " +
                "the user doing anything, so an inert receiver here leaves the " +
                "notification in the shade until the app is next opened — and " +
                "it cannot be swiped away.",
            staleId in shade(),
        )
        assertTrue(
            "the receiver took BubbleService's foreground notification with it",
            serviceId in shade(),
        )
    }

    /**
     * The action is checked in the receiver rather than trusted from the
     * manifest filter. A receiver is reachable by an explicit intent whatever
     * its filter says, so this is the guard, not the filter.
     */
    @Test
    fun thePackageReplacedReceiverIgnoresAnyOtherAction() {
        postedBy(staleChannel, staleId, ongoing = true)

        PackageReplacedReceiver().onReceive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED))
        PackageReplacedReceiver().onReceive(ctx, null)

        assertTrue(
            "the receiver acted on an action that does not mean \"this package " +
                "was just replaced\", so its own action check is not reached " +
                "and the manifest filter is the only thing deciding",
            staleId in shade(),
        )
    }

    /**
     * The backstop, for a device that does not deliver the replaced broadcast.
     *
     * 🔴 Driven by actually creating the activity, so this pins the CALL and not
     * a field: a migration whose call site was deleted in a refactor is the
     * exact failure this guards, and nothing else in the suite would notice.
     */
    @Test
    fun openingTheAppAlsoClearsIt() {
        postedBy(staleChannel, staleId, ongoing = true)
        postedBy(serviceChannel, serviceId, ongoing = true)
        assertTrue("precondition: the stale notification is posted", staleId in shade())

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            assertFalse(
                "opening MainActivity left the stale trigger notification in " +
                    "the shade. This is the backstop for a device that does " +
                    "not deliver ACTION_MY_PACKAGE_REPLACED, and it is the one " +
                    "place a user who went looking for the vanished switch " +
                    "will land.",
                staleId in shade(),
            )
            assertTrue(
                "opening MainActivity cancelled BubbleService's own foreground " +
                    "notification",
                serviceId in shade(),
            )
        } finally {
            controller.close()
            File(ctx.noBackupFilesDir, "history").deleteRecursively()
        }
    }

    /**
     * 🔴 The caller LEDGER: exactly these two production files call the cleanup.
     *
     * Pins a RELATIONSHIP, which is what the two behavioural tests above cannot
     * — they would both stay green if a third caller were added, and a cleanup
     * invoked from a hot path (a touch handler, a service tick) is two binder
     * calls per invocation rather than two per upgrade. Fails when the set
     * grows **or** shrinks, so deleting either call site is a decision.
     */
    @Test
    fun exactlyTwoProductionFilesRunTheCleanup() {
        val dir = listOf(
            File("app/src/main/java/dev/zacx/dicta"),
            File("src/main/java/dev/zacx/dicta"),
            File("../app/src/main/java/dev/zacx/dicta"),
        ).firstOrNull { it.isDirectory }
        assertTrue(
            "could not locate the production sources from ${File(".").absolutePath}",
            dir != null,
        )
        val callers = dir!!.listFiles { f -> f.name.endsWith(".kt") }.orEmpty()
            .filter { f ->
                // Comments stripped: the kdoc in TriggerNotificationCleanup.kt
                // and MainActivity.kt both discuss the call in prose, and prose
                // must not satisfy a scan.
                CALLS_CLEANUP.containsMatchIn(
                    f.readText()
                        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
                        .replace(Regex("//[^\\n]*"), " ")
                )
            }
            .map { it.name }
            .sorted()

        assertEquals(
            "the set of files calling TriggerNotificationCleanup.run changed. " +
                "Two call sites are deliberate: PackageReplacedReceiver (the " +
                "primary — runs on upgrade with no user action) and " +
                "MainActivity (the backstop). BubbleBootReceiver is NOT one: a " +
                "reboot clears posted notifications anyway, so it could only " +
                "ever find the channel, which these two already remove.",
            listOf("MainActivity.kt", "PackageReplacedReceiver.kt"),
            callers,
        )
    }

    /**
     * Positive control for the ledger's pattern. An empty match set and a
     * pattern that cannot match are the same observation.
     */
    @Test
    fun theCallerLedgerPatternMatchesSomethingAndNotProse() {
        assertTrue(
            "the pattern cannot see a real call",
            CALLS_CLEANUP.containsMatchIn("TriggerNotificationCleanup.run(this)"),
        )
        assertFalse(
            "the pattern matches a bare mention, so it would count every file " +
                "whose kdoc names the object",
            CALLS_CLEANUP.containsMatchIn("see TriggerNotificationCleanup for why"),
        )
    }

    private companion object {
        val CALLS_CLEANUP = Regex("""TriggerNotificationCleanup\.run\s*\(""")
    }
}
