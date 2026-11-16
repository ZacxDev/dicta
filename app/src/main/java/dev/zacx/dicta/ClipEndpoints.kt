package dev.zacx.dicta

/**
 * URL construction for the shared-clipboard service (`clipstore`).
 *
 * [SttEndpoints]'s split, applied to the second service: the path and the query
 * shape are the two things easy to get subtly wrong, so they are asserted
 * against **literal** expected strings in `ClipEndpointsTest` rather than
 * inferred from the implementation. `SttEndpoints.kt:5-10` is explicit that
 * this is the whole reason such an object exists, and the rule is reused rather
 * than restated.
 *
 * 🔴 **The `Authorization` header is deliberately NOT here.** It is
 * [SttEndpoints.authorization], reused: *"exactly one space, the literal
 * `Bearer`, and the token untouched"* is one rule, and a second copy of it is a
 * second chance to trim or re-case a token into a 401. The two services take
 * different tokens; they do not take different header shapes.
 *
 * ## 🔴 Why this is a new PORT on `10.42.0.10` and not a new host
 *
 * `res/xml/network_security_config.xml` exempts exactly one domain from
 * cleartext policy, and `NetworkManifestTest.exactlyOneDomainIsExempted`
 * asserts the exempted list is **exactly** `["10.42.0.10"]`. A `<domain-config>`
 * carries no port, so co-hosting on that address commits **no new mesh fact** —
 * a port number — and leaves both that guard and
 * `theDefaultBaseUrlPointsAtTheExemptedHost` green. A second IP would be a
 * second mesh address in a PUBLIC repository and would turn the first of those
 * guards red, which is the test working rather than an obstacle.
 */
object ClipEndpoints {

    /**
     * The mesh address of the self-hosted clip store. Plain HTTP, inside the
     * Nebula tunnel, for the reason `network_security_config.xml` gives at
     * length for the transcription service.
     *
     * Stored in plaintext `SharedPreferences` by [ClipSettings], deliberately:
     * *"it is a LAN/mesh address, not a secret, and encrypting it would only
     * make it unreadable in a bug report"* (`SttSettings.kt:74-76`).
     */
    const val DEFAULT_BASE_URL: String = "http://10.42.0.10:8139"

    const val CLIPS_PATH: String = "/v1/clips"

    /** `POST` target for a capture, and the unbounded `GET` target. */
    fun clips(baseUrl: String): String = join(baseUrl, CLIPS_PATH)

    /**
     * `GET` target for the newest [limit] clips.
     *
     * 🔴 `limit` is not cosmetic. There is **no expiry** on this store
     * (settled decision 7), so it grows without bound and the newest-N bound
     * is the only one there is: a picker that asked for everything would, in a
     * year, parse a year of clipboard history to render ten rows.
     */
    fun clips(baseUrl: String, limit: Int): String =
        join(baseUrl, CLIPS_PATH) + "?limit=" + limit

    /**
     * `DELETE` target for one clip.
     *
     * The id is a server-assigned integer, so there is nothing to escape here
     * and nothing a caller could put in it — [Clip.id] is a `Long` parsed out
     * of the service's own response, never user text.
     */
    fun clip(baseUrl: String, id: Long): String =
        join(baseUrl, CLIPS_PATH) + "/" + id

    /**
     * Joins a user-supplied base URL to a fixed path.
     *
     * [SttEndpoints]'s own `join`, repeated rather than shared, and the reason
     * is the same one `HistoryRow.PREVIEW_MAX` gives for not deriving itself
     * from `SnipRow.PREVIEW_MAX`: the two are one line of identical code today
     * because two services happen to be addressed the same way, not because
     * one rule governs both. Exposing `SttEndpoints.join` would make a change
     * to the transcription service's URL handling silently change the clip
     * store's.
     *
     * The base URL is typed by a human into a settings field, so a trailing
     * slash is expected rather than exceptional; without the `trimEnd` the
     * result is `.../8139//v1/clips`.
     *
     * ⚠ **CORRECTION.** This sentence used to end *"which the service's router
     * does not match"*, marked 🔴. **Measured false** against the live service:
     * `…:8139//v1/clips` answers **307**, because Go's `http.ServeMux`
     * path-cleans and redirects. Nothing depends on the claim — the `trimEnd`
     * makes the doubled slash unreachable — and the `trimEnd` is still right,
     * because a 307 is a wasted round trip and `HttpURLConnection` does not
     * follow a redirect across a method-preserving POST by default. But a
     * comment is a claim, and this one was a confident statement about another
     * service's behaviour that was never checked.
     */
    private fun join(baseUrl: String, path: String): String =
        baseUrl.trim().trimEnd('/') + path

