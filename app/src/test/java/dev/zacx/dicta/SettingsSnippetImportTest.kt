package dev.zacx.dicta

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

/**
 * Importing a snip catalog from `SettingsActivity`, and the **seam** between
 * the screen that writes it and the picker that reads it.
 *
 * ## 🔴 The seam is the point, not the two halves
 *
 * `SnipStoreTest` proves the store round-trips and `SnipPickerActivityTest`
 * proves the picker renders a catalog handed to it. Both can be green while
 * the feature is broken together — the screen writing one directory and the
 * picker reading another is a defect neither file can see, because neither ever
 * builds the combined state. [anImportedCatalogIsWhatThePickerThenLists] is
 * the case that does, and it drives the real import through the real activity.
 *
 * Every fixture is synthetic; see [SnipMatcherTest]'s note on why that matters
 * in a public repository.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS")
class SettingsSnippetImportTest {

    @get:Rule
    val temp = TemporaryFolder()

    private var controller: ActivityController<SettingsActivity>? = null

    private val valid = """
        [
          {"label":"Zebra crossing","body":"walk slowly","search_terms":["stripes"],"category":"path"},
          {"label":"Quince jam","body":"boil the fruit","search_terms":["preserve"],"category":"prompt"}
        ]
    """.trimIndent()

    private fun string(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    /**
     * Starts the settings screen over a store rooted in a temp directory.
     *
     * The production store roots itself under `noBackupFilesDir`, which is
     * process-wide under Robolectric, so a test using it would be at the mercy
     * of whatever the previous test left behind — and the counts below are
     * literal.
     */
    private fun start(store: SnipStore): SettingsActivity {
        val c = Robolectric.buildActivity(SettingsActivity::class.java)
        controller = c
        c.get().snippets = store
        return c.setup().get()
    }

    private fun stateLine(a: SettingsActivity) = a.findViewById<TextView>(R.id.stt_snippets_state)

    /**
     * Drains the paused main looper until [read] differs from [from] — the
     * state line is filled from the activity's own worker via `runOnUiThread`.
     * [SettingsHistoryControlsTest]'s `awaitChange`, for its reason: on timeout
     * it returns the last value read, so the caller's own assertion reports
     * what was on screen rather than "timed out waiting".
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

    private fun settled(a: SettingsActivity, from: String = string(R.string.stt_snippets_pending)) =
        awaitChange(from) { stateLine(a).text?.toString() ?: "" }

    /** Registers [text] as the content of a fresh document URI. */
    private fun document(text: String): Uri {
        val uri = Uri.parse("content://dev.zacx.dicta.test/snippets-${counter++}.json")
        shadowOf(RuntimeEnvironment.getApplication().contentResolver)
            .registerInputStream(uri, ByteArrayInputStream(text.toByteArray()))
        return uri
    }

    private var counter = 0

    /**
     * Drives the real import: taps the button, then hands back a result
     * through the framework's own delivery.
     *
     * 🔴 Not a direct `onActivityResult` call with a restated request
     * code. `ShadowActivity.receiveResult` looks the code up from the intent
     * the activity ACTUALLY started for a result, so a production change that
     * stopped matching its own request code goes red here — which a literal
     * `1001` in this file would hide.
     */
    private fun deliver(a: SettingsActivity, uri: Uri?, resultCode: Int = Activity.RESULT_OK) {
        a.findViewById<Button>(R.id.stt_import_snippets).performClick()
        val started = shadowOf(a).nextStartedActivityForResult
        assertNotNull("the import button started no document picker", started)
        shadowOf(a).receiveResult(
            started!!.intent,
            resultCode,
            uri?.let { Intent().setData(it) },
        )
    }

    // --- the picker intent ---------------------------------------------------

    @Test
    fun theImportButtonFiresAnOpenDocumentIntentAndCreatesNoExportedSurface() {
        // 🔴 ACTION_OPEN_DOCUMENT started BY the app, which no other app can
        // trigger — the operator's decision over issue #38's share-sheet
        // wording, because ZacxDev/dicta is public and an <intent-filter>
        // would be an entry point every installed app could invoke.
        val activity = start(SnipStore(temp.newFolder("store")))
        activity.findViewById<Button>(R.id.stt_import_snippets).performClick()

        val intent = shadowOf(activity).nextStartedActivityForResult?.intent
        assertNotNull("the import button started no picker", intent)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent!!.action)
        assertTrue(
            "the intent lacks CATEGORY_OPENABLE, so a provider may hand back " +
                "a URI that cannot be read at all",
            intent.categories?.contains(Intent.CATEGORY_OPENABLE) == true,
        )
        assertEquals("application/json", intent.type)
        // 🔴 The fallback types are what keep a .json served as text/plain
        // selectable. The CONTENT is validated either way — nothing in this
        // feature trusts a declared type or a filename — so this only decides
        // what the user is allowed to pick.
        val fallbacks = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList()
        assertTrue(
            "the picker offers only application/json, so a provider that " +
                "mislabels the file greys out the very file the operator came " +
                "to choose: $fallbacks",
            fallbacks != null && fallbacks.size > 1 && fallbacks.contains("application/json"),
        )
    }

    // --- a good file ---------------------------------------------------------

    @Test
    fun aChosenDocumentIsPersistedAndCounted() {
        val store = SnipStore(temp.newFolder("store"))
        val activity = start(store)
        val pending = settled(activity)

        deliver(activity, document(valid))
        val rendered = settled(activity, from = pending)

        assertEquals(
            "the store does not hold what the import claimed to persist",
            listOf("Zebra crossing", "Quince jam"),
            store.catalog()?.snippets?.map { it.label },
        )
        assertTrue(
            "the state line does not report the imported count: \"$rendered\"",
            rendered.contains("2 snippets"),
        )
    }

    @Test
    fun anImportedCatalogIsWhatThePickerThenLists() {
        // 🔴 THE SEAM. One directory, two owners: the screen writes and the
        // picker reads. A defect here — a different root, a different file
        // name, a save that never lands — is invisible to both components'
        // own tests, because each builds only its own half of the state.
        val dir = temp.newFolder("shared")
        val activity = start(SnipStore(dir))
        val pending = settled(activity)
        deliver(activity, document(valid))
        settled(activity, from = pending)

        val picker = Robolectric.buildActivity(SnipPickerActivity::class.java)
        try {
            picker.get().source = SnipStore(dir)
            picker.get().loadExecutor = java.util.concurrent.Executor { it.run() }
            val list = picker.setup().get()
                .findViewById<android.widget.ListView>(R.id.snip_list)
            val adapter = requireNotNull(list.adapter) { "the picker has no adapter" }
            assertEquals(
                "the picker lists something other than what Settings just " +
                    "imported, so the two halves are not reading one catalog",
                listOf("Zebra crossing", "Quince jam"),
                (0 until adapter.count).map { (adapter.getItem(it) as Snippet).label },
            )
        } finally {
            runCatching { picker.destroy() }
        }
    }

    // --- a bad file ----------------------------------------------------------

    @Test
    fun aRejectedFileLeavesThePreviousCatalogIntactAndNamesTheReason() {
        // 🔴 Clear-then-import would turn one bad file into a lost catalog.
        // The save is reached only after the parse returns, so the previous
        // rows survive — and the reason goes on screen, because "import
        // failed" with nothing after it leaves the operator exactly as stuck
        // as a silent empty list would.
        val store = SnipStore(temp.newFolder("store"))
        val activity = start(store)
        val pending = settled(activity)
        deliver(activity, document(valid))
        val good = settled(activity, from = pending)

        deliver(activity, document("""[{"label":"Zebra crossing"},{"label":"Zebra crossing"}]"""))
        val failed = settled(activity, from = good)

        assertEquals(
            "a refused import destroyed the catalog that was already there",
            listOf("Zebra crossing", "Quince jam"),
            store.catalog()?.snippets?.map { it.label },
        )
        assertTrue(
            "the failure does not name the colliding label, so the operator " +
                "has nothing to act on: \"$failed\"",
            failed.contains("Zebra crossing"),
        )
        assertTrue(
            "the failure is not framed as \"that file is not a " +
                "snippets.json\", so it reads as the app being broken rather " +
                "than the file: \"$failed\"",
            failed.startsWith(string(R.string.stt_snippets_import_failed).substringBefore("%1\$s")),
        )
    }

    @Test
    fun aCancelledPickerChangesNothingAndReportsNoFailure() {
        // The negative control for the test above: an implementation that
        // treated every callback as an import would report a failure for a
        // user who simply backed out of the picker.
        val store = SnipStore(temp.newFolder("store"))
        val activity = start(store)
        val pending = settled(activity)
        deliver(activity, document(valid))
        val good = settled(activity, from = pending)

        deliver(activity, document("not json at all"), resultCode = Activity.RESULT_CANCELED)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "a cancelled picker rewrote the state line",
            good,
            stateLine(activity).text?.toString(),
        )
        assertEquals(
            listOf("Zebra crossing", "Quince jam"),
            store.catalog()?.snippets?.map { it.label },
        )
    }

    // --- the empty state -----------------------------------------------------

    @Test
    fun noCatalogAtAllShowsTheNamedEmptyStateAndNeverAZero() {
        // "0 snippets" tells the operator the import failed when it never ran.
        // "No snippets imported yet" tells them what to do next.
        val activity = start(SnipStore(temp.newFolder("store")))
        assertEquals(string(R.string.stt_snippets_none), settled(activity))
    }
}
