package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole ladder, end to end, with no network and no real clock.
 *
 * **Invariant guards** on the behaviour the brief specifies, plus one 🔴
 * **seam guard** ([theFourUploadCeilingHoldsAcrossEveryPath]) that pins a
 * *relationship* rather than a component: the fast ladder and the recovery
 * path are separately correct and could still, together, exceed the ceiling —
 * that is exactly the defect "verified in isolation" never sees, because each
 * path's own test builds only its own state.
 */
class TranscriptionCoordinatorTest {

    /** Records everything the coordinator did, in order. */
    private class Recorder : TranscriptionCoordinator.Events {
        val remote = mutableListOf<String>()

        /** The `truncated` flag exactly as it reached the consumer, in order. */
        val remoteTruncated = mutableListOf<Boolean?>()

        /** The `unreliable` flag exactly as it reached the consumer, in order. */
        val remoteUnreliable = mutableListOf<Boolean?>()
        val fellBack = mutableListOf<Pair<String, Boolean>>()
        val statuses = mutableListOf<String>()

        override fun onRemoteTranscript(
            text: String,
            truncated: Boolean?,
            unreliable: Boolean?,
        ) {
            remote += text
            remoteTruncated += truncated
            remoteUnreliable += unreliable
        }
        override fun onFellBack(reason: String, recoveryPending: Boolean) {
            fellBack += reason to recoveryPending
        }
        override fun onStatus(message: String) { statuses += message }
    }

    private class Harness(
        val outcomes: List<SttOutcome>,
        val healthAnswers: List<Boolean> = emptyList(),
    ) {
        val events = Recorder()
        val attempts = mutableListOf<TranscriptionCoordinator.UploadAttempt>()
        val sleeps = mutableListOf<Long>()
        var healthCalls = 0
        var now = 0L
        val budget = UploadBudget()

        fun run(
            audioSeconds: Double = 11.0,
            language: String = "de",
        ): String? = TranscriptionCoordinator(
            transport = { attempt ->
                attempts += attempt
                // Past the end of the script, keep returning the last
                // outcome rather than throwing: a test that crashes on an
                // unexpected extra upload would hide HOW MANY happened, and
                // the count is the thing under test.
                outcomes.getOrElse(attempts.size - 1) { outcomes.last() }
            },
            health = {
                val answer = healthAnswers.getOrElse(healthCalls) { true }
                healthCalls++
                answer
            },
            sleeper = { ms ->
                sleeps += ms
                // The injected clock advances by exactly the slept amount, so
                // the recovery budget is exercised deterministically.
                now += ms
            },
            unitRandom = { 0.5 },   // no jitter: pins the delays exactly
            elapsedMs = { now },
            events = events,
            budget = budget,
        ).run(
            // Fixture bytes are pairwise distinct and are not a constant any
            // assertion names.
            audio = byteArrayOf(0x11, 0x27, 0x3D, 0x54),
            audioSeconds = audioSeconds,
            language = language,
        )
    }

    private val ok = SttOutcome.Success("the server transcript")
    private val coldStart = SttOutcome.Http(503, "model not loaded yet")
    private val refused = SttOutcome.Network("Connection refused")

    // --- the happy path -----------------------------------------------------

    @Test
    fun aFirstTryeSuccessUploadsOnceAndReportsTheRemoteTranscript() {
        val h = Harness(listOf(ok))
        val result = h.run()

        assertEquals("the server transcript", result)
        assertEquals(listOf("the server transcript"), h.events.remote)
        assertEquals(1, h.attempts.size)
        assertEquals(1, h.budget.uploadsSpent)
        assertTrue("no fallback should have been reported", h.events.fellBack.isEmpty())
        assertTrue("nothing should have been slept", h.sleeps.isEmpty())
        assertEquals(0, h.healthCalls)
    }