    /**
     * Why [baseUrl] cannot be a clip-store base URL, or `null` when it can.
     *
     * ## 🔴 Why this exists at all
     *
     * `SettingsActivity` saved this field as **free text with no validation**,
     * and the capture-loss defect this branch fixes was reached through it:
     * paste something that is not the clip store and every `POST /v1/clips`
     * answers 404, which [ClipHttp.PERMANENT_STATUSES] used to treat as
     * permanent and the flusher therefore DELETED the capture for.
     *
     * 🔴 **This is defence in depth and NOT the fix.** The scenario measured —
     * the operator pasting the *transcription* base URL, `…:8118`, four digits
     * from the clip store's `…:8139` — produces a string this function accepts,
     * because it is a perfectly well-formed base URL. No syntactic check can
     * tell two valid mesh addresses apart. The fix is that a 404 no longer
     * destroys anything; this only closes the cases that are detectable, and
     * says so rather than claiming more.
     *
     * ## What is rejected, and why each
     *
     *  * **A scheme that is not `http`/`https`.** Anything else is not a URL
     *    this client can open, so it would surface as a transport failure —
     *    i.e. as "the mesh is down", which is the wrong diagnosis forever.
     *  * **No host.** `http://` and `http:///v1` parse and reach nothing.
     *  * **A path, query or fragment.** This is a BASE url: [clips] appends
     *    `/v1/clips` to it. Pasting the full endpoint — the single most likely
     *    paste, since it is what a `curl` line contains — yields
     *    `…/v1/clips/v1/clips`, a 404 on every POST.
     *  * **Over [MAX_BASE_URL_CHARS].** A base URL that is a kilobyte long is
     *    the only way the request URI can get near a 414, since the body is
     *    never in the URI. 2,048 is the conventional URI bound and is three
     *    orders of magnitude above any real mesh address.
     *
     * 🔴 **It does NOT normalise.** An input that is wrong is refused and
     * reported; nothing is silently rewritten into something that works,
     * because a field that quietly edits what the operator typed is how they
     * come to believe a setting says something it does not. The sole exception
     * is surrounding whitespace, which is a property of pasting and not of
     * what was pasted.
     *
     * @return a short reason suitable for the settings status line, or `null`
     *   when [baseUrl] is acceptable. An EMPTY string is acceptable: it means
     *   "clear the setting and use [DEFAULT_BASE_URL]", which is how the
     *   operator resets the field.
     */
    fun rejectionFor(baseUrl: String): String? {
        val trimmed = baseUrl.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.length > MAX_BASE_URL_CHARS) {
            return "it is longer than $MAX_BASE_URL_CHARS characters"
        }
        val scheme = trimmed.substringBefore("://", missingDelimiterValue = "")
        if (scheme != "http" && scheme != "https") {
            return "it must start with http:// or https://"
        }
        val authority = trimmed.substringAfter("://")
        if (authority.isEmpty()) return "it has no host"
        // Everything after the authority is a path, a query or a fragment, and
        // none of the three belongs in a base URL. A single trailing slash is
        // allowed because [join] already trims it and a human types it.
        val afterHost = authority.trimEnd('/')
        if (afterHost.isEmpty()) return "it has no host"
        if (afterHost.any { it == '/' || it == '?' || it == '#' }) {
            return "it must be a host and port only, with no path — " +
                "Dicta appends $CLIPS_PATH itself"
        }
        if (afterHost.any { it.isWhitespace() }) return "it contains a space"
        return null
    }

    /**
     * The longest base URL the settings field will accept.
     *
     * 🔴 The point is not tidiness: the request URI is `baseUrl + /v1/clips`
     * and **the capture body is never in it**, so an over-long base URL is the
     * only route to an HTTP 414 — which, as [ClipHttp.PERMANENT_STATUSES]
     * records, used to destroy the capture.
     */
    const val MAX_BASE_URL_CHARS: Int = 2_048
}
