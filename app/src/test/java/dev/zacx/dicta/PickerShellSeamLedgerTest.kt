package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 🔴 The seam between **a floating picker and the shell that owns its rules**.
 *
 * ## The defect this exists to make loud
 *
 * [FloatingPickerActivity] owns five obligations a picker must meet, each
 * invisible if omitted: `FloatingWindow.applySize`, the `TextWatcher` that
 * re-filters on type, the `setSelection(0)` that opens on the newest row, the
 * THREE distinct empty-state strings, and the `finish()` after a pick. A
 * silently empty list is the failure mode that reads as "the feature is
 * broken", and it is reachable by forgetting any one of them.
 *
 * That is not hypothetical. In 0.4.0 `SnipPickerActivity` shipped wearing the
 * overlay theme and never called the ONE obligation that existed then — it
 * rendered at roughly a fifth of the screen width on a real Galaxy S21, with
 * 815 tests green through it. A third picker would be five such obligations,
 * and a shell nobody is required to use is a suggestion.
 *
 * ## 🔴 Membership is the DECLARED SUPERCLASS, not a name prefix
 *
 * This guard selects on `: FloatingPickerActivity<...>()` in the source. A
 * prefix ledger — `startsWith("Snip")`, or `endsWith("PickerActivity")` — was
 * round 1's top finding on the sibling [SnipSeamLedgerTest] and was **measured
 * SURVIVED**: a flow file called anything else escaped both sides of the
 * comparison. The names a third picker might take are unpredictable
 * (`QuickReplyActivity`, `Overlay2Activity`, `TranscriptBrowser`) and none of
 * them shares a prefix with the two that exist, so spelling cannot be the
 * predicate.
 *
 * ## 🔴 It fails on GROWTH and on SHRINK
 *
 * **Growth** is the hazard the shell exists for: a third picker that is not on
 * it has five rules to get right by hand.
 *
 * **Shrink matters as much, and is the subtler half.** A picker that left the
 * shell — refactored "for clarity", or reverted — would take its obligations
 * with it, and every behavioural test in [SnipPickerActivityTest] and
 * [HistoryPickerActivityTest] would stay green for whichever picker remained.
 * Shrink is also what a lazy fix for a growth failure looks like: deleting the
 * superclass is easier than meeting the contract.
 *
 * ## 🔴 It pins a RELATIONSHIP, not a component
 *
 * The rule is not "the shell is correct" and not "each picker is correct" —
 * both of those are covered elsewhere, in isolation, which is exactly the shape
 * that leaves a defect in the seam nobody owns. The rule here is "the set of
 * pickers and the set of shell users are THE SAME SET", asserted as an equality
 * so it is red when either side moves. [everyPickerIsOnTheShell] is the
 * structural half; [noPickerReimplementsWhatTheShellOwns] is the behavioural
 * complement, because a structural check type-checks past a wrong argument.
 *
 * ## What it cannot see, stated so the clean result is not over-read
 *
 * A **textual** ledger over the Kotlin sources. It cannot see a picker
 * assembled by composition rather than inheritance, a picker written in Java, a
 * subclass of a subclass, or a picker that extends the shell and then overrides
 * every one of its behaviours to something wrong. The behavioural halves are
 * the two picker tests and [FloatingWindowSizeTest].
 */
class PickerShellSeamLedgerTest {

    /**
     * Every activity on the shared picker shell.
     *
     * Measured from the sources, not from intent. Adding a picker means editing
     * this list, which is the point: the edit is where the five obligations get
     * read.
     */
    private val pickers = listOf(
        "HistoryPickerActivity.kt",
        "SnipPickerActivity.kt",
    )

    // --- the ledger ----------------------------------------------------------

    @Test
    fun theSetOfActivitiesOnThePickerShellIsLedgered() {
        assertEquals(
            "the set of activities declaring `: FloatingPickerActivity<...>()` " +
                "changed. The shell owns FIVE rules a picker must obey — " +
                "FloatingWindow.applySize, the re-filter-on-type TextWatcher, " +
                "setSelection(0), the three DISTINCT empty-state strings, and " +
                "finish()-after-pick — and each is invisible when omitted. A " +
                "new picker goes in this list AND gets its own activity test; a " +
                "picker that LEFT the shell took those five rules with it and " +
                "nothing else would notice.",
            pickers.sorted(),
            shellSubclasses(),
        )
    }

