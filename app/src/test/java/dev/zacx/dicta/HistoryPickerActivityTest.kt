package dev.zacx.dicta

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
import java.util.concurrent.Executor

/**
 * [HistoryPickerActivity], driven through the real activity — the file
 * [SnipPickerActivityTest] is the template for, applied to the other picker.
 *
 * ## 🔴 What this file is evidence for, and what it is not
 *
 * It is evidence that the rows come from the store in the store's own order,
 * that typing filters them through [SnipMatcher] and not through a second
 * matcher, that picking one opens the card via [DictateLaunch.historyIntent]
 * **and finishes the picker**, that each of the three empty states says its own
 * thing, and that the store is asked for every retained item rather than the
 * chip reveal's 50.
 *
 * It is **not** evidence that the search field takes IME focus over an
 * arbitrary app, which is one of the two architectural reasons the picker is an
 * Activity. Robolectric has no window manager and no IME. Nor is it evidence
 * about what a row LOOKS like. The pull request says both rather than implying
 * coverage.
 *
 * ## 🔴 ONE test here is regression coverage; the rest are invariant guards
 *
 * [tappingARowOpensTheCardOnThatDictationThroughDictateLaunch] is
 * **RE-HOMED**, not new: it is `BubbleHistoryRevealTest`'s
 * `tappingAChipOpensTheCardOnThatDictationThroughDictateLaunch`, which pinned
 * behaviour the operator explicitly KEPT across this change — a row tap opens
 * the dictation card on that entry. The surface it is asserted against moved
 * from a chip in the bubble's overlay window to a row in this activity; the
 * claim did not. It is carried rather than deleted for exactly that reason.
 *
 * Everything else is an **invariant guard**. Nothing else here was watched
 * failing on pre-change code and nothing else could have been: the activity,
 * its shell and its launcher all arrive with this change, so a run against the
 * base commit is a compile error rather than a red test. What stands in for
 * red-at-base is the mutant batch in `scripts/mutants/history-picker.tsv`;
 * verdicts are in the pull request.
 *
 * ## 🔴 Every fixture is synthetic
 *
 * A PUBLIC repository. No transcript below is from the operator's real history
 * — see [SnipMatcherTest]'s note on why that matters.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryPickerActivityTest {

    private var controller: ActivityController<HistoryPickerActivity>? = null

    private val t0 = 1_700_000_000_000L

    /**
     * Three synthetic items, newest first — the order
     * [DictationHistoryStore.recent] returns.
     *
     * 🔴 The transcripts are pairwise distinct in every field a test reads, and
     * none of them can be produced by a constant: a fixture whose values
     * collide cannot see a mutant that hardcodes one of them.
     */
    private val rows = listOf(
        item("a1", agoMs = 2 * HOUR, text = "the quince jam recipe", fromRemote = true),
        item("b2", agoMs = 9 * HOUR, text = "zebra crossing on Oyster Lane", fromRemote = false),
        item("c3", agoMs = 3 * DAY, text = "remember the kubeconfig-homelab path", fromRemote = true),
    )

    private fun item(
        id: String,
        agoMs: Long,
        text: String,
        fromRemote: Boolean,
    ) = DictationHistoryStore.Item(
        id = id,
        createdAtMs = System.currentTimeMillis() - agoMs,
        audioBytes = 1_234L,
        audioFile = "$id.ogg",
        text = text,
        fromRemote = fromRemote,
        audioSeconds = 4.0,
    )

    /**
     * Starts the picker over [stored], with a SYNCHRONOUS load.
     *
     * The executor seam removes the thread, not the call — the production read
     * is a real worker — so "open, then assert" has no race in it. Same shape
     * and same reason as `SnipPickerActivityTest.start`, and the same shape the
     * deleted `BubbleService.historyExecutor` existed for.
     */
    private fun start(stored: List<DictationHistoryStore.Item>?): HistoryPickerActivity {
        val c = Robolectric.buildActivity(HistoryPickerActivity::class.java)
        controller = c
        c.get().source = { stored }
        c.get().loadExecutor = Executor { it.run() }
        return c.setup().get()
    }

    private fun stocked() = start(rows)

    private fun list(a: HistoryPickerActivity) = a.findViewById<ListView>(R.id.picker_list)
    private fun search(a: HistoryPickerActivity) = a.findViewById<EditText>(R.id.picker_search)
    private fun empty(a: HistoryPickerActivity) = a.findViewById<TextView>(R.id.picker_empty)

    /** The ids currently listed, in order — the identity a test can assert on. */
    private fun ids(a: HistoryPickerActivity): List<String> {
        val adapter = requireNotNull(list(a).adapter) { "no adapter" }
        return (0 until adapter.count)
            .map { (adapter.getItem(it) as DictationHistoryStore.Item).id }
    }

    private fun string(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    /** A formatted string resource — `@string/history_row_desc` takes one arg. */
    private fun string(id: Int, vararg args: Any) =
        RuntimeEnvironment.getApplication().getString(id, *args)

    /** Clicks row [position] the way the list does. */
    private fun pick(a: HistoryPickerActivity, position: Int): Boolean =
        list(a).performItemClick(null, position, position.toLong())

    // --- browsing ------------------------------------------------------------

    @Test
    fun thePickerOpensShowingEveryStoredDictationInTheStoresOwnOrder() {
        // 🔴 BROWSE, not a degenerate filter. A blank query must list
        // everything in the order the store supplied — newest first — because
        // that is how the picker opens, and a filter returning nothing for it
        // would look like an empty store. It is also the mitigation for the
        // extra gesture this change costs: the newest item is the first row.
        val activity = stocked()
        assertEquals(listOf("a1", "b2", "c3"), ids(activity))
        assertEquals(View.VISIBLE, list(activity).visibility)
        assertEquals(View.GONE, empty(activity).visibility)
    }

    @Test
    fun theStoreIsAskedForEveryRetainedItemAndNotTheChipRevealsFifty() {
        // 🔴 `BubbleService.REVEAL_MAX_ITEMS = 50` existed because the overlay
        // list was a thumb-sized affordance. A SEARCH FIELD over the newest 50
        // of up to HistoryRetention.MAX_ITEMS returns a confident WRONG answer:
        // the operator's device already holds 94, so a silent 50 would hide 44
        // of them and report "No saved dictations match that" for a dictation
        // that is right there on disk.
        //
        // Asserted against HistoryRetention.MAX_ITEMS rather than against the
        // literal 1000, because the rule is "the picker cannot search less than
        // the app keeps" — a relationship, not a number. The second assertion
        // pins that the limit is not merely >= 50, which is what a lazily
        // bumped cap would satisfy.
        var asked: Int? = null
        val c = Robolectric.buildActivity(HistoryPickerActivity::class.java)
        controller = c
        c.get().source = { limit -> asked = limit; rows }
        c.get().loadExecutor = Executor { it.run() }
        c.setup()

        assertNotNull("the picker never read the store at all", asked)
        assertEquals(
            "the picker asked the store for $asked items while the app RETAINS " +
                "${HistoryRetention.MAX_ITEMS}. Every item above the limit is " +
                "unsearchable and the picker says \"nothing matches\" for a " +
                "dictation that is on disk.",
            HistoryRetention.MAX_ITEMS,
            asked,
        )
        assertTrue(
            "the limit is the chip reveal's old 50, or lower",
            (asked ?: 0) > 50,
        )
    }

    @Test
    fun theFirstOpenReadsTheStoreExactlyOnce() {
        // The control for the re-entry test below: putting the reload in
        // onResume would also satisfy it, and would read the store twice — an
        // index parse plus up to MAX_ITEMS isFile stats — on every launch.
        var loads = 0
        val c = Robolectric.buildActivity(HistoryPickerActivity::class.java)
        controller = c
        c.get().source = { loads++; rows }
        c.get().loadExecutor = Executor { it.run() }
        c.setup()

        assertEquals("a fresh open read the store more than once", 1, loads)
    }

    @Test
    fun aSecondLaunchReloadsTheStoreAndClearsTheQuery() {
        // 🔴 singleTop with no noHistory, so HOME (rather than Back) leaves the
        // instance alive. Record a new dictation, tap history again, and the
        // system delivers onNewIntent while onCreate — the only place rows were
        // loaded — does not run. Without the override the picker lists a stale
        // set and tapping a row opens a card on an item that may be gone.
        var stored = rows
        val c = Robolectric.buildActivity(HistoryPickerActivity::class.java)
        controller = c
        c.get().source = { stored }
        c.get().loadExecutor = Executor { it.run() }
        val activity = c.setup().get()

        assertEquals("precondition: the first open listed set A", listOf("a1", "b2", "c3"), ids(activity))
        search(activity).setText("zebra")
        assertEquals("precondition: the query filtered the list", listOf("b2"), ids(activity))

        stored = listOf(item("d4", agoMs = MINUTE, text = "oyster fork", fromRemote = false))
        c.newIntent(HistoryLaunch.intent(activity))

        assertEquals(
            "the re-entered picker still lists the stale set, so a tap opens " +
                "a card on a dictation that may no longer exist",
            listOf("d4"),
            ids(activity),
        )
        assertEquals(
            "the query from before HOME was pressed is still filtering the " +
                "list, which reads as an empty history rather than as a filter " +
                "the user cannot see the origin of",
            "",
            search(activity).text.toString(),
        )
    }

    // --- search: SnipMatcher, and nothing else -------------------------------

    @Test
    fun typingFiltersTheTranscriptsAndClearingGoesBackToBrowse() {
        val activity = stocked()
        search(activity).setText("quince")
        assertEquals(listOf("a1"), ids(activity))

        search(activity).setText("")
        assertEquals(
            "clearing the field left a filtered list rather than returning to " +
                "BROWSE",
            listOf("a1", "b2", "c3"),
            ids(activity),
        )
    }

    @Test
    fun aMultiTokenQueryIsAnAndAcrossTheTranscriptsWords() {
        // 🔴 SnipMatcher's rule, unchanged: EVERY token must match some word.
        // The fixture is chosen so an OR implementation gives a DIFFERENT
        // answer — "zebra" is only in b2, "oyster" is only in b2, but
        // "zebra jam" spans two different rows and must match NEITHER.
        val activity = stocked()
        search(activity).setText("zebra oyster")
        assertEquals("an AND across two words of one transcript failed", listOf("b2"), ids(activity))

        search(activity).setText("zebra jam")
        assertEquals(
            "\"zebra jam\" matched something. The two tokens are in DIFFERENT " +
                "transcripts, so an AND matches nothing and an OR matches two " +
                "rows — this is the assertion that tells them apart.",
            emptyList<String>(),
            ids(activity),
        )
    }

    @Test
    fun theQueryIsCaseInsensitiveInBothDirections() {
        val activity = stocked()
        search(activity).setText("OYSTER")
        assertEquals(listOf("b2"), ids(activity))
        // And the transcript's own case does not matter either: "Oyster Lane"
        // is capitalised in the fixture and is reached by a lowercase query.
        search(activity).setText("lane")
        assertEquals(listOf("b2"), ids(activity))
    }

    @Test
    fun aHyphenInTheTranscriptSplitsWordsAndAHyphenatedQueryTokenMatchesNothing() {
        // 🔴 PINNED DELIBERATELY, AS CORRECT UPSTREAM BEHAVIOUR — not
        // discovered later as a bug report. SnipMatcher's `labelWords` is
        // `[a-z0-9]+` over the lowercased string, so a hyphen is NOT a word
        // character: "kubeconfig-homelab" tokenises to
        // ["kubeconfig", "homelab"].
        //
        // The consequence a user meets: either half matches on its own, and the
        // hyphenated spelling matches NOTHING, because the query token
        // "kubeconfig-homelab" is compared against words that never contain a
        // hyphen. That is measured upstream history rather than an accident of
        // a regex — a hyphen-KEEPING tokenizer there invented false findings
        // until it was replaced.
        val activity = stocked()

        search(activity).setText("kubeconfig")
        assertEquals("the hyphen did not split the word at all", listOf("c3"), ids(activity))
        search(activity).setText("homelab")
        assertEquals(listOf("c3"), ids(activity))

        search(activity).setText("kubeconfig-homelab")
        assertEquals(
            "a hyphenated query token matched, so this tree's SnipMatcher is " +
                "NOT the one whose tokenizer this pins — re-read " +
                "SnipMatcher.labelWords before changing this expectation",
            emptyList<String>(),
            ids(activity),
        )
    }

    @Test
    fun rankingRowByRowAgreesWithRankingTheWholeList() {
        // 🔴 The assertion behind HistoryPickerActivity's claim that filtering
        // one row at a time is EXACTLY equivalent to one `rank` call here.
        // It is equivalent only because every row's searchTerms are empty, so
        // `rank`'s "declared" group is always empty and its output is its input
        // filtered, order preserved. A future change to rank's GROUPING breaks
        // that, and this goes red rather than the picker silently reordering.
        //
        // Built from the SAME synthetic transcripts, through the public
        // matcher, for several queries including the blank one.
        val views = rows.map {
            Snippet(label = it.text, body = "", searchTerms = emptyList(), category = "")
        }
        for (query in listOf("", "the", "quince", "zebra oyster", "nonesuch", "KUBECONFIG")) {
            val wholeList = SnipMatcher.rank(views, query).map { it.label }
            val rowByRow = views.filter { SnipMatcher.rank(listOf(it), query).isNotEmpty() }
                .map { it.label }
            assertEquals(
                "ranking row-by-row disagrees with ranking the whole list for " +
                    "query <$query>, so HistoryPickerActivity.filterRows is no " +
                    "longer equivalent to a single rank call",
                wholeList,
                rowByRow,
            )
        }
        // POSITIVE CONTROL: a sweep where every query returned the same thing
        // would pass vacuously. At least one query must actually reduce the set.
        assertTrue(
            "no query in the sweep filtered anything, so the agreement above " +
                "is a fact about an unexercised code path",
            SnipMatcher.rank(views, "quince").size < views.size,
        )
    }

    @Test
    fun theSearchIsNotASecondMatcherLivingInTheHistoryPicker() {
        // 🔴 The operator's decision #3: ONE matching rule in the app, so a
        // multi-word query behaves identically in both pickers. A hand-rolled
        // `text.contains(query)` would satisfy most of the tests above and
        // would diverge on exactly the multi-token and tokenizer cases.
        //
        // Pinned on the SOURCE, because a behavioural test cannot distinguish
        // "calls SnipMatcher" from "reimplements what SnipMatcher happens to do
        // for these fixtures".
        val src = listOf(
            java.io.File("app/src/main/java/dev/zacx/dicta/HistoryPickerActivity.kt"),
            java.io.File("../app/src/main/java/dev/zacx/dicta/HistoryPickerActivity.kt"),
        ).first { it.isFile }.readText()
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("//[^\\n]*"), " ")

        assertTrue(
            "HistoryPickerActivity does not call SnipMatcher.rank outside a " +
                "comment, so its search is something else",
            Regex("""SnipMatcher\s*\.\s*rank\s*\(""").containsMatchIn(src),
        )
        for (hand in listOf(".contains(", ".startsWith(", "Regex(", ".indexOf(")) {
            assertTrue(
                "HistoryPickerActivity names <$hand>, which is how a second " +
                    "matcher gets written. If this is deliberate, say so here " +
                    "— it currently reads as a hand-rolled search.",
                !src.contains(hand),
            )
        }
    }

    // --- the three empty states ----------------------------------------------

    @Test
    fun anUnreadableStoreSaysSoRatherThanShowingAnEmptyList() {
        // 🔴 The silent-empty failure, in the UI. "could not be read" is a
        // DIFFERENT problem from "nothing stored" with a different fix, and an
        // empty list for either is indistinguishable from a broken feature.
        val activity = start(null)
        assertEquals(View.VISIBLE, empty(activity).visibility)
        assertEquals(View.GONE, list(activity).visibility)
        assertEquals(string(R.string.history_empty_unreadable), empty(activity).text.toString())
    }

    @Test
    fun aStoreThatHoldsNothingSaysSomethingDifferentFromAnUnreadableOne() {
        val activity = start(emptyList())
        assertEquals(string(R.string.history_empty_none), empty(activity).text.toString())
        assertNotEquals(
            "the two empty states share a string, so the user cannot tell " +
                "\"record one\" from \"Dicta could not read them\"",
            string(R.string.history_empty_unreadable),
            string(R.string.history_empty_none),
        )
    }

    @Test
    fun aQueryMatchingNothingSaysSoRatherThanClaimingNothingIsStored() {
        val activity = stocked()
        search(activity).setText("nonesuch")
        assertEquals(View.VISIBLE, empty(activity).visibility)
        assertEquals(View.GONE, list(activity).visibility)
        assertEquals(string(R.string.history_empty_no_matches), empty(activity).text.toString())
        assertNotEquals(
            "a failed search and an empty history read the same, which tells " +
                "the user to record a dictation they already have",
            string(R.string.history_empty_none),
            string(R.string.history_empty_no_matches),
        )
    }

    @Test
    fun theThreeEmptyStatesAreThreeDistinctStrings() {
        // 🔴 The pairwise version of the three tests above, so "three distinct
        // strings that never share a message" is asserted as a SET rather than
        // left to three separate comparisons that cover only two of the three
        // pairs.
        val messages = listOf(
            string(R.string.history_empty_unreadable),
            string(R.string.history_empty_none),
            string(R.string.history_empty_no_matches),
        )
        assertEquals(
            "two of the three history empty states carry the same message: " +
                messages.joinToString(" | "),
            3,
            messages.toSet().size,
        )
        assertTrue(
            "an empty-state string is blank, which renders as the silent-empty " +
                "failure the three of them exist to prevent",
            messages.none { it.isBlank() },
        )
    }

    // --- the pick ------------------------------------------------------------

    @Test
    fun tappingARowOpensTheCardOnThatDictationThroughDictateLaunch() {
        // 🔴 RE-HOMED from the deleted
        // `BubbleHistoryRevealTest.tappingAChipOpensTheCardOnThatDictationThroughDictateLaunch`.
        // This is the ONE claim in this file that pins behaviour which existed
        // before the change and that the operator explicitly KEPT (decision
        // #2): a row tap opens the dictation card on that entry. The surface
        // moved; the claim did not.
        //
        // 🔴 Row 1, not row 0. Index 0 would pass against an implementation
        // that always opens the first item, which is the mutation this fixture
        // exists to kill — and the ids are distinct so the extra cannot agree
        // by coincidence.
        val activity = stocked()
        assertTrue("the item click listener is not wired", pick(activity, 1))

        val intent = shadowOf(activity).nextStartedActivity
        assertNotNull("tapping a row started nothing", intent)
        assertEquals(
            DictateActivity::class.java.name,
            intent!!.component?.className,
        )
        assertEquals(
            "the card was opened on the wrong dictation — the tapped row is " +
                "b2, so an implementation that always opens the newest item " +
                "would put a1 here",
            "b2",
            intent.getStringExtra(DictateLaunch.EXTRA_HISTORY_ID),
        )
        assertTrue(
            "the intent carries no FLAG_ACTIVITY_NEW_TASK, so it was " +
                "hand-built rather than routed through DictateLaunch — and " +
                "without it the translucent card renders over Dicta's own UI, " +
                "which is issue #11 exactly",
            intent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )
    }

    @Test
    fun tappingARowFinishesThePickerSoBackReturnsToWhereTheUserWas() {
        // 🔴 The other half of decision #2, and it is the shell's `finish()`.
        // Without it, Back from the card lands on a stale picker behind it
        // rather than on the app the user started in.
        val activity = stocked()
        pick(activity, 1)
        assertTrue(
            "the picker stayed up behind the card it opened, so Back returns " +
                "the user to a stale search field",
            activity.isFinishing,
        )
    }

    @Test
    fun thePickerOpensTheCardOnHistoryAndNeverStartsARecording() {
        // 🔴 The history flow must reach no microphone and no capture. The
        // structural half is SnipSeamLedgerTest and StartSurfaceLedgerTest;
        // this is the behavioural half, asserted on the INTENT because that is
        // the only thing this activity can start.
        //
        // EXTRA_HISTORY_ID is what makes DictateActivity populate from history
        // instead of capturing, so its PRESENCE is the assertion — a plain
        // DictateLaunch.intent() would reach the same component and auto-start
        // a capture, and would be invisible to a component-only check.
        val activity = stocked()
        pick(activity, 0)
        val intent = requireNotNull(shadowOf(activity).nextStartedActivity)
        assertNotNull(
            "the card was opened WITHOUT EXTRA_HISTORY_ID, i.e. as a plain " +
                "launch — which auto-starts a capture. That is the one thing " +
                "opening a stored dictation must not do.",
            intent.getStringExtra(DictateLaunch.EXTRA_HISTORY_ID),
        )
        assertNull(
            "the picker started a SECOND activity as well",
            shadowOf(activity).nextStartedActivity,
        )
    }

    @Test
    fun anItemWithNoTranscriptIsStillListedAndStillTappable() {
        // 🔴 An item with no text still has AUDIO and is still
        // re-transcribable, so a row the user cannot see is an item they cannot
        // reach. It must also be reachable by BROWSE, since no query can match
        // an empty transcript.
        val activity = start(
            listOf(item("e5", agoMs = MINUTE, text = "", fromRemote = false)) + rows
        )
        assertEquals(listOf("e5", "a1", "b2", "c3"), ids(activity))

        val row = requireNotNull(list(activity).adapter).getView(0, null, list(activity))
        assertEquals(
            string(R.string.history_no_transcript),
            row.findViewById<TextView>(R.id.history_row_preview).text.toString(),
        )
        pick(activity, 0)
        assertEquals(
            "e5",
            shadowOf(activity).nextStartedActivity?.getStringExtra(DictateLaunch.EXTRA_HISTORY_ID),
        )
    }

    // --- rendering -----------------------------------------------------------

    @Test
    fun aRowShowsTheAgeTheEngineAndATruncatedTranscript() {
        val activity = stocked()
        val view = requireNotNull(list(activity).adapter).getView(0, null, list(activity))
        assertEquals(
            "2h · " + string(R.string.engine_badge_remote),
            view.findViewById<TextView>(R.id.history_row_title).text.toString(),
        )
        assertEquals(
            "the quince jam recipe",
            view.findViewById<TextView>(R.id.history_row_preview).text.toString(),
        )
    }

    @Test
    fun theEngineMarkerUsesTheCardsExistingVocabularyForBothValues() {
        // 🔴 "Server"/"Phone" — @string/engine_badge_remote /
        // @string/engine_badge_local, the badge the transcription card already
        // shows. Deliberately NOT a new word for the same distinction. Both
        // values are asserted, so an implementation that hardcoded one is red.
        val activity = stocked()
        val adapter = requireNotNull(list(activity).adapter)
        val remote = adapter.getView(0, null, list(activity))   // a1, fromRemote = true
        val local = adapter.getView(1, null, list(activity))    // b2, fromRemote = false
        assertTrue(
            "a server-transcribed row does not carry " +
                "@string/engine_badge_remote",
            remote.findViewById<TextView>(R.id.history_row_title).text.toString()
                .endsWith(string(R.string.engine_badge_remote)),
        )
        assertTrue(
            "an on-device row does not carry @string/engine_badge_local",
            local.findViewById<TextView>(R.id.history_row_title).text.toString()
                .endsWith(string(R.string.engine_badge_local)),
        )
        assertNotEquals(
            "the two engine badges are the same word, so the row cannot say " +
                "which engine produced the dictation",
            string(R.string.engine_badge_remote),
            string(R.string.engine_badge_local),
        )
    }

    @Test
    fun aRowSaysWhatTappingItDoesAndNotOnlyWhatItIs() {
        // 🔴 A screen-reader user meeting a row of free text has no way to know
        // it is a button. @string/history_row_desc is the
        // @string/snip_row_desc pattern: the item, then the action.
        val activity = stocked()
        val view = requireNotNull(list(activity).adapter).getView(0, null, list(activity))
        val desc = requireNotNull(view.contentDescription) { "the row announces nothing" }
            .toString()
        assertEquals(
            string(R.string.history_row_desc, "the quince jam recipe"),
            desc,
        )
        assertNotEquals(
            "the row's description is just its visible text, so it says what " +
                "the row IS and not what tapping it DOES",
            view.findViewById<TextView>(R.id.history_row_preview).text.toString(),
            desc,
        )
    }

    @Test
    fun aRecycledRowCarriesTheNewItemAndNotThePreviousOne() {
        // 🔴 The recycling hazard `BubbleService.bindChip` documented at length
        // before it was deleted, in its new home. A convertView arrives wearing
        // the PREVIOUS row's text, contentDescription and tooltip, and a bind
        // that only sets some of them leaves a row announcing a dictation it no
        // longer shows — or opening one the user did not pick.
        //
        // The two fixtures differ in EVERY field the bind writes — age, engine
        // and transcript — so a partial bind cannot pass by coincidence.
        val activity = stocked()
        val adapter = requireNotNull(list(activity).adapter)
        val scrap = adapter.getView(0, null, list(activity))
        val reused = adapter.getView(1, scrap, list(activity))
        assertSame("the adapter did not recycle the view it was handed", scrap, reused)

        assertEquals(
            "9h · " + string(R.string.engine_badge_local),
            reused.findViewById<TextView>(R.id.history_row_title).text.toString(),
        )
        assertEquals(
            "zebra crossing on Oyster Lane",
            reused.findViewById<TextView>(R.id.history_row_preview).text.toString(),
        )
        assertEquals(
            "the recycled row still announces the previous dictation: " +
                "\"${reused.contentDescription}\"",
            string(R.string.history_row_desc, "zebra crossing on Oyster Lane"),
            reused.contentDescription.toString(),
        )
    }

    @Test
    fun aLongTranscriptIsTruncatedInTheRowRatherThanRenderedWhole() {
        // A dictation is free text and can be thousands of characters; a row
        // showing all of it is a screenful per entry. The budget itself is
        // HistoryRowTest's — this asserts the ACTIVITY goes through it.
        val long = "lorem ipsum dolor sit amet ".repeat(40)
        val activity = start(listOf(item("f6", agoMs = MINUTE, text = long, fromRemote = false)))
        val view = requireNotNull(list(activity).adapter).getView(0, null, list(activity))
        val preview = view.findViewById<TextView>(R.id.history_row_preview).text.toString()
        assertTrue(
            "the row rendered ${preview.length} characters of a " +
                "${long.length}-character transcript, over the " +
                "${HistoryRow.PREVIEW_MAX} budget",
            preview.length <= HistoryRow.PREVIEW_MAX + 1,   // +1 for the ellipsis
        )
        assertTrue("nothing was truncated at all", preview.length < long.length)
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
