package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SnipMatcher], against the semantics it ports from `ZacxDev/snip`.
 *
 * ## 🔴 Every expectation here is a LITERAL, spelled out
 *
 * Not one of them is computed by calling the matcher a second way. The ranked
 * label list is written out in full, because an expectation derived from the
 * implementation it tests pins nothing — and this file's whole job is to pin
 * *snip's* order rather than this port's behaviour.
 *
 * ## 🔴 The fixtures are SYNTHETIC
 *
 * `ZacxDev/dicta` is a public repository and the real catalog on the author's
 * machine carries mesh addresses and client repository paths. Nothing here is
 * derived from it: the labels, bodies and search terms are invented, and they
 * are chosen pairwise distinct so no assertion can pass by two fixtures
 * happening to agree.
 *
 * ## Red-before-green, stated honestly
 *
 * These are **not** regression tests. There is no pre-change tree in which
 * [SnipMatcher] exists and is wrong — the class arrives with this feature, so
 * "red at `1326ce4`" would be a compile error rather than a guard watched
 * failing. What stands in for it is a mutation sweep over the matcher's own
 * expressions (the `min`/`all` choice, the `[a-z0-9]+` word class, the
 * normalise-once ordering, the declared/labeled split), each watched to turn
 * a named test below red. The matrix is in the pull request, stamped with the
 * commit it was measured on. The same note applies to every new file in this
 * change and is not repeated.
 */
class SnipMatcherTest {

    private fun snip(
        label: String,
        body: String = "body of $label",
        terms: List<String> = emptyList(),
        category: String = "misc",
    ) = Snippet(label = label, body = body, searchTerms = terms, category = category)

    /** Four rows whose labels, terms and categories are pairwise distinct. */
    private val rows = listOf(
        snip("Homelab shell", terms = listOf("ssh", "desktop"), category = "ssh"),
        snip("Mesh dashboard", terms = listOf("grafana", "charts"), category = "path"),
        snip("Ssh desktop notes", terms = listOf("writing"), category = "prompt"),
        snip("Task runner", terms = listOf("build"), category = "path"),
    )

    private fun labelsOf(ranked: List<Snippet>) = ranked.map { it.label }

    // --- BROWSE --------------------------------------------------------------

    @Test
    fun aBlankQueryIsBrowseAndKeepsTheSuppliedOrder() {
        // 🔴 A named behaviour, not a degenerate filter: it is how the picker
        // opens, and a matcher returning nothing here would be
        // indistinguishable from an empty catalog.
        assertEquals(
            listOf("Homelab shell", "Mesh dashboard", "Ssh desktop notes", "Task runner"),
            labelsOf(SnipMatcher.rank(rows, "")),
        )
        assertEquals(
            "a whitespace-only query is BROWSE too — it trims to empty",
            listOf("Homelab shell", "Mesh dashboard", "Ssh desktop notes", "Task runner"),
            labelsOf(SnipMatcher.rank(rows, "   \t ")),
        )
    }

    // --- substring, not fuzzy ------------------------------------------------

    @Test
    fun itIsASubstringMatchAndNotAFuzzyOne() {
        // "hmlb" is a subSEQUENCE of "homelab" and would match under any fuzzy
        // matcher. Upstream has no scoring and no edit distance, and its audit
        // tooling models exactly that — so matching here would be a different
        // matcher wearing this one's tests.
        assertEquals(emptyList<String>(), labelsOf(SnipMatcher.rank(rows, "hmlb")))
        assertEquals(
            "the contiguous spelling must match, or the test above proves " +
                "nothing about fuzziness",
            listOf("Homelab shell"),
            labelsOf(SnipMatcher.rank(rows, "homelab")),
        )
    }

    @Test
    fun aSubstringOfALabelWordMatchesWhichIsTheAcceptedUpstreamBlindSpot() {
        // 🔴 PINNED AS INTENDED, not reported as a bug. `token in word` is a
        // substring test, so "ask" matches a label spelling "task". Upstream
        // knows and keeps it; changing it changes snip's own results, which is
        // a decision for snip and not for this port.
        assertEquals(listOf("Task runner"), labelsOf(SnipMatcher.rank(rows, "ask")))
    }

