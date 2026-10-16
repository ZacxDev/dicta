package dev.zacx.dicta

/**
 * What the ladder should do about one [SttOutcome].
 */
enum class Disposition {
    /** 200. Stop, use the text. */
    SUCCEEDED,

    /**
     * Transient. Wait and upload again **if the budget allows** — see
     * [SttRetryPolicy.MAX_UPLOADS_PER_RECORDING].
     */
    RETRY,

    /**
     * This recording will never transcribe remotely with this request.
     * Stop immediately, fall back, and surface the reason to the user — but
     * the *next* recording may well work.
     */
    FAIL_FAST,

    /**
     * The service is misconfigured in a way no client retry can fix. Stop,
     * fall back, and do **not** schedule a recovery poll: unlike [FAIL_FAST]
     * this is not about this request, so retrying later is equally futile
     * until a human fixes the server.
     */
    PERMANENT,
}

/**
 * The retry / fallback ladder.
 *
 * ## The constraint that shapes everything here
 *
 * The service is a **synchronous handler on a single GPU replica with no
 * admission control and no request de-duplication**. Two consequences:
 *
 *  1. Client-side serialisation is the *only* backpressure that exists, so
 *     exactly one upload may ever be in flight (enforced by
 *     [SingleFlightUploader], not here).
 *  2. There is no idempotency key, so a retry after a **read timeout** on a
 *     request the server actually completed burns a second full GPU
 *     generation for a transcript nobody will read. That is why the upload
 *     budget below is a hard ceiling rather than a guideline.
 *
 * ## 🔴 A deliberate deviation from the brief, stated out loud
 *
 * The specification asked for two things that cannot both hold:
 *
 *  * *"retry 3x: 2 s, 6 s, 15 s"* — one initial upload plus three retries is
 *    **four** uploads, reached at about t=23 s; and
 *  * *"Hard ceiling: 4 uploads of one recording, ever"*, after which a
 *    `/healthz`-gated **final attempt** should still be made.
 *
 * Taken literally the final attempt is the fifth upload and breaks the
 * ceiling. Resolved in favour of the ceiling, because the ceiling is the
 * constraint with a cost attached (a wasted GPU generation) and because the
 * alternative — keeping the full fast ladder — makes the recovery path
 * *unreachable in the exact situation it was designed for*, i.e. inert code.
 *
 * So: [MAX_UPLOADS_PER_RECORDING] is 4, the fast ladder spends at most
 * [FAST_RETRY_DELAYS_MS]`.size` retries (2 s, 6 s → 3 uploads, failing at
 * about t=8 s), and the last slot is **reserved** for the first `/healthz`
 * 200. The user therefore sees the on-device transcript *sooner* than the
 * brief's 23 s, and the cold-start recovery that the 503 ladder exists for is
 * actually reachable. The numbers are constants so the trade can be re-made
 * in one line.
 */
object SttRetryPolicy {

    /**
     * 🔴 Hard ceiling on uploads of a single recording, across **every** path
     * including the recovery attempt. Enforced by a budget object
     * ([UploadBudget]) that every upload path must spend from, so adding a new
     * path cannot quietly raise it.
     */
    const val MAX_UPLOADS_PER_RECORDING: Int = 4

    /**
     * Waits before the 2nd and 3rd uploads. Jitter is applied on top; see
     * [backoffMillis].
     */
    val FAST_RETRY_DELAYS_MS: List<Long> = listOf(2_000L, 6_000L)

    /** Kept back from the fast ladder for the `/healthz`-gated attempt. */
    const val RESERVED_RECOVERY_UPLOADS: Int = 1

    /** ±30%, per the brief. */
    const val JITTER_FRACTION: Double = 0.30

    /** How often to poll `/healthz` once the fast ladder has given up. */
    const val HEALTH_POLL_INTERVAL_MS: Long = 10_000L

    /** How long to keep polling before abandoning the recording for good. */
    const val HEALTH_POLL_BUDGET_MS: Long = 120_000L

    /** Connect timeout for every request. */
    const val CONNECT_TIMEOUT_MS: Int = 10_000

    /**
     * The substring that marks the one 503 that must **never** be retried.
     *
     * The service emits three different 503s and only two are transient:
     * `"model not loaded yet"` (cold start, 14-47 s) and
     * `"the audio decoder failed: ..."` (server fault) both clear on their
     * own, but `"server has no STT_API_TOKEN ..."` means the deployment has
     * no token configured at all — retrying it is a guaranteed waste and
     * polling `/healthz` for it is worse, because `/healthz` is
     * unauthenticated and will happily return 200 the whole time.
     *
     * 🔴 **Unstated dependency, now stated: this works only while `detail` is
     * a TOP-LEVEL field.** It is matched against
     * `JsonLite.stringField(body, "detail")`, which reads the **outermost
     * object only** — deliberately, and pinned by a test asserting a
     * nested-only `detail` reads as null. FastAPI puts `detail` at the top
     * level, so it holds today. If the service ever nests it (under an
     * `error` object, say), `stringField` correctly returns null, this marker
     * stops matching, and the one 503 that must never be retried silently
     * becomes a retried one — **with nothing going red**, because every
     * fixture here spells the body the way the service spells it now. A
     * response-shape change is the trigger to re-check this.
     */
    const val PERMANENT_503_MARKER: String = "STT_API_TOKEN"

