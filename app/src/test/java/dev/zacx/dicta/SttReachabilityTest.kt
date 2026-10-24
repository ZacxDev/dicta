package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SttReachability]'s verdict, cache and schedule.
 *
 * 🔴 **The fail-open branches are the regression coverage here, not the
 * warning.** `TranscriptionCoordinator` already handles an unreachable server
 * after a recording — fast retries, the on-device transcript, then a
 * `/healthz` recovery poll that replaces the text — so a gate that blocked, or
 * that warned on a guess, would trade a working degraded path for nothing. A
 * suite that only checked "a failure warns" would pass with every one of those
 * branches inverted.
 *
 * The clock is injected throughout. Nothing here sleeps: a staleness rule
 * asserted with `Thread.sleep` is a test of the sleep.
 */
class SttReachabilityTest {

    private var clock = 1_000_000L
    private fun owner() = SttReachability(now = { clock })

    /** Records one definite result at the current clock. */
    private fun SttReachability.record(reachable: Boolean) {
        val token = requireNotNull(beginProbe()) { "no probe claim available" }
        onProbeResult(token, reachable)
    }

    // --- fail open ------------------------------------------------------------

    @Test
    fun neverProbedProceeds() {
        val r = owner()
        assertFalse("a fresh owner must hold no knowledge", r.hasKnowledge())
        assertEquals(
            "an owner that has never probed must PROCEED. This is the state " +
                "every service start begins in, and the state forget() " +
                "returns to — warning here would warn on a guess.",
            SttReachability.Verdict.PROCEED,
            r.verdict(),
        )
    }

    @Test
    fun aProbeInFlightProceedsEvenOverAFreshDefiniteFailure() {
        val r = owner()
        r.record(false)
        assertEquals(
            "precondition: a fresh definite failure must warn, or the " +
                "in-flight assertion below is vacuous",
            SttReachability.Verdict.WARN,
            r.verdict(),
        )

        assertNotNull("a second probe must be claimable once the first is done", r.beginProbe())
        assertTrue(r.isProbeInFlight())
        assertEquals(
            "a probe in flight must PROCEED even though the cached result is " +
                "a fresh definite failure: current knowledge is being revised, " +
                "and a tap must never block waiting on a network call.",
            SttReachability.Verdict.PROCEED,
            r.verdict(),
        )
    }

    @Test
    fun aDefiniteFailureOlderThanTheFreshnessWindowProceeds() {
        val r = owner()
        r.record(false)
        val window = r.freshnessWindowMs()

        // Just inside: still current. Deliberately not a round fraction of the
        // window, so a mutant that scales or halves it is not landed on by
        // accident.
        clock += window - 977L
        assertEquals(
            "a definite failure younger than the freshness window is current " +
                "and must WARN",
            SttReachability.Verdict.WARN,
            r.verdict(),
        )

        // Exactly at the boundary: the comparison is >=, so this is stale.
        clock = 1_000_000L + window
        assertEquals(
            "a result exactly as old as the freshness window must read as " +
                "UNKNOWN and PROCEED — the boundary belongs to stale, because " +
                "by that moment the schedule should already have replaced it",
            SttReachability.Verdict.PROCEED,
            r.verdict(),
        )

        clock += 1_111L
        assertEquals(
            "a definite failure older than the freshness window must PROCEED",
            SttReachability.Verdict.PROCEED,
            r.verdict(),
        )
    }

    @Test
    fun aReachableResultProceedsFreshOrStale() {
        val r = owner()
        r.record(true)
        assertEquals(SttReachability.Verdict.PROCEED, r.verdict())
        clock += r.freshnessWindowMs() * 3 + 7L
        assertEquals(
            "a stale SUCCESS is still a PROCEED. There is no path on which " +
                "reachability expiring is a reason to warn — only a fresh " +
                "definite failure warns.",
            SttReachability.Verdict.PROCEED,
            r.verdict(),
        )
    }

