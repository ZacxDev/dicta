package dev.zacx.dicta

/**
 * What one upload attempt came back as.
 *
 * Deliberately *not* a `Result<String>`: the retry ladder has to discriminate
 * on the HTTP status **and** on the response body's `detail` string, and a
 * thrown exception loses both.
 */
sealed class SttOutcome {

    /**
     * 200 with a transcript.
     *
     * @param truncated the service's `truncated` field: true when generation
     *   hit the `max_new_tokens` cap, so the transcript is **cut off**.
     *   🔴 `null` is "no signal", not false — the server returns null when it
     *   had no EOS id to look for, i.e. it could not tell. A consumer must
     *   branch on `== true` and must never read null as "checked and fine".
     *   The default is null so a construction that knows nothing claims
     *   nothing.
     */
    data class Success(
        val text: String,
        val truncated: Boolean? = null,
    ) : SttOutcome()

    /**
     * A response arrived with a non-2xx status.
     *
     * @param detail the service's JSON `detail` field when one could be
     *   parsed, else null. 🔴 Null is *information*: during a pod roll the
     *   readiness probe drops every endpoint and the client sees the
     *   **proxy's** 502/503 with an HTML or empty body, so "503 with no
     *   parseable detail" is the transient case, not an unknown one.
     */
    data class Http(val status: Int, val detail: String?) : SttOutcome()

    /** No response at all: connect refused, DNS, read timeout, TLS, socket. */
    data class Network(val reason: String) : SttOutcome()
}
