package dev.zacx.dicta

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * **The** microphone client. One [AudioRecord], teed three ways.
 *
 * ## Why exactly one
 *
 * 🔴 `AudioRecord` and `SpeechRecognizer` cannot both capture. Android
 * enforces that by **silently feeding the loser exact zeros** — no exception,
 * no error callback, a successful `startRecording()` and `read()` calls that
 * return the full byte count. So the design is not "prefer one client", it is
 * "there is one client, and the recogniser is fed from it".
 *
 * ## The three tees
 *
 *  1. **The recogniser's pipe** — the read end goes out in the intent via
 *     `EXTRA_AUDIO_SOURCE` (see [RecognizerIntents]). Writes are
 *     non-blocking: a bounded queue with a dedicated writer thread that
 *     *drops* when full, because a recogniser that stops draining must slow
 *     down the on-device transcript, never the recording we are going to
 *     upload.
 *  2. **The encoder** — [AudioEncoderSink], producing the file we POST.
 *  3. **RMS** — the level meter, which is the only "it is listening" signal
 *     the user gets in the remote-only tiers.
 *
 * ## Source choice
 *
 * `VOICE_RECOGNITION`, because the CDD requires AGC **and** noise reduction
 * to be off by default for it — the service's model wants the rawest signal
 * available. Explicitly **not** `VOICE_COMMUNICATION` or `CAMCORDER` (both
 * privacy-sensitive and both apply processing we do not want), and
 * [AudioRecord.Builder.setPrivacySensitive] is deliberately **not** called:
 * marking the capture privacy-sensitive only makes concurrent sharing
 * strictly worse, which is the opposite of what this class needs.
 */
