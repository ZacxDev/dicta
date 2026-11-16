package dev.zacx.dicta

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * When the capture queue counts as **stuck**.
 *
 * 🔴 **No runner, no Android.** [ClipOutboxHealth] folds [ClipFlusher.Report]s,
 * and both are platform-free, so this is plain JUnit — which is also what lets
 * it drive forty drains in a loop.
 *
 * 🔴 **Invariant guards, not regression coverage.** [ClipOutboxHealth] is new,
 * and so is the consumption of a Report at all: audit round 0 found the Report
 * built with the failure in it and **discarded at both production call sites**.
 * There is no tree on which this file could be watched going red for the
 * original defect, because on that tree there was nothing to call.
 *
 * ## 🔴 What is actually being guarded, and the direction that matters
 *
 * The hazard has TWO sides and an indicator can fail on either:
 *
 *  * **Silent.** A queue that cannot drain shows nothing, which is the shipped
 *    defect — a mistyped clip token 401s forever, every toast says "queued",
 *    and the picker renders the server's empty list as "nothing stored yet".
 *  * **Crying wolf.** A queue with rows in it is the ORDINARY state: the POST
 *    is always deferred by design, so a pending row one second after a capture
 *    is the feature working. An indicator on `kept > 0` would be lit after
 *    every single capture, and one that is always lit is one nobody reads.
 *
 * So every case below is a pair: this input must light it, that input must not.
 */
class ClipOutboxHealthTest {

    @Before
    fun setUp() {
        // 🔴 A process-wide object, so the previous test's drains are this
        // test's starting state unless this runs. The same reason
        // `OverlayVisibility.clearForTest` exists.
        ClipOutboxHealth.clearForTest()
    }

    @After
    fun tearDown() {
        ClipOutboxHealth.clearForTest()
    }

    /**
     * The STUCK verdict specifically — never "something is wrong".
     *
     * 🔴 An equality against one enum value rather than `verdict() != OK`, so
     * that a case asserting "this input must light the stuck indicator"
     * cannot be satisfied by the DESTROYED one. The two are different faults
     * with different labels and different remedies, and a helper that merged
     * them would let every case below pass for the wrong reason.
     */
    private fun stuck(): Boolean =
        ClipOutboxHealth.state() == ClipOutboxHealth.State.STUCK

    /** An unreachable store, which is what a 401 and a dead mesh both produce. */
    private fun unreachable(kept: Int = 1) =
        ClipFlusher.Report(kept = kept, unreachable = true)

    /** The outbox file itself could not be read. */
    private fun unreadable() =
        ClipFlusher.Report(failure = "the outbox file could not be read")

    /**
     * A pass that DESTROYED [dropped] captures — permanently refused and
     * removed from the only place they existed.
     */
    private fun destroyedPass(dropped: Int = 1, stored: Int = 0) =
        ClipFlusher.Report(stored = stored, dropped = dropped)

    private fun drain(report: ClipFlusher.Report, times: Int) {
        repeat(times) { ClipOutboxHealth.record(report) }
    }

    @Test
    fun aQueueNobodyHasFailedToDrainIsNotStuck() {
        assertFalse(stuck())
    }

    @Test
    fun aMERELYPENDINGQueueIsNEVERAFaultHoweverLongItStaysPending() {
        // 🔴 THE CRYING-WOLF SIDE, and the case that decides whether this
        // indicator is worth having. A flush that hit ClipFlusher.MAX_PER_FLUSH
        // leaves `kept` rows behind and is a drain that is WORKING — the next
        // tick takes the next batch. Forty of them in a row must still be
        // healthy.
        //
        // The fixture is deliberately the ceiling-hit shape: stored > 0 AND
        // kept > 0 AND unreachable == false. A `kept > 0` predicate would light
        // on the first of these.
        drain(ClipFlusher.Report(stored = 25, kept = 175), times = 40)
        assertFalse(
            "a queue that is draining a batch at a time reads as a FAULT, so " +
                "the bubble would show an error after every multi-item capture",
            stuck(),
        )
    }

