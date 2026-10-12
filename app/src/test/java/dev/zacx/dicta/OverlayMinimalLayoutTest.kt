package dev.zacx.dicta

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ContextThemeWrapper
import android.widget.ImageButton
import org.junit.Assert.assertEquals
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
 * `origin/main`, so this file cannot compile there and "red at base" would be a
 * compile error rather than a guard watched failing. What was observed instead
 * is a set of hand-rolled mutations on this tree; the matrix is in the PR body.
 * In particular, deleting the `wireOnDemandActions()` call from
 * `DictateActivity.onCreate` — i.e. shipping the layout's hidden row with
 * nothing to reveal it, which is the exact defect the operator warned about —
 * turns [tappingMoreRevealsTheActions] and [typingRevealsTheActions] red while
 * the rest of the suite stays green.
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
     * INVARIANT GUARD, labelled as one — it pins a reservation, not a fixed
     * bug. Two views in the status strip are stubs for in-flight work on
     * another branch (a mic level meter and an engine-provenance badge). The
     * contract this asserts is "the ids exist, they are inside
     * `overlay_status_strip`, and they are invisible until that branch lands",
     * so the merge is mechanical rather than a layout negotiation.
     *
     * It is NOT regression coverage and should not be counted as such.
     */
    @Test
    fun theReservedMeterAndEngineSlotsExistAndAreInert() {
        val root = inflate()
        val strip = root.findViewById<ViewGroup>(R.id.overlay_status_strip)
        assertNotNull("overlay_status_strip is gone — the reserved slots have no home", strip)

        val children = (0 until strip.childCount).map { strip.getChildAt(it).id }
        assertEquals(
            "the status strip's membership or order changed; the meter is " +
                "placed first and the badge last on purpose",
            listOf(R.id.level_meter, R.id.status_text, R.id.engine_badge),
            children
        )

        for (id in listOf(R.id.level_meter, R.id.engine_badge)) {
            assertEquals(
                "${nameOf(id)} is visible; it is an unbound stub and would " +
                    "render as empty chrome",
                View.GONE,
                root.findViewById<View>(id).visibility
            )
            assertTrue(
                "${nameOf(id)} has no contentDescription; an unlabelled stub " +
                    "becomes an unlabelled view the moment it is shown",
                !root.findViewById<View>(id).contentDescription.isNullOrBlank()
            )
        }

        // status_text keeps carrying the live status (and is where a
        // "transcribing…" message goes), so it must stay visible.
        assertEquals(
            View.VISIBLE,
            root.findViewById<View>(R.id.status_text).visibility
        )
    }

    /** Companion reservation on the setup screen: the settings entry point. */
    @Test
    fun theReservedSettingsSlotExistsAndIsInert() {
        val ctx = ContextThemeWrapper(
            RuntimeEnvironment.getApplication(), R.style.Theme_Dicta
        )
        val root = LayoutInflater.from(ctx).inflate(R.layout.activity_main, null)
        val slot = root.findViewById<ViewGroup>(R.id.settings_slot)
        assertNotNull("settings_slot is gone from activity_main.xml", slot)
        assertEquals(
            "settings_slot has children; it is a reservation, and a visible " +
                "control here would open nothing",
            0,
            slot.childCount
        )
        assertEquals(View.GONE, slot.visibility)
    }

    // --- helpers ------------------------------------------------------------

    private fun collectImageButtons(v: View, into: MutableSet<Int>) {
        if (v is ImageButton) into.add(v.id)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) collectImageButtons(v.getChildAt(i), into)
        }
    }

    private fun nameOf(id: Int): String =
        RuntimeEnvironment.getApplication().resources.getResourceEntryName(id)
}
