package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 🔴 The manifest half of the Quick Settings tile being a **stateful toggle**.
 *
 * ## The defect this exists to make loud
 *
 * The tile was declared `ACTIVE_TILE` while it was a stateless launcher that
 * painted the constant `Tile.STATE_INACTIVE`, where it was harmless. Making it
 * the bubble's on/off switch turned that declaration into a correctness bug and
 * nothing in the tree could notice.
 *
 * An **active** tile is bound only for `onTileAdded`, `onTileRemoved`, a tap,
 * and otherwise *only* when the app calls `TileService.requestListeningState()`
 * — it is **not** bound when the user opens the Quick Settings panel. There are
 * zero `requestListeningState` call sites in this app, and the tile's state is
 * derived from `BubbleEnabled.isUp`, which can change with no app code running
 * at all (the user revoking `Display over other apps` from system Settings).
 * So the tile would show a reading taken at some arbitrary earlier moment —
 * "Bubble on" with nothing running — and because `onClick` decides its
 * direction from the same predicate, the tap after a stale render does the
 * opposite of the affordance the user just looked at.
 *
 * The fix is the absence of the declaration: standard (non-active) mode binds
 * the service while the panel is visible, so `onStartListening` re-renders from
 * live state every time the tile is visible, with no call sites to keep in
 * sync.
 *
 * ## 🔴 What this test can and cannot observe
 *
 * **Can:** that the manifest does not declare an active tile without something
 * in the tree that could refresh one, and that the tile declares itself
 * toggleable. Both are facts about files in this repo.
 *
 * **Cannot:** anything about what the platform actually does. Whether SystemUI
 * really calls `onStartListening` on panel open, whether it really withholds
 * that call from an active tile, and whether `TOGGLEABLE_TILE` changes the
 * rendered affordance or the TalkBack announcement are all **device**
 * questions. They are not verified here and are not verified anywhere in this
 * suite — the reasoning above is read off the documented binding contract, not
 * measured. `DictaTileToggleTest` is structurally unable to help: it calls
 * `service.onStartListening()` itself, so no JVM test in this project can
 * observe whether the *system* would.
 *
 * ## Why the first assertion is a relationship and not `assertFalse`
 *
 * "`ACTIVE_TILE` is absent" would forbid a thing that is legitimate when it is
 * wired up. What is actually wrong is the *pair*: an active tile with no
 * refresh caller. Asserting the pair leaves the door open for a maintainer who
 * adds `requestListeningState` deliberately, and closes it on the one-line
 * re-add that silently reintroduces the stale tile.
 *
 * Comments are stripped before every assertion, and that is load-bearing here
 * rather than defensive: the manifest explains this exact decision in prose
 * immediately above the element, naming both `ACTIVE_TILE` and
 * `requestListeningState`. An unstripped scan would read the documentation and
 * report a confident, wrong failure — the trap `MicrophoneFgsManifestTest`,
 * `NetworkManifestTest` and `DictateLaunchTest` all record hitting in the other
 * direction.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TileManifestTest {

    @Test
    fun anActiveTileMustHaveSomethingThatRefreshesIt() {
        val declaresActive = declaresMetaData(tileElementBlock(), "ACTIVE_TILE")
        val refreshers = sourcesCalling(REQUESTS_LISTENING_STATE)

        assertTrue(
            "AndroidManifest.xml declares DictaTileService as ACTIVE_TILE, but " +
                "nothing in app/src/main calls TileService." +
                "requestListeningState(). An active tile is bound ONLY for " +
                "onTileAdded/onTileRemoved, for a tap, and for an explicit " +
                "requestListeningState — NOT when the Quick Settings panel " +
                "opens. This tile's state comes from BubbleEnabled.isUp, which " +
                "changes when the user revokes the overlay permission with no " +
                "app code running, so the tile would paint a stale reading " +
                "and the next tap would invert it. Either drop the ACTIVE_TILE " +
                "meta-data (standard mode re-renders on every panel open) or " +
                "call requestListeningState from every place the state can " +
                "change. Found refresh call sites: $refreshers",
            !declaresActive || refreshers.isNotEmpty(),
        )
    }

    /**
     * 🔴 Positive control for the assertion above, and the reason it is not
     * vacuous.
     *
     * The shipped manifest does **not** declare `ACTIVE_TILE`, so
     * `!declaresActive` is `true` and that assertion passes without ever
     * consulting the call-site scan. On its own it is therefore
     * indistinguishable from a test wired to nothing. This drives the same two
     * instruments over inputs whose answers are known, in both directions.
     */
    @Test
    fun theRelationshipCheckCanActuallyGoRed() {
        val withActive = """
            <manifest><application>
            <service android:name=".DictaTileService" android:exported="true">
                <meta-data android:name="android.service.quicksettings.ACTIVE_TILE"
                    android:value="true" />
            </service>
            </application></manifest>
        """.trimIndent()
        assertTrue(
            "the meta-data scan cannot see a real ACTIVE_TILE declaration, so " +
                "anActiveTileMustHaveSomethingThatRefreshesIt could never fire",
            declaresMetaData(tileElementBlockIn(withoutComments(withActive)), "ACTIVE_TILE"),
        )

        val commentOnly = """
            <manifest><application>
            <!-- ACTIVE_TILE is deliberately absent; re-adding it needs a
                 requestListeningState caller. android:name=
                 "android.service.quicksettings.ACTIVE_TILE" -->
            <service android:name=".DictaTileService" android:exported="true">
                <meta-data android:name="android.service.quicksettings.TOGGLEABLE_TILE"
                    android:value="true" />
            </service>
            </application></manifest>
        """.trimIndent()
        assertFalse(
            "a meta-data named only inside a comment satisfied the scan. The " +
                "real manifest documents this decision in prose right above " +
                "the element, so this would be a confident wrong failure.",
            declaresMetaData(tileElementBlockIn(withoutComments(commentOnly)), "ACTIVE_TILE"),
        )

        // The call-site scan's own positive control: it must be able to return
        // a non-empty set, or "no refresh call sites" is indistinguishable from
        // a scan that reads nothing.
        assertEquals(
            "the call-site pattern does not see a requestListeningState call, " +
                "so reporting zero of them says nothing about the tree",
            2,
            REQUESTS_LISTENING_STATE.findAll(
                normalise(
                    """
                    fun a() { TileService.requestListeningState(ctx, name) }
                    fun b() = requestListeningState (
                        context,
                        ComponentName(context, DictaTileService::class.java),
                    )
                    """.trimIndent()
                )
            ).count(),
        )
        assertFalse(
            "the call-site pattern fired on prose mentioning the API, which is " +
                "exactly what this file's own kdoc and the manifest contain",
            REQUESTS_LISTENING_STATE.containsMatchIn(
                normalise("// nothing calls requestListeningState() today")
            ),
        )
    }

    /**
     * 🔴 The control the old scan could not have passed: **attribute order
     * carries no meaning in XML, so the scan must not depend on it.**
     *
     * [declaresMetaData] used to require `android:name` before
     * `android:value="true"`, which made a value-attribute-first re-add of
     * `ACTIVE_TILE` invisible — and invisible in the fail-OPEN direction for
     * [anActiveTileMustHaveSomethingThatRefreshesIt], which then passes with the
     * stale-tile hazard present. Every fixture in this file, including that
     * assertion's own positive control, spelled the attributes name-first, so
     * nothing here could observe it.
     *
     * Both orders of the *same* declaration must give the same answer, and the
     * negatives must stay negative — a different short name, and
     * `android:value="false"`, which is not a declaration of anything.
     */
    @Test
    fun theMetaDataScanIsIndifferentToAttributeOrder() {
        fun block(metaData: String) = tileElementBlockIn(
            withoutComments(
                """
                <manifest><application>
                <service android:name=".DictaTileService" android:exported="true">
                    $metaData
                </service>
                </application></manifest>
                """.trimIndent()
            )
        )

        val active = "android.service.quicksettings.ACTIVE_TILE"
        val nameFirst = """<meta-data android:name="$active" android:value="true" />"""
        val valueFirst = """<meta-data android:value="true" android:name="$active" />"""
        val valueFalse = """<meta-data android:value="false" android:name="$active" />"""

        assertTrue(
            "the scan cannot see an ACTIVE_TILE declaration spelled " +
                "name-attribute-first, so the order control below would be " +
                "comparing two negatives",
            declaresMetaData(block(nameFirst), "ACTIVE_TILE"),
        )
        assertTrue(
            "the meta-data scan MISSED an ACTIVE_TILE declaration spelled " +
                "value-attribute-first. XML attribute order means nothing, so " +
                "that is the same declaration as the one above — and the miss " +
                "fails OPEN: anActiveTileMustHaveSomethingThatRefreshesIt " +
                "reads !declaresActive as true and goes green with a stale " +
                "active tile shipped. Read the state out of a parse tree, not " +
                "out of one spelling of it.",
            declaresMetaData(block(valueFirst), "ACTIVE_TILE"),
        )
        assertFalse(
            "the scan answered yes for TOGGLEABLE_TILE while only ACTIVE_TILE " +
                "was declared, so the short name is not being compared and " +
                "both meta-data assertions in this file mean the same thing",
            declaresMetaData(block(valueFirst), "TOGGLEABLE_TILE"),
        )
        assertFalse(
            "android:value=\"false\" satisfied the scan. The platform reads " +
                "the value, so a declaration switched off is not a declaration " +
                "— and counting it would make theTileDeclaresItselfAToggle " +
                "pass for a tile that declares itself NOT toggleable.",
            declaresMetaData(block(valueFalse), "ACTIVE_TILE"),
        )
    }

    @Test
    fun theTileDeclaresItselfAToggle() {
        assertTrue(
            "DictaTileService does not declare TOGGLEABLE_TILE. This tile's " +
                "whole job is to switch the floating bubble on and off, and " +
                "the platform uses this meta-data to give it the on/off " +
                "affordance and the two-state accessibility role rather than " +
                "treating it as a launcher button. It is ignored by releases " +
                "that predate it, so it is safe at minSdk 26. (The visible and " +
                "TalkBack effects are device questions and are NOT verified " +
                "anywhere in this suite.)",
            declaresMetaData(tileElementBlock(), "TOGGLEABLE_TILE"),
        )
    }

    /**
     * The bubble must come back after an app update, which needs this action in
     * the filter.
     *
     * 🔴 **This kdoc and the assertion below used to describe the OPPOSITE
     * behaviour as live fact** — "this is not a restore path … the receiver
     * clears the flag on this action and starts nothing" — and cross-referenced
     * `BootRestoreTest.aPackageReplacementClearsTheStaleFlagAndStartsNothing`,
     * a test that asserted the measured defect and has been deleted. Nothing
     * went red, because the assertion is about the manifest and the manifest
     * entry is needed either way: it read as coverage for the opposite of what
     * ships. Rewritten rather than deleted — the filter entry is load-bearing.
     *
     * `BubbleBootReceiver` now handles `MY_PACKAGE_REPLACED` exactly as it
     * handles `BOOT_COMPLETED`: restore the bubble if the user wants it and the
     * overlay permission allows it, and write the flag in neither case. Without
     * this filter entry the receiver is never invoked on an update at all, so
     * the restore is dead code — which is why the manifest is asserted
     * separately from the behaviour (`BootRestoreTest`).
     *
     * ## 🔴 STRUCTURAL, not spelled
     *
     * This used to ask whether the action string appeared *anywhere* in the
     * `<receiver>` block's text, and that is a guard on a spelling rather than
     * on the declaration. **Measured walkable:** with the real
     * `<action android:name="…MY_PACKAGE_REPLACED" />` DELETED and the same
     * string present only as a `<meta-data android:value="…">` on the receiver,
     * the old assertion went GREEN — a receiver that hears nothing about a
     * package replacement, passing a test whose whole subject is that it does.
     *
     * ⚠ What was *not* walkable, and the reason is worth recording so nobody
     * re-derives it: a **comment** naming the action could never satisfy it,
     * because [manifestSource] strips comments before anything looks at the
     * text. That is also measured — replacing the declaration with a comment
     * that names it fails this test both before and after this change. The hole
     * was the meta-data shape, not the 26 lines of comment inside the filter.
     *
     * So the state is read out of the parse tree: the `android:name` of every
     * `<action>` child of the receiver's `<intent-filter>`, which is the only
     * place the platform reads one from.
     * [theFilterScanReadsDECLARATIONSAndNotTheTextAroundThem] carries the
     * controls on that.
     */
    @Test
    fun theBootReceiverAlsoHearsAboutAPackageReplacement() {
        val actions = receiverFilterActions()
        assertTrue(
            "BubbleBootReceiver's intent-filter does not DECLARE an <action> " +
                "for android.intent.action.MY_PACKAGE_REPLACED, so the code " +
                "that restores the bubble after an app update is never " +
                "reached. An update stops the service and Android does not " +
                "restart one across a package replacement, so without this the " +
                "bubble is gone after every install while the user's stored " +
                "intent still says they want it. Declared actions: $actions",
            "android.intent.action.MY_PACKAGE_REPLACED" in actions,
        )
        // Positive control: an empty or mis-parsed filter would make the
        // assertion above vacuous in the passing direction only if it were
        // assertFalse — but it would make a FAILURE unattributable. BOOT_
        // COMPLETED is independently required, so finding it proves the filter
        // is genuinely being read.
        assertTrue(
            "the receiver's declared actions came back without BOOT_COMPLETED, " +
                "so the filter is not being parsed and the assertion above " +
                "proves nothing. Declared actions: $actions",
            "android.intent.action.BOOT_COMPLETED" in actions,
        )
    }

    /**
     * 🔴 The controls for the structural rewrite above: the scan reads
     * **declarations**, and nothing else in the receiver's text can stand in
     * for one.
     *
     * Three fixtures, each the real manifest's receiver block with one thing
     * changed, so a pass here is attributable:
     *
     *  1. the declaration present — the scan must see it, or every `assertTrue`
     *     above is about an empty set;
     *  2. the declaration deleted — the scan must NOT see it;
     *  3. the declaration deleted and the action string left in a
     *     `<meta-data android:value>` — **the mutation the old spelled guard
     *     went green for**, and the one this control exists for. A `<meta-data>`
     *     is a legal child of `<receiver>`, so this is not a contrived shape.
     *
     * A comment fixture is deliberately absent: comments never reach this
     * code — [manifestSource] strips them — so a control here would be
     * asserting the strip twice rather than the parse.
     */
    @Test
    fun theFilterScanReadsDECLARATIONSAndNotTheTextAroundThem() {
        val real = receiverBlock(manifestSource())
        val replaced = "android.intent.action.MY_PACKAGE_REPLACED"
        val declaration = """<action android:name="$replaced" />"""
        assertTrue(
            "the fixture failed: the receiver block cut out of the manifest no " +
                "longer contains the declaration as spelled here, so the two " +
                "negative controls below would be deleting nothing",
            real.contains(declaration),
        )

        assertTrue(
            "the scan cannot see the action declared in the real receiver " +
                "block, so every assertion built on it is about an empty set",
            replaced in filterActionsIn(real),
        )

        val deleted = real.replace(declaration, "")
        assertFalse(
            "the scan reported the action after its <action> declaration was " +
                "deleted outright, so it is not reading declarations at all",
            replaced in filterActionsIn(deleted),
        )

        val asMetaData = deleted.replace(
            "</intent-filter>",
            """</intent-filter>
            <meta-data android:name="dev.zacx.dicta.probe" android:value="$replaced" />""",
        )
        assertFalse(
            "🔴 THE WALK. The <action> declaration is gone and the action " +
                "string is present only as a <meta-data android:value>, and " +
                "the scan still reported it. That is the state the previous " +
                "substring form of this guard went GREEN for: the receiver " +
                "hears nothing about a package replacement, the bubble is gone " +
                "after every install, and the guard says the declaration is " +
                "there. Read the <action> children of the <intent-filter>, not " +
                "the receiver's text.",
            replaced in filterActionsIn(asMetaData),
        )
    }

    /**
     * 🔴 The premise `BubbleEnabled.isUp` would silently depend on if anyone
     * ever added a liveness term to it.
     *
     * A process-local record of "is BubbleService running" was tried and
     * rejected (`isUp`'s kdoc says why: `startForegroundService` returns before
     * `onCreate` runs, so the tile would paint itself off in the instant after
     * the user switched the bubble on). If it is ever reconsidered, it is sound
     * only while the app is single-process, and that is invisible at the call
     * site. Cheap to pin now, so the day a surface gets its own process the
     * failure names itself instead of appearing as a bubble that reads off
     * while it is plainly on screen.
     */
    @Test
    fun theAppIsSingleProcess() {
        val declarations = Regex("""android:process\s*=""")
            .findAll(manifestSource())
            .count()
        assertEquals(
            "AndroidManifest.xml now sets android:process somewhere. Any " +
                "static shared between MainActivity, DictaTileService and " +
                "BubbleService stops being one value — see BubbleEnabled.isUp " +
                "and BubbleService's companion for what that premise is used " +
                "for. If the split is deliberate, audit those before changing " +
                "this number.",
            0,
            declarations,
        )
    }

    // --- plumbing ------------------------------------------------------------

    private fun manifestSource(): String = withoutComments(
        read(
            "app/src/main/AndroidManifest.xml",
            "src/main/AndroidManifest.xml",
            "../app/src/main/AndroidManifest.xml",
            what = "AndroidManifest.xml",
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

    private fun withoutComments(xml: String): String =
        xml.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    /**
     * The whole `<service>` element for the tile, children included — unlike
     * `MicrophoneFgsManifestTest`'s helper, which wants only the opening tag.
     * The meta-data this file asserts on are *children*, so stopping at the
     * first `>` would make every assertion here vacuous.
     */
    private fun tileElementBlock(): String = tileElementBlockIn(manifestSource())

    private fun tileElementBlockIn(xml: String): String {
        val m = Regex(
            "<service\\b[^>]*android:name=\"\\.DictaTileService\".*?</service>",
            RegexOption.DOT_MATCHES_ALL,
        ).find(xml)
        assertTrue(
            "no <service>…</service> block for .DictaTileService was found — " +
                "the assertions that follow would be vacuous",
            m != null,
        )
        return m!!.value
    }

    private fun receiverBlock(xml: String): String {
        val m = Regex(
            "<receiver\\b[^>]*android:name=\"\\.BubbleBootReceiver\".*?</receiver>",
            RegexOption.DOT_MATCHES_ALL,
        ).find(xml)
        assertTrue(
            "no <receiver>…</receiver> block for .BubbleBootReceiver was found",
            m != null,
        )
        return m!!.value
    }

    /**
     * The `android:name` of every `<action>` the boot receiver's
     * `<intent-filter>` **declares** — the only place the platform reads one
     * from, so the only place a guard on "the receiver hears about X" may look.
     *
     * 🔴 An `<action>` outside an `<intent-filter>`, and an action string
     * sitting in any other attribute, are deliberately NOT here: the platform
     * ignores both, and counting them is what made the previous substring form
     * of [theBootReceiverAlsoHearsAboutAPackageReplacement] walkable.
     */
    private fun receiverFilterActions(): Set<String> =
        filterActionsIn(receiverBlock(manifestSource()))

    private fun filterActionsIn(receiver: String): Set<String> {
        val doc = parseXml(receiver)
        val filters = doc.getElementsByTagName("intent-filter")
        assertTrue(
            "the receiver block declares no <intent-filter> at all, so a set " +
                "of declared actions read out of it would be empty for a " +
                "reason that has nothing to do with any one action:\n$receiver",
            filters.length > 0,
        )
        val out = mutableSetOf<String>()
        for (i in 0 until filters.length) {
            val children = filters.item(i).childNodes
            for (j in 0 until children.length) {
                val child = children.item(j)
                if (child.nodeName != "action") continue
                child.attributes?.getNamedItem("android:name")?.nodeValue?.let { out += it }
            }
        }
        return out
    }

    /**
     * Whether the element declares a `<meta-data>` with this short name and
     * `android:value="true"`.
     *
     * 🔴 **Parsed, not pattern-matched, and that is the point.** This used to be
     * a regex requiring `android:name="…"` to appear *before*
     * `android:value="true"` inside the element. XML attribute order carries no
     * meaning, so `<meta-data android:value="true"
     * android:name="…ACTIVE_TILE" />` is the **same declaration** and the regex
     * could not see it — and for [anActiveTileMustHaveSomethingThatRefreshesIt]
     * that miss **FAILS OPEN**: `declaresActive` reads `false`,
     * `!declaresActive` is `true`, and the test passes with a stale active tile
     * shipped. (The `TOGGLEABLE_TILE` assertion fails closed under the same
     * mutation, so only one direction was walkable — one is enough.) Every
     * fixture in this file spelled the attributes name-first, so its own
     * positive control could not see it either;
     * [theMetaDataScanIsIndifferentToAttributeOrder] is what now does.
     *
     * A guard that can pass while the hazard exists in a different *spelling*
     * is not a guard on the hazard, so the state is read out of a parse tree
     * where spelling has already been normalised away.
     */
    private fun declaresMetaData(element: String, shortName: String): Boolean {
        val nodes = parseXml(element).getElementsByTagName("meta-data")
        return (0 until nodes.length).any { i ->
            val attrs = nodes.item(i).attributes ?: return@any false
            attrs.getNamedItem("android:name")?.nodeValue ==
                "android.service.quicksettings.$shortName" &&
                attrs.getNamedItem("android:value")?.nodeValue == "true"
        }
    }

    /**
     * Namespace-**unaware** on purpose: these fragments are cut out of the
     * manifest, so the `android` prefix's `xmlns` declaration is left behind on
     * `<manifest>` and a namespace-aware parse would fail on every one of them.
     * Unaware, the attribute's literal qualified name is just a name, which is
     * what is compared.
     *
     * A parse failure is asserted rather than swallowed: a `false` from an
     * unparsed fragment is a confident absence for a declaration nothing ever
     * looked at, which is the same fail-open shape the regex had.
     */
    private fun parseXml(xml: String): org.w3c.dom.Document {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        val parsed = runCatching {
            factory.newDocumentBuilder()
                .parse(java.io.ByteArrayInputStream(xml.toByteArray()))
        }
        assertTrue(
            "could not parse this XML fragment (${parsed.exceptionOrNull()}), " +
                "so every meta-data assertion over it would report a confident " +
                "absence for a declaration it never read:\n$xml",
            parsed.isSuccess,
        )
        return parsed.getOrThrow()
    }

    /** Production files containing a call matching [pattern]. */
    private fun sourcesCalling(pattern: Regex): List<String> {
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
        val files = dir!!.listFiles { f -> f.name.endsWith(".kt") }.orEmpty().toList()
        assertTrue(
            "the source directory held no Kotlin files, so a scan of it would " +
                "report zero call sites whatever the tree contains",
            files.size > 10,
        )
        return files
            .filter { pattern.containsMatchIn(normalise(it.readText())) }
            .map { it.name }
            .sorted()
    }

    private companion object {
        /**
         * A call to `requestListeningState`, however it is qualified or
         * wrapped. Deliberately matches the bare name as well as
         * `TileService.requestListeningState` — the point is to find *any*
         * refresh caller, so a narrower pattern would report zero for a tree
         * that has one.
         */
        val REQUESTS_LISTENING_STATE = Regex("""\brequestListeningState\s*\(""")

        /** Comments out, whitespace collapsed — as `BubbleEnabledWritersSeamTest`. */
        fun normalise(src: String): String = src
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//.*"), "")
            .replace(Regex("\\s+"), " ")
    }
}
