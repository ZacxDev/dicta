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
     * The flag must stop claiming a bubble that an app update took away.
     *
     * 🔴 This is **not** a restore path, and the negative control that says so
     * lives in `BootRestoreTest`: the receiver clears the flag on this action
     * and starts nothing. Without the filter entry the receiver is never
     * invoked at all, so the code half of the fix would be dead — which is why
     * the manifest is asserted separately from the behaviour.
     */
    @Test
    fun theBootReceiverAlsoHearsAboutAPackageReplacement() {
        val filter = receiverFilter()
        assertTrue(
            "BubbleBootReceiver's intent-filter does not include " +
                "android.intent.action.MY_PACKAGE_REPLACED, so the code that " +
                "clears the stale flag after an app update is never reached. " +
                "An update stops the service and Android does not restart one " +
                "across a package replacement, so the flag outlives the bubble " +
                "and MainActivity's switch needs two taps to recover. " +
                "Filter: $filter",
            filter.contains("android.intent.action.MY_PACKAGE_REPLACED"),
        )
        // Positive control: an empty or mis-parsed filter would make the
        // assertion above vacuous in the passing direction only if it were
        // assertFalse — but it would make a FAILURE unattributable. BOOT_
        // COMPLETED is independently required, so finding it proves the filter
        // is genuinely being read.
        assertTrue(
            "the receiver's filter came back without BOOT_COMPLETED, so it is " +
                "not being parsed and the assertion above proves nothing. " +
                "Filter: $filter",
            filter.contains("android.intent.action.BOOT_COMPLETED"),
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

    private fun receiverFilter(): String {
        val m = Regex(
            "<receiver\\b[^>]*android:name=\"\\.BubbleBootReceiver\".*?</receiver>",
            RegexOption.DOT_MATCHES_ALL,
        ).find(manifestSource())
        assertTrue(
            "no <receiver>…</receiver> block for .BubbleBootReceiver was found",
            m != null,
        )
        return m!!.value
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
