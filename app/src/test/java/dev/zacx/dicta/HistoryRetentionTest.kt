package dev.zacx.dicta

import dev.zacx.dicta.HistoryRetention.Entry
import dev.zacx.dicta.HistoryRetention.MAX_AGE_MS
import dev.zacx.dicta.HistoryRetention.MAX_BYTES
import dev.zacx.dicta.HistoryRetention.MAX_ITEMS
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The retention rule: each bound alone, the ORDER the bounds apply in, the
 * tie-break that stops the whole thing being a coin flip, and properties
 * over randomised input.
 *
 * Mostly **invariant guards** — nothing here regressed. They exist because
 * the cost of getting retention wrong is asymmetric and invisible: a bound
 * that evicts too eagerly silently destroys the user's recordings, and the
 * tie-break hazard produces a *flaky* answer rather than a wrong one, which
 * is the worse failure because it reads as CI noise.
 *
 * Every expectation is a literal derived from the stated rule, never from
 * what the implementation happens to return.
 */
class HistoryRetentionTest {

    /** Fixed epoch so no test can accidentally depend on the real clock. */
    private val now = 1_700_000_000_000L

    private val mib = 1024L * 1024L

    /** Tiny, so the byte bound never fires in a test that is not about it. */
    private val small = 1_024L

    private fun entry(id: String, ageMs: Long, bytes: Long = small) =
        Entry(id = id, createdAtMs = now - ageMs, audioBytes = bytes)

    /** Newest-first, the order every bound is expressed in. */
    private fun survivorsOf(entries: List<Entry>, nowMs: Long): List<Entry> {
        val gone = HistoryRetention.evict(entries, nowMs).toSet()
        return entries.filter { it.id !in gone }
            .sortedWith(compareByDescending<Entry> { it.createdAtMs }.thenBy { it.id })
    }

    // --- the count bound alone ---------------------------------------------

    @Test
    fun oneItemOverTheCountBoundEvictsExactlyTheOldest() {
        // MAX_ITEMS + 1 entries, each a second apart, all tiny: the ONLY
        // bound in play is the count, and it must take one item, not a batch.
        val entries = (0..MAX_ITEMS).map { i -> entry(id(i), ageMs = i * 1_000L) }
        assertEquals(
            "one item over the count bound must evict exactly the single " +
                "oldest entry — a retention pass that deletes more than it " +
                "has to is destroying recordings the bounds still permit",
            listOf(id(MAX_ITEMS)),
            HistoryRetention.evict(entries, now),
        )
    }

    @Test
    fun exactlyTheCountBoundEvictsNothing() {
        // The boundary, named: MAX_ITEMS is a ceiling that may be reached.
        val entries = (0 until MAX_ITEMS).map { i -> entry(id(i), ageMs = i * 1_000L) }
        assertEquals(
            "a history holding exactly MAX_ITEMS entries is at the bound, " +
                "not over it — evicting here is an off-by-one that silently " +
                "costs the user their oldest recording on every save",
            emptyList<String>(),
            HistoryRetention.evict(entries, now),
        )
    }

    // --- the byte bound alone ----------------------------------------------

    @Test
    fun twoItemsTotallingOverTheByteBoundEvictTheOlder() {
        // 110 MiB + 91 MiB = 201 MiB against a 200 MiB budget. Newest first,
        // so the newer one is kept and the older is what gives way.
        val newer = Entry("new", now, 110 * mib)
        val older = Entry("old", now - 1_000L, 91 * mib)
        assertEquals(
            "the byte bound must give way oldest-first; evicting the newer " +
                "entry means the recording the user just made is the one " +
                "that disappears",
            listOf("old"),
            HistoryRetention.evict(listOf(newer, older), now),
        )
    }

    @Test
    fun exactlyTheByteBoundEvictsNothing() {
        val newer = Entry("new", now, 110 * mib)
        val older = Entry("old", now - 1_000L, 90 * mib)
        assertEquals(
            "110 MiB + 90 MiB is exactly MAX_BYTES, which is a ceiling that " +
                "may be reached — this is the > vs >= boundary on the byte " +
                "accumulator",
            emptyList<String>(),
            HistoryRetention.evict(listOf(newer, older), now),
        )
    }

    // --- the age bound alone -----------------------------------------------

    @Test
    fun anItemOneMillisecondPastTheAgeBoundIsEvicted() {
        val entries = listOf(entry("stale", ageMs = MAX_AGE_MS + 1))
        assertEquals(
            "an entry past MAX_AGE_MS must be evicted even as the only item " +
                "in history — age is absolute, not a capacity bound",
            listOf("stale"),
            HistoryRetention.evict(entries, now),
        )
    }

