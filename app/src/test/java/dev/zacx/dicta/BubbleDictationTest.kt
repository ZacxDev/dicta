package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The inline-dictation flow's decisions, pinned without the platform.
 *
 * [BubbleDictation] holds every branch the redesign introduced and no Android
 * types, which is the only reason any of this is testable: the bubble's pulse,
 * the window geometry and the `AudioRecord` behind the levels are all device
 * evidence, and Robolectric actively misreports the rendering parts.
 *
 * The clock is driven by hand rather than slept on, so the debounce and the
 * two-minute cap are exercised at their boundaries instead of in real time.
 */
class BubbleDictationTest {

    private var clock = 1_000L
    private val machine = BubbleDictation(now = { clock })

    private fun tap(granted: Boolean = true) =
        machine.on(BubbleDictation.Event.BubbleTapped(granted))

    private fun speak(level: Double = LOUD) =
        machine.on(BubbleDictation.Event.LevelSampled(level))

    // --- tap 1 / tap 2 ------------------------------------------------------

    @Test
    fun theFirstTapStartsRecordingAndOpensNoOverlay() {
        val effects = tap()
        assertEquals(BubbleDictation.State.RECORDING, machine.state)
        assertEquals(
            "tap 1 must start capture and NOTHING else — the bubble flow's " +
                "whole point is that the card does not appear until tap 2",
            listOf(BubbleDictation.Effect.StartRecording),
            effects,
        )
    }

    @Test
    fun theSecondTapSubmitsAndOpensTheCard() {
        tap()
        speak()
        clock += BubbleDictation.MIN_RECORDING_MS

        val effects = tap()
        assertEquals(BubbleDictation.State.TRANSCRIBING, machine.state)
        assertEquals(
            "tap 2 must submit the recording AND open the card, in that order " +
                "— the card is opened after the submit so it can open already " +
                "showing the transcribing state rather than an idle one",
            listOf(
                BubbleDictation.Effect.SubmitRecording,
                BubbleDictation.Effect.OpenOverlayTranscribing,
            ),
            effects,
        )
    }

    @Test
    fun aTapWithoutMicPermissionAsksForItAndRecordsNothing() {
        val effects = tap(granted = false)
        assertEquals(
            "a denied microphone must leave the machine idle: a Service cannot " +
                "call requestPermissions, so the ask is handed to the card",
            BubbleDictation.State.IDLE,
            machine.state,
        )
        assertEquals(listOf(BubbleDictation.Effect.RequestMicPermission), effects)
    }

    // --- cancel -------------------------------------------------------------

    @Test
    fun cancelDiscardsWithoutUploadingOrOpeningAnything() {
        tap()
        speak()
        clock += 5_000

        val effects = machine.on(BubbleDictation.Event.CancelTapped)
        assertEquals(BubbleDictation.State.IDLE, machine.state)
        assertEquals(
            "cancel must discard and do nothing else. A cancel that opened a " +
                "card, or that submitted the audio anyway, is not a cancel — " +
                "and the audio here is well above the silence floor, so this " +
                "is the case where an upload WOULD otherwise have happened",
            listOf(BubbleDictation.Effect.DiscardRecording),
            effects,
        )
    }

    @Test
    fun cancelWhileIdleDoesNothing() {
        assertEquals(emptyList<BubbleDictation.Effect>(),
            machine.on(BubbleDictation.Event.CancelTapped))
        assertEquals(BubbleDictation.State.IDLE, machine.state)
    }

    // --- the debounce -------------------------------------------------------

    @Test
    fun aSecondTapInsideTheDebounceWindowIsSwallowed() {
        tap()
        speak()
        clock += BubbleDictation.MIN_RECORDING_MS - 1

        val effects = tap()
        assertEquals(
            "a double-tap must leave the recording running, not submit a " +
                "sub-second recording — which is both a wasted upload and the " +
                "near-silent input that makes the service hallucinate",
            BubbleDictation.State.RECORDING,
            machine.state,
        )
        assertEquals(listOf(BubbleDictation.Effect.IgnoredAsTooFast), effects)
    }

    @Test
    fun aTapExactlyAtTheDebounceBoundaryIsHonoured() {
        // The boundary itself, from the other side: `<` versus `<=` in the
        // guard is a one-character mutation and the test above cannot see it.
        tap()
        speak()
        clock += BubbleDictation.MIN_RECORDING_MS

        val effects = tap()
        assertEquals(BubbleDictation.State.TRANSCRIBING, machine.state)
        assertTrue(
            "at exactly MIN_RECORDING_MS the tap must count as 'stop'",
            effects.contains(BubbleDictation.Effect.SubmitRecording),
        )
    }