    @Test
    fun oneFailedDrainIsNotAFaultEither() {
        // The tick is a minute and the mesh drops for a minute at a time. One
        // sample carries no information, which is why the threshold exists.
        ClipOutboxHealth.record(unreachable())
        assertFalse(stuck())
    }

    @Test
    fun theFaultAppearsExactlyATTheThresholdAndNotBefore() {
        // 🔴 Asserted at the BOUNDARY and at the step either side of it, rather
        // than by driving a big number and checking it is lit. An off-by-one in
        // either direction is the whole of what this constant can get wrong.
        repeat(ClipOutboxHealth.STUCK_AFTER_FAILURES - 1) {
            ClipOutboxHealth.record(unreachable())
            assertFalse(
                "the queue reads as stuck after ${it + 1} failed drains, " +
                    "before the ${ClipOutboxHealth.STUCK_AFTER_FAILURES} the " +
                    "threshold names",
                stuck(),
            )
        }
        ClipOutboxHealth.record(unreachable())
        assertTrue(
            "the queue is NOT stuck after ${ClipOutboxHealth.STUCK_AFTER_FAILURES} " +
                "consecutive failed drains, which is exactly the state a " +
                "mistyped clip token produces forever",
            stuck(),
        )
    }

    @Test
    fun theThresholdIsMoreThanOneAndIsNotLarge() {
        // Pinned as a band rather than a literal: the number is a judgement
        // about a one-minute tick, and a test asserting `== 3` would go red on
        // a deliberate retune while saying nothing about what is wrong. What
        // must never happen is 1 (every mesh blip is a fault) or a value so
        // large the operator never sees it.
        assertTrue(
            "STUCK_AFTER_FAILURES is ${ClipOutboxHealth.STUCK_AFTER_FAILURES}: " +
                "at 1 every single dropped socket raises a fault, and an " +
                "indicator that is always on is one nobody reads",
            ClipOutboxHealth.STUCK_AFTER_FAILURES > 1,
        )
        assertTrue(
            "STUCK_AFTER_FAILURES is ${ClipOutboxHealth.STUCK_AFTER_FAILURES}, " +
                "which at a one-minute tick is over ten minutes of silence " +
                "before the operator is told anything",
            ClipOutboxHealth.STUCK_AFTER_FAILURES <= 10,
        )
    }

    @Test
    fun anUNREADABLEOutboxCountsAsAFailureJustAsAnUnreachableStoreDoes() {
        // 🔴 Both halves of ClipFlusher.Report.cannotDrain, and the second is
        // the worse one: the captures are on disk and nothing can even be
        // ATTEMPTED. A verdict that keyed only on `unreachable` would report a
        // corrupt queue file as healthy forever.
        drain(unreadable(), times = ClipOutboxHealth.STUCK_AFTER_FAILURES)
        assertTrue(
            "a queue file that cannot be read reads as healthy",
            stuck(),
        )
    }

    @Test
    fun theTwoKindsOfFailureAccumulateTOGETHERRatherThanSeparately() {
        // A phone that is off the mesh and then finds a damaged queue file has
        // not had a successful drain in either case. Two counters would reset
        // each other and the fault would never appear.
        repeat(ClipOutboxHealth.STUCK_AFTER_FAILURES) { i ->
            ClipOutboxHealth.record(if (i % 2 == 0) unreachable() else unreadable())
        }
        assertTrue(
            "alternating an unreachable store with an unreadable queue file " +
                "never reaches the threshold, so the two kinds of failure are " +
                "counted separately and each resets the other",
            stuck(),
        )
    }

    @Test
    fun ONEGoodDrainClearsTheFaultCompletely() {
        // 🔴 Asymmetric on purpose, and the asymmetry is the right way round: it
        // takes several failures to raise the fault and ONE success to clear it.
        // A success is proof the queue CAN drain — there is nothing left to
        // warn about — whereas a failure is only evidence that it did not this
        // minute. A counter that decremented would leave the bubble ember for
        // minutes after the mesh came back.
        drain(unreachable(), times = ClipOutboxHealth.STUCK_AFTER_FAILURES)
        assertTrue("precondition: the fault is up", stuck())

        ClipOutboxHealth.record(ClipFlusher.Report(stored = 1))
        assertFalse(
            "a drain that emptied the queue left the fault showing",
            stuck(),
        )

        // And the count really is back to zero rather than merely below the
        // threshold: one more failure must not re-raise it.
        ClipOutboxHealth.record(unreachable())
        assertFalse(
            "the failure count was decremented rather than reset, so one " +
                "failure after a recovery re-raises the fault",
            stuck(),
        )
    }

