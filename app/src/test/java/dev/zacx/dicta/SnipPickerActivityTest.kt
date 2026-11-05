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
import org.robolectric.shadows.ShadowToast
import java.io.File
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

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * The usage store the activity under test reads and writes.
     *
     * Held so a test can seed it before [start] or read it after a pick.
     * Rebuilt per [start] over a fresh directory.
     */
    private var usageStore: SnipUsageStore? = null

    /** The clock the usage store stamps with. A test may move it. */
    private var nowMs: Long = 1_000L

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
        // 🔴 Injected on EVERY start, including for the tests that predate
        // most-recently-used ordering. Without it they read the production
        // store over Robolectric's `noBackupFilesDir`, which is shared across
        // the class — so one test's pick would reorder another test's browse
        // list, and that failure would read as a flake rather than as the
        // ordering working. Reuses a store the test may already have seeded.
        c.get().usage = usageStore ?: SnipUsageStore(tmp.newFolder()) { nowMs }
            .also { usageStore = it }
        return c.setup().get()
    }

    /**
     * A usage store seeded with [stamps], for a test that asserts browse ORDER
     * without having to pick rows first.
     *
     * Deliberately writes through the store's own API rather than hand-rolling
     * the JSON: a fixture that wrote the file directly would keep passing if
     * the on-disk format changed under it.
     */
    private fun seedUsage(vararg stamps: Pair<String, Long>) {
        val dir = tmp.newFolder()
        val live = rows.map { it.label }.toSet()
        for ((label, at) in stamps) {
            SnipUsageStore(dir) { at }.recordAndPrune(label, live)
        }
        usageStore = SnipUsageStore(dir) { nowMs }
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

    // --- most-recently-used browse ordering ----------------------------------
    //
    // 🔴 INVARIANT GUARDS, not regression coverage, and the distinction is not
    // a technicality here. These reference SnipUsageStore, which arrives with
    // this change, so a run at 6434700 — the 0.5.0 release commit — is a
    // COMPILE ERROR rather than a test watched going red.
    //
    // The feature cannot be expressed at base at all: asserting "browse is
    // ordered by use" requires being able to record use, and nothing at base
    // can. So no test of this feature is red at base, and these do not claim
    // to be. What stands in for it is the partial-revert control (delete the
    // ordering call from filterRows and watch these redden) plus the mutant
    // batch in scripts/mutants/snip-mru.tsv — both reported in the PR.
    //
    // aTypedQueryKeepsTheRankedOrderAndIsNotReorderedByUsage is the one that
    // pins the DECISION rather than the mechanism: it is what fails if the
    // ordering is ever moved into readRows() as a pre-sort.

    @Test
    fun browseListsTheMostRecentlyPickedSnippetFirst() {
        // Catalog order is Zebra, Quince, Paste. Usage inverts it.
        seedUsage("Paste it back" to 3_000L, "Zebra crossing" to 1_000L, "Quince jam" to 2_000L)
        assertEquals(
            "the picker must open on the snippet last used, not on whatever " +
                "the catalog file happens to list first",
            listOf("Paste it back", "Quince jam", "Zebra crossing"),
            labels(stocked()),
        )
    }

    @Test
    fun snippetsNeverPickedFollowTheUsedOnesInCatalogOrder() {
        seedUsage("Paste it back" to 3_000L)
        assertEquals(
            "the one used snippet leads; the two never used keep catalog " +
                "order behind it rather than being shuffled",
            listOf("Paste it back", "Zebra crossing", "Quince jam"),
            labels(stocked()),
        )
    }

    @Test
    fun aFreshInstallBrowsesInCatalogOrder() {
        assertEquals(
            "with no usage recorded the order must be the catalog's — this is " +
                "0.5.0's behaviour and the correct starting state",
            listOf("Zebra crossing", "Quince jam", "Paste it back"),
            labels(stocked()),
        )
    }

    @Test
    fun pickingASnippetRecordsItSoTheNextBrowseLeadsWithIt() {
        // 🔴 The round trip, end to end through the activity: pick the LAST
        // row, then reopen and assert it leads. Nothing here writes the usage
        // file by hand.
        nowMs = 5_000L
        val first = stocked()
        assertEquals(
            "precondition: the catalog's own order",
            listOf("Zebra crossing", "Quince jam", "Paste it back"),
            labels(first),
        )
        pick(first, 2) // "Paste it back"

        controller?.close()
        assertEquals(
            "a pick must survive the activity and lead the next browse — " +
                "this is the whole feature, exercised the way the operator does it",
            listOf("Paste it back", "Zebra crossing", "Quince jam"),
            labels(stocked()),
        )
    }

    @Test
    fun aTypedQueryKeepsTheRankedOrderAndIsNotReorderedByUsage() {
        // 🔴 The asymmetry, pinned. Both rows below match "e" through their
        // labels, and `Quince jam` is the LESS recently used of the two — so if
        // usage leaked into the filtered path it would move. Relevance order
        // must win.
        //
        // This is the test that fails if the ordering is ever moved into
        // readRows() as a pre-sort, which is the obvious and wrong refactor:
        // SnipMatcher.rank preserves input order within its groups.
        seedUsage("Paste it back" to 9_000L, "Quince jam" to 1_000L)
        val activity = stocked()

        search(activity).setText("preserve")
        assertEquals(
            "a declared-term match must still be found",
            listOf("Quince jam"),
            labels(activity),
        )

        search(activity).setText("")
        assertEquals(
            "and clearing the field returns to the MRU browse order",
            listOf("Paste it back", "Quince jam", "Zebra crossing"),
            labels(activity),
        )
    }

    @Test
    fun aWhitespaceOnlyQueryBrowsesInUsageOrderRatherThanMatchingNothing() {
        // 🔴 The input that separates `query.trim().isEmpty()` from
        // `query.isEmpty()`, asserted through the real TextWatcher. A
        // TextWatcher produces this constantly — a space typed before the first
        // letter — and the wrong predicate shows an empty picker.
        seedUsage("Quince jam" to 2_000L)
        val activity = stocked()
        search(activity).setText(" ")
        assertEquals(
            "a whitespace-only query must browse, in usage order",
            listOf("Quince jam", "Zebra crossing", "Paste it back"),
            labels(activity),
        )
        assertEquals(View.VISIBLE, list(activity).visibility)
    }

    @Test
    fun aSnippetDroppedFromTheCatalogIsPrunedFromUsageOnTheNextPick() {
        // A label that is no longer in the catalog must not keep a stamp
        // forever — that is what bounds the usage file by the catalog.
        val dir = tmp.newFolder()
        SnipUsageStore(dir) { 1_000L }.recordAndPrune("Departed snippet", setOf("Departed snippet"))
        usageStore = SnipUsageStore(dir) { 7_000L }

        val activity = stocked()
        pick(activity, 0) // "Zebra crossing"

        assertEquals(
            "the departed label must be gone and only the live pick retained",
            mapOf("Zebra crossing" to 7_000L),
            SnipUsageStore(dir).lastUsed(),
        )
    }

    @Test
    fun pickingOneSnippetKeepsTheStampsOfTheOthersStillInTheCatalog() {
        // 🔴 Written because a MUTANT exposed that nothing covered it: if
        // `liveLabels` were never captured from readRows, it would stay empty,
        // the `ifEmpty { setOf(row.label) }` fallback would engage, and the
        // prune would drop EVERY other snippet's stamp — silently resetting the
        // operator's ordering on every pick.
        //
        // Every test that existed before this one passed under that mutant,
        // because each recorded at most one label. The bug needs two.
        val dir = tmp.newFolder()
        SnipUsageStore(dir) { 1_000L }.recordAndPrune("Quince jam", rows.map { it.label }.toSet())
        SnipUsageStore(dir) { 2_000L }.recordAndPrune("Paste it back", rows.map { it.label }.toSet())
        usageStore = SnipUsageStore(dir) { 3_000L }

        val activity = stocked()
        // 🔴 By LABEL, not by position: usage is seeded here, so the list is
        // already reordered and row 0 is "Paste it back", not "Zebra crossing".
        pickLabel(activity, "Zebra crossing")

        assertEquals(
            "picking one snippet must not prune the others — all three are in " +
                "the catalog the picker just rendered, so all three stamps survive",
            mapOf(
                "Quince jam" to 1_000L,
                "Paste it back" to 2_000L,
                "Zebra crossing" to 3_000L,
            ),
            SnipUsageStore(dir).lastUsed(),
        )
    }

    @Test
    fun aPickStillReachesTheClipboardWhenTheUsageWriteCannotHappen() {
        // 🔴 The failure posture, as a behaviour rather than a comment: usage
        // ordering is a convenience and the clipboard write is the feature. The
        // store is pointed at a path that cannot be created — a FILE standing
        // where its directory would go — so every write throws.
        val blocker = tmp.newFile("not-a-directory")
        usageStore = SnipUsageStore(File(blocker, "usage")) { 1_000L }

        val activity = stocked()
        pick(activity, 0)

        assertEquals(
            "the body must be on the clipboard even though the usage write " +
                "failed — losing the row order must never cost the paste",
            "walk slowly",
            clipText(),
        )
    }

    /** Clicks row [position] the way the list does. */
    private fun pick(activity: SnipPickerActivity, position: Int): Boolean =
        list(activity).performItemClick(null, position, position.toLong())

    /**
     * Clicks the row whose label is [label], wherever it currently sits.
     *
     * 🔴 Use this, not [pick], in any test that seeds usage. Most-recently-used
     * ordering means a POSITION no longer identifies a row: seeding usage
     * reorders the list, so `pick(activity, 0)` silently picks whichever
     * snippet the ordering floated to the top. That cost a real debugging
     * round on [pickingOneSnippetKeepsTheStampsOfTheOthersStillInTheCatalog] —
     * it asserted on "Zebra crossing" and had actually clicked "Paste it back".
     */
    private fun pickLabel(activity: SnipPickerActivity, label: String): Boolean {
        val at = labels(activity).indexOf(label)
        require(at >= 0) { "no row labelled \"$label\"; rows are ${labels(activity)}" }
        return pick(activity, at)
    }
}
