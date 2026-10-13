package dev.zacx.dicta

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
 * The two manifest facts that silently break the whole feature.
 *
 * ## Why these are tests rather than a comment
 *
 * Both failures are **invisible at build time**:
 *
 *  * A missing `android.permission.INTERNET` raises no permission dialog, no
 *    lint error and no build warning. Every socket just throws at runtime,
 *    which looks exactly like the service being down.
 *  * Cleartext HTTP to the mesh host is blocked by default on targetSdk 28+,
 *    and the easy fix — `usesCleartextTraffic="true"` — permits *every* host
 *    on *every* network. That is a real downgrade, it is one attribute away,
 *    and nothing would ever go red.
 *
 * **Invariant guards**, labelled as such: neither has regressed. They are
 * here because the cost of either regressing is a feature that silently does
 * not work, or a security property silently lost.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkManifestTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    @Test
    fun theAppDeclaresTheInternetPermission() {
        val info = ctx.packageManager.getPackageInfo(
            ctx.packageName, PackageManager.GET_PERMISSIONS
        )
        val declared = info.requestedPermissions?.toList().orEmpty()
        assertTrue(
            "android.permission.INTERNET is not declared — every socket will " +
                "throw at runtime with no build-time warning. Declared: $declared",
            declared.contains("android.permission.INTERNET"),
        )
    }

    @Test
    fun theAppStillDeclaresRecordAudio() {
        // Positive control for the test above: proves the permission list is
        // actually being read, so a passing INTERNET assertion is not just an
        // empty list matching nothing. Without this, both could be vacuous.
        val info = ctx.packageManager.getPackageInfo(
            ctx.packageName, PackageManager.GET_PERMISSIONS
        )
        val declared = info.requestedPermissions?.toList().orEmpty()
        assertTrue(
            "the permission list came back without RECORD_AUDIO, so it is not " +
                "being read correctly and the INTERNET check proves nothing",
            declared.contains("android.permission.RECORD_AUDIO"),
        )
    }

    // --- the network security config ---------------------------------------

    /**
     * Reads the source manifest rather than the parsed one: the attributes
     * under test are `android:usesCleartextTraffic` (whose *absence* is the
     * property) and `android:networkSecurityConfig`, and an absent attribute
     * is not observable through `ApplicationInfo` in a way that distinguishes
     * "not set" from "set to the platform default".
     */
    private fun manifestSource(): String =
        withoutComments(
            read(
                "app/src/main/AndroidManifest.xml",
                "src/main/AndroidManifest.xml",
                "../app/src/main/AndroidManifest.xml",
                what = "AndroidManifest.xml",
            )
        )

    private fun securityConfigSource(): String =
        withoutComments(
            read(
                "app/src/main/res/xml/network_security_config.xml",
                "src/main/res/xml/network_security_config.xml",
                "../app/src/main/res/xml/network_security_config.xml",
                what = "network_security_config.xml",
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

    /**
     * Strips XML comments before any assertion runs.
     *
     * 🔴 Not cosmetic — this is the difference between a guard on the XML and
     * a guard on the prose. Both files *document* the attribute they must not
     * use (`usesCleartextTraffic`) and the reasoning for the scoped
     * alternative, so a raw substring search finds the word inside the comment
     * that exists to explain why it is absent. The first version of
     * [theManifestDoesNotSetBlanketCleartextTraffic] failed for exactly that
     * reason: it was asserting against a word rather than against
     * configuration, and it would equally have *passed* on a manifest that set
     * the attribute while renaming the comment.
     */
    private fun withoutComments(xml: String): String =
        xml.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    @Test
    fun theManifestPointsAtAScopedNetworkSecurityConfig() {
        val manifest = manifestSource()
        assertTrue(
            "the application element must reference the scoped config",
            manifest.contains(
                "android:networkSecurityConfig=\"@xml/network_security_config\""
            ),
        )
    }

    @Test
    fun theManifestDoesNotSetBlanketCleartextTraffic() {
        // 🔴 The whole point. usesCleartextTraffic="true" would permit an
        // unencrypted request to ANY host on ANY network, including a hostile
        // Wi-Fi — not just to the mesh address that already sits inside an
        // encrypted Nebula tunnel.
        val manifest = manifestSource()
        assertFalse(
            "android:usesCleartextTraffic must not be set at all — it is a " +
                "blanket exemption, and the scoped XML config is what permits " +
                "the one mesh host",
            manifest.contains("usesCleartextTraffic"),
        )
    }

    /**
     * Negative control for [theManifestDoesNotSetBlanketCleartextTraffic].
     *
     * 🔴 The real assertion is that a substring is **absent**, and an
     * absence is indistinguishable from a check wired to the wrong string or
     * the wrong file. This feeds the same comment-stripping pipeline a
     * manifest that *does* set the attribute, and requires it to be seen —
     * and feeds it one that only mentions it in a comment, and requires it
     * NOT to be seen. Without both halves the clean result above is not
     * evidence of anything.
     */
    @Test
    fun theCleartextCheckSeesTheAttributeButNotAMentionOfItInAComment() {
        val setsIt = """
            <manifest><application
                android:usesCleartextTraffic="true"
                android:label="x" /></manifest>
        """.trimIndent()
        assertTrue(
            "the check cannot detect the attribute when it IS set, so the " +
                "real test's pass proves nothing",
            withoutComments(setsIt).contains("usesCleartextTraffic"),
        )

        val onlyMentionsIt = """
            <manifest>
            <!-- networkSecurityConfig rather than usesCleartextTraffic="true" -->
            <application android:label="x" /></manifest>
        """.trimIndent()
        assertFalse(
            "a mention inside a comment must not be mistaken for the attribute",
            withoutComments(onlyMentionsIt).contains("usesCleartextTraffic"),
        )
    }

    @Test
    fun cleartextIsPermittedForTheMeshHostOnly() {
        val config = securityConfigSource()
        assertTrue(
            "the mesh host must be named explicitly",
            config.contains("<domain>10.42.0.10</domain>"),
        )
        assertTrue(
            "the base config must refuse cleartext everywhere else, stated " +
                "explicitly rather than inherited, so a later edit cannot " +
                "widen it by accident",
            config.contains("<base-config cleartextTrafficPermitted=\"false\" />"),
        )
    }

    @Test
    fun exactlyOneDomainIsExempted() {
        // A count, not a presence check: the risk is a second host being
        // added later, and a `contains` on the first one would still pass.
        val config = securityConfigSource()
        val domains = Regex("<domain>([^<]+)</domain>")
            .findAll(config)
            .map { it.groupValues[1].trim() }
            .toList()
        assertEquals(
            "exactly one host may be exempted from cleartext policy; found $domains",
            listOf("10.42.0.10"),
            domains,
        )
    }

    @Test
    fun noDomainConfigPermitsCleartextForSubdomains() {
        // includeSubdomains on a literal IP is meaningless, but it would
        // become meaningful the moment someone replaced the IP with a
        // hostname — and would then silently widen the exemption.
        val config = securityConfigSource()
        assertFalse(
            "includeSubdomains would widen the exemption if the entry ever " +
                "became a hostname",
            config.contains("includeSubdomains=\"true\""),
        )
    }

    @Test
    fun theDefaultBaseUrlPointsAtTheExemptedHost() {
        // 🔴 A seam guard, not a restatement: the exempted host and the
        // default URL are configured in two different files, and if they
        // drift the app's out-of-the-box request is refused by network
        // policy — a failure that reads like the service being down.
        val exempted = Regex("<domain>([^<]+)</domain>")
            .find(securityConfigSource())!!
            .groupValues[1]
            .trim()
        assertTrue(
            "the default base URL (${SttEndpoints.DEFAULT_BASE_URL}) does not " +
                "point at the cleartext-exempted host ($exempted)",
            SttEndpoints.DEFAULT_BASE_URL.contains(exempted),
        )
    }
}
