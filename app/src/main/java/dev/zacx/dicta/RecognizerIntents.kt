package dev.zacx.dicta

import android.content.Intent
import android.media.AudioFormat
import android.os.ParcelFileDescriptor
import android.speech.RecognizerIntent

/**
 * Builds the `ACTION_RECOGNIZE_SPEECH` intent, including the
 * `EXTRA_AUDIO_SOURCE` plumbing that lets one `AudioRecord` serve both the
 * on-device recogniser and the remote upload.
 *
 * ## Why the pipe exists at all — a spike result, not a preference
 *
 * 🔴 Running `AudioRecord` and `SpeechRecognizer` as two concurrent captures
 * **does not work and does not fail loudly**. Android enforces its
 * one-capture-per-moment policy by handing the loser a stream of **exact
 * zeros**: `startRecording()` returns success and `read()` returns the full
 * requested byte count, every byte 0. Two ordinary apps can never capture
 * audio at the same time.
 *
 * `RecognizerIntent.EXTRA_AUDIO_SOURCE` (API 33+) is the supported way out:
 * we keep the single legitimate microphone client and hand the recogniser a
 * [ParcelFileDescriptor] **read end** of our own pipe. One mic client, one
 * UID, nothing to arbitrate.
 *
 * ## The three explicit format extras
 *
 * The documented defaults are already 16 kHz, mono, `ENCODING_PCM_16BIT` —
 * exactly what we produce. They are set anyway: a default is a statement
 * about today's implementation, and a recogniser that assumed something else
 * would mis-decode our bytes into plausible-sounding garbage rather than
 * error, which is the worst failure shape available.
 *
 * ## 🔴 Support is optional and the fallback is silent
 *
 * `EXTRA_AUDIO_SOURCE` is optional per recogniser implementation. An
 * implementation that does not honour it ignores the extra and opens the
 * microphone itself — no exception, no capability query. Whether any given
 * handset honours it is therefore **not** knowable from the API; it is
 * detected at runtime by [AudioCaptureLogic.tierFor] watching for the absence
 * of partials while non-zero PCM is flowing.
 */
object RecognizerIntents {

    /**
     * The intent for a session that feeds the recogniser our own PCM.
     *
     * @param pipeReadEnd the read end of the pipe we write capture into. The
     *   caller retains ownership of the write end and **must close it to end
     *   the session** — see [endpointingIsOurs].
     */
    fun sharedSourceIntent(
        pipeReadEnd: ParcelFileDescriptor,
        language: String,
        sampleRateHz: Int = AudioCaptureLogic.SAMPLE_RATE_HZ,
    ): Intent = baseIntent(language).apply {
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipeReadEnd)
        putExtra(
            RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT,
            AudioCaptureLogic.CHANNEL_COUNT,
        )
        putExtra(
            RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, sampleRateHz)
    }

    /**
     * The intent for a plain recogniser session that opens the microphone
     * itself — used only when [AudioCaptureLogic.supportsSharedAudioSource]
     * is false *and* we are not holding the microphone.
     */
    fun ownMicIntent(language: String): Intent = baseIntent(language)

    /**
     * The intent for the permission-free fallback that hands capture to the
     * system voice UI.
     *
     * 🔴 Deliberately **not** [baseIntent]: that one asks for partial results
     * and three candidates, which this path neither reads nor wants. What it
     * does share is [RecognizerIntent.EXTRA_LANGUAGE], and sharing *that* is
     * the whole reason this function exists rather than an `Intent {}` built
     * by hand at the call site — a hand-built second intent is precisely how
     * one of the two capture paths kept following the device locale after the
     * other had been fixed.
     */
    fun systemVoiceUiIntent(language: String, prompt: String): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        }

    private fun baseIntent(language: String): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            // 🔴 WITHOUT THIS THE RECOGNISER FOLLOWS THE DEVICE LOCALE, and
            // that is not a default — it is the setting being ignored. The
            // remote path has resolved an explicit preference since issue #7
            // while this intent carried no language at all, so a Spanish
            // handset returned Spanish for English speech whenever the
            // on-device engine served the dictation. `EXTRA_LANGUAGE_MODEL`
            // is a different knob (free-form vs web-search) and never set the
            // language, which is why its presence made this look handled.
            //
            // The value is a bare ISO code from SttLanguage.SUPPORTED, which
            // is also a well-formed IETF tag — the same string the remote
            // request sends, by construction, because both come from
            // SttSettings.dictationLanguage.
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }

    /**
     * Documentation-as-a-constant: when the recogniser is fed from a pipe it
     * does **not** endpoint on trailing silence. It reads until EOF. Closing
     * the write end of the pipe is therefore the only thing that ends the
     * session, and a session whose write end is leaked hangs forever.
     *
     * Referenced from the capture code's close path so that path cannot be
     * deleted without this comment showing up in the diff.
     */
    const val endpointingIsOurs: Boolean = true
}
