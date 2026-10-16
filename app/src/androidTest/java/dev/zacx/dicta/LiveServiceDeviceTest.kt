package dev.zacx.dicta

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The remote client, running **inside the real app process**, against the
 * **live transcription service**.
 *
 * ## What only this file can establish
 *
 * Three properties are invisible to the JVM suite, and all three are the kind
 * that fail silently:
 *
 *  * **`android.permission.INTERNET`** — its absence raises no dialog, no lint
 *    error and no build warning. Every socket just throws, which looks exactly
 *    like the service being down. Only a real app process can tell you.
 *  * **The scoped `networkSecurityConfig`** — cleartext to `10.42.0.10` is
 *    blocked by default on targetSdk 28+. The XML exemption is only applied
 *    by the runtime, from the manifest, in a real process.
 *  * **The service contract** — the endpoint path, the `Authorization` header
 *    shape, the multipart field names and the bare-ISO `language` value are
 *    all strings the client cannot check against the server at build time.
 *    [SttRequestTest] pins them to literals; this one pins them to the server.
 *
 * ## Running it
 *
 * The bearer token is **not** in the repository and **not** in the APK. Push
 * it to the device first:
 *
 * ```
 * printf '%s' "$STT_API_TOKEN" > /tmp/t && chmod 644 /tmp/t
 * adb push /tmp/t /data/local/tmp/dicta-stt-token && rm /tmp/t
 * gradle :app:connectedDebugAndroidTest
 * adb shell rm /data/local/tmp/dicta-stt-token
 * ```
 *
 * 🔴 If the token file is absent these tests **fail loudly** rather than
 * skipping themselves. A test that silently skips is worse than no test: it
 * reports green having measured nothing, and this is the only test that
 * measures the live contract.
 *
 * Never run in CI — there is no device and no service there.
 */
@RunWith(AndroidJUnit4::class)
class LiveServiceDeviceTest {

    /** The app under test — its cacheDir, its network config, its UID. */
    private val ctx: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * The **test** APK's context.
     *
     * `androidTest/assets` is packaged into `dev.zacx.dicta.test`, not into
     * the app APK, so the fixture is only reachable through this context.
     * Reading it from [ctx] throws `FileNotFoundException` — which is how the
     * first run of this file failed, and is worth a comment rather than a
     * silent fix, because the two contexts are otherwise interchangeable
     * enough that the mistake is easy to repeat.
     */
    private val testCtx: Context
        get() = InstrumentationRegistry.getInstrumentation().context

    private lateinit var token: String
    private val baseUrl = SttEndpoints.DEFAULT_BASE_URL

    /**
     * The exact transcript the service returns for
     * `assets/dictation-fixture.wav`, **measured** by POSTing that very file
     * and copying the response — not guessed, and not derived from anything
     * in this repository. Note the leading space: the service emits one, and
     * a test that trimmed it would be hiding a difference rather than
     * asserting a contract.
     */
    private val expectedFixtureTranscript =
        " The quick brown fox jumps over the lazy dog."

    @Before
    fun readToken() {
        val file = File("/data/local/tmp/dicta-stt-token")
        assertTrue(
            "no token at ${file.absolutePath} — these tests exercise the LIVE " +
                "service and cannot run without it. See this class's kdoc for " +
                "the two adb commands. Failing rather than skipping, because a " +
                "skip would report green having measured nothing.",
            file.exists() && file.length() > 0,
        )
        token = file.readText().trim()
        assertTrue("the token file is empty", token.isNotEmpty())
    }

    private fun fixtureBytes(): ByteArray =
        testCtx.resources.assets.open("dictation-fixture.wav").use { it.readBytes() }

    private fun attempt(
        audio: ByteArray,
        language: String = "en",
        seconds: Double = 3.65,
    ) = TranscriptionCoordinator.UploadAttempt(
        audio = audio, audioSeconds = seconds, language = language,
        attemptNumber = 1,
    )