    @Test
    fun anEmptyQueueIsASuccessfulDrainAndClearsTheFault() {
        // The all-default Report is what an empty outbox produces, and it is
        // emphatically not a failure: there is nothing to send. An operator who
        // deletes the stuck captures has fixed the problem.
        drain(unreachable(), times = ClipOutboxHealth.STUCK_AFTER_FAILURES)
        ClipOutboxHealth.record(ClipFlusher.Report())
        assertFalse(stuck())
    }

    // --- the channel ---------------------------------------------------------

    @Test
    fun aSinkIsToldONLYWhenTheVerdictCHANGES() {
        // 🔴 Not a nicety: the sink's job is a WindowManager layout update, and
        // a phone that has been off the mesh for an hour would otherwise do
        // sixty of them to say the same thing. OverlayVisibility makes the same
        // promise for the same reason.
        val seen = mutableListOf<ClipOutboxHealth.State>()
        val sink: (ClipOutboxHealth.State) -> Unit = { seen += it }
        ClipOutboxHealth.register(sink)

        drain(unreachable(), times = ClipOutboxHealth.STUCK_AFTER_FAILURES + 20)
        assertEquals(
            "a sink was told more than once that the queue is stuck",
            listOf(ClipOutboxHealth.State.STUCK),
            seen,
        )

        ClipOutboxHealth.record(ClipFlusher.Report(stored = 1))
        assertEquals(
            listOf(ClipOutboxHealth.State.STUCK, ClipOutboxHealth.State.OK),
            seen,
        )

        // And the pending-only shape must raise no notification at all, which
        // is the crying-wolf case at the channel rather than at the verdict.
        drain(ClipFlusher.Report(stored = 25, kept = 175), times = 10)
        assertEquals(
            listOf(ClipOutboxHealth.State.STUCK, ClipOutboxHealth.State.OK),
            seen,
        )

        ClipOutboxHealth.unregister(sink)
        drain(unreachable(), times = ClipOutboxHealth.STUCK_AFTER_FAILURES)
        assertEquals(
            "an unregistered sink was still called",
            listOf(ClipOutboxHealth.State.STUCK, ClipOutboxHealth.State.OK),
            seen,
        )
    }

    @Test
    fun aSinkThatTHROWSDoesNotStopTheOthers() {
        // The sink posts into a Service's looper and may be reached after that
        // Service is gone. One misbehaving sink must not cost the rest their
        // notification — OverlayVisibility.broadcast's rule.
        val reached = mutableListOf<ClipOutboxHealth.State>()
        val bad: (ClipOutboxHealth.State) -> Unit = { error("this sink is broken") }
        val good: (ClipOutboxHealth.State) -> Unit = { reached += it }
        ClipOutboxHealth.register(bad)
        ClipOutboxHealth.register(good)

        drain(unreachable(), times = ClipOutboxHealth.STUCK_AFTER_FAILURES)
        assertEquals(listOf(ClipOutboxHealth.State.STUCK), reached)

        ClipOutboxHealth.unregister(bad)
        ClipOutboxHealth.unregister(good)
    }

    // --- a DESTROYED capture --------------------------------------------------

    @Test
    fun ONEDestroyedCaptureIsAFaultIMMEDIATELYWithNoThreshold() {
        // 🔴 REGRESSION, and the inversion this branch exists to fix. Before
        // it, `ClipFlusher.Report.dropped` had no consumer anywhere in the
        // tree: a dropping pass is not a `cannotDrain` pass, so it fell into
        // the reset arm and held the counter at ZERO. Ten capture-destroying
        // flushes left the indicator dark while ten harmless 401 flushes lit
        // it — the indicator was dark for the destructive case and lit for the
        // safe one.
        //
        // No threshold, unlike STUCK: a row that no longer exists has no "it
        // may recover next minute" reading, so waiting three ticks would only
        // delay telling the operator about something already irreversible.
        ClipOutboxHealth.record(destroyedPass())
        assertEquals(
            "one destroyed capture did not raise a fault at all",
            ClipOutboxHealth.State.DESTROYED,
            ClipOutboxHealth.state(),
        )
    }

