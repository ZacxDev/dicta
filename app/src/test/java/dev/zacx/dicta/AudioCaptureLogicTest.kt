package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capture layer's arithmetic and branching.
 *
 * **Invariant guards.** `AudioRecord`, `MediaCodec` and `SpeechRecognizer`
 * have no usable Robolectric implementations, so the *platform* behaviour of
 * [MicCapture] and [AudioEncoderSink] is device evidence only. What is pinned
 * here is the logic those classes delegate to — the zero detector that tells
 * a muted stream from a quiet room, the tier decision, and the encoder
 * choice.
 */
class AudioCaptureLogicTest {

    // --- 🔴 the silent-feed detector ---------------------------------------

    @Test
    fun anAllZeroBufferIsDetected() {
        // 🔴 This is how Android reports that we lost microphone arbitration:
        // startRecording() succeeds, read() returns the full byte count, and
        // every byte is 0. There is no exception and no callback.
        assertTrue(AudioCaptureLogic.isAllZero(ByteArray(640), 640))
    }

    @Test
    fun aSingleNonZeroByteAnywhereMeansRealAudio() {
        // Checked at the start, the middle and the end: an implementation
        // that only samples the head would pass a head-only check.
        val head = ByteArray(640).also { it[0] = 1 }
        val middle = ByteArray(640).also { it[321] = -5 }
        val tail = ByteArray(640).also { it[639] = 7 }
        assertFalse(AudioCaptureLogic.isAllZero(head, 640))
        assertFalse(AudioCaptureLogic.isAllZero(middle, 640))
        assertFalse(AudioCaptureLogic.isAllZero(tail, 640))
    }

    @Test
    fun theDetectorRespectsTheReadLengthAndNotTheBufferSize() {
        // A short read leaves stale bytes in the tail of the buffer. Scanning
        // the whole array would call a genuinely-silenced read "real audio"
        // because of data from a previous iteration.
        val buffer = ByteArray(640)
        buffer[500] = 99        // stale, beyond the read length
        assertTrue(
            "bytes past the read length must be ignored",
            AudioCaptureLogic.isAllZero(buffer, 400),
        )
        assertFalse(
            "bytes inside the read length must be honoured",
            AudioCaptureLogic.isAllZero(buffer, 501),
        )
    }

    @Test
    fun aQuietRoomIsNotAllZero() {
        // The distinction the whole detector exists for. A real noise floor
        // is small but never bit-exact zero, so an amplitude *threshold* here
        // would confuse a quiet room with a muted stream.
        val noiseFloor = ByteArray(640) { i -> if (i % 7 == 0) 1 else 0 }
        assertFalse(AudioCaptureLogic.isAllZero(noiseFloor, 640))
    }

    // --- RMS ----------------------------------------------------------------

    @Test
    fun silenceHasZeroLevel() {
        assertEquals(0.0, AudioCaptureLogic.rms(ByteArray(64), 64), 1e-9)
    }

    @Test
    fun aFullScaleToneIsNearOne() {
        // 0x7FFF repeated, little-endian.
        val buffer = ByteArray(64)
        for (i in 0 until 32) {
            buffer[i * 2] = 0xFF.toByte()
            buffer[i * 2 + 1] = 0x7F
        }
        assertEquals(1.0, AudioCaptureLogic.rms(buffer, 64), 0.001)
    }

    @Test
    fun theLevelMovesWithAmplitude() {
        // Three distinct amplitudes, chosen so none equals another and none
        // equals 0 or Short.MAX_VALUE: a constant-returning implementation
        // cannot pass.
        fun bufferAt(amplitude: Int): ByteArray {
            val b = ByteArray(64)
            for (i in 0 until 32) {
                b[i * 2] = (amplitude and 0xFF).toByte()
                b[i * 2 + 1] = ((amplitude shr 8) and 0xFF).toByte()
            }
            return b
        }
        val quiet = AudioCaptureLogic.rms(bufferAt(900), 64)
        val medium = AudioCaptureLogic.rms(bufferAt(7_100), 64)
        val loud = AudioCaptureLogic.rms(bufferAt(21_300), 64)
        assertTrue(
            "the level must increase with amplitude: $quiet / $medium / $loud",
            quiet < medium && medium < loud,
        )
        assertTrue("the level must stay normalised", loud <= 1.0)
    }