    @Test
    fun everyPickerIsOnTheShell() {
        // The same equality read from the other side, so the failure message
        // names the right fix. A file in `pickers` that is NOT a subclass is a
        // picker that escaped the shell; the test above would report it as a
        // shrink, which reads as "delete the entry".
        val offShell = pickers.filterNot { it in shellSubclasses() }
        assertEquals(
            "a ledgered picker does not extend FloatingPickerActivity. It now " +
                "owns the window sizing, the search wiring, the empty states " +
                "and the finish() by hand — which is the duplication the shell " +
                "exists to remove, and the width defect of 0.4.0 is what one " +
                "forgotten copy looks like.",
            emptyList<String>(),
            offShell,
        )
    }

    @Test
    fun theShellIsTheOneOwnerOfEachRuleItClaims() {
        // 🔴 Growth is not the only way the shell stops being one: a picker can
        // extend it and ALSO carry its own copy of a rule, which satisfies
        // every assertion above. Each pattern below is a rule the shell's kdoc
        // claims to own, and no picker may name it.
        //
        // `Window.setLayout` is deliberately NOT here — it is
        // FloatingOverlaySeamLedgerTest.onlyFloatingWindowSizesAWindow's
        // subject, and one rule belongs in one place.
        val owned = mapOf(
            "addTextChangedListener" to ADDS_TEXT_WATCHER,
            "setSelection" to SETS_LIST_SELECTION,
            "setOnItemClickListener" to SETS_ITEM_CLICK_LISTENER,
        )
        val violations = mutableListOf<String>()
        for ((name, src) in mainSources().filter { it.first in pickers }) {
            for ((rule, pattern) in owned) {
                if (pattern.containsMatchIn(stripComments(src))) {
                    violations += "$name names $rule"
                }
            }
        }
        assertEquals(
            "a picker carries its own copy of a rule FloatingPickerActivity " +
                "owns. Two copies of a rule that must agree is how the rule " +
                "starts disagreeing with itself — and the shell's whole claim " +
                "is that a subclass says WHAT it lists and nothing about HOW a " +
                "picker behaves.\n" + violations.joinToString("\n"),
            emptyList<String>(),
            violations,
        )

        // POSITIVE CONTROL: the shell itself must name ALL THREE, or the empty
        // result above is a fact about three dead regexes.
        val shell = requireNotNull(mainSources().firstOrNull { it.first == SHELL })
        for ((rule, pattern) in owned) {
            assertTrue(
                "FloatingPickerActivity does not name $rule, so the guard " +
                    "above cannot see that rule anywhere and its empty " +
                    "violation list means nothing",
                pattern.containsMatchIn(stripComments(shell.second)),
            )
        }
    }

    @Test
    fun noPickerReimplementsWhatTheShellOwns() {
        // 🔴 The BEHAVIOURAL complement. A structural check type-checks past a
        // wrong argument, and a subclass that overrode the shell's lifecycle
        // would satisfy every textual guard above. These are the three
        // members the shell implements and a subclass must not override:
        // onCreate (where the sizing is applied), onNewIntent (the stale-rows
        // reload) and the row click (where finish() happens).
        val violations = mutableListOf<String>()
        for ((name, src) in mainSources().filter { it.first in pickers }) {
            val code = stripComments(src)
            for (member in listOf("onCreate", "onNewIntent", "onDestroy")) {
                if (Regex("""override\s+fun\s+${Regex.escape(member)}\s*\(""")
                        .containsMatchIn(code)
                ) {
                    violations += "$name overrides $member"
                }
            }
        }
        assertEquals(
            "a picker overrides a lifecycle member FloatingPickerActivity " +
                "implements. onCreate is where FloatingWindow.applySize lands " +
                "AFTER setContentView — an override that forgets to call " +
                "super, or calls it in the wrong order, reproduces the 0.4.0 " +
                "width defect while every other guard stays green. onNewIntent " +
                "is the stale-rows reload. Say WHAT you list by overriding " +
                "readRows/filterRows/bindRow/onRowPicked instead.\n" +
                violations.joinToString("\n"),
            emptyList<String>(),
            violations,
        )
    }

