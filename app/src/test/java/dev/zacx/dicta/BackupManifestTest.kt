package dev.zacx.dicta

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 🔴 `android:allowBackup` must stay **false**, and nothing may quietly replace
 * it with the rule-file mechanism.
 *
 * ## The failure this exists to make loud
 *
 * A dictation-history store lives in `noBackupFilesDir`, and the platform
 * default for `allowBackup` is `true`. With the default in place, up to 200 MB
 * of the user's **recorded voice** plus **every transcript** is eligible for
 * cloud auto-backup and for `adb backup` — a data-egress change that raises no
 * lint error, no build warning and no runtime symptom. Nothing would ever go
 * red; the only observable is the recording leaving the device.
 *
 * ## Why one attribute rather than `android:dataExtractionRules`
 *
 * Not a capability argument — a **drift** argument. `dataExtractionRules` is
 * API 31+ and needs two rule sets (`<cloud-backup>` and `<device-transfer>`),
 * and `minSdk` here is 26, so devices on 26–30 would additionally need
 * `fullBackupContent`. That is **three files that must agree with each other
 * and with the store's directory name** — exactly the cross-file drift class
 * that produced issue #11. The single attribute covers both transports, every
 * API level, and `shared_prefs` too.
 *
 * The accepted cost, stated so it is not rediscovered as a bug: on device
 * migration the user loses the backup of their base URL and language
 * preference (two fields, under a minute to re-enter), and they must re-enter
 * the bearer token regardless, because the `AndroidKeyStore` key that wraps it
 * is non-exportable and so could never have been restored anyway.
 *
 * Reads the source manifest rather than the parsed `ApplicationInfo`, following
 * [NetworkManifestTest]: the properties under test include the **absence** of
 * two attributes, and an absent attribute is not observable through
 * `ApplicationInfo` in a way that distinguishes "not set" from "set to the
 * platform default".
 */
class BackupManifestTest {

    @Test
    fun theManifestDisablesBackup() {
        val manifest = manifestSource()
        assertTrue(
            "android:allowBackup must be \"false\" — the dictation history in " +
                "noBackupFilesDir plus every transcript and shared_prefs entry " +
                "is otherwise eligible for cloud auto-backup and adb backup, " +
                "with no build-time warning of any kind",
            manifest.contains("android:allowBackup=\"false\""),
        )
    }

    @Test
    fun theManifestDoesNotEnableBackup() {
        // 🔴 Separate from the assertion above, not a restatement of it: a
        // manifest could carry both attributes (a bad merge), and a second
        // `allowBackup="true"` on another element would win for that element.
        val manifest = manifestSource()
        assertFalse(
            "android:allowBackup=\"true\" is present somewhere in the manifest " +
                "— recorded audio and transcripts become backup-eligible",
            manifest.contains("android:allowBackup=\"true\""),
        )
    }

    /**
     * Negative and positive controls for [theManifestDoesNotEnableBackup].
     *
     * 🔴 That assertion is that a substring is **absent**, and an absence is
     * indistinguishable from a check wired to the wrong string or the wrong
     * file. So: feed the same comment-stripping pipeline a manifest that *does*
     * set the attribute and require it to be **seen** (the pattern can go red),
     * and feed it one that names the attribute only inside an XML comment and
     * require it **not** to be seen (a mention in the prose that explains the
     * hazard is not the hazard). This file's own manifest comment names
     * `allowBackup="true"`, so without the comment strip the real assertion
     * above would fail on the explanation of why it holds.
     */
    @Test
    fun theBackupCheckSeesTheAttributeButNotAMentionOfItInAComment() {
        val setsIt = """
            <manifest><application
                android:allowBackup="true"
                android:label="x" /></manifest>
        """.trimIndent()
        assertTrue(
            "the check cannot detect android:allowBackup=\"true\" when it IS " +
                "set, so the real test's pass proves nothing",
            withoutComments(setsIt).contains("android:allowBackup=\"true\""),
        )

        val onlyMentionsIt = """
            <manifest>
            <!-- allowBackup="false" rather than the default android:allowBackup="true" -->
            <application android:allowBackup="false" android:label="x" /></manifest>
        """.trimIndent()
        assertFalse(
            "a mention inside a comment must not be mistaken for the attribute",
            withoutComments(onlyMentionsIt).contains("android:allowBackup=\"true\""),
        )
    }

    @Test
    fun noBackupRuleFilesHaveBeenAdopted() {
        // 🔴 A drift guard, and the reason the single attribute was chosen. It
        // is NOT a claim that the rule files are wrong — only that adopting
        // them turns one declaration into three that must agree.
        val manifest = manifestSource()
        val message =
            "android:dataExtractionRules / android:fullBackupContent has " +
                "appeared. The single android:allowBackup=\"false\" was chosen " +
                "precisely to avoid this: the rule-file route needs TWO rule " +
                "sets (<cloud-backup> and <device-transfer>) on API 31+, plus " +
                "fullBackupContent for minSdk 26–30 — three files that must " +
                "stay in step with EACH OTHER and with the dictation store's " +
                "directory name. If you are adopting them deliberately, you " +
                "own keeping that directory name in step across all three, and " +
                "this guard should be replaced by one that checks they agree."
        assertFalse(message, manifest.contains("android:dataExtractionRules"))
        assertFalse(message, manifest.contains("android:fullBackupContent"))
    }

    // --- reading the manifest ------------------------------------------------

    private fun manifestSource(): String =
        withoutComments(
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

    /**
     * Strips XML comments before any assertion runs.
     *
     * 🔴 Not cosmetic — it is the difference between a guard on the XML and a
     * guard on the prose. The manifest's own comment explains the hazard by
     * naming `allowBackup="true"`, so a raw substring search would find the
     * word inside the comment that exists to say why the attribute is absent.
     * Controlled in
     * [theBackupCheckSeesTheAttributeButNotAMentionOfItInAComment].
     */
    private fun withoutComments(xml: String): String =
        xml.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
}
