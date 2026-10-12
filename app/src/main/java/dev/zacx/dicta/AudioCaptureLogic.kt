package dev.zacx.dicta

/**
 * The decisions the capture layer has to make, separated from the platform
 * objects that make them untestable.
 *
 * `AudioRecord`, `MediaCodec` and `SpeechRecognizer` have no usable
 * Robolectric implementations, so anything that touches them is device-only
 * evidence. What *can* be pinned is the arithmetic and the branching, and
 * those are the parts that were actually getting decided wrong.
 */
object AudioCaptureLogic {

    /** The capture format. 16 kHz mono is what the recogniser documents. */
    const val SAMPLE_RATE_HZ: Int = 16_000
    const val CHANNEL_COUNT: Int = 1
    const val BYTES_PER_SAMPLE: Int = 2

    /**
     * Anything above 16 kHz mono is wasted bytes: the service resamples to
     * 16 kHz mono internally regardless of what it is sent.
     */
    const val FALLBACK_SAMPLE_RATE_HZ: Int = 48_000

    /** ~24 kbps mono Opus ≈ 3 kB/s, so 15 s of dictation is about 45 kB. */
    const val OPUS_BITRATE: Int = 24_000

    /** AAC-LC needs a little more for comparable intelligibility. */
    const val AAC_BITRATE: Int = 32_000

    const val MIME_OPUS: String = "audio/opus"
    const val MIME_AAC: String = "audio/mp4a-latm"

    /**
     * How long to wait for the first partial result before concluding that
     * the recogniser is not going to produce any.
     *
     * 🔴 The reason this timer exists: `EXTRA_AUDIO_SOURCE` support is
     * **optional per recogniser implementation**, and an implementation that
     * does not support it *silently ignores it and opens the microphone
     * itself*. There is no error and no capability flag to query. Since we
     * already hold the one legitimate `AudioRecord`, the recogniser's own
     * open then yields a stream Android fills with **exact zeros** (its
     * concurrent-capture policy is enforced by silence, not by an exception),
     * so it will never endpoint and never emit a partial. The absence of
     * partials *while we are demonstrably writing non-zero PCM* is the only
     * available tell.
     */
    const val FIRST_PARTIAL_TIMEOUT_MS: Long = 1_500L

    /**
     * Which degradation tier the session is running in.
     *
     * Every tier must still produce a transcript — the tiers differ only in
     * whether the *local* engine contributes live partials and a fallback
     * transcript, never in whether the user gets text.
     */
    enum class Tier {
        /** `EXTRA_AUDIO_SOURCE` honoured: live local partials + remote final. */
        LOCAL_PARTIALS_AND_REMOTE,

        /**
         * The extra was accepted but no partials arrived while real audio was
         * flowing. Recognition is cancelled, recording continues, the level
         * meter carries the "it is listening" signal, and the transcript is
         * remote-only.
         */
        REMOTE_ONLY_AFTER_TIMEOUT,

        /** `SDK_INT < 33`: the extra does not exist. Same behaviour as above. */
        REMOTE_ONLY_NO_API,
    }

    /** The first API level with `RecognizerIntent.EXTRA_AUDIO_SOURCE`. */
    const val MIN_SDK_FOR_AUDIO_SOURCE: Int = 33

    /**
     * Whether the pipe-the-recogniser-our-own-PCM path is even available.
     *
     * `minSdk` is 26, so this is a real branch and not a formality.
     */
    fun supportsSharedAudioSource(sdkInt: Int): Boolean =
        sdkInt >= MIN_SDK_FOR_AUDIO_SOURCE

    /**
     * The tier to run in, given what has been observed so far.
     *
     * @param sdkInt the running platform level.
     * @param sawPartial whether any partial result has arrived.
     * @param wroteNonZeroPcm whether we have written at least one non-zero
     *   PCM frame into the recogniser's pipe. 🔴 Load-bearing: without it, a
     *   genuinely silent room is indistinguishable from an ignored
     *   `EXTRA_AUDIO_SOURCE`, and the client would cancel a perfectly good
     *   recognition session every time the user paused before speaking.
     * @param elapsedSincePcmMs time since the first non-zero frame.
     */
    fun tierFor(
        sdkInt: Int,
        sawPartial: Boolean,
        wroteNonZeroPcm: Boolean,
        elapsedSincePcmMs: Long,
    ): Tier {
        if (!supportsSharedAudioSource(sdkInt)) return Tier.REMOTE_ONLY_NO_API
        if (sawPartial) return Tier.LOCAL_PARTIALS_AND_REMOTE
        if (wroteNonZeroPcm && elapsedSincePcmMs >= FIRST_PARTIAL_TIMEOUT_MS) {
            return Tier.REMOTE_ONLY_AFTER_TIMEOUT
        }
        // Still inside the grace window, or nothing has been spoken yet.
        return Tier.LOCAL_PARTIALS_AND_REMOTE
    }

