package dev.zacx.dicta

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 🔴 **THE RELEASE BUILD MUST NOT CONTAIN THE CONFIG SURFACE AT ALL.**
 *
 * `ZacxDev/dicta` is a PUBLIC repository and [DebugClipConfigReceiver] writes a
 * real credential through an `exported="true"` component. The gate the operator
 * chose is the **source set**: the receiver is declared only in
 * `src/debug/AndroidManifest.xml`, so a release build has no such component —
 * as opposed to a `BuildConfig.DEBUG` check, which would leave an addressable
 * entry point in every shipped APK whose safety rested on one `if`.
 *
 * ## 🔴 What this guard asserts, and why each half is needed
 *
 *  * `src/main/AndroidManifest.xml` must not name the receiver, the class, or
 *    either action — the actual security property;
 *  * `src/debug/AndroidManifest.xml` must name all of them — the **positive
 *    control**, without which the assertion above is indistinguishable from a
 *    check wired to the wrong file, a renamed class, or a deleted feature;
 *  * the receiver's Kotlin source must live under `src/debug/` and not under
 *    `src/main/` — because a manifest in the right place over a class in the
 *    wrong one still ships the class, and a future `src/main` manifest entry
 *    would then have something to point at.
 *
 * Both manifests are read with XML comments STRIPPED, which is not cosmetic:
 * `src/main/AndroidManifest.xml` has every reason to *document* why this
 * receiver is not in it, and a raw substring search would find the word inside
 * the very comment that explains its absence. `NetworkManifestTest` records
 * the same trap, met the same way, and its negative control is reproduced here
 * for this file's own pattern.
 *
 * ## ⚠ What this does NOT establish
 *
 * It is a guard on the SOURCE manifests, not on a built release APK. The
 * release manifest is produced by merging `src/main` with nothing else (debug
 * is a different variant), so this is the right place to assert it — but a
 * change to `app/build.gradle.kts` that added the debug source set to the
 * release variant would leave this green. That would be a visible, deliberate
 * edit to the build file; this guards the thing that can drift by accident.
 *
 * **MUTATION-TESTED, and the result is recorded because it is not obvious.**
 * The `<receiver>` block was MOVED from `src/debug/AndroidManifest.xml` into
 * `src/main/AndroidManifest.xml` and the class was run. **Two tests failed,
 * each with its OWN message**, which is what makes them reachable rather than
 * merely breakable:
 *
 *  * [theReleaseManifestDoesNotDeclareTheConfigSurface] — *"src/main/
 *    AndroidManifest.xml names `.DebugClipConfigReceiver`"*;
 *  * [theDebugManifestDOESDeclareItWithBothActions] — *"src/debug/
 *    AndroidManifest.xml does not name `.DebugClipConfigReceiver`, so the
 *    release-manifest check above proves nothing"*.
 *
 * 🔴 That second failure is also the comment-stripping control working on live
 * data rather than on a fixture: after the move, `src/debug/AndroidManifest.xml`
 * still *mentions* the class in prose, and the guard correctly refused to count
 * that as a declaration. The block was then moved back.
 *
 * **Invariant guard, labelled as such** — there was no `src/debug` source set
 * before this change, so nothing here ever regressed. It is here because the
 * failure it prevents is a credential-writing entry point shipped to a handset.
 */
class DebugConfigSurfaceManifestTest {

    private val receiverClass = "DebugClipConfigReceiver"

    /** Every token that would betray the surface's presence in a manifest. */
    private val tells = listOf(
        ".$receiverClass",
        DebugClipConfigReceiver.ACTION_CONFIGURE,
        DebugClipConfigReceiver.ACTION_DIAGNOSE,
    )

    // --- the property ---------------------------------------------------------

    @Test
    fun theReleaseManifestDoesNotDeclareTheConfigSurface() {
        val main = withoutComments(read(MAIN_MANIFEST))
        for (tell in tells) {
            assertFalse(
                "🔴 src/main/AndroidManifest.xml names \"$tell\". That manifest " +
                    "is the one a RELEASE build merges, so this surface would " +
                    "exist in a shipped APK — an exported component that writes " +
                    "a real credential, in a public app. Move the declaration " +
                    "back to src/debug/AndroidManifest.xml.",
                main.contains(tell),
            )
        }
    }

