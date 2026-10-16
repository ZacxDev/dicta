package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Request construction: the endpoint path, the `Authorization` header shape,
 * and the multipart field names.
 *
 * **Invariant guards**, not regression tests — nothing here was ever observed
 * broken. They exist because all three are strings the client cannot verify
 * against the server at build time, and getting any of them subtly wrong
 * produces a confident 401/422 that reads like a configuration problem.
 *
 * Expectations are literals typed from the measured contract, never derived
 * from the constants under test.
 */
class SttRequestTest {

    // --- endpoints ----------------------------------------------------------

    @Test
    fun theTranscriptionPathIsTheMeasuredOne() {
        assertEquals(
            "http://10.42.0.10:8118/v1/audio/transcriptions",
            SttEndpoints.transcriptions("http://10.42.0.10:8118"),
        )
    }

    @Test
    fun theHealthPathIsTheMeasuredOne() {
        assertEquals(
            "http://10.42.0.10:8118/healthz",
            SttEndpoints.healthz("http://10.42.0.10:8118"),
        )
    }

    @Test
    fun aTrailingSlashOnTheBaseUrlDoesNotProduceADoubleSlash() {
        // The base URL is typed by a human into a settings field, so this is
        // the expected input rather than a pathological one. "//v1/..." does
        // not match the service's router.
        assertEquals(
            "http://10.42.0.10:8118/v1/audio/transcriptions",
            SttEndpoints.transcriptions("http://10.42.0.10:8118/"),
        )
        assertEquals(
            "http://10.42.0.10:8118/healthz",
            SttEndpoints.healthz("http://10.42.0.10:8118///"),
        )
    }

    @Test
    fun surroundingWhitespaceFromAPasteIsTrimmed() {
        assertEquals(
            "http://10.42.0.10:8118/healthz",
            SttEndpoints.healthz("  http://10.42.0.10:8118  "),
        )
    }

    @Test
    fun aDifferentHostIsHonouredRatherThanIgnored() {
        // Control against an implementation that hardcodes the default: the
        // host here cannot equal DEFAULT_BASE_URL's, so a constant-returning
        // implementation fails.
        assertEquals(
            "http://192.0.2.7:9999/v1/audio/transcriptions",
            SttEndpoints.transcriptions("http://192.0.2.7:9999"),
        )
    }

    @Test
    fun theDefaultBaseUrlIsTheMeshAddress() {
        assertEquals("http://10.42.0.10:8118", SttEndpoints.DEFAULT_BASE_URL)
    }

    // --- the Authorization header ------------------------------------------

    @Test
    fun theAuthorizationHeaderIsBearerSpaceToken() {
        // One space, the literal "Bearer", capital B, token untouched. The
        // service compares the remainder byte-for-byte.
        assertEquals("Bearer abc123", SttEndpoints.authorization("abc123"))
    }

    @Test
    fun theTokenIsNotReCasedOrTrimmedInsideTheHeader() {
        // A token with mixed case and inner punctuation must survive exactly.
        val token = "Zx-9_AbC.dEf~qQ"
        assertEquals("Bearer $token", SttEndpoints.authorization(token))
    }

    // --- the multipart body -------------------------------------------------

    private val audio = byteArrayOf(0x4F, 0x67, 0x67, 0x53, 0x11, 0x22, 0x33)

    private fun bodyText(
        language: String? = "de",
        punctuation: Boolean? = true,
    ): String = MultipartBody.build(
        boundary = "BNDRY7",
        fileName = "dictation.ogg",
        fileContentType = "audio/ogg",
        audio = audio,
        language = language,
        punctuation = punctuation,
    ).toString(Charsets.ISO_8859_1)

    @Test
    fun theFieldNamesAreFileLanguageAndPunctuation() {
        val body = bodyText()
        assertTrue(
            "the file part must be named exactly \"file\"",
            body.contains("name=\"file\""),
        )
        assertTrue(
            "the language part must be named exactly \"language\"",
            body.contains("name=\"language\""),
        )
        assertTrue(
            "the punctuation part must be named exactly \"punctuation\"",
            body.contains("name=\"punctuation\""),
        )
    }

    @Test
    fun theFieldNameConstantsMatchTheServerContract() {
        // Literals from the contract, so renaming a constant cannot
        // "fix" the test above by moving both sides together.
        assertEquals("file", MultipartBody.FIELD_FILE)
        assertEquals("language", MultipartBody.FIELD_LANGUAGE)
        assertEquals("punctuation", MultipartBody.FIELD_PUNCTUATION)
    }

