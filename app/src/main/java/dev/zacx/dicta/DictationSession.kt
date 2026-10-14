package dev.zacx.dicta

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One dictation: capture, the on-device recogniser, the upload, and the
 * three-tier degradation between them.
 *
 * ## The contract with the UI
 *
 * Whatever happens, [Callbacks.onTranscript] is called at least once if
 * *either* engine produced text, and it always says **which engine** produced
 * what it is handing over. A remote transcript replaces a local one; a local
 * transcript is never thrown away on the expectation that a remote one is
 * coming.
 *
 * ## The three tiers
 *
 * 1. **[AudioCaptureLogic.Tier.LOCAL_PARTIALS_AND_REMOTE]** —
 *    `EXTRA_AUDIO_SOURCE` honoured. Live partials from the phone while
 *    recording, then the server's more accurate transcript replaces them.
 * 2. **[AudioCaptureLogic.Tier.REMOTE_ONLY_AFTER_TIMEOUT]** — the extra was
 *    accepted but no partial arrived within
 *    [AudioCaptureLogic.FIRST_PARTIAL_TIMEOUT_MS] *while non-zero PCM was
 *    being written*. Recognition is cancelled, recording continues, the level
 *    meter carries the feedback, and the transcript is remote-only.
 * 3. **[AudioCaptureLogic.Tier.REMOTE_ONLY_NO_API]** — `SDK_INT < 33`. Same
 *    behaviour as tier 2, decided up front.
 *
 * 🔴 Tiers 2 and 3 exist because `EXTRA_AUDIO_SOURCE` support is **optional
 * per recogniser implementation** and an implementation that ignores it does
 * so *silently* — it just opens the microphone, which we already hold, and
 * then receives the exact zeros Android feeds the loser of mic arbitration.
 * There is no capability flag to query, so the tier is detected at runtime.
 */