    // --- reachability --------------------------------------------------------

    @Test
    fun theAppProcessCanReachTheServiceOverCleartextOnTheMesh() {
        // 🔴 This single assertion covers INTERNET *and* the scoped cleartext
        // exemption. If either is wrong it fails, and nothing else in the
        // suite would have noticed.
        assertTrue(
            "the app process could not reach $baseUrl/healthz. Either " +
                "android.permission.INTERNET is missing, or the " +
                "networkSecurityConfig does not exempt this host from " +
                "cleartext policy, or the mesh is down.",
            SttHttp(baseUrl, token = "").healthz(),
        )
    }

    @Test
    fun healthzNeedsNoTokenAndADeliberatelyWrongOneDoesNotBreakIt() {
        // The endpoint is unauthenticated. Both calls must succeed, which is
        // what makes it usable as a readiness probe when the token itself is
        // the suspect.
        assertTrue(SttHttp(baseUrl, token = "").healthz())
        assertTrue(SttHttp(baseUrl, token = "definitely-not-the-token").healthz())
    }

    // --- 🔴 the real transcription -------------------------------------------

    @Test
    fun aRealUploadThroughTheAppsOwnClientReturnsTheExpectedTranscript() {
        val outcome = SttHttp(baseUrl, token).transcribe(attempt(fixtureBytes()))

        assertTrue(
            "expected a 200 with a transcript, got: $outcome",
            outcome is SttOutcome.Success,
        )
        // Exact string equality against a MEASURED value. Not a `contains`,
        // not a trim: this is the client's whole reason to exist, and a
        // fuzzy assertion here would pass on a truncated or re-cased result.
        assertEquals(
            expectedFixtureTranscript,
            (outcome as SttOutcome.Success).text,
        )
        assertEquals(Disposition.SUCCEEDED, SttRetryPolicy.classify(outcome))

        // 🔴 The trust signals, against the LIVE contract. Every other
        // assertion about these two fields in this repository runs on a
        // hand-written fixture, so if the server renames a key, changes its
        // type or stops emitting it, the whole unit suite stays green and the
        // warning silently never fires again — the app degrades to exactly its
        // pre-change behaviour, unobservably. This is the only place that can
        // notice. MEASURED values, like the transcript above.
        assertEquals(
            "the service no longer reports unreliable=false for ordinary " +
                "speech. Either the key was renamed or its type changed — in " +
                "which case boolField now returns null for every response and " +
                "no fabrication will ever be flagged again — or the threshold " +
                "moved and real speech is now being flagged.",
            false,
            outcome.unreliable,
        )
        assertEquals(
            "the service no longer reports truncated=false for a transcript " +
                "that ends on EOS",
            false,
            outcome.truncated,
        )
    }

    /**
     * 🔴 The **positive** control for the fabrication signal, against the live
     * service: a 200 Hz tone contains no speech, and the service answers with
     * invented words.
     *
     * Without this, `unreliable` is only ever asserted to be `false` — and a
     * field that is hardwired to `false`, or that boolField reads as `null` on
     * every response, satisfies that perfectly. The pair is the evidence; the
     * negative half alone is indistinguishable from a signal wired to nothing.
     *
     * Measured against cohere-stt 0.1.12: a tone returns `unreliable=true`
     * with `unreliable_reasons=["no_speech_prob"]`, and the text is fabricated
     * (`" you"` was one observed output). The *text* is deliberately not
     * asserted — it is a hallucination and will vary — only that the server
     * flagged it and that the client carried the flag.
     */
    @Test
    fun aToneWithNoSpeechIsFlaggedUnreliableByTheServiceAndTheClientCarriesIt() {
        val outcome = SttHttp(baseUrl, token).transcribe(
            attempt(toneWavBytes(seconds = 1.0, hz = 200.0), seconds = 1.0)
        )

        assertTrue(
            "expected a 200 — the service refuses nothing and reports instead, " +
                "so a tone must still come back as a Success: $outcome",
            outcome is SttOutcome.Success,
        )
        assertEquals(
            "🔴 the service did NOT flag a pure tone as unreliable, so the " +
                "client's whole fabrication warning is wired to a signal that " +
                "never fires. Either the server-side threshold changed (it is " +
                "deliberately retunable and WILL move) or the field is no " +
                "longer being read correctly.",
            true,
            (outcome as SttOutcome.Success).unreliable,
        )
    }