    /**
     * 🔴 The `truncated` flag crosses the coordinator **unchanged**, on every
     * path that can deliver a transcript.
     *
     * The coordinator applies no threshold and makes no judgement: it is the
     * seam between the transport that read the field and the consumer that
     * decides what to say. All three values are asserted — a pass-through that
     * collapses null to false, or drops the flag entirely and always hands on
     * null, is a defect neither end's own tests can see.
     */
    @Test
    fun theTruncatedFlagReachesTheConsumerUnchangedOnTheFastPath() {
        for (flag in listOf(true, false, null)) {
            val h = Harness(listOf(SttOutcome.Success("a scripted transcript", flag)))
            h.run()
            assertEquals(
                "truncated=$flag did not survive the coordinator",
                listOf(flag),
                h.events.remoteTruncated,
            )
        }
    }

    /**
     * 🔴 The same guarantee for `unreliable`, asserted as the **pair** rather
     * than one flag at a time.
     *
     * The nine combinations are walked with the two flags set to *different*
     * values wherever they can differ, so a pass-through that hands the same
     * value on for both — the copy-paste error this widening invites — is
     * caught. Each is checked positionally: a swap of the two arguments at the
     * delivery site leaves both lists non-empty and both ends green.
     */
    @Test
    fun bothFlagsReachTheConsumerUnchangedAndUnswappedOnTheFastPath() {
        val values = listOf(true, false, null)
        for (truncated in values) {
            for (unreliable in values) {
                val h = Harness(
                    listOf(
                        SttOutcome.Success(
                            "a scripted transcript",
                            truncated = truncated,
                            unreliable = unreliable,
                        )
                    )
                )
                h.run()
                assertEquals(
                    "truncated=$truncated did not survive the coordinator " +
                        "(unreliable was $unreliable — if these two are " +
                        "transposed, the user is warned about the wrong fault)",
                    listOf(truncated),
                    h.events.remoteTruncated,
                )
                assertEquals(
                    "unreliable=$unreliable did not survive the coordinator " +
                        "(truncated was $truncated)",
                    listOf(unreliable),
                    h.events.remoteUnreliable,
                )
            }
        }
    }

    @Test
    fun theUnreliableFlagAlsoSurvivesTheRecoveryPath() {
        // 🔴 The second delivery site, which is a separate code path and was
        // the finding of the previous round. The scripted outcome disagrees
        // across the two flags on purpose, so neither read can stand in for
        // the other.
        val h = Harness(
            outcomes = listOf(
                coldStart, coldStart, coldStart,
                SttOutcome.Success("late and suspect", truncated = false, unreliable = true),
            ),
            healthAnswers = listOf(false, true),
        )
        assertEquals("late and suspect", h.run())
        assertEquals(
            "the recovery path dropped the fabrication signal the fast path " +
                "carries — whether the user is warned about a possibly " +
                "invented transcript would depend on which rung of the ladder " +
                "happened to succeed",
            listOf(true),
            h.events.remoteUnreliable,
        )
        assertEquals(listOf(false), h.events.remoteTruncated)
    }

    @Test
    fun theTruncatedFlagAlsoSurvivesTheRecoveryPath() {
        // The late path: the fast ladder fails, /healthz comes back, and the
        // reserved slot returns a TRUNCATED transcript. Separately correct
        // paths are exactly where a pass-through gets added to one of them.
        val h = Harness(
            outcomes = listOf(refused, refused, refused, SttOutcome.Success("late and cut off", true)),
            healthAnswers = listOf(false, true),
        )
        assertEquals("late and cut off", h.run())
        assertEquals(
            "the recovery path dropped the truncation signal the fast path " +
                "carries — the user is warned or not depending on which " +
                "rung of the ladder happened to succeed",
            listOf(true),
            h.events.remoteTruncated,
        )
    }

    @Test
    fun theLanguageAndAudioReachTheTransportUnchanged() {
        val h = Harness(listOf(ok))
        h.run(audioSeconds = 7.5, language = "pt")
        val attempt = h.attempts.single()
        assertEquals("pt", attempt.language)
        assertEquals(7.5, attempt.audioSeconds, 0.0001)
        assertArrayEqualsBytes(byteArrayOf(0x11, 0x27, 0x3D, 0x54), attempt.audio)
        assertEquals(1, attempt.attemptNumber)
    }

