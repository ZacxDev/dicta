package dev.zacx.dicta

/**
 * Drains the [ClipOutbox] into the store, and decides what happens to an item
 * the store would not take.
 *
 * ## 🔴 Why this is its own object and not three lines in [BubbleService]
 *
 * The rules below are the whole of the "captures are never lost" claim, and
 * they are policy rather than plumbing: stop at the first unreachable item,
 * drop a permanently-refused one, keep everything else. `BubbleService` is
 * already 2200 lines and cannot be driven in a test without a window, a
 * `WindowManager` shadow and an overlay permission; this can be driven with
 * two fakes. The service keeps the SCHEDULE — which is genuinely its, because
 * the handler chain and the screen receiver live there — and this keeps the
 * DECISIONS.
 *
 * Deliberately Android-free, so its tests are about the rules. (They still need
 * Robolectric, because a real [ClipOutboxStore] goes through `org.json`.)
 *
 * ## 🔴 It stops at the first [ClipPost.Unreachable], it does not skip past it
 *
 * Two reasons, and the second is the one that is easy to miss:
 *
 *  1. If the mesh is down, item 2 will fail exactly as item 1 did, so
 *     continuing is a queue-length number of pointless sockets each holding a
 *     worker thread for the connect timeout.
 *  2. **Order.** [Clip.createdAtMs] is assigned by the store on arrival, so the
 *     sequence in which items are accepted IS the order every other device
 *     sees. Skipping a stuck item and posting the ones behind it would show the
 *     operator their own clipboard history out of sequence, with nothing on
 *     screen explaining why.
 *
 * ## 🔴 There is no reachability precheck, and that is enforced
 *
 * It attempts the POST and keeps the item on failure. It must not read
 * `SttReachability.verdict()` (`exactlyOneSiteInTheWholeTreeReadsTheVerdict`
 * asserts exactly one site in this directory) and must not call `.healthz()`
 * (`onlyTheDeclaredFilesPerformAHealthProbe` pins three named files). Both
 * ledgers are **red today** if this file reaches for either. They are also the
 * right answer on the merits: the clip store's `/healthz` is a *schema* probe
 * that answers 200 with its database file deleted, so a green probe followed by
 * a dropped capture is strictly worse than no probe.
 */