    /**
     * A 16-bit mono PCM WAV of a sine tone, built in memory.
     *
     * The service identifies the container by content via ffmpeg (see
     * [SttHttp.AUDIO_FILE_NAME]'s kdoc), so a hand-written WAV header is
     * accepted the same way the `.wav` asset is. Synthesised rather than
     * shipped as a second asset because the waveform is three lines and an
     * asset is a binary blob nobody can review.
     */
    private fun toneWavBytes(seconds: Double, hz: Double, rate: Int = 16_000): ByteArray {
        val frames = (seconds * rate).toInt()
        val data = java.io.ByteArrayOutputStream()
        for (n in 0 until frames) {
            // 0.5 amplitude: loud enough that no reasonable level gate would
            // call it silence, which is the point — the service hallucinates
            // on it anyway.
            val v = (Math.sin(2.0 * Math.PI * hz * n / rate) * 16_000).toInt()
            data.write(v and 0xFF)
            data.write((v shr 8) and 0xFF)
        }
        val pcm = data.toByteArray()
        val out = java.io.ByteArrayOutputStream()
        fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun le32(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
        }
        fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        ascii("RIFF"); le32(36 + pcm.size); ascii("WAVE")
        ascii("fmt "); le32(16); le16(1); le16(1)
        le32(rate); le32(rate * 2); le16(2); le16(16)
        ascii("data"); le32(pcm.size); out.write(pcm)
        return out.toByteArray()
    }

    @Test
    fun theCoordinatorEndToEndYieldsTheRemoteTranscriptOnTheFirstUpload() {
        // The whole ladder with the REAL transport, so the budget, the
        // single-flight wrapper and the event reporting are all exercised
        // against the live service rather than a fake.
        val http = SttHttp(baseUrl, token)
        val serialised = SingleFlightUploader { http.transcribe(it) }
        val budget = UploadBudget()
        val remote = mutableListOf<String>()
        val fellBack = mutableListOf<String>()

        val result = TranscriptionCoordinator(
            transport = { serialised.upload(it) },
            health = { http.healthz() },
            sleeper = { Thread.sleep(it) },
            unitRandom = { 0.5 },
            elapsedMs = { System.currentTimeMillis() },
            events = object : TranscriptionCoordinator.Events {
                override fun onRemoteTranscript(
                    text: String,
                    truncated: Boolean?,
                    unreliable: Boolean?,
                ) {
                    remote += text
                }
                override fun onFellBack(reason: String, recoveryPending: Boolean) {
                    fellBack += reason
                }
                override fun onStatus(message: String) {}
            },
            budget = budget,
        ).run(fixtureBytes(), 3.65, SttLanguage.fromTag("en-US"))

        assertEquals(expectedFixtureTranscript, result)
        assertEquals(listOf(expectedFixtureTranscript), remote)
        assertTrue("no fallback should have occurred: $fellBack", fellBack.isEmpty())
        assertEquals("a healthy service must cost exactly one upload", 1,
            budget.uploadsSpent)
        assertEquals(1, serialised.maxObservedConcurrency)
        assertEquals(0, serialised.concurrencyViolations)
    }

