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
     * result is `.../8139//v1/clips`, which the service's router does not
     * match.
     */
    private fun join(baseUrl: String, path: String): String =
        baseUrl.trim().trimEnd('/') + path
}