    // --- the runaway cap ----------------------------------------------------

    @Test
    fun theDurationCapAutoSubmitsAndSaysSo() {
        tap()
        speak()
        clock += BubbleDictation.MAX_RECORDING_MS

        val effects = machine.on(BubbleDictation.Event.MaxDurationReached)
        assertEquals(BubbleDictation.State.TRANSCRIBING, machine.state)
        assertEquals(
            "at the cap the recording must be SUBMITTED, not discarded — the " +
                "user did say something — and the truncation must be announced",
            listOf(
                BubbleDictation.Effect.ReportMaxDuration,
                BubbleDictation.Effect.SubmitRecording,
                BubbleDictation.Effect.OpenOverlayTranscribing,
            ),
            effects,
        )
    }

    @Test
    fun theDurationCapIsInertWhenNotRecording() {
        assertEquals(
            "a cap that fires after the recording already stopped must not " +
                "submit a second time",
            emptyList<BubbleDictation.Effect>(),
            machine.on(BubbleDictation.Event.MaxDurationReached),
        )
        assertEquals(BubbleDictation.State.IDLE, machine.state)
    }

    @Test
    fun theDurationCapStillRespectsTheSilenceGate() {
        // A bubble left recording in a pocket for two minutes is the single
        // most likely way to reach the cap, and it is also the case most likely
        // to be silent. Auto-submitting it would ship exactly the near-silent
        // audio the gate exists to stop.
        tap()
        speak(SILENT)
        clock += BubbleDictation.MAX_RECORDING_MS

        val effects = machine.on(BubbleDictation.Event.MaxDurationReached)
        assertEquals(
            listOf(
                BubbleDictation.Effect.ReportMaxDuration,
                BubbleDictation.Effect.DiscardRecording,
                BubbleDictation.Effect.ReportNothingHeard,
            ),
            effects,
        )
        assertEquals(BubbleDictation.State.IDLE, machine.state)
    }

    // --- level samples ------------------------------------------------------

    @Test
    fun levelSamplesNeverChangeTheState() {
        // They arrive on every capture buffer — tens of times a second — so a
        // transition driven by one would fire continuously.
        for (state in listOf(BubbleDictation.State.IDLE, BubbleDictation.State.RECORDING)) {
            if (state == BubbleDictation.State.RECORDING) tap()
            val before = machine.state
            repeat(50) {
                assertEquals(
                    "a level sample produced an effect in state $before",
                    emptyList<BubbleDictation.Effect>(),
                    speak(0.5),
                )
                assertEquals(before, machine.state)
            }
        }
    }

    @Test
    fun thePeakIsTheHighestSampleNotTheLastOne() {
        // The gate reads the peak, so a loud utterance followed by a quiet tail
        // — i.e. every real recording, which ends with the user going silent
        // before they reach for the bubble — must still pass.
        tap()
        speak(0.4)
        speak(0.0001)
        assertEquals(0.4, machine.peakLevel, 1e-9)

        clock += BubbleDictation.MIN_RECORDING_MS
        assertTrue(
            "a recording that ended quietly was gated as silent",
            tap().contains(BubbleDictation.Effect.SubmitRecording),
        )
    }

    // --- the silence gate (issue #8) ----------------------------------------

    /**
     * 🔴 The gate, counted rather than asserted structurally — and reported as
     * a **pair**: the zero below is only meaningful next to the one above it.
     *
     * A zero upload count from a harness wired to nothing is indistinguishable
     * from a real refusal, so [aboveTheFloorExactlyOneUploadIsAttempted] is not
     * decoration: it is what proves the counter can move at all.
     */
    @Test
    fun belowTheFloorZeroUploadsAreAttempted() {
        assertEquals(
            "audio whose peak never crossed the floor must produce ZERO upload " +
                "attempts. Fed near-silence the service returns long, confident, " +
                "invented prose — not an empty string — and the card then labels " +
                "it as the server's work (issue #8).",
            0,
            uploadAttemptsFor(SILENT),
        )
    }

