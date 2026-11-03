package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 🔴 The seam between **the overlay THEME and the code that sizes its window**.
 *
 * ## The defect this exists to make loud
 *
 * `@style/Theme.Dicta.Overlay` makes an activity's window *floating*. Nothing
 * in it makes the window *wide*: a floating window is `WRAP_CONTENT`, so
 * `match_parent` in the layout matches the window and the window's own width is
 * whatever its widest `wrap_content` child wants. Capping it is a **code**
 * obligation — `FloatingWindow.applySize` — because the only theme attributes
 * that could do it (`windowFixedWidthMinor`/`Major`) are private platform
 * attributes that fail aapt2 linking from an app theme.
 *
 * That obligation was invisible, and it was missed. `DictateActivity` had the
 * sizing from the overlay redesign; `SnipPickerActivity` arrived with issue #38
 * wearing the same theme and never called anything equivalent. On a real Galaxy
 * S21 running 0.4.0 the picker rendered at roughly a **fifth** of the screen
 * width, truncating every row label to about six characters. The whole suite
 * was green through it — 815 tests, a round-0 and a round-1 audit — because
 * Robolectric has no window manager and nothing asserted a window's size.
 *
 * ## 🔴 Membership comes from the MANIFEST'S `android:theme`, not from a name
 *
 * The set this guard is about is "every Activity whose window is floating", and
 * the manifest is where that is decided. A ledger selected by file name — the
 * shape round 1 of this very feature had to replace in [SnipSeamLedgerTest],
 * where `startsWith("Snip")` was blind to a snip-flow file called anything else
 * — would be blind to the only case that matters: a NEW floating activity, whose
 * name nobody can predict. `FloatingPickerActivity`, `QuickReplyActivity` and
 * `Overlay2Activity` are all equally plausible and none of them shares a prefix
 * with the two that exist.
 *
 * ## 🔴 It fails on GROWTH and on SHRINK
 *
 * Growth is the hazard — a third floating surface that never calls the sizing
 * is exactly this bug again. Shrink matters as much: a set that shrank means an
 * activity stopped being floating (or stopped being declared), and every
 * behavioural assertion in [FloatingWindowSizeTest] would still be green for
 * the ones that remain.
 *
 * ## What it cannot see, stated so the clean result is not over-read
 *
 * This is a **textual** ledger over the manifest and the Kotlin sources. It
 * cannot see a theme applied at runtime with `setTheme()`, a floating window
 * created by a dialog or a `WindowManager` view (that is
 * [OverlayWindowLedgerTest]'s subject), a sizing call reached through a wrapper
 * or a lambda, or a call that is present but passes the wrong activity — a
 * structural check type-checks past a wrong argument. The behavioural half is
 * [FloatingWindowSizeTest], which drives both real activities and reads the
 * window's actual `attributes.width`.
 */
class FloatingOverlaySeamLedgerTest {

    /**
     * Every activity declared with `android:theme="@style/Theme.Dicta.Overlay"`.
     *
     * Measured from `AndroidManifest.xml`, not from intent. Adding a floating
     * surface means editing this list, which is the point: the edit is where the
     * sizing obligation gets read.
     */
    private val overlayThemedActivities = listOf(
        ".DictateActivity",
        ".SnipPickerActivity",
    )

    @Test
    fun theSetOfFloatingOverlayActivitiesIsLedgered() {
        assertEquals(
            "the set of activities declaring " +
                "android:theme=\"@style/Theme.Dicta.Overlay\" changed. Such a " +
                "window is FLOATING, i.e. WRAP_CONTENT in both axes, and the " +
                "theme cannot cap its width — windowFixedWidthMinor/Major are " +
                "private platform attributes. So a new one MUST call " +
                "FloatingWindow.applySize(this) from onCreate, after " +
                "setContentView, or it renders at the width of its widest " +
                "wrap_content child. That is not hypothetical: it is what " +
                "SnipPickerActivity did in 0.4.0 on a Galaxy S21 — about a " +
                "fifth of the screen, every row label truncated to ~6 " +
                "characters, with all 815 tests green. Ledger the new activity " +
                "here AND give it a case in FloatingWindowSizeTest.",
            overlayThemedActivities.sorted(),
            activitiesWithTheme(OVERLAY_THEME),
        )
    }

    @Test
    fun everyFloatingOverlayActivityAppliesTheSharedWindowSizing() {
        val missing = overlayThemedActivities
            .map { sourceFileFor(it) }
            .filterNot { (_, src) -> APPLIES_SHARED_SIZE.containsMatchIn(stripComments(src)) }
            .map { it.first }
            .sorted()

        assertEquals(
            "a floating overlay activity never calls " +
                "FloatingWindow.applySize. Its window is WRAP_CONTENT, so " +
                "`match_parent` in its layout matches the WINDOW and the " +
                "window's width collapses to its widest wrap_content child. " +
                "Call it from onCreate AFTER setContentView — " +
                "PhoneWindow.generateLayout() resets a floating window to " +
                "WRAP_CONTENT x WRAP_CONTENT while inflating, so an earlier " +
                "call is silently undone.",
            emptyList<String>(),
            missing,
        )
    }

    /**
     * 🔴 One owner, enforced — the other half of the fix.
     *
     * Wiring the picker by COPYING `applyFloatingWindowSize()` into it would
     * satisfy the ledger above while making two copies of a rule that must
     * agree, and `claude/RULES.md` names what happens then: a predicate
     * duplicated across call sites regenerates the same bug at every site. This
     * one goes red on the copy.
     */
    @Test
    fun onlyFloatingWindowSizesAWindow() {
        val sizers = mainSources()
            .filter { (_, src) -> SETS_WINDOW_LAYOUT.containsMatchIn(stripComments(src)) }
            .map { it.first }
            .sorted()

        assertEquals(
            "the set of files calling Window.setLayout changed. The floating " +
                "overlay's geometry has exactly ONE owner — FloatingWindow — " +
                "and a second copy of it is how the width rule starts " +
                "disagreeing with itself. A new floating surface must CALL " +
                "FloatingWindow.applySize, not reimplement it; the two copies " +
                "that preceded this guard were DictateActivity's method and the " +
                "nothing SnipPickerActivity had instead.",
            listOf("FloatingWindow.kt"),
            sizers,
        )
    }

    // --- controls for every instrument above ---------------------------------

    @Test
    fun theManifestThemeScanSeesAThirdFloatingActivityAndIgnoresTheOthers() {
        // 🔴 NEGATIVE CONTROL for the ledger: a manifest it MUST reject. Without
        // it, the green above says only "today's manifest matches" and
        // establishes nothing about whether the scan can see a new floating
        // surface at all — which is the single case it exists for.
        val grown = """
            <manifest>
              <application android:theme="@style/Theme.Dicta">
                <activity android:name=".MainActivity" android:exported="true">
                  <intent-filter />
                </activity>
                <activity
                    android:name=".DictateActivity"
                    android:theme="@style/Theme.Dicta.Overlay"
                    android:launchMode="singleTop" />
                <activity
                    android:theme="@style/Theme.Dicta.Overlay"
                    android:name=".QuickReplyActivity" />
                <activity android:name=".SettingsActivity" />
              </application>
            </manifest>
        """.trimIndent()

        assertEquals(
            "the theme scan cannot see a newly-added floating activity, or it " +
                "keys on attribute ORDER — the second element below writes " +
                "android:theme before android:name, which a maintainer may do " +
                "and which must not hide a surface",
            listOf(".DictateActivity", ".QuickReplyActivity"),
            activitiesWithThemeIn(grown, OVERLAY_THEME),
        )

        // And it must not drag in activities on other themes, or the ledger is
        // noise and gets bumped without being read.
        assertEquals(
            "the theme scan fired on activities that do not declare the " +
                "overlay theme, so the ledger would demand a sizing call from " +
                "surfaces that are not floating",
            emptyList<String>(),
            activitiesWithThemeIn(grown, "@style/Theme.Dicta.Nonexistent"),
        )

        // POSITIVE CONTROL on the real file: a zero here would be
        // indistinguishable from a dead regex reading the wrong path.
        assertTrue(
            "the real manifest yielded no <activity> elements at all, so every " +
                "result above is a fact about a regex rather than about the " +
                "manifest",
            allActivityNames().size >= 4,
        )
    }

    @Test
    fun theSizingCallScanSeesTheCallHoweverItIsWrittenAndNotInProse() {
        val calls = listOf(
            "FloatingWindow.applySize(this)",
            "FloatingWindow . applySize ( activity )",
            "FloatingWindow.applySize(\n    this,\n)",
        )
        for (sample in calls) {
            assertTrue(
                "the scan cannot see a real sizing call: <$sample>. A " +
                    "ktfmt reformat that wraps the call across two lines must " +
                    "not make a correct activity report as missing.",
                APPLIES_SHARED_SIZE.containsMatchIn(stripComments(sample)),
            )
        }

        val nonCalls = listOf(
            "// FloatingWindow.applySize(this) <- only a comment",
            "/* calls FloatingWindow.applySize(this) after setContentView */",
            "val x = FloatingWindow",
            "// see FloatingWindow.applySize",
        )
        for (sample in nonCalls) {
            assertFalse(
                "the scan was satisfied by prose rather than by a call: " +
                    "<$sample>. Prose naming a construction must not satisfy a " +
                    "scan — that is how a guard reads as coverage while " +
                    "providing none.",
                APPLIES_SHARED_SIZE.containsMatchIn(stripComments(sample)),
            )
        }

        // 🔴 NEGATIVE CONTROL for the per-activity guard: a themed activity
        // whose source omits the call must be REPORTED. This is the exact
        // pre-change state of SnipPickerActivity.
        val unsized = stripComments(
            """
            class QuickReplyActivity : Activity() {
                override fun onCreate(b: Bundle?) {
                    super.onCreate(b)
                    setContentView(R.layout.activity_quick_reply)
                }
            }
            """.trimIndent()
        )
        assertFalse(
            "a themed activity that never sizes its window is invisible to the " +
                "per-activity guard, so its empty missing-list says nothing",
            APPLIES_SHARED_SIZE.containsMatchIn(unsized),
        )
    }

    @Test
    fun theWindowSizingOwnerScanSeesACopyPasteAndIgnoresMereMentions() {
        val sizings = listOf(
            "window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)",
            "activity.window.setLayout(w, h)",
            "getWindow().setLayout(w, h)",
            "window . setLayout (\n    width,\n    h,\n)",
        )
        for (sample in sizings) {
            assertTrue(
                "the owner scan cannot see a window being sized: <$sample>. A " +
                    "copy-pasted sizing method in a second activity would then " +
                    "be invisible, which is the duplication this guard exists " +
                    "to refuse.",
                SETS_WINDOW_LAYOUT.containsMatchIn(stripComments(sample)),
            )
        }

        val nonSizings = listOf(
            "// window.setLayout(width, h) is how the cap is applied",
            "/* Window.setLayout is the public equivalent */",
            "params = WindowManager.LayoutParams(0, 0)",
            "view.layoutParams = LinearLayout.LayoutParams(w, h)",
            "import android.view.WindowManager",
        )
        for (sample in nonSizings) {
            assertFalse(
                "the owner scan fired on something that sizes no window, so " +
                    "the one-file result would be noise: <$sample>",
                SETS_WINDOW_LAYOUT.containsMatchIn(stripComments(sample)),
            )
        }
    }

    // --- reading the tree ----------------------------------------------------

    /** Names of every `<activity>` declaring `android:theme="[theme]"`. */
    private fun activitiesWithTheme(theme: String): List<String> =
        activitiesWithThemeIn(manifestText(), theme)

    private fun activitiesWithThemeIn(manifest: String, theme: String): List<String> =
        ACTIVITY_ELEMENT.findAll(manifest)
            .filter { m -> attr(m.value, "theme") == theme }
            .mapNotNull { m -> attr(m.value, "name") }
            .sorted()
            .toList()

    private fun allActivityNames(): List<String> =
        ACTIVITY_ELEMENT.findAll(manifestText()).mapNotNull { attr(it.value, "name") }.toList()

    /**
     * The `.kt` source behind a manifest `android:name`, as `name to text`.
     *
     * Fails loudly rather than skipping: an activity declared in the manifest
     * with no source file is a build error, and silently dropping it from the
     * scan would make the per-activity guard narrower than its own description.
     */
    private fun sourceFileFor(manifestName: String): Pair<String, String> {
        val simple = manifestName.substringAfterLast('.') + ".kt"
        val found = mainSources().firstOrNull { it.first == simple }
        assertTrue(
            "the manifest declares $manifestName but there is no $simple in " +
                "the production sources — refusing to report a pass on an " +
                "activity whose source was never read",
            found != null,
        )
        return found!!
    }

    private fun mainSources(): List<Pair<String, String>> {
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

    private fun manifestText(): String {
        val manifest = listOf(
            File("app/src/main/AndroidManifest.xml"),
            File("src/main/AndroidManifest.xml"),
            File("../app/src/main/AndroidManifest.xml"),
        ).firstOrNull { it.isFile }
        assertTrue(
            "could not read AndroidManifest.xml from ${File(".").absolutePath} " +
                "— refusing to report a pass on a file that was never read",
            manifest != null,
        )
        return manifest!!.readText()
    }

    private companion object {

        const val OVERLAY_THEME = "@style/Theme.Dicta.Overlay"

        /**
         * An `<activity …>` element, self-closing or not. `[^>]*` is safe
         * because an attribute value cannot contain `>` unescaped, and it keeps
         * the match from running past the element into the next one.
         */
        val ACTIVITY_ELEMENT = Regex("""<activity\b[^>]*>""", RegexOption.DOT_MATCHES_ALL)

        /**
         * One `android:<key>` attribute of an element.
         *
         * Attribute-ORDER insensitive on purpose: keying the scan on `name`
         * appearing before `theme` would hide a surface from the ledger because
         * of how someone formatted the manifest, and
         * [theManifestThemeScanSeesAThirdFloatingActivityAndIgnoresTheOthers]
         * pins that with an element written the other way round.
         */
        fun attr(element: String, key: String): String? =
            Regex("""android:${Regex.escape(key)}\s*=\s*"([^"]*)"""")
                .find(element)
                ?.groupValues
                ?.get(1)

        /**
         * The shared sizing being CALLED — whitespace-tolerant, so a `ktfmt`
         * reformat cannot make a correct activity report as missing, and
         * requiring the open paren, so a bare type reference or a prose mention
         * of the method name does not satisfy it.
         */
        val APPLIES_SHARED_SIZE =
            Regex("""FloatingWindow\s*\.\s*applySize\s*\(""")

        /**
         * A `Window` being sized. Receiver-insensitive for
         * [OverlayWindowLedgerTest]'s measured reason — a sibling ledger's
         * first version matched a literal receiver and was blind in the one
         * file it existed to protect — so this matches `setLayout(` on anything
         * whose name ENDS in `window` or `getWindow()`, case-insensitively.
         * (No leading `\b`: in `getWindow()` the capital W is preceded by a
         * word character, so a word boundary there would never match — which is
         * the Java-style spelling a future file is most likely to use.)
         */
        val SETS_WINDOW_LAYOUT =
            Regex("""(?i)window(?:\(\))?\s*(?:\?\.|\.)\s*setLayout\s*\(""")

        fun stripComments(src: String): String = src
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("//[^\\n]*"), " ")
    }
}
