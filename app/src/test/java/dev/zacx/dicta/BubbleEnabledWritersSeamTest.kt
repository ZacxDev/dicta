package dev.zacx.dicta

import android.content.Context
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
 * 🔴 **One owner for the WRITES is only half of the invariant, and this class
 * used to assert only that half while its wording claimed the whole thing.**
 * Two surfaces can route every write through [BubbleEnabled] and still *show*
 * different answers — and they did: `MainActivity` rendered its switch from
 * `isOn && overlayGranted`, `DictaTileService.render` open-coded the same
 * conjunction, and that tile's `onClick` branched on bare `isOn`. So the
 * surfaces could disagree, in the worst possible place: the two halves of a
 * single toggle. The second half is
 * [everyDisplayedBubbleStateComesFromOnePredicate] — everything the user sees
 * derives from `BubbleEnabled.isUp`, and only code acting on stored intent
 * reads `isOn`.
 *
 * So assertions in several different dimensions. First the three about who may
 * write:
 *
 *  * [onlyTheOwnerTouchesTheRawPreferenceKey] — the **string literal**
 *    `"bubble_enabled"` appears in a declared set of files. It fails if that
 *    set grows (a new file spelling the key out) or shrinks (the owner stopped
 *    being the owner). 🔴 It is **two** files, not one: [ClipConfigIngress]
 *    names the same string as the `CONFIGURE` broadcast's extra, on purpose,
 *    and [theIngressNamesTheKeyWithoutBeingAbleToReachThePreferences] is what
 *    makes that safe rather than merely declared. It does **not** cover the
 *    pref *file* name `"dicta"`: other files
 *    legitimately open that same file for unrelated keys, so [TOUCHES_RAW_KEY]
 *    matches the key only.
 *  * [onlyTheDeclaredSurfacesChangeWhetherTheBubbleIsOn] — the *mutators* are called from a
 *    declared set of files. It fails when that set grows **or shrinks**.
 *  * [onlyTheOwnerCanNameThePreferenceFileAndKey] — the *constants* are
 *    `private`, which is what makes the first assertion's claim a claim about
 *    the key rather than only about a literal.
 *
 * 🔴 **The first assertion is narrower than it reads, and the third is why that
 * is now safe.** [TOUCHES_RAW_KEY] matches the literal `"bubble_enabled"`; it
 * says nothing about a file that writes the same key *through the owner's
 * constants*. While `BubbleEnabled.PREF_FILE` / `PREF_KEY` were `internal`,
 * `getSharedPreferences(BubbleEnabled.PREF_FILE, …)` + `putBoolean(BubbleEnabled.PREF_KEY, …)`
 * was a live evasion of **both** text ledgers at once: it spells no literal, so
 * the raw-key scan misses it — indeed that scan's own control at
 * [thePatternsSeeEveryCallSpellingAndNoneOfTheMereMentions] asserts it must
 * *not* fire on `BubbleEnabled.PREF_KEY` — and it calls no
 * `BubbleEnabled.<mutator>(`, so [CHANGES_BUBBLE_STATE] misses it too. No text
 * scan can close that hole. Both constants are now `private`, so the compiler
 * does, and the third assertion pins the visibility that is doing the work.
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
 *
 * ⚠ Nothing here pins how [BubbleEnabled.set] writes (`apply()` vs `commit()`).
 * Robolectric lands the XML synchronously under either spelling, so no test
 * that runs in CI can tell them apart — and the force-stop durability concern
 * that motivated asking was measured on hardware and not observed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BubbleEnabledWritersSeamTest {

    @Test
    fun onlyTheOwnerTouchesTheRawPreferenceKey() {
        val files = sourceFiles()
            .filter { f -> TOUCHES_RAW_KEY.containsMatchIn(normalise(f.readText())) }
            .map { it.name }
            .sorted()

        assertEquals(
            "the set of files SPELLING OUT the raw \"bubble_enabled\" " +
                "preference key changed. It must be written as a literal in " +
                "BubbleEnabled.kt and nowhere else: a surface that open-codes " +
                "the pref cannot be made to agree with the others, which is " +
                "exactly how the notification's Hide action and MainActivity's " +
                "switch came to disagree (issue #14). Route the new surface " +
                "through BubbleEnabled instead of adding it here. (This checks " +
                "the LITERAL only — reaching the same key through the owner's " +
                "constants is closed by their visibility, which " +
                "onlyTheOwnerCanNameThePreferenceFileAndKey pins.)",
            listOf(
                // The owner.
                "BubbleEnabled.kt",
                // 🔴 DECLARED, with its own reason: it names `bubble_enabled`
                // as the CONFIGURE broadcast's extra — deliberately the same
                // string, so the operator spells one name for one thing — and
                // it cannot reach the preferences at all.
                // theIngressNamesTheKeyWithoutBeingAbleToReachThePreferences
                // is that claim, asserted rather than asserted-in-a-comment,
                // and every write it makes goes through BubbleEnabled.Control.
                "ClipConfigIngress.kt",
            ).sorted(),
            files,
        )
    }

    /**
     * 🔴 Closes the one route past **both** text ledgers: naming the pref
     * through [BubbleEnabled]'s own constants instead of a literal. See this
     * class's kdoc for why neither scan can see that spelling.
     *
     * Asserted through the JVM modifiers rather than by scanning the source for
     * the word `private`, because the modifier is what the compiler actually
     * enforces and a source scan would be a third text ledger with the same
     * blind spot as the other two. Kotlin's `internal` compiles to a **public**
     * JVM member — a `const val` is not name-mangled — so this does distinguish
     * the two, which is exactly the regression it has to catch.
     */
    @Test
    fun onlyTheOwnerCanNameThePreferenceFileAndKey() {
        for (name in listOf("PREF_FILE", "PREF_KEY")) {
            val field = BubbleEnabled::class.java.declaredFields.firstOrNull { it.name == name }
            assertTrue(
                "BubbleEnabled has no declared field named $name, so this " +
                    "assertion is checking nothing. Either the constant was " +
                    "renamed — which silently resets every existing install to " +
                    "bubble-off, see BubbleEnabled's kdoc — or it stopped being " +
                    "a `const val`. Fix the name here deliberately.",
                field != null,
            )
            assertTrue(
                "BubbleEnabled.$name is reachable from outside BubbleEnabled.kt " +
                    "(JVM modifiers: ${java.lang.reflect.Modifier.toString(field!!.modifiers)}). " +
                    "It must be `private`. While it is `internal` — which is " +
                    "PUBLIC in the bytecode — any file in app/src/main can write " +
                    "the flag as getSharedPreferences(BubbleEnabled.PREF_FILE, " +
                    "..).putBoolean(BubbleEnabled.PREF_KEY, ..), which spells no " +
                    "literal and calls no mutator and so evades BOTH ledgers in " +
                    "this class. Route the new surface through BubbleEnabled.",
                java.lang.reflect.Modifier.isPrivate(field.modifiers),
            )
        }
    }

    @Test
    fun onlyTheDeclaredSurfacesChangeWhetherTheBubbleIsOn() {
        val files = sourceFiles()
            .filter { f -> f.name != "BubbleEnabled.kt" }
            .filter { f -> CHANGES_BUBBLE_STATE.containsMatchIn(normalise(f.readText())) }
            .map { it.name }
            .sorted()

        assertEquals(
            "the set of files that can change whether the bubble is on " +
                "changed. Each of these reaches a mutator, and each owes the " +
                "rule its own event calls for — which is NOT one rule, and " +
                "this message used to state only the first of three:\n" +
                " * a surface the USER reaches (the switch, the tile, Hide " +
                "   bubble, the debug CONFIGURE door) must leave the flag " +
                "   agreeing with whether the service is running. Stopping " +
                "   the bubble without clearing the flag re-creates issue " +
                "   #14; setting the flag without starting the service is its " +
                "   mirror image.\n" +
                " * a RESTORE path (BubbleBootReceiver, on boot and on a " +
                "   package replacement) must start the service and write the " +
                "   flag in NEITHER direction. Writing `false` there is the " +
                "   defect measured on hardware; writing `true` would invent " +
                "   intent the user never expressed.\n" +
                " * a PLATFORM-REFUSAL path (BubbleService, through " +
                "   foregroundPromotionRefused) writes CONDITIONALLY, and the " +
                "   condition is the origin of the start — clear for a start " +
                "   the user asked for, keep for a restore.\n" +
                "Add yours here deliberately, say which of the three it is, " +
                "then write its behavioural test: this scan can see that a " +
                "mutator is called and nothing about what was passed to it.",
            listOf(
                // Restores the bubble after a reboot OR an app upgrade: starts
                // the service, and deliberately does NOT write the flag — the
                // user's stored intent is unchanged by either. 🔴 It used to
                // clear the flag on MY_PACKAGE_REPLACED; that write is the
                // measured defect and is gone.
                "BubbleBootReceiver.kt",
                // 🔴 The debug-only CONFIGURE broadcast's `bubble_enabled`
                // extra, through BubbleEnabled.control. Declared here because
                // the scan was widened to app/src/debug in the same commit: a
                // writer in a source set the ledger cannot see is exactly the
                // hole this ledger exists to close.
                "DebugClipConfigReceiver.kt",
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
                fun e() { BubbleEnabled.startService(ctx, userAsked = false) }
                fun f() { BubbleEnabled.foregroundPromotionRefused(this) }
                fun g() = BubbleEnabled.control(ctx)
            }
        """.trimIndent()
        assertEquals(
            "the pattern must see a call however it is spelled or wrapped — a " +
                "ktfmt reformat must not change the ledger",
            7,
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
     * 🔴 The **second** half of "the surfaces cannot disagree", and the half
     * that was missing while this class's kdoc claimed the whole thing.
     *
     * One owner for the writes does not stop two surfaces *displaying*
     * different answers, and they did. `MainActivity.refreshStatus` rendered
     * its switch from `isOn && overlayGranted`; `DictaTileService.render`
     * open-coded the same conjunction; and that tile's `onClick` branched on
     * bare `isOn`. Three hand-copied readings of one question, which is how the
     * two halves of a single toggle came to disagree whenever the overlay
     * permission was revoked.
     *
     * So this is a **relationship**, not a count: [READS_STORED_INTENT] must
     * appear only in the file that legitimately acts on stored *intent* rather
     * than showing it, and [READS_DISPLAYED_STATE] must be exactly the two
     * surfaces the user sees. It fails when either set **grows or shrinks** —
     * a new surface rendering `isOn` is the regression, and a surface dropping
     * `isUp` means it went back to open-coding the predicate.
     *
     * 🔴 What it can and cannot see: it is a text ledger over this package's
     * sources, so it sees which *predicate* each surface asks. It cannot see
     * whether the answer is then used the right way round — `BubbleEnabled`'s
     * own `theOwnerIsTheSingleSourceOfTruthAcrossSurfaces`,
     * `DictaTileToggleTest` and `BubbleHideFromNotificationTest` are the
     * behavioural half.
     */
    @Test
    fun everyDisplayedBubbleStateComesFromOnePredicate() {
        val intent = sourceFiles()
            .filter { f -> f.name != "BubbleEnabled.kt" }
            .filter { f -> READS_STORED_INTENT.containsMatchIn(normalise(f.readText())) }
            .map { it.name }
            .sorted()

        assertEquals(
            "the set of files reading BubbleEnabled.isOn changed. isOn is " +
                "stored INTENT and deliberately outlives the bubble, so a " +
                "surface that SHOWS it claims a bubble that may not be there — " +
                "issue #14's defect by a new route. Only code that acts on " +
                "intent rather than displaying it may read isOn; anything the " +
                "user sees must read BubbleEnabled.isUp. If you are adding a " +
                "screen, a tile or a notification here, you want isUp.",
            listOf(
                // Acts on stored intent at boot; it displays nothing. It
                // deliberately does NOT use isUp: isUp folds in the overlay
                // permission, and the receiver has its own argued reason to
                // check that separately (see its kdoc).
                "BubbleBootReceiver.kt",
            ),
            intent,
        )

        val displayed = sourceFiles()
            .filter { f -> f.name != "BubbleEnabled.kt" }
            .filter { f -> READS_DISPLAYED_STATE.containsMatchIn(normalise(f.readText())) }
            .map { it.name }
            .sorted()

        assertEquals(
            "the set of files reading BubbleEnabled.isUp changed. These are the " +
                "surfaces the user can SEE, and every one of them must derive " +
                "from the one predicate — including a toggle's click direction, " +
                "not just the state it paints. A surface that disappears from " +
                "this list has gone back to open-coding the conjunction, which " +
                "is what let DictaTileService.onClick and its own render() " +
                "disagree.",
            listOf(
                // The Quick Settings tile: both the state it paints AND the
                // direction its click takes.
                "DictaTileService.kt",
                // The switch on the setup screen.
                "MainActivity.kt",
            ).sorted(),
            displayed,
        )
    }

    /**
     * 🔴 Controls for the ledger above. Without these it is a claim about two
     * regexes — and the risk is concrete here, because `BubbleEnabled.kt`'s own
     * kdoc discusses `isOn` and `isUp` in prose at length, and so do the
     * comments at both call sites.
     */
    @Test
    fun thePredicatePatternsSeeCallsAndNotTheProseAboutThem() {
        val calls = """
            class X {
                fun a() = BubbleEnabled.isOn(this)
                fun b() { if (BubbleEnabled.isOn (context)) {} }
                fun c() = BubbleEnabled.isOn(
                    context,
                )
            }
        """.trimIndent()
        assertEquals(
            "the isOn pattern must see a call however it is spelled or wrapped",
            3,
            READS_STORED_INTENT.findAll(normalise(calls)).count(),
        )
        assertEquals(
            "the isUp pattern fired on isOn calls — the two ledgers would be " +
                "the same ledger and neither would mean anything",
            0,
            READS_DISPLAYED_STATE.findAll(normalise(calls)).count(),
        )

        val upCalls = """
            class Y {
                fun a() = BubbleEnabled.isUp(this)
                fun b() { if (BubbleEnabled.isUp (ctx)) {} }
            }
        """.trimIndent()
        assertEquals(
            "the isUp pattern must see a call however it is spelled",
            2,
            READS_DISPLAYED_STATE.findAll(normalise(upCalls)).count(),
        )
        assertEquals(
            "the isOn pattern fired on an isUp call — isUp CONTAINS the string " +
                "isOn nowhere, but a sloppy pattern like `is(On|Up)` would " +
                "collapse both ledgers into one",
            0,
            READS_STORED_INTENT.findAll(normalise(upCalls)).count(),
        )

        for (sample in listOf(
            "// render() must use BubbleEnabled.isOn(this) <- comment only",
            "/** derived from [BubbleEnabled.isUp] */",
            "private val p = BubbleEnabled::isUp",
        )) {
            assertFalse(
                "a predicate pattern fired on prose, so the ledgers would list " +
                    "files that read nothing: <$sample>",
                READS_STORED_INTENT.containsMatchIn(normalise(sample)) ||
                    READS_DISPLAYED_STATE.containsMatchIn(normalise(sample)),
            )
        }
    }

    /**
     * 🔴 The behavioural half of the invariant on the one path that could still
     * break it with the overlay permission GRANTED.
     *
     * `BubbleService.onCreate` has two `stopSelf()` guards, and they are
     * deliberately asymmetric. The `canDrawOverlays` one leaves the flag alone
     * — a revoked permission is not the user changing their mind, and
     * `BubbleEnabled.isUp` folds the permission in, so nothing lies. The
     * `promoteForeground` one has no such mask: the permission is granted, so
     * `isUp` would report the bubble as up while the service stops itself
     * without ever showing a window. `MainActivity`'s switch would render ON
     * with nothing running and, because a `Switch` fires its listener only on a
     * **change**, one tap would do nothing — issue #14 verbatim.
     *
     * Driven through the real service and the real `foregroundPromoter` seam,
     * not by calling the owner directly, because the claim is about what
     * `onCreate` does.
     *
     * 🔴 **Scope: this is the USER-initiated half only, and the fixture now has
     * to say so.** `BubbleEnabled.foregroundPromotionRefused` clears the flag
     * only for a start the user asked for, so the fixture reaches the flag
     * through `turnOn` — the actual user path — rather than through `set`,
     * which would set the flag without establishing who asked and would make
     * this assertion about the fail-safe default instead of about the user
     * path. The boot half (a refused restore must *keep* the flag) is
     * `BootRestoreTest.aRefusedPromotionKeepsBootIntentButClearsAUserStart`,
     * which drives both directions through the receiver.
     */
    @Test
    fun aRefusedForegroundPromotionLeavesNoSurfaceClaimingABubble() {
        ShadowSettings.setCanDrawOverlays(true)
        val app = RuntimeEnvironment.getApplication()
        assertEquals(
            "the fixture failed: turnOn must report STARTED, or the user path " +
                "this test is about was never taken",
            BubbleEnabled.Start.STARTED,
            BubbleEnabled.turnOn(app),
        )
        assertTrue(
            "the fixture failed: the flag must start ON or there is nothing " +
                "for the refused promotion to leave behind",
            BubbleEnabled.isUp(app),
        )

        val c = org.robolectric.Robolectric.buildService(BubbleService::class.java)
        c.get().foregroundPromoter = { false }
        runCatching { c.create() }

        assertFalse(
            "BubbleService stopped itself because promoteForeground was " +
                "refused and left bubble_enabled set. The overlay permission " +
                "is granted, so nothing masks it: every surface now claims a " +
                "bubble that does not exist, and MainActivity's switch needs " +
                "TWO taps to recover.",
            BubbleEnabled.isUp(app),
        )
        runCatching { c.destroy() }
        OverlayVisibility.clearForTest()
        BubbleSessionBus.clearForTest()
        MicrophoneOwner.clearForTest()
    }

    /**
     * 🔴 The behavioural pin for [BubbleEnabled.isUp]'s **permission** term.
     *
     * Found by a surviving mutant: deleting `canDrawOverlays` from `isUp` left
     * the whole suite green. Nothing was asserting the case, even though it is
     * the most ordinary one — the user has the bubble on, revokes `Display over
     * other apps` from system Settings, and comes back to the app.
     *
     * The flag is still `true` (correctly — a revoked permission is not the
     * user changing their mind, and `BubbleBootReceiver` relies on that), so a
     * switch rendered from the raw flag reads **ON while disabled**: a control
     * that claims a bubble exists and cannot be used to do anything about it.
     * The tile's version is worse, which the sibling case in
     * `DictaTileToggleTest` covers.
     *
     * Asserted together with `isEnabled`, because "off and greyed out" is the
     * state being claimed and a check on either half alone passes for the
     * wrong reason: an always-disabled switch would satisfy the first, and a
     * switch that is enabled-and-off would satisfy the second.
     */
    @Test
    fun revokingTheOverlayPermissionLeavesTheSwitchOffRatherThanLying() {
        val app = RuntimeEnvironment.getApplication()
        ShadowSettings.setCanDrawOverlays(true)
        BubbleEnabled.set(app, true)
        // The permission goes away while the app is not running — nothing gets
        // a chance to reconcile the flag, which is the whole point.
        ShadowSettings.setCanDrawOverlays(false)

        val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java)
            .setup()
        val activity = controller.get()
        val switch = activity.findViewById<android.widget.Switch>(R.id.bubble_switch)

        assertTrue(
            "the fixture failed: bubble_enabled must still be set, or there is " +
                "no stale intent for the switch to be honest about",
            BubbleEnabled.isOn(app),
        )
        assertFalse(
            "the switch reads ON with the overlay permission revoked. The " +
                "bubble cannot exist without that permission, so this is a " +
                "control claiming something that is not there — and it is " +
                "DISABLED, so the user cannot even tap it to find out. It must " +
                "derive from BubbleEnabled.isUp, which folds the permission in, " +
                "not from the raw flag.",
            switch.isChecked,
        )
        assertFalse(
            "the switch is enabled with no overlay permission, so tapping it " +
                "would ask for a bubble the platform will refuse",
            switch.isEnabled,
        )
        controller.pause().stop().destroy()
    }

    /**
     * 🔴 **`src/main` AND `src/debug`.** The scan was widened to the debug
     * source set in the commit that gave the `CONFIGURE` broadcast a
     * `bubble_enabled` extra — because a writer the ledger cannot see makes the
     * ledger narrower than its own description, which is the defect this class
     * keeps finding. `ClipSeamLedgerTest` widened for the same reason and in
     * the same shape when the debug receiver learned to read the capture queue.
     *
     * The debug set is small and has exactly one file, so
     * [theScanReachesBothSourceSets] pins that both directories were really
     * read: a `firstOrNull` over candidate paths that silently found only one
     * of them would report a comfortable, wrong ledger.
     */
    private fun sourceFiles(): List<File> = sourceDirs().flatMap { dir ->
        dir.listFiles { f -> f.name.endsWith(".kt") }.orEmpty().toList()
    }.also { files ->
        assertTrue(
            "the source directories held too few Kotlin files, so the ledgers " +
                "would be comparing an empty list against an empty list",
            files.size > 10,
        )
    }

    private fun sourceDirs(): List<File> = listOf("main", "debug").map { set ->
        val dir = listOf(
            File("app/src/$set/java/dev/zacx/dicta"),
            File("src/$set/java/dev/zacx/dicta"),
            File("../app/src/$set/java/dev/zacx/dicta"),
        ).firstOrNull { it.isDirectory }
        assertTrue(
            "could not locate the src/$set source directory from " +
                "${File(".").absolutePath} — refusing to report a pass on " +
                "files that were never read",
            dir != null,
        )
        dir!!
    }

    /**
     * 🔴 The control for the widening above: both source sets really are in
     * the scan.
     *
     * Without it, every ledger in this class would still pass if the debug
     * directory had been missed — a `src/debug` writer would simply be absent
     * from a list that is asserted to be complete, which is the quiet direction.
     */
    @Test
    fun theScanReachesBothSourceSets() {
        val names = sourceFiles().map { it.name }
        assertTrue(
            "the scan did not read app/src/main — BubbleEnabled.kt is missing " +
                "from it, so every ledger here is about the wrong tree",
            "BubbleEnabled.kt" in names,
        )
        assertTrue(
            "the scan did not read app/src/debug — DebugClipConfigReceiver.kt " +
                "is missing from it, so a declared writer in that source set " +
                "would be invisible to the ledgers below",
            "DebugClipConfigReceiver.kt" in names,
        )
    }

    /**
     * 🔴 [ClipConfigIngress] spells the raw key and that is **declared** in
     * [onlyTheOwnerTouchesTheRawPreferenceKey]; this is the reason it is safe.
     *
     * It names `bubble_enabled` because that is the name of the broadcast
     * extra, deliberately the same string so the operator spells one name. The
     * claim being made is that it names the key and **cannot reach the store**:
     * the owner's `PREF_FILE` / `PREF_KEY` are `private`
     * ([onlyTheOwnerCanNameThePreferenceFileAndKey]), so the only way for that
     * file to write the preference would be to open it itself — which this
     * asserts it does not.
     */
    @Test
    fun theIngressNamesTheKeyWithoutBeingAbleToReachThePreferences() {
        val ingress = sourceFiles().single { it.name == "ClipConfigIngress.kt" }
        val code = normalise(ingress.readText())

        assertTrue(
            "the fixture failed: ClipConfigIngress.kt no longer spells the raw " +
                "key, so this test is about nothing — take it off the declared " +
                "list in onlyTheOwnerTouchesTheRawPreferenceKey instead",
            TOUCHES_RAW_KEY.containsMatchIn(code),
        )
        assertFalse(
            "ClipConfigIngress.kt opens SharedPreferences. It is on the " +
                "raw-key ledger's declared list on the strength of NOT doing " +
                "that: it names the key as a broadcast extra and routes every " +
                "write through BubbleEnabled.Control. A file that both spells " +
                "the key and opens the store is a second writer the owner " +
                "cannot see.",
            Regex("""getSharedPreferences\s*\(""").containsMatchIn(code) ||
                Regex("""SharedPreferences""").containsMatchIn(code),
        )
        // Positive control: the pattern can see such a call, so the absence
        // above is not the absence of a working regex.
        assertTrue(
            Regex("""getSharedPreferences\s*\(""").containsMatchIn(
                normalise("""val p = context.getSharedPreferences("dicta", 0)""")
            ),
        )
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
         * `foregroundPromotionRefused` is a member for the same reason, and it
         * is the one that would otherwise have been missed: it writes the flag
         * *conditionally*, so a file that called it would change whether the
         * bubble is on without spelling `set`, `turnOn` or `turnOff` anywhere.
         * A mutator the ledger cannot see makes the ledger narrower than its
         * own description, which is the defect this class keeps finding.
         *
         * `control` is a member although it writes nothing itself: it hands out
         * a [BubbleEnabled.Control], i.e. the ability to turn the bubble on and
         * off, so a file that calls it is a writer by any reading that matters.
         * A factory for a mutator that the mutator ledger could not see would
         * be the same hole one indirection out.
         *
         * `isOn` is deliberately excluded: a reader cannot put the flag out of
         * step with reality, and including it would make every file that merely
         * *consults* the bubble a member.
         */
        val CHANGES_BUBBLE_STATE = Regex(
            """BubbleEnabled\.""" +
                """(set|turnOn|turnOff|startService|foregroundPromotionRefused|control)""" +
                """\s*\("""
        )

        /** The literal preference key, as a Kotlin string. */
        val TOUCHES_RAW_KEY = Regex(""""bubble_enabled"""")

        /**
         * A read of **stored intent**. Spelled out separately from
         * [READS_DISPLAYED_STATE] rather than as one `is(On|Up)` alternation on
         * purpose: the whole point of
         * [everyDisplayedBubbleStateComesFromOnePredicate] is that these two
         * reads mean different things, so one pattern matching both would make
         * the ledger unable to state its own claim.
         */
        val READS_STORED_INTENT = Regex("""BubbleEnabled\.isOn\s*\(""")

        /** A read of **what the user is shown**. */
        val READS_DISPLAYED_STATE = Regex("""BubbleEnabled\.isUp\s*\(""")

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
