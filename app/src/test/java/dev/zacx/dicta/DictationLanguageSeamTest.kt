package dev.zacx.dicta

import android.os.ParcelFileDescriptor
import android.speech.RecognizerIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

/**
 * 🔴 The seam between the **two** engines that can transcribe a dictation, and
 * the one thing they must agree about: what language was spoken.
 *
 * ## The failure this exists to make loud
 *
 * Issue #7 gave the operator a dictation-language picker and wired it into the
 * remote request. It was never wired into the *on-device* recogniser: the
 * intent carried `EXTRA_LANGUAGE_MODEL` — a different knob, free-form vs
 * web-search — and no `EXTRA_LANGUAGE` at all, so `SpeechRecognizer` fell back
 * to the **device** locale. On a Spanish handset the remote path honoured a
 * choice of English while the fallback returned Spanish for the same English
 * speech, and which one you got depended on whether the server was reachable.
 *
 * That is why this is a *seam* test rather than two component tests. Both
 * halves were individually correct: `SttLanguage.resolve` had its own passing
 * suite, and the recogniser intent built exactly the intent it meant to build.
 * Nothing loaded both surfaces at once, so nothing could see that only one of
 * them consulted the setting. **Ask which surface your fixture does not load.**
 *
 * ## Why the fixtures are neither "en" nor the device locale
 *
 * [DEVICE] is Spanish and [CHOSEN] is French, and the expected answer is
 * French — so it equals neither the device locale nor [SttLanguage.DEFAULT].
 * A mutant that hardcodes `"en"`, one that returns `fromLocale(locale)`, and
 * one that returns the constant default are all distinguishable. A fixture
 * using English for the choice could not tell any of them apart while staying
 * green.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictationLanguageSeamTest {

    @Test
    fun anExplicitChoiceBeatsTheDeviceLocale() {
        assertEquals(
            "the operator's picker must win over the handset's locale — this " +
                "is the whole of issue #7",
            CHOSEN,
            SttLanguage.resolve(CHOSEN, DEVICE),
        )
    }

    @Test
    fun theOwnMicIntentCarriesTheResolvedLanguage() {
        val intent = RecognizerIntents.ownMicIntent(CHOSEN)
        assertEquals(
            "the recogniser that opens the microphone itself must be told the " +
                "language; without EXTRA_LANGUAGE it follows the DEVICE locale " +
                "and silently disagrees with the remote request",
            CHOSEN,
            intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE),
        )
    }

    @Test
    fun theSharedSourceIntentCarriesTheResolvedLanguage() {
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            val intent = RecognizerIntents.sharedSourceIntent(pipe[0], CHOSEN)
            assertEquals(
                "the pipe-fed recogniser is the one that serves a normal " +
                    "bubble dictation, so this is the path the operator " +
                    "actually hits",
                CHOSEN,
                intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE),
            )
        } finally {
            pipe[0].close()
            pipe[1].close()
        }
    }

    @Test
    fun theSystemVoiceUiIntentCarriesTheResolvedLanguage() {
        val intent = RecognizerIntents.systemVoiceUiIntent(CHOSEN, "speak now")
        assertEquals(
            "the permission-free fallback is a third capture path and was " +
                "built by hand at its call site, which is exactly how it was " +
                "missed the first time",
            CHOSEN,
            intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE),
        )
    }

    /**
     * 🔴 `EXTRA_LANGUAGE_MODEL` being present is what made the omission read as
     * handled. Pin that the two are not the same knob, so a future reader
     * cannot delete `EXTRA_LANGUAGE` on the grounds that the language is
     * "already set".
     */
    @Test
    fun theLanguageModelIsNotTheLanguage() {
        val intent = RecognizerIntents.ownMicIntent(CHOSEN)
        assertEquals(
            "free-form vs web-search — this knob never carried a language",
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL),
        )
        assertFalse(
            "the two extras must not be the same string, or the assertion " +
                "above would pass for the wrong reason",
            RecognizerIntent.EXTRA_LANGUAGE == RecognizerIntent.EXTRA_LANGUAGE_MODEL,
        )
    }

    /**
     * 🔴 The structural half: every intent in main sources that asks for
     * speech recognition must set a language.
     *
     * Fails when the set **grows** — a fourth capture path built by hand is the
     * defect this whole file is about, and it type-checks perfectly.
     */
    @Test
    fun everyRecogniserIntentInMainSourcesSetsTheLanguage() {
        val offenders = sourceDir().listFiles { f -> f.name.endsWith(".kt") }
            .orEmpty()
            .filter { f ->
                val src = stripComments(f.readText())
                BUILDS_RECOGNISER_INTENT.containsMatchIn(src) &&
                    !SETS_LANGUAGE.containsMatchIn(src)
            }
            .map { it.name }
            .sorted()

        assertEquals(
            "a file builds an ACTION_RECOGNIZE_SPEECH intent without ever " +
                "setting EXTRA_LANGUAGE, so that capture path follows the " +
                "DEVICE locale while the remote request honours the operator's " +
                "picker. Route it through RecognizerIntents rather than adding " +
                "the extra by hand — and note this scan cannot tell whether " +
                "the value you set is the RESOLVED one, only that you set " +
                "something.",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * 🔴 Positive control for the scan above. Until this passes, that empty
     * offender list is a fact about a regex and not about the source tree —
     * a pattern that matches nothing reports "no offenders" for a tree full
     * of them.
     */
    @Test
    fun theScanSeesBothAnOffenderAndACompliantFile() {
        val offender = """
            class X {
                fun go() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, FREE_FORM)
                }
            }
        """.trimIndent()
        assertTrue(
            "the scan must SEE an intent construction, or it can never report " +
                "an offender",
            BUILDS_RECOGNISER_INTENT.containsMatchIn(stripComments(offender)),
        )
        assertFalse(
            "EXTRA_LANGUAGE_MODEL must NOT satisfy the language check — that " +
                "confusion is the original bug, and a pattern matching the " +
                "prefix would call the offender compliant",
            SETS_LANGUAGE.containsMatchIn(stripComments(offender)),
        )

        val compliant = offender.replace(
            "putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, FREE_FORM)",
            "putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)",
        )
        assertTrue(
            "a file that does set the language must read as compliant",
            SETS_LANGUAGE.containsMatchIn(stripComments(compliant)),
        )

        assertFalse(
            "a comment mentioning the action must not count as building one",
            BUILDS_RECOGNISER_INTENT.containsMatchIn(
                stripComments("// ACTION_RECOGNIZE_SPEECH is built in RecognizerIntents")
            ),
        )
    }

    /**
     * 🔴 The consolidation guard. [SttLanguage.resolve] must be spelled in
     * exactly one place, because a predicate open-coded at N sites is wrong at
     * N−1 of them — and here it literally was: the remote call site resolved
     * the preference and the recogniser never did.
     */
    @Test
    fun theLanguageIsResolvedInExactlyOnePlace() {
        val resolvers = sourceDir().listFiles { f -> f.name.endsWith(".kt") }
            .orEmpty()
            .filter { f -> CALLS_RESOLVE.containsMatchIn(stripComments(f.readText())) }
            .map { it.name }
            .sorted()

        assertEquals(
            "SttLanguage.resolve must be called from SttSettings.dictationLanguage " +
                "and nowhere else. A second call site is a second place that can " +
                "be forgotten when a third capture path appears.",
            listOf("SttSettings.kt"),
            resolvers,
        )
    }

    @Test
    fun theResolveScanDistinguishesACallFromTheDeclaration() {
        // Positive control: the pattern must fire on a real call...
        assertTrue(
            CALLS_RESOLVE.containsMatchIn(
                stripComments("val x = SttLanguage.resolve(language, locale)")
            ),
        )
        // ...and must not fire on the declaration itself, or SttLanguage.kt
        // would always be listed and the ledger would pin nothing.
        assertFalse(
            "the declaration in SttLanguage.kt is not a call site",
            CALLS_RESOLVE.containsMatchIn(
                stripComments("    fun resolve(override: String?, locale: Locale): String {")
            ),
        )
    }

    private fun sourceDir(): File {
        val dir = listOf(
            File("app/src/main/java/dev/zacx/dicta"),
            File("src/main/java/dev/zacx/dicta"),
            File("../app/src/main/java/dev/zacx/dicta"),
        ).firstOrNull { it.isDirectory }
        assertTrue(
            "could not locate the source directory from ${File(".").absolutePath} " +
                "— refusing to report a pass on files that were never read",
            dir != null,
        )
        return dir!!
    }

    private companion object {
        /** Spanish handset — the operator's real configuration. */
        val DEVICE: Locale = Locale.forLanguageTag("es-CO")

        /**
         * Deliberately NOT "en": the expected value must differ from
         * [SttLanguage.DEFAULT] *and* from [DEVICE], so a mutant returning
         * either one is caught.
         */
        const val CHOSEN: String = "fr"

        val BUILDS_RECOGNISER_INTENT =
            Regex("""Intent\s*\(\s*RecognizerIntent\.ACTION_RECOGNIZE_SPEECH""")

        /**
         * `EXTRA_LANGUAGE` **not** followed by an identifier character, so
         * `EXTRA_LANGUAGE_MODEL` and `EXTRA_LANGUAGE_PREFERENCE` do not
         * satisfy it. The whole bug was those two reading as the same thing.
         */
        val SETS_LANGUAGE = Regex("""EXTRA_LANGUAGE(?![A-Z_])""")

        /** A call to `SttLanguage.resolve(`, never the `fun resolve(` that declares it. */
        val CALLS_RESOLVE = Regex("""SttLanguage\.resolve\s*\(""")

        fun stripComments(src: String): String = src
            .replace(Regex("//.*"), "")
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
    }
}
