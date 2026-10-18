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
 *
 * ## 🔴 The bounds are large, and the byte bound IS NOW LOAD-BEARING
 *
 * Measured on the handset this ships to (SM-G991W): history lives in
 * `/data/data/<pkg>/no_backup/history/`, where `/data` has **18 GB free of
 * 105 GB (83% used)**, and the app's whole footprint is currently 10 MB. The
 * ceiling is bounded by the user's phone, not by server capacity — there is no
 * server. Every byte here counts against Dicta in Settings → Storage as the
 * user's own data, and the user is the only one who pays for it.
 *
 * Two consequences of the current numbers, both deliberate:
 *
 *  - **A year of the user's voice can accumulate** unless they clear it. The
 *    "Delete all history" control is therefore load-bearing, not a
 *    convenience — see [DictationHistoryStore.clearAll], which is why that
 *    method deletes every file in the directory rather than the indexed ones.
 *  - 🔴 **The byte bound binds ON ONE ENCODER at the current constants — as of
 *    2026-10-17, and this is the THIRD state this sentence has been in. Read
 *    the history before writing a fourth.** (1) An early draft claimed raising
 *    [MAX_BYTES] to 2 GiB made the byte stage "a production code path": FALSE
 *    in the commit that wrote it. (2) It was corrected to "cannot bind", which
 *    was true while `BubbleDictation.MAX_RECORDING_MS` was 120 s. (3) The cap
 *    is now **600 s**, which moved the worst case across the budget — but for
 *    AAC only.
 *
 *    **The method, which outlives every figure below: per-item bytes =
 *    bitrate ÷ 8 × `MAX_RECORDING_MS`; worst case = that × [MAX_ITEMS];
 *    compare against [MAX_BYTES]. Whichever of the two bounds is crossed
 *    first is the one that binds.** Re-run it; do not trust the numbers.
 *
 *    | regime | per item (Opus 24 kbps / AAC 32 kbps) | ×[MAX_ITEMS] | vs 2048 MiB |
 *    |---|---|---|---|
 *    | OLD — 120 s cap | ~352 KiB / ~469 KiB | ~343 MiB / ~458 MiB | neither binds; count binds first by 4.5–6× |
 *    | **CURRENT — 600 s cap** | **~1.72 MiB / ~2.29 MiB** | **~1.68 GiB / ~2.24 GiB** | **Opus 1717 MiB — does NOT bind; AAC 2289 MiB — BINDS** |
 *
 *    🔴 So "count always binds first, in every regime" — which this comment
 *    used to assert as an absolute — is now FALSE, and that is the whole
 *    change. It still holds for Opus, the encoder that actually ships
 *    (`AudioCaptureLogic` prefers it). The byte stage trims in production only
 *    on a device with no Opus encoder, which falls back to AAC. Counting that
 *    as "the byte stage is now live in production" overstates it.
 *
 *    The arithmetic — not the prose — is pinned by
 *    [HistoryRetentionTest.theByteBoundIsLoadBearingAtTheCurrentConstants],
 *    which inverted along with this claim and will fire again if a constant
 *    moves back.
 *
 *    The oversize carve-out is unreachable in BOTH regimes and is not made
 *    live by this change: it needs ONE continuous recording larger than
 *    [MAX_BYTES], i.e. ~199 hours of Opus or ~149 hours of AAC, against a
 *    10-minute cap. It protects nothing at these constants.
 *
 *    The stage is KEPT, and the reason is narrow and specific: it is the
 *    backstop for a change to `MAX_RECORDING_MS`. Long-form recording is an
 *    openly contemplated direction, and it is exactly the change that would
 *    make this bound load-bearing overnight. [theByteBoundIsLoadBearingAtTheCurrentConstants]
 *    pins the arithmetic above so this comment cannot quietly go stale again
 *    — if someone raises the recording cap, that test fails and tells them the
 *    byte stage has just become live.
 *
 * ## Bounds are values, not just constants
 *
 * [evict] takes a [Bounds] defaulting to the three constants. This exists for
 * one reason: at 1000 items, a test that drove eviction through
 * [DictationHistoryStore.add] against a real filesystem would do ~1001 file
 * copies and ~1001 full-index serialisations — quadratic, half a million JSON
 * object writes. The *mechanism* is the same at any bound, so tests drive it
 * with small ones. 🔴 That makes the default the only thing tying the store to
 * the real policy, which is why `DictationHistoryBoundsSeamTest` asserts the
 * production store's effective bounds ARE these constants: without it the
 * mechanism could be tested at 5 items while the shipped app used anything at
 * all, and every test would still be green.
 */
object HistoryRetention {

    /**
     * How many recordings history keeps, at most.
     *
     * A browsing bound rather than a storage one, and the bound that binds
     * first in practice (see the object kdoc). At 1000 this is far past the
     * point where the list stops being something you scroll and starts being
     * something you search, and there is no search — the bet is that a user
     * who wants an old transcript would rather scroll a long way than not
     * have it.
     */
    const val MAX_ITEMS: Int = 1000