    // --- multi-token AND -----------------------------------------------------

    @Test
    fun multipleTokensAreAnAnd() {
        // "Homelab shell" declares both ssh and desktop; "Ssh desktop notes"
        // carries both in its LABEL. "Task runner" has neither.
        assertEquals(
            listOf("Homelab shell", "Ssh desktop notes"),
            labelsOf(SnipMatcher.rank(rows, "ssh desktop")),
        )
        assertEquals(
            "a second token that nothing satisfies must empty the result, or " +
                "the AND is really an OR",
            emptyList<String>(),
            labelsOf(SnipMatcher.rank(rows, "ssh nonesuch")),
        )
    }

    // --- normalise once ------------------------------------------------------

    @Test
    fun theQueryIsNormalisedOnceSoCaseAndSurroundingSpaceDoNotMatter() {
        // Upstream watched a test go red — "SSH DES" resolving to zero rows —
        // before the normalisation moved ahead of the matching.
        val expected = listOf("Homelab shell", "Ssh desktop notes")
        assertEquals(expected, labelsOf(SnipMatcher.rank(rows, "SSH DES")))
        assertEquals(expected, labelsOf(SnipMatcher.rank(rows, "  ssh des  ")))
        assertEquals(expected, labelsOf(SnipMatcher.rank(rows, "Ssh Des")))
    }

    @Test
    fun termMatchesDoesNoCaseFoldingOfItsOwnSoCallersMustNormalise() {
        // 🔴 The asymmetry is load-bearing and is why `rank` is the only entry
        // point that should be called with user text. A `termMatches` that
        // lowercased its argument would normalise TWICE, which is harmless
        // today and is exactly how the responsibility stops being in one
        // place.
        val row = rows[0]
        assertTrue(SnipMatcher.termMatches("ssh", row))
        assertFalse(
            "termMatches folded case itself, so normalisation now happens in " +
                "two places and `rank`'s single normalise is no longer the rule",
            SnipMatcher.termMatches("SSH", row),
        )
    }

    // --- tokenisation --------------------------------------------------------

    @Test
    fun labelWordsAreLowerCasedAlphanumericRuns() {
        assertEquals(
            listOf("deploy", "flux", "v2"),
            SnipMatcher.labelWords("Deploy-Flux v2!"),
        )
        assertEquals(emptyList<String>(), SnipMatcher.labelWords("—/—"))
    }

    @Test
    fun hyphensSeparateLabelWordsSoATokenCarryingOneMatchesNoLabelWord() {
        // 🔴 Measured upstream history rather than a property of the regex: a
        // hyphen-keeping tokenizer there invented false findings until it was
        // replaced. `[a-z0-9]+` is the fix and this is the test for it.
        val hyphenated = listOf(snip("Mesh-dashboard link"))
        assertEquals(
            "a token spanning the hyphen matched a label word, so the " +
                "tokenizer is keeping hyphens",
            emptyList<String>(),
            labelsOf(SnipMatcher.rank(hyphenated, "mesh-dash")),
        )
        assertEquals(
            "neither side of the hyphen matches either, so this fixture is " +
                "not exercising the split at all",
            listOf("Mesh-dashboard link"),
            labelsOf(SnipMatcher.rank(hyphenated, "dashboard")),
        )
    }

    // --- search_terms --------------------------------------------------------

