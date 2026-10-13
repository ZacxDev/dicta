package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 🔴 A ledger of **who constructs a [DictationSession]**.
 *
 * ## The failure this exists to make loud
 *
 * One `DictationSession` means one `AudioRecord`. Two means Android's
 * concurrent-capture policy decides, and that policy is **enforced by silently
 * feeding the loser exact zeros** — `startRecording()` succeeds, `read()`
 * returns the full byte count, every byte is 0. There is no exception and no
 * error callback. The symptom that reaches the user is *"the transcript came
 * back empty"*, which reads as a server bug and gets investigated as one.
 *
 * Under the inline-dictation flow the service is already recording by the time
 * the overlay opens, so `DictateActivity.onCreate`'s unconditional
 * `startDictation()` would construct exactly that second session. The guard is
 * therefore not "is the count small" but "is the SET what we decided":
 *
 *  * `DictationSession.kt` — its own declaration and, in the same file, nothing
 *    that constructs a second one.
 *  * `BubbleService.kt` — owns the session for the bubble flow.
 *  * `DictateActivity.kt` — still owns one for the tile / notification /
 *    in-app entry points, which open the card *before* any capture exists.
 *
 * It fails when that set **grows or shrinks**, so a new recording surface
 * forces a deliberate decision here rather than inheriting a silent zero-fill.
 *
 * Watched red on the pre-change tree, where the set was
 * `[DictateActivity.kt, DictationSession.kt]` — `BubbleService.kt` constructed
 * nothing because the bubble only ever started an activity.
 */
class DictationSessionLedgerTest {

    @Test
    fun onlyTheDeclaredOwnersConstructADictationSession() {
        val constructors = sourceDir().listFiles { f -> f.name.endsWith(".kt") }
            .orEmpty()
            .filter { f -> CONSTRUCTS_SESSION.containsMatchIn(stripComments(f.readText())) }
            .map { it.name }
            .sorted()

        assertEquals(
            "the set of files constructing a DictationSession changed. Every " +
                "session is one AudioRecord, and two concurrent sessions do NOT " +
                "error — Android feeds one of them exact zeros, which surfaces " +
                "as an empty transcript and gets blamed on the server. If you " +
                "added a recording surface, make sure it cannot run while " +
                "another session holds the mic, then add it here deliberately.",
            listOf("BubbleService.kt", "DictateActivity.kt"),
            constructors,
        )
    }

    @Test
    fun theLedgerPatternCanSeeEveryCallSpellingAndNoneOfTheNonCalls() {
        // 🔴 Positive control. The ledger above is a scan: until this passes,
        // its result is a fact about the REGEX and not about the source tree.
        // DictateLaunchTest records the precise way the sibling guard was wrong
        // — its pattern matched the literal `Intent(this, …` and so could not
        // see `Intent(context, …`, leaving it blind in the one file it existed
        // to protect. This pattern is therefore insensitive to how the
        // arguments are spelled, and that insensitivity is pinned here.
        val calls = """
            class X {
                fun a() = DictationSession(context = this, settings = s, callbacks = c)
                fun b() = DictationSession(ctx, s, c)
                fun c() = DictationSession (ctx, s, c)
                fun d() = DictationSession(
                    context = appContext,
                )
            }
        """.trimIndent()
        assertEquals(
            "the pattern must match a construction however its receiver and " +
                "arguments are spelled",
            4,
            CONSTRUCTS_SESSION.findAll(stripComments(calls)).count(),
        )

        // And must NOT fire on the things that merely name the type.
        val nonCalls = listOf(
            "// DictationSession(ctx, s, c) <- only a comment",
            "class DictationSession(",
            "private var session: DictationSession? = null",
            "callbacks: DictationSession.Callbacks",
            "internal fun uiCallbacks(): DictationSession.Callbacks = SessionCallbacks()",
        )
        for (sample in nonCalls) {
            assertFalse(
                "the pattern fired on something that does not construct a " +
                    "session, so the ledger would count files that own none: " +
                    "<$sample>",
                CONSTRUCTS_SESSION.containsMatchIn(stripComments(sample)),
            )
        }
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
        /**
         * A `DictationSession(...)` construction, for any argument spelling and
         * any incidental whitespace or line break after the paren.
         *
         * The negative lookbehind for `class ` is what keeps the type's own
         * primary-constructor declaration in `DictationSession.kt` out of the
         * ledger — without it the file would always be a "constructor" and the
         * assertion would be pinning a constant.
         */
        val CONSTRUCTS_SESSION = Regex("""(?<!class )DictationSession\s*\(""")

        fun stripComments(src: String): String = src
            .replace(Regex("//.*"), "")
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
    }
}
