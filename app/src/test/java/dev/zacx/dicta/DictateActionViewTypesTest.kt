package dev.zacx.dicta

import android.view.LayoutInflater
import android.view.View
import android.view.ContextThemeWrapper
import android.widget.Button
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
 * REGRESSION test for the ClassCastException class of bug that this PR exists
 * because of: `findViewById<Button>(R.id.copy_button)` on an `ImageButton`
 * throws at runtime, because `ImageButton` is an `ImageView`, not a `Button`.
 *
 * ## What was actually observed about red-before-green
 *
 * This test cannot be run against the pre-change tree: at the parent commit
 * `activity_dictate.xml` has no icon buttons at all, and the test file does not
 * exist there either, so "red at base" would be a compile error rather than a
 * guard watched going red. What WAS observed is a hand-rolled partial revert of
 * the shipping change on this tree — `copy_button` changed back from
 * `ImageButton` to `Button` in `activity_dictate.xml`, which reproduces exactly
 * the pre-change type mismatch:
 *
 *   * `iconActionsAreNotButtons` FAILED ("copy_button is a android.widget.Button,
 *     which IS a Button")
 *   * `activityStartsWithoutAClassCastException` FAILED with the real
 *     `ClassCastException` out of `DictateActivity.onCreate`
 *   * unmutated control: all three tests green
 *
 * That matrix is in the PR body. It is a mutation result, not a checkout of the
 * base commit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictateActionViewTypesTest {

    /**
     * The ledger of the icon-only actions in the bottom row. Asserting a
     * RELATIONSHIP rather than one side: [theLedgerCoversEveryIdInTheActionRow]
     * fails if the action row grows or shrinks without this list being updated,
     * so a new action cannot silently escape type coverage.
     *
     * The types the activity casts these to are NOT re-asserted structurally —
     * [activityStartsWithoutAClassCastException] runs the real `onCreate`, so
     * the real casts execute, which is strictly stronger than inspecting the
     * inflated classes.
     */
    private val iconActions = listOf(
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

    /**
     * The half that makes `findViewById<Button>` throw: the icon actions are
     * specifically NOT Buttons. Stated as the negative, because that is the
     * property the broken cast depends on.
     */
    @Test
    fun iconActionsAreNotButtons() {
        val root = inflate()
        for (id in iconActions) {
            val view = root.findViewById<View>(id)
            assertNotNull("no view with id ${nameOf(id)} in activity_dictate.xml", view)
            assertFalse(
                "${nameOf(id)} is a ${view.javaClass.name}, which IS a Button — " +
                    "either the layout or DictateActivity's ImageButton cast is wrong",
                view is Button
            )
        }
    }

    /**
     * Seam guard: the action row's membership is asserted as a set, so adding a
     * fifth action (or deleting one) without updating [iconActions] fails here
     * rather than silently escaping type coverage.
     *
     * Observed working, not assumed: adding `screenshot_button` to the layout
     * for this PR turned this test red with "the action row's children changed"
     * until [iconActions] was updated. That is the reachability proof — the
     * guard fires on a row that genuinely grew.
     */
    @Test
    fun theLedgerCoversEveryIdInTheActionRow() {
        val root = inflate()
        val row = findActionRow(root)
        val actualIds = (0 until row.childCount).map { row.getChildAt(it).id }.toSet()
        assertEquals(
            "the action row's children changed; update iconActions",
            iconActions.toSet(),
            actualIds
        )
    }

    /**
     * Behavioural half: actually run [DictateActivity.onCreate], so the real
     * `findViewById<ImageButton>` casts execute. A structural check can
     * type-check past a wrong cast; this cannot.
     */
    @Test
    fun activityStartsWithoutAClassCastException() {
        val controller = Robolectric.buildActivity(DictateActivity::class.java).setup()
        try {
            val activity = controller.get()
            assertNotNull(activity)
            assertFalse(activity.isFinishing)
            // And the click listeners are wired on the real views.
            for (id in iconActions) {
                assertTrue(
                    "${nameOf(id)} has no click listener",
                    activity.findViewById<View>(id).hasOnClickListeners()
                )
            }
        } finally {
            controller.close()
        }
    }

    private fun findActionRow(root: View): android.view.ViewGroup {
        val anyIcon = root.findViewById<View>(R.id.copy_button)
        return anyIcon.parent as android.view.ViewGroup
    }

    private fun nameOf(id: Int): String =
        RuntimeEnvironment.getApplication().resources.getResourceEntryName(id)
}
