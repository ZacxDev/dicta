package dev.zacx.dicta

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Whether the capture queue is **stuck** — and the channel that puts that on
 * the bubble.
 *
 * ## 🔴 The defect this closes, which was silent in every direction
 *
 * [ClipFlusher.Report] was built with the failure in it and **discarded at both
 * production call sites** — `BubbleService.defaultClipFlush` and
 * `ClipPickerActivity.defaultFlush`. Paste a typo'd clip token into Settings
 * and the consequence was: every capture lands in the outbox, every toast says
 * "queued", every flush gets a 401 → [ClipPost.Unreachable] → the row is KEPT
 * (correctly — `ClipHttp.PERMANENT_STATUSES` excludes 401 so a five-second
 * repair cannot destroy captures), **forever, with nothing on screen**. The
 * picker meanwhile fetched the server's own empty list and rendered
 * `@string/clip_empty_none`, "nothing stored yet" — so the one surface the
 * operator would open to check reported the opposite of the truth.
 *
 * Operator decision, recorded on PR #48: *a stuck outbox MUST be visible on the
 * phone*. Deleting [ClipFlusher.Report] as unused was the other candidate and
 * it was **rejected**.
 *
 * ## 🔴 "Has pending work" is NOT "cannot drain", and conflating them is the
 * failure mode of this kind of indicator
 *
 * A queue with rows in it is the ordinary state: a capture is appended
 * synchronously and the POST is **always** deferred to the next tick (see
 * [ClipOutbox]'s kdoc — that is the design, not a fallback), so a pending row
 * one second after a capture is the feature working. An indicator that fired on
 * `kept > 0` would be lit after every single capture, and an indicator that is
 * always on is one nobody reads.
 *
 * So two things are required, and both are deliberate:
 *
 *  * the pass must have **failed** rather than merely left work —
 *    [ClipFlusher.Report.cannotDrain], which is `unreachable || failure != null`
 *    and is explicitly not `kept > 0`; and
 *  * it must have failed [STUCK_AFTER_FAILURES] times **in a row**. The mesh
 *    drops for a minute at a time and the tick is a minute, so one failure
 *    carries no information.
 *
 * Any pass that is not a failure resets the count to zero — including a
 * [ClipFlusher.MAX_PER_FLUSH] ceiling hit, which leaves `kept > 0` and is a
 * drain that is working.
 *
 * ## Why an object, and why this shape
 *
 * [OverlayVisibility]'s shape exactly, and for the same reason: the two writers
 * ([BubbleService]'s minute tick and [ClipPickerActivity]'s flush-on-open) and
 * the one reader ([BubbleService]'s clip button) are in one process but have no
 * reference to each other, and a static `Service` reference would leak a
 * destroyed component and could not be unit-tested. Sinks are notified **only
 * when the verdict changes**, so a phone that has been off the mesh for an hour
 * is one window update rather than sixty.
 *
 * 🔴 Deliberately NOT recorded inside [ClipFlusher.flush] itself, even though
 * that would make it impossible to discard. `ClipFlusher`'s own kdoc draws the
 * line: it holds the DECISIONS and is Android-free and allocation-free of
 * global state, so its tests are about the rules. A process-wide singleton
 * write inside it would make every one of those tests order-dependent on every
 * other. The call sites record, and
 * `ClipSeamLedgerTest.everySiteThatDrainsTheOutboxCONSUMESItsReport` is what
 * makes a site that forgets a red test rather than a review catch.
 *
 * ## 🔴 A DESTROYED capture is a third state, not a louder second one
 *
 * [ClipFlusher.Report.dropped] counts captures that left the outbox without
 * reaching the store. The outbox is the only copy, so a drop is **permanent
 * data loss** — strictly worse than a stuck queue, which still holds the text.
 *
 * Until this branch `dropped` had **no consumer anywhere in the tree**, and the
 * consequence was the exact inversion of what an indicator is for: a dropping
 * pass is not a [ClipFlusher.Report.cannotDrain] pass, so it fell into the
 * `else` arm below and **reset the fault counter to zero**. Ten
 * capture-destroying flushes left this object reporting healthy while ten
 * harmless 401 flushes lit it.
 *
 * So [State] has three values, and the two faults are not the same fault:
 *
 *  * [State.STUCK] — the queue cannot drain, the captures are still on the
 *    phone, and fixing the mesh or the setting drains them. Needs
 *    [STUCK_AFTER_FAILURES] consecutive failures, because one carries no
 *    information.
 *  * [State.DESTROYED] — at least one capture is **gone**. Raised on the
 *    FIRST occurrence: there is no "it may recover next minute" reading of a
 *    row that no longer exists, so a threshold would only delay telling the
 *    operator about something already irreversible. It outranks [State.STUCK]
 *    and it LATCHES — a later successful drain says nothing about the captures
 *    that were destroyed. [acknowledgeDestroyed] is the one thing that clears
 *    it, called when the operator opens the picker, i.e. when they have been
 *    shown it and are looking at the feature. A fault that no act of the
 *    operator's can clear is the permanently-red gate this repository has a
 *    rule about.
 *
 * A drop also **does not reset** the stuck counter: a pass that destroyed a
 * capture is not evidence that the queue is healthy.
 *
 * ## ⚠ What this cannot see, stated rather than discovered
 *
 * A queue on a handset with **no clip token configured at all** never produces
 * a [ClipFlusher.Report] — both call sites return before building a flusher, by
 * design (a flush then would be a 401 per queued row per minute). So the
 * genuinely-no-token case is invisible here. That is the ordinary state of a
 * fresh install, where the queue is also empty; and a missing token is visible
 * on the settings screen, which is where it is fixed.
 *
 * 🔴 **That paragraph used to cover one more case than it was entitled to.** It
 * dismissed the blind spot partly because *"the queue is also empty"* — which
 * is **false** for a token that is STORED but will not decrypt. After a
 * keystore invalidation (the lock screen removed, a restore onto a new device)
 * `TokenStore.token()` returns null, `hasToken` is false, and both drain sites
 * returned before building a flusher — on a handset where the operator has been
 * capturing for days and the queue is full. That case now reaches here: see
 * [ClipSettings.tokenIsUnusable] and the two call sites, which record a
 * [ClipFlusher.Report] with a failure rather than returning silently.
 */
object ClipOutboxHealth {

    /**
     * What a surface should render. Ordered by severity; [DESTROYED] wins.
     *
     * 🔴 **Named `State`/[state] and NOT `Verdict`/`verdict()`, deliberately
     * and permanently.** This type was written as `Verdict` with a `verdict()`
     * reader and CI went red on
     * `SttReachabilityLedgerTest.exactlyOneSiteInTheWholeTreeReadsTheVerdict`,
     * which counts `.verdict(` call sites **across the whole of
     * `app/src/main`** and asserts exactly one. That ledger keys on the dot and
     * the bare word, receiver-spelling-insensitive **by construction** — its
     * own kdoc records that a ledger keyed on an identifier was once blind to
     * the single file it existed to protect — so the word `verdict` is
     * effectively RESERVED tree-wide for `SttReachability`.
     *
     * It was a name collision and not a second reader: `SttReachability`'s
     * verdict is still read in exactly one place, `BubbleService`'s
     * `shouldWarnUnreachable`, and nothing here touches reachability at all.
     * The fix was therefore to rename **this** type, never to widen, bump or
     * exempt anything in that ledger — [ClipOutbox]'s own kdoc already said of
     * those two ledgers, *"those are tests to obey, not to widen"*. Renaming a
     * brand-new internal type costs nothing; weakening a guard that caught a
     * real consolidation defect once costs the next person the defect.
     *
     * ⚠ So: do not "tidy" this back to `Verdict`. It compiles, and CI goes red
     * for a reason that reads as unrelated to whatever you were doing.
     */
    enum class State {
        /** Draining, or merely busy. Nothing to say. */
        OK,

        /** [STUCK_AFTER_FAILURES] consecutive failed drains. Text is safe. */
        STUCK,

        /** At least one capture was dropped and no longer exists. */
        DESTROYED,
    }

    /**
     * How many consecutive failed drains make the queue *stuck*.
     *
     * 🔴 Three, not one. `BubbleService.CLIP_FLUSH_INTERVAL_MS` is a minute, so
     * this is "the queue has not moved for about three minutes" — long enough
     * that a mesh blip, a sleeping relay or one dropped socket does not raise a
     * fault, short enough that an operator who mistyped a token sees it on the
     * next long press rather than tomorrow.
     *
     * 🔴 Not 1: a single failure is the single most common observation this
     * object will ever make, and treating it as a fault is how the indicator
     * becomes noise and stops being read. Not 10: the picker's flush-on-open
     * also records, and an operator who opens the picker twice has contributed
     * two of the ten without a minute passing.
     */
    const val STUCK_AFTER_FAILURES: Int = 3

    private val sinks = CopyOnWriteArrayList<(State) -> Unit>()

    /** Guarded by this object's monitor. */
    private var consecutiveFailures = 0

    /**
     * Guarded by this object's monitor. **Latched** — see the class kdoc: a
     * later success says nothing about a capture that no longer exists. Only
     * [acknowledgeDestroyed] clears it.
     */
    private var destroyedCaptures = false

    fun register(sink: (State) -> Unit) {
        sinks.add(sink)
    }

    fun unregister(sink: (State) -> Unit) {
        sinks.remove(sink)
    }

    /**
     * Folds one drain's [report] into the verdict.
     *
     * Call this from **every** site that drains the outbox, with the Report
     * that drain produced. Broadcasts only on a change of verdict.
     *
     * 🔴 **NOT `@Synchronized`, and the forward below is why (audit round 1,
     * finding 2).** It was, and the diagnostics forward sat inside the monitor
     * — across `ClipDiagnostics.PrefsStore.write`, which is a **blocking**
     * `SharedPreferences.commit()`. [state] takes the same monitor and runs on
     * the MAIN thread (`BubbleService`'s window build, and the debug receiver's
     * `DIAGNOSE`), so the main thread could block on a worker's disk write.
     * `commit()` is correct for its own stated reason and stays; what was wrong
     * was holding the monitor across it. The fold that actually needs the
     * monitor is [fold], and it touches no IO.
     */
    fun record(report: ClipFlusher.Report) {
        // 🔴 Forwarded rather than recorded at the call sites, so the readout's
        // "last flush" and this object's verdict are computed from the SAME
        // Report and cannot disagree. Every drain site already reports here —
        // `ClipSeamLedgerTest.everySiteThatDrainsTheOutboxCONSUMESItsReport`
        // is what keeps that true — so this adds no site to that ledger.
        //
        // 🔴 OUTSIDE the monitor, deliberately. This persists, and a reader on
        // the main thread must not queue behind it. Ordering is unaffected: the
        // readout's `lastFlush` and the verdict come from this one Report
        // either way, which is the property the forward exists for — it is the
        // same Report object, not a second read of shared state.
        ClipDiagnostics.recordFlush(report)
        fold(report)
    }

    @Synchronized
    private fun fold(report: ClipFlusher.Report) {
        val was = stateUnsynchronized()
        // 🔴 FIRST, and unconditionally: a destroyed capture is the fact that
        // outranks everything else this Report carries.
        if (report.destroyed) destroyedCaptures = true
        if (report.cannotDrain) {
            // Saturating rather than unbounded: the only question asked of this
            // counter is whether it has reached the threshold, and a phone left
            // off the mesh for a month would otherwise count to 40,000.
            if (consecutiveFailures < STUCK_AFTER_FAILURES) consecutiveFailures++
        } else if (!report.destroyed) {
            // 🔴 `&& !report.destroyed`, which is the defect this branch
            // closes at its source. A pass that destroyed a capture is NOT
            // evidence the queue is healthy, and without this clause a run of
            // capture-destroying flushes held the counter at zero forever.
            consecutiveFailures = 0
        }
        val now = stateUnsynchronized()
        if (was != now) broadcast(now)
    }

    /**
     * the state a surface renders.
     *
     * Read at window-build time as well as pushed through [register], because a
     * service that starts while the queue is already stuck must show it on the
     * first long press rather than wait for a change it has already missed.
     */
    @Synchronized
    fun state(): State = stateUnsynchronized()

    /**
     * Clears a latched [State.DESTROYED].
     *
     * 🔴 Called when the operator OPENS the shared clipboard — the one moment
     * they have demonstrably seen the indicator and are looking at the feature.
     * It clears only the latch: a drain in the same session that destroys
     * another capture re-raises it immediately, and a [State.STUCK] queue
     * stays stuck because nothing about it has been repaired.
     */
    @Synchronized
    fun acknowledgeDestroyed() {
        val was = stateUnsynchronized()
        destroyedCaptures = false
        val now = stateUnsynchronized()
        if (was != now) broadcast(now)
    }

    private fun stateUnsynchronized(): State = when {
        destroyedCaptures -> State.DESTROYED
        consecutiveFailures >= STUCK_AFTER_FAILURES -> State.STUCK
        else -> State.OK
    }

    private fun broadcast(verdict: State) {
        // Never let one misbehaving sink stop the others — OverlayVisibility's
        // reason, and here the sink posts to a looper that may be gone.
        for (sink in sinks) {
            try {
                sink(verdict)
            } catch (_: Throwable) {
            }
        }
    }

    /** Test seam only. */
    @Synchronized
    fun clearForTest() {
        sinks.clear()
        consecutiveFailures = 0
        destroyedCaptures = false
    }
}
