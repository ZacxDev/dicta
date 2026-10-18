package dev.zacx.dicta

/**
 * The inline-dictation state machine: **pure, with no Android types**.
 *
 * ## Why this is factored out
 *
 * The bubble flow is almost entirely decisions — which tap means what, when a
 * tap is too fast to have been meant, when a runaway recording has to be cut
 * off — and almost none of it can be exercised through the platform.
 * `AudioRecord`, `MediaCodec` and `SpeechRecognizer` have no usable
 * Robolectric implementations, and the bubble's pulse and the window geometry
 * are pixels, which Robolectric measures wrongly (`TextPaint.measureText`
 * returns a flat 1.0 px per character here regardless of text size). So the
 * same split
 * [AudioCaptureLogic] and [SttRetryPolicy] already use applies: the decisions
 * live here and are pinned by tests, the platform calls live in
 * [BubbleService] and are device evidence only.
 *
 * ## The flow
 *
 * ```
 *   IDLE  --tap-->  RECORDING  --tap-->  TRANSCRIBING  --transcript-->  IDLE
 *                      |  |
 *            cancel <--+  +--> max duration --> TRANSCRIBING
 * ```
 *
 * Tap 1 starts capture and shows **no** overlay — the bubble itself is the
 * recording indicator. Tap 2 stops capture, opens the card already in its
 * "transcribing…" state, and submits. Cancel discards: no upload, no overlay.
 *
 * ## What the machine deliberately does not know
 *
 * Whether `RECORD_AUDIO` is granted arrives as a parameter on
 * [Event.BubbleTapped] rather than being queried, because a permission check is
 * a platform call and this class has none. Likewise the clock is injected.
 */
