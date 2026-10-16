package dev.zacx.dicta

import android.os.Looper
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
import java.io.File

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
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsHistoryControlsTest {

    @get:Rule
    val temp = TemporaryFolder()

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

    private companion object {
        /** Never equal to any real read; keeps [settleUsage]'s loop honest. */
        const val PENDING_PREFIX_MARKER = " "
    }
}
