package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The classification table, the backoff schedule and the upload budget.
 *
 * **Invariant guards.** Nothing here regressed; these pin a policy that is
 * expensive to get wrong in a way no test would otherwise notice — the cost
 * of a mis-classified retry is a wasted GPU generation on a single-replica
 * service with no request de-duplication, which is invisible from the client.
 *
 * Every expectation is a literal from the measured service contract.
 */
class SttRetryPolicyTest {

    private fun http(status: Int, detail: String? = null) =
        SttRetryPolicy.classify(SttOutcome.Http(status, detail))

    // --- fail fast ----------------------------------------------------------

    @Test
    fun a400IsNotRetried() {
        // Undecodable audio. The same bytes will be just as undecodable.
        assertEquals(Disposition.FAIL_FAST, http(400, "could not decode audio"))
    }

    @Test
    fun a401IsNotRetried() {
        // The measured body from a no-token POST.
        assertEquals(
            Disposition.FAIL_FAST,
            http(401, "invalid or missing bearer token"),
        )
    }

    @Test
    fun a413IsNotRetried() {
        // Audio longer than 3600 s. Retrying cannot shorten it.
        assertEquals(Disposition.FAIL_FAST, http(413, "audio too long"))
    }

    @Test
    fun a422IsNotRetried() {
        assertEquals(Disposition.FAIL_FAST, http(422, "field required"))
    }

    @Test
    fun a500IsNotRetried() {
        // 🔴 Deliberately NOT bucketed with the other 5xx. The service's known
        // 500 is an unhandled ValueError from an out-of-allowlist `language`
        // value — deterministic in the request, so every retry reproduces it
        // while burning the GPU.
        assertEquals(Disposition.FAIL_FAST, http(500, null))
        assertEquals(Disposition.FAIL_FAST, http(500, "Internal Server Error"))
    }

    @Test
    fun anUnexpectedClientErrorIsNotRetried() {
        assertEquals(Disposition.FAIL_FAST, http(404, null))
        assertEquals(Disposition.FAIL_FAST, http(405, null))
    }

    // --- the permanent 503 --------------------------------------------------

    @Test
    fun the503AboutAMissingServerTokenIsPermanent() {
        // 🔴 This is the discrimination that matters: three different 503s
        // share a status code and only this one must never be retried.
        assertEquals(
            Disposition.PERMANENT,
            http(503, "server has no STT_API_TOKEN configured"),
        )
    }

    @Test
    fun theOtherTwo503sAreRetried() {
        assertEquals(Disposition.RETRY, http(503, "model not loaded yet"))
        assertEquals(
            Disposition.RETRY,
            http(503, "the audio decoder failed: moov atom not found"),
        )
    }

    @Test
    fun the503DiscriminationIsByDetailAndNotByStatus() {
        // Stated as the pair, so an implementation that treats all 503s the
        // same fails whichever way it chose.
        val permanent = http(503, "server has no STT_API_TOKEN configured")
        val transient = http(503, "model not loaded yet")
        assertFalse(
            "all 503s were classified identically — the detail is being ignored",
            permanent == transient,
        )
    }

    @Test
    fun a503WithNoParseableDetailIsTransient() {
        // 🔴 During a pod roll the readiness probe drops every endpoint and
        // the client sees the PROXY's 503 with an HTML or empty body, not the
        // app's JSON. That is the most transient case there is.
        assertEquals(Disposition.RETRY, http(503, null))
        assertEquals(Disposition.RETRY, http(503, ""))
    }

    @Test
    fun theMarkerIsMatchedAsASubstringOfARealisticDetail() {
        // The real message is a sentence, not the bare token name.
        assertEquals(
            Disposition.PERMANENT,
            http(
                503,
                "server has no STT_API_TOKEN set; refusing to serve unauthenticated",
            ),
        )
    }

    @Test
    fun aDetailMentioningSomethingElseEntirelyIsStillTransient() {
        // Control: the marker must not match by accident on an unrelated 503.
        assertEquals(Disposition.RETRY, http(503, "STT model warm-up in progress"))
    }

    // --- retry --------------------------------------------------------------

    @Test
    fun gatewayErrorsAreRetried() {
        assertEquals(Disposition.RETRY, http(502, null))
        assertEquals(Disposition.RETRY, http(504, null))
    }

    @Test
    fun anUnknownServerErrorIsRetried() {
        assertEquals(Disposition.RETRY, http(599, null))
    }

    @Test
    fun aTransportFailureIsRetried() {
        assertEquals(
            Disposition.RETRY,
            SttRetryPolicy.classify(SttOutcome.Network("Connection refused")),
        )
        assertEquals(
            Disposition.RETRY,
            SttRetryPolicy.classify(SttOutcome.Network("read timed out")),
        )
    }

    @Test
    fun a200Succeeds() {
        assertEquals(
            Disposition.SUCCEEDED,
            SttRetryPolicy.classify(SttOutcome.Success("the transcript")),
        )
        assertEquals(Disposition.SUCCEEDED, http(200, null))
    }

    // --- backoff ------------------------------------------------------------

    @Test
    fun theBaseDelaysAreTwoAndSixSeconds() {
        // Literals, so a change to the schedule is a visible decision.
        assertEquals(listOf(2_000L, 6_000L), SttRetryPolicy.FAST_RETRY_DELAYS_MS)
    }

    @Test
    fun theBackoffIsOrderedIncreasing() {
        // Same jitter sample for both, so the ordering claim is about the
        // schedule and not about the RNG.
        val first = SttRetryPolicy.backoffMillis(0, 0.5)!!
        val second = SttRetryPolicy.backoffMillis(1, 0.5)!!
        assertTrue(
            "backoff must increase: got $first then $second",
            second > first,
        )
    }