    @Test
    fun aBlankSearchTermEntryIsSkippedAsAPortFidelityInvariantNotReachableCoverage() {
        // 🔴 LABELLED, because what this asserts is narrower than it looks.
        //
        // The first assertion is the reachable claim: a row whose search_terms
        // are blank must not answer an ordinary query. That holds with or
        // without `SnipMatcher`'s `isNotEmpty()`, because `"".contains("anything")`
        // is already false.
        //
        // The second is an INVARIANT GUARD on that `isNotEmpty()`, and the only
        // thing that kills mutant m-snip05. It calls tokenMatches with an input
        // NO PRODUCTION CALLER CAN PRODUCE: tokensOf drops empty tokens, and a
        // whitespace-only query is answered at rank's BROWSE branch. So it is a
        // port-fidelity check against upstream's own defensive `st == ""` skip
        // (internal/matcher/matcher.go:72-77) — not evidence that a live path is
        // covered. MEASURED: delete this one call and m-snip05 SURVIVES at 15
        // tests / 0 failures with the rank-level assertion still in place.
        //
        // An earlier version of this comment claimed an empty token "is
        // reachable from a query of pure punctuation". That is false: "!!!"
        // tokenises to ["!!!"], one non-empty token.
        val blank = listOf(snip("Zebra crossing", terms = listOf("", "")))
        assertEquals(emptyList<String>(), labelsOf(SnipMatcher.rank(blank, "anything")))
        assertFalse(
            "the port dropped upstream's defensive blank-entry skip: a blank " +
                "search term matched an empty token. No production caller can " +
                "hand one in, so this is a fidelity claim about the port, not " +
                "a claim that any live query is affected",
            SnipMatcher.tokenMatches("", blank[0], useLabel = false),
        )
    }

    @Test
    fun aRowReachedOnlyThroughItsCategoryIsNotMatched() {
        // Upstream's matcher does not consult the category, so neither does
        // this. Adding it would silently widen the result set against snip's.
        val onlyCategory = listOf(snip("Zebra crossing", terms = listOf("stripes"), category = "kubeconfig"))
        assertEquals(emptyList<String>(), labelsOf(SnipMatcher.rank(onlyCategory, "kubeconfig")))
    }

    @Test
    fun aRowReachedOnlyThroughItsBodyIsNotMatched() {
        // Same shape, the other unmatched field: the body is what gets copied,
        // not what gets searched.
        val onlyBody = listOf(snip("Zebra crossing", body = "kubeconfig --context staging"))
        assertEquals(emptyList<String>(), labelsOf(SnipMatcher.rank(onlyBody, "staging")))
    }

    // --- the declared / labeled split ---------------------------------------

    @Test
    fun declaredMatchesRankAboveLabelOnlyMatchesAndKeepTheSuppliedOrderWithin() {
        // 🔴 The whole of `Rank`'s ordering, in one literal. "ssh" reaches
        // row 1 through its search_terms (declared) and rows 3 and 4 only
        // through their label words — and the supplied order between those two
        // is preserved, which is the half a set comparison cannot see.
        val ordered = listOf(
            snip("Sshuttle wrapper", terms = listOf("tunnel")),      // label only, first supplied
            snip("Bastion hop", terms = listOf("ssh", "jump")),      // declared
            snip("Open ssh config", terms = listOf("editor")),       // label only, second supplied
        )
        assertEquals(
            listOf("Bastion hop", "Sshuttle wrapper", "Open ssh config"),
            labelsOf(SnipMatcher.rank(ordered, "ssh")),
        )
    }

    @Test
    fun aRowThatIsBothDeclaredAndLabelledAppearsOnceInTheDeclaredGroup() {
        // The groups are a partition, not two passes over the same list: a row
        // satisfying both tests must not be emitted twice.
        val both = listOf(snip("Ssh desktop", terms = listOf("ssh")))
        assertEquals(listOf("Ssh desktop"), labelsOf(SnipMatcher.rank(both, "ssh")))
    }

    @Test
    fun termMatchesDeclaredIgnoresTheLabelEntirely() {
        val labelOnly = snip("Grafana board", terms = listOf("charts"))
        assertTrue(
            "the label-aware test must see the label, or the pair below is " +
                "comparing nothing",
            SnipMatcher.termMatches("grafana", labelOnly),
        )
        assertFalse(
            "termMatchesDeclared consulted the label, so the declared group " +
                "is not the \"search_terms reached it\" group at all",
            SnipMatcher.termMatchesDeclared("grafana", labelOnly),
        )
    }

    @Test
    fun aQueryMatchingNothingReturnsNoRowsRatherThanEverything() {
        assertEquals(emptyList<String>(), labelsOf(SnipMatcher.rank(rows, "nonesuch")))
    }
}
