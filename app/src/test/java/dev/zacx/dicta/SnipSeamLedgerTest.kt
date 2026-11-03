package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 🔴 The **seams** of the snip feature, asserted as RELATIONSHIPS rather than
 * as components.
 *
 * ## Why a file of its own, when every piece is already tested
 *
 * Because "verified in isolation" is where the defect hides. [SnipMatcher],
 * [SnipCatalog], [SnipClip] and [SnipPickerActivity] each have their own
 * hermetic tests, and all four can be green while the feature is broken
 * *together* — a second consumer of the effect doing its own thing, a
 * hand-built picker intent that reproduces #11, a `taskAffinity` that drifted
 * out of step with the manifest, a snip file that reached for the microphone.
 * None of those is visible to a test scoped to one surface, because no such
 * test ever builds the combined state.
 *
 * ## 🔴 Each ledger fails when its set GROWS **or** SHRINKS
 *
 * Growth is the hazard (a second consumer, a second constructor); shrinkage
 * matters as much, because a set that shrank to empty means the wiring is gone
 * and every component test here would still be green.
 *
 * ## 🔴 And each is pinned on STRUCTURE, not on words
 *
 * Nothing below matches a comment, a string name or a button label: the
 * consumer is identified by the `when`-branch that carries
 * `Effect.OpenSnipPicker` out, the constructor by a literal
 * `Intent(_, SnipPickerActivity::class.java)`. Matching runs over
 * comment-stripped, whitespace-collapsed source for
 * [StartSurfaceLedgerTest]'s two measured reasons: prose naming a construction
 * must not satisfy a scan, and a `ktfmt` reformat that wraps a call across two
 * lines must not make one go spuriously red.
 *
 * ## What it cannot see, stated so the clean result is not over-read
 *
 * A textual ledger tracks call sites of a spelling. An effect dispatched
 * through a wrapper, a reflective `startActivity`, or a `Class` handed between
 * files would all be invisible to it — which is why
 * [BubbleSnipRevealTest.tappingTheSnipButtonOpensThePickerThroughSnipLaunch]
 * drives the real click to a real intent. A structural ledger type-checks past
 * a wrong argument; the behavioural case is what does not.
 */
class SnipSeamLedgerTest {

    // --- the effect's two ends ----------------------------------------------

    @Test
    fun exactlyOneSiteCarriesOutOpenSnipPickerAndItIsTheServicesEffectLoop() {
        val consumers = sitesOf(CONSUMES_OPEN_SNIP_PICKER, skip = setOf("BubbleDictation.kt"))
        assertEquals(
            "Effect.OpenSnipPicker is carried out from $consumers. Exactly one " +
                "site may open the picker, and it must be the service's " +
                "exhaustive effect loop — a second handler is a second thing " +
                "the snip button silently does, and `carryOut`'s exhaustive " +
                "`when` is what forces a new effect to be decided about rather " +
                "than ignored.",
            listOf("BubbleService.kt#carryOut"),
            consumers,
        )
    }

    @Test
    fun exactlyOneSiteEmitsOpenSnipPickerAndItIsTheMachinesSnipArm() {
        val emitters = sitesOf(EMITS_OPEN_SNIP_PICKER, skip = emptySet())
        assertEquals(
            "Effect.OpenSnipPicker is emitted from $emitters. The decision to " +
                "open the picker belongs to the state machine's snip arm and " +
                "nowhere else: a second emitter is a second control that opens " +
                "it, bypassing the IDLE-only guard and the HideHistory prefix.",
            listOf("BubbleDictation.kt#onSnipTapped"),
            emitters,
        )
    }

    @Test
    fun exactlyOneSiteRaisesSnipTappedAndItIsTheButtonInTheReveal() {
        val raisers = sitesOf(RAISES_SNIP_TAPPED, skip = setOf("BubbleDictation.kt"))
        assertEquals(
            "Event.SnipTapped is raised from $raisers. One control, in the " +
                "reveal. A second raiser is a second entry point to the " +
                "picker — a tile, a notification action, a widget — each one " +
                "individually reasonable, which is how a surface set grows.",
            listOf("BubbleService.kt#buildWindow"),
            raisers,
        )
    }

    // --- the intent, and its manifest pairing -------------------------------