    @Test
    fun theMidpointJitterSampleGivesTheBaseDelayExactly() {
        assertEquals(2_000L, SttRetryPolicy.backoffMillis(0, 0.5))
        assertEquals(6_000L, SttRetryPolicy.backoffMillis(1, 0.5))
    }

    @Test
    fun jitterSpansExactlyPlusMinusThirtyPercent() {
        // The bounds, named. 0.0 -> -30%, 1.0 -> +30%.
        assertEquals(1_400L, SttRetryPolicy.backoffMillis(0, 0.0))
        assertEquals(2_600L, SttRetryPolicy.backoffMillis(0, 1.0))
        assertEquals(4_200L, SttRetryPolicy.backoffMillis(1, 0.0))
        assertEquals(7_800L, SttRetryPolicy.backoffMillis(1, 1.0))
    }

    @Test
    fun jitterActuallyMovesTheDelay() {
        // Positive control against an implementation that ignores the sample:
        // three distinct samples must give three distinct delays.
        val low = SttRetryPolicy.backoffMillis(0, 0.1)!!
        val mid = SttRetryPolicy.backoffMillis(0, 0.5)!!
        val high = SttRetryPolicy.backoffMillis(0, 0.9)!!
        assertTrue("jitter is not being applied", low < mid && mid < high)
    }

    @Test
    fun pastTheEndOfTheLadderThereIsNoDelay() {
        // Null is the ladder's terminator, not a zero-length wait.
        assertNull(SttRetryPolicy.backoffMillis(2, 0.5))
        assertNull(SttRetryPolicy.backoffMillis(99, 0.5))
    }

    // --- timeouts -----------------------------------------------------------

    @Test
    fun theConnectTimeoutIsTenSeconds() {
        assertEquals(10_000, SttRetryPolicy.CONNECT_TIMEOUT_MS)
    }

    @Test
    fun theReadTimeoutHasATwentySecondFloor() {
        // A 3 s clip would otherwise get 6 s, which a 14-47 s cold start
        // cannot fit inside.
        assertEquals(20_000, SttRetryPolicy.readTimeoutMs(3.0))
        assertEquals(20_000, SttRetryPolicy.readTimeoutMs(0.0))
    }

    @Test
    fun theReadTimeoutScalesAtTwiceTheAudioInTheMiddle() {
        // Measured at a middle point, not just at the bounds: 17 s of audio
        // is above the floor and below the cap, so this is the only region
        // where the 2x term is observable.
        assertEquals(34_000, SttRetryPolicy.readTimeoutMs(17.0))
        assertEquals(60_000, SttRetryPolicy.readTimeoutMs(30.0))
    }

    @Test
    fun theReadTimeoutIsCappedAtNinetySeconds() {
        assertEquals(90_000, SttRetryPolicy.readTimeoutMs(45.0))
        assertEquals(90_000, SttRetryPolicy.readTimeoutMs(600.0))
    }

    @Test
    fun theHealthPollCadenceAndBudgetAreTheBriefsNumbers() {
        assertEquals(10_000L, SttRetryPolicy.HEALTH_POLL_INTERVAL_MS)
        assertEquals(120_000L, SttRetryPolicy.HEALTH_POLL_BUDGET_MS)
    }

    // --- the upload budget --------------------------------------------------

    @Test
    fun theCeilingIsFourUploads() {
        assertEquals(4, SttRetryPolicy.MAX_UPLOADS_PER_RECORDING)
    }

    @Test
    fun theFastLadderCannotSpendTheReservedSlot() {
        val budget = UploadBudget()
        var spent = 0
        while (budget.canUpload(respectReservation = true)) {
            budget.spend()
            spent++
            // Guard against a non-terminating loop masquerading as a pass.
            if (spent > 20) break
        }
        // 4 total, 1 reserved -> the ladder gets 3.
        assertEquals(3, spent)
        assertEquals(1, budget.remaining)
    }

    @Test
    fun theRecoveryAttemptCanSpendTheReservedSlot() {
        val budget = UploadBudget()
        repeat(3) { budget.spend() }
        assertFalse(
            "the ladder must be refused once only the reservation is left",
            budget.canUpload(respectReservation = true),
        )
        assertTrue(
            "the recovery path must be allowed the reserved slot",
            budget.canUpload(respectReservation = false),
        )
        budget.spend()
        assertEquals(4, budget.uploadsSpent)
    }

    @Test
    fun nothingCanSpendBeyondTheCeiling() {
        val budget = UploadBudget()
        repeat(4) { budget.spend() }
        assertFalse(budget.canUpload(respectReservation = false))
        assertFalse(budget.canUpload(respectReservation = true))
        val thrown = try {
            budget.spend()
            null
        } catch (e: IllegalStateException) {
            e
        }
        // 🔴 Throws rather than returning false: a caller that got here
        // without asking canUpload() is a bug, and silently refusing would
        // let that bug ship.
        assertTrue("a fifth upload must be refused loudly", thrown != null)
        assertEquals(4, budget.uploadsSpent)
    }

    @Test
    fun theBudgetCountsUploadsAndNotRetries() {
        // Positive control that the counter moves: a fresh budget reports 0,
        // and each spend is visible.
        val budget = UploadBudget()
        assertEquals(0, budget.uploadsSpent)
        budget.spend()
        assertEquals(1, budget.uploadsSpent)
        budget.spend()
        assertEquals(2, budget.uploadsSpent)
    }
}