    @Test
    fun theLanguageMappedFromTheDevicesOwnLocaleIsAccepted() {
        // 🔴 The documented 500, closed against the live server. The device
        // locale's toLanguageTag() is "en-US", which the service would pass
        // unvalidated to a processor that raises ValueError OUTSIDE its
        // try/except. Mapped through the allowlist it is "en" and succeeds.
        val mapped = SttLanguage.fromLocale(java.util.Locale.getDefault())
        assertTrue(
            "the device locale mapped to \"$mapped\", which is not in the " +
                "server's accepted set",
            mapped in SttLanguage.SUPPORTED,
        )
        val outcome = SttHttp(baseUrl, token)
            .transcribe(attempt(fixtureBytes(), language = mapped))
        assertTrue(
            "the mapped language \"$mapped\" was rejected: $outcome",
            outcome is SttOutcome.Success,
        )
    }

    // --- 🔴 the fallback trigger --------------------------------------------

    @Test
    fun aWrongTokenIs401AndClassifiesAsFailFastSoTheFallbackFires() {
        val outcome = SttHttp(baseUrl, "deadbeef-not-a-real-token")
            .transcribe(attempt(fixtureBytes()))

        assertTrue("expected an HTTP outcome, got: $outcome", outcome is SttOutcome.Http)
        val http = outcome as SttOutcome.Http
        assertEquals(401, http.status)
        // The service's own measured message.
        assertEquals("invalid or missing bearer token", http.detail)
        assertEquals(Disposition.FAIL_FAST, SttRetryPolicy.classify(outcome))
    }

    @Test
    fun aWrongTokenCostsExactlyOneUploadAndHandsOverToTheLocalEngine() {
        // The fallback path, driven by a real 401 from the real service.
        val http = SttHttp(baseUrl, "deadbeef-not-a-real-token")
        val budget = UploadBudget()
        val fellBack = mutableListOf<Pair<String, Boolean>>()
        val remote = mutableListOf<String>()

        val result = TranscriptionCoordinator(
            transport = { http.transcribe(it) },
            health = { http.healthz() },
            sleeper = { Thread.sleep(it) },
            unitRandom = { 0.5 },
            elapsedMs = { System.currentTimeMillis() },
            events = object : TranscriptionCoordinator.Events {
                override fun onRemoteTranscript(
                    text: String,
                    truncated: Boolean?,
                    unreliable: Boolean?,
                ) {
                    remote += text
                }
                override fun onFellBack(reason: String, recoveryPending: Boolean) {
                    fellBack += reason to recoveryPending
                }
                override fun onStatus(message: String) {}
            },
            budget = budget,
        ).run(fixtureBytes(), 3.65, "en")

        assertEquals("the remote path must produce nothing", null, result)
        assertTrue(remote.isEmpty())
        assertEquals("a 401 must cost exactly one upload", 1, budget.uploadsSpent)
        val (reason, pending) = fellBack.single()
        assertFalse("there is nothing to recover from a 401", pending)
        assertTrue(
            "the user must be pointed at Settings, got: $reason",
            reason.contains("Settings"),
        )
        assertFalse(
            "the bogus token must not appear in a user-facing reason",
            reason.contains("deadbeef"),
        )
    }

    @Test
    fun anUnreachableHostIsANetworkOutcomeAndRetryable() {
        // 192.0.2.0/24 is TEST-NET-1 and is guaranteed unroutable. Also a
        // control on the cleartext scoping: this host is NOT exempted, so the
        // failure may be either a network error or a policy refusal — both
        // are Network outcomes, and both must be retryable rather than
        // throwing out of the client.
        val outcome = SttHttp("http://192.0.2.123:8118", token)
            .transcribe(attempt(fixtureBytes(), seconds = 1.0))
        assertTrue("expected a Network outcome, got: $outcome",
            outcome is SttOutcome.Network)
        assertEquals(Disposition.RETRY, SttRetryPolicy.classify(outcome))
    }

    // --- 🔴 our own encoder's output must be decodable by the server ---------