    @Test
    fun anItemOneMillisecondInsideTheAgeBoundIsKept() {
        val entries = listOf(entry("fresh", ageMs = MAX_AGE_MS - 1))
        assertEquals(
            "an entry inside MAX_AGE_MS must survive",
            emptyList<String>(),
            HistoryRetention.evict(entries, now),
        )
    }

    @Test
    fun anItemExactlyAtTheAgeBoundIsKept() {
        // 🔴 The > vs >= boundary, stated on its own. The brief's pair
        // (MAX_AGE_MS + 1 evicted, MAX_AGE_MS - 1 kept) is satisfied by BOTH
        // comparisons, so without this case the age predicate's strictness is
        // untested and a >= slips through green.
        val entries = listOf(entry("exact", ageMs = MAX_AGE_MS))
        assertEquals(
            "an entry aged exactly MAX_AGE_MS is AT the bound, not past it; " +
                "a >= comparison here evicts a recording a full millisecond " +
                "before the policy says it may",
            emptyList<String>(),
            HistoryRetention.evict(entries, now),
        )
    }

    // --- the order the bounds apply in -------------------------------------

    @Test
    fun ageBeatsTheCountBoundHavingRoomToSpare() {
        // Three entries — nowhere near MAX_ITEMS, nowhere near MAX_BYTES —
        // and one of them is expired. If age were a capacity bound the other
        // two's slack would rescue it.
        val entries = listOf(
            entry("a", ageMs = 0),
            entry("b", ageMs = 1_000),
            entry("c", ageMs = MAX_AGE_MS + 5_000),
        )
        assertEquals(
            "age must evict even with count and byte room to spare — an " +
                "expired recording that history is small enough to keep is " +
                "exactly the case the age bound exists for",
            listOf("c"),
            HistoryRetention.evict(entries, now),
        )
    }

    @Test
    fun theCountBoundEvictsAnItemTheByteBoundWouldHaveKept() {
        // MAX_ITEMS + 1 entries whose TOTAL is a rounding error against
        // MAX_BYTES. The byte bound is satisfied by every one of them; the
        // count bound still takes the oldest.
        val entries = (0..MAX_ITEMS).map { i -> entry(id(i), ageMs = i * 1_000L, bytes = 97L) }
        val evicted = HistoryRetention.evict(entries, now)
        assertEquals(
            "the count bound must bite independently of the byte bound — " +
                "a byte-only implementation keeps all " + (MAX_ITEMS + 1) +
                " entries and the list grows without limit",
            listOf(id(MAX_ITEMS)),
            evicted,
        )
    }

    @Test
    fun theEvictionListIsGroupedByStageAgeThenCountThenBytes() {
        // 🔴 This is where the STAGE ORDER becomes observable.
        //
        // The evicted *set* is provably invariant under swapping age and
        // count: age is monotone in createdAtMs and the list is sorted by
        // createdAtMs, so the expired entries are always a suffix, and
        // trimming a suffix commutes with taking a prefix. No assertion on
        // the set can therefore ever see that swap.
        //
        // The returned *sequence* is not invariant: age-first condemns the
        // expired entries first, count-first condemns indices past the bound
        // first. Pinning the whole list is what makes the documented pipeline
        // order a machine-checkable claim rather than a comment.
        //
        // 52 live entries (2 over the count bound) + 3 expired.
        val live = (0..51).map { i -> entry(id(i), ageMs = i * 1_000L) }
        val expired = (52..54).map { i ->
            entry(id(i), ageMs = MAX_AGE_MS + 1_000L + (i - 52) * 1_000L)
        }
        assertEquals(
            "the eviction list must run age-evictions first (newest-first " +
                "within the stage), then count, then bytes — this is the " +
                "documented pipeline order, and a reordered pipeline " +
                "produces the same SET so nothing else can detect it",
            listOf(id(52), id(53), id(54), id(50), id(51)),
            HistoryRetention.evict(live + expired, now),
        )
    }

    // --- determinism: the flakiness guard ----------------------------------

    @Test
    fun entriesSharingATimestampEvictIdenticallyInEitherInputOrder() {
        // 🔴 The flakiness guard. entries comes from File.listFiles(), which
        // has NO documented order, so a tie-break on list position makes this
        // function non-deterministic across devices and across calls. That
        // fails one run in N and reads as CI noise rather than as a bug.
        //
        // 49 distinct-timestamp entries + 2 sharing the oldest timestamp
        // = MAX_ITEMS + 1, so exactly one of the tied pair must go and the
        // tie-break is the only thing that can choose.
        val distinct = (0..48).map { i -> entry(id(i), ageMs = i * 1_000L) }
        val tiedA = entry("aaa-tied", ageMs = 100_000L)
        val tiedZ = entry("zzz-tied", ageMs = 100_000L)

        val aFirst = HistoryRetention.evict(distinct + listOf(tiedA, tiedZ), now)
        val zFirst = HistoryRetention.evict(distinct + listOf(tiedZ, tiedA), now)

        assertEquals(
            "two entries sharing a createdAtMs must evict identically " +
                "whichever order the directory listing hands them over — " +
                "otherwise which recording gets deleted is a coin flip",
            aFirst,
            zFirst,
        )
        assertEquals(
            "the tie must break on id ASC (lower id is treated as newer and " +
                "survives), not on list position",
            listOf("zzz-tied"),
            aFirst,
        )
    }