class ClipFlusher(
    private val outbox: ClipOutbox,
    /** This handset's attribution, from [ClipDevice]. */
    private val device: String,
    /** The POST, injected so a test needs no socket. */
    private val post: (body: String, device: String) -> ClipPost,
) {

    /**
     * What one flush did.
     *
     * @param stored how many items the store accepted. A clip the server
     *   collapsed into an existing row by its `UNIQUE(body_sha256)` dedup is as
     *   stored as a new one — it is on the server, so the local row must leave
     *   the queue either way — and the client does not distinguish the two. (It
     *   carried the server's `deduped` verdict as a field until this branch's
     *   audit round 0; nothing ever read it, so it is deleted rather than left
     *   looking like a thing that is checked.)
     * @param dropped how many were permanently refused and discarded.
     * @param kept how many are still queued, for any reason.
     * @param unreachable whether the drain stopped because the store did not
     *   answer. 🔴 Distinct from `kept > 0`: a flush that hit its [MAX_PER_FLUSH]
     *   ceiling also leaves items queued, and that is not a failure.
     * @param failure set when the outbox itself could not be read. The queue is
     *   untouched in that case and nothing was attempted.
     */
    data class Report(
        val stored: Int = 0,
        val dropped: Int = 0,
        val kept: Int = 0,
        val unreachable: Boolean = false,
        val failure: String? = null,
    ) {

        /**
         * 🔴 Whether this pass **could not move the queue forward** — as
         * opposed to having simply left work for the next tick.
         *
         * This is the one reading of a [Report] that a surface may act on, and
         * it is deliberately NOT `kept > 0`. A flush that hit its
         * [MAX_PER_FLUSH] ceiling also leaves items queued, and so does one
         * that stored three of four; neither is a fault, and a bubble that
         * showed one would cry wolf on every ordinary multi-item capture.
         *
         * Two things qualify, and both mean the queue is *stuck* rather than
         * *busy*:
         *
         *  * [unreachable] — the POST got no usable answer. 🔴 Includes a
         *    **401**: `ClipHttp.PERMANENT_STATUSES` deliberately excludes it,
         *    so a typo'd token keeps every capture forever, with every toast
         *    saying "queued". That is the defect this property exists for.
         *  * [failure] — the outbox file itself could not be read, so captures
         *    are on disk and nothing can be attempted at all.
         *
         * ONE pass saying so is still not a fault — the mesh drops for a
         * minute at a time. Persistence is [ClipOutboxHealth]'s job, which
         * folds a run of these into the verdict a surface renders.
         */
        val cannotDrain: Boolean get() = unreachable || failure != null

        /**
         * 🔴 Whether this pass **DESTROYED** one or more of the user's
         * captures.
         *
         * A [dropped] row left the outbox without reaching the store, and the
         * outbox was the only copy — so this is not "the queue is stuck", it
         * is "text the user was told had been taken no longer exists
         * anywhere". It is strictly worse than [cannotDrain] and, until this
         * branch, it had **less visibility than nothing**: `dropped` had no
         * consumer in the whole tree, and because a dropping pass is not a
         * `cannotDrain` pass it also *reset* [ClipOutboxHealth]'s fault
         * counter to zero. Ten capture-destroying flushes left the indicator
         * dark while ten harmless 401 flushes lit it.
         *
         * Deliberately SEPARATE from [cannotDrain] rather than folded into it.
         * They are different facts with different remedies — a stuck queue
         * waits for the mesh or a corrected setting, a destroyed capture
         * cannot be retried at all — and [ClipOutboxHealth] renders them with
         * different words for that reason. Folding them would also have made
         * `ClipOutboxHealthTest.cannotDrainIsTHEReadingAndItIsNotKeptGreaterThanZero`
         * assert the opposite of what it says.
         */
        val destroyed: Boolean get() = dropped > 0
    }

    /** One pass. Never throws. */
    fun flush(max: Int = MAX_PER_FLUSH): Report {
        val queue = try {
            outbox.pending()
        } catch (e: ClipOutbox.Failed) {
            // 🔴 Reported, not swallowed into a zero Report. "Nothing to do"
            // and "the queue is unreadable" are the empty result that the most
            // causes share, and this one reaches the user.
            //
            // ⚠ This comment used to say *"and the caller logs this one"*. It
            // did not: BOTH production callers built the Report and threw it
            // away, so a 401 on a typo'd token kept every capture forever with
            // nothing on screen. Round 0 of this branch's audit found it. Each
            // caller now hands the Report to [ClipOutboxHealth], and
            // `ClipSeamLedgerTest.everySiteThatDrainsTheOutboxCONSUMESItsReport`
            // is red if a third one appears that does not.
            return Report(failure = e.message ?: e.javaClass.simpleName)
        }
        var stored = 0
        var dropped = 0
        for ((index, item) in queue.withIndex()) {
            if (index >= max) {
                return Report(stored, dropped, kept = queue.size - index)
            }
            when (post(item.body, device)) {
                is ClipPost.Stored -> {
                    outbox.remove(item.id)
                    stored++
                }
                is ClipPost.Refused -> {
                    // 🔴 DROPPED, WHICH DESTROYS THE USER'S ONLY COPY. The
                    // server will refuse these BYTES forever, and the flush
                    // stops at the first unreachable item — so a
                    // permanently-refused head is a dead queue for every clip
                    // behind it. That trade is only defensible while
                    // `ClipHttp.PERMANENT_STATUSES` holds nothing a corrected
                    // setting could fix; read that set's kdoc before adding to
                    // it, because until this branch it held five statuses that
                    // a mistyped base URL produced.
                    //
                    // 🔴 Counted, and now CONSUMED: [Report.destroyed] reaches
                    // [ClipOutboxHealth] and raises a fault of its own. Until
                    // this branch `dropped` had no consumer anywhere in the
                    // tree, so this line destroyed a capture and left the
                    // indicator DARKER than a harmless 401 did.
                    outbox.remove(item.id)
                    dropped++
                }
                is ClipPost.Unreachable -> {
                    return Report(
                        stored = stored,
                        dropped = dropped,
                        kept = queue.size - index,
                        unreachable = true,
                    )
                }
            }
        }
        return Report(stored, dropped, kept = 0)
    }

    companion object {
        /**
         * How many items one pass will attempt.
         *
         * A bound rather than "all of them" because the pass runs on one
         * worker thread off a 60-second tick: a 200-item queue built up over a
         * week off-mesh would otherwise hold that thread for as long as 200
         * sequential round trips take, and the next tick would find it busy.
         * Whatever is left is attempted on the following tick, so nothing is
         * lost — only delayed, by a minute per batch.
         */
        const val MAX_PER_FLUSH: Int = 25
    }
}
