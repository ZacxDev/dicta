package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 🔴 **The enumerated set of ways a recording can START.** It must be exactly
 * the bubble's tap.
 *
 * Operator decision: *"Trigger via floating bubble only"*, truly. The Quick
 * Settings tile became a bubble toggle, `MainActivity`'s "Try dictation now"
 * button is deleted, and the persistent trigger notification with its *Dictate*
 * action is deleted. Three start surfaces became one.
 *
 * ## Why a SET, and why not a label
 *
 * `OverlayNeverStartsARecordingTest` pins the *overlay card's* control by
 * driving states — the right shape for a view whose label is the role. This
 * file answers a different question, which no amount of state-driving can: how
 * many places in the app can a recording begin from *at all*. That is a
 * property of the wiring, not of any one screen, and the failure mode is
 * **growth** — a fourth surface added later, each one individually reasonable.
 *
 * So it is a ledger: it fails when the set grows **or** shrinks. Shrinking
 * matters as much, because the set shrinking to empty means the app can no
 * longer dictate at all and every other guard here would still be green.
 *
 * 🔴 And it is pinned on **structure, not words**. Nothing below matches a
 * button label, a string name or a comment:
 *
 *  * the bubble's own capture is identified by
 *    `BubbleDictation.Effect.StartRecording` — the single state-machine effect
 *    that means "open the microphone" — and by the event that produces it;
 *  * the second mechanism is identified by a *plain overlay launch*, because
 *    `DictateActivity.onCreate` auto-starts a capture for any launch that
 *    carries neither `EXTRA_ATTACH_SESSION` nor `EXTRA_HISTORY_ID`.
 *
 * Renaming or rewording a button cannot walk any of it. Reintroducing
 * `try_now_button`, a tile `onClick` that opens the overlay, or a notification
 * *Dictate* action all have to add a plain overlay launch, which the second
 * scan counts.
 *
 * ## Comments stripped, whitespace normalised
 *
 * Both are load-bearing, and both come from mistakes a line-based scan made in
 * this repo before: prose mentioning a construction satisfied the scan, and a
 * pure `ktfmt` reformat that wrapped one call across two lines made it go
 * spuriously red. Matching runs over comment-stripped, whitespace-collapsed
 * source, so a reformat is invisible to it and an expression-bodied one-line
 * forwarder is not.
 */
class StartSurfaceLedgerTest {

    /**
     * 🔴 Every way a recording can begin, as `<file>#<function>`.
     *
     * Three entries for **one** user-facing surface, which is the honest count:
     *
     *  * `BubbleService.kt#tapped` — the bubble's `OnClickListener`, the only
     *    production site that raises `Event.BubbleTapped`. This is the tap.
     *  * `BubbleDictation.kt#onTap` — the only transition that emits
     *    `Effect.StartRecording`, i.e. the only decision in the app that means
     *    "start capturing". It is reachable from the entry above and nothing
     *    else.
     *  * `BubbleService.kt#requestMicPermission` — the **same tap, continued**.
     *    A tap with `RECORD_AUDIO` ungranted routes to
     *    `Effect.RequestMicPermission`, which opens the card so it can ask; a
     *    plain launch auto-starts the capture once granted. A `Service` cannot
     *    `requestPermissions`, so this hop is not optional and it is not a
     *    separate surface — a user can only reach it by tapping the bubble.
     */
    private val expected = listOf(
        "BubbleDictation.kt#onTap",
        "BubbleService.kt#requestMicPermission",
        "BubbleService.kt#tapped",
    )

    @Test
    fun theOnlyWayToStartARecordingIsTheBubbleTap() {
        val observed = (bubbleCaptureSites() + plainOverlayLaunchSites()).sorted()
        assertEquals(
            "the set of places a recording can START changed.\n" +
                "  expected: $expected\n" +
                "  observed: $observed\n" +
                "The operator's decision is that the floating bubble's tap is " +
                "the ONLY way to begin a recording. An entry that APPEARED is " +
                "a second start surface — a reintroduced \"Try dictation now\", " +
                "a tile that opens the overlay, a notification Dictate action, " +
                "a widget. An entry that VANISHED means the app may no longer " +
                "be able to dictate at all. Either way this is a decision, not " +
                "a test to adjust.",
            expected,
            observed,
        )
    }

    // --- mechanism 1: the bubble opens the microphone itself -----------------

