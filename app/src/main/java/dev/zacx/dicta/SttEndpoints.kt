package dev.zacx.dicta

/**
 * URL and header construction for the transcription service.
 *
 * Split out from the HTTP code so the two things that are easy to get subtly
 * wrong — the path and the `Authorization` header shape — can be asserted
 * against literal expected strings rather than inferred from the
 * implementation.
 */
object SttEndpoints {

    /** The mesh address of the self-hosted service. Plain HTTP; see below. */
    const val DEFAULT_BASE_URL: String = "http://10.42.0.10:8118"

    const val TRANSCRIPTIONS_PATH: String = "/v1/audio/transcriptions"
    const val HEALTHZ_PATH: String = "/healthz"

    /** `POST` target for an upload. */
    fun transcriptions(baseUrl: String): String = join(baseUrl, TRANSCRIPTIONS_PATH)

    /**
     * `GET` target for readiness.
     *
     * 🔴 This endpoint is **unauthenticated** — it must be called with *no*
     * `Authorization` header. It is also the only probe available during the
     * cold-start window, when the service answers uploads with a retryable
     * 503.
     */
    fun healthz(baseUrl: String): String = join(baseUrl, HEALTHZ_PATH)

    /**
     * The bearer header value.
     *
     * Exactly one space, the literal `Bearer`, and the token untouched — the
     * service compares the remainder of the header byte-for-byte against its
     * configured token, so any trimming or re-casing here is a 401.
     */
    fun authorization(token: String): String = "Bearer $token"

    /**
     * Joins a user-supplied base URL to a fixed path.
     *
     * The base URL is typed by a human into a settings field, so a trailing
     * slash is expected rather than exceptional; without this the result is
     * `.../8118//v1/...`, which the service's router does not match.
     */
    private fun join(baseUrl: String, path: String): String =
        baseUrl.trim().trimEnd('/') + path
}
