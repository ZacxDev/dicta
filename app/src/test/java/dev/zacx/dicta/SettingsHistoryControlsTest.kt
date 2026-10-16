package dev.zacx.dicta

import android.app.AlertDialog
import android.content.DialogInterface
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.util.Locale

/**
 * The two history controls on the settings screen: the read-only usage line,
 * and "Delete all history".
 *
 * ## Why the test seeds the REAL store and not an injected fake
 *
 * `SettingsActivity` builds `DictationHistoryStore(this)`, which roots itself
 * under `noBackupFilesDir`. This file constructs a store the same way and
 * writes into it, so the activity and the test are looking at one directory —
 * which is the point: a fake injected past the production constructor could not
 * tell that the screen reads the store the app actually keeps its recordings in.
 *
 * ## What is asserted, and what is deliberately NOT
 *
 * View **state** only — the rendered string of `@id/stt_history_usage`, and the
 * store's contents afterwards. Nothing here touches layout geometry, because in
 * this repo Robolectric's `TextPaint.measureText` returns a flat 1.0px per
 * character and `Layout.getEllipsisCount` returns 0 even for a 4000-character
 * string, so any assertion about wrapping or truncation would be an assertion
 * about Robolectric rather than about the screen.
 *
 * 🔴 And nothing here is evidence that the group *reads* as the same app as
 * MainActivity. That is a pixel claim whose only honest check is a device
 * screenshot of both screens side by side, which is the real closing condition
 * for this screen. `SettingsThemingTest` pins the mechanical preconditions
 * (which drawable the button carries, that the destructive action survives the
 * loss of colour vision); this file pins the behaviour.
 *
 * ## 🔴 The locale is PINNED, because this file asserts formatted numbers
 *
 * `qualifiers = "en-rUS"` fixes the resource configuration, and
 * [restoreDefaultLocale] puts `Locale.getDefault()` back after every test. The
 * literals below contain a decimal separator, so without the qualifier they
 * were assertions about whatever locale the machine running them happened to
 * have: green on CI (`en_US`), red on an `es-CO` handset, and the suite fixed
 * nothing. Pinning it also makes the dimension *visible* — see
 * [theUsageLineFormatsTheNumberInTheLocaleTheSentenceResolvesIn], which is the
 * test that can only exist once the two locales are set independently.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS")
class SettingsHistoryControlsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val systemLocale: Locale = Locale.getDefault()

    @After
    fun restoreDefaultLocale() {
        Locale.setDefault(systemLocale)
    }

    /** The same directory the activity's own store will use. */
    private fun appStore(): DictationHistoryStore =
        DictationHistoryStore(RuntimeEnvironment.getApplication())

    @Before
    fun emptyHistory() {
        // Robolectric gives each test its own data dir, but an explicit clear
        // makes the seeded counts below literal rather than dependent on that.
        appStore().clearAll()
    }

    private var counter = 0

    /** A recording of exactly [bytes] bytes, so the MB figure is exact. */
    private fun seed(store: DictationHistoryStore, bytes: Int, text: String) {
        val audio = File(temp.newFolder("src-${counter++}"), "dictation.ogg")
            .apply { writeBytes(ByteArray(bytes)) }
        store.add(audio = audio, text = text, fromRemote = false, audioSeconds = 2.0)
    }

    /**
     * Drains the paused main looper until [read] differs from [from], because
     * the usage line is filled from a worker thread via `runOnUiThread`.
     *
     * On timeout it returns the last value read rather than throwing: the
     * caller's own `assertEquals` then reports what was on screen and why that
     * is wrong, which is a better failure than "timed out waiting".
     */
    private fun awaitChange(from: String, read: () -> String): String {
        val deadline = System.currentTimeMillis() + 5_000
        var now = read()
        while (now == from && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
            now = read()
        }
        return now
    }

    /** The line's text once the first store read has landed. */
    private fun firstRender(line: TextView): String = awaitChange(
        RuntimeEnvironment.getApplication().getString(R.string.stt_history_usage_pending),
    ) { line.text?.toString() ?: "" }

    @Test
    fun theUsageLineRendersWhatTheStoreReports() {
        val store = appStore()
        seed(store, 262_144, "one")
        seed(store, 262_144, "two")
        // Positive control: if the seeding did not land, the line below would
        // read "0 dictations" and this test would be measuring nothing.
        assertEquals(
            "the seeded store does not hold what this test assumes",
            DictationHistoryStore.Usage(2, 524_288L),
            store.usage(),
        )

        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val line = controller.get().findViewById<TextView>(R.id.stt_history_usage)
            val rendered = firstRender(line)

            // Literal, not computed from the activity's own formatter: an
            // expectation derived from the code under test would pin nothing.
            assertEquals(
                "the usage line does not report the store's own counts. This " +
                    "line is the screen's ONLY statement of what the app is " +
                    "keeping of the user's voice.",
                "History: 2 dictations, 0.5 MB",
                rendered,
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun theUsageLineIsSingularForOneDictation() {
        seed(appStore(), 104_858, "only one")

        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val line = controller.get().findViewById<TextView>(R.id.stt_history_usage)
            assertEquals(
                "History: 1 dictation, 0.1 MB",
                firstRender(line),
            )
        } finally {
            controller.close()
        }
    }

    /**
     * 🔴 The number and the sentence around it are formatted by DIFFERENT code
     * in different locales, and this is the only test that can see it.
     *
     * `%1$d` is formatted by `Resources.getQuantityString`, which uses the
     * **resource configuration's** locale (pinned `en-rUS` by this class's
     * `@Config`). `%2$s` is formatted by `SettingsActivity.usageText`. When
     * that half reads `Locale.getDefault()` instead, the two disagree the
     * moment the app's locale is not the JVM default — an ordinary state under
     * Android 13's per-app language, and the configuration this test builds by
     * setting the default to Germany while the resources stay `en-rUS`. The
     * result is one sentence with two number conventions in it: a `0,5` inside
     * an English string.
     *
     * Not an invariant guard: this fails on the `Locale.getDefault()` spelling
     * with `0,5 MB` and passes on `resources.configuration.locales[0]`.
     */
    @Test
    fun theUsageLineFormatsTheNumberInTheLocaleTheSentenceResolvesIn() {
        Locale.setDefault(Locale.GERMANY)
        val store = appStore()
        seed(store, 262_144, "one")
        seed(store, 262_144, "two")

        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val line = controller.get().findViewById<TextView>(R.id.stt_history_usage)
            assertEquals(
                "the MB figure is formatted in the JVM default locale while the " +
                    "sentence around it comes from the resource configuration's, " +
                    "so under a per-app language the line mixes two number " +
                    "conventions. Both halves must use the locale the string " +
                    "resource resolved in.",
                "History: 2 dictations, 0.5 MB",
                firstRender(line),
            )
        } finally {
            controller.close()
        }
    }

    /** The dialog the delete button now raises, or null when none is showing. */
    private fun confirmation(): AlertDialog? = ShadowAlertDialog.getLatestAlertDialog()

    @Test
    fun tappingDeleteAllHistoryDeletesNothingUntilItIsConfirmed() {
        val store = appStore()
        seed(store, 262_144, "one")
        seed(store, 262_144, "two")

        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val activity = controller.get()
            firstRender(activity.findViewById(R.id.stt_history_usage))

            activity.findViewById<Button>(R.id.stt_clear_history).performClick()
            shadowOf(Looper.getMainLooper()).idle()

            val dialog = confirmation()
            assertNotNull(
                "tapping \"Delete all history\" raised no confirmation dialog. " +
                    "The button's error ring and contentDescription are cues, " +
                    "and a cue is not a gate: one mis-tap on a scrolling " +
                    "settings screen would destroy every recording on the " +
                    "phone, with no undo and — until the browsing UI lands — no " +
                    "per-item delete to reach for instead.",
                dialog,
            )
            // The whole normalised string, not a keyword: the claim is that the
            // message names the COUNT and the irreversibility, and a guard on
            // the word "undone" is satisfiable by any reword that keeps it.
            assertEquals(
                "the confirmation does not name what is about to be lost. " +
                    "\"Are you sure?\" tells the user nothing they do not " +
                    "already know; the count and \"cannot be undone\" are the " +
                    "whole value of the gate.",
                "Delete all 2 saved recordings and their transcripts from this " +
                    "phone? This cannot be undone.",
                shadowOf(dialog!!).message?.toString(),
            )
            assertEquals(
                "the tap ALONE emptied the store, so the dialog is decoration " +
                    "in front of a delete that has already happened",
                DictationHistoryStore.Usage(2, 524_288L),
                store.usage(),
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun keepingAtTheConfirmationLeavesHistoryUntouched() {
        val store = appStore()
        seed(store, 262_144, "one")
        seed(store, 262_144, "two")

        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val activity = controller.get()
            val line = activity.findViewById<TextView>(R.id.stt_history_usage)
            val before = firstRender(line)

            activity.findViewById<Button>(R.id.stt_clear_history).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            confirmation()!!.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            // The delete would run on the activity's worker thread, so a
            // decline that wrongly started it needs a window to land in before
            // the assertions below; otherwise this reads green for the wrong
            // reason. Same idiom as awaitChange, which cannot be used here
            // because the expected outcome is that nothing changes.
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(200)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(
                "declining the confirmation still deleted history. Cancel must " +
                    "do nothing at all — there is no partial clear and no undo.",
                DictationHistoryStore.Usage(2, 524_288L),
                store.usage(),
            )
            assertEquals(
                "the usage line changed after a declined delete, so the screen " +
                    "is reporting a deletion that did not happen",
                before,
                line.text?.toString(),
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun deletingAllHistoryEmptiesTheStoreAndUpdatesTheLine() {
        val store = appStore()
        seed(store, 262_144, "one")
        seed(store, 262_144, "two")

        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val activity = controller.get()
            val line = activity.findViewById<TextView>(R.id.stt_history_usage)
            val before = firstRender(line)
            assertEquals("History: 2 dictations, 0.5 MB", before)

            activity.findViewById<Button>(R.id.stt_clear_history).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            confirmation()!!.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            val after = awaitChange(before) { line.text?.toString() ?: "" }

            assertEquals(
                "history still holds items after \"Delete all history\", so the " +
                    "user's recordings are still on the phone",
                DictationHistoryStore.Usage(0, 0L),
                store.usage(),
            )
            assertEquals(
                "the directory still holds files, so audio survived a control " +
                    "whose label says everything is deleted: " +
                    "${store.dir.list()?.toList()}",
                emptyList<String>(),
                (store.dir.listFiles() ?: emptyArray())
                    .filterNot { it.name == DictationHistoryStore.INDEX }
                    .map { it.name },
            )
            assertEquals(
                "the usage line was not refreshed after the delete, so the " +
                    "screen still reports recordings that no longer exist — " +
                    "and that line is the action's only confirmation",
                "History: 0 dictations, 0.0 MB",
                after,
            )
            assertNotEquals(
                "the line reads the same before and after the delete, so this " +
                    "test cannot see a refresh at all",
                before,
                after,
            )
        } finally {
            controller.close()
        }
    }

    /**
     * 🔴 The `count == 0` branch of the confirmation, which is reachable and
     * was untested.
     *
     * `lastUsage` is null until the first off-thread `usage()` read has been
     * rendered, so a tap before that lands — a real sequence on a cold
     * activity, not a contrived one — must raise a confirmation that names no
     * number. "Delete all 0 saved recordings" would be the one wrong number
     * that tells the user there is nothing to lose.
     *
     * The window is held open by NOT draining the main looper before the tap:
     * the worker's `runOnUiThread` is queued, so `renderUsage` has not run. The
     * store is seeded all the same, so a branch that read `usage()` directly
     * would say "2" and fail here.
     */
    @Test
    fun theConfirmationNamesNoCountUntilTheFirstUsageReadHasRendered() {
        val store = appStore()
        seed(store, 262_144, "one")
        seed(store, 262_144, "two")

        val controller = Robolectric.buildActivity(SettingsActivity::class.java).create()
        try {
            val activity = controller.get()
            val ctx = RuntimeEnvironment.getApplication()
            assertEquals(
                "fixture: the usage line has already rendered, so lastUsage is " +
                    "set and this test is exercising the counted branch instead",
                ctx.getString(R.string.stt_history_usage_pending),
                activity.findViewById<TextView>(R.id.stt_history_usage).text?.toString(),
            )

            activity.findViewById<Button>(R.id.stt_clear_history).performClick()

            val dialog = confirmation()
            assertNotNull("tapping delete raised no confirmation", dialog)
            assertEquals(
                "the confirmation quoted a count it does not have. Before the " +
                    "first usage read lands lastUsage is null, and \"0 saved " +
                    "recordings\" is the one number that would tell the user " +
                    "their history is already empty when it is not.",
                ctx.getString(R.string.stt_clear_history_confirm_any),
                shadowOf(dialog!!).message?.toString(),
            )
            assertEquals(
                "the store was emptied by the tap alone",
                2,
                store.usage().itemCount,
            )
        } finally {
            controller.close()
        }
    }

    /**
     * 🔴 The confirmation must not outlive the activity. No `configChanges`
     * and no `screenOrientation` are declared for this activity, so a rotation
     * with the dialog up destroys the activity under it: the window leaks
     * (`WindowLeaked`) and the confirmation the user was part-way through is
     * silently dropped.
     *
     * Robolectric does not report `WindowLeaked`, so what is asserted is the
     * mechanism that prevents it — the dialog is not showing once `onDestroy`
     * has run.
     */
    @Test
    fun theConfirmationIsDismissedWhenTheActivityIsDestroyed() {
        val store = appStore()
        seed(store, 262_144, "one")

        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        firstRender(activity.findViewById(R.id.stt_history_usage))
        activity.findViewById<Button>(R.id.stt_clear_history).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = confirmation()
        assertNotNull("fixture: no confirmation was raised", dialog)
        assertTrue("fixture: the confirmation is not showing", dialog!!.isShowing)

        controller.destroy()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "the confirmation is still showing after the activity was " +
                "destroyed — a dialog attached to a dead activity, which is " +
                "the WindowLeaked a rotation produces, and the user's " +
                "in-flight confirmation is dropped rather than re-offered",
            false,
            dialog.isShowing,
        )
    }

    /**
     * The usage read happens off the main thread, because `usage()` reads the
     * index off disk. There is no main-thread guard that can observe this in a
     * unit test — Robolectric runs the activity ON the main thread and does not
     * enforce `StrictMode`, so a `usage()` call moved back onto it would pass
     * every assertion in this file. What IS observable is the mechanism that
     * makes the off-thread render necessary: the layout's own text is the
     * pending string, not a number.
     */
    @Test
    fun theUsageLineStartsAsPendingRatherThanAFabricatedZero() {
        val ctx = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).create()
        try {
            assertTrue(
                "the usage line's layout text is not @string/stt_history_usage_pending",
                ctx.getString(R.string.stt_history_usage_pending).isNotBlank(),
            )
            assertEquals(
                "the pending string claims a count. A number shown before the " +
                    "store has been read is a wrong number, not a pending one.",
                false,
                ctx.getString(R.string.stt_history_usage_pending).any(Char::isDigit),
            )
        } finally {
            controller.close()
        }
    }
}