    @Test
    fun anEmptyOrOddLengthReadDoesNotThrow() {
        assertEquals(0.0, AudioCaptureLogic.rms(ByteArray(0), 0), 1e-9)
        // One trailing byte is half a sample and must be dropped, not read.
        assertEquals(0.0, AudioCaptureLogic.rms(ByteArray(1), 1), 1e-9)
    }

    // --- duration -----------------------------------------------------------

    @Test
    fun durationIsComputedFromTheByteCount() {
        // 16 kHz mono 16-bit = 32000 bytes/second. 48000 bytes = 1.5 s.
        assertEquals(1.5, AudioCaptureLogic.secondsFor(48_000), 1e-9)
        assertEquals(0.0, AudioCaptureLogic.secondsFor(0), 1e-9)
    }

    @Test
    fun durationHonoursAFallbackSampleRate() {
        // At 48 kHz the same byte count is a third of the duration. Pinned
        // because the read timeout is derived from this number, and getting
        // it 3x wrong would set a 3x wrong timeout.
        assertEquals(0.5, AudioCaptureLogic.secondsFor(48_000, 48_000), 1e-9)
    }

    // --- the three tiers ----------------------------------------------------

    @Test
    fun belowApi33ThereIsNoSharedAudioSource() {
        // minSdk is 26, so this is a real branch.
        assertFalse(AudioCaptureLogic.supportsSharedAudioSource(26))
        assertFalse(AudioCaptureLogic.supportsSharedAudioSource(32))
        assertTrue(AudioCaptureLogic.supportsSharedAudioSource(33))
        assertTrue(AudioCaptureLogic.supportsSharedAudioSource(35))
    }

    @Test
    fun theApiFloorIs33() {
        assertEquals(33, AudioCaptureLogic.MIN_SDK_FOR_AUDIO_SOURCE)
    }

    @Test
    fun anOldPlatformLandsInTierThreeRegardlessOfAnythingElse() {
        assertEquals(
            AudioCaptureLogic.Tier.REMOTE_ONLY_NO_API,
            AudioCaptureLogic.tierFor(
                sdkInt = 30, sawPartial = true,
                wroteNonZeroPcm = true, elapsedSincePcmMs = 9_000,
            ),
        )
    }

    @Test
    fun aPartialMeansTierOne() {
        assertEquals(
            AudioCaptureLogic.Tier.LOCAL_PARTIALS_AND_REMOTE,
            AudioCaptureLogic.tierFor(
                sdkInt = 35, sawPartial = true,
                wroteNonZeroPcm = true, elapsedSincePcmMs = 9_000,
            ),
        )
    }

    @Test
    fun noPartialAfterTheTimeoutWhileRealAudioFlowsMeansTierTwo() {
        // 🔴 The runtime detection for "this recogniser silently ignored
        // EXTRA_AUDIO_SOURCE". There is no capability flag to query, so the
        // absence of partials while we are demonstrably writing non-zero PCM
        // is the only tell available.
        assertEquals(
            AudioCaptureLogic.Tier.REMOTE_ONLY_AFTER_TIMEOUT,
            AudioCaptureLogic.tierFor(
                sdkInt = 35, sawPartial = false,
                wroteNonZeroPcm = true, elapsedSincePcmMs = 1_500,
            ),
        )
        assertEquals(
            AudioCaptureLogic.Tier.REMOTE_ONLY_AFTER_TIMEOUT,
            AudioCaptureLogic.tierFor(
                sdkInt = 35, sawPartial = false,
                wroteNonZeroPcm = true, elapsedSincePcmMs = 4_000,
            ),
        )
    }

    @Test
    fun silenceIsNotMistakenForAnIgnoredExtra() {
        // 🔴 Load-bearing: without the wroteNonZeroPcm term, a user who takes
        // more than 1.5 s to start speaking would have recognition cancelled
        // every single time. That is most dictations.
        assertEquals(
            AudioCaptureLogic.Tier.LOCAL_PARTIALS_AND_REMOTE,
            AudioCaptureLogic.tierFor(
                sdkInt = 35, sawPartial = false,
                wroteNonZeroPcm = false, elapsedSincePcmMs = 30_000,
            ),
        )
    }

