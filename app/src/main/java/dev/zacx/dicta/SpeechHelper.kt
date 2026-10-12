package dev.zacx.dicta

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Thin wrapper around [SpeechRecognizer] delivering live partial results and a
 * final result. Must be used from the main thread (see SpeechRecognizer docs).
 *
 * ## The on-device engine's role changed
 *
 * It used to be the only engine. It is now the **fallback** and the source of
 * live partials, while the authoritative transcript comes from the remote
 * service — so this class no longer opens the microphone when it can avoid
 * it. When [sharedSource] is supplied it is fed PCM through a pipe that
 * [MicCapture] writes, because 🔴 two concurrent captures do not work and do
 * not fail loudly: Android enforces its policy by handing the loser **exact
 * zeros**, with a successful `startRecording()` and no exception.
 *
 * @param sharedSource the read end of [MicCapture]'s pipe, or null to let the
 *   recogniser open the microphone itself — which is only correct when
 *   nothing else holds it.
 */
class SpeechHelper(
    context: Context,
    private val listener: Listener,
    private val sharedSource: ParcelFileDescriptor? = null,
    private val sampleRateHz: Int = AudioCaptureLogic.SAMPLE_RATE_HZ,
) {
    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
        fun onReady()
    }

    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    val isListening: Boolean get() = listening
    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(appContext)

    fun start() {
        if (listening) return
        try {
            val r = SpeechRecognizer.createSpeechRecognizer(appContext)
            recognizer = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = listener.onReady()
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onError(error: Int) {
                    listening = false
                    listener.onError(errorName(error))
                }

                override fun onResults(results: Bundle?) {
                    listening = false
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    if (text.isNotBlank()) listener.onFinal(text)
                    else listener.onError("No speech recognized")
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    if (text.isNotBlank()) listener.onPartial(text)
                }
            })
            val intent = sharedSource
                ?.let { RecognizerIntents.sharedSourceIntent(it, sampleRateHz) }
                ?: RecognizerIntents.ownMicIntent()
            listening = true
            r.startListening(intent)
        } catch (t: Throwable) {
            listening = false
            listener.onError(t.message ?: "Recognizer failed to start")
        }
    }

    fun stop() {
        listening = false
        try {
            recognizer?.stopListening()
        } catch (_: Throwable) {
        }
    }

    /**
     * Abandons recognition without asking for a final result.
     *
     * This is the tier-2 degradation path: the `EXTRA_AUDIO_SOURCE` extra was
     * accepted but no partial ever arrived while real audio was flowing, so
     * the recogniser is not going to produce anything. `cancel()` rather than
     * `stop()` because `stop()` asks for a final result from a session we have
     * concluded is dead, and the recogniser would sit on it until it times
     * out. Recording continues — only the local engine is given up on.
     */
    fun cancel() {
        listening = false
        try {
            recognizer?.cancel()
        } catch (_: Throwable) {
        }
    }

    fun destroy() {
        listening = false
        try {
            recognizer?.destroy()
        } catch (_: Throwable) {
        }
        recognizer = null
    }

    private fun errorName(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
        SpeechRecognizer.ERROR_CLIENT -> "Client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Missing microphone permission"
        SpeechRecognizer.ERROR_NETWORK -> "Network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized — try again"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
        SpeechRecognizer.ERROR_SERVER -> "Recognition server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
        else -> "Recognition error ($error)"
    }
}
