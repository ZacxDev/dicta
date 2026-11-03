package dev.zacx.dicta

/**
 * One row of the [snip](https://github.com/ZacxDev/snip) catalog.
 *
 * ## 🔴 There is no `trigger` field, and issue #38 says there is
 *
 * The issue's body describes entries as `{trigger, label?, body}` and asks for
 * "search by trigger and label". That schema is **stale**.
 *
 * What is MEASURED is the shape: `ZacxDev/snip`'s
 * `internal/artifact/artifact.go`, confirmed against a live `snippets.json`,
 * carries exactly the four fields below and no `trigger`. So the faithful
 * reading of the acceptance line is **search_terms + label words**, which is
 * what `internal/picker/picker.go`'s `Rank` does and what [SnipMatcher] ports.
 *
 * 🔴 The removal itself is **snip's own account of its history, not a commit
 * this repo has seen**. Five files there (`internal/matcher/matcher.go:6`,
 * `internal/picker/picker.go`, `internal/recent/recent.go:6`,
 * `matcher_test.go:14,214`, `render_test.go:156`) state that `trigger` was
 * dropped on 2026-10-31 and identity moved to the label — but snip's git
 * history begins with an already-four-field import, so no commit in that
 * repository removes the field. The change predates its git history (the
 * matcher descends from an espanso-era detector, which is where a trigger
 * lived). Treat the four-field shape as fact and the removal date as snip's
 * in-source claim about itself.
 *
 * @param label REQUIRED, non-empty and UNIQUE — the row's identity. Upstream's
 *   `Find` is byte-exact on it, so two rows sharing a label make one of them
 *   unreachable; [SnipCatalog] refuses such a file rather than importing it.
 * @param body the expansion text. May be multi-line and long (747 characters
 *   in the catalog this was sized against), which is why the picker shows a
 *   truncated one-line preview — see [SnipRow].
 * @param searchTerms the row's "declared interface". A row matched through
 *   these ranks ABOVE one matched only through its label words; see
 *   [SnipMatcher.rank]. May legitimately be empty.
 * @param category e.g. `prompt` / `path` / `ssh` / `kubeconfig` / `clipboard`.
 *   Displayed, never matched on — upstream's matcher does not consult it, and
 *   adding it to the match set would be a different matcher wearing this one's
 *   tests.
 */
data class Snippet(
    val label: String,
    val body: String,
    val searchTerms: List<String>,
    val category: String,
)