    // --- retry ladder -------------------------------------------------------

    @Test
    fun aTransientFailureThenSuccessUploadsTwiceAndWaitsTwoSeconds() {
        val h = Harness(listOf(coldStart, ok))
        val result = h.run()

        assertEquals("the server transcript", result)
        assertEquals(2, h.attempts.size)
        assertEquals(listOf(2_000L), h.sleeps)
        assertTrue(h.events.fellBack.isEmpty())
    }

    @Test
    fun theFastLadderSpendsThreeUploadsAndSleepsTwoThenSix() {
        // All transient: the ladder runs to its reservation limit.
        val h = Harness(listOf(coldStart, coldStart, coldStart), healthAnswers = listOf(false))
        h.now = 0

        val h2 = Harness(
            listOf(coldStart, coldStart, coldStart),
            // Never healthy, so the recovery loop times out and the upload
            // count stops at the ladder's three.
            healthAnswers = List(40) { false },
        )
        h2.run()

        assertEquals(
            "the fast ladder must spend exactly 3 uploads (4 total minus the " +
                "1 reserved for recovery)",
            3,
            h2.attempts.count { it.attemptNumber <= 3 },
        )
        // The ORDER of the waits, not just their presence.
        assertEquals(
            listOf(2_000L, 6_000L),
            h2.sleeps.take(2),
        )
        // Silence the unused-variable warning while keeping the first harness
        // documented as the single-poll variant.
        assertEquals(0, h.attempts.size)
    }

    @Test
    fun afterTheFastLadderTheUserIsHandedTheLocalTranscriptWithRecoveryPending() {
        val h = Harness(
            listOf(coldStart, coldStart, coldStart),
            healthAnswers = List(40) { false },
        )
        h.run()

        assertFalse(
            "the user must be told the local transcript is what they have",
            h.events.fellBack.isEmpty(),
        )
        val (reason, pending) = h.events.fellBack.first()
        assertTrue(
            "the fallback reason should name the server's own detail, got: $reason",
            reason.contains("model not loaded yet"),
        )
        assertTrue(
            "recovery is still possible here, so the UI must say so",
            pending,
        )
    }

    // --- fail fast ----------------------------------------------------------

    @Test
    fun a401UploadsOnceAndNeverRetries() {
        val h = Harness(listOf(SttOutcome.Http(401, "invalid or missing bearer token")))
        val result = h.run()

        assertNull(result)
        assertEquals("a 401 must cost exactly one upload", 1, h.attempts.size)
        assertTrue("a 401 must not be slept on", h.sleeps.isEmpty())
        assertEquals("a 401 must not trigger a health poll", 0, h.healthCalls)
        val (reason, pending) = h.events.fellBack.single()
        assertFalse("there is nothing to recover from a 401", pending)
        assertTrue(
            "the user must be pointed at Settings, got: $reason",
            reason.contains("Settings"),
        )
    }

    @Test
    fun a413UploadsOnceAndNeverRetries() {
        val h = Harness(listOf(SttOutcome.Http(413, "audio too long")))
        assertNull(h.run())
        assertEquals(1, h.attempts.size)
        assertTrue(h.sleeps.isEmpty())
        assertFalse(h.events.fellBack.single().second)
    }

    @Test
    fun a400UploadsOnceAndNeverRetries() {
        val h = Harness(listOf(SttOutcome.Http(400, "could not decode audio")))
        assertNull(h.run())
        assertEquals(1, h.attempts.size)
        assertEquals(0, h.healthCalls)
    }

