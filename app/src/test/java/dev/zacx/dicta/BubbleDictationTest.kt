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
        clock += 5_000

        val effects = machine.on(BubbleDictation.Event.CancelTapped)
        assertEquals(BubbleDictation.State.IDLE, machine.state)
        assertEquals(
            "cancel must discard and do nothing else. A cancel that opened a " +
                "card, or that submitted the audio anyway, is not a cancel — " +
                "and the recording here is well past the debounce window, so " +
                "this is the case where a tap WOULD have submitted it",
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

    /**
     * 🔴 The debounce in **absolute milliseconds**, deliberately not in terms
     * of [BubbleDictation.MIN_RECORDING_MS].
     *
     * Found by a mutation sweep: setting `MIN_RECORDING_MS = 0L` — which kills
     * the debounce outright — left the whole suite green, because every other
     * case here advances the clock *by the constant*, so the fixture moved with
     * the mutant and the boundary was never crossed. A guard whose fixture is
     * derived from the value under test cannot see that value being wrong.
     *
     * So this names a real interval instead: 50 ms is a thumb's double-tap on a
     * 60 dp target, and a recording that short must never be submitted. It
     * fails if the window is shortened below 50 ms for any reason.
     */
    @Test
    fun aDoubleTapFiftyMillisecondsApartIsSwallowed() {
        tap()
        clock += 50L

        assertEquals(
            "a 50 ms double-tap submitted a 50 ms recording. That is a wasted " +
                "upload of near-empty audio, which the service answers with " +
                "invented prose (issue #8), and the user never asked for it.",
            listOf(BubbleDictation.Effect.IgnoredAsTooFast),
            tap(),
        )
        assertEquals(BubbleDictation.State.RECORDING, machine.state)
    }

    @Test
    fun aTapExactlyAtTheDebounceBoundaryIsHonoured() {
        // The boundary itself, from the other side: `<` versus `<=` in the
        // guard is a one-character mutation and the test above cannot see it.
        tap()
        clock += BubbleDictation.MIN_RECORDING_MS

        val effects = tap()
        assertEquals(BubbleDictation.State.TRANSCRIBING, machine.state)
        assertTrue(
            "at exactly MIN_RECORDING_MS the tap must count as 'stop'",
            effects.contains(BubbleDictation.Effect.SubmitRecording),
        )
    }

    // --- the runaway cap ----------------------------------------------------

    /**
     * 🔴 The cap submits whatever was recorded and does NOT inspect the audio —
     * including the pocket case, a quiet two minutes.
     *
     * A client-side silence gate used to sit in this exact path and discard a
     * low-peak recording instead. It was removed because its premise was
     * measured false, not because its constant was mistuned: against the real
     * service, room tone with no speech at a peak normalised RMS of **0.002023
     * hallucinated** invented prose while clean speech at **0.001825
     * transcribed verbatim**, so the two populations are inverted and no peak
     * floor separates them. Issue #8 stays open; it is not addressed here.
     */
    @Test
    fun theDurationCapAutoSubmitsAndSaysSo() {
        tap()
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

    // --- errors and the transcribing state ----------------------------------

    @Test
    fun captureThatFailsToStartReturnsToIdleAndSaysSo() {
        tap()
        assertEquals(
            "a failed capture emitted no effects, so the failure is completely " +
                "silent: no toast, no notification change, and — because tap 1 " +
                "no longer opens the card — no status line either. With the mic " +
                "held by a call, or no usable encoder, or startForeground " +
                "refused, the tap then does NOTHING the user can see, which is " +
                "indistinguishable from the app being dead.",
            listOf(BubbleDictation.Effect.ReportCaptureFailed),
            machine.on(BubbleDictation.Event.CaptureFailed),
        )
        assertEquals(BubbleDictation.State.IDLE, machine.state)
        // And the bubble is usable again rather than wedged.
        assertEquals(listOf(BubbleDictation.Effect.StartRecording), tap())
    }

    @Test
    fun aSessionErrorReturnsToIdleAndSaysSo() {
        tap()
        clock += 1_000
        assertEquals(
            "a terminal session error was as silent as a failed capture used " +
                "to be",
            listOf(BubbleDictation.Effect.ReportCaptureFailed),
            machine.on(BubbleDictation.Event.SessionFailed),
        )
        assertEquals(BubbleDictation.State.IDLE, machine.state)
    }

    @Test
    fun tapsAreIgnoredWhileATranscriptIsInFlight() {
        tap()
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

    /**
     * 🔴 The recovery path reaches `onTap` **recursively**, so every per-
     * recording field it resets is a field it could skip — and `startedAtMs` is
     * the one with teeth. Left stale, the new recording looks minutes old, the
     * debounce compares against the *previous* recording's start, and the very
     * next tap submits a recording that is milliseconds long. That is the
     * sub-second upload the debounce exists to prevent, reachable only through
     * this path.
     *
     * (This replaces a peak-reset guard on the same recursion. The peak existed
     * only for the silence gate, which was removed after a measurement refuted
     * it; the recursion still needs a guard, so the guard moved to the field
     * that still matters rather than being deleted with the gate.)
     */
    @Test
    fun theRecordingStartedByTheRecoveryPathIsStillDebounced() {
        tap()
        clock += BubbleDictation.MIN_RECORDING_MS
        tap()
        clock += BubbleDictation.TRANSCRIBE_GRACE_MS

        // The recursive start.
        assertEquals(listOf(BubbleDictation.Effect.StartRecording), tap())
        assertEquals(BubbleDictation.State.RECORDING, machine.state)

        // One millisecond short of the window: this tap is a slip, not a stop.
        clock += BubbleDictation.MIN_RECORDING_MS - 1
        assertEquals(
            "startedAtMs leaked through the recovery path, so the debounce was " +
                "measured against the PREVIOUS recording and this tap submitted " +
                "a 1 ms recording",
            listOf(BubbleDictation.Effect.IgnoredAsTooFast),
            tap(),
        )
        assertEquals(BubbleDictation.State.RECORDING, machine.state)
    }

    @Test
    fun aTapWithoutPermissionAfterTheGraceWindowAsksRatherThanRecords() {
        tap()
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
        clock += BubbleDictation.MIN_RECORDING_MS
        tap()
        machine.on(BubbleDictation.Event.TranscriptSettled)
        assertEquals(BubbleDictation.State.IDLE, machine.state)
        assertEquals(listOf(BubbleDictation.Effect.StartRecording), tap())
    }

    /**
     * The same reset, on the non-recursive path: a settled transcript returns
     * the machine to IDLE and the next tap starts a recording whose debounce
     * window is its own.
     */
    @Test
    fun theRecordingAfterASettledTranscriptIsStillDebounced() {
        tap()
        clock += BubbleDictation.MIN_RECORDING_MS
        tap()
        machine.on(BubbleDictation.Event.TranscriptSettled)

        assertEquals(listOf(BubbleDictation.Effect.StartRecording), tap())
        clock += BubbleDictation.MIN_RECORDING_MS - 1
        assertEquals(
            "startedAtMs leaked across recordings, so the next recording's " +
                "debounce was measured against the previous one's start",
            listOf(BubbleDictation.Effect.IgnoredAsTooFast),
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
}
