package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 🔴 The CI test-count floor must keep tracking the suite it guards.
 *
 * ## The defect this exists to make loud
 *
 * `.github/workflows/tests.yml` asserts a minimum number of executed tests, so
 * a Gradle task that reports success having run almost nothing cannot be green.
 * The floor is a **constant in a comment**, re-derived by hand, and the comment
 * that tells a maintainer how to re-derive it drifted out of step with the
 * tree twice over:
 *
 *  * It said the suite "had grown to 623" and that the slack had drifted "to
 *    20%". Both were **true when written** — the suite really was 623 at
 *    commits `0544fa2` / `32b089c`, where 500 against 623 is 19.7% slack. Then
 *    `723c21d` deleted the bubble's refusal apparatus, four tests went with it,
 *    and the comment was never touched. A number measured on one commit of a
 *    branch and quoted on another is the whole failure; it needs no
 *    carelessness and no wrong arithmetic.
 *  * `android.nix` restated the suite size as well, "so update both together"
 *    — and within four lines of itself said `619-test suite` and `grown to
 *    623`. Two copies of one number is how one of them is always stale.
 *
 * ## What is asserted, and why it is a band rather than the formula
 *
 * The obvious guard — `floor == floor(total * 500 / 575)` — would go red on
 * **every single test added**, and a permanently-red gate is worse than no
 * gate because it trains everyone to click through. So this asserts the two
 * properties that actually matter, both of which the drift above violated:
 *
 *  * the floor is not above the suite (it can never be spuriously red); and
 *  * the slack has not drifted past 20% (the gate can still notice a fifth of
 *    the suite disappearing — which is exactly what it had quietly stopped
 *    being able to do).
 *
 * There is room to add tests before this fires, and when it does the fix is to
 * re-derive the floor with the formula the workflow comment carries.
 *
 * 🔴 **What it cannot observe:** whether CI runs the workflow at all, and
 * whether `scripts/assert-tests-ran.py` reads the XML correctly. It is a
 * consistency check between two files in this repo, not a test of the gate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TestCountFloorTest {

    @Test
    fun theCiFloorStillTracksTheSuiteItGuards() {
        val total = suiteSize()
        val floor = declaredFloor()

        assertTrue(
            "the CI test-count floor ($floor) is ABOVE the number of tests in " +
                "the suite ($total), so .github/workflows/tests.yml can only " +
                "ever fail. A permanently-red gate is worse than no gate: it " +
                "trains everyone to merge through it. Re-derive the floor with " +
                "the formula in that file's comment.",
            floor <= total,
        )

        val slack = (total - floor).toDouble() / total
        assertTrue(
            "the CI test-count floor has drifted: $floor against a suite of " +
                "$total is ${"%.1f".format(slack * 100)}% slack, past the 20% " +
                "ceiling. The gate can no longer notice a fifth of the suite " +
                "disappearing, which is the state it was already found in once " +
                "— 500 against 623, left alone while its own instruction to " +
                "raise it went unfollowed. Re-derive it: " +
                "floor = floor(total * 500 / 575) = ${total * 500 / 575}, and " +
                "restate the baseline on the first line of that comment " +
                "together with THE COMMIT YOU COUNTED ON.",
            slack <= 0.20,
        )
    }

    /**
     * 🔴 Both instruments, in both directions. Until this passes, the
     * assertion above is a fact about two regexes.
     *
     * The counter is a proxy — `@Test` annotations in the sources, not executed
     * tests — so it is pinned against a known value here, and it was validated
     * against the JUnit XML per class: a full run's `TEST-*.xml` reported the
     * same number for every one of the 57 test classes that this counter
     * returns for the corresponding source file. 🔴 That per-class comparison
     * is what found the string-literal defect [countTests] documents; a
     * whole-suite total would have hidden it behind a 4-test discrepancy that
     * made the floor look safer rather than failing.
     */
    @Test
    fun bothInstrumentsAreValidated() {
        // --- the counter -----------------------------------------------------
        val total = suiteSize()
        assertTrue(
            "the suite counter returned $total, which cannot be right: this " +
                "project has had well over 500 tests since long before this " +
                "guard existed. A counter reading near zero makes the floor " +
                "assertion vacuously TRUE, which is the direction that " +
                "matters — the gate would look fine while measuring nothing. " +
                "(Deliberately a threshold and not a snapshot of the current " +
                "total: a second copy of that number is exactly what went " +
                "stale in android.nix.)",
            total > 500,
        )
        assertEquals(
            "the counter cannot see a @Test however the file is formatted, or " +
                "it counts one inside a comment. Either breaks the floor " +
                "assertion silently, and counting a commented-out test HIGH " +
                "is the direction that makes a drifted floor look fine.",
            2,
            countTests(
                """
                class X {
                    // @Test fun commentedOut() {}
                    /* @Test fun alsoCommentedOut() {} */
                    @Test
                    fun a() {}
                    @Test fun b() {}
                }
                """.trimIndent()
            ),
        )
        // 🔴 The regression that was actually measured, not an imagined one.
        // While countTests did not strip string literals it returned FOUR more
        // than the suite contained, and every phantom was an @Test inside THIS
        // file's own fixture strings. It failed silently in the dangerous
        // direction: an over-count makes the slack look smaller and the floor
        // assertion pass. (No totals quoted here on purpose — a count restated
        // where nothing checks it is the other half of this file's subject.)
        assertEquals(
            "countTests counted an @Test inside a string literal. A counter " +
                "that inflates itself from its own fixtures reports a " +
                "comfortable number for a tree it never measured — and it " +
                "inflates the total, so the slack looks SMALLER and the floor " +
                "assertion passes when it should not.",
            1,
            countTests(
                "val sample = \"\"\"class Y { @Test fun inAString() {} }\"\"\"\n" +
                    "val other = \"@Test also in a string\"\n" +
                    "@Test fun theOnlyRealOne() {}\n"
            ),
        )

        // --- the floor parser ------------------------------------------------
        assertEquals(
            "the --min parser cannot read a floor out of a realistic workflow " +
                "line, so declaredFloor() would be reading nothing",
            550,
            parseFloor("        run: python3 scripts/assert-tests-ran.py --min 550\n"),
        )
        assertEquals(
            "the --min parser read a floor out of a COMMENT. The workflow's " +
                "comment block quotes --min values and past floors in prose, " +
                "so without comment-stripping this parser picks up a historical " +
                "number and the whole assertion reports on the wrong one.",
            550,
            parseFloor(
                """
                # It was --min 3, which could only ever catch a collapse.
                # The previous entry read 500 against 575.
                      run: python3 scripts/assert-tests-ran.py --min 550
                """.trimIndent()
            ),
        )
    }

    /**
     * 🔴 One number, one place.
     *
     * `android.nix` used to restate the suite size "so update both together",
     * and then contradicted itself inside four lines. Nothing in that file
     * depends on the count — it pins a nixpkgs revision — so quoting it there
     * bought nothing and guaranteed a stale copy. A number nothing asserts on
     * is unpinned by construction: either pin it or stop quoting it.
     */
    @Test
    fun onlyTheCiGateQuotesASuiteSizeBaseline() {
        val nix = read("android.nix", "../android.nix", what = "android.nix")
        val quoted = SUITE_SIZE_CLAIM.findAll(nix).map { it.value }.toList()
        assertEquals(
            "android.nix states a test-suite size again. It is not the file " +
                "that asserts on one — .github/workflows/tests.yml is — and " +
                "the last time the number lived in both places they drifted " +
                "apart, with this file saying \"619-test suite\" and " +
                "\"grown to 623\" four lines from each other. Say which " +
                "toolchain the suite was last run green against by naming the " +
                "revision and the date, not by restating a count nothing here " +
                "checks. Found: $quoted",
            emptyList<String>(),
            quoted,
        )

        // Positive control: the pattern must be able to find such a claim, or
        // the empty result above is indistinguishable from a dead regex.
        assertEquals(
            "the suite-size pattern cannot see a restated count, so reporting " +
                "none of them in android.nix proves nothing",
            listOf("619-test suite", "623 tests"),
            SUITE_SIZE_CLAIM.findAll(
                "this project's 619-test suite was green; it had grown to 623 tests by then"
            ).map { it.value }.toList(),
        )
    }

    // --- plumbing ------------------------------------------------------------

    private fun suiteSize(): Int {
        val dir = listOf(
            File("app/src/test/java/dev/zacx/dicta"),
            File("src/test/java/dev/zacx/dicta"),
            File("../app/src/test/java/dev/zacx/dicta"),
        ).firstOrNull { it.isDirectory }
        assertTrue(
            "could not locate the test source directory from " +
                "${File(".").absolutePath} — refusing to report a pass on a " +
                "suite that was never counted",
            dir != null,
        )
        val files = dir!!.listFiles { f -> f.name.endsWith(".kt") }.orEmpty().toList()
        assertTrue(
            "the test source directory held no Kotlin files, so the count " +
                "would be zero and the floor assertion vacuous",
            files.size > 10,
        )
        return files.sumOf { countTests(it.readText()) }
    }

    private fun declaredFloor(): Int = parseFloor(
        read(
            ".github/workflows/tests.yml",
            "../.github/workflows/tests.yml",
            what = ".github/workflows/tests.yml",
        )
    )

    private fun read(vararg paths: String, what: String): String {
        val found = paths.map { File(it) }.firstOrNull { it.exists() }
        assertTrue(
            "could not locate $what from ${File(".").absolutePath} — refusing " +
                "to report a pass on a file that was never read",
            found != null,
        )
        return found!!.readText()
    }

    private companion object {
        /**
         * A restated suite size: `NNN-test suite`, `NNN tests`, or
         * `grown to NNN`. Three digits minimum so it cannot fire on a version
         * number or an SDK level.
         */
        val SUITE_SIZE_CLAIM = Regex("""\b\d{3,}(?:-test suite|\s+tests?)\b""")

        /**
         * `@Test` annotations — **string literals and comments stripped
         * first, in that order.**
         *
         * 🔴 The literal-stripping is not hygiene; it was a measured defect.
         * Without it this counter read four too many for the real suite, and all
         * four phantoms were in *this* file: the `@Test`s inside
         * [bothInstrumentsAreValidated]'s own fixture string. A self-counting
         * instrument whose fixtures inflate its own answer is the exact shape
         * of a check that reports a comfortable number for a tree it has not
         * measured — and it failed silently, because an over-count makes the
         * slack look *smaller* and the floor assertion *pass*.
         *
         * Raw strings are stripped first because they can contain
         * comment-opening sequences, which would otherwise feed the comment
         * regexes text that is not code. (Writing such a sequence literally in
         * this kdoc is itself a trap: Kotlin block comments NEST, so an
         * unescaped one opens a nested comment and the closing delimiter then
         * terminates that instead of the kdoc, which breaks the parse of
         * everything below it. Measured, while writing this comment.)
         */
        fun countTests(src: String): Int = Regex("""@Test\b""")
            .findAll(withoutComments(withoutStringLiterals(src)))
            .count()

        fun withoutStringLiterals(src: String): String = src
            .replace(Regex("\"\"\".*?\"\"\"", RegexOption.DOT_MATCHES_ALL), "\"\"")
            .replace(Regex("\"(?:\\\\.|[^\"\\\\\\n])*\""), "\"\"")

        /**
         * The `--min` argument of the assert-tests-ran step. Comments are
         * stripped first and that is load-bearing, not defensive: the
         * workflow's own comment block quotes `--min 3` and `500 against 575`
         * in prose directly above the step.
         */
        fun parseFloor(yml: String): Int {
            val m = Regex("""--min\s+(\d+)""").find(withoutYamlComments(yml))
            assertTrue(
                "no `--min <n>` argument was found in the workflow outside its " +
                    "comments. Either the gate step was removed — in which case " +
                    "the suite has no floor at all and this test is the only " +
                    "thing saying so — or it was reworded. Fix it deliberately.",
                m != null,
            )
            return m!!.groupValues[1].toInt()
        }

        fun withoutComments(src: String): String = src
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//.*"), "")

        fun withoutYamlComments(yml: String): String =
            yml.lineSequence().joinToString("\n") { it.replace(Regex("#.*"), "") }
    }
}
