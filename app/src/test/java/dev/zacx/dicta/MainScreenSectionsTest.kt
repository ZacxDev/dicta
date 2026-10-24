package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 🔴 The setup screen's **section headings must not contradict the rows under
 * them.**
 *
 * ## The defect this exists to make loud
 *
 * `@string/tile_row` was corrected to say the Quick Settings tile "switches the
 * bubble on or off" — i.e. that it is *not* a way to start a dictation — while
 * it was still the first row under a heading reading **"Triggers (pick any)"**.
 * The copy denied in words what the heading two lines above asserted, and the
 * commit that corrected the copy claimed it had done so "rather than leaving
 * the heading to imply otherwise". The heading was left. A screen whose heading
 * and whose first row disagree is the same class of defect as two surfaces
 * rendering one flag from two predicates: whichever the user reads first is the
 * one they act on.
 *
 * ## Why a whole-string pin and not a keyword check
 *
 * The artifact under test is **prose**, and a guard on words is walkable by
 * rewording: `assertFalse(heading.contains("trigger"))` passes for "Ways to
 * start dictating", which makes exactly the same false promise. So the two
 * strings that have to agree are pinned **whole and normalised**. A cosmetic
 * reword therefore fails this test — that is the price of a machine-readable
 * claim, and it is paid deliberately: changing either string is a decision
 * about what the screen asserts, which is the thing that went wrong twice.
 *
 * ## Why the grouping is parsed and not grepped
 *
 * "Which heading is this row under" is a fact about **position in the layout
 * tree**, not about text near it in a file. The layout is parsed and the rows
 * are attributed to the heading that precedes them, so moving a row between
 * groups — in either direction — fails. [theGrouperActuallyGroups] is the
 * control, without which the ledger below is a claim about a helper.
 *
 * 🔴 **What this cannot observe.** Nothing about how the screen *renders*:
 * Robolectric cannot measure text, so there is no claim here about wrapping,
 * truncation, ordering as seen by a user, or what a screen reader announces.
 * Those are device questions and no device has been attached to this project.
 * This is a consistency check between two files in this repo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainScreenSectionsTest {

    @Test
    fun theRowsUnderEachHeadingAreTheRowsThatHeadingClaims() {
        val groups = groupsIn(read(*LAYOUT, what = "activity_main.xml"))

        assertTrue(
            "the layout parse produced no section groups at all, so the " +
                "assertion below would compare an absent list against an " +
                "absent list. Found: ${groups.keys}",
            groups.size >= 2,
        )
        assertEquals(
            "the rows under @string/section_triggers changed. This group's " +
                "heading has to be true of EVERY row in it, and the rows are " +
                "not all triggers: @string/tile_row is the bubble's on/off " +
                "switch and starts nothing, which is why the heading reads " +
                "\"From anywhere\" rather than naming triggers. If you add a " +
                "row here, check the heading still describes it — and if you " +
                "move one out, check the heading has not become narrower than " +
                "it needs to be. Found: ${groups["@string/section_triggers"]}",
            listOf("@string/tile_row", "@string/bubble_row", "@string/notif_row"),
            groups["@string/section_triggers"],
        )
    }

    /**
     * The prose half: the two strings that have to agree, pinned whole.
     *
     * The heading must not promise a way to START a dictation, because the
     * first row under it is the tile and the tile opens nothing. Both values are
     * asserted here rather than one, because the contradiction is a
     * *relationship*: correcting either string on its own is how this screen
     * came to say two different things.
     */
    @Test
    fun theHeadingAndTheTileRowStillSayTheSameThing() {
        val strings = stringsIn(read(*STRINGS, what = "strings.xml"))

        assertEquals(
            "@string/section_triggers changed. It labels a group whose first " +
                "row is the Quick Settings tile, and the tile switches the " +
                "floating bubble on and off — it starts no dictation. A " +
                "heading that calls the rows beneath it triggers therefore " +
                "contradicts @string/tile_row directly below it, which is the " +
                "state this screen shipped in once. If the new wording keeps " +
                "that true, update this expectation deliberately.",
            "From anywhere",
            strings["section_triggers"],
        )
        assertEquals(
            "@string/tile_row changed. It is the sentence that tells the user " +
                "the tile is a switch and not a way in, and it is what the " +
                "heading above it has to stay consistent with. Its previous " +
                "wording — \"swipe down from any app and tap Dicta\" — " +
                "promised a dictation the tile does not start.",
            "Quick Settings tile — swipe down from any app to switch the bubble on or off.",
            strings["tile_row"],
        )
    }

    /**
     * 🔴 Control. Until this passes, [theRowsUnderEachHeadingAreTheRowsThatHeadingClaims]
     * is a claim about a helper rather than about the layout.
     *
     * Both directions over an input whose answer is known: the grouper must
     * attribute a row to the heading that precedes it and to no other; it must
     * see a row **nested** inside a panel rather than only a direct child,
     * which is how every row in the real layout is written; and it must ignore
     * a container that is not a row panel, which is how `@id/settings_slot`
     * stays out of the triggers group.
     */
    @Test
    fun theGrouperActuallyGroups() {
        val synthetic = """
            <?xml version="1.0" encoding="utf-8"?>
            <ScrollView xmlns:android="http://schemas.android.com/apk/res/android">
                <LinearLayout android:orientation="vertical">
                    <LinearLayout android:background="@drawable/row_bg">
                        <TextView android:text="@string/not_in_a_section" />
                    </LinearLayout>
                    <TextView style="@style/Text.Dicta.SectionHeading"
                        android:text="@string/first_heading" />
                    <LinearLayout android:background="@drawable/row_bg">
                        <TextView android:text="@string/nested_row" />
                        <Button android:text="@string/a_button" />
                    </LinearLayout>
                    <FrameLayout>
                        <TextView android:text="@string/not_a_row_panel" />
                    </FrameLayout>
                    <TextView style="@style/Text.Dicta.SectionHeading"
                        android:text="@string/second_heading" />
                    <LinearLayout android:background="@drawable/row_bg">
                        <TextView android:text="@string/second_row" />
                    </LinearLayout>
                </LinearLayout>
            </ScrollView>
        """.trimIndent()

        val groups = groupsIn(synthetic)

        assertEquals(
            "the grouper did not find exactly the two headings in the fixture, " +
                "so it is not keyed on the section-heading style at all",
            listOf("@string/first_heading", "@string/second_heading"),
            groups.keys.toList(),
        )
        assertEquals(
            "the grouper cannot see a row NESTED inside a container, which is " +
                "how every row in the real layout is written — so the ledger " +
                "would report an empty group for a section full of rows",
            listOf("@string/nested_row"),
            groups["@string/first_heading"],
        )
        assertEquals(
            "a row after the SECOND heading was attributed to the first, so " +
                "the grouper is accumulating rather than grouping and the " +
                "ledger could not notice a row moving between sections",
            listOf("@string/second_row"),
            groups["@string/second_heading"],
        )
        val everything = groups.values.flatten()
        assertTrue(
            "the grouper counted a Button's android:text as a row. Buttons are " +
                "controls, not copy a section heading has to be true of: " +
                "$everything",
            !everything.contains("@string/a_button"),
        )
        assertTrue(
            "the grouper counted a container that is NOT a row panel. That is " +
                "what keeps @id/settings_slot out of the triggers group, so " +
                "without it the ledger would churn on an unrelated slot: " +
                "$everything",
            !everything.contains("@string/not_a_row_panel"),
        )
        assertTrue(
            "the grouper attributed a row that precedes every heading to a " +
                "section, so the masthead and @string/intro would be in one: " +
                "$everything",
            !everything.contains("@string/not_in_a_section"),
        )
    }

    // --- plumbing ------------------------------------------------------------

    /**
     * `@string/…` of every `TextView` inside a **row panel**, keyed by the
     * `@string/…` of the section heading that precedes it, in document order.
     *
     * A heading is identified by the shared `@style/Text.Dicta.SectionHeading`
     * and a row panel by the shared `@drawable/row_bg` background — the two
     * conventions this screen is built from, rather than position or text.
     * Anything before the first heading belongs to no section and is dropped.
     *
     * 🔴 The row-panel filter is why `@id/settings_slot` is not in any group.
     * It is a `FrameLayout` with no `row_bg`, deliberately placed after the
     * triggers it configures and before the call to action (see the layout's
     * own header), so it is not copy that a section heading has to be true of.
     * [theGrouperActuallyGroups] pins that the filter excludes it.
     */
    private fun groupsIn(layout: String): LinkedHashMap<String, List<String>> {
        val container = elementChildren(parse(layout).documentElement)
            .firstOrNull { it.tagName == "LinearLayout" }
        assertTrue(
            "activity_main.xml has no LinearLayout child of its root, so there " +
                "is nothing to walk and every group would come back empty",
            container != null,
        )

        val groups = LinkedHashMap<String, MutableList<String>>()
        var current: MutableList<String>? = null
        for (child in elementChildren(container!!)) {
            val heading = child.getAttribute("style") == SECTION_HEADING_STYLE
            if (heading) {
                current = mutableListOf()
                groups[child.getAttribute("android:text")] = current
            } else if (child.getAttribute("android:background") == ROW_PANEL_BACKGROUND) {
                current?.addAll(rowTextsIn(child))
            }
        }
        return LinkedHashMap(groups.mapValues { (_, v) -> v.toList() })
    }

    /** `android:text` of [element] and every `TextView` inside it. */
    private fun rowTextsIn(element: Element): List<String> {
        val out = mutableListOf<String>()
        if (element.tagName == "TextView") {
            element.getAttribute("android:text").takeIf { it.isNotEmpty() }?.let(out::add)
        }
        val nested = element.getElementsByTagName("TextView")
        for (i in 0 until nested.length) {
            (nested.item(i) as Element).getAttribute("android:text")
                .takeIf { it.isNotEmpty() }
                ?.let(out::add)
        }
        return out
    }

    /** `name` → value for every `<string>` in a values resource file. */
    private fun stringsIn(xml: String): Map<String, String> {
        val nodes = parse(xml).getElementsByTagName("string")
        val out = LinkedHashMap<String, String>()
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as Element
            out[e.getAttribute("name")] = e.textContent.replace(Regex("\\s+"), " ").trim()
        }
        assertTrue(
            "no <string> elements were found, so every value compared below " +
                "would be null and the assertions would be about an empty map",
            out.size > 10,
        )
        return out
    }

    private fun elementChildren(parent: Element): List<Element> {
        val out = mutableListOf<Element>()
        val kids = parent.childNodes
        for (i in 0 until kids.length) {
            val n = kids.item(i)
            if (n.nodeType == Node.ELEMENT_NODE) out.add(n as Element)
        }
        return out
    }

    /**
     * Namespace-**unaware**: the attribute names in these files are compared as
     * the literal `android:text` / `android:name` they are written as, and the
     * synthetic fixtures above are fragments that would not resolve a prefix.
     */
    private fun parse(xml: String) =
        DocumentBuilderFactory.newInstance()
            .also { it.isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray()))

    private fun read(vararg paths: String, what: String): String {
        val found = paths.map { File(it) }.firstOrNull { it.exists() }
        assertTrue(
            "could not locate $what from ${File(".").absolutePath} — refusing " +
                "to report a pass on a file that was never read",
            found != null,
        )
        return found!!.readText()
    }

    private companion object {
        const val SECTION_HEADING_STYLE = "@style/Text.Dicta.SectionHeading"
        const val ROW_PANEL_BACKGROUND = "@drawable/row_bg"

        val LAYOUT = arrayOf(
            "app/src/main/res/layout/activity_main.xml",
            "src/main/res/layout/activity_main.xml",
            "../app/src/main/res/layout/activity_main.xml",
        )

        val STRINGS = arrayOf(
            "app/src/main/res/values/strings.xml",
            "src/main/res/values/strings.xml",
            "../app/src/main/res/values/strings.xml",
        )
    }
}
