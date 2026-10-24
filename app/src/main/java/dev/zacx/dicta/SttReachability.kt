package dev.zacx.dicta

/**
 * The **one owner** of "can the transcription server be reached right now".
 *
 * Pure, with no Android types and an injected clock, for the same reason
 * [BubbleDictation] is: everything here is a decision, and the platform parts
 * — the socket, the executor, the screen state, the window — live in
 * [BubbleService] and are device evidence only.
 *
 * ## Why an owner rather than a check at the call site
 *
 * [BubbleEnabled] was consolidated onto a single predicate after three
 * hand-copied readings of the bubble's enabled flag disagreed. A reachability
 * reading has the same shape and a worse failure: it is a *cached* fact with a
 * timestamp, so a second copy of the staleness rule is a second answer to "is
 * this still true". So the cache, its age, the probe schedule and the verdict
 * are all here, and [SttReachabilityLedgerTest] pins who may touch them.
 *
 * ## 🔴 The verdict FAILS OPEN, and that is the whole contract
 *
 * [TranscriptionCoordinator] already handles an unreachable server *after* a
 * recording: a fast retry ladder, then the on-device transcript, then a
 * `/healthz` recovery poll that replaces the text if the server comes back. So
 * with the mesh down the user still gets a dictation. A pre-flight check that
 * blocked, or that warned when it did not know, would trade that working
 * degraded path for nothing.
 *
 * [verdict] therefore returns [Verdict.WARN] for exactly one input — a
 * **fresh, definite failure** — and [Verdict.PROCEED] for every other,
 * including every form of "unknown":
 *
 *  * never probed, or the cache was cleared ([forget]);
 *  * a probe in flight, so current knowledge is being revised;
 *  * the last definite result is older than [freshnessWindowMs];
 *  * the last result was a success.
 *
 * A probe that **threw** records nothing at all: [BubbleService] routes it to
 * [abandonProbe], so whatever was known before stands and a never-probed owner
 * stays never-probed. `SttHttp.healthz` already maps every transport failure to
 * `false` and does not throw, so an exception escaping the probe is a fault in
 * this process rather than an observation of the server — and recording
 * `false` for it would mint the one input that warns out of a local bug. What a
 * thrown probe must never do is leave `inFlight` set forever, which would wedge
 * the verdict on PROCEED and stop the schedule; [abandonProbe] and
 * [onProbeResult] both clear it unconditionally.
 *
 * ## Freshness is tied to the schedule that produces the results
 *
 * [freshnessWindowMs] is [currentIntervalMs] plus one [PROBE_INTERVAL_MS] of
 * grace: a result is current until the schedule that produced it has had a
 * chance to produce a successor and failed to. A fixed window would be wrong
 * in one direction or the other, because [currentIntervalMs] backs off after
 * [BACKOFF_AFTER_FAILURES] consecutive failures — pin it at 60 s and a backed
 * off cache goes stale between every pair of probes, so the warning becomes
 * unreachable during exactly the sustained outage it is for.
 *
 * 🔴 Time is the backstop here, not the main mechanism. The case the operator
 * named — the mesh tunnel dropping while the phone sleeps, so a probe then
 * reports a false negative — is handled structurally instead: [BubbleService]
 * calls [forget] when the screen goes off, which drops the cache to "never
 * probed" and discards any in-flight probe's result via [generation]. A clock
 * window cannot express that, because a sleeping phone's clock keeps running
 * while its probes do not.
 */
