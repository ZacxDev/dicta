package dev.zacx.dicta

import android.app.Activity
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every floating overlay activity's window is capped at
 * [R.fraction.overlay_width_fraction] of the display width.
 *
 * ## 🔴 What is measured: the window ATTRIBUTE, not a rendered pixel
 *
 * Robolectric has no window manager and lays nothing out for real, so this
 * reads `activity.window.attributes.width` — the value Dicta *asked* for. It is
 * evidence that the request was made with the right number, and nothing more:
 * it cannot see a platform that ignores the request, a decor inset, or what the
 * card looks like. On-device width is verified on hardware and reported
 * separately.
 *
 * That gap is not academic — it is exactly how the defect below shipped.
 *
 * ## 🔴 The defect this file is a regression test for
 *
 * `@style/Theme.Dicta.Overlay` makes a window floating, i.e. `WRAP_CONTENT`,
 * and nothing in the theme can cap it (`windowFixedWidthMinor`/`Major` are
 * private platform attributes). So `match_parent` in the layout matches the
 * WINDOW, and the window's width is whatever its widest `wrap_content` child
 * wants.
 *
 *  * **`SnipPickerActivity` (issue #38, shipped in 0.4.0) never applied the
 *    cap.** On a Galaxy S21 the picker rendered at roughly a FIFTH of the
 *    screen width — the width of its one-word `SNIPPETS` heading — truncating
 *    every row label to about six characters. The [snipPicker…] cases below are
 *    **real regression coverage**: they are red at `4a0ad3e`, the release
 *    commit, and green here. The matrix and the observed failure message are in
 *    the pull request.
 *  * `DictateActivity`'s half is **not** a regression test and is not labelled
 *    as one. This file could not be run against the tree that predates the
 *    overlay redesign — it did not exist there, and neither did
 *    `R.fraction.overlay_width_fraction` or `R.id.dictate_card`, so "red at
 *    base" would be a compile error rather than a guard watched going red. What
 *    WAS observed for it is a hand-rolled partial revert: deleting the sizing
 *    call from `onCreate`.
 *
 * ## Why four configurations each
 *
 * A layout verified at one size is a claim about one size. These assertions are
 * also the end-to-end check on the 0.92 / 0.62 / 0.70 / 0.50 literals — they
 * read `attrs.width / displayWidth` off a real activity, so there is
 * deliberately no separate resource-value test of the same four numbers.
 *
 * 🔴 Every expected value is distinct from `MATCH_PARENT` (-1), from
 * `WRAP_CONTENT` (-2) and from the display width itself (1134≠1233,
 * 1700≠2742, 1120≠1600, 1280≠2560), so no assertion here can pass by
 * coincidence — [theExpectedWidthsCannotBeHitByAccident] pins that property
 * rather than leaving it to arithmetic in a comment.
 *
 * The set of activities that must appear in this file is not a judgement call:
 * `FloatingOverlaySeamLedgerTest` derives it from the manifest's
 * `android:theme` and fails when it grows or shrinks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FloatingWindowSizeTest {

    // --- the transcription card ---------------------------------------------

    @Test
    @Config(qualifiers = "w411dp-h914dp-port-xxhdpi")
    fun dictateCardPhonePortrait() = assertCappedAt(DictateActivity::class.java, 0.92f)

    @Test
    @Config(qualifiers = "w914dp-h411dp-land-xxhdpi")
    fun dictateCardPhoneLandscape() = assertCappedAt(DictateActivity::class.java, 0.62f)

    @Test
    @Config(qualifiers = "sw600dp-w800dp-h1200dp-port-xhdpi")
    fun dictateCardTabletPortrait() = assertCappedAt(DictateActivity::class.java, 0.70f)

    @Test
    @Config(qualifiers = "sw600dp-w1280dp-h800dp-land-xhdpi")
    fun dictateCardTabletLandscape() = assertCappedAt(DictateActivity::class.java, 0.50f)

    // --- the snippet picker: the surface that shipped UNSIZED ----------------

    @Test
    @Config(qualifiers = "w411dp-h914dp-port-xxhdpi")
    fun snipPickerPhonePortrait() = assertCappedAt(SnipPickerActivity::class.java, 0.92f)

    @Test
    @Config(qualifiers = "w914dp-h411dp-land-xxhdpi")
    fun snipPickerPhoneLandscape() = assertCappedAt(SnipPickerActivity::class.java, 0.62f)

    @Test
    @Config(qualifiers = "sw600dp-w800dp-h1200dp-port-xhdpi")
    fun snipPickerTabletPortrait() = assertCappedAt(SnipPickerActivity::class.java, 0.70f)

    @Test
    @Config(qualifiers = "sw600dp-w1280dp-h800dp-land-xhdpi")
    fun snipPickerTabletLandscape() = assertCappedAt(SnipPickerActivity::class.java, 0.50f)

    private fun <A : Activity> assertCappedAt(clazz: Class<A>, expectedFraction: Float) {
        val controller = Robolectric.buildActivity(clazz).setup()
        try {
            val activity = controller.get()
            val name = clazz.simpleName
            val displayWidth = activity.resources.displayMetrics.widthPixels
            val attrs = activity.window.attributes

            assertTrue("display width came back as $displayWidth", displayWidth > 0)

            // Not WRAP_CONTENT / MATCH_PARENT — an explicit pixel cap. Both
            // sentinels are negative, so `> 0` excludes them; they are named
            // individually because the failure messages are what a maintainer
            // reads, and "it is still WRAP_CONTENT" is the diagnosis.
            assertNotEquals(
                "$name's window width is WRAP_CONTENT, so the window was never " +
                    "sized and its width collapses to the widest wrap_content " +
                    "child — a one-word heading, in the picker's case. This is " +
                    "the 0.4.0 defect verbatim.",
                ViewGroup.LayoutParams.WRAP_CONTENT,
                attrs.width,
            )
            assertNotEquals(
                "$name's window width is MATCH_PARENT, which on a floating " +
                    "window is not the cap this file is about",
                ViewGroup.LayoutParams.MATCH_PARENT,
                attrs.width,
            )
            assertTrue(
                "$name's window width is ${attrs.width}; the window was never " +
                    "sized, so the decor is still measured at " +
                    "AT_MOST(display) and the card's width is decided by its " +
                    "content rather than by the display",
                attrs.width > 0
            )
            assertTrue(
                "$name's window width ${attrs.width} is not narrower than the " +
                    "$displayWidth px display — the card still spans edge to edge",
                attrs.width < displayWidth
            )
            assertEquals(
                "$name's window/display ratio",
                expectedFraction,
                attrs.width.toFloat() / displayWidth,
                0.005f
            )

            // Height stays content-driven: a fixed height would either clip the
            // card or leave it tall and empty.
            assertEquals(
                "$name's window height is not WRAP_CONTENT",
                WindowManager.LayoutParams.WRAP_CONTENT,
                attrs.height,
            )

            // Centred, so the margin is on all sides rather than one.
            assertEquals("$name's window gravity", Gravity.CENTER, attrs.gravity)
        } finally {
            controller.close()
        }
    }

    /**
     * 🔴 The control on the assertions above: each expected width must be a
     * number nothing else in the computation can produce.
     *
     * An assertion that happened to expect `-1`, `-2` or the display width
     * itself would pass on an unsized window — i.e. it would be green for the
     * exact defect. This checks the four (qualifier, fraction) pairs chosen
     * above actually avoid all three, so the choice is pinned rather than
     * asserted in a comment.
     */
    @Test
    fun theExpectedWidthsCannotBeHitByAccident() {
        val cases = listOf(
            Triple("phone portrait", 1233, 0.92f),
            Triple("phone landscape", 2742, 0.62f),
            Triple("tablet portrait", 1600, 0.70f),
            Triple("tablet landscape", 2560, 0.50f),
        )
        for ((label, displayWidth, fraction) in cases) {
            val expected = (displayWidth * fraction).toInt()
            assertNotEquals(
                "$label's expected width equals MATCH_PARENT, so the " +
                    "assertion would pass on a window that was never sized",
                ViewGroup.LayoutParams.MATCH_PARENT,
                expected,
            )
            assertNotEquals(
                "$label's expected width equals WRAP_CONTENT, so the " +
                    "assertion would pass on a window that was never sized",
                ViewGroup.LayoutParams.WRAP_CONTENT,
                expected,
            )
            assertNotEquals(
                "$label's expected width equals the display width, so the " +
                    "assertion could not tell a capped window from one that " +
                    "clamped to the whole screen",
                displayWidth,
                expected,
            )
            assertTrue("$label's expected width is not positive", expected > 0)
        }
    }

    /**
     * The card must be inside a scroll container, or a landscape phone clips the
     * action row off the bottom of a WRAP_CONTENT dialog.
     */
    @Test
    @Config(qualifiers = "w914dp-h411dp-land-xxhdpi")
    fun theCardScrollsRatherThanClipping() {
        val controller = Robolectric.buildActivity(DictateActivity::class.java).setup()
        try {
            val card = controller.get().findViewById<android.view.View>(R.id.dictate_card)
            var parent = card.parent
            var found = false
            while (parent is android.view.View) {
                if (parent is android.widget.ScrollView) {
                    found = true
                    break
                }
                parent = parent.getParent()
            }
            assertTrue(
                "the dictate card has no ScrollView ancestor; on a short " +
                    "landscape window the bottom action row would be clipped",
                found
            )
        } finally {
            controller.close()
        }
    }
}