    @Test
    fun tenDestroyingFlushesDoNOTReadAsHealthy() {
        // 🔴 The measured shape of the shipped defect, as a case: a 404 from a
        // mistyped base URL destroyed one capture per flush, and `isStuck()`
        // stayed FALSE through all ten of them. The counterpart control is
        // `theFaultAppearsExactlyATTheThresholdAndNotBefore`, where ten
        // capture-KEEPING 401 flushes do light the indicator.
        drain(destroyedPass(), times = 10)
        assertEquals(
            "ten consecutive capture-destroying flushes read as healthy",
            ClipOutboxHealth.State.DESTROYED,
            ClipOutboxHealth.state(),
        )
    }

    @Test
    fun aDropDoesNotRESETTheStuckCounter() {
        // 🔴 The counter arm, asserted on its own. A pass that destroyed a
        // capture is not evidence the queue is healthy, so it must not clear
        // the failures that came before it. Driven to one BELOW the threshold
        // so the assertion is about the counter and not about the latch: if
        // the drop reset it, the following failure leaves the count at 1 and
        // the queue reads OK once the destruction is acknowledged.
        drain(unreachable(), times = ClipOutboxHealth.STUCK_AFTER_FAILURES - 1)
        ClipOutboxHealth.record(destroyedPass())
        ClipOutboxHealth.record(unreachable())
        ClipOutboxHealth.acknowledgeDestroyed()
        assertTrue(
            "a drop in the middle of a run of failures RESET the counter, so " +
                "a queue that is both destroying and failing never reports " +
                "stuck",
            stuck(),
        )
    }

    @Test
    fun aSuccessfulDrainDoesNOTUnsayADestroyedCapture() {
        // 🔴 LATCHED, unlike STUCK. One good drain clears a stuck queue
        // because a success proves the queue CAN drain and there is nothing
        // left to warn about. Nothing proves a destroyed capture back into
        // existence, so the only thing that clears it is the operator seeing
        // it — see the acknowledgement case below.
        ClipOutboxHealth.record(destroyedPass())
        drain(ClipFlusher.Report(stored = 3), times = 40)
        assertEquals(
            "forty successful drains erased the record that a capture was " +
                "destroyed, so an operator who was not looking at the phone " +
                "in that minute is never told",
            ClipOutboxHealth.State.DESTROYED,
            ClipOutboxHealth.state(),
        )
    }

    @Test
    fun aDestroyedCaptureOUTRANKSAStuckQueue() {
        // Both faults at once. They are rendered by different strings and the
        // worse one has to win, or a queue that is stuck AND losing captures
        // is announced with "Open it to retry".
        drain(unreachable(), times = ClipOutboxHealth.STUCK_AFTER_FAILURES)
        ClipOutboxHealth.record(destroyedPass())
        assertEquals(
            ClipOutboxHealth.State.DESTROYED,
            ClipOutboxHealth.state(),
        )
    }

    @Test
    fun acknowledgingClearsTheLatchAndNOTHINGElse() {
        // 🔴 The escape from a permanently-red indicator, which this
        // repository has a standing rule about: a fault no act of the
        // operator's can clear trains them to ignore it. Opening the picker
        // acknowledges it.
        //
        // And it clears ONLY the latch: a stuck queue underneath is not
        // repaired by looking at it, so the verdict falls back to STUCK
        // rather than to OK.
        drain(unreachable(), times = ClipOutboxHealth.STUCK_AFTER_FAILURES)
        ClipOutboxHealth.record(destroyedPass())
        ClipOutboxHealth.acknowledgeDestroyed()
        assertEquals(
            "acknowledging a destroyed capture also cleared a stuck queue " +
                "that nothing had repaired",
            ClipOutboxHealth.State.STUCK,
            ClipOutboxHealth.state(),
        )

        // And a later destruction re-raises it: the acknowledgement is of the
        // captures already lost, not a permanent opt-out.
        ClipOutboxHealth.record(destroyedPass())
        assertEquals(
            ClipOutboxHealth.State.DESTROYED,
            ClipOutboxHealth.state(),
        )
    }

