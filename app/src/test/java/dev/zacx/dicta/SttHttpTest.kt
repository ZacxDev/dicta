package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * What actually goes on the wire, captured through the injected connection
 * opener.
 *
 * **Invariant guards.** These assert the request the client builds and the
 * [SttOutcome] it derives from each response — the two halves the policy
 * tests take as given. Without them, [SttRetryPolicyTest] is a correct policy
 * over outcomes nothing proves are produced correctly.
 */
class SttHttpTest {

    /** Captures everything set on the connection. */
    private class FakeConnection(
        url: String,
        private val status: Int,
        private val body: String,
        private val throwOnConnect: IOException? = null,
    ) : HttpURLConnection(URL(url)) {

        val headers = mutableMapOf<String, String>()
        val sent = ByteArrayOutputStream()
        var fixedLength: Int = -1
        var disconnected = false
        val openedUrl: String = url

        override fun setRequestProperty(key: String, value: String?) {
            headers[key] = value.orEmpty()
        }

        override fun setFixedLengthStreamingMode(contentLength: Int) {
            fixedLength = contentLength
        }

        override fun getOutputStream(): OutputStream {
            throwOnConnect?.let { throw it }
            return sent
        }

        override fun getResponseCode(): Int {
            throwOnConnect?.let { throw it }
            return status
        }

        override fun getInputStream(): InputStream {
            if (status >= 400) throw IOException("no input stream on an error")
            return ByteArrayInputStream(body.toByteArray())
        }

        override fun getErrorStream(): InputStream? =
            if (status >= 400) ByteArrayInputStream(body.toByteArray()) else null

        override fun disconnect() { disconnected = true }
        override fun usingProxy(): Boolean = false
        override fun connect() { throwOnConnect?.let { throw it } }
    }

    private val realisticToken =
        "9f2c41be7a05d83c6e1b4faa2d907c5538be61470cd92fa8b3e5071c6da4982f"

    private fun attempt(
        language: String = "de",
        audioSeconds: Double = 11.0,
    ) = TranscriptionCoordinator.UploadAttempt(
        audio = byteArrayOf(0x4F, 0x67, 0x67, 0x53, 0x7A),
        audioSeconds = audioSeconds,
        language = language,
        attemptNumber = 1,
    )

    private fun post(
        status: Int,
        body: String,
        baseUrl: String = "http://10.42.0.10:8118",
        token: String = "tok-ABC-123",
        language: String = "de",
        audioSeconds: Double = 11.0,
    ): Pair<SttOutcome, FakeConnection> {
        var captured: FakeConnection? = null
        val outcome = SttHttp(baseUrl, token) { url ->
            FakeConnection(url, status, body).also { captured = it }
        }.transcribe(attempt(language, audioSeconds))
        return outcome to captured!!
    }

    // --- the request ---------------------------------------------------------

    @Test
    fun theUploadGoesToTheTranscriptionsEndpoint() {
        val (_, conn) = post(200, """{"text":"hi"}""")
        assertEquals(
            "http://10.42.0.10:8118/v1/audio/transcriptions",
            conn.openedUrl,
        )
    }

    @Test
    fun theUploadIsAPostWithTheBearerHeader() {
        val (_, conn) = post(200, """{"text":"hi"}""", token = realisticToken)
        assertEquals("POST", conn.requestMethod)
        assertEquals("Bearer $realisticToken", conn.headers["Authorization"])
    }

    @Test
    fun theContentTypeIsMultipartWithTheBodysOwnBoundary() {
        val (_, conn) = post(200, """{"text":"hi"}""")
        val contentType = conn.headers["Content-Type"]!!
        assertTrue(
            "Content-Type must be multipart/form-data, got: $contentType",
            contentType.startsWith("multipart/form-data; boundary="),
        )
        val boundary = contentType.substringAfter("boundary=")
        val body = conn.sent.toByteArray().toString(Charsets.ISO_8859_1)
        assertTrue(
            "the header's boundary must be the one used in the body",
            body.startsWith("--$boundary\r\n"),
        )
        assertTrue(body.endsWith("--$boundary--\r\n"))
    }

    @Test
    fun theBodyCarriesTheThreeNamedFields() {
        val (_, conn) = post(200, """{"text":"hi"}""")
        val body = conn.sent.toByteArray().toString(Charsets.ISO_8859_1)
        assertTrue(body.contains("name=\"file\""))
        assertTrue(body.contains("name=\"language\""))
        assertTrue(body.contains("name=\"punctuation\""))
        assertTrue(body.contains("filename=\"dictation.ogg\""))
    }

