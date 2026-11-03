package dev.zacx.dicta

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.view.View
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.Executor

/**
 * [SnipPickerActivity], driven through the real activity.
 *
 * ## 🔴 What this file is evidence for, and what it is not
 *
 * It is evidence that the rows come from the catalog in [SnipMatcher.rank]'s
 * order, that typing filters them, that picking one writes the **resolved**
 * body to the clipboard and toasts, and that each of the three empty states
 * says its own thing.
 *
 * It is **not** evidence that the search field takes IME focus over an
 * arbitrary app, which is the whole architectural reason the picker is an
 * Activity. Robolectric has no window manager and no IME; only a device can
 * answer that, and the pull request says so rather than implying coverage.
 *
 * Every fixture is synthetic — see [SnipMatcherTest]'s note on why that
 * matters in a public repository.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SnipPickerActivityTest {

    private var controller: ActivityController<SnipPickerActivity>? = null

    private val rows = listOf(
        Snippet("Zebra crossing", "walk slowly", listOf("stripes"), "path"),
        Snippet("Quince jam", "boil the fruit", listOf("preserve"), "prompt"),
        Snippet("Paste it back", SnipClip.CLIP_VAR, listOf("passthrough"), "clipboard"),
    )

    /**
     * Starts the picker over [catalog], with a SYNCHRONOUS load.
     *
     * The executor seam removes the thread, not the call — the production read
     * is a real worker — so "open, then assert" has no race in it. Same shape
     * `BubbleService.historyExecutor` exists for.
     */
    private fun start(catalog: SnipStore.Catalog?): SnipPickerActivity {
        val c = Robolectric.buildActivity(SnipPickerActivity::class.java)
        controller = c
        c.get().source = SnipSource { catalog }
        c.get().loadExecutor = Executor { it.run() }
        return c.setup().get()
    }

    private fun stocked() = start(SnipStore.Catalog(rows, 1_700_000_000_000L))

    private fun list(a: SnipPickerActivity) = a.findViewById<ListView>(R.id.picker_list)
    private fun search(a: SnipPickerActivity) = a.findViewById<EditText>(R.id.picker_search)
    private fun empty(a: SnipPickerActivity) = a.findViewById<TextView>(R.id.picker_empty)

    private fun labels(a: SnipPickerActivity): List<String> {
        val adapter = requireNotNull(list(a).adapter) { "no adapter" }
        return (0 until adapter.count).map { (adapter.getItem(it) as Snippet).label }
    }

    private fun clipboard(): ClipboardManager = RuntimeEnvironment.getApplication()
        .getSystemService(ClipboardManager::class.java)

    private fun clipText(): String? =
        clipboard().primaryClip?.getItemAt(0)?.coerceToText(RuntimeEnvironment.getApplication())
            ?.toString()

    private fun string(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    // --- browsing and filtering ---------------------------------------------

    @Test
    fun thePickerOpensShowingEveryRowInTheCatalogsOwnOrder() {
        val activity = stocked()
        assertEquals(
            listOf("Zebra crossing", "Quince jam", "Paste it back"),
            labels(activity),
        )
        assertEquals(View.VISIBLE, list(activity).visibility)
        assertEquals(View.GONE, empty(activity).visibility)
    }

    @Test
    fun typingFiltersTheRowsThroughTheRankedOrder() {
        val activity = stocked()
        search(activity).setText("stripes")
        assertEquals(listOf("Zebra crossing"), labels(activity))

        // And clearing the field goes back to BROWSE rather than to nothing.
        search(activity).setText("")
        assertEquals(
            listOf("Zebra crossing", "Quince jam", "Paste it back"),
            labels(activity),
        )
    }

    @Test
    fun theRankedOrderIsTheMatchersAndNotTheCatalogsWhenTheyDisagree() {
        // 🔴 The behavioural half of "ported for semantics". "Quince jam"
        // declares nothing matching, "Paste it back" declares "passthrough"
        // and "Zebra crossing" reaches the query only through a label word —
        // so the declared row must come FIRST even though it is supplied last.
        val activity = start(
            SnipStore.Catalog(
                listOf(
                    Snippet("Passage markers", "x", emptyList(), "path"),
                    Snippet("Quay wall", "y", listOf("passage"), "path"),
                ),
                1L,
            )
        )
        search(activity).setText("passage")
        assertEquals(listOf("Quay wall", "Passage markers"), labels(activity))
    }

    // --- the three empty states ---------------------------------------------

    @Test
    fun noCatalogAtAllShowsTheNamedEmptyStateAndNotAnEmptyList() {
        // 🔴 The silent-empty failure, in the UI. A picker that simply showed
        // an empty list is indistinguishable from a broken feature, and the
        // operator would have no way to learn that the fix is an import.
        val activity = start(null)
        assertEquals(View.VISIBLE, empty(activity).visibility)
        assertEquals(View.GONE, list(activity).visibility)
        assertEquals(string(R.string.snip_empty_no_catalog), empty(activity).text.toString())
    }

    @Test
    fun anImportedButEmptyCatalogSaysSomethingDifferentFromNothingImported() {
        val activity = start(SnipStore.Catalog(emptyList(), 1L))
        assertEquals(string(R.string.snip_empty_catalog_empty), empty(activity).text.toString())
        assertNotEquals(
            "the two empty states share a string, so the operator cannot tell " +
                "\"import something\" from \"your catalog is empty\"",
            string(R.string.snip_empty_no_catalog),
            string(R.string.snip_empty_catalog_empty),
        )
    }

    @Test
    fun aQueryMatchingNothingSaysSoRatherThanClaimingNothingIsImported() {
        val activity = stocked()
        search(activity).setText("nonesuch")
        assertEquals(View.VISIBLE, empty(activity).visibility)
        assertEquals(string(R.string.snip_empty_no_matches), empty(activity).text.toString())
        assertNotEquals(
            "a failed search and an empty catalog read the same, which sends " +
                "the operator to Settings to fix a search",
            string(R.string.snip_empty_no_catalog),
            string(R.string.snip_empty_no_matches),
        )
    }

    // --- the clipboard write -------------------------------------------------

    @Test
    fun pickingARowPutsThatRowsBodyOnTheClipboardAndSaysSo() {
        val activity = stocked()
        assertTrue("the item click listener is not wired", pick(activity, 1))

        assertEquals("boil the fruit", clipText())
        assertEquals(
            RuntimeEnvironment.getApplication()
                .getString(R.string.snip_copied, "Quince jam"),
            ShadowToast.getTextOfLatestToast(),
        )
        assertTrue(
            "the picker stayed up over the app the user is pasting into",
            activity.isFinishing,
        )
    }

    @Test
    fun theToastNamesTheSnippetAndNeverItsBody() {
        // 🔴 A toast is drawn over whatever app the user is in and is
        // screenshot-able; a snippet body is arbitrary operator text — paths,
        // hostnames, prompt templates — and has no business being flashed on
        // screen to confirm a copy.
        val secret = "boil the fruit"
        val activity = stocked()
        pick(activity, 1)
        val toast = ShadowToast.getTextOfLatestToast()
        assertNotNull("nothing was toasted at all", toast)
        assertTrue(
            "the toast carries the snippet BODY: \"$toast\"",
            !toast.contains(secret),
        )
        assertTrue("the toast does not name the snippet: \"$toast\"", toast.contains("Quince jam"))
    }

    @Test
    fun pickingARowResolvesTheClipVariableFromTheCurrentClipboard() {
        clipboard().setPrimaryClip(ClipData.newPlainText("seed", "zebra crossing"))
        val activity = stocked()
        pick(activity, 2)

        assertEquals(
            "the pass-through row copied the literal token instead of " +
                "resolving it — which the user then pastes into a message",
            "zebra crossing",
            clipText(),
        )
    }

    @Test
    fun anEmptyClipboardDoesNotLeaveTheLiteralTokenOnTheClipboard() {
        // The same row with nothing to substitute, which is the case under
        // test: an "empty clipboard" must not surface as the literal token.
        clipboard().clearPrimaryClip()
        val activity = stocked()
        pick(activity, 2)
        val copied = clipText() ?: ""
        assertEquals("", copied)
        assertTrue(
            "the literal token reached the clipboard: \"$copied\"",
            !copied.contains(SnipClip.CLIP_VAR),
        )
    }

    @Test
    fun aRowWithNoVariableIsCopiedUntouchedEvenWhenTheClipboardHasText() {
        // The negative control for the two above: an implementation that
        // resolved unconditionally would pass them and would also rewrite every
        // body that happens to contain a brace.
        clipboard().setPrimaryClip(ClipData.newPlainText("seed", "zebra crossing"))
        val activity = stocked()
        pick(activity, 0)
        assertEquals("walk slowly", clipText())
    }

    @Test
    fun theCopiedSnippetIsMarkedSensitiveSoThePlatformDoesNotDisplayIt() {
        // 🔴 From Android 13 a copy raises a preview that renders the copied
        // TEXT over whatever app the user is in, and keeps it in clipboard
        // history. A snippet body is arbitrary operator text — SnipStore argues
        // noBackupFilesDir on exactly that basis, and Snippet names `ssh` and
        // `kubeconfig` among its categories — so this feature would otherwise
        // put a kubeconfig on screen to confirm that it had been copied, while
        // @string/snip_copied is worded to keep the body out of Dicta's OWN
        // toast.
        val activity = stocked()
        pick(activity, 1)

        val description = clipboard().primaryClip?.description
        assertNotNull("nothing reached the clipboard at all", description)
        assertTrue(
            "the snip clipboard write is not flagged " +
                "ClipDescription.EXTRA_IS_SENSITIVE, so the platform previews " +
                "the snippet body and keeps it in clipboard history: " +
                "extras=${description!!.extras}",
            description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true,
        )
    }

    // --- re-entry into a surviving instance ----------------------------------

    @Test
    fun aSecondLaunchReloadsTheCatalogAndClearsTheQuery() {
        // 🔴 The picker is launchMode="singleTop" with no noHistory, so HOME
        // (rather than Back) leaves the instance alive. Import a different
        // snippets.json, tap snip again, and the system delivers onNewIntent
        // while onCreate — the only place a catalog was loaded — does not run.
        // Without the override the picker lists rows the user has already
        // replaced and tapping one copies a body that no longer exists, with
        // nothing on screen saying so.
        var catalog = SnipStore.Catalog(rows, 1L)
        val c = Robolectric.buildActivity(SnipPickerActivity::class.java)
        controller = c
        c.get().source = SnipSource { catalog }
        c.get().loadExecutor = Executor { it.run() }
        val activity = c.setup().get()

        assertEquals(
            "precondition: the first open listed catalog A",
            listOf("Zebra crossing", "Quince jam", "Paste it back"),
            labels(activity),
        )
        search(activity).setText("stripes")
        assertEquals(
            "precondition: the query filtered the list",
            listOf("Zebra crossing"),
            labels(activity),
        )

        // Settings replaces the catalog while the picker sits stopped.
        catalog = SnipStore.Catalog(
            listOf(Snippet("Oyster fork", "shuck it", listOf("shellfish"), "path")),
            2L,
        )
        c.newIntent(SnipLaunch.intent(activity))

        assertEquals(
            "the re-entered picker still lists the REPLACED catalog, so a tap " +
                "copies a body the user already deleted",
            listOf("Oyster fork"),
            labels(activity),
        )
        assertEquals(
            "the query from before HOME was pressed is still filtering the " +
                "list, which reads as a short catalog rather than as a filter " +
                "the user cannot see the origin of",
            "",
            search(activity).text.toString(),
        )
    }

    @Test
    fun theFirstOpenLoadsTheCatalogExactlyOnce() {
        // The control for the test above: putting the reload in onResume would
        // also satisfy it, and would read the catalog twice — a file read plus
        // a JSON parse of the whole catalog — on every single launch.
        var loads = 0
        val c = Robolectric.buildActivity(SnipPickerActivity::class.java)
        controller = c
        c.get().source = SnipSource {
            loads++
            SnipStore.Catalog(rows, 1L)
        }
        c.get().loadExecutor = Executor { it.run() }
        c.setup()

        assertEquals("a fresh open read the catalog more than once", 1, loads)
    }

    // --- rendering -----------------------------------------------------------

    @Test
    fun aRecycledRowCarriesTheNewSnippetAndNotThePreviousOne() {
        // 🔴 The recycling hazard `BubbleService.bindChip` documents at
        // length, in its second home: a convertView arrives wearing the
        // previous row's text and contentDescription, and a bind that only
        // sets some of them leaves a row announcing a snippet it no longer
        // shows.
        val activity = stocked()
        val adapter = requireNotNull(list(activity).adapter)
        val scrap = adapter.getView(0, null, list(activity))
        val reused = adapter.getView(1, scrap, list(activity))
        assertSame("the adapter did not recycle the view it was handed", scrap, reused)

        assertEquals(
            "Quince jam · prompt",
            reused.findViewById<TextView>(R.id.snip_row_title).text.toString(),
        )
        assertEquals(
            "boil the fruit",
            reused.findViewById<TextView>(R.id.snip_row_preview).text.toString(),
        )
        assertTrue(
            "the recycled row still announces the previous snippet: " +
                "\"${reused.contentDescription}\"",
            reused.contentDescription.toString().contains("Quince jam"),
        )
    }

    @Test
    fun thePickerStartsNoActivityAndRaisesNoDictation() {
        // It reads the clipboard and writes it. Anything else started from
        // here would be a surface nobody decided to add — and the one that
        // matters is a plain DictateActivity launch, which auto-starts a
        // capture.
        val activity = stocked()
        pick(activity, 0)
        assertNull(
            "the picker started an activity",
            shadowOf(activity).nextStartedActivity,
        )
    }

    /** Clicks row [position] the way the list does. */
    private fun pick(activity: SnipPickerActivity, position: Int): Boolean =
        list(activity).performItemClick(null, position, position.toLong())
}
