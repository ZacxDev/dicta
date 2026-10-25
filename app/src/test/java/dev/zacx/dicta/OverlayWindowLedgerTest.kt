package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 🔴 A ledger of **who adds a `WindowManager` view** — an overlay window.
 *
 * ## The failure this exists to make loud
 *
 * An always-on-top overlay window is composited by `MediaProjection` into
 * every frame it captures, so Dicta's own UI ends up **inside the user's
 * screenshot**. That shipped: it was PR #4's bug, and the cause was an overlay
 * window added without registering an [OverlayVisibility] sink to hide it for
 * the duration of a capture. There is no exception, no log line and no build
 * warning — the only observable is Dicta's bubble sitting in the picture the
 * user just took and shared.
 *
 * A **second overlay window** is the way that regresses, and nothing
 * structural was watching for one. Hence a ledger: it fails when the set
 * **grows or shrinks**, so a new overlay surface forces a deliberate decision
 * here instead of inheriting the old bug.
 *
 * 🔴 The ledger is a map from file to **how many** windows that file adds, not
 * a list of files. A set of file names passes unchanged when an existing owner
 * opens a second window, which is a guard that can be satisfied while the
 * exact hazard it names exists in a different shape — and it is the shape that
 * arrived: the unreachable-server warning is a second `addView` inside
 * `BubbleService.kt`.
 *
 *  * `BubbleService.kt` — **two** windows. (1) the bubble, which shares its
 *    window with the cancel button and the history row; (2) the
 *    unreachable-server warning dialog, which cannot be a child of (1) because
 *    that window is dragged, `WRAP_CONTENT` and positioned at `Gravity.TOP|END`
 *    — see the field kdoc on `BubbleService.warning`. One
 *    `OverlayVisibility.register(visibilitySink)` in `onCreate` drives both,
 *    from one boolean, so the two windows cannot disagree about whether Dicta
 *    is on screen.
 *
 * 🔴 **THAT LAST SENTENCE IS A FACT ABOUT THE SOURCE, NOT SOMETHING THIS FILE
 * CHECKS** — and until 2026-10-25 nothing checked the dialog half of it.
 * Measured at `03e2d02`: deleting the sink's entire `val dialog = warning …
 * updateViewLayout(dialog, dialogParams)` block left this ledger green and the
 * other 698 tests green too, because a count of `addView` sites is satisfied by
 * a window that is never hidden. The behavioural guards are
 * [BubbleHiddenWhileTheCardIsUpTest] for the bubble and
 * `BubbleUnreachableWarningTest.theWarningDialogLeavesTheScreenForACaptureAndComesBack`
 * for the dialog; each drives `OverlayVisibility` and asserts its own window's
 * `alpha`/`FLAG_NOT_TOUCHABLE`. A new overlay window needs one too — bumping
 * the number below is half the job.
 *
 * 🔴 **Receiver-spelling insensitivity is load-bearing, and was learned the
 * hard way.** [DictationSessionLedgerTest]'s kdoc records the sibling ledger's
 * first version matching a literal receiver (`Intent(this, …`) and therefore
 * being blind to `Intent(context, …` — blind in the one file it existed to
 * protect. So this pattern does **not** key on the name `wm`: it finds every
 * identifier the file binds to a `WindowManager` (a typed `val`/`var`, a
 * `getSystemService(WindowManager…)` assignment, an `as WindowManager` cast)
 * and then looks for `addView(` on any of them, safe-call included, plus the
 * inline-chained form. Pinned in
 * [theAddViewPatternSeesEveryWindowManagerCallAndNoneOfTheViewGroupOnes].
 *
 * 🔴 **What it cannot see**, stated so the clean result is not over-read: a
 * `ViewGroup.addView` is deliberately excluded (`BubbleService.kt` has five of
 * those, nesting views *inside* the one window — they are not new windows), and
 * so is any overlay reached through a wrapper type, a factory, a lambda, or a
 * `WindowManager` obtained in one file and handed to another. The ledger
 * tracks textual call sites of the platform API, not the behaviour.
 *
 * Measured over every `.kt` file in `app/src/main/java/dev/zacx/dicta`: exactly
 * one file matches, with exactly two calls — `BubbleService.kt`'s
 * `wm.addView(container, params)` and `wm.addView(view, p)`.
 */
class OverlayWindowLedgerTest {

    @Test
    fun onlyTheDeclaredOwnersAddAWindowManagerView() {
        val adders = sourceFiles()
            .map { it.name to windowManagerAddViewCount(it.readText()) }
            .filter { (_, n) -> n > 0 }
            .sortedBy { (name, _) -> name }
            .toMap()

        assertEquals(
            "the per-file count of WindowManager.addView calls changed. Every " +
                "such view is an overlay window, and MediaProjection " +
                "composites an overlay window into EVERY frame it captures — " +
                "which is how Dicta's own UI ended up inside the user's " +
                "screenshots (PR #4). If you added an overlay window: drive it " +
                "from the SAME OverlayVisibility sink as the existing ones, so " +
                "it is hidden for the duration of a capture, or put the view " +
                "inside an existing window instead of opening another. Then " +
                "change the count here deliberately — AND add a behavioural " +
                "test that drives OverlayVisibility and asserts your window's " +
                "own params go alpha=0 + FLAG_NOT_TOUCHABLE and back, next to " +
                "BubbleHiddenWhileTheCardIsUpTest (the bubble) and " +
                "BubbleUnreachableWarningTest." +
                "theWarningDialogLeavesTheScreenForACaptureAndComesBack (the " +
                "warning dialog). 🔴 THIS ASSERTION CANNOT CHECK THAT, and " +
                "bumping the number is not the whole job: it compares COUNTS, " +
                "so it is satisfied by an addView whose window is never hidden " +
                "at all. Measured at 03e2d02 — with the dialog's entire half of " +
                "the sink deleted, this test still PASSED and so did the other " +
                "698. 🔴 A COUNT and not a set of file names: a set passes " +
                "unchanged when an existing owner opens a second window, which " +
                "is exactly how this feature's warning dialog arrived.",
            mapOf("BubbleService.kt" to 2),
            adders,
        )
    }

    /**
     * Every overlay-window owner also *mentions* `OverlayVisibility.register`.
     *
     * 🔴 **A weak, textual guard — labelled as such.** It cannot check that the
     * sink is correct: not that it is ever invoked, not that it zeroes the
     * right window's `params.alpha`, not that the alpha it zeroes belongs to
     * the window that was added rather than to some other set of layout
     * params. A file could satisfy this by naming the call in dead code. The
     * **real evidence is a device screenshot** taken while the overlay is up,
     * inspected for Dicta's own UI; nothing textual substitutes for that.
     *
     * It is worth having anyway because the specific omission it names — an
     * overlay window added with no sink registered at all — is the one that
     * actually shipped. A guard that catches the exact historical mistake and
     * nothing else is still strictly better than no guard, provided nobody
     * reads it as more. It is not regression coverage for the screenshot bug;
     * it is a reminder at the one spot where the bug is introduced.
     */
    @Test
    fun everyOverlayWindowOwnerRegistersAVisibilitySink() {
        val missing = sourceFiles()
            .filter { f -> windowManagerAddViewCount(f.readText()) > 0 }
            .filterNot { f -> stripComments(f.readText()).contains("OverlayVisibility.register") }
            .map { it.name }
            .sorted()

        assertEquals(
            "a file adds a WindowManager view but never names " +
                "OverlayVisibility.register, so its overlay will be " +
                "composited into the user's screenshots exactly as in PR #4. " +
                "Register a sink that hides the view AND zeroes its " +
                "params.alpha, then confirm with a real device screenshot — " +
                "this check is textual and cannot verify the sink works.",
            emptyList<String>(),
            missing,
        )
    }

    /**
     * Positive and negative controls for the `addView` detection.
     *
     * 🔴 Until this passes, the ledger above is a fact about a regex rather
     * than about the source tree — and the negative half matters as much as
     * the positive one here, because the tree's `ViewGroup.addView` calls
     * outnumber the real one five to one. A pattern that fired on those would
     * list files that open no window at all and the ledger would be noise.
     */
    @Test
    fun theAddViewPatternSeesEveryWindowManagerCallAndNoneOfTheViewGroupOnes() {
        // Declared bindings, so the samples below exercise the identifier
        // resolution rather than a hardcoded receiver name.
        val bindings = """
            private lateinit var wm: WindowManager
            private var windowManager: WindowManager? = null
            private var overlayWm: WindowManager? = null
        """.trimIndent()

        val calls = listOf(
            "wm.addView(container, params)",
            "windowManager.addView(v, p)",
            "overlayWm?.addView(v, p)",
            "wm . addView (\n    container,\n    params,\n)",
            "val w = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager\nw.addView(v, p)",
            "(getSystemService(WindowManager::class.java)).addView(v, p)",
        )
        for (sample in calls) {
            assertEquals(
                "the pattern must see a WindowManager.addView however the " +
                    "receiver is spelled — the sibling ledger's first version " +
                    "was receiver-specific and was blind to the one file it " +
                    "existed to protect: <$sample>",
                1,
                windowManagerAddViewCount("$bindings\n$sample"),
            )
        }

        val nonCalls = listOf(
            // ViewGroup.addView — nesting a view INSIDE the existing window,
            // which is the recommended fix, not the hazard.
            "halo.addView(pulse)",
            "container.addView(abort, wanted)",
            // Bare addView inside a LinearLayout `apply` block.
            "addView(halo)",
            "// wm.addView(container, params) <- only a comment",
            "/* wm.addView(v, p) */",
            // Mere mentions of the type.
            "import android.view.WindowManager",
            "params = WindowManager.LayoutParams(0, 0)",
            "window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)",
        )
        for (sample in nonCalls) {
            assertEquals(
                "the pattern fired on something that opens no overlay window, " +
                    "so the ledger would list files that own none: <$sample>",
                0,
                windowManagerAddViewCount("$bindings\n$sample"),
            )
        }

        // And the sink check must not be satisfied by a commented-out call.
        assertFalse(
            "a commented-out OverlayVisibility.register must not count as a " +
                "registration, or the weak guard above becomes vacuous",
            stripComments("// OverlayVisibility.register(sink)")
                .contains("OverlayVisibility.register"),
        )
    }

    // --- reading the tree ----------------------------------------------------

    private fun sourceFiles(): List<File> =
        sourceDir().listFiles { f -> f.name.endsWith(".kt") }.orEmpty().toList()

    private fun sourceDir(): File {
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
        return dir!!
    }

    private companion object {

        /**
         * The ways a file can bind an identifier to a `WindowManager`. The
         * capture group is the identifier, so the `addView` search below is
         * insensitive to what it was named.
         *
         * The `(?!\s*\.)` on the typed form keeps a qualified reference
         * (`WindowManager.LayoutParams`) from being read as a binding.
         */
        val WM_BINDINGS = listOf(
            Regex("""(?:val|var)\s+(\w+)\s*:\s*WindowManager\b(?!\s*\.)"""),
            Regex("""(\w+)\s*=\s*getSystemService\s*\(\s*WindowManager"""),
            Regex("""(?:val|var)\s+(\w+)\s*=[^\n]*\bas\s+WindowManager\b"""),
        )

        /**
         * The inline-chained form, where the manager is never bound to a name:
         * `getSystemService(…WindowManager…).addView(` or
         * `(… as WindowManager).addView(`.
         */
        val WM_ADD_VIEW_CHAINED = Regex(
            """(?:getSystemService[^\n]*WindowManager[^\n]*|as\s+WindowManager\s*)\)?\s*\.\s*addView\s*\("""
        )

        /**
         * How many overlay windows this source adds: `addView(` on anything
         * the file bound to a `WindowManager`, plus the chained form.
         *
         * 🔴 Comments are stripped FIRST. Both this file and `BubbleService.kt`
         * discuss the hazard in prose that names the call, so a raw search
         * would match the explanation of why the window is safe.
         */
        fun windowManagerAddViewCount(src: String): Int {
            val source = stripComments(src)
            val receivers = WM_BINDINGS
                .flatMap { re -> re.findAll(source).map { it.groupValues[1] } }
                .toSet()
            val viaReceiver = receivers.sumOf { id ->
                Regex("""\b${Regex.escape(id)}\s*(?:\?\.|\.)\s*addView\s*\(""")
                    .findAll(source)
                    .count()
            }
            return viaReceiver + WM_ADD_VIEW_CHAINED.findAll(source).count()
        }

        fun stripComments(src: String): String = src
            .replace(Regex("//.*"), "")
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
    }
}
