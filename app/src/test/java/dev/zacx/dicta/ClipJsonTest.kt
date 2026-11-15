package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The clip store's wire format.
 *
 * 🔴 **`@RunWith(RobolectricTestRunner::class)` IS MANDATORY HERE.**
 * [ClipJson] goes through `org.json`, which is a platform class with no JVM
 * implementation: without the runner every call is a `Stub!` throw, a write
 * silently produces nothing and a read returns nothing — which is
 * indistinguishable from a parse defect. That cost a debugging round on the
 * most-recently-used work, where every store test failed with
 * `expected:<{ssh-prod=4242}> but was:<{}>` and read as a prune bug.
 *
 * 🔴 **Invariant guards, not regression coverage.** [ClipJson] is a new file.
 *
 * ## 🔴 Every fixture below is the LIVE service's own output
 *
 * Captured with `curl` on 2026-11-15 against `http://10.42.0.10:8139`, which is
 * what makes these expectations a contract rather than a restatement of the
 * parser. The list envelope in particular is **not** what the design document
 * predicted: the design says *"a clip list is an array"*, and the service
 * actually answers `{"clips":[…],"limit":20}` — an outermost OBJECT. Had these
 * fixtures been written from the document, the parser would have been built for
 * a shape the service never sends.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipJsonTest {

    /** `GET /v1/clips?limit=20`, verbatim, two rows. */
    private val liveListResponse = """
        {"clips":[{"id":7,"body":"dicta-ct-probe-zzq9","device":"wire-probe","created_at":1794779809819,"bump_count":0},{"id":3,"body":"snip-shape-probe-zz1 verification","device":"shape-probe","created_at":1794779741207,"bump_count":1}],"limit":20}
    """.trimIndent()

    @Test
    fun theLiveListResponseParsesToItsRowsInTheServersOrder() {
        val rows = ClipJson.parseList(liveListResponse)
        assertEquals(2, rows.size)
        assertEquals(
            Clip(
                id = 7L,
                body = "dicta-ct-probe-zzq9",
                device = "wire-probe",
                createdAtMs = 1794779809819L,
                bumpCount = 0,
            ),
            rows[0],
        )
        assertEquals(3L, rows[1].id)
        assertEquals(1, rows[1].bumpCount)
        // 🔴 ORDER, asserted as such. Newest-first is the requirement and the
        // server is what decides it; a parser that sorted would be a second
        // definition of "newest" that could disagree with the desktop's.
        assertTrue(
            "the parser reordered the server's rows",
            rows[0].createdAtMs > rows[1].createdAtMs,
        )
    }

    @Test
    fun anEmptyClipsArrayIsVALIDAndYieldsNoRows() {
        // 🔴 Distinct from a failed read, which is `null` from
        // SharedClipSource.rows and a different empty state in the picker. An
        // operator who has deleted everything is not an error.
        assertEquals(emptyList<Clip>(), ClipJson.parseList("""{"clips":[],"limit":100}"""))
    }

    @Test
    fun anUnknownFieldIsToleratedAndDoesNotBrickThePhone() {
        // SnipCatalog's rule: a clipstore release that adds a field must not
        // make this app a gate on the service's schema evolution.
        val rows = ClipJson.parseList(
            """{"clips":[{"id":1,"body":"x","device":"d","created_at":5,"bump_count":0,"pinned":true}],"next":"abc"}"""
        )
        assertEquals(1, rows.size)
        assertEquals("x", rows[0].body)
    }

    @Test
    fun aMissingBodyOrDeviceDefaultsButAMissingIdIsFATAL() {
        // The id is what SharedClipSource.delete acts on, and a 0 would
        // address a row that does not exist — so it is the one field whose
        // absence refuses the whole response. Everything else defaults, which
        // is SnipCatalog's rule for exactly the same reason.
        val lenient = ClipJson.parseList("""{"clips":[{"id":9}]}""")
        assertEquals(1, lenient.size)
        assertEquals("", lenient[0].body)
        assertEquals("", lenient[0].device)
        assertEquals(0L, lenient[0].createdAtMs)

        for (bad in listOf("""{"clips":[{"body":"x"}]}""", """{"clips":[{"id":0,"body":"x"}]}""")) {
            val thrown = runCatching { ClipJson.parseList(bad) }.exceptionOrNull()
            assertTrue(
                "a row with no usable id parsed instead of being refused: $bad",
                thrown is ClipJson.Malformed,
            )
        }
    }

    @Test
    fun aResponseThatIsNotAClipListIsREFUSEDRatherThanReadAsEmpty() {
        // 🔴 The whole failure posture of this feature, in one test. An empty
        // list is what a drained store looks like, so degrading to `[]` here
        // would make the picker say "nothing on the shared clipboard yet" for a
        // store full of clips — the silent-empty failure
        // SnipUsageStore.kt:48-52 records as already decided against.
        //
        // The HTML case is not hypothetical: it is what a proxy answers during
        // a pod roll, which is JsonLite's own stated reason for tolerating a
        // non-JSON body.
        val refused = listOf(
            "",
            "   ",
            "<html><body>502 Bad Gateway</body></html>",
            """[{"id":1,"body":"x"}]""",
            """{"limit":20}""",
            """{"clips":{"id":1}}""",
            """{"clips":[7]}""",
            """{"clips":[{"id":1,"body":"x"}""",
        )
        for (body in refused) {
            val thrown = runCatching { ClipJson.parseList(body) }.exceptionOrNull()
            assertTrue(
                "this was accepted as a clip list instead of refused: <$body> " +
                    "(got ${thrown?.javaClass?.simpleName})",
                thrown is ClipJson.Malformed,
            )
        }
    }

    @Test
    fun aMalformedRefusalNamesWhatIsWrong() {
        // A refusal with no reason is the silent-empty failure wearing an
        // exception: the caller logs this string, and "parse failed" tells
        // whoever reads that log nothing to act on.
        val reason = runCatching { ClipJson.parseList("""{"limit":20}""") }
            .exceptionOrNull()
            ?.message
            .orEmpty()
        assertTrue("the refusal named nothing: <$reason>", reason.contains("clips"))
    }

    // --- the request body ----------------------------------------------------

    @Test
    fun thePostBodyIsTheTwoFieldsTheServiceRequires() {
        assertEquals(
            """{"body":"hello","device":"pixel-7"}""",
            ClipJson.postBody("hello", "pixel-7"),
        )
    }

    @Test
    fun thePostBodyEscapesTheCharactersAClipboardActuallyCarries() {
        // 🔴 Not a theoretical concern: a clipboard body contains quotes,
        // backslashes and newlines as a matter of routine, and a hand-built
        // body would produce a 400 for exactly the clips a user most wants to
        // move between machines.
        //
        // The expectation is a literal, so it is a claim about the WIRE and not
        // about whatever JSONObject happens to do.
        assertEquals(
            """{"body":"say \"hi\"\nC:\\tmp\tx","device":"d"}""",
            ClipJson.postBody("say \"hi\"\nC:\\tmp\tx", "d"),
        )
    }

    // --- reading a POST's answer ---------------------------------------------

    @Test
    fun theLive201AndThe200DedupeAnswerAreReadForWhatTheySay() {
        // Both captured from the live service: an identical body answers 200
        // with deduped:true and a bumped count where a new one answers 201.
        val created =
            """{"id":5,"body":"dicta-wire-probe-zzq9","device":"wire-probe","created_at":1794779793374,"bump_count":0,"deduped":false}"""
        val deduped =
            """{"id":5,"body":"dicta-wire-probe-zzq9","device":"wire-probe","created_at":1794779793667,"bump_count":1,"deduped":true}"""

        assertEquals(5L, ClipJson.parseIdOrZero(created))
        assertEquals(false, ClipJson.deduped(created))
        assertEquals(5L, ClipJson.parseIdOrZero(deduped))
        assertEquals(true, ClipJson.deduped(deduped))
    }

    @Test
    fun anIdThatIsAbsentOrUnreadableIsZeroAndNeverGuessed() {
        // 🔴 Zero is what makes ClipHttp refuse to treat the POST as a
        // success. The one thing that must not happen is an item leaving the
        // outbox — the only copy of the user's capture — on the strength of a
        // 2xx whose body said nothing.
        assertEquals(0L, ClipJson.parseIdOrZero("{}"))
        assertEquals(0L, ClipJson.parseIdOrZero(""))
        assertEquals(0L, ClipJson.parseIdOrZero("not json at all"))
        assertEquals(0L, ClipJson.parseIdOrZero("""{"id":"five"}"""))
    }

    @Test
    fun dedupedIsThreeValuedAndAbsenceIsNULLRatherThanFalse() {
        // 🔴 JsonLite.boolField's rule, and the third value is load-bearing in
        // the same direction: `null` means the server said nothing, which must
        // not be read as "checked, and it was new".
        assertNull(ClipJson.deduped("""{"id":1}"""))
        assertNull(ClipJson.deduped("""{"id":1,"deduped":null}"""))
        assertNull(ClipJson.deduped("<html>502</html>"))
    }

    @Test
    fun theServicesOwnErrorStringsAreWhatGetsReported() {
        // Both are the live service's, measured: an empty body and a DELETE of
        // an id that is gone.
        assertEquals(
            "`body` is required and must not be empty",
            ClipJson.detail("""{"error":"empty_body","detail":"`body` is required and must not be empty"}"""),
        )
        assertEquals(
            "no clip with id 5",
            ClipJson.detail("""{"error":"no_such_clip","detail":"no clip with id 5"}"""),
        )
        // The error code alone when there is no detail; null when there is
        // neither, so a caller cannot report an empty string as a reason.
        assertEquals("teapot", ClipJson.detail("""{"error":"teapot"}"""))
        assertNull(ClipJson.detail("{}"))
        assertNull(ClipJson.detail("<html>504 Gateway Timeout</html>"))
    }
}