    @Test
    fun forgetDropsKnowledgeSoTheVerdictProceeds() {
        val r = owner()
        r.record(false)
        assertEquals(SttReachability.Verdict.WARN, r.verdict())

        r.forget()

        assertFalse("forget() must leave no knowledge behind", r.hasKnowledge())
        assertEquals(
            "forget() is what the screen going off calls. A result taken " +
                "before the phone slept says nothing about a tunnel that drops " +
                "while it sleeps, so the verdict must go back to PROCEED " +
                "immediately rather than ageing out on a clock that keeps " +
                "running while the probes do not.",
            SttReachability.Verdict.PROCEED,
            r.verdict(),
        )
    }

    @Test
    fun aResultFromARetiredGenerationIsDiscardedButStillReleasesTheClaim() {
        val r = owner()
        val token = requireNotNull(r.beginProbe())
        r.forget()

        r.onProbeResult(token, reachable = false)

        assertFalse(
            "the in-flight claim must be released even for a retired " +
                "generation, or beginProbe() refuses forever and the probe " +
                "schedule wedges",
            r.isProbeInFlight(),
        )
        assertFalse(
            "a probe that was started before the screen went off must not " +
                "repopulate the cache it was just cleared out of — that " +
                "landing-after-sleep result is the false negative the screen " +
                "gating exists to avoid",
            r.hasKnowledge(),
        )
        assertEquals(SttReachability.Verdict.PROCEED, r.verdict())
    }

    @Test
    fun abandoningAProbeReleasesTheClaimAndRecordsNothing() {
        val r = owner()
        r.record(true)
        val before = r.verdict()

        assertNotNull(r.beginProbe())
        r.abandonProbe()

        assertFalse("the claim must be released", r.isProbeInFlight())
        assertTrue("the previous knowledge must still be there", r.hasKnowledge())
        assertEquals(
            "abandonProbe() records nothing: it is for a probe that was never " +
                "dispatched, which observed nothing about the server. A false " +
                "recorded here would mint the one input that warns out of a " +
                "purely local condition.",
            before,
            r.verdict(),
        )
        assertEquals(
            "and it must not count as a failure for the backoff either",
            0,
            r.failureStreak(),
        )
    }

    // --- one at a time --------------------------------------------------------

    @Test
    fun onlyOneProbeMayBeInFlight() {
        val r = owner()
        val first = r.beginProbe()
        assertNotNull("the first claim must be granted", first)
        assertNull(
            "a second concurrent probe buys nothing and both would race to " +
                "write the same cache",
            r.beginProbe(),
        )
        r.onProbeResult(first!!, reachable = true)
        assertNotNull("and the claim must be available again afterwards", r.beginProbe())
    }

    // --- the schedule ---------------------------------------------------------

    @Test
    fun theCadenceBacksOffOnlyAfterTheDeclaredRunOfFailures() {
        val r = owner()
        assertEquals(
            "an owner with no failures runs at the ordinary cadence",
            SttReachability.PROBE_INTERVAL_MS,
            r.currentIntervalMs(),
        )
        for (i in 1 until SttReachability.BACKOFF_AFTER_FAILURES) {
            r.record(false)
            assertEquals(
                "failure $i of ${SttReachability.BACKOFF_AFTER_FAILURES} must " +
                    "NOT back the cadence off. One or two failures is what a " +
                    "phone crossing between Wi-Fi and cellular produces, and " +
                    "backing off on it would leave the cache stale — and the " +
                    "warning therefore unavailable — for the whole of the next " +
                    "backoff interval.",
                SttReachability.PROBE_INTERVAL_MS,
                r.currentIntervalMs(),
            )
        }
        r.record(false)
        assertEquals(
            "${SttReachability.BACKOFF_AFTER_FAILURES} consecutive failures " +
                "must back the cadence off",
            SttReachability.BACKOFF_INTERVAL_MS,
            r.currentIntervalMs(),
        )
        assertEquals(
            "nextProbeDelayMs must be the cadence in effect",
            SttReachability.BACKOFF_INTERVAL_MS,
            r.nextProbeDelayMs(),
        )
    }