    @Test
    fun everyPickerDeclaresThreeDistinctEmptyStateResources() {
        // 🔴 The shell makes three empty states MANDATORY by declaring three
        // abstract members — a picker cannot compile without supplying all
        // three. What it cannot make mandatory is that they are DIFFERENT: a
        // subclass can point all three at one resource and the compiler is
        // happy, which restores the silent-empty failure in its original form
        // (one message for several different problems).
        //
        // Read from the source rather than by instantiating, so a third picker
        // is covered without having to be driven.
        for ((name, src) in mainSources().filter { it.first in pickers }) {
            val code = stripComments(src)
            val resources = EMPTY_STATE_OVERRIDE.findAll(code)
                .map { it.groupValues[1] to it.groupValues[2] }
                .toList()
            assertEquals(
                "$name does not override exactly the three empty-state " +
                    "resources; found ${resources.map { it.first }}",
                3,
                resources.size,
            )
            assertEquals(
                "$name points two of its three empty states at the same " +
                    "string resource: $resources. \"nothing stored\", " +
                    "\"nothing matches that\" and \"could not be read\" are " +
                    "three different problems with three different fixes, and " +
                    "one message for several of them is the silent-empty " +
                    "failure moved into the UI.",
                3,
                resources.map { it.second }.toSet().size,
            )
        }
    }

    // --- controls for every instrument above ---------------------------------

    @Test
    fun theSuperclassScanSeesAThirdPickerHoweverItIsWrittenAndNotInProse() {
        // 🔴 NEGATIVE CONTROL for the ledger: sources it MUST classify as
        // pickers. Without this, the green above says only "today's tree
        // matches" and establishes nothing about whether the scan can see a NEW
        // picker — the single case it exists for.
        val subclasses = listOf(
            "class QuickReplyActivity : FloatingPickerActivity<Reply>() {",
            "class A : FloatingPickerActivity<Foo.Bar>() {",
            "internal class B:FloatingPickerActivity<String>(){",
            "class C :\n    FloatingPickerActivity<Thing>() {",
            "class D : FloatingPickerActivity < Thing > () {",
        )
        for (sample in subclasses) {
            assertTrue(
                "the scan cannot see a real picker: <$sample>. A ktfmt " +
                    "reformat that wraps the supertype onto its own line must " +
                    "not make a picker invisible to the ledger.",
                EXTENDS_SHELL.containsMatchIn(stripComments(sample)),
            )
        }

        val nonSubclasses = listOf(
            "// class QuickReplyActivity : FloatingPickerActivity<Reply>()",
            "/* extends FloatingPickerActivity<Reply>() */",
            "class E : Activity() { val x = FloatingPickerActivity::class }",
            "// see FloatingPickerActivity for the five rules",
            "abstract class FloatingPickerActivity<T> : Activity() {",
        )
        for (sample in nonSubclasses) {
            assertFalse(
                "the scan was satisfied by prose, by a mere reference, or by " +
                    "the shell's OWN declaration: <$sample>. Prose naming a " +
                    "construction must not satisfy a scan — that is how a " +
                    "guard reads as coverage while providing none.",
                EXTENDS_SHELL.containsMatchIn(stripComments(sample)),
            )
        }

        // POSITIVE CONTROL on the real tree: a zero here would be
        // indistinguishable from a dead regex reading the wrong path.
        assertTrue(
            "the scan found no pickers at all in the real sources, so every " +
                "result above is a fact about a regex rather than about the " +
                "tree",
            shellSubclasses().size >= 2,
        )
        assertTrue(
            "the shell's own file was not read, so the scans have no subject",
            mainSources().any { it.first == SHELL },
        )
    }

    @Test
    fun theLedgerWouldGoRedOnBothAThirdPickerAndADepartedOne() {
        // 🔴 The growth-AND-shrink claim, mutated rather than asserted in
        // prose. Both directions are computed against the SAME real scan
        // result, so neither arm can pass because the scan is broken.
        val actual = shellSubclasses()

        val grown = (actual + "QuickReplyActivity.kt").sorted()
        assertNotEquals(
            "the ledger's expected set already contains a hypothetical third " +
                "picker, so GROWTH could not fail it",
            pickers.sorted(),
            grown,
        )

        val shrunk = actual.drop(1)
        assertNotEquals(
            "the ledger's expected set equals the tree minus a picker, so " +
                "SHRINK could not fail it — which is the half that a lazy fix " +
                "for a growth failure produces",
            pickers.sorted(),
            shrunk,
        )
    }