    @Test
    fun aboveTheFloorExactlyOneUploadIsAttempted() {
        assertEquals(
            "positive control for the gate: speech-level audio must produce " +
                "exactly one upload. Without this the zero above could just as " +
                "easily mean the counter is wired to nothing.",
            1,
            uploadAttemptsFor(LOUD),
        )
    }

    @Test
    fun exactlyAtTheFloorTheRecordingIsUploaded() {
        // The boundary. `<` versus `<=` decides whether a recording sitting
        // exactly on the floor is thrown away, and the chosen direction is
        // "upload it" — the floor is deliberately set low enough that anything
        // reaching it is not the pathological all-but-zero case.
        assertEquals(1, uploadAttemptsFor(BubbleDictation.SILENCE_PEAK_FLOOR))
    }

    @Test
    fun justBelowTheFloorNothingIsUploaded() {
        assertEquals(0, uploadAttemptsFor(BubbleDictation.SILENCE_PEAK_FLOOR * 0.9))
    }

    /** Runs one whole recording at [peak] and counts the submits it causes. */
    private fun uploadAttemptsFor(peak: Double): Int {
        val m = BubbleDictation(now = { clock })
        m.on(BubbleDictation.Event.BubbleTapped(micGranted = true))
        // A real recording reports many samples; only the peak may matter.
        m.on(BubbleDictation.Event.LevelSampled(0.0))
        m.on(BubbleDictation.Event.LevelSampled(peak))
        m.on(BubbleDictation.Event.LevelSampled(0.0))
        clock += BubbleDictation.MIN_RECORDING_MS + 1
        val effects = m.on(BubbleDictation.Event.BubbleTapped(micGranted = true))

        // A discarded recording must never also be submitted, and vice versa:
        // counting only submits would miss a machine that emitted both.
        assertTrue(
            "the machine emitted BOTH a submit and a discard for one recording",
            !(effects.contains(BubbleDictation.Effect.SubmitRecording) &&
                effects.contains(BubbleDictation.Effect.DiscardRecording)),
        )
        return effects.count { it == BubbleDictation.Effect.SubmitRecording }
    }

    @Test
    fun aSilenceGatedRecordingReportsItselfAndDoesNotOpenTheCard() {
        val m = BubbleDictation(now = { clock })
        m.on(BubbleDictation.Event.BubbleTapped(micGranted = true))
        m.on(BubbleDictation.Event.LevelSampled(SILENT))
        clock += BubbleDictation.MIN_RECORDING_MS + 1

        val effects = m.on(BubbleDictation.Event.BubbleTapped(micGranted = true))
        assertEquals(
            "a gated recording must be discarded and REPORTED — a tap that " +
                "silently does nothing is worse than the hallucination it " +
                "prevents, because the user cannot tell the mic was dead",
            listOf(
                BubbleDictation.Effect.DiscardRecording,
                BubbleDictation.Effect.ReportNothingHeard,
            ),
            effects,
        )
        assertEquals(BubbleDictation.State.IDLE, m.state)
    }

    // --- errors and the transcribing state ----------------------------------

    @Test
    fun captureThatFailsToStartReturnsToIdle() {
        tap()
        assertEquals(
            emptyList<BubbleDictation.Effect>(),
            machine.on(BubbleDictation.Event.CaptureFailed),
        )
        assertEquals(BubbleDictation.State.IDLE, machine.state)
        // And the bubble is usable again rather than wedged.
        assertEquals(listOf(BubbleDictation.Effect.StartRecording), tap())
    }

    @Test
    fun aSessionErrorReturnsToIdle() {
        tap()
        speak()
        clock += 1_000
        machine.on(BubbleDictation.Event.SessionFailed)
        assertEquals(BubbleDictation.State.IDLE, machine.state)
    }

    @Test
    fun tapsAreIgnoredWhileATranscriptIsInFlight() {
        tap()
        speak()
        clock += BubbleDictation.MIN_RECORDING_MS
        tap()
        assertEquals(BubbleDictation.State.TRANSCRIBING, machine.state)

        assertEquals(
            "a tap while the card is open and transcribing must not start a " +
                "second capture: the card is on screen with its own mic control",
            emptyList<BubbleDictation.Effect>(),
            tap(),
        )
        assertEquals(BubbleDictation.State.TRANSCRIBING, machine.state)
    }