    @Test
    fun onlySnipLaunchConstructsAPickerIntent() {
        val constructors = mainSources()
            .filter { (_, src) -> CONSTRUCTS_PICKER_INTENT.containsMatchIn(stripAndNormalise(src)) }
            .map { it.first }
            .sorted()
        assertEquals(
            "the set of files hand-building a SnipPickerActivity intent " +
                "changed: $constructors. Every launch must go through " +
                "SnipLaunch, or it will repeat issue #11 — FLAG_ACTIVITY_NEW_TASK " +
                "without the matching taskAffinity puts the translucent picker " +
                "on top of MainActivity instead of over the app the user is in.",
            listOf("SnipLaunch.kt"),
            constructors,
        )
    }

    @Test
    fun thePickersTaskAffinityMatchesTheManifestAndIsNotTheCards() {
        val element = pickerManifestElement()
        val declared = Regex("""android:taskAffinity="([^"]*)"""")
            .find(element)
            ?.groupValues
            ?.get(1)
        assertEquals(
            "SnipPickerActivity's android:taskAffinity does not equal " +
                "SnipLaunch.PICKER_TASK_AFFINITY. They live in different files " +
                "and a drift between them restores issue #11 SILENTLY: the " +
                "flag is still there, the affinity falls back to the " +
                "application default, and the picker renders over Dicta's own " +
                "UI.",
            SnipLaunch.PICKER_TASK_AFFINITY,
            declared,
        )
        assertTrue(
            "the affinity is empty, which means \"no affinity to anything\" " +
                "and can hand out a fresh task per launch — which fights " +
                "launchMode=\"singleTop\"",
            SnipLaunch.PICKER_TASK_AFFINITY.isNotEmpty(),
        )
        assertNotEquals(
            "the picker shares the transcription card's task affinity. With " +
                "singleTop on both, opening the picker over an open card " +
                "stacks them and Back walks through a surface the user never " +
                "opened.",
            DictateLaunch.OVERLAY_TASK_AFFINITY,
            SnipLaunch.PICKER_TASK_AFFINITY,
        )
    }

    @Test
    fun thePickerIsNotExportedAndDeclaresNoIntentFilter() {
        // 🔴 The operator's decision over issue #38's share-sheet wording:
        // ZacxDev/dicta is a PUBLIC app, so an <intent-filter> that accepts a
        // catalog — or anything else — is an entry point every installed app
        // can invoke. The element being self-closing IS the no-filter claim:
        // a filter would have to be a child, so it could not be.
        val element = pickerManifestElement()
        assertTrue(
            "SnipPickerActivity is not exported=\"false\": $element",
            element.contains("""android:exported="false""""),
        )
        assertTrue(
            "the SnipPickerActivity element is not self-closing, so it has " +
                "children — and the only children an <activity> takes are " +
                "<intent-filter> and <meta-data>. Declaring a filter makes " +
                "this activity reachable by any app on the device: $element",
            element.trimEnd().endsWith("/>"),
        )
    }

    // --- the microphone, which the snip flow must never reach ---------------

    /**
     * Every production file this feature adds.
     *
     * 🔴 A LEDGER, so the microphone scan below cannot quietly stop covering a
     * file. A set of names that is merely *checked against what exists* would
     * grow silently with the next snip source file, and the one thing this
     * guard must not do is read as coverage while providing none for the newest
     * file. `BubbleService.kt`, `BubbleDictation.kt` and `SettingsActivity.kt`
     * are deliberately NOT in it: they legitimately own microphone paths of
     * their own, and the claim being made is about the snip flow's own files.
     */
    private val snipSources = listOf(
        "SnipCatalog.kt",
        "SnipClip.kt",
        "SnipLaunch.kt",
        "SnipMatcher.kt",
        "SnipPickerActivity.kt",
        "SnipRow.kt",
        "SnipStore.kt",
        "Snippet.kt",
    )

    @Test
    fun theSnipFlowsOwnFilesAreTheLedgeredSet() {
        assertEquals(
            "the set of Snip* production files changed. Add it to " +
                "`snipSources` so the microphone scan below actually covers " +
                "it — a scan over a stale list reads as coverage while " +
                "providing none for the newest file, which is worse than no " +
                "scan at all.",
            snipSources,
            mainSources().map { it.first }.filter { it.startsWith("Snip") }.sorted(),
        )
    }

    @Test
    fun noFileInTheSnipFlowNamesAMicrophoneOrCaptureSymbol() {
        val found = mutableListOf<String>()
        for ((name, src) in mainSources().filter { it.first in snipSources }) {
            val normalised = stripAndNormalise(src)
            for (symbol in MIC_SYMBOLS) {
                if (Regex(Regex.escape(symbol) + """\b""").containsMatchIn(normalised)) {
                    found += "$name names $symbol"
                }
            }
        }
        assertEquals(
            "a file in the snip flow reaches a microphone or capture symbol. " +
                "Issue #38 is explicit that opening the picker is not a " +
                "dictation start: the picker reads the clipboard and writes " +
                "it, and nothing more.\n" + found.joinToString("\n"),
            emptyList<String>(),
            found,
        )
        assertTrue(
            "the scan read no files at all, so its empty result is a fact " +
                "about the file filter rather than about the sources",
            mainSources().count { it.first in snipSources } == snipSources.size,
        )
    }

    // --- controls for every instrument above --------------------------------

    @Test
    fun everyLedgerPatternMatchesSomethingAndIgnoresComments() {
        // 🔴 Until this passes, every result above is a fact about a regex. An
        // empty match set and a pattern that cannot match are the same
        // observation.
        val sample = stripAndNormalise(
            """
            class Sample {
                // BubbleDictation.Effect.OpenSnipPicker -> openSnipPicker()
                /* listOf(Effect.OpenSnipPicker) */
                fun a() {
                    BubbleDictation.Effect.OpenSnipPicker -> openSnipPicker()
                }
                fun b() = listOf(Effect.OpenSnipPicker)
                fun c() = machine.on(BubbleDictation.Event.SnipTapped)
                fun d() = Intent(ctx , SnipPickerActivity::class.java)
                fun e(): Class<*> = SnipPickerActivity::class.java
            }
            """.trimIndent()
        )

        assertEquals(
            "CONSUMES_OPEN_SNIP_PICKER must match the when-branch and not the " +
                "commented one",
            1,
            CONSUMES_OPEN_SNIP_PICKER.findAll(sample).count(),
        )
        assertEquals(
            "EMITS_OPEN_SNIP_PICKER must match the real emission and not the " +
                "block-commented one",
            1,
            EMITS_OPEN_SNIP_PICKER.findAll(sample).count(),
        )
        assertEquals(
            "RAISES_SNIP_TAPPED must match the event being raised",
            1,
            RAISES_SNIP_TAPPED.findAll(sample).count(),
        )
        assertEquals(
            "CONSTRUCTS_PICKER_INTENT must match a hand-built intent and must " +
                "NOT match a bare type reference, or the ledger would count " +
                "files that construct nothing",
            1,
            CONSTRUCTS_PICKER_INTENT.findAll(sample).count(),
        )

        // The enclosing-function resolver must name the function the match is
        // IN, not the first or the last in the file.
        assertEquals(
            listOf("Sample.kt#a"),
            sitesIn("Sample.kt", sample, CONSUMES_OPEN_SNIP_PICKER),
        )
        assertEquals(
            listOf("Sample.kt#b"),
            sitesIn("Sample.kt", sample, EMITS_OPEN_SNIP_PICKER),
        )
    }

    @Test
    fun theLedgersSeeASecondConsumerASecondEmitterAndASecondConstructor() {
        // 🔴 The NEGATIVE control: a tree each ledger MUST reject. Without it,
        // their green says only "today's tree matches" and establishes nothing
        // about whether the instrument can see a violation at all.
        val violating = stripAndNormalise(
            """
            class Rogue {
                fun handle() {
                    BubbleDictation.Effect.OpenSnipPicker -> doSomethingElse()
                }
                fun decide() = listOf(Effect.OpenSnipPicker)
                fun launch() = startActivity(Intent(this, SnipPickerActivity::class.java))
            }
            """.trimIndent()
        )
        assertEquals(
            listOf("Rogue.kt#handle"),
            sitesIn("Rogue.kt", violating, CONSUMES_OPEN_SNIP_PICKER),
        )
        assertEquals(
            listOf("Rogue.kt#decide"),
            sitesIn("Rogue.kt", violating, EMITS_OPEN_SNIP_PICKER),
        )
        assertTrue(
            "the construction ledger cannot see a hand-built picker intent, " +
                "so its one-file result says nothing",
            CONSTRUCTS_PICKER_INTENT.containsMatchIn(violating),
        )

        // And the microphone scan must be able to fire.
        assertTrue(
            "the microphone scan cannot see a capture symbol, so its empty " +
                "result is a fact about its own list",
            MIC_SYMBOLS.any {
                Regex(Regex.escape(it) + """\b""")
                    .containsMatchIn(stripAndNormalise("val s = DictationSession(this)"))
            },
        )
        assertFalse(
            "the microphone scan fires on a file that names none of its " +
                "symbols, so it would report violations on correct sources",
            MIC_SYMBOLS.any {
                Regex(Regex.escape(it) + """\b""")
                    .containsMatchIn(stripAndNormalise("val body = SnipClip.resolve(b, c)"))
            },
        )
    }

    // --- reading the tree ----------------------------------------------------

    private fun sitesOf(pattern: Regex, skip: Set<String>): List<String> =
        mainSources()
            .filter { it.first !in skip }
            .flatMap { (name, src) -> sitesIn(name, stripAndNormalise(src), pattern) }
            .sorted()

    /** Every match of [pattern] in [src], as `<file>#<function>`. */
    private fun sitesIn(file: String, src: String, pattern: Regex): List<String> =
        pattern.findAll(src).map { m ->
            val before = src.substring(0, m.range.first)
            val fn = FUN_DECL.findAll(before).lastOrNull()?.groupValues?.get(1)
                ?: "<top-level>"
            "$file#$fn"
        }.distinct().toList()

    private fun mainSources(): List<Pair<String, String>> {
        val dir = listOf(
            File("app/src/main/java/dev/zacx/dicta"),
            File("src/main/java/dev/zacx/dicta"),
            File("../app/src/main/java/dev/zacx/dicta"),
        ).firstOrNull { it.isDirectory }
        assertTrue(
            "could not locate the production sources from " +
                "${File(".").absolutePath} — refusing to report a pass on a " +
                "scan that read nothing",
            dir != null,
        )
        val files = dir!!.listFiles { f -> f.name.endsWith(".kt") }.orEmpty()
        assertTrue("the production source directory is empty", files.isNotEmpty())
        return files.map { it.name to it.readText() }.sortedBy { it.first }
    }

    /** The `<activity … .SnipPickerActivity … />` element, verbatim. */
    private fun pickerManifestElement(): String {
        val manifest = listOf(
            File("app/src/main/AndroidManifest.xml"),
            File("src/main/AndroidManifest.xml"),
            File("../app/src/main/AndroidManifest.xml"),
        ).firstOrNull { it.isFile }
        assertTrue(
            "could not read AndroidManifest.xml from ${File(".").absolutePath} " +
                "— refusing to report a pass on a file that was never read",
            manifest != null,
        )
        val element = PICKER_ACTIVITY_ELEMENT.find(manifest!!.readText())?.value
        assertTrue(
            "AndroidManifest.xml declares no <activity> for " +
                ".SnipPickerActivity at all. An activity started with an " +
                "explicit intent but not declared throws " +
                "ActivityNotFoundException at runtime, with nothing going red " +
                "at build time.",
            element != null,
        )
        return element!!
    }

    private companion object {

        /** Comments out, whitespace collapsed. See the class kdoc. */
        fun stripAndNormalise(src: String): String = src
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("//[^\\n]*"), " ")
            .replace(Regex("\\s+"), " ")

        /** `fun <name>(`, capturing the name. */
        val FUN_DECL = Regex("""\bfun\s+(\w+)\s*\(""")

        /** The effect being CARRIED OUT. */
        val CONSUMES_OPEN_SNIP_PICKER =
            Regex("""Effect\.OpenSnipPicker\s*->""")

        /** The machine DECIDING to open the picker. */
        val EMITS_OPEN_SNIP_PICKER =
            Regex("""listOf\(\s*(?:BubbleDictation\.)?Effect\.OpenSnipPicker\s*\)""")

        /** Anything raising the one event that reaches the decision. */
        val RAISES_SNIP_TAPPED =
            Regex("""Event\.SnipTapped\b""")

        /**
         * A hand-built picker intent, receiver-insensitive for
         * [DictateLaunchTest]'s measured reason: that ledger's first version
         * matched a literal `Intent(this, …` and was therefore blind to
         * `Intent(context, …` — blind in the one file it existed to protect.
         */
        val CONSTRUCTS_PICKER_INTENT =
            Regex("""Intent\(\s*\w+\s*,\s*SnipPickerActivity::class\.java\s*\)""")

        /** The `<activity>` element for the picker, self-closing or not. */
        val PICKER_ACTIVITY_ELEMENT = Regex(
            """<activity[^>]*?\.SnipPickerActivity[^>]*?>""",
            RegexOption.DOT_MATCHES_ALL,
        )

        /**
         * Symbols that mean "this file can reach the microphone".
         *
         * Word-bounded for [StartSurfaceLedgerTest]'s reason: a bare substring
         * check matches live, unrelated spellings and would fail on sources
         * that are entirely correct.
         */
        val MIC_SYMBOLS = listOf(
            "MicCapture",
            "AudioRecord",
            "DictationSession",
            "MicrophoneOwner",
            "RECORD_AUDIO",
            "startForeground",
            "Effect.StartRecording",
            "Event.BubbleTapped",
            "DictateLaunch",
        )
    }
}
