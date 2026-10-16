package dev.zacx.dicta

/**
 * Runs the retry / fallback ladder for **one** recording.
 *
 * Everything with a clock, a socket or a UI thread in it is injected, so the
 * whole policy — which statuses retry, when the fallback fires, how many
 * uploads can ever happen, in what order the waits occur — is exercised
 * synchronously in a unit test with no network and no `Thread.sleep`.
 *
 * ## The shape of the contract
 *
 * The caller always gets a transcript if either engine produced one. The
 * remote result **replaces** the local one when it arrives, including when it
 * arrives late via the recovery path; the local one is never discarded on the
 * assumption that the remote will come good.
 *
 * @param transport performs one upload. Must be synchronous and must already
 *   be serialised to one in-flight request (see [SingleFlightUploader]).
 * @param health one unauthenticated `GET /healthz`; true iff it answered 200.
 * @param sleeper blocks for n ms. Injected so the test can record the
 *   *ordering* of the waits rather than wait them out.
 * @param unitRandom supplies the jitter sample in `[0, 1)`.
 * @param elapsedMs a monotonic clock for the recovery-poll budget.
 * @param events where outcomes are reported. Called on the coordinator's own
 *   thread — the Android caller marshals to the main thread itself.
 */
class TranscriptionCoordinator(
    private val transport: (UploadAttempt) -> SttOutcome,
    private val health: () -> Boolean,
    private val sleeper: (Long) -> Unit,
    private val unitRandom: () -> Double,
    private val elapsedMs: () -> Long,
    private val events: Events,
    private val budget: UploadBudget = UploadBudget(),
) {

    /** One upload's worth of input. */
    data class UploadAttempt(
        val audio: ByteArray,
        val audioSeconds: Double,
        val language: String,
        /** 1-based, for logging and for the test to read back. */
        val attemptNumber: Int,
    ) {
        // data class on a ByteArray: generated equals/hashCode compare the
        // array by identity, which is wrong and surprising. Overridden so a
        // test that compares two attempts compares the bytes.
        override fun equals(other: Any?): Boolean =
            other is UploadAttempt &&
                audio.contentEquals(other.audio) &&
                audioSeconds == other.audioSeconds &&
                language == other.language &&
                attemptNumber == other.attemptNumber

        override fun hashCode(): Int =
            audio.contentHashCode() * 31 + attemptNumber
    }

    interface Events {
        /**
         * The authoritative remote transcript. May arrive after [onFellBack].
         *
         * @param truncated [SttOutcome.Success.truncated], passed through
         *   unchanged — the coordinator applies no threshold and makes no
         *   judgement about it. 🔴 null is "the server could not tell", not
         *   false; the consumer decides what to say.
         */
        fun onRemoteTranscript(text: String, truncated: Boolean?)

        /**
         * The remote path has given up for now and the on-device transcript
         * is what the user has.
         *
         * @param reason user-facing, already free of anything secret.
         * @param recoveryPending true when a `/healthz` poll is still running
         *   and the text may yet be replaced — the UI says so rather than
         *   letting the text change without explanation.
         */
        fun onFellBack(reason: String, recoveryPending: Boolean)

        /** Progress, for the status line. */
        fun onStatus(message: String)
    }

    /**
     * Executes the whole ladder. Blocks; run it on a worker.
     *
     * @return the remote transcript if one was ever obtained, else null.
     */
    fun run(audio: ByteArray, audioSeconds: Double, language: String): String? {
        val fast = runFastLadder(audio, audioSeconds, language)
        if (fast != null) return fast

        // The fast ladder is done and the user has nothing from us yet.
        // Hand over to the local transcript *now* rather than making them
        // watch a spinner through the recovery window.
        val recoverable = lastDisposition == Disposition.RETRY &&
            budget.canUpload(respectReservation = false)
        events.onFellBack(lastReason, recoverable)
        if (!recoverable) return null

        return runRecovery(audio, audioSeconds, language)
    }

    private var lastDisposition: Disposition = Disposition.RETRY
    private var lastReason: String = "transcription service unreachable"

    /** Initial upload plus the fast retries, within the reserved budget. */
    private fun runFastLadder(
        audio: ByteArray,
        audioSeconds: Double,
        language: String,
    ): String? {
        var retryIndex = 0
        while (budget.canUpload(respectReservation = true)) {
            if (retryIndex > 0) {
                val wait = SttRetryPolicy.backoffMillis(retryIndex - 1, unitRandom())
                    ?: break
                events.onStatus("retrying in ${wait / 1000}s")
                sleeper(wait)
            }
            budget.spend()
            val attempt = UploadAttempt(
                audio = audio,
                audioSeconds = audioSeconds,
                language = language,
                attemptNumber = budget.uploadsSpent,
            )
            val outcome = transport(attempt)
            val disposition = SttRetryPolicy.classify(outcome)
            lastDisposition = disposition
            lastReason = describe(outcome)

            when (disposition) {
                Disposition.SUCCEEDED -> {
                    val success = outcome as SttOutcome.Success
                    events.onRemoteTranscript(success.text, success.truncated)
                    return success.text
                }
                // Neither of these is worth another byte on the wire.
                Disposition.FAIL_FAST, Disposition.PERMANENT -> return null
                Disposition.RETRY -> retryIndex++
            }
            // Past the end of the fast ladder: stop even if budget remains,
            // because what remains is the reserved recovery slot.
            if (retryIndex > SttRetryPolicy.FAST_RETRY_DELAYS_MS.size) break
        }
        return null
    }

    /**
     * Polls `/healthz` and, on the first 200, spends the reserved slot.
     *
     * One attempt, not a loop: the reservation is a single upload, and a loop
     * here would be exactly the "count maintained in a second place" that
     * [UploadBudget] exists to prevent.
     */
    private fun runRecovery(
        audio: ByteArray,
        audioSeconds: Double,
        language: String,
    ): String? {
        val deadline = elapsedMs() + SttRetryPolicy.HEALTH_POLL_BUDGET_MS
        while (elapsedMs() < deadline) {
            if (health()) {
                if (!budget.canUpload(respectReservation = false)) return null
                budget.spend()
                events.onStatus("service is back — retranscribing")
                val outcome = transport(
                    UploadAttempt(
                        audio = audio,
                        audioSeconds = audioSeconds,
                        language = language,
                        attemptNumber = budget.uploadsSpent,
                    )
                )
                return if (outcome is SttOutcome.Success) {
                    events.onRemoteTranscript(outcome.text, outcome.truncated)
                    outcome.text
                } else {
                    // The reserved slot is spent. Say so instead of leaving
                    // "recovery pending" on screen forever.
                    events.onFellBack(describe(outcome), recoveryPending = false)
                    null
                }
            }
            sleeper(SttRetryPolicy.HEALTH_POLL_INTERVAL_MS)
        }
        events.onFellBack("transcription service did not come back", false)
        return null
    }

    /**
     * A user-facing reason.
     *
     * 🔴 Never includes the bearer token, and never echoes a whole response
     * body: the `detail` field is the service's own prose and is safe, but an
     * arbitrary body is not necessarily.
     */
    private fun describe(outcome: SttOutcome): String = when (outcome) {
        is SttOutcome.Success -> "ok"
        is SttOutcome.Network -> "network: ${outcome.reason}"
        is SttOutcome.Http -> when (outcome.status) {
            401 -> "transcription service rejected the token — check Settings"
            413 -> "recording too long for the transcription service"
            400 -> "the transcription service could not decode the audio"
            else -> {
                val d = outcome.detail?.takeIf { it.isNotBlank() }
                if (d != null) "HTTP ${outcome.status}: $d" else "HTTP ${outcome.status}"
            }
        }
    }
}