    @Test
    fun shufflingTheWholeInputCannotChangeTheResult() {
        // Broader positive control on the same hazard, with ties spread
        // through the list rather than only at the eviction boundary.
        val rng = Random(97_003L)
        val entries = (0..59).map { i ->
            // Deliberate collisions: 60 entries over 20 distinct timestamps.
            entry(id(i), ageMs = (i % 20) * 1_000L, bytes = 3L * mib + i)
        }
        val reference = HistoryRetention.evict(entries, now)
        repeat(12) { round ->
            val shuffled = entries.shuffled(rng)
            assertEquals(
                "round $round: a reordered input produced a different " +
                    "eviction list — evict() is reading list position " +
                    "somewhere instead of (createdAtMs DESC, id ASC)",
                reference,
                HistoryRetention.evict(shuffled, now),
            )
        }
    }

    // --- degenerate inputs --------------------------------------------------

    @Test
    fun aSingleItemLargerThanTheWholeByteBudgetIsKept() {
        // 🔴 The deliberate carve-out. Enforcing the byte bound strictly here
        // means a long dictation can never be in history AT ALL: the user
        // watches the item vanish the instant it is created, every time, with
        // no explanation and no discoverable duration limit.
        val huge = Entry("huge", now, MAX_BYTES + 1)
        assertEquals(
            "a single recording exceeding MAX_BYTES must be KEPT — evicting " +
                "it makes long dictations permanently impossible to have in " +
                "history, which is a silently unusable feature rather than a " +
                "bounded overage",
            emptyList<String>(),
            HistoryRetention.evict(listOf(huge), now),
        )
    }

    @Test
    fun anOversizeNewestItemStillEvictsEverythingOlder() {
        // The carve-out is ONE item, not a licence to ignore the bound: the
        // overage must stay bounded at a single recording.
        val huge = Entry("huge", now, MAX_BYTES + 1)
        val older = Entry("older", now - 1_000L, small)
        assertEquals(
            "the oversize carve-out must cover exactly the newest entry; " +
                "if older entries survive alongside it the byte bound is " +
                "unbounded rather than over by one recording",
            listOf("older"),
            HistoryRetention.evict(listOf(huge, older), now),
        )
    }

    @Test
    fun emptyInputEvictsNothing() {
        assertEquals(emptyList<String>(), HistoryRetention.evict(emptyList(), now))
    }

    @Test
    fun aClockBeforeEveryEntryEvictsNothingOnAgeAndDoesNotCrash() {
        // A backwards device clock, an NTP step, or an entry timestamped in
        // the future. The age term goes negative; it must not wrap, throw, or
        // read as "infinitely old".
        val entries = (0..9).map { i ->
            Entry("future-$i", now + 1_000_000L + i * 1_000L, small)
        }
        assertEquals(
            "a nowMs before every createdAtMs must evict nothing on age — " +
                "a sign error here deletes the user's entire history the " +
                "first time their clock steps backwards",
            emptyList<String>(),
            HistoryRetention.evict(entries, now),
        )
    }

    @Test
    fun aClockBeforeEveryEntryStillAppliesTheCountBound() {
        // Age is unobservable here, so this pins that the OTHER bounds keep
        // working rather than the whole pass bailing out.
        val entries = (0..MAX_ITEMS).map { i ->
            Entry(id(i), now + 1_000_000L - i * 1_000L, small)
        }
        assertEquals(
            "a backwards clock must disable only the age bound, not the pass",
            listOf(id(MAX_ITEMS)),
            HistoryRetention.evict(entries, now),
        )
    }

    // --- the constants ------------------------------------------------------

    @Test
    fun theBoundsAreTheBriefsNumbers() {
        // Literals, so changing a bound is a visible decision.
        assertEquals(50, MAX_ITEMS)
        assertEquals(200L * 1024 * 1024, MAX_BYTES)
        assertEquals(14L * 24 * 60 * 60 * 1000, MAX_AGE_MS)
    }

    // --- properties over randomised input -----------------------------------

