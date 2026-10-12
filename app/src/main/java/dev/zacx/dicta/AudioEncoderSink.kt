package dev.zacx.dicta

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import java.io.File
import java.nio.ByteBuffer

/**
 * Compresses the captured PCM into a container the service can decode, and
 * writes it to a file.
 *
 * The service decodes with **ffmpeg** and resamples to 16 kHz mono itself, so
 * any container ffmpeg reads is acceptable and there is no transcoding to do
 * on the phone beyond compression. Opus at 24 kbps mono is about 3 kB/s — 15
 * seconds of dictation is roughly 45 kB, which is what makes the upload cheap
 * enough to retry at all.
 *
 * Not unit-tested: `MediaCodec` and `MediaMuxer` have no Robolectric
 * implementation. The *selection* logic is in
 * [AudioCaptureLogic.pickEncoder] and is tested; this class is the platform
 * plumbing around it and is device-verified.
 */
class AudioEncoderSink private constructor(
    private val codec: MediaCodec,
    private val muxer: MediaMuxer,
    private val choice: AudioCaptureLogic.EncoderChoice,
    val outputFile: File,
) {

    private var trackIndex = -1
    private var muxerStarted = false
    private var presentationTimeUs = 0L
    private val bufferInfo = MediaCodec.BufferInfo()
    @Volatile private var closed = false

    /** Total PCM bytes accepted, for the duration estimate. */
    @Volatile var pcmBytesWritten: Long = 0L
        private set

    val audioSeconds: Double
        get() = AudioCaptureLogic.secondsFor(pcmBytesWritten, choice.sampleRateHz)

    /** Feeds one PCM chunk. Drains whatever the codec has ready. */
    fun write(pcm: ByteArray, length: Int) {
        if (closed) return
        var offset = 0
        while (offset < length) {
            val inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (inIndex < 0) {
                // No input buffer available. Drain and try again; if the
                // codec is genuinely wedged the next write will find space.
                drain(endOfStream = false)
                return
            }
            val buf = codec.getInputBuffer(inIndex) ?: return
            buf.clear()
            val n = minOf(buf.remaining(), length - offset)
            buf.put(pcm, offset, n)
            codec.queueInputBuffer(inIndex, 0, n, presentationTimeUs, 0)
            offset += n
            pcmBytesWritten += n
            presentationTimeUs += usFor(n)
            drain(endOfStream = false)
        }
    }

    /**
     * Signals end of stream, flushes the muxer and releases everything.
     *
     * Idempotent: the capture teardown path can be reached both from a normal
     * stop and from `onDestroy`, and a double-release on `MediaCodec` throws.
     */
    fun finish(): File? {
        if (closed) return outputFile.takeIf { it.length() > 0 }
        closed = true
        return try {
            val inIndex = codec.dequeueInputBuffer(END_OF_STREAM_TIMEOUT_US)
            if (inIndex >= 0) {
                codec.queueInputBuffer(
                    inIndex, 0, 0, presentationTimeUs,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
            }
            drain(endOfStream = true)
            outputFile.takeIf { it.length() > 0 }
        } catch (_: IllegalStateException) {
            null
        } finally {
            release()
        }
    }

    private fun drain(endOfStream: Boolean) {
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    // At end of stream keep spinning until the codec reports
                    // BUFFER_FLAG_END_OF_STREAM, otherwise the tail of the
                    // recording is silently truncated.
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                }
                outIndex >= 0 -> {
                    val encoded: ByteBuffer? = codec.getOutputBuffer(outIndex)
                    val isConfig =
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (encoded != null && bufferInfo.size > 0 && !isConfig &&
                        muxerStarted
                    ) {
                        encoded.position(bufferInfo.offset)
                        encoded.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encoded, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    ) {
                        return
                    }
                }
                else -> return
            }
        }
    }

    private fun release() {
        try {
            codec.stop()
        } catch (_: IllegalStateException) {
        }
        try {
            codec.release()
        } catch (_: IllegalStateException) {
        }
        try {
            if (muxerStarted) muxer.stop()
        } catch (_: IllegalStateException) {
        }
        try {
            muxer.release()
        } catch (_: IllegalStateException) {
        }
    }

    private fun usFor(byteCount: Int): Long =
        (byteCount.toLong() * 1_000_000L) /
            (choice.sampleRateHz.toLong() * AudioCaptureLogic.CHANNEL_COUNT *
                AudioCaptureLogic.BYTES_PER_SAMPLE)

    companion object {
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val END_OF_STREAM_TIMEOUT_US = 100_000L

        /**
         * Probes `MediaCodecList` for the rates each candidate encoder
         * supports and hands the answer to
         * [AudioCaptureLogic.pickEncoder].
         */
        fun chooseEncoder(): AudioCaptureLogic.EncoderChoice? =
            AudioCaptureLogic.pickEncoder(
                opusRates = supportedRates(AudioCaptureLogic.MIME_OPUS),
                aacRates = supportedRates(AudioCaptureLogic.MIME_AAC),
            )

        /**
         * @return the discrete sample rates the encoder advertises, an empty
         *   array when it advertises a continuous range instead, or null when
         *   there is no encoder for [mime] at all.
         */
        private fun supportedRates(mime: String): IntArray? {
            return try {
                val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                val info = list.codecInfos.firstOrNull { codecInfo ->
                    codecInfo.isEncoder &&
                        codecInfo.supportedTypes.any {
                            it.equals(mime, ignoreCase = true)
                        }
                } ?: return null
                info.getCapabilitiesForType(mime).audioCapabilities
                    ?.supportedSampleRates ?: IntArray(0)
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: RuntimeException) {
                null
            }
        }

        /**
         * Builds a sink, or null when neither encoder can be started.
         *
         * Ogg needs `MUXER_OUTPUT_OGG`, which is API 29+; `minSdk` is 26, so
         * on 26-28 an Opus choice has no container and falls through to
         * AAC/MPEG-4.
         */
        fun create(
            outputFile: File,
            choice: AudioCaptureLogic.EncoderChoice,
        ): AudioEncoderSink? {
            val useOgg = choice.isOpus &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            val effective = if (choice.isOpus && !useOgg) {
                AudioCaptureLogic.EncoderChoice(
                    AudioCaptureLogic.MIME_AAC,
                    choice.sampleRateHz,
                    AudioCaptureLogic.AAC_BITRATE,
                )
            } else {
                choice
            }
            return try {
                val format = MediaFormat.createAudioFormat(
                    effective.mime,
                    effective.sampleRateHz,
                    AudioCaptureLogic.CHANNEL_COUNT,
                ).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, effective.bitrate)
                    if (effective.mime == AudioCaptureLogic.MIME_AAC) {
                        setInteger(
                            MediaFormat.KEY_AAC_PROFILE,
                            MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                        )
                    }
                }
                val codec = MediaCodec.createEncoderByType(effective.mime)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                val container = if (effective.mime == AudioCaptureLogic.MIME_OPUS) {
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
                } else {
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                }
                val muxer = MediaMuxer(outputFile.absolutePath, container)
                AudioEncoderSink(codec, muxer, effective, outputFile)
            } catch (_: Exception) {
                // Any failure here is "this encoder is unavailable on this
                // device", which the caller handles by trying the fallback.
                null
            }
        }

        /** Opus first, AAC second, null when neither starts. */
        fun createBest(outputFile: File): AudioEncoderSink? {
            val first = chooseEncoder() ?: return null
            create(outputFile, first)?.let { return it }
            if (first.isOpus) {
                return create(
                    outputFile,
                    AudioCaptureLogic.EncoderChoice(
                        AudioCaptureLogic.MIME_AAC,
                        first.sampleRateHz,
                        AudioCaptureLogic.AAC_BITRATE,
                    )
                )
            }
            return null
        }

        /** The container extension for a choice, for the upload's filename. */
        fun extensionFor(choice: AudioCaptureLogic.EncoderChoice): String =
            if (choice.isOpus) "ogg" else "m4a"
    }
}