    @Test
    fun aSuccessResetsTheStreakAndTheCadence() {
        val r = owner()
        repeat(SttReachability.BACKOFF_AFTER_FAILURES + 2) { r.record(false) }
        assertEquals(
            SttReachability.BACKOFF_INTERVAL_MS,
            r.currentIntervalMs(),
        )

        r.record(true)

        assertEquals("a success resets the streak", 0, r.failureStreak())
        assertEquals(
            "and therefore the cadence — a server that has come back must be " +
                "probed at the ordinary rate again",
            SttReachability.PROBE_INTERVAL_MS,
            r.currentIntervalMs(),
        )
    }

    @Test
    fun forgetLeavesTheFailureStreakAlone() {
        val r = owner()
        repeat(SttReachability.BACKOFF_AFTER_FAILURES) { r.record(false) }

        r.forget()

        assertEquals(
            "the streak is a claim about the SERVER; forget() is a claim about " +
                "our own knowledge. Resetting it on every screen-off would put " +
                "the probe back on the ordinary cadence against a host that " +
                "has already refused ${SttReachability.BACKOFF_AFTER_FAILURES} " +
                "times in a row.",
            SttReachability.BACKOFF_AFTER_FAILURES,
            r.failureStreak(),
        )
        assertEquals(
            SttReachability.BACKOFF_INTERVAL_MS,
            r.currentIntervalMs(),
        )
    }

    @Test
    fun theFreshnessWindowTracksTheCadenceInEffect() {
        val r = owner()
        assertEquals(
            "at the ordinary cadence a result is current for one interval plus " +
                "one interval of grace: it stays current until the schedule " +
                "that produced it has had a chance to produce a successor and " +
                "failed to",
            SttReachability.PROBE_INTERVAL_MS + SttReachability.PROBE_INTERVAL_MS,
            r.freshnessWindowMs(),
        )

        repeat(SttReachability.BACKOFF_AFTER_FAILURES) { r.record(false) }

        assertEquals(
            "once backed off, the window has to widen with the cadence. Pinned " +
                "at the ordinary interval it would expire between every pair " +
                "of backed-off probes, which makes WARN unreachable during " +
                "exactly the sustained outage the warning is for.",
            SttReachability.BACKOFF_INTERVAL_MS + SttReachability.PROBE_INTERVAL_MS,
            r.freshnessWindowMs(),
        )
    }

    @Test
    fun aBackedOffFailureIsStillCurrentAnIntervalLater() {
        val r = owner()
        repeat(SttReachability.BACKOFF_AFTER_FAILURES) { r.record(false) }

        // One backed-off interval on, which is where the next probe is due.
        clock += SttReachability.BACKOFF_INTERVAL_MS
        assertEquals(
            "a backed-off cache must still WARN one backoff interval after its " +
                "last result. This is the behavioural half of the window " +
                "widening: with the window pinned at the ordinary interval " +
                "this reads PROCEED and the feature is inert during an outage.",
            SttReachability.Verdict.WARN,
            r.verdict(),
        )
    }

    @Test
    fun theDeclaredConstantsAreTheOnesTheScheduleUses() {
        // 🔴 Literals, not expressions over the constants: the point is that
        // the cadence the operator asked for ("every ~60 s, backing off to
        // ~5 min") is what ships. An assertion written as
        // `SttReachability.PROBE_INTERVAL_MS` against itself is true for every
        // value and pins nothing.
        assertEquals(60_000L, SttReachability.PROBE_INTERVAL_MS)
        assertEquals(300_000L, SttReachability.BACKOFF_INTERVAL_MS)
        assertEquals(3, SttReachability.BACKOFF_AFTER_FAILURES)
    }
}
