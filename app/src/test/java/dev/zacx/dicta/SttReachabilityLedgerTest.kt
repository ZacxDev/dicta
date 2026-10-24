package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 🔴 A ledger of **who reads and who writes the reachability owner**.
 *
 * ## The failure this exists to make loud
 *
 * `BubbleEnabled` was consolidated onto one predicate after an audit found
 * three hand-copied readings of the bubble's enabled flag, which disagreed. A
 * reachability reading has the same shape and a worse failure mode, because it
 * is a *cached* fact with a timestamp: a second copy of the staleness rule is a
 * second answer to "is this still true", and the two answers differ only
 * sometimes. There is no exception and no log line — the observable is a
 * warning that appears when the server is up, or does not appear when it is
 * down, intermittently.
 *
 * So the ledger fails when the set **grows or shrinks**: a new consumer of
 * [SttReachability] forces a deliberate decision here, and a consumer that
 * stops reading the owner (because it started deciding for itself) shows up as
 * a missing name.
 *
 *  * `SttReachability.kt` — the owner. Holds the last definite result, its
 *    timestamp, the in-flight claim and the failure streak; [SttReachability.verdict]
 *    is the single decision.
 *  * `BubbleService.kt` — the only consumer. Probes, records, forgets on
 *    screen-off, and reads the verdict once, at the one place
 *    `BubbleDictation.Effect.StartRecording` is dispatched.
 *
 * 🔴 **What it cannot see**, stated so a clean result is not over-read: a call
 * reached through a wrapper, a lambda, a factory or a second type is invisible
 * to it, as is an owner handed from one file to another. It is a ledger of
 * textual call sites, not of behaviour — which is why
 * [theOwnerIsWhatActuallyDecides] is here too: a structural check type-checks
 * straight past a wrong argument.
 *
 * Comments and string literals are stripped and all whitespace is collapsed
 * before any pattern runs. Both are load-bearing rather than hygiene: this
 * tree discusses the owner's methods in prose at length, and a line-based scan
 * on this project has both missed a one-line expression-bodied forwarder and
 * gone spuriously red on a pure `ktfmt` reformat.
 */
class SttReachabilityLedgerTest {

    @Test
    fun onlyTheDeclaredFilesTouchTheReachabilityOwner() {
        val mentions = sourceFiles()
            .filter { f -> normalise(f.readText()).contains("SttReachability") }
            .map { it.name }
            .sorted()

        assertEquals(
            "the set of files naming SttReachability changed. It is a CACHED " +
                "fact with a timestamp, so a second consumer is a second " +
                "answer to \"is this still true\" — the shape BubbleEnabled was " +
                "consolidated to remove after three hand-copied readings " +
                "disagreed. If you added a consumer, have it read " +
                "SttReachability.verdict() rather than the cache, and add it " +
                "here deliberately.",
            listOf("BubbleService.kt", "SttReachability.kt"),
            mentions,
        )
    }

    @Test
    fun theWritersAndReadersAreTheDeclaredOnes() {
        val used = sourceFiles()
            .map { f -> apiUsedIn(f) }
            .filter { (_, names) -> names.isNotEmpty() }
            .sortedBy { (name, _) -> name }
            .toMap()

        assertEquals(
            "the per-file set of SttReachability methods called from main " +
                "sources changed. Grown: something new is writing the cache or " +
                "deciding from it. Shrunk: a consumer stopped asking the owner, " +
                "which is how a second copy of the staleness rule gets written. " +
                "Either way, decide it here.",
            mapOf(
                "BubbleService.kt" to listOf(
                    "abandonProbe",
                    "beginProbe",
                    "forget",
                    "nextProbeDelayMs",
                    "onProbeResult",
                    "verdict",
                )
            ),
            used,
        )
    }

    @Test
    fun exactlyOneSiteInTheWholeTreeReadsTheVerdict() {
        val sites = sourceFiles().sumOf { f -> count(normalise(f.readText()), "verdict") }

        assertEquals(
            "🔴 the verdict must be read in exactly ONE place. This is the " +
                "\"one owner\" claim with teeth: the method-name ledger above " +
                "passes with a second verdict() call added to an existing " +
                "consumer, and two sites that decide whether to warn are two " +
                "places for the fail-open contract to be wrong — one of them " +
                "blocking a recording the fallback ladder would have " +
                "transcribed.",
            1,
            sites,
        )
    }