    @Test
    fun theEmptyStateScanSeesCollidingResourcesAndIgnoresDistinctOnes() {
        // NEGATIVE CONTROL for everyPickerDeclaresThreeDistinctEmptyStateResources:
        // a source it MUST reject. Without it, three distinct resources today
        // says nothing about whether a collision would be seen.
        val colliding = stripComments(
            """
            class Bad : FloatingPickerActivity<String>() {
                override val emptyUnreadableRes: Int = R.string.nothing_here
                override val emptyNothingStoredRes: Int = R.string.nothing_here
                override val emptyNoMatchesRes: Int = R.string.nothing_here
            }
            """.trimIndent()
        )
        val bad = EMPTY_STATE_OVERRIDE.findAll(colliding).map { it.groupValues[2] }.toList()
        assertEquals("the scan did not see all three overrides", 3, bad.size)
        assertEquals(
            "the scan cannot tell three identical resources from three " +
                "distinct ones, so the distinctness assertion is inert",
            1,
            bad.toSet().size,
        )

        val distinct = stripComments(
            """
            class Good : FloatingPickerActivity<String>() {
                override val emptyUnreadableRes: Int = R.string.a
                override val emptyNothingStoredRes: Int = R.string.b
                override val emptyNoMatchesRes: Int = R.string.c
            }
            """.trimIndent()
        )
        assertEquals(
            "the scan reported a collision for three distinct resources, so " +
                "the guard is noise and would get bumped without being read",
            3,
            EMPTY_STATE_OVERRIDE.findAll(distinct).map { it.groupValues[2] }.toSet().size,
        )
    }

    // --- reading the tree ----------------------------------------------------

    /** Every production `.kt` that declares `: FloatingPickerActivity<...>()`. */
    private fun shellSubclasses(): List<String> =
        mainSources()
            .filter { (_, src) -> EXTENDS_SHELL.containsMatchIn(stripComments(src)) }
            .map { it.first }
            .sorted()

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

    private companion object {

        const val SHELL = "FloatingPickerActivity.kt"

        /**
         * A class declaring the shell as its SUPERTYPE.
         *
         * Requires the `:` and the generic argument's closing `>` followed by
         * `(`, which is what distinguishes a supertype declaration from a bare
         * reference (`FloatingPickerActivity::class`) or a prose mention.
         * Whitespace-tolerant across newlines so a `ktfmt` reformat cannot hide
         * a picker; `[^>]*` keeps the generic argument from running away.
         *
         * 🔴 The `:` is also what excludes the shell's OWN declaration
         * (`abstract class FloatingPickerActivity<T> : Activity()`), which
         * would otherwise ledger the shell as a user of itself.
         */
        val EXTENDS_SHELL =
            Regex(""":\s*FloatingPickerActivity\s*<[^>]*>\s*\(""", RegexOption.DOT_MATCHES_ALL)

        /** One of the shell's three empty-state members being overridden. */
        val EMPTY_STATE_OVERRIDE = Regex(
            """override\s+val\s+(emptyUnreadableRes|emptyNothingStoredRes|""" +
                """emptyNoMatchesRes)\s*:\s*Int\s*=\s*([A-Za-z0-9_.]+)"""
        )

        val ADDS_TEXT_WATCHER = Regex("""addTextChangedListener\s*\(""")
        val SETS_LIST_SELECTION = Regex("""\.\s*setSelection\s*\(""")
        /**
         * 🔴 `[({]`, not `\(`. The shell writes this as a TRAILING LAMBDA —
         * `setOnItemClickListener { _, _, position, _ -> … }` — so a pattern
         * requiring the paren matches nothing in the one file it exists to find
         * the rule in, and `theShellIsTheOneOwnerOfEachRuleItClaims`'s positive
         * control is what caught that.
         */
        val SETS_ITEM_CLICK_LISTENER = Regex("""setOnItemClickListener\s*[({]""")

        fun stripComments(src: String): String = src
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("//[^\\n]*"), " ")
    }
}
