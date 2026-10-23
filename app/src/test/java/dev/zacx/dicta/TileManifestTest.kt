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

    /** Whether the element declares a `<meta-data>` with this short name. */
    private fun declaresMetaData(element: String, shortName: String): Boolean =
        Regex(
            "<meta-data\\b[^>]*android:name=\"android\\.service\\.quicksettings\\." +
                "$shortName\"[^>]*android:value=\"true\"",
            RegexOption.DOT_MATCHES_ALL,
        ).containsMatchIn(element)

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