    /**
     * The state-machine sites that mean "start capturing", plus the sites that
     * can reach them.
     *
     * Deliberately NOT just "`Effect.StartRecording` is emitted once": an
     * emitter is only a start surface if something raises the event that
     * reaches it. Both halves are counted, so a second `Event.BubbleTapped`
     * raiser — a widget, a tile, an accessibility shortcut — grows the set even
     * though the machine itself did not change.
     */
    private fun bubbleCaptureSites(): List<String> {
        val emitters = sitesOf(EMITS_START_RECORDING, skip = setOf())
        val raisers = sitesOf(RAISES_BUBBLE_TAPPED, skip = setOf("BubbleDictation.kt"))

        // The consumer is pinned separately rather than folded into the set: it
        // is not a surface, it is the wire. If `Effect.StartRecording` were
        // handled in two places the set above would not notice, and the second
        // handler could do anything.
        val consumers = sitesOf(CONSUMES_START_RECORDING, skip = setOf("BubbleDictation.kt"))
        assertEquals(
            "Effect.StartRecording is carried out from $consumers. Exactly one " +
                "site may open the microphone, or the ledger above is counting " +
                "decisions while a second handler quietly makes its own.",
            listOf("BubbleService.kt#applyEffects"),
            consumers,
        )

        return emitters + raisers
    }

    // --- mechanism 2: a plain overlay launch auto-starts a capture -----------

    /**
     * Production call sites that construct a **plain** `DictateActivity` launch.
     *
     * `DictateActivity.onCreate` starts a capture for any launch carrying
     * neither `EXTRA_ATTACH_SESSION` nor `EXTRA_HISTORY_ID`, so every such call
     * site is a start surface whatever the control that reaches it looks like.
     * `transcribingIntent` and `historyIntent` carry an extra by construction
     * and are correctly not matched.
     *
     * `DictateLaunch.kt` itself is skipped — it is the definition, and its
     * internal `intent(context)` calls are what the two non-starting helpers
     * delegate to.
     */
    private fun plainOverlayLaunchSites(): List<String> =
        sitesOf(PLAIN_OVERLAY_LAUNCH, skip = setOf("DictateLaunch.kt"))

    // --- the dead surfaces --------------------------------------------------

    /**
     * The deleted surfaces, gone from the **declaration** forms rather than
     * merely unreferenced.
     *
     * Checked by declaration (`android:id="@+id/…"`, `<string name="…"`) and
     * not by a bare mention, because a `grep` hit on a deleted symbol in this
     * repo is usually the comment recording the deletion — the file above this
     * one contains several.
     */
    @Test
    fun theDeletedStartSurfacesAreGoneFromProductionSourcesAndResources() {
        val layout = resource("layout/activity_main.xml")
        val strings = resource("values/strings.xml")
        val gone = mutableListOf<String>()

        for (id in listOf("try_now_button", "notif_switch")) {
            if (layout.contains("android:id=\"@+id/$id\"")) {
                gone += "@+id/$id is still declared in activity_main.xml"
            }
        }
        for (name in listOf("try_now", "notif_row", "notif_text", "trigger_channel", "dictate")) {
            if (strings.contains("<string name=\"$name\"")) {
                gone += "<string name=\"$name\"> is still declared"
            }
        }
        // The Kotlin side: no TRIGGER_* constant and no reference to a deleted
        // id or string survives anywhere in main/.
        //
        // 🔴 Word-bounded, and that is not cosmetic: a plain substring check for
        // `R.string.dictate` also matches the live `R.string.dictate_hint`,
        // `dictate_title` and `dictate_text_hint`, so this guard would have
        // failed on sources that are entirely correct.
        //
        // 🔴 The cleanup object legitimately owns the deleted notification's id
        // and channel VALUES (3001 / "dicta_trigger") so it can cancel what an
        // older build posted — under its own names, which are not these.
        for ((file, src) in allMainSources()) {
            val normalised = stripAndNormalise(src)
            for (symbol in DEAD_KOTLIN_SYMBOLS) {
                if (Regex(Regex.escape(symbol) + """\b""").containsMatchIn(normalised)) {
                    gone += "$file still references $symbol"
                }
            }
        }

        assertEquals(
            "a deleted start surface survives in production sources or " +
                "resources. An id or string left behind is a role a new call " +
                "site reinstates just by spelling it:\n" + gone.joinToString("\n"),
            emptyList<String>(),
            gone,
        )
    }