class SttReachability(
    /** Injected so staleness is asserted rather than slept through. */
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /** What a tap that would start a recording should do. */
    enum class Verdict {
        /** Start the recording. The default, and every uncertain case. */
        PROCEED,

        /** Show the warning and let the user decide. */
        WARN,
    }

    /**
     * The last definite result, or null for "no knowledge" — never probed, or
     * [forget]ten. Null is a real state and is a PROCEED.
     */
    private var lastReachable: Boolean? = null

    /** When [lastReachable] was recorded. Meaningless while it is null. */
    private var lastResultAtMs: Long = 0L

    private var inFlight: Boolean = false

    /**
     * Consecutive failing probes, which is what [currentIntervalMs] backs off
     * on. A success resets it; [forget] does **not**, because the streak is a
     * claim about the *server* and clearing the cache is a claim about our own
     * knowledge. Resetting it on every screen-off would put the probe back on
     * the 60 s cadence against a host that has refused a hundred times.
     */
    private var consecutiveFailures: Int = 0

    /**
     * Bumped by [forget], and carried by each probe so a result that belongs
     * to a discarded generation can be dropped.
     *
     * 🔴 A result is dropped rather than merely overwritten. The probe that is
     * in flight when the screen goes off was started while the phone was
     * awake but can land after it has slept, and that is precisely the false
     * negative the screen gating exists to avoid — so it must not be allowed
     * to repopulate the cache it was just cleared out of.
     */
    private var generation: Long = 0L

    /** For the tests and for [BubbleService]'s scheduling. */
    @Synchronized
    fun isProbeInFlight(): Boolean = inFlight

    @Synchronized
    fun failureStreak(): Int = consecutiveFailures

    /** True once a definite result has been recorded and not cleared. */
    @Synchronized
    fun hasKnowledge(): Boolean = lastReachable != null

    /**
     * Claims the right to run a probe.
     *
     * @return a token to hand back to [onProbeResult], or null when a probe is
     *   already in flight — one at a time, because a second concurrent probe
     *   buys nothing and both would be racing to write the same cache.
     */
    @Synchronized
    fun beginProbe(): Long? {
        if (inFlight) return null
        inFlight = true
        return generation
    }

    /**
     * Records one probe's outcome.
     *
     * @param token the value [beginProbe] returned. A token from a generation
     *   [forget] has since retired clears [inFlight] — so the schedule is not
     *   wedged — and records nothing.
     */
    @Synchronized
    fun onProbeResult(token: Long, reachable: Boolean) {
        inFlight = false
        if (token != generation) return
        lastReachable = reachable
        lastResultAtMs = now()
        consecutiveFailures = if (reachable) 0 else consecutiveFailures + 1
    }

    /**
     * Releases a probe claim **without recording anything**.
     *
     * 🔴 Not `onProbeResult(token, false)`. The caller for this is a probe
     * that was never dispatched — the executor refused it — so nothing about
     * the server was observed, and a `false` would mint the one input
     * [verdict] warns on out of a purely local condition. The claim still has
     * to be released or [beginProbe] refuses forever and the schedule wedges.
     *
     * Takes no token, and that is correct rather than lax: [beginProbe]
     * refuses while one probe is in flight, so at most one claim exists at a
     * time and it is necessarily the caller's. A token check here would have
     * to decide what to do with a retired generation's claim, and the only
     * answer that does not wedge [inFlight] is to clear it anyway — which is
     * this. [onProbeResult] clears it unconditionally for the same reason, and
     * uses its token only to decide whether to *record*.
     */
    @Synchronized
    fun abandonProbe() {
        inFlight = false
    }

    /**
     * Drops all knowledge: the next [verdict] is PROCEED until a probe of the
     * new generation reports.
     *
     * Called when the screen goes off and when the remote path is not
     * configured. Leaves [consecutiveFailures] alone — see that field.
     */
    @Synchronized
    fun forget() {
        generation++
        lastReachable = null
        lastResultAtMs = 0L
        // Not cleared: the in-flight probe's RESULT is what is being
        // discarded, by generation, and clearing this instead would let a
        // second probe start alongside the one still running.
    }

    /**
     * The decision for a tap that would start a recording.
     *
     * 🔴 Read this **before** starting the tap's own probe, never after. Every
     * tap also kicks a probe off (that is the operator's "again on each tap"),
     * and a probe in flight is a PROCEED — so asking afterwards would make
     * [Verdict.WARN] unreachable from a tap and the whole gate inert.
     */
    @Synchronized
    fun verdict(): Verdict {
        if (inFlight) return Verdict.PROCEED
        val last = lastReachable ?: return Verdict.PROCEED
        if (last) return Verdict.PROCEED
        if (now() - lastResultAtMs >= freshnessWindowMsLocked()) return Verdict.PROCEED
        return Verdict.WARN
    }

    /**
     * How long until the next probe is due, from the moment this is called.
     *
     * Not a function of when the last probe ran: [BubbleService] schedules the
     * next tick as each result lands, so "from now" is what the caller needs.
     */
    @Synchronized
    fun nextProbeDelayMs(): Long = currentIntervalMsLocked()

    /** The cadence in effect: [PROBE_INTERVAL_MS], or the backed-off one. */
    @Synchronized
    fun currentIntervalMs(): Long = currentIntervalMsLocked()

    /** How long a definite result counts as current. See the class kdoc. */
    @Synchronized
    fun freshnessWindowMs(): Long = freshnessWindowMsLocked()

    private fun currentIntervalMsLocked(): Long =
        if (consecutiveFailures >= BACKOFF_AFTER_FAILURES) {
            BACKOFF_INTERVAL_MS
        } else {
            PROBE_INTERVAL_MS
        }

    private fun freshnessWindowMsLocked(): Long =
        currentIntervalMsLocked() + PROBE_INTERVAL_MS

    companion object {
        /**
         * The ordinary cadence while the overlay exists and the screen is
         * interactive — the operator's "every ~60 s".
         */
        const val PROBE_INTERVAL_MS: Long = 60_000L

        /** The cadence after [BACKOFF_AFTER_FAILURES] failures: ~5 min. */
        const val BACKOFF_INTERVAL_MS: Long = 300_000L

        /**
         * How many consecutive failures back the cadence off.
         *
         * Three rather than one: a single failure is what a roaming phone
         * produces crossing between Wi-Fi and cellular, and backing off to
         * five minutes on it would mean the cache is stale — and the warning
         * therefore unavailable — for the whole of the next five minutes after
         * one blip.
         */
        const val BACKOFF_AFTER_FAILURES: Int = 3
    }
}