    /**
     * 🔴 The wedge. Found by reading the code, not by a test — which is why it
     * gets one.
     *
     * Leaving `TRANSCRIBING` depends on a transcript arriving, and the one path
     * that produces none at all is the server being unreachable *and* the phone's
     * recogniser having produced nothing either. With an unconditional "ignore
     * taps while transcribing", the bubble was then dead permanently — no error,
     * no way back short of restarting the service, and dead in exactly the
     * situation where the user most wants to retry.
     */
    @Test
    fun aTapWellAfterSubmittingStartsAFreshRecordingEvenIfNoTranscriptEverCame() {
        tap()
        speak()
        clock += BubbleDictation.MIN_RECORDING_MS
        tap()
        assertEquals(BubbleDictation.State.TRANSCRIBING, machine.state)

        // No TranscriptSettled is ever delivered: this is the server-down case.
        clock += BubbleDictation.TRANSCRIBE_GRACE_MS

        assertEquals(
            "the bubble stayed wedged in TRANSCRIBING. A transcript that never " +
                "arrives must not disable the bubble for the rest of the " +
                "service's life.",
            listOf(BubbleDictation.Effect.StartRecording),
            tap(),
        )
        assertEquals(BubbleDictation.State.RECORDING, machine.state)
    }

    @Test
    fun theFreshRecordingAfterTheGraceWindowStartsWithACleanPeak() {
        // The recovery path goes through onTap recursively, so it must not skip
        // the peak reset — or the silence gate would be defeated for every
        // recording started this way.
        tap()
        speak(LOUD)
        clock += BubbleDictation.MIN_RECORDING_MS
        tap()
        clock += BubbleDictation.TRANSCRIBE_GRACE_MS
        tap()

        assertEquals("the peak leaked through the recovery path", 0.0, machine.peakLevel, 1e-9)
        speak(SILENT)
        clock += BubbleDictation.MIN_RECORDING_MS
        assertEquals(
            listOf(
                BubbleDictation.Effect.DiscardRecording,
                BubbleDictation.Effect.ReportNothingHeard,
            ),
            tap(),
        )
    }

    @Test
    fun aTapWithoutPermissionAfterTheGraceWindowAsksRatherThanRecords() {
        tap()
        speak()
        clock += BubbleDictation.MIN_RECORDING_MS
        tap()
        clock += BubbleDictation.TRANSCRIBE_GRACE_MS

        assertEquals(
            "the recovery path must still respect the microphone permission",
            listOf(BubbleDictation.Effect.RequestMicPermission),
            tap(granted = false),
        )
        assertEquals(BubbleDictation.State.IDLE, machine.state)
    }

    @Test
    fun aSettledTranscriptReturnsTheBubbleToIdle() {
        tap()
        speak()
        clock += BubbleDictation.MIN_RECORDING_MS
        tap()
        machine.on(BubbleDictation.Event.TranscriptSettled)
        assertEquals(BubbleDictation.State.IDLE, machine.state)
        assertEquals(listOf(BubbleDictation.Effect.StartRecording), tap())
    }

    @Test
    fun aFreshRecordingDoesNotInheritThePreviousPeak() {
        // 🔴 The gate would be defeated by one line: a peak that is not reset
        // means every recording after a single loud one passes, silent or not.
        tap()
        speak(LOUD)
        clock += BubbleDictation.MIN_RECORDING_MS
        tap()
        machine.on(BubbleDictation.Event.TranscriptSettled)

        tap()
        assertEquals("the peak leaked across recordings", 0.0, machine.peakLevel, 1e-9)
        speak(SILENT)
        clock += BubbleDictation.MIN_RECORDING_MS
        assertEquals(
            listOf(
                BubbleDictation.Effect.DiscardRecording,
                BubbleDictation.Effect.ReportNothingHeard,
            ),
            tap(),
        )
    }

    @Test
    fun elapsedIsZeroWhenNotRecording() {
        assertEquals(0L, machine.elapsedMs)
        tap()
        clock += 1_234
        assertEquals(1_234L, machine.elapsedMs)
    }

    private companion object {
        /**
         * A speech-level peak. Two orders of magnitude above the floor, so this
         * fixture cannot accidentally sit on the boundary it is meant to clear
         * — and deliberately not a round multiple of the floor.
         */
        const val LOUD = 0.37

        /**
         * The pathological case the gate is for: all-but-zero. Not exactly 0.0,
         * because a gate that only caught bit-exact zero would be satisfied by
         * a single stray LSB.
         */
        const val SILENT = 0.00017
    }
}
