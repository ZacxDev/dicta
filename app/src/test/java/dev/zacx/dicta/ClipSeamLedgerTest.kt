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
 * 🔴 **Two source sets, and non-recursive within each.** The scan lists
 * `app/src/main/java/dev/zacx/dicta` **and** `app/src/debug/java/dev/zacx/
 * dicta` with `listFiles` and unions them, so a file in a NEW SUBDIRECTORY of
 * either would be invisible to it and the ledger would read clean. Every source
 * file in this app is flat, and this scan is one of the reasons it must stay
 * that way. [theScanActuallyReadsTheTree] is the positive control, and it names
 * a file from each source set so a debug listing that came back empty cannot
 * pass silently. [sourceDirs] fails closed if either directory is missing.
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
        // 🔴 A READER and never a writer: the debug diagnostics surface calls
        // `pending()` to report the queue depth and never `append` or
        // `remove`. It is ledgered anyway — the scan was widened to src/debug
        // in the same change for exactly this reason, because a participant
        // the ledger cannot see is the hole, whatever it does once it is
        // there. `ClipDiagnostics` itself is deliberately NOT here: it is
        // handed a `ClipOutbox` rather than constructing one, which is what
        // keeps it Android-free and unit-testable.
        "DebugClipConfigReceiver.kt",
    )

    @Test
    fun onlyTheDeclaredFilesBuildAClipStoreClient() {
        val writers = sourceFiles()
            .filter { needle("ClipHttp(").containsMatchIn(normalise(it.readText())) }
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
            .filter { needle("ClipOutboxStore(").containsMatchIn(normalise(it.readText())) }
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

    /**
     * The only files that may DRAIN the outbox — i.e. build a [ClipFlusher].
     *
     * 🔴 A THIRD ledger, added by audit round 0, and the relationship it pins is
     * the one that was broken: every drain site must **consume** the
     * [ClipFlusher.Report] it produces. Both sites built one carrying the
     * failure and discarded it, so a mistyped clip token queued every capture
     * forever while every toast said "queued" and the picker rendered the
     * server's own empty list as "nothing stored yet".
     *
     * `ClipFlusher.kt` is a member for the mirror image of the reason
     * [outboxStoreConstructors] records about its own absentee: the pattern
     * `ClipFlusher(` DOES match `class ClipFlusher(`, so the declaring file
     * lands on its own ledger — and is excused the consumption rule, which is
     * about call sites.
     */
    private val clipFlusherConstructors = listOf(
        "BubbleService.kt",
        "ClipFlusher.kt",
        "ClipPickerActivity.kt",
    )

    @Test
    fun everySiteThatDrainsTheOutboxCONSUMESItsReport() {
        // 🔴 THE SEAM NOBODY OWNED. `ClipFlusherTest` proves a failed drain
        // produces a Report that says so; `ClipOutboxHealthTest` proves a RUN of
        // those is a fault and one of them is not; `BubbleClipRevealTest` proves
        // a fault reaches the button. Each of those is scoped to one surface,
        // and the defect lived in none of them — the Report was simply never
        // handed over. So this asserts the RELATIONSHIP: a ledger of the drain
        // sites, plus the rule that each one records.
        //
        // 🔴 Both directions, which is what makes it a ledger rather than a
        // check on today's two files. GROWTH: a third drain is a third place a
        // persistent failure can go unreported, which is this feature's whole
        // silent-failure class. SHRINK: a drain that vanished means captures
        // queue and never leave.
        val drains = sourceFiles()
            .filter { needle("ClipFlusher(").containsMatchIn(normalise(it.readText())) }
            .map { it.name }
            .sorted()
        assertEquals(
            "🔴 the set of files that drain the capture outbox changed. Every " +
                "one of them must hand its ClipFlusher.Report to " +
                "ClipOutboxHealth.record — see ClipOutboxHealth's kdoc for the " +
                "defect that rule exists for. Update this ledger deliberately.",
            clipFlusherConstructors,
            drains,
        )

        val callSites = drains.filter { it != "ClipFlusher.kt" }
        for (name in callSites) {
            val src = normalise(sourceFiles().first { it.name == name }.readText())
            assertTrue(
                "🔴 $name builds a ClipFlusher and never calls " +
                    "ClipOutboxHealth.record. That is audit round 0's finding " +
                    "exactly: the Report is built with the failure in it and " +
                    "dropped on the floor, so a 401 on a mistyped token keeps " +
                    "every capture forever with nothing on screen saying so.",
                needle("ClipOutboxHealth.record(").containsMatchIn(src),
            )
        }

        // 🔴 POSITIVE CONTROL for the LOOP, which is a different claim from the
        // scan's own control below. A ledger holding only `ClipFlusher.kt` would
        // skip every iteration and pass while nothing consumed anything — a
        // zero-iteration loop is indistinguishable from a satisfied one.
        assertEquals(
            "the consumption rule was applied to no file at all",
            2,
            callSites.size,
        )
    }

    /**
     * Every file that records or reads a diagnostics outcome, which must be
     * every file that installs the store.
     *
     * 🔴 THE SEAM PERSISTENCE CREATED. `ClipDiagnostics` holds its outcomes in
     * memory and writes them through to a [ClipDiagnostics.Store] that someone
     * has to install; a recorder that never installs one loses its outcome on
     * the next force-stop, and a READER that never installs one reports `none`
     * for outcomes that are sitting on disk. Both are silent, and both are the
     * exact defect round 0's Q2 was answered to close — so the relationship is
     * pinned rather than the component.
     *
     * Both directions. GROWTH: a fourth surface that records or reads without
     * installing re-opens the hole one file sideways. SHRINK: a site that
     * stopped installing is a process whose diagnostics quietly went back to
     * being process-lifetime.
     *
     * 🔴 **WIDENED (audit round 1, finding 4): this guard was NARROWER THAN ITS
     * OWN NAME.** It says *"records or READS"* and scanned for
     * `ClipDiagnostics.record` and `readout(` only — so the three public
     * readers, [ClipDiagnostics.lastFlush], [ClipDiagnostics.lastFetch] and
     * [ClipDiagnostics.lastFetchOutcome], were uncovered. A file reading
     * `lastFlush()` without installing is precisely the defect in the paragraph
     * above (it reports `none` for an outcome sitting on disk), and the guard
     * read as coverage for it while providing none. [READERS] is now the whole
     * public surface, enumerated so the next function added to it is a visible
     * decision.
     *
     * ⚠ `beginFetch` is deliberately NOT in [READERS]: it neither records nor
     * reads an outcome, it forgets the typed one, and that needs no store — so
     * requiring an install for it would be a rule wider than its own sentence,
     * which is the fault this correction is about.
     */
    @Test
    fun everySiteThatRecordsOrREADSDiagnosticsINSTALLSTheStore() {
        val touching = sourceFiles()
            .filter { file ->
                val src = normalise(file.readText())
                READERS.any { needle(it).containsMatchIn(src) }
            }
            .map { it.name }
            .sorted()
        assertEquals(
            "🔴 the set of files that record or read a ClipDiagnostics " +
                "outcome changed. An ENTRY POINT must call " +
                "ClipDiagnostics.install first — see that object's kdoc for " +
                "the defect (a cold DIAGNOSE reporting `none` for a queue " +
                "that has been failing for days). If the new file is a " +
                "forwarder reached only from an entry point, say so by " +
                "putting it in the forwarder list and explaining why it has " +
                "no Context of its own. Update this ledger deliberately.",
            (diagnosticsEntryPoints + diagnosticsForwarders).sorted(),
            touching,
        )

        for (name in diagnosticsEntryPoints) {
            val src = normalise(sourceFiles().first { it.name == name }.readText())
            assertTrue(
                "🔴 $name records or reads a diagnostics outcome and never " +
                    "calls ClipDiagnostics.install. Without a store, an " +
                    "outcome is held for this process only — which is what " +
                    "the operator rejected: the first DIAGNOSE after a " +
                    "force-stop then says `none` in exactly the situation the " +
                    "readout exists to explain.",
                needle("ClipDiagnostics.install(").containsMatchIn(src),
            )
        }

        // 🔴 POSITIVE CONTROL for the LOOP, not for the scan: an entry-point
        // ledger that came back empty would skip every iteration and pass
        // while nothing installed anything.
        assertEquals(
            "the install rule was applied to no file at all",
            2,
            diagnosticsEntryPoints.size,
        )
    }

    /**
     * The surfaces that record or read a diagnostics outcome **and own a
     * `Context`**, so each must install the store itself.
     *
     * 🔴 `BubbleService.kt` installs one too and is deliberately NOT here: it
     * flushes through `ClipOutboxHealth` rather than recording directly, so the
     * scan does not see it. The rule is that every entry point installs, not
     * that every installer is an entry point — an extra install is harmless and
     * a missing one is the defect.
     */
    private val diagnosticsEntryPoints = listOf(
        "ClipPickerActivity.kt",
        "DebugClipConfigReceiver.kt",
    )

    /**
     * Files that record an outcome but are reached only from an entry point
     * that has already installed the store.
     *
     * 🔴 Enumerated rather than filtered out of the scan, so a second file
     * cannot join this exemption silently: the set comparison above fails on
     * GROWTH here just as it does on the entry points. `ClipOutboxHealth` is
     * on it because it has no `Context` to install with and acquiring one would
     * make the health counter depend on the diagnostics store — the forwarding
     * itself is what keeps the readout's last flush and the bubble's verdict
     * computed from one Report.
     */
    private val diagnosticsForwarders = listOf("ClipOutboxHealth.kt")

    /**
     * Every way to record or READ a diagnostics outcome, as needles over the
     * normalised source — the whole of [ClipDiagnostics]' public outcome
     * surface, writers and readers alike.
     *
     * 🔴 `ClipDiagnostics.record` without a trailing `(` on purpose: it covers
     * `recordFlush` and `recordFetch` both, and a third `record…` function
     * would be covered the moment it is written rather than the moment someone
     * remembers to add it here. The readers are spelled with their `(` because
     * `lastFetch(` must not also match `lastFetchOutcome(`'s kdoc references.
     *
     * 🔴 These are **spellings**, not patterns: [needle] turns each one into a
     * whitespace-tolerant regex, so a `ktfmt` wrap at the dot cannot drop a
     * reader off this ledger. Add a spelling here, never a regex — the
     * tolerance is one rule in one place, and
     * [theWidenedDiagnosticsScanCanSeeEACHWayOfReadingAnOutcome] controls it in
     * both directions.
     */
    private val READERS = listOf(
        "ClipDiagnostics.record",
        "ClipDiagnostics.readout(",
        "ClipDiagnostics.lastFlush(",
        "ClipDiagnostics.lastFetch(",
        "ClipDiagnostics.lastFetchOutcome(",
    )

    @Test
    fun theWidenedDiagnosticsScanCanSeeEACHWayOfReadingAnOutcome() {
        // 🔴 POSITIVE CONTROL for the widening itself, which is a different
        // claim from the ledger passing. A needle with a typo in it — or one
        // that stopped matching after a rename — contributes nothing and the
        // ledger still reads clean, which is exactly how the narrow version of
        // this guard looked correct for three readers it could not see.
        for (spelling in READERS) {
            assertTrue(
                "the needle `$spelling` does not match a call site written with " +
                    "it, so it contributes nothing to the ledger above",
                needle(spelling).containsMatchIn(normalise("fun f() { val x = $spelling 1) }")),
            )
            // 🔴 And the same call site after a REFORMAT. `ktfmt` wraps a long
            // call at the dot, and until the needles became regexes that wrap
            // dropped the file off a ledger asserted to be complete — measured,
            // on this very call in DebugClipConfigReceiver.kt. The old answer
            // was a comment in that file telling the next reader not to
            // reformat it; this is the mechanism that replaced it, so it is
            // asserted here rather than requested there.
            val wrapped = spelling.replaceFirst(".", "\n            .")
            assertTrue(
                "the needle `$spelling` stops matching once the call is " +
                    "wrapped at the dot, so a pure reformat can silently " +
                    "un-cover a reader. Spelled across lines: <$wrapped>",
                needle(spelling).containsMatchIn(normalise("fun f() { val x = $wrapped 1) }")),
            )
        }
        // 🔴 NEGATIVE CONTROL on the tolerance itself: `\s*` must not have
        // widened a needle into "any character". A needle that matched
        // `ClipDiagnosticsXreadout(` would be wider than its own sentence, and
        // every ledger above would then be reporting on files it misread.
        assertFalse(
            "the whitespace tolerance made the dot in a needle match any " +
                "character, so the needles no longer name the calls they say " +
                "they name",
            needle("ClipDiagnostics.readout(")
                .containsMatchIn(normalise("fun f() { ClipDiagnosticsXreadout(1) }")),
        )
        // 🔴 And the READER needles have a real hit in the tree, so widening to
        // them is observable rather than decorative. If no file reads an
        // outcome any more, this control is what should be failing — not the
        // ledger, which would still be green.
        val readerNeedles = READERS.filter { it.contains(".last") }
        val hits = sourceFiles().count { file ->
            val src = normalise(file.readText())
            readerNeedles.any { needle(it).containsMatchIn(src) }
        }
        assertTrue(
            "no file in the shipped source reads an outcome through " +
                "lastFlush()/lastFetch()/lastFetchOutcome(), so the three " +
                "needles added for them cannot change this ledger's answer",
            hits > 0,
        )
    }

    @Test
    fun theTwoLedgersDoNotSILENTLYAgreeByBeingEmpty() {
        // 🔴 POSITIVE CONTROL. All three assertions above are set comparisons,
        // and a scan wired to nothing — a wrong directory, a pattern that
        // stopped matching after a rename — returns an empty list that compares
        // equal to an empty ledger. Non-zero counts are what make a clean
        // result evidence.
        assertTrue("the ClipHttp ledger is empty", clipHttpConstructors.isNotEmpty())
        assertTrue("the outbox ledger is empty", outboxStoreConstructors.isNotEmpty())
        assertTrue("the flusher ledger is empty", clipFlusherConstructors.isNotEmpty())
        val hits = sourceFiles().count {
            needle("ClipHttp(").containsMatchIn(normalise(it.readText()))
        }
        assertEquals(
            "the scan found $hits files constructing ClipHttp — if this is 0 " +
                "the ledger above is vacuous",
            clipHttpConstructors.size,
            hits,
        )
        // The third ledger's own non-zero count. Its consumption loop already
        // has a control, but that one proves the loop RAN — this proves the scan
        // it iterates over saw the tree.
        val flushes = sourceFiles().count {
            needle("ClipFlusher(").containsMatchIn(normalise(it.readText()))
        }
        assertEquals(
            "the scan found $flushes files constructing ClipFlusher — if this " +
                "is 0 the drain ledger is vacuous",
            clipFlusherConstructors.size,
            flushes,
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
            // 🔴 THE HOLE THIS TEST HAD, AND IT WAS LOAD-BEARING.
            // `CaptureActivity.kt:22-25` calls this "the highest-value test in
            // this feature", and the sentence this method asserts claims the
            // receiver is "incapable of reaching … either token". The list
            // held `TokenStore` ONLY — while `ClipSettings.create(context)` is
            // a PUBLIC FACTORY that returns one. Demonstrated: a mutant that
            // read the clip token via `ClipSettings.create(this).tokens
            // .token()` left this test GREEN, while a control mutant
            // (`startActivity(ClipLaunch.intent(this))`) turned it red. So the
            // guard read as coverage for the token claim while providing
            // none, which is worse than providing none because it stops
            // anyone looking.
            //
            // All three routes, not only the one demonstrated:
            // `SttSettings.create` hands back the transcription token's store
            // by the identical shape, and `KeystoreCipher` is the layer under
            // both — a file that names it is unwrapping something itself.
            "ClipSettings",
            "SttSettings",
            "KeystoreCipher",
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
        // random strings of that shape, so this scan covers the SHIPPED source
        // sets — `main` and `debug` — and not `src/test`. Widening it to
        // `debug` matters here more than anywhere: the config surface that
        // takes a token lives there, and an example token pasted into its
        // usage block is the obvious way one would get published.
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
            // 🔴 From the DEBUG source set, which the scan was widened to
            // cover. Without this entry the widening could silently stop
            // working — a zero-file debug listing satisfies every ledger here.
            "DebugClipConfigReceiver.kt",
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

    /**
     * Every Kotlin source the ledgers are claims about.
     *
     * 🔴 **`src/debug` IS SCANNED, and widening it here was part of the change
     * that added that source set.** The scan used to list `src/main` only,
     * which made a debug-only file that opens the capture queue or builds a
     * clip-store client **structurally invisible** to every ledger in this
     * file — the hole they exist to close, reachable by putting the new reader
     * one directory sideways rather than one directory down. A debug build is
     * the build the operator actually runs on the handset, so "it is only in
     * debug" is not a reason the queue's writers may go uncounted.
     * [theScanActuallyReadsTheTree] names a file from each source set.
     */
    private fun sourceFiles(): List<File> =
        sourceDirs().flatMap {
            it.listFiles { f -> f.name.endsWith(".kt") }.orEmpty().toList()
        }

    private fun sourceDirs(): List<File> {
        val dirs = listOf(
            listOf(
                File("app/src/main/java/dev/zacx/dicta"),
                File("src/main/java/dev/zacx/dicta"),
                File("../app/src/main/java/dev/zacx/dicta"),
            ),
            listOf(
                File("app/src/debug/java/dev/zacx/dicta"),
                File("src/debug/java/dev/zacx/dicta"),
                File("../app/src/debug/java/dev/zacx/dicta"),
            ),
        ).map { candidates -> candidates.firstOrNull { it.isDirectory } }
        assertTrue(
            "could not locate the main AND debug source directories from " +
                "${File(".").absolutePath} — refusing to report a pass on " +
                "files that were never read. A missing src/debug is not an " +
                "empty one: the config surface lives there.",
            dirs.all { it != null },
        )
        return dirs.map { it!! }
    }

    /**
     * Code only — [SourceText.normalise].
     *
     * 🔴 **HOISTED (round 0's D5).** This was a THIRD copy of the
     * strip-literals-then-comments pipeline, and the comment here used to
     * record the triplication as a cost and decline to fix it. That is the
     * moment the rule says to stop and consolidate: a predicate open-coded at
     * N sites is typically wrong at N−1 of them, and the two ledger copies
     * were already the same rule spelled two different ways. The order, and
     * why it is load-bearing, now lives in one place with the code.
     */
    private fun normalise(src: String): String = SourceText.normalise(src)

    private companion object {

        /**
         * A call or construction spelling, as a **whitespace-tolerant** regex.
         *
         * 🔴 **This exists because a comment was standing in for a mechanism.**
         * Every needle in this file used to be a plain substring, so a `ktfmt`
         * reformat that wrapped a call — `ClipDiagnostics` on one line,
         * `.readout(` on the next — dropped its file off a ledger that is
         * asserted to be COMPLETE. Measured, not reasoned: wrapping
         * `DebugClipConfigReceiver`'s `ClipDiagnostics.readout(` turned
         * [everySiteThatRecordsOrREADSDiagnosticsINSTALLSTheStore] red with no
         * change in behaviour whatsoever. The response at the time was a
         * comment in that file telling the next reader not to reformat it,
         * which is not a mechanism: it fails in the quiet direction (a reader
         * silently UN-covered) and it only ever covered the one call somebody
         * had already been bitten by.
         *
         * [SourceText.normalise] collapses runs of whitespace to one space, so
         * `\s*` is all the tolerance a wrapped call needs — and it is written
         * `\s*` rather than ` ?` because the normalisation is the floor this
         * sits on, not a second place to re-state its rules.
         *
         * The receiver and the member are escaped, so a `.` in a spelling is a
         * literal dot and not "any character" — a needle that matched
         * `ClipDiagnosticsXreadout(` would be wider than its own sentence.
         */
        fun needle(spelling: String): Regex {
            val call = spelling.endsWith("(")
            val parts = spelling.removeSuffix("(").split(".")
            return Regex(
                parts.joinToString("""\s*\.\s*""") { Regex.escape(it) } +
                    if (call) """\s*\(""" else ""
            )
        }
    }
}