class BubbleDictation(
    /** Injected so the debounce and the duration cap are testable. */
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    enum class State {
        /** Not recording. The bubble is a plain "start dictation" button. */
        IDLE,

        /** Capturing. No overlay on screen; the bubble pulses with the level. */
        RECORDING,

        /** Capture finished and submitted; the card is open and waiting. */
        TRANSCRIBING,
    }

    /**
     * 🔴 Why a capture did not start — because each cause is a **different
     * message**, and one fixed string for all of them told the user the wrong
     * thing more often than the right one.
     *
     * The shipped version toasted *"the microphone may be in use by a call or
     * another app"* whenever a start failed. For [MIC_IN_USE] that blames
     * another app for something Dicta is doing, and leaves the user with
     * nothing to act on — the accurate copy (`stt_mic_in_use`, "stop that
     * recording first") already existed and was not shown. For
     * [FOREGROUND_DENIED] the microphone is not involved at all.
     *
     * 🔴 Not the deleted `StartOutcome` enum coming back: that type was
     * correctly removed because **nothing branched on it**. This one is
     * branched on, in exactly one place — `BubbleService.messageFor` — whose
     * `when` is exhaustive, so a further cause cannot be added without
     * choosing what it says.
     */
    enum class Failure {
        /**
         * Another capture in this process holds the microphone: the bubble,
         * the card's own session, or the card's phone-only recogniser. The one
         * cause the user can fix directly, and the one the old single string
         * actively misdescribed.
         */
        MIC_IN_USE,

        /**
         * `startForeground(TYPE_MICROPHONE)` was refused, so the recording was
         * abandoned before the microphone was touched. Nothing to do with the
         * mic being busy.
         */
        FOREGROUND_DENIED,

        /**
         * The capture itself could not start: no usable encoder, or an
         * `AudioRecord` that would not initialise. A platform refusal, and
         * often a transient one, so the message may fairly say "try again".
         *
         * Every cause reaching here really is a *capture* refusal: the only
         * other way `startRecording` could produce no recording was a
         * `SttSettings.create()` that threw, and that path was deleted as
         * unreachable — see `BubbleService.realSession`.
         */
        CAPTURE_UNAVAILABLE,
    }

    sealed interface Event {
        /**
         * The user tapped the bubble.
         *
         * @param micGranted whether `RECORD_AUDIO` is held *right now*. A
         *   Service cannot call `requestPermissions`, so a denied microphone
         *   routes to [Effect.RequestMicPermission] — which opens
         *   `DictateActivity`, the one place the ask can happen and the one
         *   place the mic-denied fallback copy lives.
         */
        data class BubbleTapped(val micGranted: Boolean) : Event

        /** The cancel button under the bubble. Aborts; nothing is uploaded. */
        object CancelTapped : Event

        /**
         * The user held the bubble past the long-press threshold.
         *
         * Recognised entirely outside this class, by [BubbleGesture] — which
         * owns the milliseconds and the pixels, neither of which belongs here.
         * By the time this arrives the shell has already decided the gesture
         * was a press and not a drag.
         */
        object BubbleLongPressed : Event

        /**
         * A touch landed OUTSIDE the overlay window while the history row was
         * showing — the user tapping away to dismiss it.
         *
         * 🔴 It carries no effects of its own ON PURPOSE. [on] already
         * prefixes [Effect.HideHistory] to every event that is not
         * [BubbleLongPressed], so "dismiss the row and do nothing else" is
         * expressed by adding an event with an empty arm rather than by a
         * second dismissal path in the shell. That keeps the hide predicate in
         * ONE place; a shell that hid the row itself would be the N-sites
         * duplicate this class exists to avoid.
         *
         * Delivered only while the row is up: the shell adds
         * `FLAG_WATCH_OUTSIDE_TOUCH` on reveal and removes it on hide, so an
         * idle bubble is not woken by every touch on the device.
         */
        object TouchedOutside : Event

        /**
         * Capture could not start at all, and [failure] says why — the shell
         * knows which of its own steps refused, and the user needs to be told
         * which.
         */
        data class CaptureFailed(val failure: Failure) : Event

        /** The runaway cap fired. */
        object MaxDurationReached : Event

        /** A transcript (or a terminal "nothing came back") landed. */
        object TranscriptSettled : Event
    }

    sealed interface Effect {
        /** Open the microphone and begin capture. */
        object StartRecording : Effect

        /** Hand the ask to `DictateActivity`; do not record. */
        object RequestMicPermission : Effect

        /**
         * Stop capture and let the finished recording be **uploaded**.
         *
         * Every stop that is not a cancel emits this: the machine does not
         * inspect the audio, so the only thing that keeps a recording off the
         * wire is the user asking for that with [DiscardRecording].
         */
        object SubmitRecording : Effect

        /** Stop capture and **discard**: no upload, no transcript, no card. */
        object DiscardRecording : Effect

        /** Open the card already showing "transcribing…". */
        object OpenOverlayTranscribing : Effect

        /** The duration cap cut the recording short; say so. */
        object ReportMaxDuration : Effect

        /**
         * 🔴 Capture could not start; **say so on screen**.
         *
         * `CaptureFailed` used to emit no effects at all, which made the whole
         * failure silent in the new flow. With no usable encoder, with the
         * microphone held exclusively (an in-progress call), or when
         * `startForeground(TYPE_MICROPHONE)` is refused, the tap produced
         * nothing whatsoever: no toast, no notification change, and — because
         * the bubble no longer opens the card on tap 1 — no card either. The
         * error text `MicCapture` produces went to [BubbleSessionBus] and was
         * cleared by the next `reset()` without ever being shown.
         *
         * Pre-redesign this was visible by accident, in the card's status line,
         * because every tap opened the card. A tap that silently does nothing
         * is indistinguishable from the app being dead, so the report is now
         * explicit. [Effect.ReportMaxDuration] already had one; this is the
         * strictly worse case that did not.
         *
         * 🔴 [failure] exists because the first version of this effect carried
         * no cause and the shell toasted one fixed string for all three — which
         * named the wrong cause on two of them. See [Failure].
         */
        data class ReportCaptureFailed(val failure: Failure) : Effect

        /** A tap arrived too soon after the last one and was swallowed. */
        object IgnoredAsTooFast : Effect

        /**
         * Show the recent-dictations row attached to the bubble.
         *
         * 🔴 **Not a state.** There is deliberately no `HISTORY_REVEALED`
         * member in [State]: the reveal is a surface, not a phase of a
         * dictation, and adding a member would force every existing transition
         * to decide about it *and* would drag the reveal inside the
         * `state == IDLE ⇒ session == null` invariant that
         * `BubbleService.applyEffects` enforces — an invariant about the
         * microphone, which the reveal has nothing to do with.
         */
        object RevealHistory : Effect

        /**
         * Put the recent-dictations row away.
         *
         * Emitted **before** whatever else the triggering event produces, so a
         * tap on a revealed bubble reads as "the row goes away, then recording
         * starts" rather than leaving a row of history chips floating over a
         * live recording. The ordering is the assertion; membership alone
         * would pass with the row still on screen.
         */
        object HideHistory : Effect
    }

    var state: State = State.IDLE
        private set

    private var startedAtMs: Long = 0L

    /** When the current recording was handed off for transcription. */
    private var submittedAtMs: Long = 0L

    /** Milliseconds recorded so far, or 0 when not recording. */
    val elapsedMs: Long
        get() = if (state == State.RECORDING) now() - startedAtMs else 0L

    /**
     * Whether the history row is currently on screen.
     *
     * 🔴 A private flag rather than a [State] member, and the distinction is
     * the whole of the design decision recorded on [Effect.RevealHistory]. It
     * exists only so that the *next* event can emit [Effect.HideHistory] —
     * which has to be decided here, in the one place that sees every event, or
     * the shell ends up spelling "hide the row unless this was a long press"
     * at each call site, which is the N-sites predicate this repo has already
     * been bitten by twice.
     */
    private var historyRevealed = false

    /**
     * Applies [event] and returns the effects the shell must carry out, in
     * order. Pure with respect to the platform: nothing here touches Android.
     *
     * Every event other than [Event.BubbleLongPressed] dismisses an open
     * history row, and the [Effect.HideHistory] that does so is **prefixed**
     * to that event's own effects — see [Effect.HideHistory].
     */
    fun on(event: Event): List<Effect> {
        if (event is Event.BubbleLongPressed) return onLongPress()
        val dismiss = if (historyRevealed) listOf(Effect.HideHistory) else emptyList()
        historyRevealed = false
        return dismiss + when (event) {
            is Event.BubbleTapped -> onTap(event.micGranted)
            Event.CancelTapped -> onCancel()
            is Event.CaptureFailed -> onCaptureFailed(event.failure)
            Event.MaxDurationReached -> onMaxDuration()
            Event.TranscriptSettled -> toIdle()
            // Dismiss-only: the `dismiss` prefix above IS this event's whole
            // behaviour. See Event.TouchedOutside.
            Event.TouchedOutside -> emptyList()
            // Unreachable: handled above, before `historyRevealed` is cleared.
            Event.BubbleLongPressed -> emptyList()
        }
    }

    /**
     * 🔴 The long press is honoured **only while idle**, and the two refusals
     * are not symmetry for its own sake:
     *
     *  * **RECORDING.** Tap 2 on this same control is stop-and-submit, so a
     *    press that is already in progress is ambiguous to the thumb before
     *    anything is drawn. Revealing there puts a row of buttons under a
     *    finger that is in the middle of a stop gesture, and the nearest
     *    control to that finger is `cancel` — which is how an accidental
     *    discard of a recording the user wanted ships. Silence is the right
     *    answer: their next action is a tap, and a tap means stop.
     *  * **TRANSCRIBING.** Nothing to escape from. The grace window in [onTap]
     *    exists because a transcript that never arrives would otherwise leave
     *    the bubble permanently dead; a long press cannot wedge anything, so
     *    it needs no equivalent and gets none.
     *
     * The state is left exactly as it was in all three cases.
     */
    private fun onLongPress(): List<Effect> = if (state == State.IDLE) {
        historyRevealed = true
        listOf(Effect.RevealHistory)
    } else {
        emptyList()
    }

    private fun onTap(micGranted: Boolean): List<Effect> = when (state) {
        State.IDLE -> {
            if (!micGranted) {
                listOf(Effect.RequestMicPermission)
            } else {
                state = State.RECORDING
                startedAtMs = now()
                listOf(Effect.StartRecording)
            }
        }

        State.RECORDING -> {
            // 🔴 Debounce. Without it a double-tap starts and instantly submits
            // a recording of a few milliseconds — which is both a wasted upload
            // and, because the audio is all-but-empty, exactly the input that
            // makes the ASR service hallucinate (issue #8).
            if (now() - startedAtMs < MIN_RECORDING_MS) {
                listOf(Effect.IgnoredAsTooFast)
            } else {
                finishRecording()
            }
        }

        // 🔴 NOT simply ignored, and the reason is a real failure rather than a
        // nicety. Leaving TRANSCRIBING depends on a transcript arriving — and
        // the one path that produces none at all is the server being down with
        // no local fallback text to show. An unconditional "ignore" here would
        // therefore leave the bubble permanently dead in exactly the situation
        // where the user most wants to retry, with no error and no way back
        // short of restarting the service.
        //
        // So the card owns the interaction only briefly: long enough to swallow
        // the stop-tap's own accidental double. After that a tap means what it
        // always means, and the user can start another recording whether or not
        // the previous transcript ever landed.
        State.TRANSCRIBING -> {
            if (now() - submittedAtMs < TRANSCRIBE_GRACE_MS) {
                emptyList()
            } else {
                state = State.IDLE
                onTap(micGranted)
            }
        }
    }

    private fun onCancel(): List<Effect> =
        if (state == State.RECORDING) {
            state = State.IDLE
            // No overlay and no upload: a cancel that opened a card, or that
            // sent the audio anyway, is not a cancel.
            listOf(Effect.DiscardRecording)
        } else {
            emptyList()
        }

    private fun onMaxDuration(): List<Effect> =
        if (state == State.RECORDING) {
            // Deliberately NOT debounced: the cap is two minutes and the
            // debounce window is half a second, so the two cannot interact —
            // but routing the cap through the same guard would make a future
            // change to either number silently able to disable the cap.
            listOf(Effect.ReportMaxDuration) + finishRecording()
        } else {
            emptyList()
        }

    /**
     * The shared tail of "stop recording": submit, and open the card already in
     * its transcribing state.
     *
     * 🔴 Unconditional, and that is a decision rather than an omission. A
     * client-side silence gate stood here — it discarded a recording whose peak
     * normalised RMS never crossed `0.002` — and it was removed because the
     * premise it rested on was measured false, not because its constant was
     * mistuned. Measured against the real service at 16 kHz mono: room tone with
     * no speech at a peak of **0.002023 hallucinated** fluent invented prose,
     * while clean speech at a peak of **0.001825 transcribed verbatim**. The two
     * populations overlap and are *inverted* — the hallucinating silence is
     * LOUDER than the good quiet speech — so no peak-RMS floor can separate
     * them, and the one chosen was wrong in both directions at once. Nor can
     * [AudioCaptureLogic.isAllZero] stand in: every hallucinating case measured
     * was non-zero audio.
     *
     * The hallucination (issue #8) is therefore **still open and not handled
     * here**. It needs a signal this class does not have — the local engine's
     * own transcript to cross-check against — so do not reintroduce a level
     * threshold in this spot.
     */
    private fun finishRecording(): List<Effect> {
        state = State.TRANSCRIBING
        submittedAtMs = now()
        return listOf(Effect.SubmitRecording, Effect.OpenOverlayTranscribing)
    }

    private fun toIdle(): List<Effect> {
        state = State.IDLE
        return emptyList()
    }

    /**
     * Back to idle **with a report**. See [Effect.ReportCaptureFailed] for why
     * the silent version was a defect rather than a tidy no-op.
     *
     * Emitted unconditionally rather than only from RECORDING: capture failure
     * is reached from the IDLE→RECORDING transition itself (the shell has
     * already moved the machine to RECORDING when it tries to open the mic),
     * and a `state == RECORDING` guard here would be a condition that is
     * always true — which is to say untestable, and one refactor away from
     * silently swallowing the only message the user gets.
     *
     * The cause is carried through unexamined: the shell knows which of its
     * own steps refused and this class cannot, so deciding here would be
     * guessing. There is deliberately no second "the session failed
     * mid-recording" event — one existed, mapped to this same handler, and was
     * emitted by nothing at all.
     */
    private fun onCaptureFailed(failure: Failure): List<Effect> {
        state = State.IDLE
        return listOf(Effect.ReportCaptureFailed(failure))
    }

    companion object {
        /**
         * Minimum recording length. A second tap inside this window is
         * swallowed rather than treated as "stop".
         *
         * The bubble's tap target is 60 dp and start/stop are the *same* tap on
         * the *same* control, so an accidental double-tap is a normal thing for
         * a thumb to do — and it would otherwise produce a sub-100 ms recording:
         * a wasted upload, and near-empty audio, which is the input the service
         * answers with invented prose (issue #8). This is a guard on the *tap*,
         * not on the audio — nothing here inspects what was recorded.
         */
        const val MIN_RECORDING_MS: Long = 500L

        /**
         * The runaway cap. At it, the recording is auto-submitted rather than
         * discarded — the user said something, and throwing it away would be
         * worse than transcribing a truncated version of it. (Operator
         * decision, re-confirmed 2026-10-17 when this was raised.)
         *
         * 🔴 Why this is a cap on the *recording* and not a concern about the
         * transcript: `STT_MAX_NEW_TOKENS` on the service is a per-chunk limit
         * and the service chunks at ≤35 s, so this is handled as several chunks
         * rather than as one truncated decode. The cap is here because the
         * bubble flow puts no CARD on screen while recording, so a bubble left
         * recording in a pocket is easy to walk away from.
         *
         * ## Why TEN minutes, and why not more
         *
         * Raised from 120 s on 2026-10-17 (operator: *"too low, needs to support
         * hours"*). Ten is what the EXISTING upload contract supports with
         * headroom — not a preference, and not the ask, which was hours:
         *
         *  - [SttRetryPolicy.readTimeoutMs] caps the client's wait at **90 s**,
         *    deliberately, so one clip cannot pin the single GPU replica.
         *  - Measured server throughput, from this app's own `stt-api` log on
         *    2026-10-17: RTFx **14.5 / 41.0 / 45.7** over three real uploads.
         *  - At the WORST of those, 10 min of audio needs ~41 s of server time;
         *    the ceiling before the 90 s timeout starts biting is ~21 min.
         *
         * 🔴 **Hours is NOT reachable by changing this constant and nothing
         * else, and raising it further without the work below produces a
         * recording that can never be transcribed:** a 3 h clip needs 4–12 min
         * of server time and the client hangs up at 90 s, on every one of the
         * four authorised attempts. It also needs a segmented, resumable upload
         * ([RemoteTranscriber] reads the whole file into memory and posts one
         * fixed-length multipart body, so a failure at 95% loses everything), a
         * wakelock (there is none, and the mesh tunnel was measured dropping
         * while the phone sleeps), and a progress surface. Storage is NOT a
         * blocker — Opus at 24 kbps is ~10.8 MB/hour against a 2 GiB bound.
         *
         * ⚠ **This raise makes `#14` item 6 five times worse and that is
         * accepted, not overlooked.** That item reads: *"A bubble left recording
         * in a pocket uploads two minutes of audio. More pointed since the
         * silence gate was removed — see #8."* Ten minutes of pocket silence is
         * ten minutes the server will answer with confident invented prose. The
         * mitigation shipped alongside is the notification chronometer in
         * `BubbleService.buildNotification` — the cap's own rationale is that
         * nothing reminds you, so the fix is to make it remind you.
         */
        const val MAX_RECORDING_MS: Long = 600_000L

        /**
         * How long a tap on the bubble is swallowed after submitting.
         *
         * 🔴 This window is the only thing standing between a failed transcript
         * and a permanently dead bubble, so it is deliberately SHORT. Its job is
         * narrow: absorb the accidental second tap that follows the stop tap
         * (the same thumb, the same control, a few hundred milliseconds apart),
         * which would otherwise start a recording the user never asked for while
         * the card was still opening. It is NOT a wait for the transcript —
         * waiting for that is what wedges, because the server-down path emits no
         * transcript at all.
         */
        const val TRANSCRIBE_GRACE_MS: Long = 1_500L
    }
}