    /**
     * True when every sample in `buffer[0, length)` is exactly zero.
     *
     * 🔴 This is the detector for Android's concurrent-capture policy, which
     * is enforced by **silently feeding zeros**: `startRecording()` succeeds,
     * `read()` returns the requested number of bytes, and every one of them
     * is 0. Two ordinary apps can never capture audio at the same time, and
     * this is the only way the loser finds out.
     *
     * *Exact* zeros, not a threshold: real microphone silence has a noise
     * floor and essentially never produces a run of bit-exact zeros, so an
     * amplitude threshold here would confuse a quiet room with a muted
     * stream. That distinction is the whole point.
     */
    fun isAllZero(buffer: ByteArray, length: Int): Boolean {
        val end = minOf(length, buffer.size)
        for (i in 0 until end) {
            if (buffer[i] != 0.toByte()) return false
        }
        return true
    }

    /**
     * Root-mean-square amplitude of 16-bit little-endian PCM, normalised to
     * `[0, 1]`, for the on-screen level meter.
     *
     * The meter matters more than it looks: in the remote-only tiers no
     * partial text ever appears, so it is the *only* feedback that the mic is
     * live. "Nothing on screen is moving" would otherwise be
     * indistinguishable from a dead capture.
     */
    fun rms(buffer: ByteArray, length: Int): Double {
        val samples = minOf(length, buffer.size) / BYTES_PER_SAMPLE
        if (samples <= 0) return 0.0
        var sumSquares = 0.0
        for (i in 0 until samples) {
            val lo = buffer[i * 2].toInt() and 0xFF
            val hi = buffer[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo
            sumSquares += sample.toDouble() * sample.toDouble()
        }
        val rms = Math.sqrt(sumSquares / samples)
        return (rms / Short.MAX_VALUE).coerceIn(0.0, 1.0)
    }

    /** Seconds of audio in `byteCount` bytes at [SAMPLE_RATE_HZ] mono 16-bit. */
    fun secondsFor(byteCount: Long, sampleRateHz: Int = SAMPLE_RATE_HZ): Double =
        byteCount.toDouble() / (sampleRateHz * CHANNEL_COUNT * BYTES_PER_SAMPLE)

    /**
     * Picks the encoder for the upload.
     *
     * Opus in an Ogg container is the first choice (~3 kB/s at 24 kbps mono);
     * AAC-LC in MPEG-4 is the fallback when Opus cannot be initialised. If
     * the chosen encoder rejects 16 kHz, capture at 48 kHz and let the server
     * resample — the alternative, resampling on the phone, is work we would
     * be doing to send *more* bytes.
     *
     * @param opusRates the rates `MediaCodecList` reports for `audio/opus`,
     *   or null when there is no Opus encoder at all.
     * @param aacRates likewise for AAC-LC.
     */
    fun pickEncoder(opusRates: IntArray?, aacRates: IntArray?): EncoderChoice? {
        opusRates?.let { return choose(MIME_OPUS, OPUS_BITRATE, it) }
        aacRates?.let { return choose(MIME_AAC, AAC_BITRATE, it) }
        return null
    }

    private fun choose(mime: String, bitrate: Int, rates: IntArray): EncoderChoice {
        val rate = if (rates.isEmpty() || rates.contains(SAMPLE_RATE_HZ)) {
            // An empty list means the codec did not enumerate discrete rates
            // (it advertises a range); 16 kHz is inside every such range we
            // care about, so try it rather than inflating to 48 kHz.
            SAMPLE_RATE_HZ
        } else {
            FALLBACK_SAMPLE_RATE_HZ
        }
        return EncoderChoice(mime = mime, sampleRateHz = rate, bitrate = bitrate)
    }

    data class EncoderChoice(
        val mime: String,
        val sampleRateHz: Int,
        val bitrate: Int,
    ) {
        val isOpus: Boolean get() = mime == MIME_OPUS
    }
}
