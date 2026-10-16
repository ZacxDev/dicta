package dev.zacx.dicta

/**
 * The dictation-history retention rule: which saved recordings get deleted.
 *
 * ## Why this is a pure object with no clock and no filesystem
 *
 * History lives as audio files in app-private storage plus a row per
 * recording. Deciding *what to delete* and *doing the deleting* are two
 * different jobs with two different failure modes: the decision is pure
 * arithmetic over a list, the deletion is IO that can partially fail. Mixing
 * them means the only way to test the arithmetic is to lay down real files
 * and stub a clock, and then every retention bug is a flaky IO test.
 *
 * So [evict] takes the entries and the current time as **arguments** and
 * returns ids. It reads no clock, touches no disk and needs no `Context`.
 * The caller does the IO and is free to stop halfway.
 *
 * ## The three bounds, and why the ORDER of them is load-bearing
 *
 * Three independent bounds apply, **in this order**:
 *
 *  1. **Age** ([MAX_AGE_MS]) — absolute. An expired recording is deleted no
 *     matter how few items there are or how little space they take. Age is
 *     the bound that exists for a reason the user cannot see (they will not
 *     remember dictating it, and we should not be holding their voice
 *     indefinitely), so it must not be rescuable by the other two.
 *  2. **Count** ([MAX_ITEMS]) — of what age kept, newest first.
 *  3. **Bytes** ([MAX_BYTES]) — of what count kept, newest first.
 *
 * 🔴 The *evicted set* happens to be invariant under swapping (1) and (2),
 * and that is a fact about the data rather than a licence to reorder: age is
 * monotone in `createdAtMs` and the list is sorted by `createdAtMs`, so the
 * expired entries are always a **suffix**, and trimming a suffix before or
 * after taking a prefix lands in the same place. What is *not* invariant is
 * the **order of the returned list** — see "Deletion order" below, which is
 * the stage order made observable. Do not read the set-invariance as "the
 * order does not matter"; it is the reason the order has to be pinned
 * somewhere, and here is where.
 *
 * ## Deletion order
 *
 * The returned list is grouped by the stage that condemned each entry —
 * age-evictions first, then count, then bytes — and within a stage it runs
 * newest-first. This is an arbitrary but **total and deterministic** order,
 * chosen so that the pipeline's stage structure is visible from the outside.
 * It is not a priority hint: the caller should delete all of them.
 *
 * ## 🔴 A single recording bigger than the whole byte budget is KEPT
 *
 * This is the one place where "enforce the bound" and "do something sane"
 * disagree, and the bound loses.
 *
 * A strict byte bound evicts any entry whose inclusion would exceed
 * [MAX_BYTES] — including the newest one, when that single recording is
 * itself larger than the budget. The user's experience of that is: they
 * finish a long dictation, the item appears in history, and it vanishes
 * immediately, every time, with no explanation. The feature is simply
 * unavailable to them above some duration they cannot discover.
 *
 * So the newest surviving entry is always kept, even when it alone blows the
 * budget. The cost is bounded and honest — history can exceed [MAX_BYTES] by
 * at most one recording, and only transiently, because the *next* recording
 * pushes the oversize one out via the ordinary rule. The alternative cost
 * (a silently unusable feature) is neither bounded nor discoverable.
 *
 * ## Ties break on `id`, never on list order
 *
 * The caller builds `entries` from a directory listing, and
 * `File.listFiles()` has **no documented order** — it is whatever the
 * filesystem hands back, which differs between devices and between calls.
 * Two recordings can share a `createdAtMs` (same millisecond, or two
 * imported with one timestamp), and if the tie broke on list position then
 * which of them got deleted would be a coin flip.
 *
 * That failure is worse than a wrong answer: it is a *flaky* answer. The
 * test passes locally and fails in CI one run in four, and the bug reads as
 * infrastructure noise. So [evict] sorts by `(createdAtMs DESC, id ASC)`
 * before doing anything at all, making the whole function a deterministic
 * map from `(entries, nowMs)` to a list — the same set of entries in any
 * input order gives byte-identical output.
 */
object HistoryRetention {