    @Test
    fun theLanguageValueIsTheBareCode() {
        // "de", not "de-DE". The fixture language is deliberately NOT "en",
        // so an implementation that hardcodes the default fails here.
        val body = bodyText(language = "de")
        assertTrue(body.contains("name=\"language\"\r\n\r\nde\r\n"))
        assertFalse("a region-qualified tag reached the wire", body.contains("de-DE"))
    }

    @Test
    fun aBcp47TagIsRefusedRatherThanSentOrSilentlyRewritten() {
        // 🔴 The precondition is the point: quietly rewriting "en-US" here
        // would hide an upstream bug that the next caller re-introduces.
        // Refusing makes it a test failure at the call site instead of a 500
        // in production.
        val thrown = try {
            MultipartBody.build(
                boundary = "B", fileName = "a.ogg", fileContentType = "audio/ogg",
                audio = audio, language = "en-US", punctuation = true,
            )
            null
        } catch (e: IllegalArgumentException) {
            e
        }
        assertTrue(
            "building a body with a BCP-47 language tag must throw",
            thrown != null,
        )
    }

    @Test
    fun omittingLanguageOmitsTheFieldEntirely() {
        val body = bodyText(language = null)
        assertFalse(
            "a null language must not emit an empty language part",
            body.contains("name=\"language\""),
        )
        // The file part is still there — omitting one field must not drop the
        // rest of the body.
        assertTrue(body.contains("name=\"file\""))
    }

    @Test
    fun omittingPunctuationOmitsTheFieldEntirely() {
        val body = bodyText(punctuation = null)
        assertFalse(body.contains("name=\"punctuation\""))
        assertTrue(body.contains("name=\"file\""))
    }

    @Test
    fun punctuationIsSerialisedAsLowercaseTrueOrFalse() {
        assertTrue(bodyText(punctuation = true).contains("\r\n\r\ntrue\r\n"))
        assertTrue(bodyText(punctuation = false).contains("\r\n\r\nfalse\r\n"))
    }

    @Test
    fun theAudioBytesSurviveVerbatim() {
        val raw = MultipartBody.build(
            boundary = "BNDRY7", fileName = "dictation.ogg",
            fileContentType = "audio/ogg", audio = audio,
            language = "de", punctuation = true,
        )
        // Locate the audio by its exact byte sequence rather than by offset.
        val needle = audio
        var found = -1
        outer@ for (i in 0..(raw.size - needle.size)) {
            for (j in needle.indices) {
                if (raw[i + j] != needle[j]) continue@outer
            }
            found = i
            break
        }
        assertTrue("the audio bytes were altered or dropped", found >= 0)
    }

    @Test
    fun theBodyIsTerminatedWithTheClosingBoundary() {
        val body = bodyText()
        assertTrue(
            "a multipart body without the closing boundary is a 422",
            body.endsWith("--BNDRY7--\r\n"),
        )
    }

    @Test
    fun theFilePartCarriesAFilenameWithAnExtension() {
        // Some form parsers drop a file part with no filename.
        val body = bodyText()
        assertTrue(body.contains("filename=\"dictation.ogg\""))
        assertTrue(body.contains("Content-Type: audio/ogg"))
    }

    @Test
    fun theContentTypeHeaderNamesTheBoundary() {
        assertEquals(
            "multipart/form-data; boundary=BNDRY7",
            MultipartBody.contentType("BNDRY7"),
        )
    }

    @Test
    fun theUploadFileNameAndTypeAreTheOnesTheServerDecodes() {
        assertEquals("dictation.ogg", SttHttp.AUDIO_FILE_NAME)
        assertEquals("audio/ogg", SttHttp.AUDIO_CONTENT_TYPE)
    }

    // --- the detail/text extractor -----------------------------------------

    @Test
    fun theTranscriptIsReadFromTheTextField() {
        assertEquals(
            "hello there",
            JsonLite.stringField(
                """{"text": "hello there", "duration_s": 3.5, "elapsed_s": 1.2}""",
                "text",
            ),
        )
    }

    @Test
    fun theDetailIsReadFromAnErrorBody() {
        assertEquals(
            "invalid or missing bearer token",
            JsonLite.stringField(
                """{"detail":"invalid or missing bearer token"}""",
                "detail",
            ),
        )
    }

