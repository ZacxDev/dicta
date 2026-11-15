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
     * @param stored how many items the store accepted (deduped included — a
     *   deduped clip is as stored as a new one; see [ClipPost.Stored.deduped]).
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
    )

    /** One pass. Never throws. */
    fun flush(max: Int = MAX_PER_FLUSH): Report {
        val queue = try {
            outbox.pending()
        } catch (e: ClipOutbox.Failed) {
            // 🔴 Reported, not swallowed into a zero Report. "Nothing to do"
            // and "the queue is unreadable" are the empty result that the most
            // causes share, and the caller logs this one.
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
                    // 🔴 DROPPED. The server will refuse this body forever, and
                    // the flush stops at the first unreachable item — so a
                    // permanently-refused head is a dead queue for every clip
                    // behind it. The only reachable case measured is a 400 on
                    // an empty body, which [ClipOutboxStore.append] already
                    // refuses to queue, so this is belt-and-braces against a
                    // server-side rule this client does not know about.
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