    /**
     * No second probe, and no second HTTP client.
     *
     * `SttHttp.healthz()` is the probe. It already carries its own short read
     * timeout and `SttRetryPolicy.CONNECT_TIMEOUT_MS`, and already maps every
     * transport failure to `false`. The APK has an empty runtime
     * `dependencies {}` block by explicit, re-measured decision, so a second
     * probe would also be the thing that drags in a client.
     *
     * The pattern is the **zero-argument** call, so `SttEndpoints.healthz(url)`
     * — URL construction, not a request — is deliberately not counted.
     */
    @Test
    fun onlyTheDeclaredFilesPerformAHealthProbe() {
        val callers = sourceFiles()
            .filter { f -> HEALTHZ_CALL.containsMatchIn(normalise(f.readText())) }
            .map { it.name }
            .sorted()

        assertEquals(
            "the set of files that call SttHttp.healthz() changed. If this " +
                "grew for a new reachability check, route it through " +
                "BubbleService's existing probe and SttReachability instead — " +
                "a second probe is a second schedule, a second timeout and a " +
                "second cache.",
            listOf("BubbleService.kt", "RemoteTranscriber.kt", "SettingsActivity.kt"),
            callers,
        )
    }

    /**
     * 🔴 The behavioural half. The three structural checks above would all pass
     * with [SttReachability.verdict] returning a constant.
     */
    @Test
    fun theOwnerIsWhatActuallyDecides() {
        var clock = 42_000L
        val owner = SttReachability(now = { clock })

        fun record(reachable: Boolean) {
            owner.onProbeResult(requireNotNull(owner.beginProbe()), reachable)
        }

        assertEquals(
            "never probed: PROCEED",
            SttReachability.Verdict.PROCEED,
            owner.verdict(),
        )
        record(false)
        assertEquals(
            "a fresh definite failure: WARN",
            SttReachability.Verdict.WARN,
            owner.verdict(),
        )
        clock += owner.freshnessWindowMs() + 1_201L
        assertEquals(
            "the same result, stale: PROCEED",
            SttReachability.Verdict.PROCEED,
            owner.verdict(),
        )
        record(true)
        assertEquals(
            "reachable: PROCEED",
            SttReachability.Verdict.PROCEED,
            owner.verdict(),
        )
    }

    /**
     * Controls for every pattern this file reads a verdict from.
     *
     * 🔴 Until this passes, the three ledgers above are facts about regexes.
     * The negative halves matter as much as the positive ones: the tree
     * discusses these method names in prose constantly, so a pattern that saw
     * a comment would list files that call nothing.
     */
    @Test
    fun everyPatternHasBothControls() {
        // --- the method-call pattern, receiver-spelling insensitive ----------
        val hits = listOf(
            "reachability.verdict()",
            "this.reachability . verdict ( )",
            "owner?.verdict()",
            "SttReachability(now = { 0L }).verdict()",
            // The forwarder shape a line-based scan missed once already.
            "private fun v() = reachability.verdict()",
            // Split across lines; normalise() collapses it.
            "reachability\n    .verdict(\n    )",
        )
        for (sample in hits) {
            assertEquals(
                "the pattern must see a verdict() call however the receiver is " +
                    "spelled or wrapped: <$sample>",
                1,
                count(normalise(sample), "verdict"),
            )
        }

        val misses = listOf(
            "// reachability.verdict() <- only a comment",
            "/* reachability.verdict() */",
            "val doc = \"reachability.verdict()\"",
            // The DECLARATION is not a call site, or the owner would count as
            // its own consumer and the ledger could never shrink.
            "fun verdict(): Verdict = PROCEED",
            "@Synchronized fun verdict() : Verdict { return PROCEED }",
            // A mere mention of the name.
            "enum class Verdict { PROCEED, WARN }",
            "import dev.zacx.dicta.SttReachability",
        )
        for (sample in misses) {
            assertEquals(
                "the pattern fired on something that is not a call site, so " +
                    "the counts above would be about prose: <$sample>",
                0,
                count(normalise(sample), "verdict"),
            )
        }

        // --- the healthz pattern ---------------------------------------------
        assertTrue(
            "the healthz pattern must see a zero-argument probe call",
            HEALTHZ_CALL.containsMatchIn(normalise("SttHttp(url, token = \"\").healthz()")),
        )
        assertTrue(
            "...and must NOT see URL construction, which takes an argument — " +
                "SttHttp.kt would otherwise appear as a caller of itself",
            !HEALTHZ_CALL.containsMatchIn(
                normalise("conn = opener(SttEndpoints.healthz(baseUrl)).apply {")
            ),
        )

        // --- normalise() itself ----------------------------------------------
        assertEquals(
            "normalise() must strip raw strings, then ordinary literals, then " +
                "comments, then collapse whitespace — in that order. Comments " +
                "first and a string containing // loses its closing quote, " +
                "after which the literal regex deletes real code between that " +
                "orphan and the next quote it finds.",
            "val a = \"\" ; val b = 1",
            normalise(
                """
                val a = "// not a comment"   // a real one
                /* and a block one */ ; val b = 1
                """.trimIndent()
            ),
        )
    }