    @Test
    fun theLanguageOnTheWireIsTheBareCode() {
        // 🔴 "de", never "de-DE". A BCP-47 tag here is an unhandled 500.
        val (_, conn) = post(200, """{"text":"hi"}""", language = "de")
        val body = conn.sent.toByteArray().toString(Charsets.ISO_8859_1)
        assertTrue(body.contains("name=\"language\"\r\n\r\nde\r\n"))
        assertFalse(body.contains("de-DE"))
        assertFalse(body.contains("de_DE"))
    }

    @Test
    fun theTokenNeverAppearsInTheRequestBody() {
        // It belongs in the header and nowhere else.
        val (_, conn) = post(200, """{"text":"hi"}""", token = realisticToken)
        val body = conn.sent.toByteArray().toString(Charsets.ISO_8859_1)
        assertFalse(
            "the bearer token leaked into the multipart body",
            body.contains(realisticToken),
        )
    }

    @Test
    fun theRequestDeclaresAFixedContentLengthMatchingTheBody() {
        // 🔴 Fixed-length rather than chunked, so the platform client cannot
        // silently re-send the request on a dropped connection — that would
        // be an upload the budget never authorised.
        val (_, conn) = post(200, """{"text":"hi"}""")
        assertEquals(conn.sent.toByteArray().size, conn.fixedLength)
        assertTrue(conn.fixedLength > 0)
    }

    @Test
    fun theTimeoutsComeFromThePolicy() {
        val (_, conn) = post(200, """{"text":"hi"}""", audioSeconds = 17.0)
        assertEquals(10_000, conn.connectTimeout)
        // max(20s, 2 x 17s) = 34s, inside the 90s cap.
        assertEquals(34_000, conn.readTimeout)
    }

    @Test
    fun theConnectionIsAlwaysDisconnected() {
        val (_, conn) = post(200, """{"text":"hi"}""")
        assertTrue("a leaked connection holds a socket", conn.disconnected)
    }

    // --- the responses -------------------------------------------------------

    @Test
    fun a200YieldsTheTranscript() {
        val (outcome, _) = post(
            200,
            """{"text":"the quick brown fox","duration_s":5.4,"elapsed_s":1.9}""",
        )
        assertEquals(SttOutcome.Success("the quick brown fox"), outcome)
    }

    @Test
    fun a200WithNoTextFieldIsNotTreatedAsSuccess() {
        // Treating it as one would put an empty transcript on screen and
        // report done.
        val (outcome, _) = post(200, """{"duration_s":5.4}""")
        assertTrue(outcome is SttOutcome.Http)
        assertEquals(200, (outcome as SttOutcome.Http).status)
        assertEquals(Disposition.SUCCEEDED, SttRetryPolicy.classify(outcome))
    }

    // --- the truncation signal ----------------------------------------------
    //
    // 🔴 The whole seam, not either side of it: the body goes in as bytes and
    // what comes out is the three-valued flag on SttOutcome.Success. A
    // boolField unit test (SttRequestTest) plus a status-selection test
    // (RemoteTranscriberTest) can both be green while nothing ever wires the
    // field into the outcome.

    @Test
    fun a200WithTruncatedTrueCarriesTheFlagOnTheOutcome() {
        val (outcome, _) = post(
            200,
            """{"text":"a sentence that stops mid","duration_s":9.37,""" +
                """"elapsed_s":2.14,"finish_reason":"length","truncated":true}""",
        )
        assertEquals(
            "the server said the transcript is CUT OFF and the outcome does " +
                "not carry it — nothing downstream can tell the user",
            SttOutcome.Success("a sentence that stops mid", true),
            outcome,
        )
    }

    @Test
    fun a200WithTruncatedFalseCarriesFalseRatherThanNull() {
        val (outcome, _) = post(
            200,
            """{"text":"a whole sentence","duration_s":4.81,"elapsed_s":1.33,""" +
                """"finish_reason":"stop","truncated":false}""",
        )
        assertEquals(
            SttOutcome.Success("a whole sentence", false),
            outcome,
        )
    }

    @Test
    fun a200WithTruncatedNullIsNoSignalAndNotFalse() {
        // 🔴 The server sends null when it had no EOS id to look for. "The
        // server could not tell" and "the server checked and it is whole" are
        // different claims, and only one of them may ever be shown as such.
        val (outcome, _) = post(
            200,
            """{"text":"a whole sentence","duration_s":2.46,"elapsed_s":0.9,""" +
                """"finish_reason":"unknown","truncated":null}""",
        )
        assertEquals(
            "truncated:null was read as a decision rather than as an absence",
            SttOutcome.Success("a whole sentence", null),
            outcome,
        )
        assertNull((outcome as SttOutcome.Success).truncated)
    }