    @Test
    fun theOpusOggFileOurEncoderProducesIsAcceptedByTheService() {
        // The integration risk `AudioCaptureLogicTest` cannot touch: whether
        // the MediaCodec + MediaMuxer output we upload is actually something
        // the service's ffmpeg can decode. A 400 "undecodable audio" here
        // would mean every real dictation fails while every unit test passes.
        val out = File(ctx.cacheDir, "encoder-probe-${System.nanoTime()}.ogg")
        val sink = AudioEncoderSink.createBest(out)
        assertTrue("no audio encoder could be started on this device", sink != null)

        // Feed the fixture's PCM: a 44-byte canonical WAV header then 16 kHz
        // mono 16-bit samples, which is exactly MicCapture's format.
        val wav = fixtureBytes()
        val pcm = wav.copyOfRange(44, wav.size)
        var offset = 0
        val chunk = 3200   // 100 ms
        while (offset < pcm.size) {
            val n = minOf(chunk, pcm.size - offset)
            sink!!.write(pcm.copyOfRange(offset, offset + n), n)
            offset += n
        }
        val encoded = sink!!.finish()
        assertTrue("the encoder produced no file", encoded != null)
        assertTrue("the encoder produced an empty file", encoded!!.length() > 0)

        val bytes = encoded.readBytes()
        // A real compression win, not a passthrough: ~24 kbps vs 256 kbps PCM.
        assertTrue(
            "the encoded file (${bytes.size} B) is not smaller than the PCM " +
                "(${pcm.size} B) — the encoder is not compressing",
            bytes.size < pcm.size,
        )

        val outcome = SttHttp(baseUrl, token).transcribe(
            attempt(bytes, seconds = AudioCaptureLogic.secondsFor(pcm.size.toLong()))
        )
        assertTrue(
            "the service could not decode our own encoder's output: $outcome",
            outcome is SttOutcome.Success,
        )
        // The transcript is not pinned to the exact fixture string here: this
        // is a lossy codec at 24 kbps and the claim under test is
        // decodability, not fidelity. But it must not be empty — a 200 with
        // no words would mean we uploaded silence.
        val text = (outcome as SttOutcome.Success).text.trim()
        assertTrue(
            "the service decoded our file but returned no words, so the " +
                "encoder is emitting silence",
            text.isNotEmpty(),
        )
        encoded.delete()
    }

    @Test
    fun theEncoderChoiceOnThisDeviceIsReported() {
        // Not an assertion about which codec "should" win — a measurement, so
        // the PR can state what this handset actually does instead of
        // assuming Opus.
        val choice = AudioEncoderSink.chooseEncoder()
        assertTrue("no encoder available at all", choice != null)
        println(
            "DICTA_ENCODER_CHOICE mime=${choice!!.mime} " +
                "rate=${choice.sampleRateHz} bitrate=${choice.bitrate}"
        )
        assertTrue(
            "the chosen sample rate must be one we capture at",
            choice.sampleRateHz == AudioCaptureLogic.SAMPLE_RATE_HZ ||
                choice.sampleRateHz == AudioCaptureLogic.FALLBACK_SAMPLE_RATE_HZ,
        )
    }

    @Test
    fun theSharedAudioSourceApiIsAvailableOnThisDevice() {
        // Reports which degradation tier this handset can even attempt.
        // 🔴 Availability of the API is NOT the same as the recogniser
        // honouring EXTRA_AUDIO_SOURCE — support is optional per
        // implementation and the fallback is silent. This measures only the
        // API floor; the tier is decided at runtime by MicCapture's watchdog.
        val sdk = android.os.Build.VERSION.SDK_INT
        println(
            "DICTA_SDK=$sdk sharedAudioSourceApiAvailable=" +
                AudioCaptureLogic.supportsSharedAudioSource(sdk)
        )
        assertEquals(
            AudioCaptureLogic.supportsSharedAudioSource(sdk),
            sdk >= 33,
        )
    }
}
