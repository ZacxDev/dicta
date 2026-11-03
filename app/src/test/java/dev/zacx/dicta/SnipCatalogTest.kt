package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [SnipCatalog] — what it accepts, what it refuses, and that a refusal **says
 * why**.
 *
 * Robolectric rather than a plain JUnit class only because `org.json` is a
 * platform library: nothing here touches a `Context`.
 *
 * ## 🔴 The reason text is asserted, not just the throw
 *
 * A refusal whose message is empty, or the same for every cause, is the defect
 * this class exists to prevent one layer in: `SettingsActivity` puts the reason
 * on screen, and "that file is not a snippets.json: " with nothing after the
 * colon leaves the operator exactly as stuck as a silent empty list would.
 *
 * ## 🔴 Every fixture is SYNTHETIC
 *
 * `ZacxDev/dicta` is public and the author's real catalog carries mesh
 * addresses and client repository paths. No row below is derived from it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SnipCatalogTest {

    @Test
    fun aValidArrayParsesEveryRowWithEveryField() {
        val parsed = SnipCatalog.parse(
            """
            [
              {"label":"Zebra crossing","body":"walk","search_terms":["stripes","walk"],"category":"path"},
              {"label":"Quince jam","body":"boil","search_terms":["fruit"],"category":"prompt"}
            ]
            """.trimIndent()
        )
        // Literal, in full. An expectation built by reading the same JSON a
        // second way would pin nothing about this parser.
        assertEquals(
            listOf(
                Snippet("Zebra crossing", "walk", listOf("stripes", "walk"), "path"),
                Snippet("Quince jam", "boil", listOf("fruit"), "prompt"),
            ),
            parsed,
        )
    }

    @Test
    fun anEmptyArrayIsValidAndYieldsNoSnippets() {
        // An operator who cleared their catalog is not an error, and the
        // picker's own "the imported snippets.json has no snippets in it" copy
        // covers it. Refusing here would make a legitimate file an import
        // failure, which leaves the PREVIOUS catalog in place — the opposite of
        // what the operator asked for.
        assertEquals(emptyList<Snippet>(), SnipCatalog.parse("[]"))
    }

    @Test
    fun anEmptyLabelIsRefusedAndTheReasonNamesTheRow() {
        val reason = reasonFor("""[{"label":"Quince jam"},{"label":"","body":"x"}]""")
        assertTrue(
            "the refusal does not name the field or the row: \"$reason\"",
            reason.contains("label") && reason.contains("2"),
        )
    }

    @Test
    fun aMissingLabelIsRefusedTheSameWayAnEmptyOneIs() {
        // Missing and empty are the same defect — no identity — and must not
        // be told apart by whether the key happens to be present.
        val reason = reasonFor("""[{"body":"x","category":"path"}]""")
        assertTrue("the refusal does not name the field: \"$reason\"", reason.contains("label"))
    }

    @Test
    fun aDuplicateLabelIsRefusedAndTheReasonNamesTheLabel() {
        // Upstream's `Find` is byte-exact on the label, so a duplicate makes
        // one of the two rows permanently unreachable. Importing it would be a
        // catalog that silently cannot do what it says.
        val reason = reasonFor(
            """[{"label":"Quince jam","body":"a"},{"label":"Quince jam","body":"b"}]"""
        )
        assertTrue(
            "the refusal does not name the colliding label: \"$reason\"",
            reason.contains("Quince jam"),
        )
    }

    @Test
    fun somethingThatIsNotAnArrayIsRefused() {
        for (text in listOf("""{"label":"Quince jam"}""", "not json at all", "", "17")) {
            val reason = reasonFor(text)
            assertTrue(
                "the refusal of <$text> does not say what shape was expected: " +
                    "\"$reason\"",
                reason.contains("array"),
            )
        }
    }

    @Test
    fun aRowThatIsNotAnObjectIsRefused() {
        val reason = reasonFor("""[{"label":"Quince jam"},"a bare string"]""")
        assertTrue(
            "the refusal does not name the offending row: \"$reason\"",
            reason.contains("2"),
        )
    }

    @Test
    fun searchTermsThatIsNotAnArrayIsRefused() {
        // A string there is a different SCHEMA rather than a missing field, and
        // silently reading it as one term would import a row whose declared
        // interface is wrong — which shows up as a search that quietly misses.
        val reason = reasonFor("""[{"label":"Quince jam","search_terms":"fruit"}]""")
        assertTrue(
            "the refusal names neither the field nor the row: \"$reason\"",
            reason.contains("search_terms") && reason.contains("Quince jam"),
        )
    }

    @Test
    fun unknownKeysAreToleratedSoASnipSchemaAdditionCannotBrickThePhone() {
        // 🔴 Deliberate. This app must not be a gate on snip's schema
        // evolution: a release that adds a field would otherwise make every
        // import fail, with a message about a file that is perfectly good.
        assertEquals(
            listOf(Snippet("Quince jam", "boil", listOf("fruit"), "prompt")),
            SnipCatalog.parse(
                """
                [{"label":"Quince jam","body":"boil","search_terms":["fruit"],
                  "category":"prompt","shortcut":"qj","usage_count":4,
                  "nested":{"anything":[1,2,3]}}]
                """.trimIndent()
            ),
        )
    }

    @Test
    fun theOptionalFieldsDefaultToEmptyRatherThanFailing() {
        // Mirrors upstream's `json.Unmarshal` plus its validation, which checks
        // the label and nothing else. A row with only a label is a usable row:
        // it copies an empty body, which is strange but is what the file says.
        assertEquals(
            listOf(Snippet("Quince jam", "", emptyList(), "")),
            SnipCatalog.parse("""[{"label":"Quince jam"}]"""),
        )
    }

    @Test
    fun aMultiLineBodySurvivesARoundTripThroughTheStoredForm() {
        // The real catalog's long prompt snippets are multi-line, and the
        // persisted form goes back through this parser on every read — so a
        // newline mangled on the way out would corrupt the body the user
        // pastes, one import later.
        val body = "first line\nsecond line\n\n  indented \"quoted\" tail"
        val original = listOf(Snippet("Quince jam", body, listOf("a b"), "prompt"))
        assertEquals(original, SnipCatalog.parse(SnipCatalog.toJson(original).toString()))
    }

    /** The reason [SnipCatalog.parse] refuses [text], or a failing assertion. */
    private fun reasonFor(text: String): String = try {
        val parsed = SnipCatalog.parse(text)
        throw AssertionError("<$text> was ACCEPTED as $parsed; it must be refused")
    } catch (e: SnipCatalog.Invalid) {
        assertTrue("the refusal of <$text> carries no reason at all", e.reason.isNotBlank())
        e.reason
    }
}
