package dev.zacx.dicta

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * **Does the user's capture still exist after the flush?** — asked of the real
 * objects, end to end.
 *
 * ## 🔴 Why this file exists when four others already cover its parts
 *
 * `ClipHttpTest` pins what each status maps to. `ClipFlusherTest` pins what
 * each outcome does to the queue. `ClipOutboxStoreTest` pins the file. Every
 * one of them is scoped to ONE surface with the other two faked — and the
 * capture-loss defect lived in none of them. It lived in the **composition**:
 * a status classified as `Refused` by a client nobody doubted, handed to a
 * flusher whose drop rule nobody doubted, over a store that did exactly what it
 * was told. Each component was right about its own contract and the system
 * destroyed the operator's data.
 *
 * So this file fakes **only the connection opener** — the one thing that cannot
 * be a socket in a unit test. The [ClipOutboxStore] is real and on disk, the
 * [ClipFlusher] is real, the [ClipHttp] is real, and the question asked is the
 * operator's question rather than any component's: after a flush that met this
 * status, **is the text still there**.
 *
 * ## 🔴 The defect, and the matrix this was watched to produce
 *
 * `ClipSettings.baseUrl` was free text saved with no validation, four digits
 * from the transcription service's own port. Pointed at it, a live
 * `POST http://10.42.0.10:8118/v1/clips` answers **404** — which
 * `ClipHttp.PERMANENT_STATUSES` held, so [ClipFlusher] dropped the row and the
 * outbox was the only copy. One capture destroyed per flush, per minute, with
 * the capture toast promising *"it will sync when Dicta is on the mesh"* and
 * `ClipOutboxHealth` **dark**, because `dropped` had no consumer anywhere in
 * the tree and a drop reset the fault counter to zero.
 *
 * At `d3efcd0` the 404 and 413 cases below are RED and the 401 case is green.
 *
 * ## The controls, which are what make a green here mean anything
 *
 *  * **401 keeps the capture** — the case that was always correct. If it ever
 *    goes red the fix has over-reached and `Refused` is dead.
 *  * **A 201 drains the queue** — the positive control. Every other assertion
 *    in this file is "the row is still there", and a probe wired to a store
 *    that cannot remove anything satisfies all of them while observing
 *    nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipCaptureSurvivalTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val base = "http://10.42.0.10:8139"

    /** Realistic in SHAPE only. Not a credential, and not the operator's. */
    private val fixtureToken =
        "4f1b9e07a35c82d6e94173bf0c5a8d27e61934ba0f7d5c8e23a6049bd1f7e35c"

    @Before
    fun setUp() {
        ClipOutboxHealth.clearForTest()
    }

    @After
    fun tearDown() {
        ClipOutboxHealth.clearForTest()
    }

    /** `ClipHttpTest`'s fake, which is `SttHttpTest`'s. Deliberately not a variant. */
    private class FakeConnection(
        url: String,
        private val status: Int,
        private val body: String,
    ) : HttpURLConnection(URL(url)) {

        val sent = ByteArrayOutputStream()

        override fun setRequestProperty(key: String, value: String?) = Unit
        override fun setFixedLengthStreamingMode(contentLength: Int) = Unit
        override fun getOutputStream(): OutputStream = sent
        override fun getResponseCode(): Int = status
        override fun getInputStream(): InputStream {
            if (status >= 400) throw java.io.IOException("no input stream on an error")
            return ByteArrayInputStream(body.toByteArray())
        }

        override fun getErrorStream(): InputStream? =
            if (status >= 400) ByteArrayInputStream(body.toByteArray()) else null

        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun connect() = Unit
    }

    /** Opened connections, so a case can prove the POST was really attempted. */
    private val opened = mutableListOf<String>()

    /**
     * The whole production stack over a temp dir, with one capture already in
     * it, answering every POST with [status].
     */
    private fun stackWith(
        body: String,
        status: Int,
        responseBody: String = """{"error":"x"}""",
    ): Pair<ClipOutboxStore, ClipFlusher> {
        val outbox = ClipOutboxStore(folder.root)
        outbox.append(body)
        val http = ClipHttp(
            baseUrl = base,
            token = fixtureToken,
            opener = { url ->
                opened += url
                FakeConnection(url, status, responseBody)
            },
        )
        val flusher = ClipFlusher(
            outbox = outbox,
            device = "pixel-7",
            post = { b, d -> http.post(b, d) },
        )
        return outbox to flusher
    }

    private fun bodies(outbox: ClipOutboxStore): List<String> =
        outbox.pending().map { it.body }

    // --- the positive control, first -----------------------------------------

    @Test
    fun aSTOREDCaptureREALLYLeavesTheQueue() {
        // 🔴 POSITIVE CONTROL for every other case in this file. They all
        // assert "the capture is still there", and a probe over a store whose
        // `remove` did nothing — or a flusher that never posted — satisfies
        // all of them while observing nothing at all. This is the case that
        // proves the apparatus can see a row leave.
        val text = "the thing I copied"
        val (outbox, flusher) = stackWith(text, status = 201, responseBody = """{"id":7}""")
        assertEquals(listOf(text), bodies(outbox))

        val report = flusher.flush()

        assertEquals(
            "the queue did not drain on a 201, so every 'the capture " +
                "survived' assertion in this file is vacuous",
            emptyList<String>(),
            bodies(outbox),
        )
        assertEquals(1, report.stored)
        assertEquals(0, report.dropped)
        assertEquals(
            "no connection was opened, so no POST was attempted",
            1,
            opened.size,
        )
        assertEquals("$base/v1/clips", opened.single())
    }

    // --- the defect ----------------------------------------------------------

    @Test
    fun aFOURZEROFOURFromAMistypedBaseUrlDoesNotDESTROYTheCapture() {
        // 🔴 RED AT `d3efcd0`. The measured scenario: the operator pastes the
        // transcription base URL into the clip store field, so every POST
        // answers 404, and 404 was in PERMANENT_STATUSES.
        val text = "a password I copied out of the manager"
        val (outbox, flusher) = stackWith(
            text,
            status = 404,
            responseBody = """{"error":"not_found"}""",
        )

        val report = flusher.flush()

        assertEquals(
            "🔴 THE CAPTURE WAS DESTROYED. A 404 means the clip store URL in " +
                "Settings is wrong — the identical bytes succeed the moment " +
                "it is corrected — so the row must WAIT, not be deleted from " +
                "the only place it exists.",
            listOf(text),
            bodies(outbox),
        )
        assertEquals("the pass reported a destroyed capture", 0, report.dropped)
        assertTrue("a 404 did not report the store as unreachable", report.unreachable)
        assertEquals(1, report.kept)
    }

    @Test
    fun aFOURONETHREEOnALargeSelectionDoesNotDESTROYIt() {
        // 🔴 RED AT `d3efcd0`. PROCESS_TEXT delivers up to 100,000 characters,
        // so a 413 can be an entirely legitimate selection meeting a limit the
        // operator can raise on the server. The body here is well inside
        // ClipOutboxStore.MAX_BODY_CHARS, which is the point: this is a
        // capture the app accepted and the deployment did not.
        val text = "x".repeat(50_000)
        val (outbox, flusher) = stackWith(
            text,
            status = 413,
            responseBody = """{"error":"too_large"}""",
        )

        val report = flusher.flush()

        assertEquals(
            "🔴 THE CAPTURE WAS DESTROYED. A 413 is the deployment's limit, " +
                "not a property of the bytes, and the selection may be a " +
                "legitimate 100,000-character one.",
            listOf(text),
            bodies(outbox),
        )
        assertEquals(0, report.dropped)
    }

    // --- the control that was always correct ---------------------------------

    @Test
    fun aFOURZEROONEStillKeepsTheCapture() {
        // 🔴 CONTROL. This case was correct before the fix and must stay
        // correct after it: if it goes red, `Refused` has been widened or the
        // flusher's drop arm has been deleted outright, and this file would
        // otherwise be green for the wrong reason.
        val text = "something I copied while the token was wrong"
        val (outbox, flusher) = stackWith(
            text,
            status = 401,
            responseBody = """{"error":"unauthorized"}""",
        )

        val report = flusher.flush()

        assertEquals(listOf(text), bodies(outbox))
        assertEquals(0, report.dropped)
        assertTrue(report.unreachable)
    }

    // --- the indicator -------------------------------------------------------

    @Test
    fun aRunOfCaptureDESTROYINGFlushesLightsTheIndicator() {
        // 🔴 RED AT `d3efcd0`, and the inversion is the finding: ten
        // capture-destroying flushes left `isStuck()` FALSE while ten
        // capture-KEEPING 401 flushes lit it. The indicator was dark for the
        // destructive case and lit for the harmless one.
        //
        // Driven through the REAL stack rather than by handing Reports to
        // ClipOutboxHealth, because the fact under test is that a drop
        // reaches the indicator at all — which is exactly the hop that did not
        // exist: `dropped` had no consumer anywhere in the tree.
        //
        // 400 rather than 404, because 404 is now Unreachable: a 400 is a
        // genuine body fault and is still, correctly, a drop. The point is
        // that a drop is now VISIBLE, not that drops no longer happen.
        val outbox = ClipOutboxStore(folder.root)
        val http = ClipHttp(
            baseUrl = base,
            token = fixtureToken,
            opener = { url ->
                opened += url
                FakeConnection(url, 400, """{"error":"unparseable"}""")
            },
        )
        repeat(10) { i ->
            outbox.append("capture number $i")
            val report = ClipFlusher(
                outbox = outbox,
                device = "pixel-7",
                post = { b, d -> http.post(b, d) },
            ).flush()
            ClipOutboxHealth.record(report)
        }

        assertEquals(
            "ten flushes destroyed ten captures and left no trace anywhere " +
                "the operator can see",
            emptyList<String>(),
            bodies(outbox),
        )
        assertEquals(
            "🔴 ten capture-DESTROYING flushes left the shared-clipboard " +
                "indicator dark, while ten capture-KEEPING 401 flushes light " +
                "it. The destructive case had LESS visibility than nothing.",
            ClipOutboxHealth.State.DESTROYED,
            ClipOutboxHealth.state(),
        )
    }

    @Test
    fun aRunOfFOURZEROFOURFlushesNowLightsTheSTUCKIndicatorInstead() {
        // The other half of the fix, end to end: with 404 reclassified, the
        // same scenario that used to delete a capture a minute now keeps every
        // one of them AND reports the queue stuck — which is the state it is
        // actually in, and which names the right remedy ("check the clip
        // token / URL in Settings") rather than silence.
        val outbox = ClipOutboxStore(folder.root)
        val http = ClipHttp(
            baseUrl = base,
            token = fixtureToken,
            opener = { url -> FakeConnection(url, 404, """{"error":"not_found"}""") },
        )
        repeat(10) { i ->
            outbox.append("capture number $i")
            ClipOutboxHealth.record(
                ClipFlusher(
                    outbox = outbox,
                    device = "pixel-7",
                    post = { b, d -> http.post(b, d) },
                ).flush()
            )
        }

        assertEquals(
            "a run of 404s lost captures",
            10,
            outbox.pending().size,
        )
        assertEquals(
            ClipOutboxHealth.State.STUCK,
            ClipOutboxHealth.state(),
        )
    }
}
