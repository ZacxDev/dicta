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
        // 🔴 Sized from MAX_BYTES, not from a remembered 200 MiB. These were
        // 110 + 91 MiB when the budget was 200 MiB; at 2 GiB that pair is a
        // tenth of the bound and the case stops being about the byte bound at
        // all — it would assert an eviction that no longer happens, and a
        // smaller-sized variant would pass VACUOUSLY. So: 1200 MiB + 900 MiB
        // = 2100 MiB against 2048 MiB. Overshoots by 52 MiB rather than
        // landing on a multiple of either size.
        val newer = Entry("new", now, 1_200 * mib)
        val older = Entry("old", now - 1_000L, 900 * mib)
        // 🔴 Fixture control, so raising MAX_BYTES again cannot quietly turn
        // this into a test of nothing: the pair MUST straddle the bound, and
        // the newest alone must NOT reach it (otherwise the oversize carve-out
        // is what is being observed, not the byte bound).
        assertTrue(
            "fixture is vacuous: ${newer.audioBytes + older.audioBytes} bytes " +
                "does not exceed MAX_BYTES=$MAX_BYTES, so nothing can be " +
                "evicted on bytes and this case asserts nothing about the bound",
            newer.audioBytes + older.audioBytes > MAX_BYTES,
        )
        assertTrue(
            "fixture is wrong: the newest entry alone exceeds MAX_BYTES, so " +
                "this exercises the oversize carve-out instead",
            newer.audioBytes <= MAX_BYTES,
        )
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
        // Deliberately exact, unlike its sibling above: this case IS the
        // boundary. 1200 + 848 = 2048 MiB = 2 GiB.
        val newer = Entry("new", now, 1_200 * mib)
        val older = Entry("old", now - 1_000L, 848 * mib)
        assertEquals(
            "fixture is stale: the pair must total EXACTLY MAX_BYTES for this " +
                "to be the > vs >= boundary at all",
            MAX_BYTES,
            newer.audioBytes + older.audioBytes,
        )
        assertEquals(
            "1200 MiB + 848 MiB is exactly MAX_BYTES, which is a ceiling that " +
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
        // MAX_ITEMS + 2 live entries (2 over the count bound) + 3 expired.
        // 🔴 Derived from MAX_ITEMS rather than the literal 52 these were when
        // the bound was 50: hard-coded counts are how a case that USED to
        // straddle the bound ends up a tenth of the way to it and asserts an
        // eviction list that no longer has a count stage in it.
        val overBy = 2
        val liveCount = MAX_ITEMS + overBy
        val expiredCount = 3
        val live = (0 until liveCount).map { i -> entry(id(i), ageMs = i * 1_000L) }
        val expired = (liveCount until liveCount + expiredCount).map { i ->
            entry(id(i), ageMs = MAX_AGE_MS + 1_000L + (i - liveCount) * 1_000L)
        }
        // Age-evictions first, newest-first within the stage, then the two
        // entries past the count bound (the oldest live ones), newest-first.
        val expectedAgeStage = (liveCount until liveCount + expiredCount).map { id(it) }
        val expectedCountStage = (MAX_ITEMS until liveCount).map { id(it) }
        assertEquals(
            "the eviction list must run age-evictions first (newest-first " +
                "within the stage), then count, then bytes — this is the " +
                "documented pipeline order, and a reordered pipeline " +
                "produces the same SET so nothing else can detect it",
            expectedAgeStage + expectedCountStage,
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
        // MAX_ITEMS - 1 distinct-timestamp entries + 2 sharing the OLDEST
        // timestamp = MAX_ITEMS + 1, so exactly one of the tied pair must go
        // and the tie-break is the only thing that can choose.
        //
        // 🔴 Both counts and the tied age are derived from MAX_ITEMS. At the
        // old bound these were 49 entries and a 100 s age, which happened to
        // make the tied pair the oldest; at MAX_ITEMS = 1000 the distinct
        // entries reach ~1000 s, the tied pair would no longer be oldest, and
        // the case would silently stop being about the eviction boundary.
        val tiedAgeMs = MAX_ITEMS * 1_000L + 500_000L
        val distinct = (0 until MAX_ITEMS - 1).map { i -> entry(id(i), ageMs = i * 1_000L) }
        val tiedA = entry("aaa-tied", ageMs = tiedAgeMs)
        val tiedZ = entry("zzz-tied", ageMs = tiedAgeMs)
        assertTrue(
            "fixture is broken: the tied pair must be the OLDEST entries, " +
                "or the count bound evicts somebody else and the tie-break is " +
                "never consulted",
            distinct.all { it.createdAtMs > tiedA.createdAtMs },
        )
        assertTrue(
            "fixture is broken: the tied pair must be INSIDE the age bound, " +
                "or they are evicted on age and the tie-break is never " +
                "consulted",
            now - tiedA.createdAtMs <= MAX_AGE_MS,
        )

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
        //
        // 🔴 Sized over MAX_ITEMS. At 60 entries of 3 MiB this case evicted
        // NOTHING once the bounds were raised: the reference was the empty
        // list, every shuffle also returned the empty list, and a function
        // that read list position everywhere would have passed. A determinism
        // guard over an input that triggers no eviction is a guard over
        // `emptyList() == emptyList()`.
        val rng = Random(97_003L)
        val count = MAX_ITEMS + 61
        val entries = (0 until count).map { i ->
            // Deliberate collisions: timestamps repeat every 20 entries, so
            // ties are spread through the list and also land on the boundary.
            entry(id(i), ageMs = (i % 20) * 1_000L, bytes = 3L * mib + i)
        }
        val reference = HistoryRetention.evict(entries, now)
        assertTrue(
            "fixture is vacuous: evict() returned nothing, so every shuffle " +
                "trivially agrees and this proves nothing about input order",
            reference.isNotEmpty(),
        )
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
        // 🔴 The offset must exceed MAX_ITEMS * 1_000 or the oldest entries
        // stop being in the future and the premise ("a clock before EVERY
        // entry") quietly stops holding — at MAX_ITEMS = 1000 the old
        // 1_000_000 ms offset put the last entry exactly on `now`.
        val entries = (0..MAX_ITEMS).map { i ->
            Entry(id(i), now + 2_000_000L - i * 1_000L, small)
        }
        assertTrue(
            "fixture is broken: not every entry is in the future, so this is " +
                "no longer a backwards-clock case",
            entries.all { it.createdAtMs > now },
        )
        assertEquals(
            "a backwards clock must disable only the age bound, not the pass",
            listOf(id(MAX_ITEMS)),
            HistoryRetention.evict(entries, now),
        )
    }

    // --- the constants ------------------------------------------------------

    @Test
    fun theBoundsAreTheBriefsNumbers() {
        // Literals, so changing a bound is a visible decision. This is the ONE
        // place the numbers are written down; everything else, including
        // DictationHistoryBoundsSeamTest, compares against the constants.
        assertEquals(1_000, MAX_ITEMS)
        assertEquals(2L * 1024 * 1024 * 1024, MAX_BYTES)
        assertEquals(365L * 24 * 60 * 60 * 1000, MAX_AGE_MS)
    }

    @Test
    fun theDefaultBoundsValueIsExactlyTheConstants() {
        // evict()'s default argument. A Bounds.DEFAULT that drifted from the
        // constants would change the policy for every caller that does not
        // pass bounds — which is all of production — while leaving the
        // literals above green.
        assertEquals(
            "HistoryRetention.Bounds.DEFAULT is not the constants on this " +
                "object, so the policy every default-argument caller gets is " +
                "not the policy documented here",
            HistoryRetention.Bounds(MAX_ITEMS, MAX_BYTES, MAX_AGE_MS),
            HistoryRetention.Bounds.DEFAULT,
        )
    }

    @Test
    fun anExplicitBoundsOverridesTheConstantsForEveryStage() {
        // The injection seam itself, in the pure object: all three bounds must
        // actually be read from the argument. A stage that kept reading its
        // constant would be invisible — at the production numbers the small
        // bounds the store's tests pass would simply never bite.
        // One stage tightened at a time, the other two left at the production
        // constants: a shared tiny Bounds made the byte stage evict the entry
        // the count assertion was about, which is a mutant-that-removes-the-
        // guard-with-its-enclosing-condition in fixture form — it fails, but
        // for the wrong stage.
        val tinyItems = HistoryRetention.Bounds(maxItems = 2)
        val tinyBytes = HistoryRetention.Bounds(maxBytes = 203L)
        val tinyAge = HistoryRetention.Bounds(maxAgeMs = 30_000L)

        assertEquals(
            "maxItems was not read from the supplied Bounds",
            listOf(id(2)),
            HistoryRetention.evict(
                (0..2).map { i -> entry(id(i), ageMs = i * 1_000L) },
                now,
                tinyItems,
            ),
        )
        assertEquals(
            "maxBytes was not read from the supplied Bounds",
            listOf("b"),
            HistoryRetention.evict(
                // 150 + 100 = 250 against 203: overshoots, and neither size
                // is a divisor of the bound.
                listOf(Entry("a", now, 150L), Entry("b", now - 1_000L, 100L)),
                now,
                tinyBytes,
            ),
        )
        assertEquals(
            "maxAgeMs was not read from the supplied Bounds",
            listOf("stale"),
            HistoryRetention.evict(
                listOf(entry("fresh", ageMs = 0), entry("stale", ageMs = 30_001L)),
                now,
                tinyAge,
            ),
        )
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

    /**
     * Zero-padded so `id ASC` and index order agree, making ties readable.
     * Four digits because MAX_ITEMS is now 1000 and `%03d` would make
     * `rec-1000` sort before `rec-999`.
     */
    private fun id(i: Int) = "rec-%04d".format(i)

    /**
     * Sizes deliberately overshoot every bound and are NOT multiples of the
     * step: a fixture that can only land exactly on a bound cannot see a
     * mutant that moves the comparison by one.
     *
     * 🔴 Re-derived for the raised bounds, and both changes were load-bearing
     * rather than cosmetic — each one turned a positive control from firing to
     * silent:
     *
     *  - **Count.** `n` was up to 80 entries, which cannot pressure
     *    MAX_ITEMS = 1000 at all; `sawCountPressure` would have been 0 and the
     *    count half of the property suite vacuous.
     *  - **Age.** The age span was `nextInt(2_000_000_003)` ms ≈ 23 days,
     *    which overshot a 14-day bound but is a *sixteenth* of 365 days —
     *    `java.util.Random.nextInt` is Int-typed so it cannot reach a year on
     *    its own. Expired entries are now drawn from an explicit branch
     *    instead, which also keeps most entries recent so the count bound can
     *    still be reached in the same trial.
     */
    private fun randomEntries(rng: Random): List<Entry> {
        // Up to 1426 entries: overshoots MAX_ITEMS = 1000 even after ~1 in 8
        // are expired, and is not a multiple of it.
        val n = rng.nextInt(1_427)
        return (0 until n).map { i ->
            val bytes = if (rng.nextInt(8) == 0) {
                // Occasionally a single recording bigger than the whole
                // budget, so the carve-out branch is actually reached.
                MAX_BYTES + 1 + rng.nextInt(400_000_000)
            } else {
                rng.nextInt(31_000_003).toLong()
            }
            // ~1 in 8 past the age bound by up to ~23 days, the rest within
            // ~23 days of now and so comfortably inside it. Plus up to ~1 day
            // forward, which keeps future-dated entries in the mix.
            val ageMs = if (rng.nextInt(8) == 0) {
                MAX_AGE_MS + 86_400_001L + rng.nextInt(2_000_000_003)
            } else {
                rng.nextInt(2_000_000_003).toLong()
            }
            Entry(
                id = "r-%04d".format(i),
                createdAtMs = now - ageMs + rng.nextInt(86_400_011),
                audioBytes = bytes,
            )
        }
    }

    /**
     * 🔴 Pins the arithmetic [HistoryRetention]'s kdoc asserts. **The claim
     * INVERTED on 2026-10-17** and the test inverted with it.
     *
     * It used to assert the byte stage **cannot bind** — a dormant backstop,
     * never a live code path. Raising `BubbleDictation.MAX_RECORDING_MS` from
     * 120 s to 600 s broke it, which is exactly what its own warning said would
     * happen: *"If you raised MAX_RECORDING_MS (long-form recording) and landed
     * here: that is the point."* It fired as designed.
     *
     * So the byte bound is now **LOAD-BEARING**, deliberately. 1000 items of at
     * most 600 s at 32 kbps is ~2.4 GB against a 2 GiB budget, so the byte
     * stage trims. That was read before accepting it, as the old warning
     * instructed, and the stage is safe to rely on: it evicts newest-first and
     * carries the oversize carve-out, so the newest surviving entry is kept
     * even when it alone blows the budget — history exceeds [MAX_BYTES] by at
     * most one recording and only until the next one lands. The failure mode
     * the carve-out exists to prevent (a long dictation that silently vanishes
     * every time) therefore does not appear at the new cap.
     *
     * ⚠ What this test can no longer tell you: the byte stage's behaviour is
     * now reachable in production and is pinned by the byte-stage cases in this
     * class, not by this one. This assertion is only about which regime the
     * constants put us in.
     */
    @Test
    fun theByteBoundIsLoadBearingAtTheCurrentConstants() {
        val capSeconds = BubbleDictation.MAX_RECORDING_MS / 1000.0
        val worstBitrate = maxOf(
            AudioCaptureLogic.OPUS_BITRATE,
            AudioCaptureLogic.AAC_BITRATE,
        )
        val largestPossibleItem = (capSeconds * worstBitrate / 8).toLong()
        val worstCaseTotal = largestPossibleItem * MAX_ITEMS

        assertTrue(
            "the byte stage has gone DORMANT again: $MAX_ITEMS items of at most " +
                "$largestPossibleItem B total $worstCaseTotal B, which is under " +
                "MAX_BYTES=$MAX_BYTES. That is not wrong, but it inverts the " +
                "regime HistoryRetention's kdoc now documents — if a constant " +
                "was lowered, restore the 'cannot bind' wording in both places.",
            worstCaseTotal >= MAX_BYTES,
        )

        // Positive control: the comparison can fail. Without this, a bug that
        // made either side zero would leave the assertion vacuously true.
        assertTrue(
            "the worst-case total computed as $worstCaseTotal B, which is not a " +
                "plausible non-zero size — the arithmetic above is wired to nothing",
            worstCaseTotal > 100L * 1024 * 1024,
        )
    }

    private fun randomNow(rng: Random): Long = now + rng.nextInt(1_000_003) - 500_001
}
