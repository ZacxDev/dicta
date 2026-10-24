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
 *  * `BubbleService.kt` — owns the session for the bubble flow.
 *  * `DictateActivity.kt` — still owns one for the notification / in-app
 *    entry points, which open the card *before* any capture exists. (The QS
 *    tile was listed among them; it opens no card now.)
 *
 * It fails when that set **grows or shrinks**, so a new recording surface
 * forces a deliberate decision here rather than inheriting a silent zero-fill.
 *
 * 🔴 **The scope of what this ledger can promise**, stated precisely because an
 * earlier version of this comment overstated it.
 *
 * It said the mutual exclusion "now exists… whether or not whoever added it read
 * this file". That was **false for any `SpeechRecognizer`-based opener**, and
 * such an opener is not even a member here, because this ledger greps for
 * `DictationSession(`. `DictateActivity.startPhoneOnlyDictation` was exactly
 * that: it opened the microphone through `SpeechHelper` with no acquire at all,
 * and nothing in this file could see it.
 *
 * What is true now: `DictationSession.start()` acquires [MicrophoneOwner] and
 * **fails** if it is already held, *and* the phone-only recogniser acquires it
 * too, so the two openers exclude each other in both directions
 * ([MicOpenerSeamTest] is where that is pinned, and
 * `MicrophoneOwnershipTest` where each surface's refusal is). What this ledger
 * does is narrower and still worth having: it makes the **set of openers** a
 * declared decision, so a new one cannot arrive unnoticed. It does not and
 * cannot check that a new opener acquires anything — only a behavioural test
 * of that opener can.
 *
 * 🔴 Hence [theDeclaredSetOfMicrophoneOpenersIsUnchanged] below, which covers
 * openers rather than only session constructions: a file that constructs a
 * [MicCapture] or a bare [SpeechHelper] opens a microphone just as surely as
 * one that constructs a session, and the one that bit us was of the second kind.
 *
 * 🔴 `DictationSession.kt` is deliberately **not** a member, even though it
 * contains the text `DictationSession(`: that occurrence is the type's own
 * primary-constructor declaration, and [CONSTRUCTS_SESSION]'s `(?<!class )`
 * lookbehind excludes it. Do not "reconcile" this list by adding the file — the
 * assertion would go red. Re-measured by running the regex over both trees:
 * `origin/main` yields `[DictateActivity.kt]` and `HEAD` yields
 * `[BubbleService.kt, DictateActivity.kt]`. That difference is what the guard
 * was watched red on: before the bubble flow, `BubbleService.kt` constructed
 * nothing, because the bubble only ever started an activity.
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
                "added a recording surface, it is refused at runtime by " +
                "MicrophoneOwner if another session already holds the mic — " +
                "check that its start() failure is REPORTED to the user, then " +
                "add it here deliberately.",
            listOf("BubbleService.kt", "DictateActivity.kt"),
            constructors,
        )
    }

    /**
     * 🔴 The wider ledger: every file that constructs **a microphone client**.
     *
     * Three types open one, directly or by handing the platform a descriptor:
     * [DictationSession] (which owns a [MicCapture]), [MicCapture] itself (the
     * `AudioRecord`), and [SpeechHelper] (where `SpeechRecognizer` opens the
     * mic itself unless it is given a shared source). The set:
     *
     *  * `BubbleService.kt` — the bubble's session.
     *  * `DictateActivity.kt` — the card's session, **and** the phone-only
     *    `SpeechHelper` that opens the microphone itself. That second opener is
     *    the one the session-only ledger above could not see.
     *  * `DictationSession.kt` — its own `MicCapture`, and the `SpeechHelper`
     *    it feeds from that capture's pipe rather than from the microphone.
     *
     * 🔴 Membership is **not** evidence that a member acquires
     * [MicrophoneOwner] — a text scan cannot check that, and claiming it could
     * is how the overstatement in this file's header happened. Growth of this
     * set is a prompt to go and write the behavioural test, not a substitute
     * for one.
     */
    @Test
    fun theDeclaredSetOfMicrophoneOpenersIsUnchanged() {
        val openers = sourceDir().listFiles { f -> f.name.endsWith(".kt") }
            .orEmpty()
            .filter { f -> OPENS_MICROPHONE.containsMatchIn(stripComments(f.readText())) }
            .map { it.name }
            .sorted()

        assertEquals(
            "the set of files that construct a microphone client changed. " +
                "Every one of them must either acquire MicrophoneOwner before " +
                "it opens the mic, or be fed from a client that does (the " +
                "SpeechHelper inside DictationSession is given MicCapture's " +
                "pipe, not the microphone). Two concurrent openers do NOT " +
                "error — Android feeds one of them exact zeros, which surfaces " +
                "as an empty or invented transcript. Add yours here, then go " +
                "write the behavioural test: this scan cannot tell whether it " +
                "acquires anything.",
            listOf("BubbleService.kt", "DictateActivity.kt", "DictationSession.kt"),
            openers,
        )
    }

    @Test
    fun theOpenerPatternSeesEveryClientAndNoneOfTheMereMentions() {
        // 🔴 Positive control, for the same reason the session pattern has one:
        // until this passes, the ledger above is a fact about a regex.
        val calls = """
            class X {
                fun a() = DictationSession(context = this, settings = s, callbacks = c)
                fun b() = MicCapture(MicListener(), file)
                fun c() = SpeechHelper (ctx, listener, sharedSource = source)
                fun d() = SpeechHelper(
                    this,
                )
            }
        """.trimIndent()
        assertEquals(
            "the pattern must see all three microphone-client types, however " +
                "their arguments are spelled",
            4,
            OPENS_MICROPHONE.findAll(stripComments(calls)).count(),
        )

        val nonCalls = listOf(
            "// SpeechHelper(ctx, l) <- only a comment",
            "class SpeechHelper(",
            "class MicCapture(",
            "private var speech: SpeechHelper? = null",
            "private val listener: MicCapture.Listener",
            "object : SpeechHelper.Listener {",
        )
        for (sample in nonCalls) {
            assertFalse(
                "the pattern fired on something that opens no microphone, so " +
                    "the ledger would count files that have none: <$sample>",
                OPENS_MICROPHONE.containsMatchIn(stripComments(sample)),
            )
        }
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

        /**
         * A construction of any of the three microphone-client types, with the
         * same `class ` lookbehind so each type's own declaration does not make
         * its file a member and pin a constant.
         *
         * `SpeechHelper.Listener` and `MicCapture.Listener` are excluded by the
         * `\s*\(` requirement — a qualified type reference has a `.` where the
         * paren would be — and the `private val listener: MicCapture.Listener`
         * shape by the same token. Both are pinned in
         * [theOpenerPatternSeesEveryClientAndNoneOfTheMereMentions].
         *
         * 🔴 **SCOPE — read this before trusting the ledger.** This pattern is
         * a list of three *names*, so the ledger only sees an opener that goes
         * through one of them. **If you add a microphone opener that does not,
         * this guard will not catch you.** Specifically invisible to it:
         *
         * - a bare `AudioRecord(...)`, `AudioRecord.Builder()`,
         *   `MediaRecorder(...)` or
         *   `SpeechRecognizer.createSpeechRecognizer(...)` anywhere in the
         *   tree — in a new file, or in one already on the ledger;
         * - anything inside `MicCapture.kt` itself, which the `(?<!class )`
         *   lookbehind makes *structurally unmemberable*: the only
         *   `MicCapture(` occurrence in that file is its own class
         *   declaration, so the file can never join the ledger however many
         *   microphones it opens;
         * - an opener reached through a new wrapper type, a factory or a
         *   lambda — the ledger matches the three names, not the behaviour.
         *
         * 🔴 Not hypothetical. Measured over every `.kt` file in
         * `app/src/main/java/dev/zacx/dicta`: the only two real platform
         * openers in the tree are `AudioRecord.Builder` in **`MicCapture.kt`**
         * and `createSpeechRecognizer` in **`SpeechHelper.kt`**, and *neither
         * file is a ledger member*. The ledger's three members
         * (`BubbleService.kt`, `DictateActivity.kt`, `DictationSession.kt`) are
         * the files that construct those two wrappers, not the files that touch
         * the microphone. So the guard tracks the *call sites of our own
         * abstractions* — which is what it is for — and says nothing about where
         * a microphone is actually opened.
         *
         * That blind spot is the same shape as the second mic opener this PR
         * missed: one nothing structural was watching. Widening the pattern to
         * the platform types is the real fix and is **not done here** — it
         * needs its own positive and negative controls, and a decision about
         * `MicCapture.kt`. Until then: if you open a microphone, register the
         * opener with [MicrophoneOwner] by hand and do not expect a red test
         * to remind you.
         */
        val OPENS_MICROPHONE =
            Regex("""(?<!class )(DictationSession|MicCapture|SpeechHelper)\s*\(""")

        fun stripComments(src: String): String = src
            .replace(Regex("//.*"), "")
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
    }
}
