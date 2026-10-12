package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Guards the one documented **HTTP 500**.
 *
 * ## What this is
 *
 * A **regression test**, not an invariant guard. The failing behaviour is
 * concrete and was reachable by the obvious implementation: the service hands
 * `language` straight to a processor that raises `ValueError` *outside* its
 * try/except, so any value outside the accepted set is an unhandled 500 with
 * a traceback — and `Locale.getDefault().toLanguageTag()` returns `"en-US"`,
 * which is outside the set. A client written the natural way therefore 500s
 * on every request from a US-English phone, i.e. always.
 *
 * The red/green matrix is in the PR body: with [SttLanguage.fromTag] replaced
 * by `tag ?: "en"` — the naive implementation — [enUsBecomesBareEn],
 * [enGbUnderscoreBecomesBareEn] and [ptBrBecomesBarePt] all fail.
 *
 * ## Why the expectations are literals
 *
 * Every assertion below names the exact string expected, and the allowlist is
 * spelled out again in [theAllowlistIsExactlyTheServersSet] rather than read
 * from [SttLanguage.SUPPORTED]. Deriving the expectation from the
 * implementation would make the test pass for any allowlist at all, including
 * an empty one — and the whole value of the test is that it pins the *server's*
 * contract, which the client cannot see.
 */
class SttLanguageTest {

    /** The service's accepted set, re-typed from the contract. */
    private val serverAccepts = setOf(
        "ar", "de", "el", "en", "es", "fr", "it",
        "ja", "ko", "nl", "pl", "pt", "vi", "zh",
    )

    @Test
    fun theAllowlistIsExactlyTheServersSet() {
        // Not `assertEquals(SttLanguage.SUPPORTED, SttLanguage.SUPPORTED)`:
        // the left side is typed out above from the service contract, so a
        // well-meaning addition to the client's set turns this red and makes
        // someone go and check the server.
        assertEquals(serverAccepts, SttLanguage.SUPPORTED)
    }

    // --- the four cases the brief names ------------------------------------

    @Test
    fun enUsBecomesBareEn() {
        // 🔴 The exact value Locale.getDefault().toLanguageTag() returns on
        // the target handset. Sending it unmapped is the 500.
        assertEquals("en", SttLanguage.fromTag("en-US"))
    }

    @Test
    fun enGbUnderscoreBecomesBareEn() {
        // The underscore spelling arrives from Locale.toString() rather than
        // toLanguageTag().
        //
        // 🔴 This assertion ALONE is vacuous, and a mutation sweep proved it:
        // a mapper that splits only on '-' leaves "en_GB" whole, fails the
        // allowlist, and degrades to DEFAULT — which is "en", the very value
        // this test expects. The fixture can only ever produce the constant's
        // own value, so it cannot see the bug. See
        // [ptBrUnderscoreBecomesBarePt] for the case that actually detects it;
        // this one is kept because en_GB is the real-world input.
        assertEquals("en", SttLanguage.fromTag("en_GB"))
    }

    @Test
    fun ptBrUnderscoreBecomesBarePt() {
        // 🔴 The control that makes the underscore handling observable: here
        // the correct answer ("pt") CANNOT equal DEFAULT ("en"), so a mapper
        // that only splits on '-' degrades to "en" and this goes red. Added
        // after the sweep scored `lang_split_dash_only` as SURVIVED against
        // the en_GB case above.
        assertEquals("pt", SttLanguage.fromTag("pt_BR"))
        assertEquals("de", SttLanguage.fromTag("de_AT"))
        assertEquals("zh", SttLanguage.fromTag("zh_Hans_CN"))
    }

    @Test
    fun ptBrBecomesBarePt() {
        // Distinct from "en" on purpose: an implementation that always
        // returned the default would pass the en-US and en_GB cases while
        // being completely wrong.
        assertEquals("pt", SttLanguage.fromTag("pt-BR"))
    }

    @Test
    fun anUnsupportedLanguageDegradesToTheDefaultRatherThanBeingPassedThrough() {
        // hi-IN: Hindi is not in the server's set. The wrong answer here is
        // not "hi" (a 500) and not "hi-IN" (also a 500) — it is "en", which
        // is a wrong transcript the user can see and recover from.
        assertEquals("en", SttLanguage.fromTag("hi-IN"))
        assertEquals("en", SttLanguage.fromTag("hi"))
    }

    // --- the shapes a platform locale can take -----------------------------

    @Test
    fun aBareSupportedCodePassesThrough() {
        assertEquals("de", SttLanguage.fromTag("de"))
        assertEquals("ja", SttLanguage.fromTag("ja"))
    }

    @Test
    fun casingIsNormalised() {
        assertEquals("pt", SttLanguage.fromTag("PT-br"))
        assertEquals("zh", SttLanguage.fromTag("ZH"))
    }

    @Test
    fun aScriptSubtagIsDropped() {
        // zh-Hans-CN has three subtags; only the first is a language.
        assertEquals("zh", SttLanguage.fromTag("zh-Hans-CN"))
    }

    @Test
    fun nullAndBlankBecomeTheDefault() {
        assertEquals("en", SttLanguage.fromTag(null))
        assertEquals("en", SttLanguage.fromTag(""))
        assertEquals("en", SttLanguage.fromTag("   "))
        assertEquals("en", SttLanguage.fromTag("-US"))
    }

    @Test
    fun everyResultIsInTheAllowlist() {
        // The property that actually protects the server, checked over a set
        // of inputs chosen to include the shapes that broke it.
        val inputs = listOf(
            "en-US", "en_GB", "pt-BR", "hi-IN", "zh-Hans-CN", "fr-CA",
            "es_419", "qqq", "", "   ", "-", "_", "x", "EN-us", "nl-BE",
        )
        for (input in inputs) {
            val mapped = SttLanguage.fromTag(input)
            assertTrue(
                "fromTag(\"$input\") returned \"$mapped\", which is not a " +
                    "bare code the server accepts",
                mapped in serverAccepts,
            )
        }
        assertTrue(SttLanguage.fromTag(null) in serverAccepts)
    }

    @Test
    fun fromLocaleGoesThroughTheSameAllowlist() {
        // Locale.US is the real call site's value on the target device.
        assertEquals("en", SttLanguage.fromLocale(Locale.US))
        assertEquals("en", SttLanguage.fromLocale(Locale.UK))
        assertEquals("fr", SttLanguage.fromLocale(Locale.FRANCE))
        assertEquals("ja", SttLanguage.fromLocale(Locale.JAPAN))
        // Not in the server's set.
        assertEquals("en", SttLanguage.fromLocale(Locale.forLanguageTag("hi-IN")))
    }

    @Test
    fun noResultEverContainsARegionSeparator() {
        // The failure mode stated as a property: a '-' or '_' in the value
        // means a tag escaped, which is the 500.
        val inputs = listOf("en-US", "en_GB", "pt-BR", "zh-Hans-CN", "hi-IN")
        for (input in inputs) {
            val mapped = SttLanguage.fromTag(input)
            assertTrue(
                "fromTag(\"$input\") = \"$mapped\" still carries a separator",
                !mapped.contains('-') && !mapped.contains('_'),
            )
        }
    }
}