class DictationSession(
    context: Context,
    private val settings: SttSettings,
    private val callbacks: Callbacks,
    /** Overridable for tests; production uses the real device locale. */
    private val locale: Locale = Locale.getDefault(),
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {

    interface Callbacks {
        /**
         * @param text the transcript.
         * @param fromRemote true when the self-hosted service produced it,
         *   false when the phone did. 🔴 Not cosmetic: the two engines
         *   disagree, so the UI must be able to tell the user which one is on
         *   screen.
         * @param isFinal false for a live partial.
         */
        fun onTranscript(text: String, fromRemote: Boolean, isFinal: Boolean)

        /** Status line text, already localised and secret-free. */
        fun onStatus(text: String)

        /** Normalised `[0, 1]` microphone level for the meter. */
        fun onLevel(level: Double)

        /** The tier actually in force, once known. */
        fun onTier(tier: AudioCaptureLogic.Tier)

        /** The platform is feeding us silence; surfaced as a real UI state. */
        fun onClientSilenced(silenced: Boolean)

        /** Recording stopped (so the UI can flip the mic button back). */
        fun onRecordingStopped()
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()

    private var capture: MicCapture? = null
    private var speech: SpeechHelper? = null
    private var audioFile: File? = null

    private val sawPartial = AtomicBoolean(false)
    private val recording = AtomicBoolean(false)
    private var localTranscript: String = ""
    private var tier: AudioCaptureLogic.Tier =
        AudioCaptureLogic.Tier.LOCAL_PARTIALS_AND_REMOTE

    val isRecording: Boolean get() = recording.get()

    private val uploadSuppressed = AtomicBoolean(false)

    /**
     * 🔴 Closes the **class**, not just the instance's capture.
     *
     * `destroy()` does `worker.shutdownNow()` and
     * `main.removeCallbacksAndMessages(null)`, which only drops messages that
     * are *already queued*. A worker thread blocked in `http.transcribe()` is
     * blocked in a socket read, which `shutdownNow`'s interrupt does not
     * unblock — it can return long afterwards and still run
     * `main.post { callbacks.onTranscript(..., isFinal = true) }`.
     *
     * Delivered to [BubbleService], that late `isFinal` drives
     * `TranscriptSettled → toIdle()` on the state machine of a *different,
     * live* recording: IDLE with the microphone open, no pulse ring, no cancel
     * button, the FGS left promoted, and `onMaxDuration()` inert in IDLE so the
     * two-minute cap never fires. The generation gate in `BubbleService` is the
     * other half; this is the half that belongs to the dead session itself, so
     * every consumer gets it, including ones added later.
     */
    private val destroyed = AtomicBoolean(false)

    /** True once [destroy] has run. */
    val isDestroyed: Boolean get() = destroyed.get()

    /**
     * Every callback that is *deferred* goes through [postToMain] or
     * [postToMainDelayed], so "a destroyed session delivers nothing late" is a
     * property of two routes in this file rather than of N remembered checks.
     * Gated at **delivery** time, not post time, so a message that was already
     * queued when [destroy] ran cannot deliver.
     *
     * 🔴 That is *not* the whole hazard, which this sentence used to claim.
     * [destroy] calls `main.removeCallbacksAndMessages(null)`, so a message
     * posted while alive and still pending at death is **deleted**, not merely
     * gated — the gate never sees it. What the gate actually covers is the case
     * `removeCallbacksAndMessages` cannot: a `post` that lands *after*
     * `destroy()` has already swept the queue, i.e. a worker or recogniser
     * callback thread that raced the teardown. Narrower than the old wording,
     * and the only reading under which these two routes earn their keep.
     *
     * 🔴 The precise claim, because an earlier version of this comment
     * overstated it. Three callbacks are *not* deferred — the `onStatus` calls
     * in [start] and the `onRecordingStopped` in [stop] — and they are instead
     * on paths that cannot run after `destroy()`: `start()` returns at its
     * `destroyed` check before either status, and `stop()`'s is explicitly
     * guarded. The recogniser's own `onPartial`/`onFinal` used to be a fourth,
     * calling `callbacks.onTranscript` directly (including `isFinal = true`)
     * from a route with no gate at all; they are routed through [postToMain]
     * now, which is what makes the sentence above true for every consumer
     * rather than only for the ones that keep a generation counter of their
     * own.
     */
    private fun postToMain(block: () -> Unit) {
        main.post { if (!destroyed.get()) block() }
    }

    /**
     * [postToMain] with a delay. Separate only because `Handler.postDelayed`
     * is a different call; the gate is identical and is at delivery.
     *
     * ⚠ **Measured: this gate is pinned by nothing, and cannot honestly be.**
     * A mutation removing the `destroyed` check here leaves the whole suite
     * green. Two independent reasons, and the **stronger one is the handler**:
     *
     * 1. [destroy] calls `main.removeCallbacksAndMessages(null)`, which deletes
     *    every message this session has pending — including a delayed one that
     *    has not fired. So a message posted *while alive* is not merely gated
     *    after `destroy()`, it is **gone**, and the gate never runs at all.
     * 2. Even if it did run, the one caller — the watchdog in
     *    `MicListener.onFirstAudio` — is already inert: `destroy()` sets
     *    `capture` to null, so `tierFor` is handed `wroteNonZeroPcm = false`
     *    and never decides `REMOTE_ONLY_AFTER_TIMEOUT`.
     *
     * Because of (1), observing this check needs **two** things a test cannot
     * honestly arrange together: a post made *after* `destroy()` (nothing does
     * that — every caller runs on a live session), *and* a re-planted
     * `capture` to make the block's effect visible, which is a state the code
     * makes unreachable. So the check is kept as this route's contract,
     * identical to [postToMain]'s, and is NOT counted as covered. (The
     * open-coded check it replaced was equally unpinned; this is a
     * measurement, not a regression.)
     *
     * 🔴 And therefore the residual hazard for [postToMain] and this route is
     * **a post made after death, not one that outlives it** — the earlier
     * reading of this note ("a message that was posted while alive and runs
     * after death") named a hazard `removeCallbacksAndMessages` already
     * closes. The gate's real value is covering a `post` from a thread that
     * raced `destroy()`, which is why it stays despite being unpinned.
     */
    private fun postToMainDelayed(delayMs: Long, block: () -> Unit) {
        main.postDelayed({ if (!destroyed.get()) block() }, delayMs)
    }

    /**
     * Refuses the upload for the recording currently in progress.
     *
     * 🔴 Why a flag rather than "just call [destroy]": `destroy()` stops the
     * capture, and `MicCapture.stop()` calls `onFinished`, which hands the file
     * to the worker — so the upload is already submitted by the time `destroy()`
     * reaches `worker.shutdownNow()`. Whether the POST actually goes out is then
     * a race, which is not a basis for the feature that needs this to be
     * certain: the cancel button. "Nothing was uploaded" has to be a promise the
     * user can rely on, not the likely outcome of a shutdown race.
     *
     * Checked in `onFinished` *before* anything is enqueued, so "nothing was
     * uploaded" is a property of the control flow and not of a timing window.
     * Cleared by the next [start].
     */
    fun suppressUpload() {
        uploadSuppressed.set(true)
    }

    // --- start / stop -------------------------------------------------------

    /**
     * Opens the microphone.
     *
     * 🔴 The ownership acquire is the **first** thing, before any platform
     * call: a second session must not reach `AudioRecord` at all, because
     * Android would not refuse it — it would feed one of the two exact zeros
     * with no error. See [MicrophoneOwner].
     */
    fun start(): Boolean {
        if (destroyed.get()) return false
        if (recording.get()) return true
        if (!MicrophoneOwner.acquire(this)) {
            // 🔴 Loud, not swallowed. This status reaches every surface the
            // session is wired to — the bubble's bus and the card's status line
            // — and it is the only thing the user can act on. The alternative
            // is the platform's own answer to a second capture: bit-exact
            // zeros, no error, and a transcript that comes back empty.
            callbacks.onStatus(appContext.getString(R.string.stt_mic_in_use))
            return false
        }
        sawPartial.set(false)
        uploadSuppressed.set(false)
        localTranscript = ""

        val file = File(
            appContext.cacheDir,
            "dictation-${System.currentTimeMillis()}.ogg"
        )
        audioFile = file

        val mic = MicCapture(MicListener(), file)
        capture = mic
        if (!mic.start()) {
            capture = null
            // Nothing is open, so holding the mic here would block every later
            // recording in this process.
            //
            // ⚠ Today this is REDUNDANT with the release in `MicListener.onError`
            // below: every false return from `MicCapture.start()` calls
            // `onError` first, so a mutation removing either one alone still
            // leaves the suite green (measured). It is kept because it is
            // lexically paired with the `acquire` above and therefore does not
            // depend on MicCapture's internal contract — do NOT delete it as
            // "covered"; nothing covers it individually.
            MicrophoneOwner.release(this)
            return false
        }
        recording.set(true)

        // Tier decided up front when the platform has no EXTRA_AUDIO_SOURCE.
        if (!AudioCaptureLogic.supportsSharedAudioSource(sdkInt)) {
            setTier(AudioCaptureLogic.Tier.REMOTE_ONLY_NO_API)
        } else {
            startRecognizer(mic)
        }
        callbacks.onStatus(appContext.getString(R.string.stt_recording))
        return true
    }

    /** Hands the recogniser the pipe's read end, not the microphone. */
    private fun startRecognizer(mic: MicCapture) {
        val source = mic.recognizerSource ?: run {
            // The pipe could not be created; the recogniser would otherwise
            // open the mic and get zeros.
            setTier(AudioCaptureLogic.Tier.REMOTE_ONLY_NO_API)
            return
        }
        val helper = SpeechHelper(
            appContext,
            object : SpeechHelper.Listener {
                override fun onReady() {}

                // 🔴 Both forwards go through postToMain, like every other
                // callback here. They used to call `callbacks` directly — the
                // one route out of this class with no `destroyed` gate on it,
                // and the only one that can deliver `isFinal = true`. A
                // destroyed session's late local final then reached whatever
                // was listening: BubbleService happens to have a generation
                // gate of its own, but DictateActivity.SessionCallbacks does
                // not and writes the card's views.
                override fun onPartial(text: String) {
                    sawPartial.set(true)
                    setTier(AudioCaptureLogic.Tier.LOCAL_PARTIALS_AND_REMOTE)
                    postToMain {
                        callbacks.onTranscript(text, fromRemote = false, isFinal = false)
                    }
                }

                override fun onFinal(text: String) {
                    // Not inside the post: this is the session's own state, and
                    // the fallback ladder reads it from the worker thread when
                    // the remote path gives up.
                    localTranscript = text
                    postToMain {
                        callbacks.onTranscript(text, fromRemote = false, isFinal = true)
                    }
                }

                override fun onError(message: String) {
                    // Not surfaced as an error: the local engine failing is
                    // expected in tiers 2 and 3 and the remote path is
                    // unaffected. The status line is owned by the remote
                    // ladder, which has something more useful to say.
                }
            },
            sharedSource = source,
        )
        if (!helper.isAvailable) {
            setTier(AudioCaptureLogic.Tier.REMOTE_ONLY_NO_API)
            return
        }
        speech = helper
        helper.start()
    }

    /**
     * Stops capture.
     *
     * The recogniser's write end is closed by [MicCapture.stop] — 🔴 which is
     * what ends the recognition session, because a pipe-fed recogniser does
     * not endpoint on trailing silence and reads to EOF.
     */
    fun stop() {
        // 🔴 Released here, not in destroy(): the microphone is shut by the
        // capture?.stop() below, while this session lives on for the upload —
        // the card is waiting on it. Holding ownership through a retrying POST
        // would refuse the user's next recording for the whole retry ladder.
        //
        // And released BEFORE the not-recording early return, not inside it.
        // The release is owner-scoped and idempotent, so a redundant stop()
        // costs nothing — whereas ownership stranded inside a guard refuses
        // every later recording in the process, which is the strictly worse
        // failure of the two.
        MicrophoneOwner.release(this)
        if (!recording.compareAndSet(true, false)) return
        speech?.stop()
        capture?.stop() // -> MicListener.onFinished -> the upload
        if (!destroyed.get()) callbacks.onRecordingStopped()
    }

    fun destroy() {
        destroyed.set(true)
        recording.set(false)
        speech?.destroy()
        speech = null
        capture?.stop()
        capture = null
        MicrophoneOwner.release(this)
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
        audioFile?.let { if (it.exists()) it.delete() }
    }

    private fun setTier(next: AudioCaptureLogic.Tier) {
        if (tier == next) return
        tier = next
        postToMain { callbacks.onTier(next) }
    }

    // --- the capture listener ----------------------------------------------

    private inner class MicListener : MicCapture.Listener {

        override fun onLevel(level: Double) {
            postToMain { callbacks.onLevel(level) }
        }

        override fun onClientSilenced(silenced: Boolean) {
            postToMain {
                callbacks.onClientSilenced(silenced)
                if (silenced) {
                    callbacks.onStatus(
                        appContext.getString(R.string.stt_mic_silenced)
                    )
                }
            }
        }

        /**
         * Arms the partial-result watchdog.
         *
         * Armed from the **first non-zero frame**, not from the start of
         * recording: timing it from `start()` would cancel recognition every
         * time the user took more than 1.5 s to begin speaking, which is most
         * of the time.
         */
        override fun onFirstAudio() {
            // postToMainDelayed, not a second open-coded `destroyed` check:
            // the gate belongs to the two posting routes, not to each caller.
            postToMainDelayed(AudioCaptureLogic.FIRST_PARTIAL_TIMEOUT_MS) {
                val decided = AudioCaptureLogic.tierFor(
                    sdkInt = sdkInt,
                    sawPartial = sawPartial.get(),
                    wroteNonZeroPcm = capture?.hasNonZeroAudio == true,
                    elapsedSincePcmMs = capture?.millisSinceFirstAudio ?: 0L,
                )
                if (decided == AudioCaptureLogic.Tier.REMOTE_ONLY_AFTER_TIMEOUT) {
                    // Cancel, not stop: stop() asks for a final result from a
                    // session we have concluded is dead. Recording continues.
                    speech?.cancel()
                    setTier(decided)
                    callbacks.onStatus(
                        appContext.getString(R.string.stt_tier_remote_only)
                    )
                }
            }
        }

        override fun onError(message: String) {
            recording.set(false)
            // The microphone never opened (or has just died); another session
            // must be able to take it. Pinned by
            // MicrophoneOwnershipTest.aCaptureErrorReleasesTheMicrophone —
            // driven through this listener rather than through start(), see the
            // note on the release in start()'s own failure branch.
            MicrophoneOwner.release(this@DictationSession)
            postToMain {
                callbacks.onStatus(message)
                callbacks.onRecordingStopped()
            }
        }

        override fun onFinished(file: File?, audioSeconds: Double) {
            // 🔴 Before everything: destroy() stops the capture, which lands
            // here. Enqueueing on an executor destroy() is about to
            // shutdownNow() made "was it uploaded" a race, and after
            // shutdownNow it throws RejectedExecutionException on the capture
            // thread. A destroyed session uploads nothing and says nothing.
            if (destroyed.get()) {
                file?.let { if (it.exists()) it.delete() }
                return
            }
            // 🔴 Before the null/duration check and before anything is enqueued:
            // the caller asked for this recording NOT to be transcribed (the
            // cancel button, or a service teardown), so it must not reach the
            // service and must not announce a status it will never deliver.
            if (uploadSuppressed.get()) {
                file?.let { if (it.exists()) it.delete() }
                return
            }
            if (file == null || audioSeconds <= 0.0) {
                postToMain {
                    callbacks.onStatus(appContext.getString(R.string.stt_no_audio))
                }
                return
            }
            worker.execute { transcribeRemotely(file, audioSeconds) }
        }
    }

    // --- the remote path ----------------------------------------------------

    /**
     * Runs the retry / fallback ladder for the finished recording.
     *
     * On the worker thread. [SingleFlightUploader] wraps the transport so the
     * 🔴 exactly-one-in-flight invariant is observable rather than merely
     * implied by the executor having one thread.
     */
    private fun transcribeRemotely(file: File, audioSeconds: Double) {
        if (!settings.isRemoteConfigured) {
            postToMain {
                callbacks.onStatus(
                    appContext.getString(R.string.stt_not_configured)
                )
            }
            return
        }
        val token = settings.tokens.token() ?: run {
            postToMain {
                callbacks.onStatus(
                    appContext.getString(R.string.stt_not_configured)
                )
            }
            return
        }
        val audio = try {
            file.readBytes()
        } catch (_: Exception) {
            postToMain {
                callbacks.onStatus(appContext.getString(R.string.stt_no_audio))
            }
            return
        }

        postToMain {
            callbacks.onStatus(appContext.getString(R.string.stt_uploading))
        }

        val http = SttHttp(settings.baseUrl, token)
        val serialised = SingleFlightUploader { http.transcribe(it) }

        TranscriptionCoordinator(
            transport = { serialised.upload(it) },
            health = { http.healthz() },
            sleeper = { ms ->
                try {
                    Thread.sleep(ms)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            },
            unitRandom = { Math.random() },
            elapsedMs = { System.currentTimeMillis() },
            events = object : TranscriptionCoordinator.Events {
                override fun onRemoteTranscript(text: String) {
                    postToMain {
                        callbacks.onTranscript(
                            text, fromRemote = true, isFinal = true
                        )
                        callbacks.onStatus(
                            appContext.getString(R.string.stt_remote_done)
                        )
                    }
                }

                override fun onFellBack(reason: String, recoveryPending: Boolean) {
                    postToMain {
                        // 🔴 The user is told the text on screen came from the
                        // phone, and whether it may still be replaced. A silent
                        // swap is how a less accurate transcript gets trusted.
                        callbacks.onStatus(
                            appContext.getString(
                                if (recoveryPending) {
                                    R.string.stt_fell_back_retrying
                                } else {
                                    R.string.stt_fell_back
                                },
                                reason,
                            )
                        )
                        if (localTranscript.isNotBlank()) {
                            callbacks.onTranscript(
                                localTranscript, fromRemote = false, isFinal = true
                            )
                        }
                    }
                }

                override fun onStatus(message: String) {
                    postToMain { callbacks.onStatus(message) }
                }
            },
            // 🔴 A fresh budget per recording — the four-upload ceiling is
            // per-recording, and reusing one across recordings would starve
            // every recording after the first.
            budget = UploadBudget(),
        ).run(
            audio = audio,
            audioSeconds = audioSeconds,
            // 🔴 The bare ISO code, never the BCP-47 tag. toLanguageTag()
            // returns "en-US" here, which the service passes unvalidated to a
            // processor that raises ValueError outside its try/except — an
            // unhandled 500.
            //
            // The user's explicit Settings choice wins over the device locale
            // (issue #7): on an es-CO handset the locale alone made English
            // dictation impossible, because the service faithfully transcribed
            // English speech as Spanish — which reads as a broken recogniser
            // rather than as a setting nobody was offered. null still means
            // "follow the device", and is still the default.
            language = SttLanguage.resolve(settings.language, locale),
        )
    }
}
