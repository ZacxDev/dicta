package dev.zacx.dicta

import android.content.Intent
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The manifest facts that decide whether the capture path exists at all.
 *
 * ## 🔴 Why these are tests rather than a comment
 *
 * Every failure below is invisible at build time, and all of them look the same
 * from the handset: the menu item simply is not there. There is no error, no
 * log line and nothing to search for.
 *
 *  * No `<intent-filter>` for `ACTION_PROCESS_TEXT` → no selection-toolbar
 *    item, ever.
 *  * `exported="false"` → the filter is declared and the platform refuses to
 *    show it, because the caller is another app.
 *  * No `mimeType="text/plain"` → the filter matches nothing, since the system
 *    always sends a type.
 *  * A shared `taskAffinity` → the receiver lands in the bubble's task and Back
 *    walks the user through a surface they never opened (issue #11's defect
 *    class).
 *
 * 🔴 **This file reads the SOURCE manifest, not only the parsed one.** An
 * attribute's *absence* is the property in two of the cases, and an absent
 * attribute is not distinguishable through `ActivityInfo` from one set to the
 * platform default — [NetworkManifestTest]'s reason, verbatim.
 *
 * 🔴 **Invariant guards, not regression coverage.** Both activities are new.
 *
 * ⚠ **What no test in this repository can see:** whether the item actually
 * appears in the toolbar, and under what label, on the target handset. Samsung
 * reimplements the selection toolbar on One UI, and **no device was reachable
 * while this was written**. The pull request carries the on-device checklist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureManifestTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    private fun manifestSource(): String =
        File(
            listOf(
                "app/src/main/AndroidManifest.xml",
                "src/main/AndroidManifest.xml",
                "../app/src/main/AndroidManifest.xml",
            ).firstOrNull { File(it).exists() }
                ?: error(
                    "could not locate AndroidManifest.xml from " +
                        "${File(".").absolutePath} — refusing to report a pass " +
                        "on a file that was never read"
                )
        ).readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    /** The `<activity …>` opening tag for [name], comments already stripped. */
    private fun element(name: String): String {
        val source = manifestSource()
        val at = source.indexOf("android:name=\"$name\"")
        assertTrue("there is no <activity android:name=\"$name\"> at all", at >= 0)
        val open = source.lastIndexOf('<', at)
        val close = source.indexOf('>', at)
        return source.substring(open, close + 1)
    }

    // --- the capture receiver ------------------------------------------------

    @Test
    fun theSystemCanResolveTheSelectionToolbarIntentToTheCaptureActivity() {
        // 🔴 Resolved through the PACKAGE MANAGER, over a realistic intent,
        // rather than by looking for a string in the XML. A filter can be
        // present and still not match — a missing mimeType or a missing DEFAULT
        // category each produce exactly that, and a substring check on
        // "PROCESS_TEXT" would pass for both.
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, "selected words")
        }
        val matches = ctx.packageManager
            .queryIntentActivities(intent, 0)
            .map { it.activityInfo.name }
        assertTrue(
            "nothing in this app answers ACTION_PROCESS_TEXT for text/plain, " +
                "so the selection-toolbar item cannot exist. Matched: $matches",
            matches.contains(CaptureActivity::class.java.name),
        )
    }

    @Test
    fun theSystemCanResolveAPlainTextSHAREToTheCaptureActivity() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "shared words")
        }
        val matches = ctx.packageManager
            .queryIntentActivities(intent, 0)
            .map { it.activityInfo.name }
        assertTrue(
            "nothing in this app answers a text/plain share. Matched: $matches",
            matches.contains(CaptureActivity::class.java.name),
        )
    }

    @Test
    fun theResolverFindsNothingForAnIMAGEShareWhichIsThePositiveControl() {
        // 🔴 NEGATIVE CONTROL for the two tests above. They assert that a
        // query MATCHES, which a resolver configured to match everything would
        // also satisfy. This feeds a share the app must NOT claim and requires
        // zero of its activities back — so a passing match above is evidence
        // about the filter rather than about the resolver.
        val intent = Intent(Intent.ACTION_SEND).apply { type = "image/png" }
        val mine = ctx.packageManager
            .queryIntentActivities(intent, 0)
            .map { it.activityInfo.name }
            .filter { it.startsWith("dev.zacx.dicta") }
        assertEquals(
            "this app claims an image share, which it has no way to handle",
            emptyList<String>(),
            mine,
        )
    }

    @Test
    fun theCaptureActivityIsEXPORTEDBecauseEveryCallerIsAnotherApp() {
        // 🔴 The one deliberately exported activity in the app, and the only
        // one that can be. A PROCESS_TEXT or SEND caller is by definition the
        // app the user is in; exported="false" would make the platform hide
        // the item with no error anywhere.
        //
        // Its blast radius is bounded by what it DOES rather than by who may
        // start it — see CaptureActivityTest's "starts NOTHING" case and
        // ClipSeamLedgerTest's structural half.
        assertTrue(
            "CaptureActivity is not exported, so no other app can reach it " +
                "and the toolbar item cannot appear",
            element(".CaptureActivity").contains("android:exported=\"true\""),
        )
    }

    @Test
    fun theCaptureActivityLeavesNoTraceInRecentsOrInHistory() {
        // 🔴 Privacy, not housekeeping. It is started over whatever app the
        // user is in and it finishes in onCreate; a Recents entry titled with
        // this app would tell anyone who opens the switcher that the user
        // captured something, and `noHistory` is what stops a stale instance
        // being resurrected with the previous selection still in its intent.
        val el = element(".CaptureActivity")
        assertTrue("noHistory is not set", el.contains("android:noHistory=\"true\""))
        assertTrue(
            "excludeFromRecents is not set",
            el.contains("android:excludeFromRecents=\"true\""),
        )
    }

    @Test
    fun theCaptureActivityDrawsNOTHINGAndSoWearsNoOverlayTheme() {
        // 🔴 NARROWED, and the wide version WAS the bug. This asserted that the
        // element carries no `android:theme=` AT ALL, which is wider than its
        // own stated reason — and the reason is only about one theme. With no
        // theme declared the activity inherited <application>'s
        // @style/Theme.Dicta, parented on android:Theme.Material.NoActionBar
        // with an OPAQUE windowBackground; since it calls no setContentView,
        // the platform raised a full-screen empty window over whatever the user
        // was reading before finish(). So the guard did not merely fail to
        // catch finding #2 of this branch's audit round 0 — it forbade the fix.
        //
        // What is actually required is that it does not wear the OVERLAY theme:
        // that is what makes a window floating, it is what
        // FloatingOverlaySeamLedgerTest derives its set of floating surfaces
        // from, and a receiver that shows no window would then owe a
        // FloatingWindowSizeTest case for geometry it never has — a guard
        // asserting a size for a window that does not exist.
        val el = element(".CaptureActivity")
        assertFalse(
            "CaptureActivity declares @style/Theme.Dicta.Overlay. That theme " +
                "makes a window FLOATING and puts the activity on " +
                "FloatingOverlaySeamLedgerTest's ledger, which would then owe " +
                "a FloatingWindowSizeTest case for a window this activity " +
                "never shows at all",
            el.contains(OVERLAY_THEME_ATTR),
        )

        // 🔴 And the other half, which the wide version could not express: it
        // must declare a TRANSLUCENT theme rather than inherit the app's opaque
        // one. Asserted positively, because "no overlay theme" is satisfied by
        // the defect as well as by the fix.
        assertTrue(
            "CaptureActivity declares no translucent theme, so it inherits " +
                "<application>'s @style/Theme.Dicta — an OPAQUE window " +
                "background on an activity that calls no setContentView, i.e. " +
                "a full-screen blank window raised over the app the user is " +
                "reading. Element: $el",
            el.contains(TRANSLUCENT_THEME_ATTR),
        )

        // 🔴 NEGATIVE CONTROL for the narrowed predicate, on a synthetic
        // element: both halves must be able to FAIL, or a green reading above
        // is a fact about two `contains` calls. The overlay theme must trip the
        // first; an element with no theme at all must trip the second.
        val wearingTheOverlayTheme =
            """<activity android:name=".CaptureActivity" $OVERLAY_THEME_ATTR>"""
        assertTrue(
            "the narrowed guard cannot see the overlay theme it exists to " +
                "forbid, so its verdict on the real element means nothing",
            wearingTheOverlayTheme.contains(OVERLAY_THEME_ATTR),
        )
        assertFalse(
            "the translucent-theme check matches an element that declares no " +
                "theme at all — which is the state audit round 0 found and is " +
                "precisely what it has to reject",
            """<activity android:name=".CaptureActivity" android:exported="true">"""
                .contains(TRANSLUCENT_THEME_ATTR),
        )
    }

    // --- task affinities -----------------------------------------------------

    @Test
    fun theCaptureActivityAndThePickerHaveTheirOWNTaskAffinities() {
        // 🔴 Issue #11's defect class, at two more instances. An activity with
        // FLAG_ACTIVITY_NEW_TASK and the DEFAULT affinity joins the bubble's
        // task: Back then walks the user through a surface they never opened,
        // and the system may resurrect the wrong one.
        assertTrue(
            "CaptureActivity has no taskAffinity of its own",
            element(".CaptureActivity")
                .contains("android:taskAffinity=\"${CaptureActivity.CAPTURE_TASK_AFFINITY}\""),
        )
        assertTrue(
            "ClipPickerActivity has no taskAffinity of its own",
            element(".ClipPickerActivity")
                .contains("android:taskAffinity=\"${ClipLaunch.PICKER_TASK_AFFINITY}\""),
        )
    }

    @Test
    fun everyDeclaredTaskAffinityInTheManifestIsDISTINCT() {
        // 🔴 A COUNT over the whole manifest rather than a check on the two new
        // ones: the hazard is a LATER activity reusing an affinity, and a
        // pairwise check between the two files this change adds would pass
        // happily while a third collided with one of them.
        //
        // 🔴 And it fails on a DUPLICATE, not on a change — so renaming an
        // affinity is free and colliding is not, which is the relationship
        // worth pinning.
        val declared = Regex("android:taskAffinity=\"([^\"]*)\"")
            .findAll(manifestSource())
            .map { it.groupValues[1] }
            .toList()
        assertEquals(
            "two activities share a taskAffinity: $declared",
            declared.size,
            declared.toSet().size,
        )
        // The positive control: the scan can see affinities at all, and there
        // are now FIVE of them — the three pre-existing surfaces plus the two
        // this change adds. 🔴 Asserted as a COUNT so that a regex which
        // silently stopped matching would not read as "no duplicates".
        //
        // 🔴 The first draft of this said four, counting the picker and
        // forgetting the receiver that sits beside it in the same change. It
        // went red naming the set, which is what the COUNT is for.
        assertEquals(
            "the affinity scan found $declared — if this shrank, an activity " +
                "lost its own task; if the scan found nothing, the duplicate " +
                "check above is vacuous",
            5,
            declared.size,
        )
        assertTrue(
            "the affinity set is $declared",
            declared.containsAll(
                listOf(
                    "dev.zacx.dicta.overlay",
                    "dev.zacx.dicta.snip",
                    "dev.zacx.dicta.history",
                    "dev.zacx.dicta.clip",
                    "dev.zacx.dicta.capture",
                )
            )
        )
    }

    @Test
    fun theCaptureAffinityIsNotTheClipPickersEvenThoughBothAreNewHere() {
        // Spelled out because the two were added in one change, which is
        // exactly when a copied line keeps the wrong value.
        assertTrue(
            CaptureActivity.CAPTURE_TASK_AFFINITY != ClipLaunch.PICKER_TASK_AFFINITY
        )
    }

    // --- the picker ----------------------------------------------------------

    @Test
    fun theClipPickerIsNotExportedAndClaimsNoIntentFilter() {
        // 🔴 The picker shows the shared clipboard's CONTENTS. Exporting it, or
        // giving it a filter, would let any app on the device open a window
        // listing text from every other machine — and a filter is how an
        // activity becomes reachable without being exported deliberately.
        //
        // The element is SELF-CLOSING, which is what makes "no intent-filter" a
        // structural fact rather than a search for an absence.
        val el = element(".ClipPickerActivity")
        assertTrue(
            "ClipPickerActivity is exported",
            el.contains("android:exported=\"false\""),
        )
        assertTrue(
            "the ClipPickerActivity element is not self-closing, so it may " +
                "contain an <intent-filter>: $el",
            el.trimEnd().endsWith("/>"),
        )
    }

    @Test
    fun theClipPickerIsResolvableByNOTHINGFromOutsideTheApp() {
        // The behavioural half: nothing the system could send reaches it.
        for (action in listOf(Intent.ACTION_MAIN, Intent.ACTION_SEND, Intent.ACTION_PROCESS_TEXT)) {
            val matches = ctx.packageManager
                .queryIntentActivities(Intent(action).apply { type = "text/plain" }, 0)
                .map { it.activityInfo.name }
            assertFalse(
                "$action resolves to the clip picker, which lists the shared " +
                    "clipboard's contents",
                matches.contains(ClipPickerActivity::class.java.name),
            )
        }
    }

    @Test
    fun theClipPickerWearsTheOverlayThemeBecauseItDOESShowAFloatingWindow() {
        // The mirror image of the CaptureActivity theme case: this one must
        // carry it, and FloatingOverlaySeamLedgerTest is what holds the theme
        // and the sizing obligation together.
        assertTrue(
            "ClipPickerActivity does not wear @style/Theme.Dicta.Overlay",
            element(".ClipPickerActivity").contains(OVERLAY_THEME_ATTR),
        )
    }

    private companion object {

        /**
         * The floating-overlay theme attribute, spelled once.
         *
         * Two tests in this file branch on it in OPPOSITE directions — the
         * capture receiver must not wear it, the picker must — so a typo in one
         * copy would silently pass the half that asserts absence.
         * `FloatingOverlaySeamLedgerTest` keys on this same string.
         */
        const val OVERLAY_THEME_ATTR = "android:theme=\"@style/Theme.Dicta.Overlay\""

        /**
         * What the capture receiver must wear instead.
         *
         * A PLATFORM theme, so there is no Dicta style to keep in sync: it is
         * `windowIsTranslucent` + a null window background + no title bar,
         * which is §4.2's whole requirement for an activity that draws nothing.
         */
        const val TRANSLUCENT_THEME_ATTR =
            "android:theme=\"@android:style/Theme.Translucent.NoTitleBar\""
    }
}