    /**
     * How many recordings history keeps, at most.
     *
     * A browsing bound rather than a storage one: past about this many, the
     * list stops being something you scroll and starts being something you
     * search, and there is no search.
     */
    const val MAX_ITEMS: Int = 50

    /**
     * How much audio history keeps, at most — 200 MiB.
     *
     * 🔴 Subject to the one-oversize-item carve-out documented on the object;
     * history can exceed this by one recording.
     */
    const val MAX_BYTES: Long = 200L * 1024 * 1024

    /**
     * How long a recording may live — 14 days.
     *
     * Absolute: unlike the other two this is not a capacity bound and cannot
     * be traded against them. It is the reason the whole pipeline runs even
     * when history is nearly empty.
     */
    const val MAX_AGE_MS: Long = 14L * 24 * 60 * 60 * 1000

    /**
     * One history row, reduced to exactly what retention needs.
     *
     * @param id stable identity, and the tie-breaker when [createdAtMs]
     *   collides. Must be unique per entry; duplicates are collapsed.
     * @param createdAtMs wall-clock creation time, same epoch as `nowMs`.
     * @param audioBytes size on disk of the audio for this entry.
     */
    data class Entry(
        val id: String,
        val createdAtMs: Long,
        val audioBytes: Long,
    )

    /**
     * The ids to delete, in deletion order.
     *
     * A pure function of its two arguments. Never returns an id that is not
     * in [entries], and never returns the same id twice.
     *
     * @param nowMs the caller's current time. A value *before* some or all
     *   of the [Entry.createdAtMs] — a backwards device clock, an NTP step,
     *   a timestamp from the future — is handled rather than guarded: a
     *   negative age is simply not greater than [MAX_AGE_MS], so nothing is
     *   evicted on age and the count and byte bounds still apply. Never
     *   throws.
     */
    fun evict(entries: List<Entry>, nowMs: Long): List<String> {
        if (entries.isEmpty()) return emptyList()

        // 🔴 The determinism anchor. Everything below indexes into this, so
        // the function cannot see the caller's input order at all.
        val ordered = entries.sortedWith(
            compareByDescending<Entry> { it.createdAtMs }.thenBy { it.id },
        )

        // A set, so a duplicate id in the input (or an entry condemned by two
        // stages) cannot produce a duplicate in the output. Insertion-ordered,
        // which is what makes the stage grouping below the returned order.
        val condemned = LinkedHashSet<String>()

        // --- 1. Age. Absolute; nothing downstream can rescue these. --------
        val ageSurvivors = ArrayList<Entry>(ordered.size)
        for (entry in ordered) {
            if (nowMs - entry.createdAtMs > MAX_AGE_MS) {
                condemned.add(entry.id)
            } else {
                ageSurvivors.add(entry)
            }
        }

        // --- 2. Count, newest first. ---------------------------------------
        val countSurvivors = ArrayList<Entry>(ageSurvivors.size)
        for ((index, entry) in ageSurvivors.withIndex()) {
            if (index > MAX_ITEMS - 1) {
                condemned.add(entry.id)
            } else {
                countSurvivors.add(entry)
            }
        }

        // --- 3. Bytes, newest first. ---------------------------------------
        // Once the budget is blown, everything older goes too: the entries
        // are in time order, so "skip this one, it is big, keep the next" is
        // not retention, it is a hole in the user's history.
        var accumulated = 0L
        var budgetBlown = false
        for ((index, entry) in countSurvivors.withIndex()) {
            if (budgetBlown) {
                condemned.add(entry.id)
                continue
            }
            if (accumulated + entry.audioBytes > MAX_BYTES) {
                if (index == 0) {
                    // 🔴 The carve-out: the newest survivor is kept even when
                    // it alone exceeds MAX_BYTES, because evicting it makes a
                    // long dictation permanently impossible to have in
                    // history. Accumulating it anyway means everything older
                    // is evicted on the next iteration, which is correct —
                    // the overage is one item, not unbounded.
                    accumulated += entry.audioBytes
                    continue
                }
                budgetBlown = true
                condemned.add(entry.id)
            } else {
                accumulated += entry.audioBytes
            }
        }

        return condemned.toList()
    }
}
