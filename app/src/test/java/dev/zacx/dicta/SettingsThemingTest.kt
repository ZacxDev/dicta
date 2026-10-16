package dev.zacx.dicta

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Spinner
import android.widget.TextView
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Issue #10: the settings screen must be built from the same conventions as
 * MainActivity, and must not drift back to stock platform controls.
 *
 * ## Why these are measurements and not spot checks
 *
 * The defect in #10 was COUNTABLE — "the unthemed-control count went up" when
 * the language `Spinner` landed carrying `android.R.layout.simple_spinner_item`.
 * A test that checks the three controls it happens to name cannot see the
 * fourth one somebody adds later, which is exactly how this regressed once
 * already. So [controlBackgrounds] is a LEDGER asserted as a relationship:
 * [everyControlIsLedgeredAndCarriesTheDrawableItShould] enumerates the controls
 * actually present and fails when the set GROWS (a new control escaping the
 * convention) *or* SHRINKS (one removed while the ledger still claims it).
 *
 * ## Why half of this reads the layout SOURCE rather than the inflated tree
 *
 * Both are used, for different claims, and the split is a measured constraint
 * rather than a preference:
 *
 *  * Resolved values — a colour, the heading tracking, a compound drawable's
 *    presence, `stateListAnimator`, a `contentDescription` — are read off the
 *    INFLATED view, because that is the thing the user meets and because a
 *    style can be gutted while the `style="…"` attribute still reads correctly.
 *  * WHICH drawable resource a background is cannot be read off an inflated
 *    view. Robolectric's `ShadowDrawable.createdFromResId` is populated for
 *    drawables created from a resource *stream*, not for the `<selector>` and
 *    `<shape>` XML drawables this screen uses, so it reports nothing here — and
 *    a guard that silently reports nothing is the failure mode this file exists
 *    to prevent. The identity is therefore asserted against the layout XML,
 *    which is the source of truth for it.
 *
 * ## Red-before-green
 *
 * This file cannot compile at `origin/main`: `R.drawable.destructive_button_bg`,
 * `R.layout.spinner_item` and `R.string.stt_section_service` do not exist there,
 * so "red at base" would be a compile error rather than a guard watched
 * failing — the same situation `OverlayMinimalLayoutTest` documents for the
 * design pass. What was done instead is a mutation: with the new resources left
 * in place, `activity_settings.xml` and `SettingsActivity.bindLanguageSpinner()`
 * were reverted to their `origin/main` content — the genuinely unthemed screen —
 * and these tests were watched to fail. The matrix is in the commit message.
 *
 * ## What this file deliberately does NOT claim
 *
 * Nothing here is evidence that the screen *reads* as the same app as
 * MainActivity. That is a pixel claim and its only honest check is a device
 * screenshot of both screens side by side. These tests pin the mechanical
 * preconditions: which drawable each control carries, that the picker inflates
 * the app's own rows, that a group heading is an accent label rather than bold
 * body text, and that the destructive action survives the loss of colour
 * vision.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsThemingTest {

    /**
     * Every interactive control on the settings screen and the background
     * drawable it must carry, by the `android:id` spelling used in the layout.
     *
     * `stt_remote_enabled` maps to `null` on purpose and it is the one entry
     * that needs justifying: a framework `CheckBox` draws its box and tick from
     * `colorControlNormal` / `colorControlActivated`, which `Theme.Dicta`
     * already points at the ember accent, so a background drawable would add
     * nothing — exactly as MainActivity leaves `bubble_switch` and
     * `notif_switch` bare. The claim for that control is made on its label
     * instead, by [everyTextBearingControlUsesTheOnSurfaceColour].
     */
    private val controlBackgrounds: Map<String, String?> = mapOf(
        "stt_remote_enabled" to null,
        "stt_language" to "@drawable/input_bg",
        "stt_url" to "@drawable/input_bg",
        "stt_token" to "@drawable/input_bg",
        "stt_save" to "@drawable/outline_button_bg",
        "stt_test" to "@drawable/outline_button_bg",
        "stt_clear_token" to "@drawable/destructive_button_bg",
    )

    /** The group labels, which must all be accent labels and not body text. */
    private val headingStrings = listOf(
        R.string.stt_section_service,
        R.string.stt_section_language,
        R.string.stt_section_connection,
    )

    private val controlTags = setOf("Button", "Spinner", "CheckBox", "EditText")

    // --- plumbing -----------------------------------------------------------

    private fun themedContext() = ContextThemeWrapper(
        RuntimeEnvironment.getApplication(), R.style.Theme_Dicta
    )

    private fun inflate(): View =
        LayoutInflater.from(themedContext()).inflate(R.layout.activity_settings, null)

    private fun descendants(root: View): List<View> {
        val out = mutableListOf<View>()
        fun walk(v: View) {
            out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    /** Only the interactive widgets — a `TextView` is not a control. */
    private fun controlsIn(root: View): List<View> = descendants(root).filter {
        it is Button || it is Spinner || it is CheckBox || it is EditText
    }

    private fun nameOf(id: Int): String =
        RuntimeEnvironment.getApplication().resources.getResourceEntryName(id)

    /**
     * The layout file as a DOM, namespace-UNaware so attributes keep their
     * literal `android:` prefix.
     *
     * The candidate paths exist because the test's working directory is the
     * Gradle module dir in a normal run; if neither resolves, this fails with
     * the directory it actually looked in rather than skipping — a layout guard
     * that cannot find the layout must not read as a pass.
     */
    private fun layoutXml(name: String): Element {
        val candidates = listOf(
            File("src/main/res/layout/$name.xml"),
            File("app/src/main/res/layout/$name.xml"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "cannot find $name.xml from ${File(".").absolutePath} (tried " +
                    candidates.joinToString { it.path } + ")"
            )
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    private fun elements(root: Element): List<Element> {
        val out = mutableListOf<Element>()
        fun walk(n: Node) {
            if (n is Element) out += n
            val children = n.childNodes
            for (i in 0 until children.length) walk(children.item(i))
        }
        walk(root)
        return out
    }

    /** `@+id/stt_save` and `@id/stt_save` both reduce to `stt_save`. */
    private fun idName(e: Element): String? = e.getAttribute("android:id")
        .takeIf { it.isNotEmpty() }
        ?.substringAfterLast('/')

    private fun controlElements(): Map<String, Element> =
        elements(layoutXml("activity_settings"))
            .filter { it.tagName in controlTags }
            .associateBy { idName(it) ?: "<no id: ${it.tagName}>" }

    // --- the ledger ---------------------------------------------------------

    @Test
    fun everyControlIsLedgeredAndCarriesTheDrawableItShould() {
        val controls = controlElements()

        assertEquals(
            "the set of interactive controls in activity_settings.xml is not the " +
                "set this file pins. A control was added or removed — add it to " +
                "controlBackgrounds with the drawable it carries (or justify a null " +
                "the way stt_remote_enabled is justified)",
            controlBackgrounds.keys,
            controls.keys
        )

        for ((id, element) in controls) {
            val expected = controlBackgrounds[id]
            val declared = element.getAttribute("android:background").takeIf { it.isNotEmpty() }
            if (expected == null) {
                assertNull(
                    "$id is ledgered as theme-drawn but declares a background",
                    declared
                )
                continue
            }
            assertEquals(
                "$id must declare $expected. No background at all means the stock " +
                    "platform widget, which is the #10 defect itself",
                expected,
                declared
            )
        }
    }

    /**
     * Second dimension of the ledger: the inflated tree must hold exactly the
     * controls the XML declares.
     *
     * Not symmetry for its own sake — the ledger above reads the source, so on
     * its own it cannot tell that the file under test is the one the app
     * inflates. This is the seam between the two halves of this file, and it is
     * what makes the source-based assertions claims about the real screen.
     */
    @Test
    fun theInflatedScreenHoldsExactlyTheControlsTheLayoutDeclares() {
        assertEquals(
            "the controls in the inflated activity_settings do not match the ones " +
                "parsed out of its XML, so the source-based guards in this file are " +
                "reading a different file from the one the app uses",
            controlBackgrounds.keys,
            controlsIn(inflate()).map { nameOf(it.id) }.toSet()
        )
    }

    /**
     * A themed background with stock text colours is still a stock control, and
     * this is the only claim made about `stt_remote_enabled`, whose background
     * is legitimately null.
     *
     * `stt_clear_token` is excluded because it is the one control whose label is
     * deliberately NOT on-surface — see
     * [theDestructiveActionIsIdentifiableWithNoColourVision].
     */
    @Test
    fun everyTextBearingControlUsesTheOnSurfaceColour() {
        val onSurface = themedContext().getColor(R.color.dicta_on_surface)
        val examined = mutableListOf<String>()

        for (control in controlsIn(inflate())) {
            if (control.id == R.id.stt_clear_token) continue
            // A Spinner is not a TextView; its collapsed row is covered by the
            // picker tests below.
            if (control !is TextView) continue
            assertEquals(
                "${nameOf(control.id)} does not use @color/dicta_on_surface for its " +
                    "text, so it is coloured by whatever the platform widget defaults to",
                onSurface,
                control.textColors.defaultColor
            )
            examined += nameOf(control.id)
        }

        // Positive control: a zero here would make the loop vacuous and
        // indistinguishable from a tree with no text-bearing controls at all.
        assertEquals(
            "expected to examine the checkbox, both fields and both outlined " +
                "buttons; examined $examined",
            5,
            examined.size
        )
    }

    // --- the headings -------------------------------------------------------

    /**
     * The group labels must be the accent uppercase label MainActivity uses
     * ("PERMISSIONS", "TRIGGERS (PICK ANY)"), not the bare bold `TextView`s
     * this screen shipped with.
     *
     * Asserted on resolved VALUES — accent colour, the style's tracking, the
     * all-caps transformation — rather than on `style="…"`, which an inflated
     * view does not retain and which would in any case pass while pointing at a
     * style whose body had been emptied.
     */
    @Test
    fun theGroupHeadingsAreAccentLabelsAndNotBoldBodyText() {
        val ctx = themedContext()
        val accent = ctx.getColor(R.color.dicta_accent)
        val byText = descendants(inflate())
            .filterIsInstance<TextView>()
            .associateBy { it.text?.toString() }

        for (res in headingStrings) {
            val text = ctx.getString(res)
            val view = byText[text]
            assertNotNull("no TextView on the screen shows the heading \"$text\"", view)
            assertEquals(
                "the \"$text\" heading is not @color/dicta_accent, so it is not the " +
                    "section-heading label the other screen uses",
                accent,
                view!!.textColors.defaultColor
            )
            assertEquals(
                "the \"$text\" heading lost @style/Text.Dicta.SectionHeading's " +
                    "tracking, so it will not read as a label",
                // Doubles, not floats: Kotlin does not widen Float to Double
                // implicitly, so the float overload of assertEquals has to
                // exist for a float call to resolve — converting here keeps
                // this off a JUnit-version detail.
                0.12,
                view.letterSpacing.toDouble(),
                0.0001
            )
            assertNotNull(
                "the \"$text\" heading is not transformed to upper case",
                view.transformationMethod
            )
        }
    }

    // --- the language picker (the measurable half of #10) -------------------

    /**
     * 🔴 The exact regression #10 names: PR #12 added the picker with
     * `android.R.layout.simple_spinner_item` and
     * `android.R.layout.simple_spinner_dropdown_item`.
     *
     * The resource ids the adapter was built with are read back off the real
     * `ArrayAdapter` by reflection because that is the only place they survive —
     * nothing in the view tree records which layout produced a row. A missing
     * field fails the test rather than skipping it: a guard that quietly stops
     * looking is the failure mode this whole file exists to avoid.
     */
    @Test
    fun theLanguagePickerInflatesTheAppsOwnRowsNotThePlatformOnes() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val adapter = controller.get()
                .findViewById<Spinner>(R.id.stt_language).adapter as ArrayAdapter<*>

            fun resourceField(name: String): Int {
                val field = try {
                    ArrayAdapter::class.java.getDeclaredField(name)
                } catch (e: NoSuchFieldException) {
                    throw AssertionError(
                        "ArrayAdapter has no field '$name' on this SDK, so this guard " +
                            "can no longer see which layout the picker uses — fix the " +
                            "guard, do not delete it",
                        e
                    )
                }
                field.isAccessible = true
                return field.getInt(adapter)
            }

            assertEquals(
                "the collapsed picker row is not @layout/spinner_item — a platform " +
                    "spinner item is the control #10 counted",
                R.layout.spinner_item,
                resourceField("mResource")
            )
            assertEquals(
                "the dropdown rows are not @layout/spinner_dropdown_item",
                R.layout.spinner_dropdown_item,
                resourceField("mDropDownResource")
            )
        } finally {
            controller.close()
        }
    }

    /**
     * The two cues the app's own rows have to supply, because replacing the
     * platform background took them away:
     *
     *  * the CARET. A `Spinner` whose background is `@drawable/input_bg` has
     *    lost `spinner_background_material`, which is where the platform drew
     *    the caret — so with no compound drawable the control is visually a
     *    read-only field sitting between two real ones.
     *  * the SELECTED MARK. The dropdown row keeps a `checkMark`, which is a
     *    SHAPE: the current language stays identifiable with no colour vision
     *    and in a greyscale screenshot, per the rule in colors.xml.
     */
    @Test
    fun theCollapsedPickerShowsACaretAndEachDropdownRowShowsASelectedMark() {
        assertEquals(
            "@layout/spinner_item no longer declares the caret",
            "@drawable/ic_chevron_down",
            layoutXml("spinner_item").getAttribute("android:drawableEnd")
        )

        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val adapter = controller.get()
                .findViewById<Spinner>(R.id.stt_language).adapter
            val parent = FrameLayout(themedContext())

            val collapsed = adapter.getView(0, null, parent) as TextView
            assertNotNull(
                "the collapsed picker row has no end drawable, so nothing says this " +
                    "control opens a list",
                collapsed.compoundDrawablesRelative[2]
            )

            val row = adapter.getDropDownView(0, null, parent)
            assertTrue(
                "a dropdown row is a ${row.javaClass.simpleName}, not a " +
                    "CheckedTextView, so it cannot carry a selected mark",
                row is CheckedTextView
            )
            assertNotNull(
                "the dropdown row has no checkMark, which leaves the selected " +
                    "language signalled by the activated tint alone — i.e. by colour",
                (row as CheckedTextView).checkMarkDrawable
            )
        } finally {
            controller.close()
        }
    }

    // --- the destructive action ---------------------------------------------

    /**
     * "Clear token" deletes the stored bearer token. Per the rule in
     * colors.xml, the error colour may not be the only thing saying so — the ✕
     * glyph and a description naming the consequence each do it on their own.
     *
     * The description is asserted to DIFFER from the visible label, which is the
     * half with teeth: a `contentDescription` copied from the label satisfies
     * "has a description" and tells a screen-reader user nothing they did not
     * already have.
     */
    @Test
    fun theDestructiveActionIsIdentifiableWithNoColourVision() {
        val ctx = themedContext()

        assertEquals(
            "the destructive action declares no leading glyph, so losing the red " +
                "ring leaves it looking exactly like Save and Test",
            "@drawable/ic_close",
            controlElements()["stt_clear_token"]!!.getAttribute("android:drawableStart")
        )

        val button = inflate().findViewById<Button>(R.id.stt_clear_token)
        assertNotNull(
            "the inflated destructive action has no leading compound drawable",
            button.compoundDrawablesRelative[0]
        )

        val description = button.contentDescription?.toString()
        assertNotNull("the destructive action has no contentDescription", description)
        assertEquals(
            "the destructive action's description must name what is deleted and " +
                "what happens next",
            ctx.getString(R.string.stt_clear_token_desc),
            description
        )
        assertNotEquals(
            "the description merely repeats the visible label, so it adds no " +
                "information about the consequence",
            button.text.toString(),
            description
        )
        assertEquals(
            "the destructive action's label is not @color/dicta_error",
            ctx.getColor(R.color.dicta_error),
            button.textColors.defaultColor
        )
    }

    /**
     * The elevation lift has to be off on every button here, not only on the
     * two the issue named: the platform's default `stateListAnimator` raises a
     * `Button` on press, and a shadow under a flat outlined control is the most
     * visible "this is a stock widget" tell on the screen. Same reasoning for
     * ALL-CAPS labels, which no other Dicta screen uses.
     */
    @Test
    fun noButtonKeepsThePlatformsElevationAnimatorOrItsCapsLabel() {
        // 🔴 CheckBox extends CompoundButton extends Button, so a bare
        // filterIsInstance<Button>() picks up @id/stt_remote_enabled too and
        // this count would be 4. Excluding CompoundButton keeps the guard about
        // the three pressable buttons it names.
        val buttons = descendants(inflate())
            .filterIsInstance<Button>()
            .filter { it !is android.widget.CompoundButton }
        assertEquals(
            "the screen no longer has the three buttons this guard was written " +
                "for; re-derive it rather than loosening it",
            3,
            buttons.size
        )
        for (button in buttons) {
            assertNull(
                "${nameOf(button.id)} still has a stateListAnimator, so it lifts on " +
                    "press like a stock Material button",
                button.stateListAnimator
            )
        }
        for ((id, element) in controlElements()) {
            if (element.tagName != "Button") continue
            assertEquals(
                "$id does not set textAllCaps=\"false\"",
                "false",
                element.getAttribute("android:textAllCaps")
            )
        }
    }
}