    /**
     * Positive control for the test above: the checks it runs CAN fire.
     *
     * Without this, "nothing survives" is indistinguishable from a set of
     * predicates that match nothing at all — the declaration spellings could be
     * wrong, or `resource()` could be reading an empty string.
     */
    @Test
    fun theDeadSurfaceScanCanActuallyFire() {
        val layout = resource("layout/activity_main.xml")
        val strings = resource("values/strings.xml")

        // Surviving siblings, in the exact declaration forms the scan uses.
        assertTrue(
            "the layout scan matches no live id, so its spelling of " +
                "android:id=\"@+id/…\" is wrong and its zero means nothing",
            layout.contains("android:id=\"@+id/bubble_switch\"") &&
                layout.contains("android:id=\"@+id/add_tile_button\""),
        )
        assertTrue(
            "the strings scan matches no live string, so its spelling of " +
                "<string name=\"…\" is wrong and its zero means nothing",
            strings.contains("<string name=\"bubble_row\"") &&
                strings.contains("<string name=\"section_bubble\""),
        )
        // A live Kotlin symbol, through the EXACT matcher the scan uses, to
        // prove allMainSources() is reading files and the \b form can fire.
        assertTrue(
            "the source scan sees no live symbol, so it is reading nothing",
            allMainSources().any {
                Regex(Regex.escape("R.id.bubble_switch") + """\b""")
                    .containsMatchIn(stripAndNormalise(it.second))
            },
        )
        // 🔴 The other half of the same control: the \b must stop the deleted
        // `R.string.dictate` matching the live `R.string.dictate_hint`. Without
        // this the guard above is red on a correct tree.
        assertFalse(
            "R.string.dictate matches R.string.dictate_hint, so the dead-symbol " +
                "scan fails on sources that are entirely correct",
            Regex(Regex.escape("R.string.dictate") + """\b""")
                .containsMatchIn("getString(R.string.dictate_hint)"),
        )
        assertTrue(
            "R.string.dictate does not match its own exact spelling, so that " +
                "entry in the dead-symbol list can never fire",
            Regex(Regex.escape("R.string.dictate") + """\b""")
                .containsMatchIn("getString(R.string.dictate)"),
        )
    }

    // --- controls for the ledger's own instruments --------------------------

    /**
     * 🔴 Positive control for every pattern the ledger reads. An empty match
     * set and a pattern that cannot match are the same observation, so until
     * this passes the ledger's result is a fact about its regexes.
     *
     * The sample also pins the two things a line-based version of this scan got
     * wrong in this repo: a construction inside a comment must NOT count, and
     * one wrapped across lines by a formatter MUST.
     */
    @Test
    fun everyLedgerPatternMatchesSomethingAndIgnoresComments() {
        val sample = stripAndNormalise(
            """
            class Sample {
                // listOf(Effect.StartRecording)      <- a comment, must not count
                /* applyEffects(machine.on(BubbleDictation.Event.BubbleTapped(g))) */
                fun a() = listOf(Effect.StartRecording)
                fun b() {
                    applyEffects(machine.on(BubbleDictation.Event.BubbleTapped(granted)))
                }
                fun c() {
                    BubbleDictation.Effect.StartRecording -> startRecording()
                }
                fun d() = startActivity(DictateLaunch.intent(this))
                fun e() = DictateLaunch.pendingIntent(
                    context
                )
                fun f() = Intent(ctx , DictateActivity::class.java)
                fun g() = DictateLaunch.transcribingIntent(this)
                fun h() = DictateLaunch.historyIntent(this, id)
            }
            """.trimIndent()
        )

        assertEquals(
            "EMITS_START_RECORDING must match the real emission and not the " +
                "commented one",
            1,
            EMITS_START_RECORDING.findAll(sample).count(),
        )
        assertEquals(
            "RAISES_BUBBLE_TAPPED must match the real raise and not the " +
                "block-commented one",
            1,
            RAISES_BUBBLE_TAPPED.findAll(sample).count(),
        )
        assertEquals(
            "CONSUMES_START_RECORDING must match the when-branch that carries " +
                "the effect out",
            1,
            CONSUMES_START_RECORDING.findAll(sample).count(),
        )
        assertEquals(
            "PLAIN_OVERLAY_LAUNCH must match intent(), pendingIntent() — " +
                "including the formatter-wrapped spelling — and a hand-built " +
                "Intent, and must NOT match transcribingIntent/historyIntent, " +
                "which carry an extra and start nothing",
            3,
            PLAIN_OVERLAY_LAUNCH.findAll(sample).count(),
        )

        // Negative control for the enclosing-function resolver: it must report
        // the function the match is IN, not the first or last in the file.
        assertEquals(
            listOf("Sample.kt#d", "Sample.kt#e", "Sample.kt#f"),
            sitesIn("Sample.kt", sample, PLAIN_OVERLAY_LAUNCH),
        )
    }

