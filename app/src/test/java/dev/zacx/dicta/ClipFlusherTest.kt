package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Draining the outbox: what leaves, what stays, and what order it goes in.
 *
 * 🔴 **No runner, no Android, no `org.json`.** [ClipFlusher] is deliberately
 * platform-free so this file can be a plain JUnit test — which is also what
 * makes it fast enough to be worth the cases below.
 *
 * 🔴 **Invariant guards, not regression coverage.** [ClipFlusher] is new.
 *
 * 🔴 **Three outcomes, three different fates for the user's text.** `Stored`
 * removes the row, `Refused` removes it (a drop), `Unreachable` keeps it. Get
 * the second two the wrong way round and the queue either blocks forever on one
 * bad row or deletes every capture the moment the mesh goes down — so these are
 * the tests, rather than the arithmetic in [ClipFlusher.Report].
 */
class ClipFlusherTest {

    /** An in-memory outbox. `failOnRead` is the corrupt-file case. */
    private class FakeOutbox(
        bodies: List<String> = emptyList(),
        private val failOnRead: String? = null,
    ) : ClipOutbox {
        private var nextId = 1L
        val items = bodies.mapIndexed { i, b ->
            ClipOutbox.Pending(id = (i + 1).toLong(), body = b)
        }.toMutableList()
        val removed = mutableListOf<Long>()

        init { nextId = items.size + 1L }

        override fun append(body: String): Long {
            val id = nextId++
            items += ClipOutbox.Pending(id, body)
            return id
        }

        override fun pending(): List<ClipOutbox.Pending> {
            failOnRead?.let { throw ClipOutbox.Failed(it) }
            return items.toList()
        }

        override fun remove(id: Long) {
            removed += id
            items.removeAll { it.id == id }
        }
    }

    /** Records every POST in order and answers from a script. */
    private class FakePoster(private val answers: List<ClipPost>) {
        val sent = mutableListOf<Pair<String, String>>()
        operator fun invoke(body: String, device: String): ClipPost {
            sent += body to device
            return answers.getOrElse(sent.size - 1) { answers.last() }
        }
    }

    private fun flusher(
        outbox: ClipOutbox,
        answers: List<ClipPost>,
        device: String = "pixel-7",
    ): Pair<ClipFlusher, FakePoster> {
        val poster = FakePoster(answers)
        return ClipFlusher(outbox, device) { b, d -> poster(b, d) } to poster
    }

    @Test
    fun anEmptyOutboxOpensNoSocketAtAll() {
        // 🔴 The tick runs every minute for the whole life of the service, so
        // "nothing to send" must cost nothing. A probe-then-send design would
        // make an idle phone talk to the mesh 1,440 times a day for no reason.
        val outbox = FakeOutbox()
        val (flush, poster) = flusher(outbox, listOf(ClipPost.Stored(1L)))
        assertEquals(ClipFlusher.Report(), flush.flush())
        assertEquals(emptyList<Pair<String, String>>(), poster.sent)
    }

    @Test
    fun everyQueuedCaptureIsSentOLDESTFirstWithThisDevicesAttribution() {
        // 🔴 ORDER, and it is not cosmetic: the SERVER assigns created_at at
        // POST time, so sending newest-first would permanently invert the two
        // clips' order on every other device — including in the desktop
        // client's own list, where the mistake is not recoverable.
        val outbox = FakeOutbox(listOf("first", "second", "third"))
        val (flush, poster) = flusher(
            outbox,
            listOf(ClipPost.Stored(1L), ClipPost.Stored(2L), ClipPost.Stored(3L)),
        )
        val report = flush.flush()
        assertEquals(
            listOf("first" to "pixel-7", "second" to "pixel-7", "third" to "pixel-7"),
            poster.sent,
        )
        assertEquals(ClipFlusher.Report(stored = 3, kept = 0), report)
        assertEquals(emptyList<ClipOutbox.Pending>(), outbox.items)
    }

    @Test
    fun aStoredCaptureLeavesTheOutboxByITSOWNIdAndNotByPosition() {
        // The id the SERVER returns and the id the OUTBOX holds are different
        // numbers from different counters. Removing by the server's id would
        // delete an unrelated capture — or nothing, leaving the queue to send
        // the same clip every minute forever.
        val outbox = FakeOutbox(listOf("only"))
        val localId = outbox.items.single().id
        val (flush, _) = flusher(outbox, listOf(ClipPost.Stored(id = 909L)))
        flush.flush()
        assertEquals(listOf(localId), outbox.removed)
    }

    @Test
    fun aDedupedStoreCountsAsStoredAndTheRowStillLeaves() {
        // A clip the operator copied on two devices answers 200 deduped:true
        // (measured live). It IS on the server, so keeping the local row would
        // re-POST it every minute forever — bumping its timestamp each time and
        // shuffling it to the top of everyone's list.
        val outbox = FakeOutbox(listOf("same text"))
        val (flush, _) = flusher(outbox, listOf(ClipPost.Stored(5L)))
        assertEquals(ClipFlusher.Report(stored = 1), flush.flush())
        assertEquals(emptyList<ClipOutbox.Pending>(), outbox.items)
    }

    @Test
    fun aPERMANENTrefusalIsDROPPEDSoItCannotBlockEverythingBehindIt() {
        // 🔴 The queue is strictly ordered, so a row the service will never
        // accept is a row that costs the user EVERY LATER CAPTURE if it is
        // retried. Dropping it is a real loss of one capture, chosen against
        // the alternative of losing all of them.
        val outbox = FakeOutbox(listOf("unacceptable", "fine"))
        val (flush, poster) = flusher(
            outbox,
            listOf(ClipPost.Refused(400, "`body` is required"), ClipPost.Stored(2L)),
        )
        val report = flush.flush()
        assertEquals(ClipFlusher.Report(stored = 1, dropped = 1, kept = 0), report)
        assertEquals(listOf("unacceptable", "fine"), poster.sent.map { it.first })
        assertEquals(emptyList<ClipOutbox.Pending>(), outbox.items)
    }

