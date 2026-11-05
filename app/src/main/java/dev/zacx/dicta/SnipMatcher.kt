package dev.zacx.dicta

/**
 * snip's own search, ported **for semantics** rather than improved.
 *
 * ## 🔴 This is a SUBSTRING matcher, not a fuzzy one, and that is deliberate
 *
 * Ported from `ZacxDev/snip`'s `internal/matcher/matcher.go` and
 * `internal/picker/picker.go:126`. There is no scoring and no edit distance:
 * a token matches a row when it is a **substring** of one of the row's label
 * words or of one of its `search_terms`. Upstream carries an explicit note
 * saying its audit tooling models exactly this matcher, so replacing it with
 * something cleverer is a deliberate v2 with its own tests — not a silent
 * improvement to be slipped in here. A picker whose order disagrees with
 * `snip`'s own is worse than one that is merely simple: the user has already
 * learned which row comes first.
 *
 * ## 🔴 Hyphens are not word characters
 *
 * [WORD] is `[a-z0-9]+`, so `kubeconfig-homelab` tokenises to
 * `["kubeconfig", "homelab"]` and a query token that *contains* a hyphen can
 * therefore match no label word at all. That is measured upstream history, not
 * an accident of the regex: a hyphen-keeping tokenizer there invented false
 * findings until it was replaced, and a test pins it.
 *
 * ## 🔴 Normalise ONCE, before any matching
 *
 * [rank] lowercases and trims the query and then hands the normalised string
 * to [termMatches], which does **no** case folding of its own. Upstream watched
 * a test go red — `"SSH DES"` resolving to zero rows — before that was true, so
 * the asymmetry is load-bearing rather than tidy: calling [termMatches]
 * directly with un-normalised text is a caller error, and the kdoc there says
 * so.
 *
 * ## The accepted blind spot
 *
 * `token in word` is a substring test, so a query `ask` matches a label
 * spelling `task`. Upstream knows and keeps it. `SnipMatcherTest` pins it as
 * **intended behaviour** rather than reporting it as a defect; changing it
 * changes snip's results, which is a decision for snip.
 */
object SnipMatcher {

    /**
     * A label "word". Lower-case alphanumerics only — see the hyphen note on
     * the object.
     */
    private val WORD = Regex("[a-z0-9]+")

    /** The lower-cased alphanumeric runs of [label], in order. */
    fun labelWords(label: String): List<String> =
        WORD.findAll(label.lowercase()).map { it.value }.toList()

    /**
     * Whether one already-normalised [token] matches [row].
     *
     * @param useLabel when false, only `search_terms` are consulted — which is
     *   what makes the "declared" group in [rank] a different question from
     *   plain membership.
     *
     * ### The `isNotEmpty()` on a `search_terms` entry is a PORT-FIDELITY
     * guard, and 🔴 **no production caller can reach it**
     *
     * It mirrors upstream's own defensive `st == ""` skip
     * (`internal/matcher/matcher.go:72-77`), which upstream keeps while
     * disclaiming it in the same breath: no reachable caller produces an empty
     * token. The same is true here, for two independent reasons — [tokensOf]
     * drops empty tokens, so no token reaching this function is ever `""`; and
     * for a non-empty token `"".contains(token)` is already false. A
     * whitespace-only query never arrives at all: [rank] answers it at the
     * BROWSE branch.
     *
     * So this line is not load-bearing on a live path, and a maintainer must
     * not read it as if it were. It is here because the port is faithful to
     * upstream's semantics rather than improved on, which is this object's
     * whole posture. `SnipMatcherTest` exercises it with a direct
     * `tokenMatches("", …)` call, deliberately labelled as a port-fidelity
     * check rather than as reachable regression coverage — and mutant
     * `m-snip05` in `scripts/mutants/snip-picker.tsv` is recorded the same way.
     *
     * An earlier version of this paragraph claimed an empty token "is reachable
     * from a query of pure punctuation". That is **false**: `"!!!"` tokenises
     * to `["!!!"]`, one non-empty token.
     */
    fun tokenMatches(token: String, row: Snippet, useLabel: Boolean): Boolean {
        if (useLabel) {
            for (word in labelWords(row.label)) if (word.contains(token)) return true
        }
        for (term in row.searchTerms) {
            if (term.isNotEmpty() && term.lowercase().contains(token)) return true
        }
        return false
    }

    /**
     * Whether every whitespace-separated token of [term] matches [row] — a
     * multi-token query is an **AND**, so `"ssh des"` wants both.
     *
     * 🔴 [term] must already be normalised; [rank] is the one place that does
     * it. An empty or whitespace-only term matches nothing here (it has no
     * tokens); "a blank query shows everything" is [rank]'s BROWSE rule and
     * lives there, because it is a decision about the list rather than about
     * one row.
     */
    fun termMatches(term: String, row: Snippet): Boolean =
        tokensOf(term).let { it.isNotEmpty() && it.all { t -> tokenMatches(t, row, true) } }

    /** [termMatches] restricted to `search_terms` — the "declared" test. */
    fun termMatchesDeclared(term: String, row: Snippet): Boolean =
        tokensOf(term).let { it.isNotEmpty() && it.all { t -> tokenMatches(t, row, false) } }

    private fun tokensOf(term: String): List<String> =
        term.split(WHITESPACE).filter { it.isNotEmpty() }

    private val WHITESPACE = Regex("\\s+")

    /**
     * The order the picker renders: `snip`'s `Rank`.
     *
     * ```
     * norm = query.trim().lowercase()
     * norm empty            -> BROWSE: every row, in the order supplied
     * kept                  = rows matching norm (label words OR search_terms)
     * declared              = kept whose search_terms reached every token
     * out                   = declared ++ (kept - declared)
     * ```
     *
     * The supplied order is preserved **within** each group, so the caller's
     * ordering (the catalog's own) is what breaks ties — there is no score to
     * sort on and inventing one would reorder rows the user has memorised.
     *
     * 🔴 A blank or whitespace-only query is **BROWSE**, a named behaviour and
     * not a degenerate case of filtering: it is how the picker opens, and a
     * matcher that returned nothing for it would look like an empty catalog.
     */
    /**
     * Whether [query] selects BROWSE rather than filtering.
     *
     * 🔴 This exists so the predicate has **one** definition, and it is a
     * rename rather than a new rule. [rank] returns every row unchanged for a
     * blank query; `SnipPickerActivity` has to know about exactly that branch,
     * because most-recently-used ordering applies to BROWSE and to nothing
     * else — a typed query keeps `snip`'s declared-before-labeled order with
     * the catalog breaking ties.
     *
     * Two copies of `query.trim()…` would be two chances to disagree, and they
     * would disagree on the input nobody types by hand: a whitespace-only
     * query, where one copy browses and the other filters to nothing. [rank]
     * calls this, so the branch below and this function cannot drift apart.
     */
    fun isBrowse(query: String): Boolean = query.trim().isEmpty()

    fun rank(rows: List<Snippet>, query: String): List<Snippet> {
        if (isBrowse(query)) return rows
        val norm = query.trim().lowercase()
        val kept = rows.filter { termMatches(norm, it) }
        val declared = kept.filter { termMatchesDeclared(norm, it) }
        val labeled = kept.filterNot { termMatchesDeclared(norm, it) }
        return declared + labeled
    }
}