    /**
     * Negative control for the whole ledger: a tree it MUST reject.
     *
     * Feeds the resolver a synthetic second start surface of each deleted
     * shape and asserts the observed set grows. Without this, the ledger's
     * green is a claim about today's tree only — it does not establish that the
     * instrument can see a violation at all.
     */
    @Test
    fun theLedgerSeesAReintroducedStartSurface()  {
        val reintroduced = stripAndNormalise(
            """
            class MainActivity {
                fun onCreate() {
                    findViewById<Button>(R.id.try_now_button).setOnClickListener {
                        startActivity(Intent(this, DictateActivity::class.java))
                    }
                }
                fun showTriggerNotification() {
                    val pi = DictateLaunch.pendingIntent(this)
                }
            }
            """.trimIndent()
        )
        val sites = sitesIn("MainActivity.kt", reintroduced, PLAIN_OVERLAY_LAUNCH)
        assertEquals(
            "the ledger cannot see a reintroduced app-UI start surface, so its " +
                "green says nothing",
            listOf("MainActivity.kt#onCreate", "MainActivity.kt#showTriggerNotification"),
            sites,
        )
        assertFalse(
            "the reintroduced surfaces are not in the expected set, so the " +
                "ledger assertion would genuinely fail on this tree",
            expected.containsAll(sites),
        )
    }

    // --- harness ------------------------------------------------------------

    private fun sitesOf(pattern: Regex, skip: Set<String>): List<String> =
        allMainSources()
            .filter { it.first !in skip }
            .flatMap { (name, src) -> sitesIn(name, stripAndNormalise(src), pattern) }
            .sorted()

    /**
     * Every match of [pattern] in [src], reported as `<file>#<function>`.
     *
     * The enclosing function is the last `fun <name>(` *before* the match by
     * character offset — not by line — which is what makes an expression-bodied
     * one-liner and a formatter-wrapped call both resolve correctly.
     */
    private fun sitesIn(file: String, src: String, pattern: Regex): List<String> =
        pattern.findAll(src).map { m ->
            val before = src.substring(0, m.range.first)
            val fn = FUN_DECL.findAll(before).lastOrNull()?.groupValues?.get(1)
                ?: "<top-level>"
            "$file#$fn"
        }.distinct().toList()

    private fun allMainSources(): List<Pair<String, String>> {
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

    private fun resource(path: String): String {
        val f = listOf(
            File("app/src/main/res/$path"),
            File("src/main/res/$path"),
            File("../app/src/main/res/$path"),
        ).firstOrNull { it.isFile }
        assertTrue("could not read res/$path", f != null)
        return f!!.readText()
    }

    private companion object {

        /**
         * Comments out, whitespace collapsed to single spaces.
         *
         * Collapsing preserves character ORDER, which is all the
         * enclosing-function resolver needs, while making the scan blind to any
         * reformat — the thing that made a line-based ancestor of this test go
         * red on a no-op `ktfmt` run.
         */
        fun stripAndNormalise(src: String): String = src
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("//[^\\n]*"), " ")
            .replace(Regex("\\s+"), " ")

        /**
         * The Kotlin-side spellings of the deleted surfaces. Matched with a
         * trailing `\b`, so `R.string.dictate` cannot be satisfied by the live
         * `R.string.dictate_hint`.
         */
        val DEAD_KOTLIN_SYMBOLS = listOf(
            "TRIGGER_NOTIF_ID", "TRIGGER_CHANNEL", "PREF_NOTIF",
            "R.id.try_now_button", "R.id.notif_switch",
            "R.string.try_now", "R.string.notif_text", "R.string.notif_row",
            "R.string.trigger_channel", "R.string.dictate",
            "showTriggerNotification", "cancelTriggerNotification",
        )

        /** `fun <name>(`, capturing the name. */
        val FUN_DECL = Regex("""\bfun\s+(\w+)\s*\(""")

        /** The machine DECIDING to capture. */
        val EMITS_START_RECORDING =
            Regex("""listOf\(\s*(?:BubbleDictation\.)?Effect\.StartRecording\s*\)""")

        /** The machine's decision being CARRIED OUT. */
        val CONSUMES_START_RECORDING =
            Regex("""Effect\.StartRecording\s*->""")

        /** Anything raising the one event that can reach the decision. */
        val RAISES_BUBBLE_TAPPED =
            Regex("""Event\.BubbleTapped\s*\(""")

        /**
         * A **plain** `DictateActivity` launch — one that carries no extra, so
         * `DictateActivity.onCreate` auto-starts a capture.
         *
         * `DictateLaunch.intent(` and `DictateLaunch.pendingIntent(` are
         * matched with an explicit receiver so `transcribingIntent(` and
         * `historyIntent(` cannot match; the hand-built form is
         * receiver-insensitive for the reason `DictateLaunchTest`'s ledger is.
         */
        val PLAIN_OVERLAY_LAUNCH = Regex(
            """DictateLaunch\.(?:intent|pendingIntent)\s*\(""" +
                """|Intent\(\s*\w+\s*,\s*DictateActivity::class\.java\s*\)"""
        )
    }
}
