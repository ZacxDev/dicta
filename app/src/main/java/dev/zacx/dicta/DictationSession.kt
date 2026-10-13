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

    // --- start / stop -------------------------------------------------------

    fun start(): Boolean {
        if (recording.get()) return true
        sawPartial.set(false)
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

                override fun onPartial(text: String) {
                    sawPartial.set(true)
                    setTier(AudioCaptureLogic.Tier.LOCAL_PARTIALS_AND_REMOTE)
                    callbacks.onTranscript(text, fromRemote = false, isFinal = false)
                }

                override fun onFinal(text: String) {
                    localTranscript = text
                    callbacks.onTranscript(text, fromRemote = false, isFinal = true)
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
        if (!recording.compareAndSet(true, false)) return
        speech?.stop()
        capture?.stop() // -> MicListener.onFinished -> the upload
        callbacks.onRecordingStopped()
    }

    fun destroy() {
        recording.set(false)
        speech?.destroy()
        speech = null
        capture?.stop()
        capture = null
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
        audioFile?.let { if (it.exists()) it.delete() }
    }

    private fun setTier(next: AudioCaptureLogic.Tier) {
        if (tier == next) return
        tier = next
        main.post { callbacks.onTier(next) }
    }

    // --- the capture listener ----------------------------------------------

    private inner class MicListener : MicCapture.Listener {

        override fun onLevel(level: Double) {
            main.post { callbacks.onLevel(level) }
        }

        override fun onClientSilenced(silenced: Boolean) {
            main.post {
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
            main.postDelayed({
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
            }, AudioCaptureLogic.FIRST_PARTIAL_TIMEOUT_MS)
        }

        override fun onError(message: String) {
            recording.set(false)
            main.post {
                callbacks.onStatus(message)
                callbacks.onRecordingStopped()
            }
        }

        override fun onFinished(file: File?, audioSeconds: Double) {
            if (file == null || audioSeconds <= 0.0) {
                main.post {
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
            main.post {
                callbacks.onStatus(
                    appContext.getString(R.string.stt_not_configured)
                )
            }
            return
        }
        val token = settings.tokens.token() ?: run {
            main.post {
                callbacks.onStatus(
                    appContext.getString(R.string.stt_not_configured)
                )
            }
            return
        }
        val audio = try {
            file.readBytes()
        } catch (_: Exception) {
            main.post {
                callbacks.onStatus(appContext.getString(R.string.stt_no_audio))
            }
            return
        }

        main.post {
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
                    main.post {
                        callbacks.onTranscript(
                            text, fromRemote = true, isFinal = true
                        )
                        callbacks.onStatus(
                            appContext.getString(R.string.stt_remote_done)
                        )
                    }
                }

                override fun onFellBack(reason: String, recoveryPending: Boolean) {
                    main.post {
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
                    main.post { callbacks.onStatus(message) }
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
