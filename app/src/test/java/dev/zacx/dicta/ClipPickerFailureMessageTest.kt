package dev.zacx.dicta

import android.widget.TextView
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 🔴 **THE RUNNABLE RED-AT-BASE REGRESSION TEST**, at the level a human meets:
 * the sentence the shared-clipboard picker puts on screen when it cannot read
 * the store.
 *
 * ## What was wrong, and why this file is written the way it is
 *
 * `ClipHttp` collapsed four failure classes into one `null` and this screen
 * rendered every one of them as *"Dicta could not reach the shared clipboard.
 * It is mesh-only, so this is usually being off-mesh"*. So the app told the
 * operator — and an agent driving it over adb — that the mesh was down while
 * the store was reachable and refusing the token.
 *
 * 🔴 This file is deliberately built from **[ClipHttp] with an injected
 * `opener`** rather than from a fake [SharedClipSource]. A fake source would
 * have been easier and would have been evidence about the fake; the defect
 * lived in the SEAM between the client's classification and the screen's copy,
 * and a fixture that replaces the client cannot see it.
 *
 * ## The red-at-base matrix, measured
 *
 * This file as a whole still cannot compile at `origin/main` — it names
 * `R.string.clip_empty_unauthorized` and [ClipDiagnostics], neither of which
 * exists there. So the assertions below were **extracted into a probe built
 * only from symbols that DO exist at the base ref** (`ClipPickerActivity`,
 * `ClipHttp(baseUrl, token, opener = …)`, `R.id.picker_empty`,
 * `@string/clip_empty_unreachable`) and run in a worktree at `3048498`:
 *
 *  * `aRefusedTokenDoesNotSayYouAreOffMesh` — **FAILED** at `3048498`;
 *  * `aMalformedBodyDoesNotSayYouAreOffMesh` — **FAILED** at `3048498`;
 *  * the dead-socket case — **PASSED** at `3048498`, which is the positive
 *    control: the probe really did render the off-mesh string for the one
 *    failure it is true of, so the two reds above are about the message and
 *    not about a fixture that produced no failure at all.
 *
 * The same probe is green at this branch's HEAD. The exact commands and the
 * run output are in the commit message.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipPickerFailureMessageTest {

    /** Realistic in SHAPE only. Not a credential, and not the operator's. */
    private val fixtureToken =
        "9f2c7a480be15d3692c8047fab3e1d50726cb98f4a0d6e23158bc7f0942ad631"

    private val ctx get() = RuntimeEnvironment.getApplication()

    @Before
    fun reset() {
        ClipOutboxHealth.clearForTest()
        ClipDiagnostics.clearForTest()
    }

    @After
    fun tearDown() {
        ClipOutboxHealth.clearForTest()
        ClipDiagnostics.clearForTest()
    }

    private class FakeConnection(
        url: String,
        private val status: Int,
        private val body: String,
        private val throwOnConnect: Exception? = null,
    ) : HttpURLConnection(URL(url)) {

        private val sink = ByteArrayOutputStream()

        override fun setRequestProperty(key: String, value: String?) = Unit
        override fun getOutputStream(): OutputStream = sink

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

        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun connect() { throwOnConnect?.let { throw it } }
    }

    /**
     * Opens the picker over a real [ClipHttp] whose single connection answers
     * as described, and returns the empty-state text on screen.
     *
     * The flush is stubbed out: this is a test about the FETCH's message, and
     * a drain would be a second source of outcomes in it.
     */
    private fun emptyTextFor(
        status: Int = 200,
        body: String = """{"clips":[],"limit":100}""",
        throwOnConnect: Exception? = null,
    ): String {
        val controller = Robolectric.buildActivity(ClipPickerActivity::class.java)
        controller.get().flush = {}
        controller.get().remote = ClipHttp(
            baseUrl = "http://10.42.0.10:8139",
            token = fixtureToken,
            opener = { url -> FakeConnection(url, status, body, throwOnConnect) },
        )
        // The executor seam removes the THREAD, not the call, so "open then
        // assert" has no race in it. ClipPickerActivityTest's shape.
        controller.get().loadExecutor = Executor { it.run() }
        controller.setup()
        return controller.get()
            .findViewById<TextView>(R.id.picker_empty)
            .text
            .toString()
    }

    private fun offMesh(): String = ctx.getString(R.string.clip_empty_unreachable)

    // --- the regression ------------------------------------------------------

    @Test
    fun aRefusedTokenDoesNotSayYouAreOffMesh() {
        // 🔴 RED AT origin/main: there, every failure is `null` and this is the
        // off-mesh sentence.
        val shown = emptyTextFor(status = 401, body = """{"error":"unauthorized"}""")
        assertNotEquals(
            "a 401 is rendered as the off-mesh message. The store ANSWERED — " +
                "this sends the reader to debug a network that is working, " +
                "which is exactly what it did.",
            offMesh(),
            shown,
        )
        assertEquals(ctx.getString(R.string.clip_empty_unauthorized, 401), shown)
        // The number is on screen, because "it refused the token" without the
        // status is a conclusion the reader cannot check.
        assertTrue("the status is not shown: $shown", shown.contains("401"))
    }

    @Test
    fun aDeadSocketStillSaysYouAreProbablyOffMesh() {
        // 🔴 THE OTHER HALF, and the reason the message above is not simply a
        // reworded constant: the off-mesh sentence is CORRECT here and must
        // survive. A fix that said "check your token" for a dead socket would
        // have moved the defect rather than removed it.
        assertEquals(offMesh(), emptyTextFor(throwOnConnect = IOException("ECONNREFUSED")))
    }

    @Test
    fun theFourFailureClassesProduceFOURDifferentSentences() {
        val unauthorized = emptyTextFor(status = 401, body = "")
        val otherStatus = emptyTextFor(status = 503, body = "")
        val malformed = emptyTextFor(status = 200, body = "<html>not json</html>")
        val transport = emptyTextFor(throwOnConnect = IOException("ETIMEDOUT"))

        assertEquals(
            "two failure classes produce the same sentence, which is the " +
                "collapse this change exists to remove. unauthorized=" +
                "<$unauthorized> otherStatus=<$otherStatus> malformed=" +
                "<$malformed> transport=<$transport>",
            4,
            setOf(unauthorized, otherStatus, malformed, transport).size,
        )
        assertEquals(ctx.getString(R.string.clip_empty_http, 503), otherStatus)
        assertEquals(ctx.getString(R.string.clip_empty_malformed), malformed)
    }

    @Test
    fun noFailureSentenceCanCarryTheToken() {
        // 🔴 The token is a constructor argument of the client that produced
        // every one of these messages, so "it is not in there" is a claim worth
        // measuring rather than assuming.
        for (shown in listOf(
            emptyTextFor(status = 401, body = """{"error":"unauthorized"}"""),
            emptyTextFor(status = 500, body = "boom"),
            emptyTextFor(status = 200, body = "<html>"),
            emptyTextFor(throwOnConnect = IOException(fixtureToken)),
        )) {
            assertFalse(
                "a failure message carries the token: <$shown>",
                shown.contains(fixtureToken),
            )
            // Positive control: the loop really did read a message, so the
            // absence above is not the absence of any text at all.
            assertTrue("no empty-state text was rendered at all", shown.isNotEmpty())
        }
    }

    // --- the states that are NOT failures ------------------------------------

    @Test
    fun anEmptyStoreStillSaysNothingStoredAndNotAnyFailure() {
        // 🔴 The asymmetry the three empty states rest on. A read that worked
        // and found nothing must not be reworded into a failure by this
        // change — that would make the new messages a regression of their own.
        val shown = emptyTextFor(status = 200, body = """{"clips":[],"limit":100}""")
        assertEquals(ctx.getString(R.string.clip_empty_none), shown)
    }

    @Test
    fun theOutcomeOnScreenIsTheOneTheReadoutReports() {
        // 🔴 THE SEAM. Two surfaces now word the same fetch — the sentence and
        // ClipDiagnostics — and nothing above would notice if they read
        // different classifications. This pins the RELATIONSHIP rather than
        // either side of it.
        emptyTextFor(status = 401, body = "")
        assertEquals("http:401", ClipDiagnostics.lastFetch()?.reason)

        emptyTextFor(status = 200, body = "<html>")
        assertEquals("malformed", ClipDiagnostics.lastFetch()?.reason)

        emptyTextFor(status = 200, body = """{"clips":[],"limit":100}""")
        assertEquals("rows:0", ClipDiagnostics.lastFetch()?.reason)
    }
}