    @Test
    fun insideTheGraceWindowTheTierIsNotYetDowngraded() {
        // Just below the threshold. Chosen so it is not the boundary value
        // itself — the boundary is asserted separately above.
        assertEquals(
            AudioCaptureLogic.Tier.LOCAL_PARTIALS_AND_REMOTE,
            AudioCaptureLogic.tierFor(
                sdkInt = 35, sawPartial = false,
                wroteNonZeroPcm = true, elapsedSincePcmMs = 1_499,
            ),
        )
    }

    @Test
    fun theGraceWindowIsOnePointFiveSeconds() {
        assertEquals(1_500L, AudioCaptureLogic.FIRST_PARTIAL_TIMEOUT_MS)
    }

    @Test
    fun everyTierStillProducesARemoteTranscript() {
        // The property the brief states: the tiers differ in whether the
        // LOCAL engine contributes, never in whether the user gets text.
        // Asserted structurally — every tier name says remote, or says
        // remote-and-local.
        val tiers = AudioCaptureLogic.Tier.values().toList()
        assertEquals(
            "a tier was added or removed; re-check that it still yields a " +
                "transcript before changing this number",
            3,
            tiers.size,
        )
        assertTrue(
            tiers.containsAll(
                listOf(
                    AudioCaptureLogic.Tier.LOCAL_PARTIALS_AND_REMOTE,
                    AudioCaptureLogic.Tier.REMOTE_ONLY_AFTER_TIMEOUT,
                    AudioCaptureLogic.Tier.REMOTE_ONLY_NO_API,
                )
            )
        )
    }

    // --- the encoder choice -------------------------------------------------

    @Test
    fun opusIsPreferredWhenAvailable() {
        val choice = AudioCaptureLogic.pickEncoder(
            opusRates = intArrayOf(8_000, 16_000, 48_000),
            aacRates = intArrayOf(16_000, 44_100),
        )!!
        assertEquals("audio/opus", choice.mime)
        assertEquals(16_000, choice.sampleRateHz)
        assertEquals(24_000, choice.bitrate)
        assertTrue(choice.isOpus)
    }

    @Test
    fun aacIsTheFallbackWhenThereIsNoOpusEncoder() {
        val choice = AudioCaptureLogic.pickEncoder(
            opusRates = null,
            aacRates = intArrayOf(16_000, 44_100),
        )!!
        assertEquals("audio/mp4a-latm", choice.mime)
        assertEquals(16_000, choice.sampleRateHz)
        assertEquals(32_000, choice.bitrate)
        assertFalse(choice.isOpus)
    }

    @Test
    fun aRejectedSixteenKilohertzFallsBackToFortyEightAndLetsTheServerResample() {
        // Resampling on the phone would be work done in order to send MORE
        // bytes — the service resamples to 16 kHz mono internally regardless.
        val choice = AudioCaptureLogic.pickEncoder(
            opusRates = intArrayOf(48_000),
            aacRates = null,
        )!!
        assertEquals(48_000, choice.sampleRateHz)
        assertTrue(choice.isOpus)
    }

    @Test
    fun aContinuousRateRangeIsTreatedAsSupportingSixteenKilohertz() {
        // An empty discrete-rate list means the codec advertises a range.
        // Inflating to 48 kHz there would triple the upload for nothing.
        val choice = AudioCaptureLogic.pickEncoder(
            opusRates = intArrayOf(),
            aacRates = null,
        )!!
        assertEquals(16_000, choice.sampleRateHz)
    }

    @Test
    fun noEncoderAtAllIsNullRatherThanAGuess() {
        assertNull(AudioCaptureLogic.pickEncoder(opusRates = null, aacRates = null))
    }

    @Test
    fun theCaptureFormatIsSixteenKilohertzMonoSixteenBit() {
        // The recogniser's documented defaults, and what the explicit
        // EXTRA_AUDIO_SOURCE_* extras are set to.
        assertEquals(16_000, AudioCaptureLogic.SAMPLE_RATE_HZ)
        assertEquals(1, AudioCaptureLogic.CHANNEL_COUNT)
        assertEquals(2, AudioCaptureLogic.BYTES_PER_SAMPLE)
    }
}