    @Test
    fun aSinkIsToldAboutADestroyedCaptureAndAboutTheAcknowledgement() {
        // The channel, for the third verdict. A latch that nothing broadcast
        // would leave a bubble that is already built showing the old label
        // until something else changed.
        val seen = mutableListOf<ClipOutboxHealth.State>()
        val sink: (ClipOutboxHealth.State) -> Unit = { seen += it }
        ClipOutboxHealth.register(sink)

        drain(destroyedPass(), times = 5)
        assertEquals(
            "a sink was told five times about one latched fault",
            listOf(ClipOutboxHealth.State.DESTROYED),
            seen,
        )

        ClipOutboxHealth.acknowledgeDestroyed()
        assertEquals(
            listOf(ClipOutboxHealth.State.DESTROYED, ClipOutboxHealth.State.OK),
            seen,
        )

        // An acknowledgement with nothing latched changes nothing and says
        // nothing — the picker calls it on every open.
        ClipOutboxHealth.acknowledgeDestroyed()
        assertEquals(
            listOf(ClipOutboxHealth.State.DESTROYED, ClipOutboxHealth.State.OK),
            seen,
        )

        ClipOutboxHealth.unregister(sink)
    }

    // --- the Report's own reading --------------------------------------------

    @Test
    fun cannotDrainIsTHEReadingAndItIsNotKeptGreaterThanZero() {
        // 🔴 The predicate this whole object folds, pinned at the source. A
        // Report that left work behind is not a Report that failed, and
        // conflating them is the crying-wolf failure in one expression.
        assertFalse(
            "an empty queue reads as a failed drain",
            ClipFlusher.Report().cannotDrain,
        )
        assertFalse(
            "a MAX_PER_FLUSH ceiling hit reads as a failed drain, so every " +
                "large queue would raise a fault while draining normally",
            ClipFlusher.Report(stored = 25, kept = 175).cannotDrain,
        )
        assertFalse(
            "a pass that stored some and dropped one reads as a failed drain",
            ClipFlusher.Report(stored = 3, dropped = 1).cannotDrain,
        )
        assertTrue(
            "an unreachable store does NOT read as a failed drain — which is " +
                "the 401-forever case this exists for",
            ClipFlusher.Report(kept = 4, unreachable = true).cannotDrain,
        )
        assertTrue(
            "an unreadable outbox file does NOT read as a failed drain",
            ClipFlusher.Report(failure = "the outbox file could not be read").cannotDrain,
        )
    }

    @Test
    fun destroyedIsITSOWNReadingAndIsNotFoldedIntoCannotDrain() {
        // 🔴 The two predicates are deliberately separate — see
        // ClipFlusher.Report.destroyed. `cannotDrain` means "the queue did not
        // move"; `destroyed` means "text that existed no longer does". They
        // have different remedies and different labels on the bubble, and
        // folding them would make the assertion two lines above
        // (`stored = 3, dropped = 1` is not a failed drain) assert the
        // opposite of what it says.
        assertFalse(
            "an ordinary successful pass reads as having destroyed something",
            ClipFlusher.Report(stored = 3).destroyed,
        )
        assertTrue(
            "a pass that dropped a capture does NOT read as destructive, " +
                "which is the defect: `dropped` had no consumer in the tree",
            ClipFlusher.Report(stored = 3, dropped = 1).destroyed,
        )
        assertFalse(
            "a dropping pass reads as cannotDrain, which would merge data " +
                "loss into the stuck-queue label",
            ClipFlusher.Report(stored = 3, dropped = 1).cannotDrain,
        )
        assertFalse(
            "an unreachable pass reads as destructive — it is the opposite, " +
                "the capture is KEPT",
            ClipFlusher.Report(kept = 4, unreachable = true).destroyed,
        )
    }
}
