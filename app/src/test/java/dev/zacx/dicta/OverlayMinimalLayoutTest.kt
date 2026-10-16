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
 * Guards for the radically-minimal dictation overlay.
 *
 * The change this covers hid four controls that used to be on screen at all
 * times. Hiding a control is only safe if the route back to it is real, so the
 * behavioural tests here are the ones that matter: [tappingMoreRevealsTheActions]
 * and [typingRevealsTheActions] drive the two routes and would pass vacuously
 * only if `action_row` were visible to begin with — which
 * [atRestOnlyTheTranscriptAndTheMicAreVisible] independently denies.
 *
 * ## Red-before-green
 *
 * `action_row`, `more_button`, `level_meter` and `engine_badge` do not exist at
 * the design pass's base, so this file cannot compile there and "red at base"
 * would be a compile error rather than a guard watched failing. What was
 * observed instead is a set of hand-rolled mutations on this tree; the matrix is
 * in the PR body. In particular, deleting the `wireOnDemandActions()` call from
 * `DictateActivity.onCreate` — i.e. shipping the layout's hidden row with
 * nothing to reveal it, which is the exact defect the operator warned about —
 * turns [tappingMoreRevealsTheActions] and [typingRevealsTheActions] red while
 * the rest of the suite stays green.
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
     * `more_button` is in here but deliberately NOT in
     * `DictateActionViewTypesTest.iconActions`: that ledger is the membership of
     * `action_row`, and `more_button` must stay outside the row it toggles.
     */
    private val iconOnlyControls = listOf(
        R.id.more_button,
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
     * THE minimal-overlay assertion: at rest the card is the transcript, the mic
     * and the way to everything else — and the four actions are not on it.
     */
    @Test
    fun atRestOnlyTheTranscriptAndTheMicAreVisible() {
        val controller = Robolectric.buildActivity(DictateActivity::class.java).setup()
        try {
            val a = controller.get()

            for (id in listOf(R.id.dictated_text, R.id.mic_button, R.id.more_button)) {
                assertEquals(
                    "${nameOf(id)} must be visible at rest — it is one of the " +
                        "three things the minimal card is allowed to show",
                    View.VISIBLE,
                    a.findViewById<View>(id).visibility
                )
            }

            assertEquals(
                "action_row is visible at rest; the overlay is not minimal and " +
                    "the on-demand reveal tests below would pass vacuously",
                View.GONE,
                a.findViewById<View>(R.id.action_row).visibility
            )

            // The old always-on chrome that the pass removed.
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
     * `wireOnDemandActions()` calls `setActionsRevealed(..., currentText()
     * .isNotEmpty())` on create, so the activity forces the row hidden whatever
     * the XML says. Deleting `android:visibility="gone"` from the row therefore
     * leaves [atRestOnlyTheTranscriptAndTheMicAreVisible] GREEN (observed in the
     * mutation sweep — the mutant survived it). Two independent things have to
     * hold for the card to be minimal in every state, so there are two
     * assertions: this one fails on the layout default, that one on the runtime
     * state after the wiring has run.
     */
    @Test
    fun theLayoutDefaultForTheActionRowIsHidden() {
        assertEquals(
            "activity_dictate.xml declares action_row visible; the card is only " +
                "minimal because DictateActivity happens to hide it on create, " +
                "and any path that inflates this layout without that wiring " +
                "(or any future early return before it) ships the full row",
            View.GONE,
            inflate().findViewById<View>(R.id.action_row).visibility
        )
    }

    // --- route 1: the overflow ----------------------------------------------

    @Test
    fun tappingMoreRevealsTheActions() {
        val controller = Robolectric.buildActivity(DictateActivity::class.java).setup()
        try {
            val a = controller.get()
            val row = a.findViewById<View>(R.id.action_row)
            val more = a.findViewById<ImageButton>(R.id.more_button)

            assertEquals("precondition: the row starts hidden", View.GONE, row.visibility)

            more.performClick()
            assertEquals(
                "tapping more_button did not reveal action_row — the four " +
                    "actions are unreachable",
                View.VISIBLE,
                row.visibility
            )
            // ...and the revealed controls are actually invocable, not merely
            // drawn: a visible row wired to nothing is the same defect.
            for (id in listOf(
                R.id.copy_button, R.id.screenshot_button,
                R.id.share_button, R.id.close_button
            )) {
                val v = a.findViewById<View>(id)
                assertEquals(
                    "${nameOf(id)} is not visible inside the revealed row",
                    View.VISIBLE, v.visibility
                )
                assertTrue("${nameOf(id)} has no click listener", v.hasOnClickListeners())
            }

            more.performClick()
            assertEquals(
                "more_button does not toggle back — the row can be opened but " +
                    "never put away",
                View.GONE,
                row.visibility
            )
        } finally {
            controller.close()
        }
    }

    /**
     * The toggle's state must be announced, not only filled in with colour
     * (`ghost_button_bg`'s `state_selected`). Both the contentDescription and
     * the tooltipText move, because the two are read by different users.
     */
    @Test
    fun theOverflowStateIsAnnouncedInWordsNotOnlyInColour() {
        val controller = Robolectric.buildActivity(DictateActivity::class.java).setup()
        try {
            val a = controller.get()
            val more = a.findViewById<ImageButton>(R.id.more_button)

            val closedDesc = more.contentDescription?.toString()
            val closedTip = more.tooltipText?.toString()
            more.performClick()
            val openDesc = more.contentDescription?.toString()
            val openTip = more.tooltipText?.toString()

            assertEquals(a.getString(R.string.more_actions), closedDesc)
            assertEquals(a.getString(R.string.more_actions), closedTip)
            assertEquals(a.getString(R.string.hide_actions), openDesc)
            assertEquals(a.getString(R.string.hide_actions), openTip)
            assertTrue(
                "more_button is not marked selected while the row is open, so " +
                    "the visual state has nothing to hang on",
                more.isSelected
            )
        } finally {
            controller.close()
        }
    }

    // --- route 2: text arriving ---------------------------------------------

    /**
     * The route that means a user who has just dictated never has to discover
     * anything. Driven through the transcript's own text, which is how both a
     * recogniser result and manual typing arrive.
     */
    @Test
    fun typingRevealsTheActions() {
        val controller = Robolectric.buildActivity(DictateActivity::class.java).setup()
        try {
            val a = controller.get()
            val row = a.findViewById<View>(R.id.action_row)
            assertEquals("precondition: the row starts hidden", View.GONE, row.visibility)

            a.findViewById<android.widget.EditText>(R.id.dictated_text)
                .setText("hello from the recogniser")

            assertEquals(
                "text arrived in the transcript and action_row stayed hidden — " +
                    "the automatic reveal is not wired",
                View.VISIBLE,
                row.visibility
            )
        } finally {
            controller.close()
        }
    }

    /**
     * Whitespace is not text. Pinned because the obvious implementation
     * (`s.isNotEmpty()`) reveals the row on a stray space, in a state where
     * every action in it still refuses.
     */
    @Test
    fun whitespaceAloneDoesNotRevealTheActions() {
        val controller = Robolectric.buildActivity(DictateActivity::class.java).setup()
        try {
            val a = controller.get()
            a.findViewById<android.widget.EditText>(R.id.dictated_text).setText("   ")
            assertEquals(
                "a blank transcript revealed the actions",
                View.GONE,
                a.findViewById<View>(R.id.action_row).visibility
            )
        } finally {
            controller.close()
        }
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
     * at full card width (see [theLongestStatusStringIsNotTruncated]), and a
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
     *    regardless of `textSize` (measured: 154 chars -> 154.0px, textSize
     *    12.0, density 1.0). So the longest real string "fits" in one line of
     *    the ~953px card, and a `maxLines="1"` regression measures as fine.
     *
     * Both versions were written, and both passed a mutation that put the old
     * broken layout back. They were only caught because the mutation battery
     * ran — a positive control using an absurd 4000-character string passed
     * while the real 154-character assertion stayed vacuous, since 4000px DOES
     * exceed the width and 154px does not.
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
                "555px of an 801px row (~30 characters) for strings up to 154.",
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
                "and 12sp this device fits roughly 48 characters per line, and " +
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
     * So the set is now actually derived: every `R.string.*` the three classes
     * that write this view reference, resolved against the real resources.
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
     * `BubbleService` is deliberately excluded: its long strings are Toasts,
     * not this view, and pulling them in would make a Settings-adjacent copy
     * edit fail a dictation-layout guard for no reason.
     */
    private fun statusStringNames(): Set<String> =
        listOf("DictateActivity", "DictationSession", "RemoteTranscriber")
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
     * 🔴 The control for the derivation above, and the growth guard the old
     * hardcoded list could not be.
     *
     * Asserts the derived set still contains every name the previous version
     * listed by hand — so a derivation that breaks, or silently resolves to a
     * handful of button labels, goes red rather than reporting a comfortably
     * short "longest" — **and** that it contains the status strings added
     * since, which is the case the `names.size == values.size` form was blind
     * to. A new status string is picked up with no edit here; one that the
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
         * ⚠ This kdoc used to assert that longest **is 154 characters**. It is
         * not, and was not: the set the guard measures maxes out at 123 with a
         * realistic `%1$s` filler. The number was a stale restatement of a
         * measurement, so it is gone — `longestStatusString()` measures it,
         * `noStatusStringOutgrowsItsLineBudget` asserts it fits, and neither
         * needs a figure copied into prose that nothing re-checks.
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
                a.getString(R.string.stt_remote_done), badge.text.toString()
            )

            // 🔴 The badge must never carry a CAVEAT. It is an 11sp pill that
            // names the engine; the warnings belong on the full-width status
            // line. `showEngine` is deliberately flag-blind (see its kdoc),
            // and the hazard that justifies the sharing of stt_remote_done is
            // that string acquiring a case-specific claim — so pin, here,
            // that whatever the badge resolves to is neither warning. If
            // someone routes statusLabel's output into the badge, or makes
            // stt_remote_done a sentence about THIS transcript, this fails.
            for (warning in listOf(R.string.stt_remote_truncated, R.string.stt_remote_unreliable)) {
                assertTrue(
                    "engine_badge reads \"${badge.text}\", which is the status " +
                        "line's warning \"${a.getString(warning)}\". The badge " +
                        "names the ENGINE and has no basis for a claim about " +
                        "this particular transcript.",
                    badge.text.toString() != a.getString(warning),
                )
            }
            assertEquals(
                "the badge diverged from the no-signal case of " +
                    "RemoteTranscriber.statusLabel. The two deliberately share " +
                    "one resource; if that is no longer true, showEngine's kdoc " +
                    "is wrong and the badge may now be making a claim.",
                a.getString(RemoteTranscriber.statusLabel(truncated = null, unreliable = null)),
                badge.text.toString(),
            )

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
                assertEquals(a.getString(R.string.stt_local_label), localText)
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