    @Test
    fun anUNREACHABLEserviceSTOPSTheFlushImmediatelyAndKeepsTheRest() {
        // 🔴 TWO reasons, and the second is the one that is easy to miss:
        //
        //   1. every later attempt in this pass would fail the same way, so
        //      they are pointless sockets on a phone's radio; and
        //   2. ORDER. The server stamps created_at, so skipping a failed item
        //      and sending the next one would put them on every other device
        //      in the wrong order — permanently, with nothing to repair it.
        val outbox = FakeOutbox(listOf("a", "b", "c"))
        val (flush, poster) = flusher(
            outbox,
            listOf(ClipPost.Stored(1L), ClipPost.Unreachable("ECONNREFUSED")),
        )
        val report = flush.flush()
        assertEquals(
            ClipFlusher.Report(stored = 1, dropped = 0, kept = 2, unreachable = true),
            report,
        )
        // 🔴 TWO sends, not three: the third was never attempted.
        assertEquals(listOf("a", "b"), poster.sent.map { it.first })
        assertEquals(listOf("b", "c"), outbox.items.map { it.body })
    }

    @Test
    fun theFirstItemBeingUnreachableSendsOnceAndKeepsEverything() {
        val outbox = FakeOutbox(listOf("a", "b"))
        val (flush, poster) = flusher(outbox, listOf(ClipPost.Unreachable("ETIMEDOUT")))
        assertEquals(
            ClipFlusher.Report(stored = 0, dropped = 0, kept = 2, unreachable = true),
            flush.flush(),
        )
        assertEquals(1, poster.sent.size)
        assertEquals(emptyList<Long>(), outbox.removed)
    }

    @Test
    fun aFlushSendsAtMostItsBudgetAndReportsTheRemainderAsKept() {
        // A phone that was off the mesh for a week can have hundreds of rows,
        // and one flush that tries them all is a single foreground burst on a
        // metered radio. The next tick is a minute away.
        val outbox = FakeOutbox(List(7) { "item-$it" })
        val (flush, poster) = flusher(outbox, listOf(ClipPost.Stored(1L)))
        val report = flush.flush(max = 3)
        assertEquals(3, poster.sent.size)
        assertEquals(ClipFlusher.Report(stored = 3, dropped = 0, kept = 4), report)
        assertFalse("a budget cut-off is not an outage", report.unreachable)
        assertEquals(4, outbox.items.size)
    }

    @Test
    fun theDefaultBudgetIsFiniteAndTheWholeQueueIsNotTheDefault() {
        assertEquals(25, ClipFlusher.MAX_PER_FLUSH)
        val outbox = FakeOutbox(List(30) { "item-$it" })
        val (flush, poster) = flusher(outbox, listOf(ClipPost.Stored(1L)))
        val report = flush.flush()
        assertEquals(ClipFlusher.MAX_PER_FLUSH, poster.sent.size)
        assertEquals(5, report.kept)
    }

    @Test
    fun anUnreadableOutboxIsReportedAsAFailureAndNOTAsADrainedQueue() {
        // 🔴 The corrupt-file case, which the store signals by throwing rather
        // than by returning an empty list. Swallowing it here would report a
        // successful flush of nothing, every minute, forever — the silent-empty
        // failure one layer up from where ClipOutboxStore refuses it.
        val (flush, poster) = flusher(
            FakeOutbox(failOnRead = "the outbox file could not be read"),
            listOf(ClipPost.Stored(1L)),
        )
        val report = flush.flush()
        assertEquals("the outbox file could not be read", report.failure)
        assertEquals(0, report.stored)
        assertEquals(0, report.kept)
        assertEquals(emptyList<Pair<String, String>>(), poster.sent)
    }

    @Test
    fun aFlushPROBESNothingBeforeSending() {
        // 🔴 A LEDGER-SHAPED CLAIM, asserted behaviourally: the only outbound
        // call a flush makes is the POST itself. No /healthz (the endpoint is a
        // SCHEMA probe that answers 200 with the database file deleted, so it
        // is not evidence the store is usable), and no reachability verdict
        // (SttReachability's is about the TRANSCRIPTION service on a different
        // port). ClipFlusher's constructor takes exactly one function, which is
        // what makes this structurally true — and the two
        // SttReachabilityLedgerTest ledgers are what keep it true across the
        // rest of the tree.
        val outbox = FakeOutbox(listOf("a"))
        val (flush, poster) = flusher(outbox, listOf(ClipPost.Unreachable("down")))
        flush.flush()
        assertEquals(
            "a flush made a call other than the POST it was given",
            1,
            poster.sent.size,
        )
    }

    @Test
    fun aReportDistinguishesAnEmptyQueueFromABlockedOne() {
        // The picker reads these fields to pick an empty state, so "nothing to
        // do" and "could not do it" must not be the same value.
        val quiet = ClipFlusher.Report()
        assertFalse(quiet.unreachable)
        assertEquals(null, quiet.failure)
        assertEquals(0, quiet.kept)

        val blocked = flusher(
            FakeOutbox(listOf("a")),
            listOf(ClipPost.Unreachable("down")),
        ).first.flush()
        assertTrue(blocked.unreachable)
        assertEquals(1, blocked.kept)
    }
}
