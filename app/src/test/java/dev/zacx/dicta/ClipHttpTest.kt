package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * What the clip client puts on the wire, and what it concludes from each answer.
 *
 * 🔴 **Robolectric, because the client reads bodies through [ClipJson] and so
 * through `org.json`.** [SttHttpTest] needs no runner because it parses with
 * `JsonLite`; this one does, and without it every 2xx would come back
 * `Unreachable("200 with no \"id\"")` — a stuck queue that reads like an outage.
 *
 * 🔴 **Invariant guards, not regression coverage.** [ClipHttp] is a new file.
 * The connection harness is [SttHttpTest]'s, deliberately — a second,
 * subtly-different fake would make the two clients' tests incomparable.
 *
 * 🔴 **No token appears in this file.** `ZacxDev/dicta` is PUBLIC. The fixture
 * is a random hex string of the right shape, and the operator's real token was
 * never written down anywhere in this repository.
 *
 * ## 🔴 The classification is the whole point
 *
 * `Refused` is DROPPED from the outbox and `Unreachable` is KEPT and retried
 * forever, so every status's bucket is a decision about whether a capture
 * survives. Each one below was chosen against the live service's measured
 * behaviour on 2026-11-15, not from a table of HTTP semantics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipHttpTest {

    /** Realistic in SHAPE only. Not a credential, and not the operator's. */
    private val fixtureToken =
        "0c7a4e13b8d95f26a0e4713cd58b2f69904ae7d3b1c6582fdae03947b6512cf8"

    private val base = "http://10.42.0.10:8139"

    /** [SttHttpTest]'s fake, unchanged. */
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
        var statusReads = 0

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
            statusReads++
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

    private var opened: FakeConnection? = null

    private fun client(
        status: Int = 201,
        body: String = """{"id":5,"deduped":false}""",
        throwOnConnect: IOException? = null,
        limit: Int = ClipHttp.DEFAULT_LIMIT,
    ) = ClipHttp(
        baseUrl = base,
        token = fixtureToken,
        limit = limit,
        opener = { url ->
            FakeConnection(url, status, body, throwOnConnect).also { opened = it }
        },
    )

    // --- the request ---------------------------------------------------------

    @Test
    fun aPostCarriesTheJsonBodyTheAuthHeaderAndItsOwnLength() {
        client().post("copied text", "pixel-7")
        val conn = requireNotNull(opened)
        assertEquals("$base/v1/clips", conn.openedUrl)
        assertEquals("POST", conn.requestMethod)
        assertEquals(
            """{"body":"copied text","device":"pixel-7"}""",
            conn.sent.toString(Charsets.UTF_8.name()),
        )
        // 🔴 The header, byte for byte: the service compares the remainder
        // against its configured token, so a re-cased scheme is a 401 that
        // reads like a bad token.
        assertEquals(
            "Bearer 0c7a4e13b8d95f26a0e4713cd58b2f69904ae7d3b1c6582fdae03947b6512cf8",
            conn.headers["Authorization"],
        )
        assertEquals("application/json", conn.headers["Content-Type"])
        // 🔴 Fixed-length rather than chunked. SttHttp's own comment records
        // why: chunked transfer-encoding through the mesh relay is the shape
        // that has failed before, and a clipboard body is small enough to size
        // up front.
        assertEquals(
            """{"body":"copied text","device":"pixel-7"}""".toByteArray().size,
            conn.fixedLength,
        )
    }

    @Test
    fun aListRequestIsAGetThatAsksForTheConfiguredLimit() {
        client(status = 200, body = """{"clips":[],"limit":12}""", limit = 12).rows()
        val conn = requireNotNull(opened)
        assertEquals("$base/v1/clips?limit=12", conn.openedUrl)
        assertEquals("GET", conn.requestMethod)
        assertFalse("a GET must not open an output stream", conn.doOutput)
    }

    @Test
    fun theDefaultLimitIsTheServersOwnDefaultRatherThanAnInventedNumber() {
        // Measured: the service answers `"limit":100` when asked for no limit,
        // so asking for 100 explicitly changes nothing about the response and
        // keeps the number visible in one place on this side.
        assertEquals(100, ClipHttp.DEFAULT_LIMIT)
        client(status = 200, body = """{"clips":[],"limit":100}""").rows()
        assertEquals("$base/v1/clips?limit=100", requireNotNull(opened).openedUrl)
    }

    @Test
    fun aDeleteAddressesTheRowByIdAndSendsNoBody() {
        client(status = 204, body = "").delete(41L)
        val conn = requireNotNull(opened)
        assertEquals("$base/v1/clips/41", conn.openedUrl)
        assertEquals("DELETE", conn.requestMethod)
        assertEquals(-1, conn.fixedLength)
    }

    @Test
    fun everyRequestDisconnectsEvenWhenItFailed() {
        // The mesh tunnel has a finite number of sockets and the flush runs on
        // a timer; a leaked connection per attempt is an outage on a schedule.
        client(status = 201).post("x", "d")
        assertTrue("a successful POST leaked its connection", requireNotNull(opened).disconnected)
        client(status = 200, body = """{"clips":[]}""").rows()
        assertTrue("a successful GET leaked its connection", requireNotNull(opened).disconnected)
        client(throwOnConnect = IOException("ECONNREFUSED")).post("x", "d")
        assertTrue("a refused POST leaked its connection", requireNotNull(opened).disconnected)
        client(status = 500, body = "").rows()
        assertTrue("a failed GET leaked its connection", requireNotNull(opened).disconnected)
    }

    // --- what each answer MEANS ---------------------------------------------

    @Test
    fun theLive201AndTheLive200DedupeAreBothStored() {
        // Both bodies are the live service's, measured. 201 is a new clip; an
        // identical body answers 200 with deduped:true and the SAME id.
        //
        // 🔴 The two outcomes are now EQUAL, and that is the point rather than a
        // weakening. `ClipPost.Stored` carried the server's `deduped` verdict
        // until this branch's audit round 0 (D4) and nothing ever read it, so
        // what is asserted is what the client actually acts on: the store holds
        // the body, therefore the local row leaves the queue — identically in
        // both cases. Both fixtures still carry `deduped` and `bump_count`
        // deliberately, because the server sends them and parsing must tolerate
        // keys the client ignores.
        assertEquals(
            ClipPost.Stored(id = 5L),
            client(
                status = 201,
                body = """{"id":5,"body":"x","device":"d","created_at":1794779793374,"bump_count":0,"deduped":false}""",
            ).post("x", "d"),
        )
        assertEquals(
            ClipPost.Stored(id = 5L),
            client(
                status = 200,
                body = """{"id":5,"body":"x","device":"d","created_at":1794779793667,"bump_count":1,"deduped":true}""",
            ).post("x", "d"),
        )
    }

    @Test
    fun aTwoHundredWithNoIdIsUNREACHABLESoTheCaptureIsKept() {
        // 🔴 The one that matters most. A proxy or a gateway can answer 200
        // with an HTML body during a pod roll, and treating that as stored
        // would delete the only copy of the user's capture on the strength of a
        // response that contains no clip.
        val outcome = client(status = 200, body = "<html>200 but not from the app</html>")
            .post("x", "d")
        assertTrue("a 200 with no clip in it was treated as stored: $outcome",
            outcome is ClipPost.Unreachable)
    }

    @Test
    fun theLiveFourHundredForAnEmptyBodyIsREFUSEDWithTheServicesOwnReason() {
        // Measured live, verbatim. 400 is permanent: retrying it forever would
        // block every item behind it, which is the only way a single bad row
        // can cost the user every later capture.
        assertEquals(
            ClipPost.Refused(400, "`body` is required and must not be empty"),
            client(
                status = 400,
                body = """{"error":"empty_body","detail":"`body` is required and must not be empty"}""",
            ).post("", "d"),
        )
    }

    @Test
    fun aMissingTokenIsUNREACHABLEAndNOTRefused() {
        // 🔴 401/403 are deliberately NOT in PERMANENT_STATUSES, and this is
        // the decision with the largest blast radius in the whole feature. A
        // token typo, an un-pasted token, or a rotated token on the server
        // would otherwise DELETE every queued capture one flush at a time —
        // silently, because dropping is how the flusher avoids a blocked queue.
        // Keeping them means the queue waits for the operator to fix the
        // setting, which is exactly what the condition is.
        for (status in listOf(401, 403)) {
            val outcome = client(status = status, body = """{"error":"unauthorized"}""")
                .post("x", "d")
            assertTrue(
                "HTTP $status dropped the user's capture: $outcome",
                outcome is ClipPost.Unreachable,
            )
        }
        assertFalse(401 in ClipHttp.PERMANENT_STATUSES)
        assertFalse(403 in ClipHttp.PERMANENT_STATUSES)
    }

    @Test
    fun aRateLimitOrAGatewayFailureIsUNREACHABLEAndRetried() {
        // 429 and 5xx are the service asking to be asked again. 408 likewise.
        for (status in listOf(408, 429, 500, 502, 503, 504)) {
            val outcome = client(status = status, body = "").post("x", "d")
            assertTrue(
                "HTTP $status dropped the user's capture: $outcome",
                outcome is ClipPost.Unreachable,
            )
        }
    }

    @Test
    fun theOtherPermanentRefusalsAreTheOnesARETRYCannotFix() {
        // Each of these is a property of the REQUEST — a route that does not
        // exist, a method the route does not serve, a body the service will
        // never accept — so the same bytes will be refused identically forever.
        assertEquals(setOf(400, 404, 405, 413, 414, 415, 422), ClipHttp.PERMANENT_STATUSES)
        for (status in ClipHttp.PERMANENT_STATUSES) {
            val outcome = client(status = status, body = "").post("x", "d")
            assertTrue(
                "HTTP $status is listed as permanent but was retried: $outcome",
                outcome is ClipPost.Refused,
            )
            assertEquals(status, (outcome as ClipPost.Refused).status)
        }
    }

    @Test
    fun aRefusedCallWithNoReadableReasonStillSaysWhichStatus() {
        // The status is the only thing left to report, and a report with
        // nothing in it is what makes a dropped capture unexplainable.
        val outcome = client(status = 422, body = "<html>unprocessable</html>").post("x", "d")
        assertEquals(ClipPost.Refused(422, null), outcome)
    }

    @Test
    fun aRefusedConnectionIsUNREACHABLEAndCarriesTheOSsOwnMessage() {
        // Off the mesh — on mobile data, or with the tunnel down — this is
        // every attempt, so the reason has to be legible in a log.
        val outcome = client(throwOnConnect = IOException("Connection refused"))
            .post("x", "d")
        assertEquals(ClipPost.Unreachable("Connection refused"), outcome)
    }

    @Test
    fun aPostNEVERThrowsWhateverTheConnectionDoes() {
        // 🔴 The contract the flusher depends on: a throw escaping here would
        // propagate out of the flush and, on the picker's path, out of
        // readRows — turning an offline phone into an error dialog instead of
        // an empty state.
        val thrown = runCatching {
            ClipHttp(base, fixtureToken, opener = { throw IllegalStateException("boom") })
                .post("x", "d")
        }
        assertTrue("post threw: ${thrown.exceptionOrNull()}", thrown.isSuccess)
        assertTrue(thrown.getOrNull() is ClipPost.Unreachable)
    }

    // --- reading rows --------------------------------------------------------

    @Test
    fun theLiveListResponseComesBackAsRowsInTheServersOrder() {
        val rows = client(
            status = 200,
            body = """{"clips":[{"id":7,"body":"newer","device":"a","created_at":20,"bump_count":0},{"id":3,"body":"older","device":"b","created_at":10,"bump_count":1}],"limit":100}""",
        ).rows()
        assertEquals(listOf("newer", "older"), rows?.map { it.body })
    }

    @Test
    fun aDrainedStoreIsAnEmptyListAndAFailedReadIsNULL() {
        // 🔴 THE DISTINCTION THE PICKER'S THREE EMPTY STATES REST ON. Collapse
        // these and an operator whose mesh is down is told their shared
        // clipboard is empty — advice to copy something, for a store that is
        // full.
        assertEquals(
            emptyList<Clip>(),
            client(status = 200, body = """{"clips":[],"limit":100}""").rows(),
        )
        assertNull("a 500 was reported as an empty store",
            client(status = 500, body = "").rows())
        assertNull("a 401 was reported as an empty store",
            client(status = 401, body = """{"error":"unauthorized"}""").rows())
        assertNull("a refused connection was reported as an empty store",
            client(throwOnConnect = IOException("ETIMEDOUT")).rows())
    }

    @Test
    fun aMalformedBodyIsNULLAndNotAnEmptyStoreOrAThrow() {
        // 🔴 ClipJson.Malformed extends Exception, NOT RuntimeException — so
        // this case does not fall into the `catch (RuntimeException)` clause
        // and would escape the client entirely if it were not caught by name.
        // The first draft of this file had the clause in the wrong order and
        // this test is what the ordering is for.
        for (body in listOf("<html>502 Bad Gateway</html>", """[{"id":1}]""", "")) {
            val got = runCatching {
                client(status = 200, body = body).rows()
            }
            assertTrue("rows() threw on <$body>: ${got.exceptionOrNull()}", got.isSuccess)
            assertNull("a malformed body read as a store state: <$body>", got.getOrNull())
        }
    }

    // --- deleting ------------------------------------------------------------

    @Test
    fun aDeleteSucceedsOnTheLive204AndAlsoOnA404() {
        // Both measured live: the first DELETE of an id answers 204, a second
        // answers 404 `no_such_clip`. 🔴 404 counts as success because the
        // caller's intent — "this row should not exist" — is satisfied, and the
        // alternative is a UI that reports a failure for a row another device
        // already deleted.
        assertTrue(client(status = 204, body = "").delete(5L))
        assertTrue(
            client(status = 404, body = """{"error":"no_such_clip","detail":"no clip with id 5"}""")
                .delete(5L)
        )
    }

    @Test
    fun aDeleteFailsOnEverythingElseAndNeverThrows() {
        for (status in listOf(401, 403, 405, 500, 502)) {
            assertFalse("HTTP $status reported a successful delete",
                client(status = status, body = "").delete(5L))
        }
        val thrown = runCatching {
            ClipHttp(base, fixtureToken, opener = { throw IllegalStateException("boom") })
                .delete(5L)
        }
        assertTrue("delete threw: ${thrown.exceptionOrNull()}", thrown.isSuccess)
        assertEquals(false, thrown.getOrNull())
    }
}
