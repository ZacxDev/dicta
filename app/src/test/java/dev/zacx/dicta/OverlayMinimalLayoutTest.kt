package dev.zacx.dicta

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ContextThemeWrapper
import android.widget.ImageButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Guards for the minimal dictation overlay.
 *
 * ## 🔴 THE OVERFLOW IS GONE — and that is what these guards now pin
 *
 * The design pass hid the four actions (Copy / Screenshot / Send / Close)
 * behind an `@id/more_button` overflow, and most of this file existed to prove
 * the routes back to them were real. The operator's instruction — *"remove the
 * button to toggle action buttons, just always show the action buttons"* —
 * retires that whole mechanism, so the guards are REPLACED rather than deleted,
 * each by the assertion of the inverse contract:
 *
 *  * `tappingMoreRevealsTheActions`, `typingRevealsTheActions` and
 *    `whitespaceAloneDoesNotRevealTheActions` drove the two reveal routes.
 *    There is nothing to reveal any more, and the property that replaces all
 *    three is stronger and cheaper to hold:
 *    [theActionsAreOnScreenInEveryTranscriptState] — the row is visible
 *    whatever the transcript says, so no state can hide it.
 *  * `theOverflowStateIsAnnouncedInWordsNotOnlyInColour` pinned the toggle's
 *    announced state. A control that does not exist announces nothing; the
 *    ledger below ([iconOnlyControls]) is what now fails if it comes back
 *    unlabelled.
 *  * `theLayoutDefaultForTheActionRowIsHidden` becomes
 *    [theLayoutDefaultForTheActionRowIsVisible] — same reasoning in reverse,
 *    and still separate from the running-activity assertion, because the XML
 *    default and the runtime state are two independent things that have to
 *    hold.
 *
 * ## The reserved slots, and what happened to them
 *
 * This file originally pinned three RESERVATIONS the design pass left for
 * in-flight remote-STT work: `settings_slot` empty and GONE, and `level_meter` /
 * `engine_badge` as GONE, unbound stubs. Those assertions were correct as
 * reservations and became wrong the moment the slots were filled, so the merge
 * of that branch RETIRED them — and replaced them, rather than deleting them:
 *
 *  * `theReservedSettingsSlotExistsAndIsInert` (slot has 0 children) became
 *    [theSettingsSlotIsFilledWithAControlThatOpensSettings], which asserts the
 *    inverse AND the thing the old guard existed to protect: that the control
 *    in the slot actually opens something.
 *  * `theReservedMeterAndEngineSlotsExistAndAreInert` split in two. Its shape
 *    half survives verbatim as
 *    [theStatusStripHoldsTheMeterTheStatusAndTheBadgeInThatOrder]; its
 *    inertness half became [theMeterAndTheBadgeSaySilentUntilADictationDrivesThem]
 *    (the at-rest default, now justified by "nothing true to say yet" rather
 *    than "unbound") plus the new
 *    [aDictationBindsTheMeterAndNamesTheEngine], which drives the real
 *    callbacks and is the half with teeth — "present and GONE" passes just as
 *    well when nothing will ever show them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverlayMinimalLayoutTest {

    /**
     * Every icon-only control in the overlay, in one ledger.
     *
     * This is a RELATIONSHIP, not a list of spot checks:
     * [everyIconOnlyControlIsLedgeredAndLabelled] enumerates the actual
     * `ImageButton`s in the inflated tree and asserts the set is exactly this,
     * so the guard fails when the set GROWS (a new icon control escaping
     * contentDescription/tooltipText coverage) *or* SHRINKS (a control removed
     * while something still addresses it).
     *
     * 🔴 `more_button` was in here and is deliberately NOT any more — the
     * overflow was deleted with the toggle. The set is now exactly
     * `DictateActionViewTypesTest.iconActions`, i.e. the membership of
     * `action_row`, because the row is the only icon-only chrome left on the
     * card. Re-adding an overflow is a SHRINK-then-GROW this ledger reports.
     */
    private val iconOnlyControls = listOf(
        R.id.copy_button,
        R.id.screenshot_button,
        R.id.share_button,
        R.id.close_button,
    )

    private fun inflate(): View {
        val ctx = ContextThemeWrapper(
            RuntimeEnvironment.getApplication(), R.style.Theme_Dicta_Overlay
        )
        return LayoutInflater.from(ctx).inflate(R.layout.activity_dictate, null)
    }

    // --- the resting state --------------------------------------------------

    /**
     * The resting card: the transcript and the actions, with nothing to tap
     * first.
     *
     * REPLACES `atRestOnlyTheTranscriptAndTheMicAreVisible`, which asserted
     * `action_row` GONE here. That was the overflow design's central claim and
     * is now the defect: the operator asked for the actions to be on screen.
     *
     * ⚠ `mic_button` MOVED SIDES in this list. It used to be asserted VISIBLE
     * at rest, where it read "Speak" — a control that starts a recording, on
     * the card that exists to show the one just finished. The operator asked
     * for it to go, so at rest there is no mic control at all and the only
     * states that reveal one are STOP and the mic-denied fallback. The full
     * state table is `OverlayNeverStartsARecordingTest`.
     */
    @Test
    fun atRestTheTranscriptAndTheActionsAreVisibleAndTheMicIsNot() {
        val controller = Robolectric.buildActivity(DictateActivity::class.java).setup()
        try {
            val a = controller.get()

            for (id in listOf(R.id.dictated_text, R.id.action_row)) {
                assertEquals(
                    "${nameOf(id)} must be visible at rest — the card is the " +
                        "transcript and the actions",
                    View.VISIBLE,
                    a.findViewById<View>(id).visibility
                )
            }

            assertEquals(
                "mic_button is on screen at rest. There is no recording to " +
                    "stop in this state, so the only thing it can offer is a " +
                    "START — which the overlay must never present.",
                View.GONE,
                a.findViewById<View>(R.id.mic_button).visibility
            )

            // The old always-on chrome that the design pass removed, and which
            // the overflow's removal does not bring back.
            assertEquals(
                "attachment_note must stay hidden until a capture attaches one",
                View.GONE,
                a.findViewById<View>(R.id.attachment_note).visibility
            )
        } finally {
            controller.close()
        }
    }

    /**
     * The LAYOUT's own default for the action row, asserted separately from the
     * running activity's state above — and the separation is a measured need,
     * not symmetry for its own sake.
     *
     * While the overflow existed, `wireOnDemandActions()` forced the row's
     * visibility on create whatever the XML said, so a mutation to the XML
     * default SURVIVED the running-activity assertion (observed in that
     * change's mutation sweep). The wiring is gone and the XML is now the only
     * thing deciding, but the two assertions are kept apart anyway: any future
     * code that sets `action_row`'s visibility would re-open exactly that gap,
     * and this one would still be the half that sees it.
     */
    @Test
    fun theLayoutDefaultForTheActionRowIsVisible() {
        assertEquals(
            "activity_dictate.xml does not declare action_row visible. The " +
                "actions are unconditional now — there is no overflow left to " +
                "reveal them, so a hidden default means Copy / Screenshot / " +
                "Send / Close are simply unreachable.",
            View.VISIBLE,
            inflate().findViewById<View>(R.id.action_row).visibility
        )
    }

    // --- the actions are unconditional --------------------------------------

    /**
     * REPLACES the three reveal-route tests. The operator asked for the
     * actions to be always on screen, so the property is "no transcript state
     * hides them" — which is both wider than the old routes and the thing a
     * re-introduced reveal heuristic would break.
     *
     * The invocability half is carried over verbatim from
     * `tappingMoreRevealsTheActions`: a visible row wired to nothing is the
     * same defect as an unreachable one.
     */
    @Test
    fun theActionsAreOnScreenInEveryTranscriptState() {
        val controller = Robolectric.buildActivity(DictateActivity::class.java).setup()
        try {
            val a = controller.get()
            val row = a.findViewById<View>(R.id.action_row)
            val text = a.findViewById<android.widget.EditText>(R.id.dictated_text)

            // Empty, whitespace-only, real text, and emptied again — the four
            // states the old TextWatcher distinguished between.
            for (state in listOf("", "   ", "hello from the recogniser", "")) {
                text.setText(state)
                assertEquals(
                    "action_row is hidden with the transcript set to " +
                        "\"$state\" — something is still deciding when the " +
                        "actions are allowed on screen",
                    View.VISIBLE,
                    row.visibility
                )
            }

            for (id in listOf(
                R.id.copy_button, R.id.screenshot_button,
                R.id.share_button, R.id.close_button
            )) {
                val v = a.findViewById<View>(id)
                assertEquals(
                    "${nameOf(id)} is not visible inside the action row",
                    View.VISIBLE, v.visibility
                )
                assertTrue("${nameOf(id)} has no click listener", v.hasOnClickListeners())
            }
        } finally {
            controller.close()
        }
    }

    /**
     * The overflow is GONE, not merely hidden — asserted against the inflated
     * tree so it cannot come back as a 0x0 or INVISIBLE leftover that still
     * takes a touch slot.
     *
     * [everyIconOnlyControlIsLedgeredAndLabelled] would also fail if it
     * returned, but it would fail with "the ImageButton set changed", which
     * reads as a ledger that needs updating. This one says what is wrong.
     */
    @Test
    fun thereIsNoOverflowToggleLeftOnTheCard() {
        val root = inflate()
        val byName = RuntimeEnvironment.getApplication().resources.getIdentifier(
            "more_button", "id", RuntimeEnvironment.getApplication().packageName
        )
        assertTrue(
            "an @id/more_button is still declared. The actions are " +
                "unconditional now, so a toggle for them either does nothing " +
                "or can hide controls the operator asked to always see.",
            byName == 0 || root.findViewById<View>(byName) == null
        )
    }

    // --- accessibility ------------------------------------------------------

    /**
     * Ledger + label guard in one. The enumeration side is what makes it a
     * relationship: a sixth `ImageButton` added to this layout fails here until
     * someone adds it to [iconOnlyControls], at which point the label assertions
     * apply to it automatically.
     */
    @Test
    fun everyIconOnlyControlIsLedgeredAndLabelled() {
        val root = inflate()

        val found = mutableSetOf<Int>()
        collectImageButtons(root, found)
        assertEquals(
            "the set of ImageButtons in activity_dictate.xml changed; update " +
                "iconOnlyControls so the new control is covered by the " +
                "contentDescription/tooltipText assertions below",
            iconOnlyControls.toSet(),
            found
        )

        for (id in iconOnlyControls) {
            val v = root.findViewById<ImageButton>(id)
            assertNotNull("no ImageButton with id ${nameOf(id)}", v)
            assertTrue(
                "${nameOf(id)} has no contentDescription — it is icon-only, so " +
                    "a screen reader would announce it as an unlabelled button",
                !v.contentDescription.isNullOrBlank()
            )
            assertTrue(
                "${nameOf(id)} has no tooltipText — it is icon-only, and for a " +
                    "control that is sometimes not even on screen the long-press " +
                    "label is the only sighted discovery route",
                !v.tooltipText.isNullOrBlank()
            )
        }
    }

    /**
     * Touch targets. 48dp is the floor; the ledgered controls are declared at
     * exactly 48dp high, and the mic control at 56dp.
     */
    @Test
    @Config(qualifiers = "w411dp-h914dp-port-xxhdpi")
    fun everyControlMeetsTheMinimumTouchTarget() {
        val controller = Robolectric.buildActivity(DictateActivity::class.java).setup()
        try {
            val a = controller.get()
            val minPx = (48 * a.resources.displayMetrics.density).toInt()
            // more_button is square; the row actions are 0dp-weighted in width
            // and only their height is declared here.
            for (id in iconOnlyControls + R.id.mic_button) {
                val v = a.findViewById<View>(id)
                val h = v.layoutParams.height
                assertTrue(
                    "${nameOf(id)} declares a height of $h px, under the " +
                        "$minPx px (48dp) minimum touch target",
                    h >= minPx
                )
            }
        } finally {
            controller.close()
        }
    }

    // --- the reserved slots -------------------------------------------------

    /**
     * The status strip's SHAPE: membership and order.
     *
     * CHANGED BY ISSUE #9. `status_text` used to be the middle child here,
     * weighted and capped at one ellipsized line. It now lives BELOW the strip
     * at full card width (see [theStatusLineSpansTheFullCardWidth] and
     * [noStatusStringOutgrowsItsLineBudget] — this used to name a
     * `theLongestStatusStringIsNotTruncated` that exists nowhere, the test
     * the DEAD END kdoc below explains could never be written), and a
     * spacer took over the job of holding the badge at the trailing edge.
     *
     * Still an invariant guard, not regression coverage: asserting the exact
     * list makes this a RELATIONSHIP, failing when the strip grows a child,
     * loses one, or reorders.
     */
    @Test
    fun theStatusStripHoldsTheMeterTheSpacerAndTheBadgeInThatOrder() {
        val root = inflate()
        val strip = root.findViewById<ViewGroup>(R.id.overlay_status_strip)
        assertNotNull("overlay_status_strip is gone — the strip has no home", strip)

        val children = (0 until strip.childCount).map { strip.getChildAt(it).id }
        assertEquals(
            "the status strip's membership or order changed; the meter is " +
                "placed first and the badge last on purpose",
            listOf(R.id.level_meter, R.id.status_strip_spacer, R.id.engine_badge),
            children
        )

        // status_text keeps carrying the live status (and is where a
        // "transcribing…" message goes), so it must stay visible — and it must
        // NOT be back inside the strip, which is what capped it at #9's width.
        assertEquals(
            View.VISIBLE,
            root.findViewById<View>(R.id.status_text).visibility
        )
        assertFalse(
            "status_text is a child of the status strip again — that is the " +
                "layout that truncated it at ~30 characters (issue #9)",
            hasAncestorWithId(
                root.findViewById<View>(R.id.status_text),
                R.id.overlay_status_strip,
            ),
        )
    }

    /**
     * Issue #9, as regression coverage rather than an invariant guard.
     *
     * ## What this measures, and what it deliberately does not
     *
     * It takes the **longest string the status line actually carries** —
     * discovered from the resources, not hardcoded, so new copy is covered
     * automatically — renders it into the real inflated `status_text` at the
     * real card width, and requires the `Layout` to report **zero ellipsised
     * characters**.
     *
     * 🔴 The pixel numbers are Robolectric's text metrics, not the S21's, so
     * this does not prove the exact on-device wrap. What it DOES pin is the
     * property that broke: a `status_text` constrained such that the longest
     * real string does not fit is red here, and the pre-#9 layout (one
     * ellipsized line inside the strip) fails it. The device check is a
     * screenshot, recorded in the PR.
     */
    /**
     * 🔴 DEAD END, RECORDED SO IT IS NOT RE-DERIVED.
     *
     * The obvious test here is "render the longest status string and assert it
     * is not truncated". It **cannot be written on the JVM**, and the way it
     * fails is silent:
     *
     *  * `Layout.getEllipsisCount` under Robolectric returns **0 for a
     *    4000-character string** in a one-line view. It does not model
     *    ellipsising at all.
     *  * `TextPaint.measureText` returns **exactly 1.0px per character**
     *    regardless of `textSize` (textSize 12.0, density 1.0). So any real
     *    status string "fits" in one line of the ~953px card — every one of
     *    them is a few hundred pixels under that bound at 1px per character —
     *    and a `maxLines="1"` regression measures as fine.
     *
     * Both versions were written, and both passed a mutation that put the old
     * broken layout back. They were only caught because the mutation battery
     * ran — a positive control using an absurd 4000-character string passed
     * while the assertion against a real status string stayed vacuous, since
     * 4000px DOES exceed the width and a real string's length in px does not.
     *
     * ⚠ This passage used to reason from a specific figure — "measured: 154
     * chars -> 154.0px" and "the real 154-character assertion". No status
     * string is or ever was 154 characters (see [REQUIRED_STATUS_LINES]), so
     * that datum is **withdrawn rather than corrected**. The argument does
     * not need it and is not re-propped with a replacement number: it turns
     * only on 1px-per-character against a ~953px card, which holds for every
     * real status string by a wide margin and would hold for any plausible
     * future one.
     *
     * So the wrap itself is a **device** question, confirmed by screenshot on
     * the PR. What is pinned here instead is the configuration that caused #9,
     * asserted as state rather than as rendered pixels: the view's real
     * measured width, and its real line budget. Both mutations die against
     * these two tests.
     */
    @Test
    fun theStatusLineSpansTheFullCardWidth() {
        val root = inflate()
        val widthPx = 993
        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        root.layout(0, 0, widthPx, root.measuredHeight)

        val card = root.findViewById<View>(R.id.dictate_card)
        val status = root.findViewById<android.widget.TextView>(R.id.status_text)
        val cardInner = card.width - card.paddingLeft - card.paddingRight

        assertTrue(
            "nothing was laid out (card inner width ${cardInner}px), so this " +
                "test would pass without measuring anything",
            cardInner > 0,
        )
        assertEquals(
            "status_text is ${status.width}px wide inside a ${cardInner}px " +
                "card. It must span the FULL card width — issue #9 was this " +
                "view being a weighted child of the status strip, left with " +
                "555px of an 801px row (~30 characters) for status copy " +
                "several times that long.",
            cardInner,
            status.width,
        )
    }

    @Test
    fun theStatusLineAllowsEnoughLinesForTheLongestString() {
        val status = inflate().findViewById<android.widget.TextView>(R.id.status_text)
        val longest = longestStatusString()

        assertTrue(
            "status_text allows ${status.maxLines} line(s). At full card width " +
                "and 12sp this device fits roughly $CHARS_PER_STATUS_LINE " +
                "characters per line, and " +
                "the longest string it must carry is ${longest.length} " +
                "characters, so it needs at least $REQUIRED_STATUS_LINES. " +
                "Issue #9 was this view capped at ONE ellipsized line. " +
                "String was: \"$longest\"",
            status.maxLines >= REQUIRED_STATUS_LINES,
        )
    }

    /**
     * A copy budget, not a restatement of the line count.
     *
     * #9 is ultimately "the words outgrew the box". Pinning only the box would
     * leave the other half unguarded: a future 300-character status string
     * overflows four lines just as surely, and nothing else would notice.
     * [CHARS_PER_STATUS_LINE] is a DEVICE measurement (1080px-wide card, 12sp,
     * `@color/dicta_on_surface_variant`), not a Robolectric one — see the dead
     * end above for why the JVM cannot supply it.
     */
    @Test
    fun noStatusStringOutgrowsItsLineBudget() {
        val longest = longestStatusString()
        val budget = REQUIRED_STATUS_LINES * CHARS_PER_STATUS_LINE
        assertTrue(
            "the longest status string is ${longest.length} characters but the " +
                "status line holds about $budget ($REQUIRED_STATUS_LINES lines " +
                "x ~$CHARS_PER_STATUS_LINE chars). Give the view another line " +
                "rather than cutting the sentence — the card is a ScrollView " +
                "and can afford the height. String was: \"$longest\"",
            longest.length <= budget,
        )
    }

    /**
     * The longest string `status_text` can be asked to display.
     *
     * 🔴 **Derived, not listed** — and it previously only claimed to be. The
     * old version hardcoded five resource names under this same kdoc, and
     * asserted `names.size == values.size`, which notices a **removed** string
     * and is blind to an **added** one. Two status strings were then added
     * (`stt_remote_truncated`, `stt_remote_unreliable`) and the guard measured
     * neither: it kept reporting the old maximum while silently no longer
     * covering the set it describes. The sentence was true of the intent and
     * false of the code, which is the failure mode a reader cannot see.
     *
     * So the set is now actually derived: every `R.string.*` referenced by
     * the classes in [STATUS_WRITERS], resolved against the real resources.
     * `onStatus` forwards an already-localised string, so there is no single
     * `getString` call site to enumerate — the reference set in those files is
     * the honest over-approximation, and over-approximating is the safe
     * direction for a "longest" bound.
     *
     * [theStatusStringLedgerIsDerivedAndNotAStaleList] is its positive
     * control: a derivation that quietly resolved to nothing would otherwise
     * report a comfortable zero.
     */
    private fun longestStatusString(): String =
        statusStringValues().maxBy { it.length }

    /** Every candidate status string, `%1$s` filled, as a user would see it. */
    private fun statusStringValues(): List<String> {
        val ctx = RuntimeEnvironment.getApplication()
        return statusStringNames().mapNotNull { name ->
            val id = ctx.resources.getIdentifier(name, "string", ctx.packageName)
            if (id == 0) {
                null
            } else {
                // stt_fell_back_retrying and friends carry a %1$s; fill it with
                // a realistic reason so the measured length is the one a user
                // actually sees.
                ctx.resources.getString(id).replace("%1\$s", "the service timed out")
            }
        }
    }

    /**
     * The resource names referenced by the classes that write `status_text`.
     *
     * 🔴 **[STATUS_WRITERS] is the ledger, and it was wrong by one.** The
     * first version of this derivation listed three classes and its kdoc
     * called them "the three classes that write `status_text`".
     * [TranscriptionCoordinator] is a fourth: it calls `events.onStatus(...)`,
     * `RemoteTranscriber` forwards that verbatim, and `DictateActivity`
     * assigns it to `statusText.text`. Demonstrated rather than argued — the
     * same overlong string referenced from the coordinator left the suite
     * fully green while the byte-identical one referenced from
     * `DictateActivity` was caught. It uses bare literals today, so there was
     * no live defect; the moment one is localised the guard could not see it.
     * That is precisely the blindness this derivation exists to close, so
     * being blind to a whole writer was the same bug one level up.
     *
     * `BubbleService` stays excluded, and the reason is narrower than "it is
     * not a writer": it **forwards** status text it did not author (already
     * covered through the classes above), and the strings it references
     * itself go to Toasts — `stt_max_duration` via `toast(...)`. Pulling them
     * in would let an unrelated copy edit fail a dictation-layout guard.
     */
    private fun statusStringNames(): Set<String> =
        STATUS_WRITERS
            .flatMap { cls ->
                RESOURCE_REFERENCE
                    .findAll(readSource("app/src/main/java/dev/zacx/dicta/$cls.kt", cls))
                    .map { it.groupValues[1] }
            }
            .toSet()

    private fun readSource(path: String, what: String): String {
        val found = listOf(path, path.removePrefix("app/"), "../$path")
            .map { java.io.File(it) }
            .firstOrNull { it.exists() }
        assertTrue(
            "could not locate $what from ${java.io.File(".").absolutePath} — " +
                "refusing to report a pass on a file that was never read",
            found != null,
        )
        return found!!.readText()
    }

    /**
     * 🔴 Every class that **calls** `onStatus` must be classified — in
     * [STATUS_WRITERS] or in the exclusion below — so a new status writer is
     * a red test rather than a silent gap.
     *
     * The derivation above is only as wide as its list of writers, so a list
     * maintained by hand reproduces, one level up, the blindness the
     * derivation was built to remove. That is exactly what happened:
     * `TranscriptionCoordinator` calls `onStatus`, was absent from the list,
     * and a mutant proved the suite could not see an overlong string
     * introduced there.
     *
     * 🔴 **The first version of this guard was line-formatting dependent, in
     * both directions, and an audit demonstrated both.** It filtered per
     * physical line with `containsMatchIn(line) && !line.contains("fun
     * onStatus")`, so:
     *
     *  * a one-line **expression-bodied forwarder** — Kotlin's ordinary
     *    idiom, `override fun onStatus(t: String) = down.onStatus(...)` —
     *    declares and calls on one line, was dropped as a declaration, and a
     *    fifth writer in that shape **survived with the suite fully green**;
     *  * conversely `BubbleSessionBus`'s real forwarder escaped **only**
     *    because it is one line, so a pure whitespace reformat of it onto two
     *    (the shape `onClientSilenced` already uses ten lines below) turned
     *    this test **spuriously red**. A gate that goes red on cosmetic
     *    reformatting is the gate people learn to click through.
     *
     * So the match is now **declaration-aware rather than line-aware**: the
     * file is whitespace-normalised to a single line, `fun onStatus(...)`
     * declaration headers are deleted, and whatever `onStatus(` remains is a
     * call. Formatting cannot change the answer in either direction.
     *
     * Scope, stated rather than overclaimed. This finds **callers**;
     * `DictateActivity` is the *sink* (it declares the override and assigns
     * `statusText.text`) and calls `onStatus` nowhere, so it is in
     * [STATUS_WRITERS] for its resource references, not because it is found
     * here. A class that authored a status string and handed it to another
     * class to deliver would still be missed — none does today, and the
     * resource-reference derivation would have to miss it too.
     */
    @Test
    fun everyClassThatCallsOnStatusIsClassified() {
        val excluded = mapOf(
            // Forwards text authored elsewhere; its own strings are Toasts
            // (stt_max_duration via toast(...)).
            "BubbleService" to "forwards; own strings are Toasts",
            // A pure fan-out bus: `deliver { it.onStatus(text) }` passes on
            // the identical string and references no R.string at all.
            "BubbleSessionBus" to "pure fan-out; references no string resource",
        )
        val sourceDir = listOf(
            java.io.File("app/src/main/java/dev/zacx/dicta"),
            java.io.File("src/main/java/dev/zacx/dicta"),
            java.io.File("../app/src/main/java/dev/zacx/dicta"),
        ).firstOrNull { it.isDirectory }
        assertTrue(
            "could not locate the main source directory from " +
                "${java.io.File(".").absolutePath} — refusing to report a " +
                "pass on a scan that read nothing",
            sourceDir != null,
        )

        // walkTopDown, not listFiles: the package is flat today, and a writer
        // added in a future sub-package would otherwise be silently unscanned.
        val callers = sourceDir!!.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { callsOnStatus(it.readText()) }
            .map { it.nameWithoutExtension }
            .toSet()

        // Positive control: a scan wired to nothing classifies perfectly.
        assertTrue(
            "the onStatus scan found ${callers.size} callers (${callers.sorted()}), " +
                "which cannot be right — it is not reading the sources it " +
                "thinks it is",
            callers.containsAll(
                listOf("RemoteTranscriber", "TranscriptionCoordinator", "BubbleSessionBus")
            ),
        )

        val unclassified = callers - STATUS_WRITERS.toSet() - excluded.keys
        assertEquals(
            "these classes hand a status string into the callback chain and " +
                "are in neither STATUS_WRITERS nor the exclusion list, so the " +
                "longest-status guard cannot see any string they introduce: " +
                "$unclassified. Add each to STATUS_WRITERS, or to the " +
                "exclusion with a reason that says where its text goes.",
            emptySet<String>(),
            unclassified,
        )
    }

    /**
     * True iff [source] **calls** `onStatus`, as opposed to only declaring or
     * overriding it.
     *
     * Whitespace-normalised first so the answer cannot depend on where the
     * line breaks fall, then declaration headers are removed so that an
     * expression-bodied forwarder — which declares and calls in one
     * expression — is still seen as the call it is.
     */
    private fun callsOnStatus(source: String): Boolean {
        val normalised = source.replace(WHITESPACE_RUN, " ")
        return ON_STATUS_CALL.containsMatchIn(
            ON_STATUS_DECLARATION.replace(normalised, " ")
        )
    }

    /**
     * 🔴 The control for the resource derivation, and the growth guard the
     * old hardcoded list could not be.
     *
     * Asserts the derived set still contains every name the previous version
     * listed by hand — so a derivation that breaks, or silently resolves to a
     * handful of button labels, goes red rather than reporting a comfortably
     * short "longest" — **and** that it contains the status strings added
     * since, which is the case the `names.size == values.size` form was blind
     * to. A new status string is picked up with no edit here; one the
     * derivation cannot see fails this.
     */
    @Test
    fun theStatusStringLedgerIsDerivedAndNotAStaleList() {
        val names = statusStringNames()
        val mustContain = listOf(
            // The five the hand-written ledger used to list.
            "dictate_hint",
            "stt_tier_remote_only",
            "stt_fell_back_retrying",
            "stt_not_configured",
            "mic_denied_fallback",
            // The ones it was blind to, which is why this test exists.
            "stt_remote_done",
            "stt_remote_truncated",
            "stt_remote_unreliable",
        )
        for (name in mustContain) {
            assertTrue(
                "the derived status-string set does not contain <$name>, so " +
                    "the length bound is being measured over the wrong set. " +
                    "Derived ${names.size} names: ${names.sorted()}",
                names.contains(name),
            )
        }
        // A derivation wired to nothing would satisfy a `maxBy` on an empty
        // list by throwing, not by passing — but it would satisfy a `<=`
        // bound trivially. Pin that it resolved real values.
        val values = statusStringValues()
        assertTrue(
            "the derived names resolved to ${values.size} actual strings — " +
                "the resource lookup is not finding them",
            values.size >= mustContain.size,
        )
    }

    private companion object {
        /**
         * Lines `status_text` must allow. Four, because the card fits roughly
         * [CHARS_PER_STATUS_LINE] characters per line and four of those covers
         * the longest string the view carries.
         *
         * ⚠ This kdoc used to assert that longest **is 154 characters**. It
         * is not, and was not — no status string has ever been that length.
         * The figure was a stale restatement of a measurement, and three more
         * copies of it (and of a companion "145") were still standing in
         * `activity_dictate.xml` and in this file when the first retraction
         * only edited this one sentence; a retraction is a tree-wide sweep.
         *
         * 🔴 No corrected figure replaces them here, deliberately — the first
         * version of this retraction quoted today's measurement, which is the
         * same mistake one revision later. `longestStatusString()` measures
         * it, `noStatusStringOutgrowsItsLineBudget` asserts it fits the
         * budget, and the failure message prints the actual string and its
         * actual length at the moment it matters. Prose gets no number.
         */
        const val REQUIRED_STATUS_LINES = 4

        /**
         * Characters per line at full card width, 12sp, on the 1080px target
         * device. A DEVICE measurement — Robolectric's `measureText` reports a
         * flat 1.0px per character and cannot supply this (see the dead-end
         * note above). Deliberately conservative: understating it makes the
         * copy budget stricter, never looser.
         */
        const val CHARS_PER_STATUS_LINE = 48

        /**
         * `R.string.<name>` as it appears in source. Used to DERIVE the set of
         * strings `status_text` can carry, instead of restating it by hand.
         */
        val RESOURCE_REFERENCE = Regex("""R\.string\.([a-z0-9_]+)""")

        /** Any run of whitespace, so a scan cannot depend on line breaks. */
        val WHITESPACE_RUN = Regex("""\s+""")

        /**
         * A `fun onStatus(...)` **declaration header**, with any modifiers.
         *
         * Deleted before the call scan so that an expression-bodied forwarder
         * (`override fun onStatus(t: String) = down.onStatus(t)`) is still
         * recognised as the call it contains. Matched against
         * whitespace-normalised text, so a declaration split across lines
         * collapses to this same shape.
         */
        val ON_STATUS_DECLARATION = Regex(
            """(?:\b(?:override|private|internal|public|protected|open|abstract|final)\s+)*""" +
                """fun\s+onStatus\s*\([^)]*\)"""
        )

        /**
         * An `onStatus(` call, matched only **after**
         * [ON_STATUS_DECLARATION] has been removed — at which point any
         * remaining occurrence is a call rather than a declaration.
         */
        val ON_STATUS_CALL = Regex("""\bonStatus\s*\(""")

        /**
         * The classes whose `R.string.*` references can reach `status_text`.
         *
         * `TranscriptionCoordinator` is here because it calls `onStatus`,
         * which `RemoteTranscriber` forwards verbatim to `DictateActivity`;
         * it was missing from the first version of this list and a mutant
         * proved the gap. `DictateActivity` is here as the **sink** — it
         * declares the override that assigns `statusText.text` — not because
         * it calls `onStatus`, which it never does.
         *
         * `BubbleService` and `BubbleSessionBus` are deliberately excluded;
         * [everyClassThatCallsOnStatusIsClassified] holds the reasons and
         * fails if a new caller appears in neither place.
         */
        val STATUS_WRITERS = listOf(
            "DictateActivity",
            "DictationSession",
            "RemoteTranscriber",
            "TranscriptionCoordinator",
        )
    }

    /**
     * The at-rest default for the two strip views that carry dictation state.
     *
     * RETIRED-AND-REPLACED: this used to read "both stubs exist and are inert",
     * justified as "an unbound stub would render as empty chrome". The stubs are
     * now bound (the remote-STT merge is the event that retired that reading),
     * so the assertion survives with a DIFFERENT contract — these views describe
     * a dictation, and before one has happened there is nothing true for them to
     * say. A meter at zero and an empty badge are not neutral: they assert "no
     * input" and "no engine" about a session that has not started.
     *
     * Deliberately against [inflate] rather than a running activity, for the
     * same reason [theLayoutDefaultForTheActionRowIsHidden] is: this pins the
     * XML's own default, so any path that inflates this layout without
     * DictateActivity's wiring still starts clean.
     */
    @Test
    fun theMeterAndTheBadgeSaySilentUntilADictationDrivesThem() {
        val root = inflate()
        for (id in listOf(R.id.level_meter, R.id.engine_badge)) {
            assertEquals(
                "${nameOf(id)} is visible in the layout default; it reports on " +
                    "a dictation, and at rest there has not been one",
                View.GONE,
                root.findViewById<View>(id).visibility
            )
            assertTrue(
                "${nameOf(id)} has no contentDescription; it is announced the " +
                    "moment a dictation shows it, and a bar and a short badge " +
                    "are not self-describing to a screen reader",
                !root.findViewById<View>(id).contentDescription.isNullOrBlank()
            )
        }
        // The badge must start with nothing TO announce, not with stale text.
        assertTrue(
            "engine_badge ships with text in the layout; it would claim an " +
                "engine for a transcript that does not exist yet",
            root.findViewById<android.widget.TextView>(R.id.engine_badge)
                .text.isNullOrEmpty()
        )
    }

    /**
     * REPLACES the reservation guard, and is the half that has teeth: the meter
     * and the badge are actually WIRED, not merely present and labelled.
     *
     * The previous version of this file could only assert that two stubs sat
     * there GONE. That passes just as well when nothing will ever show them —
     * which is the defect this one catches, because it drives the real
     * callbacks object [DictateActivity.uiCallbacks] hands to
     * [DictationSession] and asserts the views move.
     *
     * The level is deliberately 0.37 rather than a round number: 37 is not
     * reachable from a hardcoded 0, 50 or 100, so a mutant that ignores its
     * argument cannot produce it.
     */
    @Test
    fun aDictationBindsTheMeterAndNamesTheEngine() {
        val controller = Robolectric.buildActivity(DictateActivity::class.java).setup()
        try {
            val a = controller.get()
            val meter = a.findViewById<android.widget.ProgressBar>(R.id.level_meter)
            val badge = a.findViewById<android.widget.TextView>(R.id.engine_badge)
            val cb = a.uiCallbacks()

            assertEquals("precondition: the meter starts hidden", View.GONE, meter.visibility)
            assertEquals("precondition: the badge starts hidden", View.GONE, badge.visibility)

            // --- the meter tracks the microphone -----------------------------
            cb.onTier(AudioCaptureLogic.Tier.REMOTE_ONLY_NO_API)
            assertEquals(
                "the meter is still hidden after a tier was settled; in the " +
                    "remote-only tiers it is the ONLY evidence the mic is live",
                View.VISIBLE, meter.visibility
            )
            cb.onLevel(0.37)
            assertEquals(
                "onLevel did not reach the meter's progress — the bar is " +
                    "drawn but bound to nothing",
                37, meter.progress
            )

            // INVISIBLE, not GONE: the strip must not reflow when another app
            // steals the mic, or the card resizes under the user.
            cb.onClientSilenced(true)
            assertEquals(
                "a silenced mic hid the meter with GONE; the strip reflows and " +
                    "the card changes height on a state the user did not cause",
                View.INVISIBLE, meter.visibility
            )
            cb.onClientSilenced(false)
            assertEquals(View.VISIBLE, meter.visibility)

            // --- the badge names the engine, in words ------------------------
            cb.onTranscript("from the server", fromRemote = true, isFinal = true)
            assertEquals(
                "engine_badge is hidden after a remote transcript arrived; the " +
                    "user cannot tell which engine produced the text on screen",
                View.VISIBLE, badge.visibility
            )
            assertEquals(
                a.getString(R.string.engine_badge_remote), badge.text.toString()
            )

            // 🔴 The badge must never carry a status line STRING — neither a
            // caveat nor the plain one. It is an 11sp pill that names the
            // engine; the full sentences belong on the full-width line, and
            // the plain one being on BOTH is the duplicate this change
            // removed (see theEngineBadgeDoesNotRepeatTheStatusLine). If
            // someone routes statusLabel's output back into the badge, every
            // one of these fails.
            for (status in listOf(
                R.string.stt_remote_done,
                R.string.stt_remote_truncated,
                R.string.stt_remote_unreliable,
            )) {
                assertTrue(
                    "engine_badge reads \"${badge.text}\", which is the status " +
                        "line's \"${a.getString(status)}\". The badge names the " +
                        "ENGINE: it has no room for a sentence, no basis for a " +
                        "claim about this particular transcript, and the status " +
                        "line is already saying it.",
                    badge.text.toString() != a.getString(status),
                )
            }

            // The two engines must be DISTINGUISHABLE, which is the whole point
            // of the badge — asserted as a difference, so a mutant that returns
            // one constant for both cannot pass.
            val remoteText = badge.text.toString()
            val fresh = Robolectric.buildActivity(DictateActivity::class.java).setup()
            try {
                val b = fresh.get()
                b.uiCallbacks().onTranscript("from the phone", fromRemote = false, isFinal = true)
                val localText = b.findViewById<android.widget.TextView>(R.id.engine_badge)
                    .text.toString()
                assertEquals(a.getString(R.string.engine_badge_local), localText)
                assertTrue(
                    "the badge reads \"$localText\" for BOTH engines — it is " +
                        "present, labelled, and says nothing",
                    localText != remoteText
                )
            } finally {
                fresh.close()
            }
        } finally {
            controller.close()
        }
    }

    /**
     * REGRESSION: **"Transcribed by the server" was on the card twice.**
     *
     * After a remote transcription the badge and the status line both resolved
     * to `@string/stt_remote_done`, so the same sentence appeared in an 11sp
     * pill and again in the full-width line under it. The operator: *"there is
     * a redundant transcribed on the server message. We only need one."*
     *
     * 🔴 Pinned as a RELATIONSHIP between the two surfaces, not as the badge's
     * spelling: the defect is the duplication, and any future copy edit that
     * re-converges the two is the same defect back in a new wording. The badge
     * is also required to still SAY something — collapsing the duplicate by
     * blanking the badge would leave the local case with no engine name at
     * all, which is the thing the badge exists for.
     *
     * The status line is the surface that keeps the sentence, because it is
     * the one that carries the `truncated` / `unreliable` variants
     * (`RemoteTranscriber.statusLabel`), and those must not move to an 11sp
     * pill — `theBadgeNeverCarriesAWarning`'s assertions inside
     * [aDictationBindsTheMeterAndNamesTheEngine] still hold that line.
     */
    @Test
    fun theEngineBadgeDoesNotRepeatTheStatusLine() {
        val controller = Robolectric.buildActivity(DictateActivity::class.java).setup()
        try {
            val a = controller.get()
            val badge = a.findViewById<android.widget.TextView>(R.id.engine_badge)
            val status = a.findViewById<android.widget.TextView>(R.id.status_text)
            val cb = a.uiCallbacks()

            // Exactly what a plain remote success does: the transcript names
            // the engine on the badge, and RemoteTranscriber's status for the
            // no-signal case goes to the status line.
            cb.onTranscript("the server transcript", fromRemote = true, isFinal = true)
            cb.onStatus(
                a.getString(RemoteTranscriber.statusLabel(truncated = null, unreliable = null))
            )

            assertTrue(
                "the badge is empty after a remote transcript; the duplicate " +
                    "was removed by silencing the surface that answers " +
                    "\"which engine\" in BOTH the remote and the local case",
                !badge.text.isNullOrBlank()
            )
            assertEquals(
                "precondition: the status line is not carrying the remote " +
                    "status, so this test is not looking at the duplicate",
                a.getString(R.string.stt_remote_done),
                status.text.toString(),
            )
            assertFalse(
                "the badge reads \"${badge.text}\" and so does the status " +
                    "line directly beneath it — one plain remote transcription " +
                    "puts the same sentence on the card twice",
                badge.text.toString() == status.text.toString(),
            )
        } finally {
            controller.close()
        }
    }

    /**
     * REPLACES `theReservedSettingsSlotExistsAndIsInert`.
     *
     * That guard asserted `settings_slot` had ZERO children, because "a visible
     * control here would open nothing". The remote-STT merge supplied the thing
     * it was holding the place for, so the assertion inverts: the slot is
     * visible, it has the control in it, and — the part the old guard could not
     * check and the reason it was written that way — the control is REACHABLE,
     * with a listener that resolves to a real activity.
     */
    @Test
    fun theSettingsSlotIsFilledWithAControlThatOpensSettings() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val a = controller.get()
            val slot = a.findViewById<ViewGroup>(R.id.settings_slot)
            assertNotNull("settings_slot is gone from activity_main.xml", slot)
            assertTrue(
                "settings_slot is empty; the reservation was never filled",
                slot.childCount > 0
            )
            assertEquals(
                "settings_slot is not visible; the control inside it cannot be " +
                    "reached",
                View.VISIBLE,
                slot.visibility
            )

            val button = a.findViewById<View>(R.id.settings_button)
            assertNotNull("settings_button is missing", button)
            assertTrue(
                "settings_button is not inside settings_slot; the design pass " +
                    "placed that slot after the triggers it configures, and a " +
                    "control that drifted out of it has lost that placement",
                hasAncestorWithId(button, R.id.settings_slot)
            )
            assertTrue(
                "settings_button has no click listener — it is the dead control " +
                    "the reservation existed to avoid",
                button.hasOnClickListeners()
            )

            // Reachability, not just a listener: the tap must start something.
            button.performClick()
            val next = org.robolectric.Shadows.shadowOf(a).nextStartedActivity
            assertNotNull(
                "tapping settings_button started no activity; the control opens " +
                    "nothing, which is exactly what the reservation forbade",
                next
            )
            assertEquals(
                SettingsActivity::class.java.name,
                next.component?.className
            )
        } finally {
            controller.close()
        }
    }

    /**
     * The accent fill is the "dictate" affordance and nothing else. Pinned
     * because the merge had to style a new button on this screen and reaching
     * for `@drawable/mic_pill_bg` is the easy wrong answer.
     *
     * Compared against the screen's real accent control rather than against a
     * drawable NAME, so it cannot be walked past by renaming the drawable.
     *
     * 🔴 The obvious spelling of this — comparing the two backgrounds'
     * `constantState` — is INERT, and was caught by mutation F in the sweep
     * (pointing `settings_button` at `@drawable/mic_pill_bg` SURVIVED a fully
     * green suite). Two inflations of one drawable resource do not share a
     * `constantState` instance here, so that comparison was unequal whatever
     * the resources were. The resource ID that each background was inflated
     * FROM is the thing that actually distinguishes them.
     */
    @Test
    fun settingsIsNotStyledAsADictationAffordance() {
        val ctx = ContextThemeWrapper(
            RuntimeEnvironment.getApplication(), R.style.Theme_Dicta
        )
        val root = LayoutInflater.from(ctx).inflate(R.layout.activity_main, null)
        val settingsBg = org.robolectric.Shadows.shadowOf(
            root.findViewById<View>(R.id.settings_button).background
        ).createdFromResId
        val accentBg = org.robolectric.Shadows.shadowOf(
            root.findViewById<View>(R.id.try_now_button).background
        ).createdFromResId

        // Positive control for the comparison itself: if the ids were not
        // resolvable, both would be -1 and the inequality below would be
        // vacuously false-negative.
        assertTrue(
            "neither background resolved to a drawable resource id " +
                "($settingsBg / $accentBg); this guard is reading nothing",
            settingsBg != -1 && accentBg != -1
        )
        assertTrue(
            "settings_button and try_now_button are inflated from the SAME " +
                "background drawable; the accent fill means \"dictate\", and " +
                "Settings is not a dictation affordance",
            settingsBg != accentBg
        )
    }

    // --- helpers ------------------------------------------------------------

    /**
     * Whether [v] sits anywhere under the view with id [id].
     *
     * The explicit `as ViewParent` is load-bearing: once `p` smart-casts to
     * `View`, `p.parent` is ambiguous between `View.parent` and
     * `ViewParent.parent` and does not compile.
     */
    private fun hasAncestorWithId(v: View, id: Int): Boolean {
        var p: android.view.ViewParent? = v.parent
        while (p != null) {
            if (p is View && p.id == id) return true
            p = (p as android.view.ViewParent).parent
        }
        return false
    }

    private fun collectImageButtons(v: View, into: MutableSet<Int>) {
        if (v is ImageButton) into.add(v.id)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) collectImageButtons(v.getChildAt(i), into)
        }
    }

    private fun nameOf(id: Int): String =
        RuntimeEnvironment.getApplication().resources.getResourceEntryName(id)
}
