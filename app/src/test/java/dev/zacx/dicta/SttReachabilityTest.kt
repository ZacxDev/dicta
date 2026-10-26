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
        val window = SttReachability.FRESHNESS_WINDOW_MS

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
        clock += SttReachability.FRESHNESS_WINDOW_MS * 3 + 7L
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
    fun theCadenceIsOneConstantInterval() {
        val r = owner()
        assertEquals(
            "🔴 the cadence is the ordinary interval and stays there. A " +
                "failure-streak backoff to ~5 min stood here until 2026-10-25 " +
                "and was deleted by operator disposition: the settled " +
                "requirement is CONTINUOUS background checking, so a run of " +
                "failures must not stretch the gap between probes. The battery " +
                "argument is answered by BubbleService probing only while the " +
                "overlay exists and the screen is interactive — see " +
                "BubbleUnreachableWarningTest.noProbeRunsWhileTheScreenIsOff.",
            60_000L,
            SttReachability.PROBE_INTERVAL_MS,
        )

        // Behavioural half: a long run of failures must not move the window
        // the verdict reads, which is the only thing a cadence change here
        // could still be observed through from inside this class.
        repeat(7) { r.record(false) }
        clock += SttReachability.PROBE_INTERVAL_MS
        assertEquals(
            "seven consecutive failures and one ordinary interval later, the " +
                "cached failure is still current and must WARN — the freshness " +
                "window does not widen with a failure streak any more, because " +
                "the cadence it is derived from no longer narrows or widens",
            SttReachability.Verdict.WARN,
            r.verdict(),
        )
    }

    @Test
    fun theFreshnessWindowIsTwoProbeIntervals() {
        assertEquals(
            "a result is current for one interval plus one interval of grace: " +
                "it stays current until the schedule that produced it has had a " +
                "chance to produce a successor and failed to. 🔴 Literals on " +
                "both sides — written as an expression over the constants this " +
                "would be true for every value and would pin nothing.",
            120_000L,
            SttReachability.FRESHNESS_WINDOW_MS,
        )
        assertEquals(
            "and it must be exactly twice the cadence, so bumping the cadence " +
                "carries the window with it",
            2 * SttReachability.PROBE_INTERVAL_MS,
            SttReachability.FRESHNESS_WINDOW_MS,
        )
    }

    @Test
    fun aFailureIsStillCurrentOneCadenceLater() {
        val r = owner()
        r.record(false)

        // One ordinary interval on, which is where the next probe is due.
        clock += SttReachability.PROBE_INTERVAL_MS
        assertEquals(
            "a failure must still WARN one full cadence after it was recorded. " +
                "This is the behavioural half of the one interval of grace: " +
                "with the window pinned at a single interval this reads PROCEED " +
                "and the feature is inert for half of every outage, because the " +
                "cache would expire at the exact moment its successor is due.",
            SttReachability.Verdict.WARN,
            r.verdict(),
        )
    }
}