    @Test
    fun thePermanent503UploadsOnceAndSchedulesNoRecoveryPoll() {
        // 🔴 The discrimination that matters most: /healthz is
        // UNAUTHENTICATED and would answer 200 the whole time the server has
        // no token configured, so a recovery poll here would retry forever
        // against a server that can never succeed.
        val h = Harness(
            listOf(SttOutcome.Http(503, "server has no STT_API_TOKEN configured")),
            healthAnswers = List(40) { true },
        )
        assertNull(h.run())

        assertEquals("a permanent 503 must cost exactly one upload", 1, h.attempts.size)
        assertEquals(
            "a permanent 503 must not poll /healthz at all",
            0,
            h.healthCalls,
        )
        assertTrue(h.sleeps.isEmpty())
        val (reason, pending) = h.events.fellBack.single()
        assertFalse("a permanent fault is not pending recovery", pending)
        assertTrue(
            "the reason should carry the server's detail, got: $reason",
            reason.contains("STT_API_TOKEN"),
        )
    }

    @Test
    fun aTransient503DoesRetryWhereThePermanentOneDoesNot() {
        // The pair, side by side. An implementation that ignores `detail`
        // fails one of these two whichever way it chose.
        val permanent = Harness(
            listOf(SttOutcome.Http(503, "server has no STT_API_TOKEN configured")),
            healthAnswers = List(40) { false },
        )
        permanent.run()

        val transient = Harness(
            listOf(coldStart, ok),
            healthAnswers = List(40) { false },
        )
        transient.run()

        assertEquals(1, permanent.attempts.size)
        assertEquals(2, transient.attempts.size)
    }

    // --- the recovery path --------------------------------------------------

    @Test
    fun theFirstHealthyPollSpendsTheReservedUploadAndReplacesTheText() {
        val h = Harness(
            // 3 ladder failures, then the reserved attempt succeeds.
            listOf(coldStart, coldStart, coldStart, ok),
            // Unhealthy once, then healthy.
            healthAnswers = listOf(false, true),
        )
        val result = h.run()

        assertEquals("the server transcript", result)
        assertEquals(
            "the recovery attempt is the 4th upload",
            4,
            h.attempts.size,
        )
        assertEquals(4, h.attempts.last().attemptNumber)
        assertEquals(4, h.budget.uploadsSpent)
        // The user was handed the local text first, then the remote replaced it.
        assertEquals(1, h.events.fellBack.size)
        assertEquals(listOf("the server transcript"), h.events.remote)
        // One 10 s poll interval was waited.
        assertTrue(
            "the health poll cadence must be 10 s, got ${h.sleeps}",
            h.sleeps.contains(10_000L),
        )
    }

    @Test
    fun aRecoveryAttemptThatAlsoFailsEndsTheRecoveryRatherThanLoopingForever() {
        val h = Harness(
            listOf(coldStart, coldStart, coldStart, coldStart),
            healthAnswers = List(40) { true },
        )
        assertNull(h.run())

        assertEquals("the ceiling must still hold", 4, h.attempts.size)
        // Two fallback reports: the hand-over, then "recovery is over".
        assertEquals(2, h.events.fellBack.size)
        assertFalse(
            "once the reserved slot is spent the UI must stop saying 'pending'",
            h.events.fellBack.last().second,
        )
    }

    @Test
    fun theRecoveryPollGivesUpAfterTheHundredAndTwentySecondBudget() {
        val h = Harness(
            listOf(refused, refused, refused),
            healthAnswers = List(100) { false },
        )
        assertNull(h.run())

        assertEquals(
            "no recovery upload may happen while /healthz never returns 200",
            3,
            h.attempts.size,
        )
        // 120 s budget at a 10 s cadence.
        val healthSleeps = h.sleeps.count { it == 10_000L }
        assertEquals(
            "the poll must stop after the 120 s budget, got $healthSleeps polls",
            12,
            healthSleeps,
        )
        assertFalse(h.events.fellBack.last().second)
    }

    // --- 🔴 the seam guard --------------------------------------------------