    /**
     * Classifies one attempt.
     *
     * The status codes are enumerated rather than bucketed by `status / 100`
     * on purpose: the interesting cases are not class-shaped. 401 and 413 are
     * both "stop", 503 is "stop" or "retry" depending on a *string*, and 500
     * is "stop" even though it is a 5xx.
     */
    fun classify(outcome: SttOutcome): Disposition = when (outcome) {
        is SttOutcome.Success -> Disposition.SUCCEEDED

        // No response at all — connect refused, read timeout, socket reset.
        // Always transient as far as the client can tell; the budget is what
        // stops this from looping.
        is SttOutcome.Network -> Disposition.RETRY

        is SttOutcome.Http -> classifyHttp(outcome.status, outcome.detail)
    }

    private fun classifyHttp(status: Int, detail: String?): Disposition = when (status) {
        in 200..299 -> Disposition.SUCCEEDED

        // 400 undecodable audio, 401 bad/missing token, 413 audio > 3600 s,
        // 422 form validation. Every one of these is a property of *this*
        // request that a retry reproduces exactly.
        400, 401, 413, 422 -> Disposition.FAIL_FAST

        503 -> if (detail != null && detail.contains(PERMANENT_503_MARKER)) {
            Disposition.PERMANENT
        } else {
            // Includes detail == null: during a pod roll the proxy answers
            // with an HTML or empty body, and that is the *most* transient
            // case there is.
            Disposition.RETRY
        }

        502, 504 -> Disposition.RETRY

        // 🔴 500 fails fast even though it is a 5xx. The service's known 500
        // is an unhandled ValueError from an out-of-allowlist `language`
        // value — deterministic in the request, so every retry reproduces it
        // while burning the GPU. SttLanguage exists to prevent it; if one
        // still arrives, retrying is the wrong answer.
        500 -> Disposition.FAIL_FAST

        // Any other 4xx is a client-side contract problem.
        in 400..499 -> Disposition.FAIL_FAST

        // Any other 5xx: unknown server fault, allow the budget to decide.
        in 500..599 -> Disposition.RETRY

        else -> Disposition.FAIL_FAST
    }

    /**
     * The jittered wait before retry number [retryIndex] (0-based).
     *
     * @param unitRandom a value in `[0, 1)`, injected so the test can pin the
     *   ordering and the bounds instead of sampling a real RNG.
     * @return the base delay scaled by `1 ± JITTER_FRACTION`, or null when
     *   [retryIndex] is past the end of the fast ladder.
     */
    fun backoffMillis(retryIndex: Int, unitRandom: Double): Long? {
        val base = FAST_RETRY_DELAYS_MS.getOrNull(retryIndex) ?: return null
        // unitRandom 0.0 -> -30%, 0.5 -> +0%, 1.0 -> +30%.
        val scale = 1.0 + JITTER_FRACTION * (2.0 * unitRandom - 1.0)
        return (base * scale).toLong()
    }

    /**
     * The read/overall timeout for a recording of [audioSeconds].
     *
     * `max(20 s, 2 x audio_seconds)` capped at 90 s. The lower bound covers a
     * cold start on a short clip; the cap stops a long clip from pinning the
     * single GPU replica for the client's whole session.
     */
    fun readTimeoutMs(audioSeconds: Double): Int {
        val wanted = maxOf(20_000.0, 2_000.0 * audioSeconds)
        return minOf(wanted, 90_000.0).toInt()
    }
}

/**
 * The 🔴 four-upload ceiling, as an object rather than a convention.
 *
 * One of these is created per *recording* and is the only thing that can
 * authorise an upload. The fast ladder and the `/healthz` recovery path both
 * spend from the same instance, which is what makes the ceiling hold across
 * paths that do not know about each other — a count maintained independently
 * in each path would be two counts, and the ceiling would be eight.
 *
 * [reserveForRecovery] is why the fast ladder cannot starve the recovery
 * attempt: the ladder asks `canUploadNow(reserving = true)` and is refused
 * once only the reserved slot is left.
 */
class UploadBudget(
    private val total: Int = SttRetryPolicy.MAX_UPLOADS_PER_RECORDING,
    private val reserveForRecovery: Int = SttRetryPolicy.RESERVED_RECOVERY_UPLOADS,
) {
    private var spent: Int = 0

    val uploadsSpent: Int get() = spent
    val remaining: Int get() = total - spent

    /**
     * @param respectReservation true for the fast ladder, which must leave
     *   the reserved slot alone; false for the recovery attempt, which is
     *   what the reservation was for.
     */
    fun canUpload(respectReservation: Boolean): Boolean {
        val floor = if (respectReservation) reserveForRecovery else 0
        return remaining > floor
    }

    /**
     * Spends one upload.
     *
     * @throws IllegalStateException rather than returning false, because a
     *   caller that reached here without asking [canUpload] is a bug and
     *   silently returning false would let it ship.
     */
    fun spend() {
        check(spent < total) {
            "upload budget exhausted: $spent/$total already spent"
        }
        spent++
    }
}
