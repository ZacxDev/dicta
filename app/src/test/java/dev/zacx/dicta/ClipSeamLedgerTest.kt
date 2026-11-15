package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Who may touch the shared clipboard, and what the capture receiver may reach.
 *
 * ## 🔴 The two relationships this pins, and why a component test cannot
 *
 * Every piece of this feature is tested in isolation elsewhere — the parser,
 * the store, the client, the flusher, the picker, the receiver. All of those
 * tests are scoped to ONE surface, so none of them can see a defect that lives
 * between two: a second file that opens its own connection to the clip store
 * with its own idea of the auth header, or a capture receiver that grows a path
 * to the microphone. Those are the two relationships below, and each is
 * asserted as a LEDGER — an enumerated set of writers that fails when the set
 * **grows or shrinks**, so both a new participant and a silently deleted one
 * are loud.
 *
 * 🔴 **The ledger is the floor, not the ceiling.** It is a scan of textual call
 * sites with comments and literals stripped, and it cannot see a call reached
 * through a wrapper, a lambda, a factory or a second type —
 * [SttReachabilityLedgerTest]'s own statement of its blind spots applies here
 * unchanged. That is why [theReceiverStartsNothingAtRuntime]'s behavioural twin
 * lives in [CaptureActivityTest]: a structural check type-checks straight past
 * a wrong argument.
 *
 * 🔴 **Non-recursive, deliberately.** The scan lists `app/src/main/java/dev/
 * zacx/dicta` with `listFiles`, so a file in a NEW SUBDIRECTORY would be
 * invisible to it and the ledger would read clean. Every source file in this
 * app is flat, and this scan is one of the reasons it must stay that way.
 * [theScanActuallyReadsTheTree] is the positive control on that.
 */
class ClipSeamLedgerTest {

    /**
     * The only files that may construct an HTTP client for the clip store.
     *
     * 🔴 `ClipHttp.kt` declares it. `BubbleService.kt` builds one for the
     * background drain and `ClipPickerActivity.kt` builds one for the
     * flush-then-fetch on open — two production callers, because the drain runs
     * on two different triggers (a timer while the bubble is up, and the user
     * opening the picker) and the operator can have the bubble disabled.
     *
     * 🔴 A FOURTH entry is the thing this guards. The auth header, the base URL
     * and the limit are three decisions, and a second hand-rolled client is how
     * one of them ends up spelled differently — a 401 that reads like a bad
     * token, or a request network policy refuses.
     */
    private val clipHttpConstructors = listOf(
        "BubbleService.kt",
        "ClipHttp.kt",
        "ClipPickerActivity.kt",
    )

    /**
     * The only files that may read or write the capture queue.
     *
     * `CaptureActivity.kt` is the only writer; `BubbleService.kt` and
     * `ClipPickerActivity.kt` are the two drains (see above).
     *
     * 🔴 `ClipOutbox.kt` is deliberately ABSENT, and its absence is a fact
     * about the scan rather than about the file: the declaration there reads
     * `class ClipOutboxStore internal constructor(` and never `ClipOutboxStore(`,
     * so the pattern does not match its own declaration site. The first draft
     * listed it and went red — recorded, because a reader who expected the
     * declaring file to be in its own ledger would otherwise read the omission
     * as a hole. 🔴 The queue holds the ONLY copy of a capture, so a fourth writer
     * is a second idea of when it is safe to delete one.
     */
    private val outboxStoreConstructors = listOf(
        "BubbleService.kt",
        "CaptureActivity.kt",
        "ClipPickerActivity.kt",
    )

    @Test
    fun onlyTheDeclaredFilesBuildAClipStoreClient() {
        val writers = sourceFiles()
            .filter { normalise(it.readText()).contains("ClipHttp(") }
            .map { it.name }
            .sorted()
        assertEquals(
            "🔴 the set of files that construct a clip-store HTTP client " +
                "changed. GROWTH: a new client is a new place for the " +
                "Authorization header, the base URL and the row limit to be " +
                "spelled differently — and a re-cased header is a 401 that " +
                "reads like a rotated token. SHRINK: a drain that used to " +
                "exist is gone, which means captures queue and never leave. " +
                "Read ClipHttp.kt and this ledger's kdoc, then update it " +
                "deliberately.",
            clipHttpConstructors,
            writers,
        )
    }