    @Test
    fun escapesInsideTheStringAreDecoded() {
        assertEquals(
            "a \"quoted\" word\nand a tab\there",
            JsonLite.stringField(
                "{\"text\":\"a \\\"quoted\\\" word\\nand a tab\\there\"}",
                "text",
            ),
        )
    }

    @Test
    fun aUnicodeEscapeIsDecoded() {
        assertEquals("café", JsonLite.stringField("""{"text":"café"}""", "text"))
    }

    @Test
    fun anAbsentFieldIsNull() {
        assertNull(JsonLite.stringField("""{"text":"hi"}""", "detail"))
    }

    @Test
    fun aNonJsonBodyIsNullRatherThanAThrow() {
        // 🔴 The pod-roll case: the proxy answers 503 with HTML or nothing.
        // Null then classifies as "transient", which is correct.
        assertNull(JsonLite.stringField("<html><body>502 Bad Gateway</body></html>", "detail"))
        assertNull(JsonLite.stringField("", "detail"))
        assertNull(JsonLite.stringField("not json at all", "detail"))
    }

    @Test
    fun aNonStringValueIsNull() {
        assertNull(JsonLite.stringField("""{"detail":null}""", "detail"))
        assertNull(JsonLite.stringField("""{"detail":503}""", "detail"))
        assertNull(JsonLite.stringField("""{"detail":{"msg":"x"}}""", "detail"))
    }

    @Test
    fun atruncatedStringIsNullRatherThanAPartialValue() {
        // A body cut off mid-value must not yield a half transcript that then
        // gets shown to the user as if it were complete.
        assertNull(JsonLite.stringField("""{"text":"half a sentence""", "text"))
    }

    @Test
    fun aMatchInsideAnotherValueIsNotMistakenForTheKey() {
        // "detail" appears as part of a *value* first. The extractor must
        // keep looking for a real key position.
        assertEquals(
            "the real one",
            JsonLite.stringField(
                """{"msg":"see detail below","detail":"the real one"}""",
                "detail",
            ),
        )
    }

    @Test
    fun whitespaceAroundTheColonIsTolerated() {
        assertEquals("x", JsonLite.stringField("""{"detail"   :   "x"}""", "detail"))
    }

    // --- the boolean extractor (truncated) ----------------------------------
    //
    // 🔴 Three-valued, and the third value is the point: null means the server
    // gave NO SIGNAL. Every one of these asserts which of the three came back,
    // because a reader that collapses null to false turns "could not tell"
    // into "checked and fine".

    @Test
    fun aTrueBooleanFieldReadsAsTrue() {
        assertEquals(
            true,
            JsonLite.boolField(
                """{"text":"a cut-off sentence","truncated":true,"duration_s":9.37}""",
                "truncated",
            ),
        )
    }

    @Test
    fun aFalseBooleanFieldReadsAsFalseAndNotAsNull() {
        // false is a POSITIVE claim by the server: it looked for the EOS token
        // and found one. It must be distinguishable from "no signal".
        assertEquals(
            false,
            JsonLite.boolField(
                """{"text":"a whole sentence","truncated":false,"duration_s":4.81}""",
                "truncated",
            ),
        )
    }

    @Test
    fun anAbsentBooleanFieldIsNullRatherThanFalse() {
        // The pre-change server, and any future response that drops the field.
        assertNull(
            JsonLite.boolField(
                """{"text":"a whole sentence","duration_s":2.46}""",
                "truncated",
            ),
        )
    }

    @Test
    fun anExplicitJsonNullIsNullAndNotFalse() {
        // 🔴 The server sends this when it had no EOS id to look for, i.e. it
        // COULD NOT TELL. Reading it as false would be a verification claim
        // nobody made.
        assertNull(JsonLite.boolField("""{"truncated":null}""", "truncated"))
    }

    @Test
    fun aNonBooleanValueIsNull() {
        assertNull(JsonLite.boolField("""{"truncated":"true"}""", "truncated"))
        assertNull(JsonLite.boolField("""{"truncated":1}""", "truncated"))
        assertNull(JsonLite.boolField("""{"truncated":{"why":"cap"}}""", "truncated"))
        assertNull(JsonLite.boolField("""{"truncated":[true]}""", "truncated"))
    }

    @Test
    fun aNonJsonBodyIsNullRatherThanAThrowForBooleans() {
        assertNull(JsonLite.boolField("<html><body>502 Bad Gateway</body></html>", "truncated"))
        assertNull(JsonLite.boolField("", "truncated"))
        assertNull(JsonLite.boolField("truncated", "truncated"))
        // A key position with nothing after it at all.
        assertNull(JsonLite.boolField("""{"truncated":""", "truncated"))
    }

