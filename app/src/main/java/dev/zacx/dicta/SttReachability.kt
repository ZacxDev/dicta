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
 * are all here, and [SttReachabilityLedgerTest] pins that exactly one site in
 * the tree reads the verdict.
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
 *  * the last definite result is older than [FRESHNESS_WINDOW_MS];
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
 * ## Freshness is one grace interval on top of the cadence
 *
 * [FRESHNESS_WINDOW_MS] is two [PROBE_INTERVAL_MS]: a result is current until
 * the schedule that produced it has had a chance to produce a successor and
 * failed to.
 *
 * 🔴 It is a **constant**, and that is the whole of it. This used to be a
 * computed property, `currentIntervalMs() + PROBE_INTERVAL_MS`, because the
 * cadence it read was itself dynamic — a failure streak backed the probe off
 * to ~5 min, and a window pinned at 60 s would then have gone stale between
 * every pair of backed-off probes, making the warning unreachable during
 * exactly the sustained outage it is for. That backoff was **deleted on
 * 2026-10-25** by operator disposition: the settled requirement is continuous
 * background checking, stretching the cadence to ~5 min is a deviation from
 * it, and the screen-on / bubble-up gating in [BubbleService] is where the
 * battery saving comes from. With one cadence there is one window, so the
 * computation had nothing left to compute.
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

    /**
     * For the tests. No `app/src/main` caller — `BubbleService` schedules from
     * [beginProbe]'s null return, not from this.
     */
    @Synchronized
    fun isProbeInFlight(): Boolean = inFlight

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
     * configured.
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
     * A probe in flight is a PROCEED — see the first line of the body. That
     * much is true of this function as written.
     *
     * 🔴 **RETRACTION (2026-10-25), and nothing has replaced it.** This kdoc
     * used to open with an ordering instruction — *"Read this **before**
     * starting the tap's own probe, never after"* — justified by two claims
     * that are both false:
     *
     *  * *"Every tap also kicks a probe off."* No tap starts a probe.
     *    [beginProbe] has exactly one call site in `app/src/main`, inside
     *    `BubbleService.runProbe`, which is reached only from the posted
     *    `probeTick` chain. There is no "tap's own probe" for this to be read
     *    before or after.
     *  * *"(that is the operator's 'again on each tap')."* The operator
     *    confirmed on 2026-10-24 that he never asked for a per-tap probe. The
     *    settled requirement is continuous background checking, which is what
     *    the `probeTick` chain already does.
     *
     * So the ordering constraint **has no remaining reason**, and that is the
     * finding rather than a gap waiting for a better rationale: with no
     * per-tap probe the sequencing it demanded cannot be got wrong. What is
     * left is an invariant guard against a hazard that **cannot currently
     * occur** — if a future change ever does start a probe from the tap path,
     * `inFlight` would make [Verdict.WARN] unreachable from that tap.
     *
     * 🔴 This comment is the FIRST writing after that retraction. Five
     * successive rationales for one guard in this project have each been
     * retracted by the next audit round, so do not supply a sixth: "the
     * constraint has no current purpose" is the measured answer. If you find
     * one, it must come with the call site that creates it.
     */
    @Synchronized
    fun verdict(): Verdict {
        if (inFlight) return Verdict.PROCEED
        val last = lastReachable ?: return Verdict.PROCEED
        if (last) return Verdict.PROCEED
        if (now() - lastResultAtMs >= FRESHNESS_WINDOW_MS) return Verdict.PROCEED
        return Verdict.WARN
    }

    companion object {
        /**
         * The cadence while the overlay exists and the screen is interactive —
         * the operator's "every ~60 s", and the only cadence there is.
         *
         * 🔴 There is deliberately **no backoff**. A failure-streak backoff to
         * ~5 min stood here until 2026-10-25 and was deleted by operator
         * disposition: the settled requirement is continuous background
         * checking, so stretching the cadence on repeated failure is a
         * deviation from the requirement rather than an optimisation of it, and
         * it had never detected anything — by construction, since the only
         * thing it changed was how long the probe waited. The battery argument
         * it was carrying is already answered structurally, by [BubbleService]
         * probing only while the overlay exists and the screen is interactive.
         * Do not reintroduce one without a call site and a measurement.
         */
        const val PROBE_INTERVAL_MS: Long = 60_000L

        /**
         * How long a definite result counts as current: one cadence, plus one
         * cadence of grace. See the class kdoc for why it is a constant.
         */
        const val FRESHNESS_WINDOW_MS: Long = 2 * PROBE_INTERVAL_MS
    }
}
