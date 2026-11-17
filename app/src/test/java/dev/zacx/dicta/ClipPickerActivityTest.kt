package dev.zacx.dicta

import android.content.ClipDescription
import android.content.ClipboardManager
import android.view.View
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.Executor

/**
 * [ClipPickerActivity], driven through the real activity.
 *
 * ## 🔴 What this file is evidence for, and what it is not
 *
 * It is evidence that the rows arrive in the **server's** order, that typing
 * filters them through [SnipMatcher], that each of the three empty states says
 * its own thing, that picking a row writes its body to the clipboard marked
 * sensitive, and that the outbox is flushed before the list is fetched.
 *
 * It is **not** evidence that the search field takes IME focus over an
 * arbitrary app — [SnipPickerActivityTest]'s note applies unchanged: Robolectric
 * has no window manager and no IME, and only a device can answer that. The pull
 * request says so rather than implying coverage.
 *
 * 🔴 **It is also not evidence about SERVER-SIDE search.** The service matches
 * with SQLite FTS5 trigrams and this picker filters locally with
 * [SnipMatcher]'s per-word substring rule; the two are **different matchers**
 * and will disagree on real queries. Nothing here asserts they agree, and
 * nothing should: the picker fetches a page of rows and filters what it has.
 *
 * Every fixture is synthetic, and **no token appears in this file** —
 * `ZacxDev/dicta` is a PUBLIC repository.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipPickerActivityTest {

    private val nowMs = 1_700_000_600_000L

    /** Newest first, as the service serves them. */
    private val serverRows = listOf(
        Clip(
            id = 7L,
            body = "zebra crossing notes",
            device = "laptop",
            createdAtMs = nowMs - 30_000L,
        ),
        Clip(
            id = 5L,
            body = "quince jam recipe",
            device = "desktop",
            createdAtMs = nowMs - 7_200_000L,
        ),
        Clip(
            id = 3L,
            body = "ssh prod gateway",
            device = "",
            createdAtMs = nowMs - 172_800_000L,
        ),
    )

    private class FakeSource(
        private val answer: List<Clip>?,
    ) : SharedClipSource {
        var reads = 0
        val deleted = mutableListOf<Long>()
        override fun rows(): List<Clip>? {
            reads++
            return answer
        }
        // 🔴 `fetch()` is the abstract member since round 0's D1 retired the
        // lossy default; `rows()` is the view the shell's own tests read. A
        // fake that answers rows has no failure to classify, so the two agree
        // by construction here: a null answer is a store that could not be
        // read, which this fixture spells as a transport failure — the one
        // class a fake can honestly claim.
        override fun fetch(): ClipFetch =
            rows()?.let { ClipFetch.Rows(it) } ?: ClipFetch.Transport("fake")
        override fun delete(id: Long): Boolean {
            deleted += id
            return true
        }
    }

    /** Records the order of the flush against the fetch. */
    private val order = mutableListOf<String>()

    private fun start(
        answer: List<Clip>?,
        flush: (() -> Unit)? = { order += "flush" },
    ): ClipPickerActivity {
        val source = object : SharedClipSource {
            override fun rows(): List<Clip>? {
                order += "fetch"
                return answer
            }
            override fun fetch(): ClipFetch =
                rows()?.let { ClipFetch.Rows(it) } ?: ClipFetch.Transport("fake")
            override fun delete(id: Long): Boolean = true
        }
        val c = Robolectric.buildActivity(ClipPickerActivity::class.java)
        c.get().remote = source
        c.get().flush = flush
        c.get().nowMs = { nowMs }
        // The executor seam removes the THREAD, not the call — the production
        // read is a real worker — so "open, then assert" has no race in it.
        // SnipPickerActivityTest's shape, for its reason.
        c.get().loadExecutor = Executor { it.run() }
        return c.setup().get()
    }

    private fun list(a: ClipPickerActivity) = a.findViewById<ListView>(R.id.picker_list)
    private fun search(a: ClipPickerActivity) = a.findViewById<EditText>(R.id.picker_search)
    private fun empty(a: ClipPickerActivity) = a.findViewById<TextView>(R.id.picker_empty)

    private fun bodies(a: ClipPickerActivity): List<String> {
        val adapter = requireNotNull(list(a).adapter) { "no adapter" }
        return (0 until adapter.count).map { (adapter.getItem(it) as Clip).body }
    }

    private fun clipboard(): ClipboardManager = RuntimeEnvironment.getApplication()
        .getSystemService(ClipboardManager::class.java)

    private fun string(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private fun string(id: Int, arg: Any) =
        RuntimeEnvironment.getApplication().getString(id, arg)

    // --- browsing and filtering ---------------------------------------------

    @Test
    fun thePickerOpensShowingEveryRowInTheSERVERSOrder() {
        // 🔴 THE SERVER'S ORDER, not a local one — and this is a DIFFERENT
        // decision from SnipPickerActivity's, which reorders a browse list
        // most-recently-used. The clip store is shared across machines, the
        // service already orders by created_at, and a local reordering would
        // make this phone's list disagree with the desktop's for no reason the
        // user could see. There is deliberately no isBrowse branch in
        // ClipPickerActivity.filterRows at all.
        val activity = start(serverRows)
        assertEquals(
            listOf("zebra crossing notes", "quince jam recipe", "ssh prod gateway"),
            bodies(activity),
        )
        assertEquals(View.VISIBLE, list(activity).visibility)
        assertEquals(View.GONE, empty(activity).visibility)
    }

    @Test
    fun typingFiltersTheRowsAndClearingGoesBackToTheWholeList() {
        val activity = start(serverRows)
        search(activity).setText("jam")
        assertEquals(listOf("quince jam recipe"), bodies(activity))

        search(activity).setText("")
        assertEquals(
            listOf("zebra crossing notes", "quince jam recipe", "ssh prod gateway"),
            bodies(activity),
        )
    }

    @Test
    fun aMultiWordQueryMatchesOnlyRowsCarryingEVERYWord() {
        // SnipMatcher's AND-across-tokens rule, reached through the clip's
        // body. "prod gateway" must not pull in a row that only has one of
        // them.
        val activity = start(
            serverRows + Clip(9L, "gateway timeout", "laptop", nowMs),
        )
        search(activity).setText("prod gateway")
        assertEquals(listOf("ssh prod gateway"), bodies(activity))
    }

    @Test
    fun filteringDoesNotREORDERTheRowsItKeeps() {
        // 🔴 The matcher RANKS, and this picker must not use that ranking:
        // SnipMatcher.rank puts a declared-term hit above a label hit, which
        // for clips would silently reorder the shared list on a keystroke. The
        // filter asks the matcher only WHETHER a row matches.
        //
        // The fixture is built so rank() and the server's order DISAGREE: the
        // older row would rank first if the ranking were used, because the
        // query is a whole word in it and only a substring in the newer one.
        val activity = start(
            listOf(
                Clip(2L, "pastelito", "a", nowMs),
                Clip(1L, "paste this", "b", nowMs - 1_000L),
            )
        )
        search(activity).setText("paste")
        assertEquals(listOf("pastelito", "paste this"), bodies(activity))
    }

    // --- the three empty states ---------------------------------------------

    @Test
    fun anUnreachableStoreShowsItsOwnStateAndNotAnEmptyList() {
        // 🔴 The silent-empty failure, in the UI, and the one case this feature
        // is most likely to hit: the phone off the mesh. Telling the operator
        // their shared clipboard is empty — when it is full and the tunnel is
        // down — is advice that cannot work.
        val activity = start(null)
        assertEquals(View.VISIBLE, empty(activity).visibility)
        assertEquals(View.GONE, list(activity).visibility)
        assertEquals(string(R.string.clip_empty_unreachable), empty(activity).text.toString())
    }

    @Test
    fun aReachableButEmptyStoreSaysSomethingDifferent() {
        val activity = start(emptyList())
        assertEquals(string(R.string.clip_empty_none), empty(activity).text.toString())
        assertNotEquals(
            "\"could not reach the store\" and \"the store is empty\" share a " +
                "string, so the operator cannot tell a dead mesh from a " +
                "drained clipboard",
            string(R.string.clip_empty_unreachable),
            string(R.string.clip_empty_none),
        )
    }

    @Test
    fun aQueryThatMatchesNothingSaysSoWithoutClaimingTheStoreIsEmpty() {
        val activity = start(serverRows)
        search(activity).setText("zzzzqqq")
        assertEquals(View.VISIBLE, empty(activity).visibility)
        assertEquals(string(R.string.clip_empty_no_matches), empty(activity).text.toString())
        assertNotEquals(string(R.string.clip_empty_none), string(R.string.clip_empty_no_matches))
        assertNotEquals(
            string(R.string.clip_empty_unreachable),
            string(R.string.clip_empty_no_matches),
        )
    }

    // --- the rows ------------------------------------------------------------

    @Test
    fun aRowShowsItsAgeItsDeviceAndItsBodyAndIsDescribedForAScreenReader() {
        val activity = start(serverRows)
        val view = requireNotNull(list(activity).adapter)
            .getView(1, null, list(activity))
        // 🔴 The literal is what HistoryRow.title ACTUALLY produces, read off a
        // run — not what the intent suggested. The first draft asserted
        // "2h ago · desktop" and went red: HistoryLabel.age renders "2h", with
        // no "ago". Taking the existing formatter's output verbatim is the
        // point of reusing it, and writing the expectation from the intent is
        // how a test ends up pinning a format the app does not have.
        assertEquals(
            "2h · desktop",
            view.findViewById<TextView>(R.id.clip_row_title).text.toString(),
        )
        assertEquals(
            "quince jam recipe",
            view.findViewById<TextView>(R.id.clip_row_preview).text.toString(),
        )
        // 🔴 The contentDescription is set on EVERY bind, not only when it is
        // absent: a recycled row would otherwise announce the previous clip's
        // text — which on a shared clipboard means announcing someone else's.
        assertEquals(
            string(R.string.clip_row_desc, "quince jam recipe"),
            view.contentDescription,
        )
    }

    @Test
    fun aClipFromAnUnnamedDeviceStillRendersALegibleTitle() {
        // The service's `device` is free text from whichever client posted, so
        // an empty one is reachable — and a bare "2d ago · " reads as damage.
        val activity = start(serverRows)
        val view = requireNotNull(list(activity).adapter)
            .getView(2, null, list(activity))
        val title = view.findViewById<TextView>(R.id.clip_row_title).text.toString()
        assertTrue(
            "an unattributed clip rendered as <$title>",
            title.contains(string(R.string.clip_device_unknown)),
        )
    }

    @Test
    fun aRecycledRowShowsTheNewClipAndNeverTheOneItHeldBefore() {
        // 🔴 On a shared clipboard a stale row is not a cosmetic bug: it shows
        // text from another machine under the wrong attribution and time.
        val activity = start(serverRows)
        val adapter = requireNotNull(list(activity).adapter)
        val reused = adapter.getView(0, null, list(activity))
        val again = adapter.getView(2, reused, list(activity))
        assertEquals(
            "ssh prod gateway",
            again.findViewById<TextView>(R.id.clip_row_preview).text.toString(),
        )
        assertEquals(
            string(R.string.clip_row_desc, "ssh prod gateway"),
            again.contentDescription,
        )
    }

    // --- picking one ---------------------------------------------------------

    @Test
    fun pickingARowPutsItsBodyOnTheClipboardAndSaysSo() {
        val activity = start(serverRows)
        list(activity).performItemClick(null, 1, 0L)

        val clip = requireNotNull(clipboard().primaryClip) { "nothing was copied" }
        assertEquals("quince jam recipe", clip.getItemAt(0).text.toString())
        assertEquals(string(R.string.clip_copied), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun theCopiedClipIsMarkedSENSITIVESoTheSystemDoesNotPreviewIt() {
        // 🔴 Not decoration. Android 13+ shows a toast containing the copied
        // text, and a shared clipboard's contents are exactly what must not be
        // rendered over whatever app is in front — this is the same flag
        // SnipPickerActivity sets, for the same reason.
        val activity = start(serverRows)
        list(activity).performItemClick(null, 0, 0L)
        val extras = requireNotNull(clipboard().primaryClip?.description?.extras) {
            "the copied clip carries no description extras at all"
        }
        assertTrue(
            "the clip is not marked sensitive",
            extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE),
        )
    }

    @Test
    fun theClipboardGetsTheBodyVERBATIMAndNoTemplateIsResolved() {
        // 🔴 A clip body is NOT a snippet. SnipPickerActivity resolves
        // {{clip}} because a snippet is a template the user authored; a clip is
        // text from another machine, and expanding a brace sequence in it would
        // silently alter what the user copied. The fixture carries the exact
        // token SnipClip treats as a variable.
        val activity = start(listOf(Clip(1L, "literal ${SnipClip.CLIP_VAR} here", "a", nowMs)))
        list(activity).performItemClick(null, 0, 0L)
        assertEquals(
            "literal ${SnipClip.CLIP_VAR} here",
            clipboard().primaryClip?.getItemAt(0)?.text?.toString(),
        )
    }

    @Test
    fun thePickerDoesNotFINISHItselfAfterAPickButLeavesThatToTheShell() {
        // The shell owns `finish()` after a pick — PickerShellSeamLedgerTest's
        // fifth rule. Asserting it here would be asserting the shell's
        // behaviour twice; what this asserts is that the OVERRIDE does not
        // short-circuit it, which is the mistake available to this file.
        val activity = start(serverRows)
        list(activity).performItemClick(null, 0, 0L)
        assertTrue("the shell did not finish after a pick", activity.isFinishing)
    }

    // --- the flush -----------------------------------------------------------

    @Test
    fun theOutboxIsFlushedBEFORETheListIsFetchedOnTheSameHop() {
        // 🔴 ORDER IS THE ASSERTION. Fetch-then-flush would show the operator a
        // list that does not contain the clip they captured ten seconds ago —
        // the single most likely thing they opened the picker to find. One
        // worker hop, flush first, then fetch.
        val activity = start(serverRows)
        assertEquals(listOf("flush", "fetch"), order)
        assertEquals(View.VISIBLE, list(activity).visibility)
    }

    @Test
    fun openingThePickerACKNOWLEDGESADestroyedCaptureAndRecordsAThrownFlush() {
        // 🔴 TWO relationships this activity owns that no component test can
        // see, both added by the capture-loss fix.
        //
        // 1. ACKNOWLEDGEMENT. ClipOutboxHealth.DESTROYED is LATCHED, because
        //    nothing proves a destroyed capture back into existence — so
        //    something has to clear it or the bubble stays ember forever,
        //    which is the permanently-red gate everyone learns to ignore.
        //    Opening the shared clipboard is the moment the operator has
        //    demonstrably seen it.
        //
        // 2. A THROWN flush is RECORDED. `readRows` wraps the drain in
        //    runCatching, and it used to swallow the throw entirely: no
        //    Report, no health signal, nothing on screen. An absence is the
        //    observable the most causes share.
        ClipOutboxHealth.clearForTest()
        try {
            ClipOutboxHealth.record(ClipFlusher.Report(dropped = 1))
            assertEquals(
                "precondition: the latch is up",
                ClipOutboxHealth.State.DESTROYED,
                ClipOutboxHealth.state(),
            )

            val throwingFlush = { throw ClipOutbox.Failed("the outbox file could not be read") }
            start(serverRows, flush = throwingFlush)

            // The acknowledgement cleared the latch, and the throw was
            // recorded as ONE failed drain — below the threshold, so OK.
            assertEquals(ClipOutboxHealth.State.OK, ClipOutboxHealth.state())

            // 🔴 And the count really is advancing rather than the
            // acknowledgement merely having run: an unrecorded throw also
            // leaves OK, so the only way to tell the two apart is to drive it
            // to the threshold.
            repeat(ClipOutboxHealth.STUCK_AFTER_FAILURES - 1) {
                start(serverRows, flush = throwingFlush)
            }
            assertEquals(
                "🔴 a flush that THREW produced no health signal at all, so " +
                    "a picker that cannot drain reports healthy forever",
                ClipOutboxHealth.State.STUCK,
                ClipOutboxHealth.state(),
            )
        } finally {
            ClipOutboxHealth.clearForTest()
        }
    }

    @Test
    fun aFlushThatFAILSStillLetsTheListLoad() {
        // 🔴 The queue and the store are independent failures. A corrupt outbox
        // file, or a keystore that refuses to unwrap the token, must not cost
        // the operator the ability to READ the shared clipboard — which is the
        // direction they use far more often.
        val activity = start(
            serverRows,
            flush = { throw ClipOutbox.Failed("the outbox file could not be read") },
        )
        assertEquals(
            listOf("zebra crossing notes", "quince jam recipe", "ssh prod gateway"),
            bodies(activity),
        )
    }

    @Test
    fun theStoreIsReadExactlyONCEPerOpen() {
        // Each read is a round trip over the mesh. A re-read per keystroke
        // would turn typing into a burst of requests, which is why filtering
        // happens locally over the page already fetched.
        val source = FakeSource(serverRows)
        val c = Robolectric.buildActivity(ClipPickerActivity::class.java)
        c.get().remote = source
        c.get().flush = {}
        c.get().nowMs = { nowMs }
        c.get().loadExecutor = Executor { it.run() }
        val activity = c.setup().get()
        search(activity).setText("jam")
        search(activity).setText("ja")
        search(activity).setText("")
        assertEquals(1, source.reads)
        assertEquals("the picker deleted a row nobody asked it to", emptyList<Long>(), source.deleted)
    }

    @Test
    fun noRowIsDELETEDByOpeningOrPickingAnything() {
        // 🔴 There is no phone-side delete affordance, by decision — the
        // SharedClipSource interface carries `delete` because the desktop
        // client's contract does, and on Android it is UNREACHABLE from the
        // UI. This is the guard on that claim: a long-press hook added later
        // without a decision would turn a tap-adjacent gesture into a
        // cross-device deletion.
        val source = FakeSource(serverRows)
        val c = Robolectric.buildActivity(ClipPickerActivity::class.java)
        c.get().remote = source
        c.get().flush = {}
        c.get().nowMs = { nowMs }
        c.get().loadExecutor = Executor { it.run() }
        val activity = c.setup().get()
        list(activity).performItemClick(null, 0, 0L)
        assertEquals(emptyList<Long>(), source.deleted)
    }

    @Test
    fun theTitleAndHintAreTheClipStringsAndNotTheSnipOnes() {
        // The shell renders whatever the subclass names, and three pickers
        // wearing the same chrome is how one ends up announcing itself as
        // another.
        val activity = start(serverRows)
        assertEquals(
            string(R.string.clip_title),
            activity.findViewById<TextView>(R.id.picker_title).text.toString(),
        )
        assertEquals(
            string(R.string.clip_search_hint),
            search(activity).hint.toString(),
        )
        assertNotEquals(string(R.string.snip_title), string(R.string.clip_title))
    }

    @Test
    fun theSearchFieldStartsEMPTYSoTheWholeListIsVisible() {
        val activity = start(serverRows)
        assertEquals("", search(activity).text.toString())
        assertNull(
            "the picker pre-seeded a query",
            search(activity).text.toString().takeIf { it.isNotEmpty() },
        )
    }
}