    @Test
    fun onlyTheDeclaredFilesTouchTheCaptureQueue() {
        val writers = sourceFiles()
            .filter { normalise(it.readText()).contains("ClipOutboxStore(") }
            .map { it.name }
            .sorted()
        assertEquals(
            "🔴 the set of files that open the capture queue changed. The " +
                "queue holds the ONLY copy of a capture between the toolbar " +
                "tap and the server accepting it, so a new participant is a " +
                "new opinion about when a row may be deleted. GROWTH or " +
                "SHRINK both fail: a drain that disappeared means captures " +
                "queue forever.",
            outboxStoreConstructors,
            writers,
        )
    }

    @Test
    fun theTwoLedgersDoNotSILENTLYAgreeByBeingEmpty() {
        // 🔴 POSITIVE CONTROL. Both assertions above are set comparisons, and
        // a scan wired to nothing — a wrong directory, a pattern that stopped
        // matching after a rename — returns an empty list that compares equal
        // to an empty ledger. Non-zero counts are what make a clean result
        // evidence.
        assertTrue("the ClipHttp ledger is empty", clipHttpConstructors.isNotEmpty())
        assertTrue("the outbox ledger is empty", outboxStoreConstructors.isNotEmpty())
        val hits = sourceFiles().count { normalise(it.readText()).contains("ClipHttp(") }
        assertEquals(
            "the scan found $hits files constructing ClipHttp — if this is 0 " +
                "the ledger above is vacuous",
            clipHttpConstructors.size,
            hits,
        )
    }

    @Test
    fun theReceiverCanReachNEITHERTheMicrophoneNORAnyOtherActivity() {
        // 🔴 CaptureActivity is the app's ONLY exported activity, so any app on
        // the device can start it. The strictest claim available is therefore
        // the right one: it starts nothing at all, and it names nothing on the
        // recording path.
        //
        // 🔴 Asserted over the normalised source, so the long prose in that
        // file about what it does NOT do cannot satisfy its own guard —
        // NetworkManifestTest's comment-stripping lesson, which is in this
        // repository because a guard once passed on its own explanation.
        val src = normalise(receiver().readText())
        for (forbidden in listOf(
            "startActivity",
            "startService",
            "startForegroundService",
            "AudioRecord",
            "MediaRecorder",
            "RECORD_AUDIO",
            "DictateLaunch",
            "HistoryLaunch",
            "SnipLaunch",
            "ClipLaunch",
            "HistoryStore",
            "SnipStore",
            "TokenStore",
        )) {
            assertFalse(
                "🔴 CaptureActivity.kt names `$forbidden`. It is the only " +
                    "EXPORTED activity in this app, which makes it the one " +
                    "surface an arbitrary app can reach — so it must remain " +
                    "a write to the capture queue and nothing else. Anything " +
                    "on this list turns it into a route to the microphone, " +
                    "to another surface, or to the stored token.",
                src.contains(forbidden),
            )
        }
    }

    /*
     * 🔴 DELETED BEFORE IT EVER SHIPPED: `theReceiverStartsNothingAtRuntime`.
     *
     * It asserted that `CaptureActivity.kt` was under a line count, on the
     * argument that past some size the forbidden-name scan above "stops being a
     * credible reading" of the file. That is not a property of the code — the
     * bound was a number picked to be above today's 245 lines, so the only
     * thing it could ever do is go red on a file that grew, naming no defect.
     * A guard whose failure means "someone wrote more comments" is the
     * permanently-red gate this repository already has a rule about.
     *
     * 🔴 What it was reaching for is real and is covered elsewhere: the scan
     * above is TEXTUAL, so a `startActivity` reached through a helper or a
     * lambda is invisible to it. The behavioural case is
     * `CaptureActivityTest.theCaptureActivityStartsNOTHINGAndAsksForNoPermission`,
     * which drives the real activity and asserts on
     * `peekNextStartedActivity()`. This comment is the pointer, because a
     * reader who finds only a structural check here would reasonably believe
     * it was the whole claim.
     */