    @Test
    fun overRandomisedInputTheOutputIsAlwaysASubsetWithNoDuplicates() {
        // SEEDED: the build must be reproducible, so a failure here is a
        // failure anyone can re-run. Math.random() would make this a
        // once-per-CI-run lottery ticket.
        val rng = Random(20_261_015L)
        repeat(400) { trial ->
            val entries = randomEntries(rng)
            val nowMs = randomNow(rng)
            val evicted = HistoryRetention.evict(entries, nowMs)
            val ids = entries.map { it.id }.toSet()

            assertTrue(
                "trial $trial: evict() returned an id that is not in its " +
                    "input — the caller would delete a file it was never " +
                    "told about: ${evicted.filter { it !in ids }}",
                evicted.all { it in ids },
            )
            assertEquals(
                "trial $trial: evict() returned a duplicate id; the caller " +
                    "deletes twice and the second delete looks like an IO " +
                    "failure: $evicted",
                evicted.size,
                evicted.distinct().size,
            )
        }
    }

    @Test
    fun overRandomisedInputTheSurvivorsSatisfyEveryBound() {
        val rng = Random(31_337_019L)
        var sawAgeEviction = 0
        var sawCountPressure = 0
        var sawBytePressure = 0
        var sawCarveOut = 0

        repeat(400) { trial ->
            val entries = randomEntries(rng)
            val nowMs = randomNow(rng)
            val survivors = survivorsOf(entries, nowMs)

            val expired = survivors.filter { nowMs - it.createdAtMs > MAX_AGE_MS }
            assertTrue(
                "trial $trial: ${expired.size} survivor(s) are past " +
                    "MAX_AGE_MS — age is absolute and must hold for every " +
                    "input, not just the small ones",
                expired.isEmpty(),
            )
            assertTrue(
                "trial $trial: ${survivors.size} survivors exceeds " +
                    "MAX_ITEMS=$MAX_ITEMS",
                survivors.size <= MAX_ITEMS,
            )

            val totalBytes = survivors.sumOf { it.audioBytes }
            val isCarveOut = survivors.size == 1 && survivors[0].audioBytes > MAX_BYTES
            assertTrue(
                "trial $trial: survivors hold $totalBytes bytes against " +
                    "MAX_BYTES=$MAX_BYTES, and this is NOT the documented " +
                    "single-oversize carve-out (${survivors.size} survivors) " +
                    "— the byte overage must be bounded at one recording",
                totalBytes <= MAX_BYTES || isCarveOut,
            )

            // Coverage counters: a property suite over inputs that never
            // reach a bound is vacuously green, so the bounds themselves are
            // asserted to have been exercised below.
            if (entries.any { nowMs - it.createdAtMs > MAX_AGE_MS }) sawAgeEviction++
            if (entries.count { nowMs - it.createdAtMs <= MAX_AGE_MS } > MAX_ITEMS) {
                sawCountPressure++
            }
            if (entries.sumOf { it.audioBytes } > MAX_BYTES) sawBytePressure++
            if (isCarveOut) sawCarveOut++
        }

        // 🔴 Positive controls on the generator, reported as counts rather
        // than trusted: a generator that never produces pressure on a bound
        // proves nothing about that bound.
        assertTrue("the generator never produced an expired entry", sawAgeEviction > 0)
        assertTrue("the generator never exceeded MAX_ITEMS", sawCountPressure > 0)
        assertTrue("the generator never exceeded MAX_BYTES", sawBytePressure > 0)
        assertTrue("the generator never hit the oversize carve-out", sawCarveOut > 0)
    }

    // --- fixtures -----------------------------------------------------------

    /** Zero-padded so `id ASC` and index order agree, making ties readable. */
    private fun id(i: Int) = "rec-%03d".format(i)

    /**
     * Sizes deliberately overshoot every bound and are NOT multiples of the
     * step: a fixture that can only land exactly on a bound cannot see a
     * mutant that moves the comparison by one.
     */
    private fun randomEntries(rng: Random): List<Entry> {
        val n = rng.nextInt(81)
        return (0 until n).map { i ->
            val bytes = if (rng.nextInt(8) == 0) {
                // Occasionally a single recording bigger than the whole
                // budget, so the carve-out branch is actually reached.
                MAX_BYTES + 1 + rng.nextInt(400_000_000)
            } else {
                rng.nextInt(31_000_003).toLong()
            }
            Entry(
                id = "r-%03d".format(i),
                // Up to ~23 days back and ~1 day forward. Bounded by
                // Int.MAX_VALUE because java.util.Random.nextInt is Int-typed;
                // ~23 days comfortably overshoots the 14-day age bound.
                createdAtMs = now - rng.nextInt(2_000_000_003) + rng.nextInt(86_400_011),
                audioBytes = bytes,
            )
        }
    }

    private fun randomNow(rng: Random): Long = now + rng.nextInt(1_000_003) - 500_001
}
