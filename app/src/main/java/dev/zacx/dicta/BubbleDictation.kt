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

        /** Capture could not start at all (no encoder, mic busy, …). */
        object CaptureFailed : Event

        /** The runaway cap fired. */
        object MaxDurationReached : Event

        /** The session reported a terminal error. */
        object SessionFailed : Event

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

        /** A tap arrived too soon after the last one and was swallowed. */
        object IgnoredAsTooFast : Effect
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
     * Applies [event] and returns the effects the shell must carry out, in
     * order. Pure with respect to the platform: nothing here touches Android.
     */
    fun on(event: Event): List<Effect> = when (event) {
        is Event.BubbleTapped -> onTap(event.micGranted)
        Event.CancelTapped -> onCancel()
        Event.CaptureFailed -> toIdle()
        Event.SessionFailed -> toIdle()
        Event.MaxDurationReached -> onMaxDuration()
        Event.TranscriptSettled -> toIdle()
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
         * worse than transcribing a truncated version of it.
         *
         * 🔴 Why this is a cap on the *recording* and not a concern about the
         * transcript: `STT_MAX_NEW_TOKENS` on the service is a per-chunk limit
         * and the service chunks at ≤35 s, so two minutes of audio is handled as
         * several chunks rather than as one truncated decode. The cap is here
         * because the bubble flow puts **nothing on screen** while recording —
         * no card, no status line — so a bubble left recording in a pocket has
         * no reminder attached to it. The pulse and the cancel button are the
         * only indications, and both are easy to walk away from.
         */
        const val MAX_RECORDING_MS: Long = 120_000L

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
