package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SnipUsageStore.order] — the browse ordering, as a pure function.
 *
 * ## 🔴 These are INVARIANT GUARDS. There is no red-at-base run, and saying so
 *
 * Every test here is an invariant guard, **not** regression coverage. The
 * honest reason: `SnipUsageStore` arrives with this change, so a run at
 * `6434700` — the 0.5.0 release commit — is a COMPILE ERROR, not a guard
 * watched going red. A compile error is not a failing test, and treating it as
 * one is how a file like this comes to read as proof of a behaviour change it
 * never demonstrated.
 *
 * The feature's observable behaviour cannot be expressed at base at all: to
 * assert "browse is ordered by use" you must first be able to record use, and
 * nothing at base can. So no test of this feature can be red at base, and this
 * file does not pretend otherwise.
 *
 * **What IS evidence, in its place:**
 *
 *  * a **partial-revert control** — deleting the ordering call from
 *    `SnipPickerActivity.filterRows` reddens
 *    [browseOrdersMostRecentlyUsedFirst] and its activity-level counterparts
 *    with their own messages. The run is recorded in the pull request.
 *  * the **mutant batch** in `scripts/mutants/snip-mru.tsv`, which is what
 *    shows these guards can go red at all rather than passing vacuously.
 *
 * ## 🔴 Fixture values are pairwise distinct AND distinct from the sentinel
 *
 * The stamps below are 1000/2000/3000, never 0 and never `Long.MIN_VALUE`,
 * which is the value [SnipUsageStore.order] substitutes for "never used". A
 * fixture that could only ever produce the sentinel's own value cannot see a
 * mutant that hardcodes it, and would survive a fully green suite.
 */
class SnipUsageOrderTest {

    private fun snip(label: String) = Snippet(
        label = label,
        body = "body-$label",
        searchTerms = listOf(label),
        category = "test",
    )

    /** Catalog order is a, b, c — deliberately NOT the expected output order. */
    private val catalog = listOf(snip("a"), snip("b"), snip("c"))

    private fun labels(rows: List<Snippet>) = rows.map { it.label }

    @Test
    fun browseOrdersMostRecentlyUsedFirst() {
        val ordered = SnipUsageStore.order(
            catalog,
            mapOf("a" to 1000L, "b" to 3000L, "c" to 2000L),
        )
        assertEquals(
            "browse must list the most recently picked snippet first; " +
                "catalog order (a, b, c) is what shipped in 0.5.0 and is the " +
                "thing this replaces",
            listOf("b", "c", "a"),
            labels(ordered),
        )
    }

    @Test
    fun theUnusedTailKeepsCatalogOrderRatherThanBeingShuffled() {
        // Only `c` has ever been picked. `a` and `b` are both "never used" and
        // therefore compare EQUAL, so the only thing that can order them is the
        // sort's stability.
        val ordered = SnipUsageStore.order(catalog, mapOf("c" to 1000L))
        assertEquals(
            "the used row must lead, and the never-used rows must stay in " +
                "catalog order behind it — an unstable sort would put them in " +
                "an arbitrary order that changes between renders",
            listOf("c", "a", "b"),
            labels(ordered),
        )
    }

    @Test
    fun aCatalogWithNoUsageAtAllIsUnchanged() {
        assertEquals(
            "with no usage recorded the browse list must be exactly the " +
                "catalog, which is 0.5.0's behaviour and the correct starting " +
                "state for a fresh install",
            listOf("a", "b", "c"),
            labels(SnipUsageStore.order(catalog, emptyMap())),
        )
    }

    @Test
    fun aStampForALabelNotInTheCatalogIsIgnoredRatherThanInsertingARow() {
        val ordered = SnipUsageStore.order(catalog, mapOf("departed" to 9000L, "b" to 1000L))
        assertEquals(
            "a stamp for a label the catalog no longer has must not add a row " +
                "and must not displace one; only `b` is ordered by it",
            listOf("b", "a", "c"),
            labels(ordered),
        )
    }

    @Test
    fun everyRowSurvivesTheOrderingRegardlessOfUsage() {
        // 🔴 The property a sort must never break, pinned separately from the
        // order itself: a `filter` written where a `sortedBy` belongs would
        // pass every assertion above that only checks the leading rows.
        val ordered = SnipUsageStore.order(catalog, mapOf("b" to 3000L))
        assertEquals("ordering must not drop or duplicate rows", 3, ordered.size)
        assertEquals(
            "the ordered set must be the input set",
            setOf("a", "b", "c"),
            ordered.map { it.label }.toSet(),
        )
    }
}