    @Test
    fun theTokenIsNeverSPELLEDAnywhereInTheSource() {
        // 🔴 `ZacxDev/dicta` IS A PUBLIC REPOSITORY. The bearer token is
        // runtime configuration: it is pasted into a settings field, wrapped by
        // the keystore and stored as ciphertext. A default in source, or a
        // fixture in a test, would publish the operator's real clip store to
        // anyone who clones this.
        //
        // 🔴 Asserted as a SHAPE rather than against the token's value — which
        // is the whole point, since the value must not be in this file either.
        // A 64-character hex run is what the service's tokens look like; the
        // fixtures in ClipHttpTest and ClipEndpointsTest are deliberately
        // random strings of that shape, so this scan covers `main` only and
        // says so.
        val hex = Regex("""\b[0-9a-f]{40,}\b""")
        for (file in sourceFiles()) {
            val hit = hex.find(file.readText())
            assertTrue(
                "🔴 ${file.name} contains a ${hit?.value?.length}-character " +
                    "hex run (${hit?.value?.take(8)}…). This repository is " +
                    "PUBLIC and that is the shape of a bearer token. The " +
                    "token is runtime configuration and must never be in " +
                    "source.",
                hit == null,
            )
        }
    }

    @Test
    fun theTokenScanCanSeeATokenShapedStringWhichIsItsControl() {
        // 🔴 NEGATIVE CONTROL for the test above, which asserts an ABSENCE —
        // indistinguishable from a regex that stopped matching. Fed a string of
        // the right shape, the scan must find it.
        val hex = Regex("""\b[0-9a-f]{40,}\b""")
        assertTrue(
            "the token scan cannot see a token-shaped string, so its clean " +
                "result above proves nothing",
            hex.containsMatchIn(
                "val token = \"" + "0".repeat(32) + "beef".repeat(8) + "\""
            ),
        )
        assertFalse(
            "the scan matches an ordinary identifier, which would make it " +
                "unmaintainable rather than protective",
            hex.containsMatchIn("val baseUrl = ClipEndpoints.DEFAULT_BASE_URL"),
        )
    }

    @Test
    fun theScanActuallyReadsTheTree() {
        // 🔴 POSITIVE CONTROL on the file listing itself. Every assertion in
        // this file is a claim about a set of files, and a scan that found zero
        // files would satisfy several of them silently.
        val files = sourceFiles().map { it.name }
        assertTrue("the scan found no source files at all", files.size > 20)
        for (expected in listOf(
            "ClipHttp.kt",
            "ClipOutbox.kt",
            "CaptureActivity.kt",
            "ClipPickerActivity.kt",
            "BubbleService.kt",
        )) {
            assertTrue(
                "🔴 $expected is not in the scanned listing. The listing is " +
                    "NON-RECURSIVE: if this file was moved into a " +
                    "subdirectory, every ledger here now reads clean while " +
                    "guarding nothing.",
                files.contains(expected),
            )
        }
    }

    // --- the scan ------------------------------------------------------------

    private fun receiver(): File =
        sourceFiles().firstOrNull { it.name == "CaptureActivity.kt" }
            ?: error("CaptureActivity.kt was not found in the scanned listing")

    private fun sourceFiles(): List<File> =
        sourceDir().listFiles { f -> f.name.endsWith(".kt") }.orEmpty().toList()

    private fun sourceDir(): File {
        val dir = listOf(
            File("app/src/main/java/dev/zacx/dicta"),
            File("src/main/java/dev/zacx/dicta"),
            File("../app/src/main/java/dev/zacx/dicta"),
        ).firstOrNull { it.isDirectory }
        assertTrue(
            "could not locate the source directory from " +
                "${File(".").absolutePath} — refusing to report a pass on " +
                "files that were never read",
            dir != null,
        )
        return dir!!
    }

    /**
     * Raw strings out, then ordinary string literals out, then comments out,
     * then all whitespace collapsed.
     *
     * 🔴 LITERALS BEFORE COMMENTS, which is [SttReachabilityLedgerTest]'s and
     * [TestCountFloorTest]'s order. The other way round, a string containing
     * `//` — and this tree has several — has its own closing quote eaten by the
     * line-comment regex, after which the literal regex pairs that orphaned
     * quote with the next one it finds and silently deletes real code between
     * them. That deletion is what makes a ledger read clean.
     *
     * 🔴 A THIRD COPY of this pipeline, and that is a cost rather than a
     * choice: the two existing owners both keep it in a PRIVATE companion, so
     * it cannot be called from here. Consolidating all three into one shared
     * test helper is the right fix and is a change to two files this one does
     * not touch — recorded here rather than done, because widening those
     * companions' visibility inside this change would put an unreviewed edit
     * into the two guards this feature is relying on staying green.
     */
    private fun normalise(src: String): String = src
        .replace(Regex("\"\"\".*?\"\"\"", RegexOption.DOT_MATCHES_ALL), "\"\"")
        .replace(Regex("\"(?:\\\\.|[^\"\\\\\n])*\""), "\"\"")
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("//.*"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
