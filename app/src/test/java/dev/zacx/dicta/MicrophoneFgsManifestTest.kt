package dev.zacx.dicta

import android.content.pm.PackageManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 🔴 The manifest half of recording inside [BubbleService].
 *
 * ## Why this is a test and not a comment
 *
 * On `targetSdk 34` a foreground service that opens the microphone must declare
 * the `microphone` FGS type **and** hold
 * `FOREGROUND_SERVICE_MICROPHONE`. `BubbleService` was `specialUse` only, and
 * the app declared `FOREGROUND_SERVICE`, `_SPECIAL_USE` and
 * `_MEDIA_PROJECTION` but not `_MICROPHONE`. Neither omission is a build error.
 *
 * ## Why the type must keep BOTH values
 *
 * The obvious fix — change the type to `microphone` — is wrong. Android 14
 * throws `SecurityException` out of `startForeground` when a `microphone`-typed
 * FGS is promoted without `RECORD_AUDIO`, and the bubble has to keep running
 * when the user has denied the microphone (that is the state in which tap 1
 * falls back to opening `DictateActivity`, the only place the permission can be
 * requested from). So the manifest declares the union and the service promotes
 * to the *specific* type at runtime: `microphone` while recording, `specialUse`
 * at rest. Both halves are asserted here, because dropping either one is a
 * one-word edit that nothing else would catch.
 *
 * Comments are stripped before any assertion. The manifest documents these
 * exact attributes in prose immediately above the element, so an unstripped
 * scan passes on the documentation while the attribute is absent — the trap
 * `NetworkManifestTest` and `DictateLaunchTest` both record hitting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MicrophoneFgsManifestTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    @Test
    fun theAppDeclaresTheMicrophoneForegroundServicePermission() {
        val declared = declaredPermissions()
        assertTrue(
            "android.permission.FOREGROUND_SERVICE_MICROPHONE is not declared. " +
                "On targetSdk 34 promoting BubbleService to the microphone FGS " +
                "type without it throws from startForeground, and the bubble " +
                "dies the moment the user taps it. Declared: $declared",
            declared.contains("android.permission.FOREGROUND_SERVICE_MICROPHONE"),
        )
    }

    @Test
    fun theAppStillDeclaresTheSpecialUseForegroundServicePermission() {
        // Positive control for the assertion above AND a real invariant: the
        // service still runs as specialUse at rest, so losing this permission
        // would break the idle bubble. An empty or unreadable permission list
        // would make the microphone assertion vacuous; this proves the list is
        // genuinely being read.
        val declared = declaredPermissions()
        assertTrue(
            "the permission list came back without " +
                "FOREGROUND_SERVICE_SPECIAL_USE, so it is not being read " +
                "correctly and the microphone check above proves nothing. " +
                "Declared: $declared",
            declared.contains("android.permission.FOREGROUND_SERVICE_SPECIAL_USE"),
        )
    }

    private fun declaredPermissions(): List<String> =
        ctx.packageManager
            .getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toList()
            .orEmpty()

    // --- the service element -------------------------------------------------

    @Test
    fun theBubbleServiceForegroundTypeIncludesMicrophone() {
        val element = serviceElement("BubbleService")
        val types = foregroundServiceTypes(element)
        assertTrue(
            "BubbleService's android:foregroundServiceType does not include " +
                "\"microphone\", so opening the mic from it is a policy " +
                "violation on targetSdk 34. Found types: $types in $element",
            types.contains("microphone"),
        )
    }

    @Test
    fun theBubbleServiceForegroundTypeStillIncludesSpecialUse() {
        // 🔴 Not a duplicate of the test above: the one-word "fix" that breaks
        // the app is REPLACING specialUse with microphone. That passes the
        // microphone assertion and then throws SecurityException out of
        // startForeground on any device where RECORD_AUDIO is denied — i.e. it
        // breaks the bubble precisely for the users who have not granted the
        // mic. The PROPERTY_SPECIAL_USE_FGS_SUBTYPE property also only has
        // meaning while this value is present.
        val element = serviceElement("BubbleService")
        val types = foregroundServiceTypes(element)
        assertTrue(
            "BubbleService's android:foregroundServiceType lost \"specialUse\". " +
                "The service must be able to run at rest WITHOUT RECORD_AUDIO " +
                "— a microphone-only type cannot. Found types: $types",
            types.contains("specialUse"),
        )
    }

    @Test
    fun theScanSeesARealAttributeAndNotAProseMentionOfOne() {
        // Negative control: the manifest's own comment names both values. If
        // comment-stripping or the regex were wrong, every assertion above
        // would be reporting a confident pass on the documentation.
        val commentOnly = """
            <manifest><application>
            <!-- android:foregroundServiceType="microphone|specialUse" is what
                 lets BubbleService open the mic; see FOREGROUND_SERVICE_MICROPHONE -->
            <service android:name=".BubbleService" android:exported="false"
                android:foregroundServiceType="specialUse" />
            </application></manifest>
        """.trimIndent()
        assertFalse(
            "a type named only inside a comment satisfied the check",
            foregroundServiceTypes(
                serviceElementIn(withoutComments(commentOnly), "BubbleService")
            ).contains("microphone"),
        )

        // Positive control: the same pipeline must SEE the real attribute, or
        // the assertions above are a fact about the parser.
        val real = """
            <manifest><application>
            <service android:name=".BubbleService" android:exported="false"
                android:foregroundServiceType="microphone|specialUse">
                <property android:name="x" android:value="y" />
            </service>
            </application></manifest>
        """.trimIndent()
        val realTypes =
            foregroundServiceTypes(serviceElementIn(withoutComments(real), "BubbleService"))
        assertTrue(
            "the scan cannot see a real microphone|specialUse attribute, so " +
                "the real tests' passes prove nothing about the manifest",
            realTypes.containsAll(listOf("microphone", "specialUse")),
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

    private fun serviceElement(name: String): String =
        serviceElementIn(manifestSource(), name)

    /**
     * The opening `<service>` tag for `.name`. `[^>]*` stops at the first `>`,
     * which is the end of the opening tag whether the element is self-closing
     * or has children (`BubbleService` has a `<property>` child).
     */
    private fun serviceElementIn(xml: String, name: String): String {
        val m = Regex("<service\\b[^>]*android:name=\"\\.$name\"[^>]*/?>").find(xml)
        assertTrue(
            "no <service> element for .$name was found — the assertions that " +
                "follow would be vacuous",
            m != null,
        )
        return m!!.value
    }

    /** The `|`-separated values of `android:foregroundServiceType`, if present. */
    private fun foregroundServiceTypes(element: String): List<String> =
        Regex("android:foregroundServiceType=\"([^\"]*)\"")
            .find(element)
            ?.groupValues
            ?.get(1)
            ?.split("|")
            ?.map { it.trim() }
            .orEmpty()
}