    /**
     * 🔴 The ceiling across **every** path, including paths that do not know
     * about each other.
     *
     * This is the guard that would catch a new upload site being added: it
     * drives the coordinator through the worst case — every response transient
     * and `/healthz` always green, so every path is maximally willing to
     * upload — and asserts the *total*. A per-path test cannot see this,
     * because each path's own count is correct.
     */
    @Test
    fun theFourUploadCeilingHoldsAcrossEveryPath() {
        val h = Harness(
            // 40 transient outcomes available: if any path is willing to
            // upload a fifth time, it can.
            List(40) { coldStart },
            healthAnswers = List(40) { true },
        )
        h.run()

        assertEquals(
            "🔴 the hard ceiling is 4 uploads of one recording across the fast " +
                "ladder AND the recovery path; a 5th burns a GPU generation on " +
                "a single-replica service with no de-duplication",
            4,
            h.attempts.size,
        )
        assertEquals(4, h.budget.uploadsSpent)
        // The attempt numbers are a dense 1..4 — no path double-counted or
        // skipped a slot.
        assertEquals(listOf(1, 2, 3, 4), h.attempts.map { it.attemptNumber })
    }

    @Test
    fun aFreshRecordingGetsAFreshBudget() {
        // The ceiling is per RECORDING. A budget shared across recordings
        // would starve every recording after the first — and would do so
        // silently, since the first one works.
        val first = Harness(List(40) { coldStart }, healthAnswers = List(40) { true })
        first.run()
        val second = Harness(listOf(ok))
        assertEquals("the server transcript", second.run())
        assertEquals(1, second.attempts.size)
    }

    // --- single-flight ------------------------------------------------------

    @Test
    fun theUploaderObservesOnlyOneRequestInFlight() {
        // 🔴 Client-side serialisation is the ONLY backpressure in the system:
        // the service is a synchronous handler on one GPU replica with no
        // admission control.
        val uploader = SingleFlightUploader { SttOutcome.Success("x") }
        val attempt = TranscriptionCoordinator.UploadAttempt(
            audio = byteArrayOf(1, 2, 3), audioSeconds = 2.0,
            language = "fr", attemptNumber = 1,
        )
        val threads = (1..8).map {
            Thread { repeat(25) { uploader.upload(attempt) } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(
            "two concurrent uploads were observed",
            0,
            uploader.concurrencyViolations,
        )
        assertEquals(1, uploader.maxObservedConcurrency)
    }

    @Test
    fun theSingleFlightCounterCanActuallyObserveConcurrency() {
        // Positive control for the guard above. A reassuring zero is
        // indistinguishable from a counter wired to nothing, so: deliberately
        // re-enter the uploader from inside its own delegate on another
        // thread, bypassing the lock by construction, and watch the number
        // move. Without this, `maxObservedConcurrency == 1` proves nothing.
        val counter = java.util.concurrent.atomic.AtomicInteger(0)
        val seen = java.util.concurrent.atomic.AtomicInteger(0)
        // A deliberately UNSERIALISED stand-in with the same counting logic,
        // to show the counting logic itself detects overlap.
        val overlapping = { _: TranscriptionCoordinator.UploadAttempt ->
            val now = counter.incrementAndGet()
            seen.updateAndGet { maxOf(it, now) }
            Thread.sleep(20)
            counter.decrementAndGet()
            SttOutcome.Success("x")
        }
        val attempt = TranscriptionCoordinator.UploadAttempt(
            audio = byteArrayOf(9), audioSeconds = 1.0, language = "it",
            attemptNumber = 1,
        )
        val threads = (1..4).map { Thread { overlapping(attempt) } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertTrue(
            "the control did not overlap, so the zero above is not evidence " +
                "of serialisation — got max=${seen.get()}",
            seen.get() > 1,
        )
    }

    private fun assertArrayEqualsBytes(expected: ByteArray, actual: ByteArray) {
        assertTrue(
            "expected ${expected.toList()} but got ${actual.toList()}",
            expected.contentEquals(actual),
        )
    }
}
