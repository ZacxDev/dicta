package dev.zacx.dicta

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Thin wrapper around [SpeechRecognizer] delivering live partial results and a
 * final result. Must be used from the main thread (see SpeechRecognizer docs).
 */
class SpeechHelper(
    context: Context,
    private val listener: Listener
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
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
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