    @Test
    fun theReceiverSourceLivesInTheDebugSourceSetOnly() {
        // 🔴 A manifest in the right place over a class in the wrong one still
        // ships the class. The declaration and the code must move together.
        assertTrue(
            "the receiver's source is not in src/debug/java — a manifest guard " +
                "over a class that ships is a guard on half of the property",
            sourceFile("debug") != null,
        )
        assertEquals(
            "the receiver's source is ALSO (or instead) in src/main/java, so it " +
                "is compiled into the release build whether or not any manifest " +
                "declares it",
            null,
            sourceFile("main"),
        )
    }

    // --- the positive control -------------------------------------------------

    @Test
    fun theDebugManifestDOESDeclareItWithBothActions() {
        // 🔴 POSITIVE CONTROL. The real assertion above is that three
        // substrings are ABSENT, and an absence is indistinguishable from a
        // check reading the wrong file, a renamed class or a deleted feature.
        // This requires the same three tells to be FOUND, in the other file,
        // through the same comment-stripping pipeline.
        val debug = withoutComments(read(DEBUG_MANIFEST))
        for (tell in tells) {
            assertTrue(
                "src/debug/AndroidManifest.xml does not name \"$tell\", so the " +
                    "release-manifest check above proves nothing — it could be " +
                    "reading a file in which nothing is declared anywhere.",
                debug.contains(tell),
            )
        }
        assertTrue(
            "the debug declaration is not exported, so `adb shell am broadcast` " +
                "cannot reach it and the surface does not work at all",
            debug.contains("android:exported=\"true\""),
        )
    }

    @Test
    fun theScanSeesADeclarationButNotAMentionOfItInAComment() {
        // 🔴 NEGATIVE CONTROL on the comment-stripping pipeline itself.
        // src/main/AndroidManifest.xml has every reason to DOCUMENT why this
        // receiver is not in it, and a raw search would find the word inside
        // the comment that explains its absence — passing a manifest that
        // declared the receiver while renaming the comment, and failing a
        // manifest that merely mentions it. NetworkManifestTest records the
        // same trap on usesCleartextTraffic.
        val declares = """
            <manifest><application>
              <receiver android:name=".$receiverClass" android:exported="true" />
            </application></manifest>
        """.trimIndent()
        assertTrue(
            "the scan cannot see the declaration when it IS there, so the " +
                "real test's pass proves nothing",
            withoutComments(declares).contains(".$receiverClass"),
        )

        val onlyMentions = """
            <manifest>
            <!-- .$receiverClass is declared in src/debug, deliberately -->
            <application /></manifest>
        """.trimIndent()
        assertFalse(
            "a mention inside a comment is mistaken for a declaration, so this " +
                "guard would go red for documenting itself",
            withoutComments(onlyMentions).contains(".$receiverClass"),
        )
    }

    // --- plumbing -------------------------------------------------------------

    private fun sourceFile(sourceSet: String): File? = listOf(
        File("app/src/$sourceSet/java/dev/zacx/dicta/$receiverClass.kt"),
        File("src/$sourceSet/java/dev/zacx/dicta/$receiverClass.kt"),
        File("../app/src/$sourceSet/java/dev/zacx/dicta/$receiverClass.kt"),
    ).firstOrNull { it.isFile }

    private fun read(relative: String): String {
        val candidates = listOf(
            File("app/$relative"),
            File(relative),
            File("../app/$relative"),
        )
        val found = candidates.firstOrNull { it.isFile }
        assertTrue(
            "could not locate $relative from ${File(".").absolutePath} — " +
                "refusing to report a pass on a file that was never read " +
                "(tried ${candidates.joinToString { it.path }})",
            found != null,
        )
        return found!!.readText()
    }

    private fun withoutComments(xml: String): String =
        xml.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    private companion object {
        const val MAIN_MANIFEST = "src/main/AndroidManifest.xml"
        const val DEBUG_MANIFEST = "src/debug/AndroidManifest.xml"
    }
}
