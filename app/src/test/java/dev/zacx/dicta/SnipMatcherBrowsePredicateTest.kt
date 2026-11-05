package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SnipMatcher.isBrowse] and [SnipMatcher.rank]'s BROWSE branch are the SAME
 * predicate.
 *
 * ## 🔴 The seam this pins, and why a unit test of `isBrowse` alone would miss it
 *
 * `SnipPickerActivity` applies most-recently-used ordering **only** when the
 * query is BROWSE, and it asks [SnipMatcher.isBrowse] which case it is in. If
 * that function and `rank`'s own branch ever disagreed, the picker would order
 * a FILTERED list by recency — the product decision that was explicitly not
 * taken — or leave the browse list in catalog order, silently reverting the
 * feature.
 *
 * `isBrowse` was extracted from `rank`, so today `rank` calls it and they cannot
 * drift. **That is an implementation detail, and this test is what makes it a
 * contract**: it asserts the two agree on every input rather than asserting
 * `rank` happens to call the function. Re-inlining the predicate in `rank` — the
 * obvious "simplification" — leaves this red.
 *
 * The inputs are the ones that separate the two plausible spellings:
 * `query.trim().isEmpty()` and `query.isEmpty()`. They agree on `""` and on
 * `"ssh"` and differ on every whitespace-only string, which is exactly the input
 * nobody types deliberately and a `TextWatcher` produces constantly — a space
 * typed before the first letter of a query.
 */
class SnipMatcherBrowsePredicateTest {

    private val rows = listOf(
        Snippet(label = "alpha", body = "a", searchTerms = listOf("alpha"), category = "t"),
        Snippet(label = "beta", body = "b", searchTerms = listOf("beta"), category = "t"),
    )

    private val browseInputs = listOf("", " ", "  ", "\t", "\n", " \t \n ")
    private val filterInputs = listOf("alpha", " alpha", "alpha ", "zzz", "a")

    @Test
    fun isBrowseAgreesWithRanksOwnBranchOnWhitespaceOnlyQueries() {
        for (q in browseInputs) {
            assertEquals(
                "isBrowse(${q.quoted()}) must be true",
                true,
                SnipMatcher.isBrowse(q),
            )
            // rank's BROWSE branch returns the input list IDENTICALLY — the same
            // instance, not merely an equal one. Any filtering path would build
            // a new list, so this distinguishes the branches without having to
            // reach inside rank.
            assertEquals(
                "rank(${q.quoted()}) must take the BROWSE branch and return " +
                    "every row in the order supplied; isBrowse said it would",
                rows,
                SnipMatcher.rank(rows, q),
            )
        }
    }

    @Test
    fun isBrowseAgreesWithRanksOwnBranchOnQueriesThatFilter() {
        for (q in filterInputs) {
            assertEquals(
                "isBrowse(${q.quoted()}) must be false",
                false,
                SnipMatcher.isBrowse(q),
            )
        }
    }

    @Test
    fun aQueryThatIsOnlyWhitespaceBrowsesRatherThanMatchingNothing() {
        // 🔴 The single input that decides between the two spellings, asserted
        // on its own so the failure message names it. `query.isEmpty()` would
        // send " " down the filtering path, where it tokenises to nothing and
        // `termMatches` keeps no rows — an empty picker with a space in the
        // search box.
        assertEquals(
            "a whitespace-only query must browse; filtering on it yields an " +
                "empty list and reads to the operator as an empty catalog",
            rows,
            SnipMatcher.rank(rows, " "),
        )
    }

    private fun String.quoted() = "\"" + replace("\t", "\\t").replace("\n", "\\n") + "\""
}
