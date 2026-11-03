package dev.zacx.dicta

/**
 * One row of the [snip](https://github.com/ZacxDev/snip) catalog.
 *
 * ## 🔴 There is no `trigger` field, and issue #38 says there is
 *
 * The issue's body describes entries as `{trigger, label?, body}` and asks for
 * "search by trigger and label". That schema is **stale**: `trigger` was
 * removed from snip outright on 2026-10-31 and identity moved to the label.
 * Measured from `ZacxDev/snip`'s `internal/artifact/artifact.go` and confirmed
 * against a live `snippets.json`, the shape is the four fields below — so the
 * faithful reading of the acceptance line is **search_terms + label words**,
 * which is what `internal/picker/picker.go`'s `Rank` actually does and what
 * [SnipMatcher] ports.
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