    @Test
    fun theBooleanNameAppearingInAValueIsNotMistakenForTheKey() {
        // 🔴 The trap stringField's own comment documents, in the shape this
        // field actually meets it: the transcript TEXT is the word, and the
        // real key says the opposite. A naive indexOf reads the value's quotes
        // and answers with whatever literal follows — here, the wrong one.
        assertEquals(
            false,
            JsonLite.boolField(
                """{"text":"truncated","truncated":false}""",
                "truncated",
            ),
        )
        // And the mirror image, so neither answer can be right by accident.
        assertEquals(
            true,
            JsonLite.boolField(
                """{"text":"not truncated at all","truncated":true}""",
                "truncated",
            ),
        )
    }

    @Test
    fun aBareWordThatMerelyStartsWithALiteralIsNotThatLiteral() {
        assertNull(JsonLite.boolField("""{"truncated":truest}""", "truncated"))
        assertNull(JsonLite.boolField("""{"truncated":falsehood}""", "truncated"))
    }

    @Test
    fun aLongerKeyThatStartsWithTheNameIsNotMistakenForIt() {
        // 🔴 Not hypothetical: the live response puts "unreliable_reasons"
        // immediately after "unreliable". A needle built without its closing
        // quote matches the LONGER key first, finds a `[` after the colon, and
        // answers "no signal" for a response that said true — a fabrication
        // warning silently lost to an adjacent field name.
        val body = """{"text":" you","unreliable":true,""" +
            """"unreliable_reasons":["no_speech_prob"],"truncated":false}"""
        assertEquals(true, JsonLite.boolField(body, "unreliable"))
        assertEquals(false, JsonLite.boolField(body, "truncated"))
        // Read on its own the longer key is an array, i.e. no signal.
        assertNull(JsonLite.boolField(body, "unreliable_reasons"))
    }

    @Test
    fun whitespaceAroundTheColonIsToleratedForBooleans() {
        assertEquals(true, JsonLite.boolField("""{"truncated"   :   true}""", "truncated"))
    }

    // --- depth: "top-level" must mean top-level -----------------------------
    //
    // 🔴 The bodies below proved the defect by probe and are kept as its
    // regression. Before the depth check both readers returned the FIRST
    // key-position match at ANY depth, so a nested field shadowed the real
    // one. For `unreliable` that is the unsafe direction: a nested `false`
    // ahead of a top-level `true` renders the plain reassuring status — the
    // unmarked fabrication this change exists to prevent, reintroduced by an
    // ADDITIVE server change with every other test still green.
    //
    // Not hypothetical. The response is explicitly a moving target (the
    // threshold lives server-side so it can be retuned without an APK), and
    // per-segment detail — `segments: [{...}]`, the shape every
    // Whisper-family API grows — is exactly the additive evolution that
    // invites it.

    @Test
    fun aNestedBooleanDoesNotShadowTheTopLevelOne() {
        assertEquals(
            "a nested \"unreliable\":false was read instead of the top-level " +
                "true — the fabrication warning is lost to a field the server " +
                "added elsewhere in the document",
            true,
            JsonLite.boolField(
                """{"segments":[{"unreliable":false}],"unreliable":true}""",
                "unreliable",
            ),
        )
        // The mirror image, so neither answer can be right by accident.
        assertEquals(
            false,
            JsonLite.boolField(
                """{"segments":[{"unreliable":true}],"unreliable":false}""",
                "unreliable",
            ),
        )
    }

    @Test
    fun aBooleanThatExistsOnlyNestedIsNoSignal() {
        assertNull(
            "a nested \"truncated\" was reported as the document's own verdict " +
                "when the document has no such field at all",
            JsonLite.boolField("""{"meta":{"truncated":true},"text":"x"}""", "truncated"),
        )
    }

    @Test
    fun aNestedStringDoesNotShadowTheTopLevelOne() {
        // Same flaw, same fix, different consequence: a nested hit here is a
        // wrong TRANSCRIPT rather than a wrong safety verdict.
        // `segments[].text` is the shape it occurs in.
        assertEquals(
            "a per-segment \"text\" was returned as the whole transcript",
            "the whole transcript",
            JsonLite.stringField(
                """{"segments":[{"text":"the first segment"}],"text":"the whole transcript"}""",
                "text",
            ),
        )
        assertNull(JsonLite.stringField("""{"error":{"detail":"nested only"}}""", "detail"))
    }

