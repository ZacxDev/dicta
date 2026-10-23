package dev.zacx.dicta

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import java.io.File

/**
 * 🔴 A ledger of **who can change "is the bubble enabled"**, plus the
 * behavioural half a text scan cannot give.
 *
 * ## The failure this exists to make loud
 *
 * The flag had exactly one writer — [MainActivity]'s switch — and that was the
 * defect, not the design: the notification's *Hide bubble* action stopped the
 * service from a different file with no way to record it, so the switch kept
 * claiming a bubble that was gone (issue #14). The fix is one owner,
 * [BubbleEnabled], and the hazard the fix introduces is the opposite one: a new
 * surface reaching past the owner into `SharedPreferences`, or a new writer
 * nobody decided on.
 *
 * So two assertions, in two different dimensions:
 *
 *  * [onlyTheOwnerTouchesTheRawPreferenceKey] — the *key* is reachable from one
 *    file. It fails if the set grows (a second file open-coding the pref) or
 *    shrinks (the owner stopped being the owner).
 *  * [onlyTheDeclaredSurfacesChangeWhetherTheBubbleIsOn] — the *mutators* are called from a
 *    declared set of files. It fails when that set grows **or shrinks**.
 *
 * ## Why the scan strips comments and normalises whitespace
 *
 * A line-based scan in this repo has previously both missed a one-line
 * expression-bodied forwarder and gone spuriously red on a pure `ktfmt`
 * reformat that only moved a call's arguments onto new lines. The source is
 * therefore comment-stripped and whitespace-collapsed before matching, and the
 * patterns are insensitive to argument spelling — pinned by
 * [thePatternsSeeEveryCallSpellingAndNoneOfTheMereMentions], without which
 * every count above is a fact about a regex rather than about the tree.
 *
 * 🔴 **Scope.** Membership is not evidence that a member is *correct*: a
 * structural check type-checks straight past `set(context, true)` where `false`
 * was meant. [theOwnerIsTheSingleSourceOfTruthAcrossSurfaces] is the
 * behavioural pair — one surface's write is the next surface's read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BubbleEnabledWritersSeamTest {

    /**
     * 🔴 Not optional tidiness. [BubbleEnabled.serviceStarter] is process-wide
     * state, and a test that sets it and then *fails* never reaches a cleanup
     * line at the end of its own body — the refusal seam then leaks into
     * whatever test runs next, which showed up here as a sibling reporting
     * REFUSED where it asserted STARTED. A failing test must not be able to
     * change what its neighbours measure.
     */
    @After
    fun tearDown() {
        BubbleEnabled.serviceStarter = null
    }

    @Test
    fun onlyTheOwnerTouchesTheRawPreferenceKey() {
        val files = sourceFiles()
            .filter { f -> TOUCHES_RAW_KEY.containsMatchIn(normalise(f.readText())) }
            .map { it.name }
            .sorted()

        assertEquals(
            "the set of files naming the raw \"bubble_enabled\" preference key " +
                "changed. It must be reachable from BubbleEnabled.kt and " +
                "nowhere else: a surface that open-codes the pref cannot be " +
                "made to agree with the others, which is exactly how the " +
                "notification's Hide action and MainActivity's switch came to " +
                "disagree (issue #14). Route the new surface through " +
                "BubbleEnabled instead of adding it here.",
            listOf("BubbleEnabled.kt"),
            files,
        )
    }

    @Test
    fun onlyTheDeclaredSurfacesChangeWhetherTheBubbleIsOn() {
        val files = sourceFiles()
            .filter { f -> f.name != "BubbleEnabled.kt" }
            .filter { f -> CHANGES_BUBBLE_STATE.containsMatchIn(normalise(f.readText())) }
            .map { it.name }
            .sorted()

        assertEquals(
            "the set of files that turn the bubble on or off changed. Each of " +
                "these is a surface the user can reach, and every one of them " +
                "must leave the flag agreeing with whether the service is " +
                "running — a surface that stops the bubble without clearing " +
                "the flag re-creates issue #14, and one that sets the flag " +
                "without starting the service creates its mirror image. Add " +
                "yours here deliberately, then write its behavioural test: " +
                "this scan cannot tell whether you passed the right boolean.",
            listOf(
                // Restores the bubble after a reboot: starts the service, and
                // deliberately does NOT write the flag — the user's stored
                // intent is unchanged by a reboot.
                "BubbleBootReceiver.kt",
                // The notification's "Hide bubble" action: clears the flag
                // while stopping itself.
                "BubbleService.kt",
                // The Quick Settings toggle.
                "DictaTileService.kt",
                // The switch on the setup screen.
                "MainActivity.kt",
            ).sorted(),
            files,
        )
    }

    /**
     * 🔴 Positive and negative controls. Until this passes, the two ledgers
     * above are claims about a regex.
     *
     * The spellings exercised are the ones that have actually broken a scan in
     * this repo: a reformatted multi-line call, an expression-bodied one-liner,
     * a named argument, and a mention inside a comment or a kdoc link.
     */
    @Test
    fun thePatternsSeeEveryCallSpellingAndNoneOfTheMereMentions() {
        val calls = """
            class X {
                fun a() = BubbleEnabled.turnOn(this)
                fun b() { BubbleEnabled.turnOff(context) }
                fun c() = BubbleEnabled.set(
                    context,
                    false,
                )
                fun d() { BubbleEnabled.set (ctx, on = true) }
                fun e() { BubbleEnabled.startService(ctx) }
            }
        """.trimIndent()
        assertEquals(
            "the pattern must see a call however it is spelled or wrapped — a " +
                "ktfmt reformat must not change the ledger",
            5,
            CHANGES_BUBBLE_STATE.findAll(normalise(calls)).count(),
        )

        val nonCalls = listOf(
            "// BubbleEnabled.turnOn(this) <- only a comment",
            "/** routed through [BubbleEnabled.turnOff] */",
            "if (BubbleEnabled.isOn(this)) { }",
            "private val owner = BubbleEnabled",
            "BubbleEnabled.PREF_KEY",
        )
        for (sample in nonCalls) {
            assertFalse(
                "the pattern fired on something that changes nothing, " +
                    "so the ledger would list files that mutate no flag: <$sample>",
                CHANGES_BUBBLE_STATE.containsMatchIn(normalise(sample)),
            )
        }

        assertTrue(
            "the raw-key pattern must see the key however it is quoted",
            TOUCHES_RAW_KEY.containsMatchIn(normalise("""val k = "bubble_enabled"""")),
        )
        assertFalse(
            "the raw-key pattern fired on a comment mentioning the key",
            TOUCHES_RAW_KEY.containsMatchIn(normalise("""// the "bubble_enabled" pref""")),
        )
    }

    /**
     * The behavioural half: one owner means one surface's write is the next
     * surface's read.
     *
     * A structural ledger cannot see a wrong boolean. This drives the two
     * mutators and reads the result back through the raw preference — the same
     * bytes an existing install already has on disk — so a "tidied" pref file
     * or key would fail here as well.
     */
    @Test
    fun theOwnerIsTheSingleSourceOfTruthAcrossSurfaces() {
        ShadowSettings.setCanDrawOverlays(true)
        val app = RuntimeEnvironment.getApplication()
        val raw = app.getSharedPreferences("dicta", Context.MODE_PRIVATE)

        assertEquals(
            "turnOn with the overlay permission granted must report STARTED",
            BubbleEnabled.Start.STARTED,
            BubbleEnabled.turnOn(app),
        )
        assertTrue(
            "turnOn did not land in the dicta/bubble_enabled preference that " +
                "every installed copy of the app already reads",
            raw.getBoolean("bubble_enabled", false),
        )
        assertTrue("the owner disagrees with its own write", BubbleEnabled.isOn(app))

        BubbleEnabled.turnOff(app)
        assertFalse(
            "turnOff left bubble_enabled set, so the switch would read ON with " +
                "no service running",
            raw.getBoolean("bubble_enabled", true),
        )
        assertFalse("the owner disagrees with its own write", BubbleEnabled.isOn(app))

        ShadowSettings.setCanDrawOverlays(false)
        assertEquals(
            "turnOn without the overlay permission must refuse rather than " +
                "record an intent it cannot honour",
            BubbleEnabled.Start.NO_OVERLAY_PERMISSION,
            BubbleEnabled.turnOn(app),
        )
        assertFalse(
            "turnOn wrote the flag even though it could not start the bubble",
            raw.getBoolean("bubble_enabled", true),
        )
    }

    /**
     * The switch's **off** direction, which nothing else covers.
     *
     * `BubbleHideFromNotificationTest` drives the on direction (one tap after a
     * hide must restart the bubble); without this, swapping `turnOff` for
     * `turnOn` in the activity's listener would survive a fully green suite,
     * and the result on a device is a switch that starts the bubble whichever
     * way you move it.
     */
    @Test
    fun theAppSwitchTurningOffStopsTheServiceAndClearsTheFlag() {
        ShadowSettings.setCanDrawOverlays(true)
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("dicta", Context.MODE_PRIVATE)
            .edit().putBoolean("bubble_enabled", true).commit()

        val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java)
            .setup()
        val activity = controller.get()
        val switch = activity.findViewById<android.widget.Switch>(R.id.bubble_switch)
        assertTrue(
            "the fixture failed: the switch should start ON with the flag set " +
                "and the overlay permission granted, so there is nothing to " +
                "switch off",
            switch.isChecked,
        )

        switch.isChecked = false

        assertFalse(
            "switching the bubble off left bubble_enabled set, so the next " +
                "launch would show it ON again",
            BubbleEnabled.isOn(app),
        )
        val stopped = org.robolectric.Shadows.shadowOf(activity).nextStoppedService
        assertEquals(
            "switching the bubble off did not stop BubbleService, so the " +
                "overlay stays on screen with the switch claiming otherwise",
            BubbleService::class.java.name,
            stopped?.component?.className,
        )
        controller.pause().stop().destroy()
    }

    /**
     * A switch that asked for a bubble the platform refused must not stay ON.
     *
     * The refusal leaves the flag cleared, so a switch left `checked` is the
     * same disagreement between the UI and reality that issue #14 reported —
     * and because a `Switch` fires its listener only on a change, the user
     * would again have to toggle off before they could retry.
     */
    @Test
    fun aRefusedStartPutsTheSwitchBackInsteadOfLeavingItOn() {
        ShadowSettings.setCanDrawOverlays(true)
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("dicta", Context.MODE_PRIVATE)
            .edit().putBoolean("bubble_enabled", false).commit()

        val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java)
            .setup()
        val activity = controller.get()
        val switch = activity.findViewById<android.widget.Switch>(R.id.bubble_switch)
        assertFalse("the fixture should start with the bubble off", switch.isChecked)
        BubbleEnabled.serviceStarter = { throw IllegalStateException("refused") }

        switch.isChecked = true

        assertFalse(
            "the switch stayed ON after the platform refused the start, so it " +
                "claims a bubble that does not exist and the next tap is a no-op",
            switch.isChecked,
        )
        assertFalse(
            "a refused start left bubble_enabled set",
            BubbleEnabled.isOn(app),
        )
        controller.pause().stop().destroy()
    }

    private fun sourceFiles(): List<File> {
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
            "the source directory held no Kotlin files, so both ledgers would " +
                "be comparing an empty list against an empty list",
            files.size > 10,
        )
        return files
    }

    private companion object {
        /**
         * Any call through the owner that changes whether the bubble is on —
         * the flag (`set`, `turnOn`, `turnOff`) **or** the service
         * (`startService`), however the call is spelled or wrapped.
         *
         * `startService` is a member because the ledger's claim is about the
         * bubble's state and not only about the preference: the boot receiver
         * starts the service without writing the flag, and a ledger that could
         * not see it would be narrower than its own description.
         *
         * `isOn` is deliberately excluded: a reader cannot put the flag out of
         * step with reality, and including it would make every file that merely
         * *consults* the bubble a member.
         */
        val CHANGES_BUBBLE_STATE =
            Regex("""BubbleEnabled\.(set|turnOn|turnOff|startService)\s*\(""")

        /** The literal preference key, as a Kotlin string. */
        val TOUCHES_RAW_KEY = Regex(""""bubble_enabled"""")

        /**
         * Comments out, whitespace collapsed. The order matters: stripping
         * comments first means a commented-out call cannot survive as code,
         * and collapsing afterwards is what makes a reformatted multi-line
         * call look the same as a one-liner.
         */
        fun normalise(src: String): String = src
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//.*"), "")
            .replace(Regex("\\s+"), " ")
    }
}
