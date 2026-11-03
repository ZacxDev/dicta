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
     * Every production file of the snip flow itself.
     *
     * 🔴 A LEDGER, so the microphone scan below cannot quietly stop covering a
     * file — and the set it is checked against is derived **structurally**, not
     * from a name.
     *
     * The first version of that check compared this list to
     * `mainSources().filter { it.startsWith("Snip") }`, which made membership a
     * matter of SPELLING. MEASURED: a snip-flow file named `CatalogFetcher.kt`
     * — the file the tailnet-catalog follow-up named on [SnipStore] will add —
     * that named `DictationSession` was absent from BOTH sides of that
     * comparison, so it escaped the microphone scan entirely and this class
     * stayed at 10 tests / 0 failures. That is the one thing this guard must
     * not do: read as coverage while providing none for the newest file.
     *
     * Two guards replace it, closing the hole from either side:
     * [everyFileReferencingASnipSymbolIsLedgeredAsFlowOrAsAMicOwningHost]
     * derives snip-flow membership from an actual reference to a snip symbol,
     * and [onlyLedgeredMicOwningFilesNameAMicrophoneSymbol] scans the WHOLE
     * production tree instead of this list — so a new file that reaches capture
     * is red whatever it is called.
     *
     * `BubbleService.kt`, `BubbleDictation.kt` and `SettingsActivity.kt` are
     * deliberately NOT in it: they legitimately own microphone paths of their
     * own, and the claim being made is about the snip flow's own files.
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

    /**
     * The files that touch the snip flow **and** name a [MIC_SYMBOLS] entry:
     * the service that carries the effect out, the state machine that emits it,
     * the settings screen that imports the catalog, and the history picker.
     *
     * Ledgered separately from [snipSources] because the two are held to
     * opposite claims — these may name a capture symbol, the snip flow's own
     * files may not — and because a new file put in the WRONG one is the
     * failure mode that would restore the blind spot. A snip-aware file can
     * only be classified by editing one of these two lists, deliberately.
     *
     * ## 🔴 `HistoryPickerActivity.kt` IS HERE, AND IT OWNS NO MICROPHONE PATH
     *
     * It is the one entry whose membership is NOT "this file legitimately
     * captures audio", and saying so is load-bearing because the list's name
     * would otherwise be a lie about it.
     *
     * Why it is in a snip ledger at all: the operator's decision #3 is that the
     * history picker's search REUSES [SnipMatcher] rather than growing a second
     * matcher, so one matching rule governs both pickers. That reference puts
     * it in `filesReferencing(SNIP_SYMBOLS)` — which is the structural ledger
     * working, not a surprise.
     *
     * Why it cannot go in [snipSources] instead: it names `DictateLaunch`,
     * which is in [MIC_SYMBOLS], so
     * [noFileInTheSnipFlowNamesAMicrophoneOrCaptureSymbol] would be red. That
     * entry in `MIC_SYMBOLS` is a deliberately COARSE proxy — it catches any
     * file that can launch the card at all, including the plain launch that
     * auto-starts a capture — and narrowing it to
     * `DictateLaunch.intent`/`pendingIntent` so that this one file could pass
     * would weaken a guard across the whole tree to accommodate it.
     *
     * 🔴 So the mic-free claim for this file is made EXACTLY rather than
     * waived: [theHistoryPickerReachesTheCardOnlyThroughHistoryIntent] asserts
     * that the only `MIC_SYMBOLS` entry it names is `DictateLaunch`, and that
     * the only `DictateLaunch` member it names is `historyIntent` — which
     * carries `EXTRA_HISTORY_ID`, so `DictateActivity` populates from history
     * and does not capture. That is a TIGHTER claim about this file than the
     * blanket one it is excused from, and it is why `StartSurfaceLedgerTest`'s
     * ledger of recording surfaces does not grow.
     */
    private val snipAwareMicOwningHosts = listOf(
        "BubbleDictation.kt",
        "BubbleService.kt",
        "HistoryPickerActivity.kt",
        "SettingsActivity.kt",
    )

    /**
     * Every production file permitted to name a microphone or capture symbol.
     *
     * 🔴 The scan over this is INVERTED relative to the one over [snipSources]:
     * instead of asking "do the snip files reach capture", it asks "does any
     * file outside this ledger reach capture". That is what makes a new,
     * arbitrarily-named snip file visible — it has to be ledgered here before
     * it may touch the microphone, and it cannot be ledgered here and in
     * [snipSources] both without the scan below going red.
     *
     * Measured from the tree, not from intent: these are the files that name a
     * [MIC_SYMBOLS] entry outside comments today.
     */
    private val micOwningSources = listOf(
        "BubbleDictation.kt",
        "BubbleService.kt",
        "BubbleSessionBus.kt",
        "DictateActivity.kt",
        "DictateLaunch.kt",
        "DictationSession.kt",
        // 🔴 Here because it names `DictateLaunch`, which MIC_SYMBOLS carries
        // as a coarse proxy for "can launch the card". It owns NO capture path:
        // see snipAwareMicOwningHosts' kdoc, and
        // theHistoryPickerReachesTheCardOnlyThroughHistoryIntent for the exact
        // claim that replaces the blanket one.
        "HistoryPickerActivity.kt",
        "MainActivity.kt",
        "MicCapture.kt",
        "MicrophoneOwner.kt",
        "ProjectionService.kt",
    )

    @Test
    fun everyFileReferencingASnipSymbolIsLedgeredAsFlowOrAsAMicOwningHost() {
        assertEquals(
            "the set of production files referencing a snip symbol changed. " +
                "Put the new file in `snipSources` if it is part of the snip " +
                "flow, or in `snipAwareMicOwningHosts` if it is a surface that " +
                "legitimately owns the microphone too — and note that the " +
                "second choice puts it under " +
                "`onlyLedgeredMicOwningFilesNameAMicrophoneSymbol` instead. " +
                "Membership here is a REFERENCE to a snip symbol, not a `Snip` " +
                "name prefix: the prefix version of this guard was blind to a " +
                "snip-flow file called anything else.",
            (snipSources + snipAwareMicOwningHosts).sorted(),
            filesReferencing(SNIP_SYMBOLS),
        )
    }

    @Test
    fun onlyLedgeredMicOwningFilesNameAMicrophoneSymbol() {
        assertEquals(
            "the set of production files naming a microphone or capture " +
                "symbol changed. A file reaching capture is a decision, not an " +
                "import: ledger it in `micOwningSources` deliberately — and if " +
                "it is part of the snip flow, it must not be reaching capture " +
                "at all, because issue #38 is explicit that opening the picker " +
                "is not a dictation start.",
            micOwningSources.sorted(),
            filesReferencing(MIC_SYMBOLS),
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

    @Test
    fun theHistoryPickerReachesTheCardOnlyThroughHistoryIntent() {
        // 🔴 THE EXACT CLAIM THAT REPLACES THE BLANKET MIC-FREE ONE for
        // HistoryPickerActivity.kt — the one file in `snipAwareMicOwningHosts`
        // that owns no capture path. See that list's kdoc for why it is there
        // and why MIC_SYMBOLS was not narrowed to let it into `snipSources`.
        //
        // Two assertions, and they close the hole from both sides:
        //  1. `DictateLaunch` is the ONLY MIC_SYMBOLS entry it names — so the
        //     exemption cannot quietly widen to MicCapture, DictationSession,
        //     RECORD_AUDIO or Effect.StartRecording.
        //  2. `historyIntent` is the only `DictateLaunch` MEMBER it names — so
        //     it cannot reach the plain launch, which auto-starts a capture and
        //     is what StartSurfaceLedgerTest counts.
        val source = requireNotNull(
            mainSources().firstOrNull { it.first == HISTORY_PICKER }
        ) { "$HISTORY_PICKER is not in the production sources" }
        val code = stripAndNormalise(source.second)

        val named = MIC_SYMBOLS.filter {
            Regex(Regex.escape(it) + """\b""").containsMatchIn(code)
        }
        assertEquals(
            "$HISTORY_PICKER names a microphone or capture symbol beyond " +
                "`DictateLaunch`. It is excused from " +
                "noFileInTheSnipFlowNamesAMicrophoneOrCaptureSymbol ONLY " +
                "because the single symbol it names is the card launcher, used " +
                "for a stored dictation. Anything else here is a real capture " +
                "path in the history flow, which is the thing this change " +
                "promised not to add.",
            listOf("DictateLaunch"),
            named,
        )

        val members = Regex("""DictateLaunch\s*\.\s*([A-Za-z0-9_]+)""")
            .findAll(code)
            .map { it.groupValues[1] }
            .toSortedSet()
            .toList()
        assertEquals(
            "$HISTORY_PICKER reaches a DictateLaunch member other than " +
                "historyIntent: $members. A plain `DictateLaunch.intent(...)` " +
                "auto-starts a capture — opening a stored dictation must carry " +
                "EXTRA_HISTORY_ID, which only historyIntent does, and " +
                "StartSurfaceLedgerTest's PLAIN_OVERLAY_LAUNCH pattern " +
                "deliberately does not match historyIntent for exactly that " +
                "reason.",
            listOf("historyIntent"),
            members,
        )

        // POSITIVE CONTROL: both results above are empty-set-shaped, so prove
        // the scan is reading real code rather than a stripped-to-nothing
        // string. A zero and a dead regex are the same observation.
        assertTrue(
            "the normalised source names neither DictateLaunch nor " +
                "SnipMatcher, so this file's assertions are facts about a " +
                "regex rather than about $HISTORY_PICKER",
            code.contains("DictateLaunch") && code.contains("SnipMatcher"),
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

        // And the snip-membership scan that replaced the `Snip` name prefix.
        assertTrue(
            "the snip-symbol scan cannot see a snip reference, so the file-set " +
                "ledgers above are facts about their own lists",
            SNIP_SYMBOLS.any {
                Regex(Regex.escape(it) + """\b""")
                    .containsMatchIn(stripAndNormalise("val store = SnipStore(this)"))
            },
        )
        assertTrue(
            "the snip-symbol scan is blind to a file that reaches the flow " +
                "through the EFFECT alone — which is BubbleDictation, so the " +
                "ledger would be wrong about the file that decides the picker " +
                "opens",
            SNIP_SYMBOLS.any {
                Regex(Regex.escape(it) + """\b""")
                    .containsMatchIn(stripAndNormalise("fun d() = listOf(Effect.OpenSnipPicker)"))
            },
        )
        assertFalse(
            "the snip-symbol scan fires on a file naming no snip symbol, so " +
                "every unrelated file in the tree would be demanded of the " +
                "ledger",
            SNIP_SYMBOLS.any {
                Regex(Regex.escape(it) + """\b""")
                    .containsMatchIn(stripAndNormalise("val s = DictationSession(this)"))
            },
        )
    }

    @Test
    fun theFileSetLedgersSeeASnipFlowFileThatIsNotNamedSnip() {
        // 🔴 The control the `Snip`-prefix version of these ledgers FAILED, and
        // the reason they are structural now. `CatalogFetcher.kt` — the tailnet
        // follow-up's own name — was invisible to a `startsWith("Snip")`
        // selection on both sides of the comparison AND to the microphone scan.
        // Here both replacement scans are shown to see it: as a snip-flow file,
        // and as a file reaching capture.
        val fetcher = stripAndNormalise(
            """
            class CatalogFetcher(private val session: DictationSession) {
                fun fetch(): SnipStore.Catalog? = null
            }
            """.trimIndent()
        )
        assertTrue(
            "a snip-flow file not named Snip* is invisible to the " +
                "snip-membership scan, so it would never be demanded of " +
                "`snipSources` and would escape the microphone scan — exactly " +
                "the measured hole the prefix version had",
            SNIP_SYMBOLS.any {
                Regex(Regex.escape(it) + """\b""").containsMatchIn(fetcher)
            },
        )
        assertTrue(
            "a file reaching capture is invisible to the whole-tree microphone " +
                "scan unless it is called Snip*, which is the half that made " +
                "the prefix version read as coverage while providing none",
            MIC_SYMBOLS.any {
                Regex(Regex.escape(it) + """\b""").containsMatchIn(fetcher)
            },
        )
    }

    // --- reading the tree ----------------------------------------------------

    /**
     * The production files naming any of [symbols], comments excluded.
     *
     * Word-bounded and comment-stripped for the class kdoc's two reasons: prose
     * naming a symbol must not satisfy a scan — `SnipPickerActivity`'s own kdoc
     * says "there is no `DictationSession` here", and a scan that counted that
     * would report the opposite of the truth — and a bare substring check
     * matches unrelated live spellings.
     */
    private fun filesReferencing(symbols: List<String>): List<String> =
        mainSources()
            .filter { (_, src) ->
                val normalised = stripAndNormalise(src)
                symbols.any {
                    Regex(Regex.escape(it) + """\b""").containsMatchIn(normalised)
                }
            }
            .map { it.first }
            .sorted()

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
        /**
         * Symbols that mean "this file participates in the snip flow".
         *
         * The structural stand-in for the `Snip` name prefix the ledger used to
         * select on. The effect and the event are in here as well as the types:
         * `BubbleDictation` reaches the flow without naming one single snip
         * TYPE in code — it emits `Effect.OpenSnipPicker` — so a list of type
         * names alone would miss the file that decides the picker opens.
         */
        val SNIP_SYMBOLS = listOf(
            "SnipCatalog",
            "SnipClip",
            "SnipLaunch",
            "SnipMatcher",
            "SnipPickerActivity",
            "SnipRow",
            "SnipStore",
            "Snippet",
            "Effect.OpenSnipPicker",
            "Event.SnipTapped",
        )

        /** @see theHistoryPickerReachesTheCardOnlyThroughHistoryIntent */
        const val HISTORY_PICKER = "HistoryPickerActivity.kt"

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