class MicCapture(
    private val listener: Listener,
    private val outputFile: File,
) {

    interface Listener {
        /** Normalised `[0, 1]` level, on the capture thread. */
        fun onLevel(level: Double)

        /**
         * 🔴 The capture is being fed zeros by the platform rather than real
         * audio — surfaced as a real UI state, not logged and swallowed.
         * Either another app won arbitration, or the user's mic is muted at
         * the OS level. Without this the user sees a dead level meter and no
         * transcript and has nothing to act on.
         */
        fun onClientSilenced(silenced: Boolean)

        /** First non-zero PCM frame: starts the partial-result watchdog. */
        fun onFirstAudio()

        /** Capture stopped and the file is ready (or null if nothing usable). */
        fun onFinished(file: File?, audioSeconds: Double)

        /** Capture could not start at all. */
        fun onError(message: String)
    }

    private val running = AtomicBoolean(false)
    private val sawNonZero = AtomicBoolean(false)
    private val firstAudioAtMs = AtomicLong(0L)

    private var record: AudioRecord? = null
    private var sink: AudioEncoderSink? = null
    private var captureThread: Thread? = null

    private var pipeRead: ParcelFileDescriptor? = null
    private var pipeWrite: ParcelFileDescriptor? = null
    private var pipeWriter: Thread? = null
    private val pipeQueue = ArrayBlockingQueue<ByteArray>(PIPE_QUEUE_CAPACITY)
    private val pipeDropped = AtomicLong(0L)

    @Volatile private var recordingCallback: Any? = null
    @Volatile var isClientSilenced: Boolean = false
        private set

    /** Milliseconds since the first non-zero frame, or 0 if there wasn't one. */
    val millisSinceFirstAudio: Long
        get() {
            val at = firstAudioAtMs.get()
            return if (at == 0L) 0L else System.currentTimeMillis() - at
        }

    val hasNonZeroAudio: Boolean get() = sawNonZero.get()

    /** PCM frames dropped rather than blocking the recogniser pipe. */
    val droppedPipeChunks: Long get() = pipeDropped.get()

    /**
     * The read end to hand to the recogniser, or null when the shared-source
     * path is unavailable. Valid only after [start].
     */
    val recognizerSource: ParcelFileDescriptor? get() = pipeRead

    @SuppressLint("MissingPermission") // caller holds RECORD_AUDIO; checked there
    fun start(): Boolean {
        if (running.get()) return true
        val choice = AudioEncoderSink.chooseEncoder()
        if (choice == null) {
            listener.onError("no audio encoder on this device")
            return false
        }
        val encoderSink = AudioEncoderSink.createBest(outputFile)
        if (encoderSink == null) {
            listener.onError("audio encoder failed to start")
            return false
        }
        sink = encoderSink

        val minBuffer = AudioRecord.getMinBufferSize(
            AudioCaptureLogic.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            listener.onError("microphone format unsupported")
            return false
        }
        val bufferBytes = minBuffer * BUFFER_MULTIPLIER

        val recorder = try {
            AudioRecord.Builder()
                // VOICE_RECOGNITION: CDD requires AGC and noise reduction off
                // by default. NOT VOICE_COMMUNICATION / CAMCORDER.
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(AudioCaptureLogic.SAMPLE_RATE_HZ)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferBytes)
                // setPrivacySensitive(true) is deliberately NOT set: it makes
                // concurrent sharing strictly worse.
                .build()
        } catch (e: Exception) {
            listener.onError(e.message ?: "microphone unavailable")
            return false
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            listener.onError("microphone could not be initialised")
            return false
        }
        record = recorder

        // 🔴 Registered BEFORE startRecording(). Registering afterwards races
        // the very first arbitration decision — the one case the callback
        // exists to report — and an app that loses the mic at t=0 would never
        // hear about it.
        registerRecordingCallback(recorder)

        openRecognizerPipe()

        return try {
            recorder.startRecording()
            running.set(true)
            captureThread = Thread({ captureLoop(recorder, bufferBytes) }, "dicta-mic")
                .apply { priority = Thread.MAX_PRIORITY; start() }
            true
        } catch (e: Exception) {
            listener.onError(e.message ?: "could not start recording")
            stop()
            false
        }
    }

    /**
     * Stops capture, closes the recogniser pipe and finalises the file.
     *
     * 🔴 Closing the **write** end is what ends the recogniser's session:
     * fed from a pipe it does not endpoint on trailing silence, it reads to
     * EOF ([RecognizerIntents.endpointingIsOurs]). A stop path that forgets
     * this leaves the recogniser waiting forever and no final result arrives.
     */
    fun stop() {
        if (!running.compareAndSet(true, false)) {
            // Still release anything half-built from a failed start().
            closePipe()
            record?.let { runCatching { it.release() } }
            record = null
            return
        }
        try {
            captureThread?.join(THREAD_JOIN_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        captureThread = null

        record?.let { r ->
            runCatching { r.stop() }
            unregisterRecordingCallback(r)
            runCatching { r.release() }
        }
        record = null

        closePipe()

        val finished = sink?.finish()
        val seconds = sink?.audioSeconds ?: 0.0
        sink = null
        listener.onFinished(finished, seconds)
    }

    // --- the capture loop ---------------------------------------------------

    private fun captureLoop(recorder: AudioRecord, bufferBytes: Int) {
        val buffer = ByteArray(bufferBytes)
        while (running.get()) {
            val read = try {
                recorder.read(buffer, 0, buffer.size)
            } catch (_: IllegalStateException) {
                break
            }
            if (read <= 0) {
                if (read == AudioRecord.ERROR_INVALID_OPERATION) break
                continue
            }

            // Tee 1: the policy tell. Exact zeros, not a threshold — real mic
            // silence has a noise floor and essentially never produces
            // bit-exact zeros, so a threshold here would confuse a quiet room
            // with a muted stream.
            val allZero = AudioCaptureLogic.isAllZero(buffer, read)
            if (!allZero && sawNonZero.compareAndSet(false, true)) {
                firstAudioAtMs.set(System.currentTimeMillis())
                listener.onFirstAudio()
            }

            // Tee 2: the encoder — the bytes we are going to upload.
            sink?.write(buffer, read)

            // Tee 3: the recogniser's pipe, via the dropping queue.
            offerToPipe(buffer, read)

            // Tee 4: the level meter.
            listener.onLevel(AudioCaptureLogic.rms(buffer, read))
        }
    }

    // --- the recogniser pipe ------------------------------------------------

    private fun openRecognizerPipe() {
        if (!AudioCaptureLogic.supportsSharedAudioSource(Build.VERSION.SDK_INT)) {
            // Tier 3: no EXTRA_AUDIO_SOURCE on this platform. No pipe, and
            // the recogniser is simply not started — see DictateActivity.
            return
        }
        try {
            val pair = ParcelFileDescriptor.createPipe()
            pipeRead = pair[0]
            pipeWrite = pair[1]
            val out = FileOutputStream(pair[1].fileDescriptor)
            pipeWriter = Thread({ pipeWriteLoop(out) }, "dicta-pipe").apply { start() }
        } catch (_: Exception) {
            closePipe()
        }
    }

    /**
     * Drains the queue into the pipe on its own thread.
     *
     * The write itself blocks when the pipe's kernel buffer fills; that is
     * fine *here* because this thread does nothing else. The non-blocking
     * guarantee the capture loop needs comes from [offerToPipe]'s bounded
     * queue, not from the write.
     */
    private fun pipeWriteLoop(out: FileOutputStream) {
        try {
            while (running.get() || pipeQueue.isNotEmpty()) {
                val chunk = pipeQueue.poll(
                    PIPE_POLL_MS, java.util.concurrent.TimeUnit.MILLISECONDS
                ) ?: continue
                out.write(chunk)
            }
            out.flush()
        } catch (_: Exception) {
            // Recogniser went away, or the pipe broke. The recording and the
            // upload are unaffected, which is the whole point of the tee.
        } finally {
            runCatching { out.close() }
        }
    }

    /** Copies and enqueues, dropping rather than blocking the capture loop. */
    private fun offerToPipe(buffer: ByteArray, length: Int) {
        if (pipeWrite == null) return
        val copy = buffer.copyOf(length)
        if (!pipeQueue.offer(copy)) pipeDropped.incrementAndGet()
    }

    private fun closePipe() {
        // Order matters: stop the writer, then close the write end. Closing
        // the write end is the EOF the recogniser is waiting for.
        pipeWriter?.let { t ->
            runCatching { t.join(THREAD_JOIN_MS) }
        }
        pipeWriter = null
        pipeWrite?.let { runCatching { it.close() } }
        pipeWrite = null
        // The read end was handed to the recogniser, which owns its copy; we
        // close ours so the descriptor is not leaked if it never started.
        pipeRead?.let { runCatching { it.close() } }
        pipeRead = null
        pipeQueue.clear()
    }

    // --- the recording callback ---------------------------------------------

    private fun registerRecordingCallback(recorder: AudioRecord) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val cb = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(
                    configs: MutableList<android.media.AudioRecordingConfiguration>?
                ) {
                    val mine = configs?.firstOrNull {
                        it.clientAudioSessionId == recorder.audioSessionId
                    } ?: return
                    val silenced = mine.isClientSilenced
                    if (silenced != isClientSilenced) {
                        isClientSilenced = silenced
                        listener.onClientSilenced(silenced)
                    }
                }
            }
            recordingCallback = cb
            recorder.registerAudioRecordingCallback({ it.run() }, cb)
        } catch (_: Exception) {
            recordingCallback = null
        }
    }

    private fun unregisterRecordingCallback(recorder: AudioRecord) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        (recordingCallback as? AudioManager.AudioRecordingCallback)?.let {
            runCatching { recorder.unregisterAudioRecordingCallback(it) }
        }
        recordingCallback = null
    }

    companion object {
        /**
         * Capacity of the recogniser-pipe queue, in chunks. Generous enough
         * to absorb a recogniser GC pause, small enough that a wedged
         * recogniser cannot grow the heap without bound.
         */
        private const val PIPE_QUEUE_CAPACITY = 64
        private const val PIPE_POLL_MS = 100L
        private const val BUFFER_MULTIPLIER = 2
        private const val THREAD_JOIN_MS = 2_000L
    }
}