    /**
     * How much audio history keeps, at most — 2 GiB.
     *
     * For scale: 2 GiB is ~199 h of `OPUS_BITRATE` audio (24 kbps = 3000 B/s,
     * so 2³¹ B / 3000 ≈ 715 828 s) and ~149 h at `AAC_BITRATE` (32 kbps).
     * Re-derived here rather than copied — an earlier figure of "~185 hours"
     * matched neither encoder.
     *
     * 🔴 **LIVE on AAC ONLY at the current constants, as of 2026-10-17.** This
     * sentence has now said "reachable" (false when written), then "dormant"
     * (true at a 120 s recording cap, where [MAX_ITEMS] items totalled at most
     * ~343 MiB on Opus / ~458 MiB on AAC), and now "live" again — because the
     * cap is 600 s, so [MAX_ITEMS] items total ~1.68 GiB on Opus and ~2.24 GiB
     * on AAC. Only the AAC figure crosses this 2 GiB budget: Opus is ~1717 MiB
     * against 2048 MiB and still does not bind, so the stage trims in
     * production only on a device that has no Opus encoder. It is live for a
     * DIFFERENT reason than
     * the first draft claimed, which is why that draft was wrong and this is
     * not: the constant that moved is the recording cap, not this one. See the
     * object kdoc for the arithmetic, and
     * [HistoryRetentionTest.theByteBoundIsLoadBearingAtTheCurrentConstants] for
     * the assertion that keeps these sentences honest.
     *
     * 🔴 Subject to the one-oversize-item carve-out documented on the object;
     * history can exceed this by one recording.
     */
    const val MAX_BYTES: Long = 2L * 1024 * 1024 * 1024

    /**
     * How long a recording may live — 365 days.
     *
     * Absolute: unlike the other two this is not a capacity bound and cannot
     * be traded against them. It is the reason the whole pipeline runs even
     * when history is nearly empty.
     *
     * 🔴 At a year, this no longer doubles as a privacy backstop the way 14
     * days did. A recording the user has forgotten making can sit on the
     * device for twelve months, so deletion is the user's job via
     * [DictationHistoryStore.clearAll] and age is only the floor under it.
     */
    const val MAX_AGE_MS: Long = 365L * 24 * 60 * 60 * 1000

    /**
     * The three bounds as one value, so a caller can supply its own.
     *
     * Defaults are the constants above, and [DEFAULT] is what production gets.
     * 🔴 Adding a fourth bound here means adding it to the seam guard too — a
     * bound whose default is not the real policy is invisible to every other
     * test.
     */
    data class Bounds(
        val maxItems: Int = MAX_ITEMS,
        val maxBytes: Long = MAX_BYTES,
        val maxAgeMs: Long = MAX_AGE_MS,
    ) {
        companion object {
            /** The production policy: exactly the constants on this object. */
            val DEFAULT = Bounds()
        }
    }

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
     * @param bounds the policy to apply, defaulting to [Bounds.DEFAULT], i.e.
     *   the constants on this object. Overridden only by tests, which drive
     *   the mechanism with small numbers; see the object kdoc for why, and for
     *   the seam guard that keeps the default honest.
     */
    fun evict(
        entries: List<Entry>,
        nowMs: Long,
        bounds: Bounds = Bounds.DEFAULT,
    ): List<String> {
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
            if (nowMs - entry.createdAtMs > bounds.maxAgeMs) {
                condemned.add(entry.id)
            } else {
                ageSurvivors.add(entry)
            }
        }

        // --- 2. Count. ------------------------------------------------------
        // 🔴 "Newest first" is the SCAN direction, not what gets deleted. The
        // list is newest→oldest, so the entries past `maxItems` are the OLDEST
        // ones, and those are what this condemns. Read as "evicts newest
        // first" it says the opposite of what it does — the newest are exactly
        // the entries kept.
        val countSurvivors = ArrayList<Entry>(ageSurvivors.size)
        for ((index, entry) in ageSurvivors.withIndex()) {
            if (index > bounds.maxItems - 1) {
                condemned.add(entry.id)
            } else {
                countSurvivors.add(entry)
            }
        }

        // --- 3. Bytes. ------------------------------------------------------
        // Same scan direction, same caveat as stage 2: newest→oldest, and what
        // is condemned once the budget is blown is everything OLDER.
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
            if (accumulated + entry.audioBytes > bounds.maxBytes) {
                if (index == 0) {
                    // 🔴 The carve-out: the newest survivor is kept even when
                    // it alone exceeds MAX_BYTES, because evicting it makes a
                    // long dictation permanently impossible to have in
                    // history. Accumulating it anyway means everything older
                    // is evicted on the next iteration, which is correct —
                    // the overage is one item, not unbounded.
                    //
                    // 🔴 UNREACHABLE at the current constants, and not made
                    // reachable by the 120 s → 600 s cap raise. `index == 0`
                    // means `accumulated` is 0, so the condition requires ONE
                    // recording larger than MAX_BYTES (2 GiB) — ~199 h of
                    // Opus or ~149 h of AAC, against a 10-minute cap. It is a
                    // backstop for a future cap, and it protects nothing
                    // today; do not cite it as a safety property of the
                    // current configuration.
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
