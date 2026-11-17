package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
 * 🔴 **REGRESSION COVERAGE for the defect that made diagnosis blind: a 401, a
 * malformed body and a dead socket were ONE `null`.**
 *
 * At `origin/main`, `ClipHttp.rows()` returned `null` for a non-200 status, for
 * a [ClipJson.Malformed] body, for an `IOException` and for a
 * `RuntimeException`, and [ClipPickerActivity] rendered all four as
 * `@string/clip_empty_unreachable` — *"usually being off-mesh"*. A refused
 * token therefore read as a network problem, on the only surface that reports
 * on this feature at all.
 *
 * ## ⚠ Red-at-base, stated precisely rather than claimed loosely
 *
 * This file **cannot compile at `origin/main`**: [ClipFetch] and
 * `SharedClipSource.fetch` do not exist there, so "watched to fail at the base
 * ref" would be a compile error and not a guard going red — the situation
 * `SettingsThemingTest` documents for the design pass. What was done instead,
 * and what the commit message records:
 *
 *  * a **MUTATION**: with [ClipFetch] left in place, `ClipHttp.fetch`'s two
 *    classifying lines were reverted to the collapsing shape — `Status(status)`
 *    and `Malformed` both answering `Transport("unreachable")` — and the suite
 *    was run. **Killed by 5 tests**: this file's
 *    [aRefusedTokenAMalformedBodyAndADeadSocketAreTHREEDistinctReasons] and
 *    [theStatusIsCarriedSoA401IsNotSpelledLikeA404OrA500], and three of
 *    `ClipPickerFailureMessageTest`'s. 🔴 **`ClipHttpTest` stayed GREEN under
 *    that mutant**, which is the finding worth recording: 21 tests over this
 *    client, all passing, and not one of them could see the collapse — because
 *    every one of them reads `rows()`, which is the lossy view. That is what
 *    these tests are for.
 *  * a **runnable** red-at-base probe at the level the user actually meets —
 *    see `ClipPickerFailureMessageTest`'s kdoc for the measured matrix at
 *    `3048498`, including its positive control.
 *
 * The classification is asserted on whole, normalised [ClipFetch.reason]
 * strings. A guard on a word would be walkable by rewording; the point of the
 * type is that two outcomes can never again be spelled the same way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipFetchClassificationTest {

    /** Realistic in SHAPE only. Not a credential, and not the operator's. */
    private val fixtureToken =
        "4b1d9e07c3a8f5126de4b0937ac15f82e6d0734ba9c81f5e20d6c48b7a39015f"

    private val base = "http://10.42.0.10:8139"

    /** `ClipHttpTest`'s fake, unchanged — a second one would be incomparable. */
    private class FakeConnection(
        url: String,
        private val status: Int,
        private val body: String,
        private val throwOnConnect: Exception? = null,
    ) : HttpURLConnection(URL(url)) {

        val sent = ByteArrayOutputStream()

        override fun setRequestProperty(key: String, value: String?) = Unit
        override fun getOutputStream(): OutputStream = sent

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

    private fun client(
        status: Int = 200,
        body: String = """{"clips":[],"limit":100}""",
        throwOnConnect: Exception? = null,
    ) = ClipHttp(
        baseUrl = base,
        token = fixtureToken,
        opener = { url -> FakeConnection(url, status, body, throwOnConnect) },
    )

    // --- the three reasons that used to be one -------------------------------

    @Test
    fun aRefusedTokenAMalformedBodyAndADeadSocketAreTHREEDistinctReasons() {
        val unauthorized = client(status = 401, body = """{"error":"unauthorized"}""").fetch()
        val malformed = client(status = 200, body = "<html>not json</html>").fetch()
        val transport = client(throwOnConnect = IOException("ECONNREFUSED")).fetch()

        // 🔴 The whole-string pins. Not `contains`, not a word.
        assertEquals("http:401", unauthorized.reason)
        assertEquals("malformed", malformed.reason)
        assertEquals("io:ECONNREFUSED", transport.reason)

        // And the set is genuinely three, which is the claim the old `null`
        // could not make: a reader of this test should not have to compare the
        // three literals above by eye.
        assertEquals(
            "two of the three failure classes are spelled the same way, which " +
                "is the collapse this type exists to prevent",
            3,
            setOf(unauthorized.reason, malformed.reason, transport.reason).size,
        )

        // The TYPES are distinct too, so a consumer can branch without
        // parsing the string.
        assertTrue(unauthorized is ClipFetch.Status)
        assertTrue(malformed is ClipFetch.Malformed)
        assertTrue(transport is ClipFetch.Transport)
    }

    @Test
    fun theStatusIsCarriedSoA401IsNotSpelledLikeA404OrA500() {
        // 🔴 Each of these was `null` at base, and each has a DIFFERENT repair:
        // the token in Settings, the base URL in Settings, and waiting for the
        // service. Spelling them the same way is why the second and third were
        // diagnosed as the first.
        val reasons = listOf(401, 403, 404, 500, 503).map {
            client(status = it, body = "").fetch().reason
        }
        assertEquals(
            listOf("http:401", "http:403", "http:404", "http:500", "http:503"),
            reasons,
        )
        assertEquals("every status must be distinguishable", 5, reasons.toSet().size)
    }

    @Test
    fun onlyFourOhOneAndFourOhThreeAreAnAuthFault() {
        // The membership is spelled in ONE place — ClipFetch.Status — because
        // the sentence on screen and the readout must not be able to disagree
        // about which statuses mean "your token was refused".
        assertTrue(ClipFetch.Status(401).isAuthFault)
        assertTrue(ClipFetch.Status(403).isAuthFault)
        for (other in listOf(400, 404, 405, 418, 500, 502, 503)) {
            assertEquals(
                "HTTP $other is not an auth fault — saying so would send the " +
                    "operator to rotate a token that is fine",
                false,
                ClipFetch.Status(other).isAuthFault,
            )
        }
    }

    // --- the rows path is unchanged ------------------------------------------

    @Test
    fun aDrainedStoreIsStillAnEmptyListAndNOTAFailure() {
        // 🔴 The asymmetry this whole feature rests on, re-asserted through the
        // new type: an empty store is Rows(empty), never a failure. Collapsing
        // THAT would make two of the picker's three empty states unreachable.
        val fetched = client(status = 200, body = """{"clips":[],"limit":100}""").fetch()
        assertEquals(ClipFetch.Rows(emptyList()), fetched)
        assertEquals("rows:0", fetched.reason)
        assertEquals(emptyList<Clip>(), client().rows())
    }

    @Test
    fun rowsRemainsTheLOSSYViewAndStillAnswersNullForEveryFailure() {
        // Deliberately asserting the OLD behaviour of the old member: every
        // existing caller and test of `rows()` is unchanged, which is what
        // makes them evidence that adding the classification did not alter
        // what this client concludes. The loss is still there; what changed is
        // that it is no longer the only view.
        assertNull(client(status = 401, body = "").rows())
        assertNull(client(status = 200, body = "<html>").rows())
        assertNull(client(throwOnConnect = IOException("ETIMEDOUT")).rows())
        assertEquals(1, client(status = 200, body = LIVE_BODY).rows()?.size)
    }

    @Test
    fun aRuntimeExceptionIsATransportFailureAndNeverEscapes() {
        // The fourth class that was folded into the same `null`. It must not
        // throw — `fetch()` inherits `rows()`'s never-throws contract.
        val got = runCatching {
            client(throwOnConnect = IllegalStateException("socket is closed")).fetch()
        }
        assertTrue("fetch() threw: ${got.exceptionOrNull()}", got.isSuccess)
        assertEquals("io:socket is closed", got.getOrNull()?.reason)
    }

    @Test
    fun aTransportReasonCarriesTheMESSAGEAndNeverTheToStringWithItsUrl() {
        // 🔴 ClipHttp's own measured rule: some platform clients put the
        // request URL in toString(). The reason string is the message, which is
        // a stronger invariant than "derived from something that has so far
        // never contained a secret".
        val reason = client(throwOnConnect = IOException("ETIMEDOUT")).fetch().reason
        assertEquals("io:ETIMEDOUT", reason)
        assertNotEquals(true, reason.contains(base))
        assertNotEquals(true, reason.contains(fixtureToken))
    }

    @Test
    fun aNamelessExceptionFallsBackToItsClassAndNeverToAnEmptyReason() {
        // An empty reason is the silent-empty failure in a diagnostic: it reads
        // as "no detail" and is indistinguishable from a bug in the plumbing.
        assertEquals(
            "io:IOException",
            client(throwOnConnect = IOException()).fetch().reason,
        )
    }

    // --- the lossy default, named rather than hidden -------------------------

    @Test
    fun aSourceThatClassifiesNothingSaysSoRatherThanInventingATransportFailure() {
        // 🔴 The default implementation of `SharedClipSource.fetch` is lossy by
        // construction. It must report `unclassified` — a fact about the
        // source — and never `io:…`, which would be a measurement nobody made.
        val bare = object : SharedClipSource {
            override fun rows(): List<Clip>? = null
            override fun delete(id: Long): Boolean = false
        }
        assertEquals(ClipFetch.Unclassified, bare.fetch())
        assertEquals("unclassified", bare.fetch().reason)

        // Positive control: the same default carries rows through, so the
        // `unclassified` above is about the failure path and not about a
        // default that answers Unclassified for everything.
        val rows = listOf(Clip(1L, "a clip", "laptop", 1_700_000_000_000L))
        val withRows = object : SharedClipSource {
            override fun rows(): List<Clip> = rows
            override fun delete(id: Long): Boolean = false
        }
        assertEquals(ClipFetch.Rows(rows), withRows.fetch())
    }

    private companion object {
        const val LIVE_BODY =
            """{"clips":[{"id":7,"body":"copied text","device":"desktop",""" +
                """"created_at":1700000000000}],"limit":100}"""
    }
}