    @Test
    fun anOrdinaryFlatBodyStillParsesAfterTheDepthCheck() {
        // 🔴 The control that stops the fix being "reject everything": the
        // real measured response, both readers, every kind of value in it.
        val body = """{"text":" you","duration_s":0.4,"elapsed_s":0.15,""" +
            """"unreliable":true,"unreliable_reasons":["no_speech_prob"],""" +
            """"finish_reason":"stop","truncated":false,"avg_logprob":-0.3622}"""
        assertEquals(" you", JsonLite.stringField(body, "text"))
        assertEquals("stop", JsonLite.stringField(body, "finish_reason"))
        assertEquals(true, JsonLite.boolField(body, "unreliable"))
        assertEquals(false, JsonLite.boolField(body, "truncated"))
    }

    @Test
    fun aKeyAfterANestedObjectIsStillFound() {
        // The scan must come back OUT of a nested value, not stop at it.
        val body = """{"meta":{"a":1,"b":[{"c":true}]},"unreliable":true,""" +
            """"nested":{"deep":{"deeper":"x"}},"truncated":true}"""
        assertEquals(true, JsonLite.boolField(body, "unreliable"))
        assertEquals(true, JsonLite.boolField(body, "truncated"))
        assertNull(JsonLite.boolField(body, "c"))
        assertNull(JsonLite.stringField(body, "deeper"))
    }

    @Test
    fun aBraceInsideAStringDoesNotMoveTheDepth() {
        // 🔴 A depth counter that does not skip string CONTENTS is fooled by
        // punctuation in the transcript itself — not an attack, just a user
        // dictating a brace.
        val body = """{"text":"a { brace } and a [ bracket ]","unreliable":true}"""
        assertEquals(
            "a brace inside the transcript text moved the nesting depth, so " +
                "the real top-level field stopped being seen as top-level",
            true,
            JsonLite.boolField(body, "unreliable"),
        )
        val escaped = """{"text":"a \"quoted\" { brace","truncated":true}"""
        assertEquals(true, JsonLite.boolField(escaped, "truncated"))
    }

    @Test
    fun aBodyThatIsNotAnObjectIsNoSignal() {
        assertNull(JsonLite.boolField("""[{"unreliable":true}]""", "unreliable"))
        assertNull(JsonLite.stringField("""["text"]""", "text"))
        assertNull(JsonLite.boolField("   ", "unreliable"))
    }

    @Test
    fun aNonJsonBodyThatMerelyQUOTESAFieldIsNoSignal() {
        // 🔴 This case exists because a mutation sweep SURVIVED without it.
        // Removing the "must start with {" check left every assertion above
        // green: for a top-level array the nesting counter happens to reach
        // zero and return -1 anyway, so those fixtures could not tell the two
        // implementations apart. The input that can is a body that is not JSON
        // at all but contains the text of a field — a proxy or gateway echoing
        // an upstream payload into a plain-text error, which is precisely the
        // "503 with an HTML body" family this reader already has to survive.
        assertNull(
            "a field QUOTED inside a non-JSON error body was read as the " +
                "response's own verdict",
            JsonLite.boolField(
                """Error from upstream: "unreliable":true (request rejected)""",
                "unreliable",
            ),
        )
        assertNull(
            JsonLite.stringField(
                """upstream said "text":"not a transcript" and gave up""",
                "text",
            ),
        )
    }

    @Test
    fun anEscapedQuoteDoesNotEndTheStringAndHideALaterKey() {
        // 🔴 Also added because a mutant SURVIVED: making the string-end scan
        // treat `\` as an ordinary character left the earlier brace fixture
        // green, because the stray `{` happened to fall inside the pseudo
        // string the mis-parse produced — the guarded branch landed exactly on
        // its own boundary and never ran. This fixture overshoots instead: the
        // mis-parse shifts every subsequent quote by one, so the real
        // top-level key stops being seen as a key at all.
        assertEquals(
            "an escaped quote inside the transcript ended the string early, " +
                "shifting every later quote and hiding the real top-level key",
            true,
            JsonLite.boolField("""{"text":"a \" b","truncated":true}""", "truncated"),
        )
        assertEquals(
            true,
            JsonLite.boolField("""{"text":"\"","unreliable":true}""", "unreliable"),
        )
        // The string itself must still decode, so the fix cannot be "treat a
        // backslash as the end of everything".
        assertEquals("a \" b", JsonLite.stringField("""{"text":"a \" b"}""", "text"))
    }
}