    @Test
    fun aPreChangeBodyWithNoTruncatedFieldIsNoSignal() {
        // The server before this field existed, and any response that drops
        // it. Must not read as false.
        val (outcome, _) = post(200, """{"text":"the quick brown fox","duration_s":5.4}""")
        assertNull((outcome as SttOutcome.Success).truncated)
    }

    @Test
    fun a401YieldsTheServersOwnDetail() {
        // The measured body from a no-token POST.
        val (outcome, _) = post(401, """{"detail":"invalid or missing bearer token"}""")
        assertEquals(
            SttOutcome.Http(401, "invalid or missing bearer token"),
            outcome,
        )
        assertEquals(Disposition.FAIL_FAST, SttRetryPolicy.classify(outcome))
    }

    @Test
    fun a503FromTheServerCarriesTheDetailThatDecidesTheDisposition() {
        val (transient, _) = post(503, """{"detail":"model not loaded yet"}""")
        assertEquals(Disposition.RETRY, SttRetryPolicy.classify(transient))

        val (permanent, _) = post(
            503, """{"detail":"server has no STT_API_TOKEN configured"}"""
        )
        assertEquals(Disposition.PERMANENT, SttRetryPolicy.classify(permanent))
    }

    @Test
    fun a503FromAProxyWithAnHtmlBodyHasANullDetailAndStaysTransient() {
        // 🔴 During a pod roll the readiness probe drops every endpoint and
        // this is what the client actually sees — not the app's JSON.
        val (outcome, _) = post(
            503,
            "<html><head><title>503</title></head><body>Service Unavailable</body></html>",
        )
        assertEquals(SttOutcome.Http(503, null), outcome)
        assertEquals(Disposition.RETRY, SttRetryPolicy.classify(outcome))
    }

    @Test
    fun anEmptyBodyOn502AlsoStaysTransient() {
        val (outcome, _) = post(502, "")
        assertEquals(SttOutcome.Http(502, null), outcome)
        assertEquals(Disposition.RETRY, SttRetryPolicy.classify(outcome))
    }

    @Test
    fun a413YieldsFailFast() {
        val (outcome, _) = post(413, """{"detail":"audio longer than 3600s"}""")
        assertEquals(Disposition.FAIL_FAST, SttRetryPolicy.classify(outcome))
    }

    @Test
    fun aConnectFailureBecomesANetworkOutcomeRatherThanAThrow() {
        val outcome = SttHttp("http://10.42.0.10:8118", "tok") { url ->
            FakeConnection(url, 200, "", IOException("Connection refused"))
        }.transcribe(attempt())

        assertEquals(SttOutcome.Network("Connection refused"), outcome)
        assertEquals(Disposition.RETRY, SttRetryPolicy.classify(outcome))
    }

    @Test
    fun aNetworkReasonNeverContainsTheToken() {
        // The reason string is surfaced to the user, so this is a real leak
        // path. The exception message is used, never its toString().
        val outcome = SttHttp("http://10.42.0.10:8118", realisticToken) { url ->
            FakeConnection(url, 200, "", IOException("timeout to $url"))
        }.transcribe(attempt())

        val reason = (outcome as SttOutcome.Network).reason
        assertFalse(
            "the token leaked into a user-facing failure reason",
            reason.contains(realisticToken),
        )
    }

    // --- /healthz ------------------------------------------------------------

    @Test
    fun healthzIsAGetToTheHealthPathWithNoAuthorizationHeader() {
        // 🔴 The endpoint is unauthenticated, and it is the probe used
        // precisely when the token might be the problem. Attaching one would
        // make a token fault look like an outage.
        var captured: FakeConnection? = null
        val ok = SttHttp("http://10.42.0.10:8118", realisticToken) { url ->
            FakeConnection(url, 200, """{"ok":true,"device":"cuda"}""")
                .also { captured = it }
        }.healthz()

        assertTrue(ok)
        val conn = captured!!
        assertEquals("http://10.42.0.10:8118/healthz", conn.openedUrl)
        assertEquals("GET", conn.requestMethod)
        assertNull(
            "/healthz must be sent WITHOUT an Authorization header",
            conn.headers["Authorization"],
        )
        assertTrue(conn.headers.values.none { it.contains(realisticToken) })
    }

    @Test
    fun aNon200HealthzIsFalse() {
        val result = SttHttp("http://10.42.0.10:8118", "t") { url ->
            FakeConnection(url, 503, """{"ok":false}""")
        }.healthz()
        assertFalse(result)
    }

    @Test
    fun anUnreachableHealthzIsFalseRatherThanAThrow() {
        val result = SttHttp("http://10.42.0.10:8118", "t") { url ->
            FakeConnection(url, 200, "", IOException("No route to host"))
        }.healthz()
        assertFalse(result)
    }
}
