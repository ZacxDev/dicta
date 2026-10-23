package dev.zacx.dicta

import android.content.Intent
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
 * Issue #11: the overlay opened on top of Dicta's own `MainActivity`.
 *
 * ## What actually broke, and why a flag test alone would not have caught it
 *
 * The bubble, tile and notification all started `DictateActivity` with
 * `FLAG_ACTIVITY_NEW_TASK`. That flag was **present and correct**, so any test
 * asserting "the intent carries NEW_TASK" was green throughout the bug. The
 * defect lived in the *relationship* between the flag and the manifest:
 * `NEW_TASK` places the activity in the task matching its `taskAffinity`, and
 * `DictateActivity` declared none, so it inherited the application default —
 * `MainActivity`'s task. The translucent card then rendered over the whole app.
 *
 * So the assertions here are deliberately about the **seam**:
 *
 *  1. the manifest declares an affinity on `DictateActivity` at all;
 *  2. it is the *same string* as [DictateLaunch.OVERLAY_TASK_AFFINITY], which
 *     is in a different file;
 *  3. it is **different** from the application's default affinity — the actual
 *     property that was violated, and the one a mere "is it set" check misses;
 *  4. every file that **hand-builds an overlay intent** is a declared one,
 *     asserted as a ledger so a new trigger cannot quietly open-code it again.
 *
 * 🔴 Point 4 read "every 'from anywhere' entry point goes through
 * [DictateLaunch]", which claimed **more than the ledger implements and more
 * than is true**. The Quick Settings tile is a from-anywhere surface and routes
 * through [DictateLaunch] nowhere — it toggles the floating bubble and opens no
 * overlay. The ledger is still correct, because what it actually scans for is a
 * file CONSTRUCTING a `DictateActivity` intent, and the tile constructs none;
 * but a reader who took the old wording at face value would believe this file
 * covers a surface it has never looked at.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictateLaunchTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    // --- the intent itself --------------------------------------------------

    @Test
    fun theOverlayIntentTargetsDictateActivityWithNewTask() {
        val intent = DictateLaunch.intent(ctx)
        assertEquals(
            "the overlay intent must target DictateActivity",
            DictateActivity::class.java.name,
            intent.component?.className,
        )
        assertTrue(
            "FLAG_ACTIVITY_NEW_TASK is required: these callers are services " +
                "and a tile, which cannot start an activity without it",
            intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )
    }

    @Test
    fun theHistoryIntentInheritsEverythingTheOverlayIntentCarries() {
        // 🔴 The point of building it through `intent()` rather than by hand.
        // A hand-built `Intent(this, DictateActivity::class.java)` with
        // NEW_TASK added would look right at the call site and reproduce #11
        // exactly: the flag present and correct, the affinity inherited from
        // the application default, the translucent card over Dicta's own UI.
        val intent = DictateLaunch.historyIntent(ctx, "item-7")
        assertEquals(
            DictateActivity::class.java.name,
            intent.component?.className,
        )
        assertTrue(
            "the history intent does not carry FLAG_ACTIVITY_NEW_TASK, so it " +
                "was not built through DictateLaunch.intent()",
            intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )
        assertEquals(
            "item-7",
            intent.getStringExtra(DictateLaunch.EXTRA_HISTORY_ID),
        )
    }

    @Test
    fun theTwoLaunchExtrasAreDistinctStrings() {
        // 🔴 Cheap, and it guards a copy-paste with a silent consequence: one
        // name for both extras makes every history launch ALSO look like an
        // attach (or the reverse), and the card then starts a recording with
        // nothing on screen to say so.
        assertFalse(
            "EXTRA_HISTORY_ID and EXTRA_ATTACH_SESSION are the same string " +
                "(${DictateLaunch.EXTRA_HISTORY_ID}), so the two launch modes " +
                "are indistinguishable at the receiver",
            DictateLaunch.EXTRA_HISTORY_ID == DictateLaunch.EXTRA_ATTACH_SESSION,
        )
    }

    @Test
    fun neitherPlainLaunchCarriesAHistoryId() {
        // Positive control for the branch in DictateActivity: if the plain or
        // the attach intent carried a history id, every notification tap would
        // open a history card instead of a dictation. (It said "every tile
        // tap"; the tile builds no overlay intent now.)
        assertEquals(
            null,
            DictateLaunch.intent(ctx).getStringExtra(DictateLaunch.EXTRA_HISTORY_ID),
        )
        assertEquals(
            null,
            DictateLaunch.transcribingIntent(ctx)
                .getStringExtra(DictateLaunch.EXTRA_HISTORY_ID),
        )
        assertEquals(
            "the history intent carries the ATTACH flag, so the card would " +
                "attach to a live session instead of showing the stored one",
            false,
            DictateLaunch.historyIntent(ctx, "item-7")
                .getBooleanExtra(DictateLaunch.EXTRA_ATTACH_SESSION, false),
        )
    }

    // --- the seam: manifest <-> constant ------------------------------------

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
     * 🔴 The manifest *documents* `taskAffinity` at length in the comment above
     * the activity, including the literal value. Without stripping comments
     * first, every assertion below would pass on the prose while the attribute
     * itself was absent — the exact trap `NetworkManifestTest` records hitting.
     */
    private fun withoutComments(xml: String): String =
        xml.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    /** The `<activity>` element for [name], comments already stripped. */
    private fun activityElement(name: String): String {
        val m = Regex("<activity\\b[^>]*android:name=\"\\.$name\"[^>]*/?>")
            .find(manifestSource())
        assertTrue(
            "no <activity> element for .$name was found in the manifest — the " +
                "assertions that follow would be vacuous",
            m != null,
        )
        return m!!.value
    }

    @Test
    fun theOverlayActivityDeclaresTheAffinityTheLauncherConstantNames() {
        val element = activityElement("DictateActivity")
        assertTrue(
            "DictateActivity must declare android:taskAffinity=\"" +
                "${DictateLaunch.OVERLAY_TASK_AFFINITY}\" — without it " +
                "FLAG_ACTIVITY_NEW_TASK puts the overlay in MainActivity's " +
                "task and the translucent card covers the whole app (#11). " +
                "Found: $element",
            element.contains(
                "android:taskAffinity=\"${DictateLaunch.OVERLAY_TASK_AFFINITY}\""
            ),
        )
    }

    /**
     * 🔴 The assertion that actually encodes the bug.
     *
     * "An affinity is declared" is not the property — `MainActivity`'s own
     * (implicit) affinity is the package name, and declaring the overlay's to
     * be *that* would reproduce #11 exactly while passing the test above. What
     * must hold is that the two differ.
     */
    @Test
    fun theOverlayAffinityDiffersFromTheApplicationDefault() {
        val default = ctx.packageName
        assertFalse(
            "the overlay's taskAffinity (${DictateLaunch.OVERLAY_TASK_AFFINITY}) " +
                "equals the application default ($default), so NEW_TASK will " +
                "still land it in MainActivity's task — this is issue #11",
            DictateLaunch.OVERLAY_TASK_AFFINITY == default,
        )
    }

    @Test
    fun theAffinityCheckCanSeeAMissingAttributeAndAMentionInAComment() {
        // Negative control. The real assertions are `contains` over text read
        // from disk; if the reader or the regex were wrong they would report a
        // confident pass on anything. Feed the pipeline a known-bad manifest
        // and require it to be seen as bad.
        val commentOnly = """
            <manifest><application>
            <!-- android:taskAffinity="dev.zacx.dicta.overlay" is what fixes #11 -->
            <activity android:name=".DictateActivity" android:exported="false" />
            </application></manifest>
        """.trimIndent()
        assertFalse(
            "an affinity named only inside a comment must not satisfy the check",
            withoutComments(commentOnly).contains("android:taskAffinity="),
        )

        // Positive control: the same pipeline must SEE a real attribute.
        val real = """
            <manifest><application>
            <activity android:name=".DictateActivity"
                android:taskAffinity="dev.zacx.dicta.overlay" />
            </application></manifest>
        """.trimIndent()
        assertTrue(
            "the check cannot see the attribute when it IS set, so the real " +
                "test's pass proves nothing",
            withoutComments(real).contains(
                "android:taskAffinity=\"dev.zacx.dicta.overlay\""
            ),
        )
    }

    // --- the ledger: who may build an overlay intent ------------------------

    /**
     * Every surface that **opens the overlay** must route through
     * [DictateLaunch] — which is narrower than "every trigger", deliberately.
     *
     * 🔴 **Read the assertion, not the heading.** What this scans is the set of
     * files hand-building a `DictateActivity` intent. It therefore says nothing
     * about a trigger that opens no overlay: the Quick Settings tile is a
     * from-anywhere surface, routes through [DictateLaunch] nowhere, and is
     * correctly absent below because it constructs no intent. The old heading
     * ("every 'from anywhere' trigger must route through DictateLaunch")
     * described coverage this test does not have — and reading as coverage
     * while providing none is worse than providing none, because it stops
     * anyone looking.
     *
     * 🔴 A ledger, not a spot check. #11 was one predicate open-coded at three
     * sites and wrong at all three; the failure mode of fixing it is that a
     * *fourth* constructor later open-codes it again and nothing goes red. This
     * fails when the set of files constructing a `DictateActivity` intent grows
     * **or** shrinks, so adding one forces a decision here.
     *
     * `MainActivity` is on the allowed list because its "Try dictation now"
     * button deliberately starts the overlay **in its own task with no flags** —
     * correct there, since the user is already looking at the app.
     */
    @Test
    fun onlyDictateLaunchAndMainActivityConstructOverlayIntents() {
        val srcDir = listOf(
            File("app/src/main/java/dev/zacx/dicta"),
            File("src/main/java/dev/zacx/dicta"),
            File("../app/src/main/java/dev/zacx/dicta"),
        ).firstOrNull { it.isDirectory }
        assertTrue(
            "could not locate the source directory from ${File(".").absolutePath}",
            srcDir != null,
        )

        val constructors = srcDir!!.listFiles { f -> f.name.endsWith(".kt") }
            .orEmpty()
            .filter { f -> CONSTRUCTS_OVERLAY_INTENT.containsMatchIn(stripComments(f.readText())) }
            .map { it.name }
            .sorted()

        assertEquals(
            "the set of files hand-building a DictateActivity intent changed. " +
                "Every trigger that opens the overlay from OUTSIDE the app must " +
                "go through DictateLaunch, or it will repeat issue #11. If you " +
                "added a trigger, route it through DictateLaunch; if you " +
                "genuinely need a new in-task launch, add it here deliberately.",
            listOf("DictateLaunch.kt", "MainActivity.kt"),
            constructors,
        )
    }

    @Test
    fun theLedgerScanActuallyMatchesSomething() {
        // 🔴 Positive control for the ledger above: an empty match set and a
        // correctly-empty scan are indistinguishable, so the ledger's result is
        // a fact about the REGEX until this passes. It also pins the receiver
        // insensitivity — the first version of the pattern was literally
        // "Intent(this, ...)" and therefore could not see DictateLaunch's own
        // "Intent(context, ...)", which made the ledger wrong in the one file
        // it exists to protect.
        val sample = """
            class X {
                // Intent(this, DictateActivity::class.java)  <- only a comment
                fun a() { startActivity(Intent(this, DictateActivity::class.java)) }
                fun b() = Intent(context, DictateActivity::class.java)
                fun c() = Intent(ctx , DictateActivity::class.java)
            }
        """.trimIndent()
        val stripped = stripComments(sample)

        assertEquals(
            "the ledger's pattern must match every receiver spelling and must " +
                "NOT match the commented-out one",
            3,
            CONSTRUCTS_OVERLAY_INTENT.findAll(stripped).count(),
        )

        // And it must not fire on something that merely mentions the class.
        assertFalse(
            "the pattern matches a bare type reference, so it would count " +
                "files that never construct an intent",
            CONSTRUCTS_OVERLAY_INTENT.containsMatchIn(
                "val c: Class<*> = DictateActivity::class.java"
            ),
        )
    }

    private companion object {
        /**
         * Matches a hand-built `Intent(<receiver>, DictateActivity::class.java)`
         * for any receiver spelling (`this`, `context`, `ctx`, …) and any
         * incidental whitespace. Receiver-insensitive on purpose — see
         * [theLedgerScanActuallyMatchesSomething].
         */
        val CONSTRUCTS_OVERLAY_INTENT =
            Regex("""Intent\(\s*\w+\s*,\s*DictateActivity::class\.java\s*\)""")

        /** Removes line and block comments so prose cannot satisfy a scan. */
        fun stripComments(src: String): String = src
            .replace(Regex("//.*"), "")
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
    }
}
