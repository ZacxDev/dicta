package dev.zacx.dicta

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads a `snippets.json` and **refuses a broken one loudly**.
 *
 * ## 🔴 Silent-empty is the failure mode that looks like the tool is dead
 *
 * That is upstream's own reasoning for validating rather than skipping bad
 * rows, and it applies harder here: the user chose a file in a document picker
 * and then long-presses the bubble to find an empty list. "Nothing happened" is
 * indistinguishable from a broken feature, so every refusal carries a
 * [Invalid.reason] that names what is wrong, and `SettingsActivity` puts it on
 * screen.
 *
 * ## What is checked, mirroring upstream
 *
 *  * the document parses as a JSON **array** (not an object, not a stream);
 *  * every element is an object;
 *  * every `label` is present and non-empty — it is the row's identity;
 *  * labels are **unique** — upstream's `Find` is byte-exact, so a duplicate
 *    makes one row permanently unreachable.
 *
 * ## 🔴 And what is deliberately NOT checked
 *
 * **Unknown keys are tolerated.** A snip release that adds a field must not
 * brick the phone's catalog, and refusing an unrecognised key would make this
 * app a gate on snip's schema evolution. By the same argument a missing `body`,
 * `category` or `search_terms` defaults to empty rather than failing: that is
 * what upstream's `json.Unmarshal` does, and its validation does not add a
 * check for them. The one field whose absence IS fatal is the one whose absence
 * breaks identity.
 *
 * `search_terms`, when present, must be an array — a string there is a
 * different schema rather than a missing field, and silently reading it as one
 * term would import a row whose declared interface is wrong.
 */
object SnipCatalog {

    /** A file that is not a usable `snippets.json`, with the reason to show. */
    class Invalid(val reason: String) : Exception(reason)

    const val KEY_LABEL: String = "label"
    const val KEY_BODY: String = "body"
    const val KEY_SEARCH_TERMS: String = "search_terms"
    const val KEY_CATEGORY: String = "category"

    /**
     * Parses [text], or throws [Invalid] naming what is wrong with it.
     *
     * An empty array is **valid** and yields no snippets: an operator who
     * cleared their catalog is not an error, and the picker's "no snippets"
     * copy covers it. That is distinct from a failed import, which leaves the
     * previous catalog alone.
     */
    fun parse(text: String): List<Snippet> {
        val array = try {
            JSONArray(text)
        } catch (_: Exception) {
            throw Invalid("the file is not a JSON array of snippets")
        }
        val out = ArrayList<Snippet>(array.length())
        val seen = HashSet<String>()
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i)
                ?: throw Invalid("entry ${i + 1} is not an object")
            val label = row.optString(KEY_LABEL, "")
            if (label.isEmpty()) throw Invalid("entry ${i + 1} has no \"label\"")
            if (!seen.add(label)) throw Invalid("the label \"$label\" appears twice")
            out += Snippet(
                label = label,
                body = row.optString(KEY_BODY, ""),
                searchTerms = searchTermsOf(row, label),
                category = row.optString(KEY_CATEGORY, ""),
            )
        }
        return out
    }

    private fun searchTermsOf(row: JSONObject, label: String): List<String> {
        if (!row.has(KEY_SEARCH_TERMS) || row.isNull(KEY_SEARCH_TERMS)) return emptyList()
        val terms = row.optJSONArray(KEY_SEARCH_TERMS)
            ?: throw Invalid("\"$KEY_SEARCH_TERMS\" on \"$label\" is not an array")
        return (0 until terms.length()).map { terms.optString(it, "") }
    }

    /** Serialises [snippets] back to upstream's own shape. */
    fun toJson(snippets: List<Snippet>): JSONArray {
        val array = JSONArray()
        for (snippet in snippets) {
            array.put(
                JSONObject()
                    .put(KEY_LABEL, snippet.label)
                    .put(KEY_BODY, snippet.body)
                    .put(KEY_CATEGORY, snippet.category)
                    .put(KEY_SEARCH_TERMS, JSONArray().apply { snippet.searchTerms.forEach(::put) }),
            )
        }
        return array
    }
}