    // --- reading the tree ----------------------------------------------------

    private fun apiUsedIn(file: File): Pair<String, List<String>> {
        val src = normalise(file.readText())
        return file.name to API.filter { count(src, it) > 0 }.sorted()
    }

    private fun sourceFiles(): List<File> =
        sourceDir().listFiles { f -> f.name.endsWith(".kt") }.orEmpty().toList()

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
        val files = dir!!.listFiles { f -> f.name.endsWith(".kt") }.orEmpty()
        assertTrue(
            "the source directory held no Kotlin files, so every ledger above " +
                "would be vacuously empty",
            files.size > 10,
        )
        return dir
    }

    private companion object {

        /** Every public method of the owner, so an unused one is also visible. */
        val API = listOf(
            "abandonProbe",
            "beginProbe",
            "currentIntervalMs",
            "failureStreak",
            "forget",
            "freshnessWindowMs",
            "hasKnowledge",
            "isProbeInFlight",
            "nextProbeDelayMs",
            "onProbeResult",
            "verdict",
        )

        /** A zero-argument `.healthz()` — a request, not URL construction. */
        val HEALTHZ_CALL = Regex("""\.\s*healthz\s*\(\s*\)""")

        /**
         * Call sites of [name]: a `.`-qualified call, with the declaration
         * excluded.
         *
         * Receiver-spelling insensitive by construction — it keys on the dot
         * rather than on any identifier, which is the lesson
         * [DictationSessionLedgerTest]'s kdoc records from a ledger that was
         * blind to the one file it existed to protect.
         *
         * The declaration is removed first. Without that the owner counts as
         * its own consumer and the ledger can never report a *shrink*, which is
         * half of what it is for.
         */
        fun count(normalisedSrc: String, name: String): Int {
            val n = Regex.escape(name)
            val withoutDecl = normalisedSrc.replace(Regex("""\bfun\s+$n\s*\("""), " ")
            return Regex("""\.\s*$n\s*\(""").findAll(withoutDecl).count()
        }

        /**
         * Raw strings out, then ordinary string literals out, then comments
         * out, then all whitespace collapsed to single spaces.
         *
         * 🔴 Literals **before** comments, which is [TestCountFloorTest]'s
         * order. The other way round, a string containing `//` — and this tree
         * has several — has its own closing quote eaten by the line-comment
         * regex, after which the literal regex pairs that orphaned quote with
         * the next one it finds and silently deletes real code between them.
         * That is not reasoning: it is the case in
         * [everyPatternHasBothControls]'s `normalise()` assertion, which was
         * first written comments-first, watched to produce the wrong answer,
         * and then reordered. Raw strings go first because they can contain
         * comment delimiters.
         *
         * Whitespace is collapsed last so the patterns can be written with
         * single `\s*` tolerance and a pure `ktfmt` reformat cannot move any
         * count — the spurious red this project has already had once from a
         * line-based scan.
         */
        fun normalise(src: String): String = src
            .replace(Regex("\"\"\".*?\"\"\"", RegexOption.DOT_MATCHES_ALL), "\"\"")
            .replace(Regex("\"(?:\\\\.|[^\"\\\\\\n])*\""), "\"\"")
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("//.*"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
