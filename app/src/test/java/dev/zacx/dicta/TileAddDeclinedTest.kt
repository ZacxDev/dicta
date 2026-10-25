package dev.zacx.dicta

import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.widget.Button
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.Executor
import java.util.function.Consumer

/**
 * 🔴 **Declining the add-tile prompt must not be reported as a success.**
 *
 * ## The defect, as measured on `f54ea19`
 *
 * `MainActivity.promptAddTile` called
 * `StatusBarManager.requestAddTileService(...)` and its result callback was
 * `{ _ -> … }` — the parameter discarded, one unconditional
 * `R.string.tile_added` toast. So a user who tapped **Add tile**, saw the
 * system dialog and pressed *no* was told *"Tile added — find it in your Quick
 * Settings panel."* and sent looking for a tile that was never added.
 * `R.string.tile_add_declined` ("Tile not added.") was declared in
 * `strings.xml` and referenced from **nowhere** under `app/src/main/java` — the
 * orphan is the fingerprint of the discarded branch.
 *
 * ## Why this file is written against the OLD symbols only
 *
 * 🔴 A test that cannot be watched fail proves nothing, and a test naming a
 * class the fix introduces cannot fail on pre-change code — it fails to
 * *compile*, which is not the same claim. So this file deliberately touches
 * nothing the fix adds: no `TileAddPrompt`, no `TileAddOutcome`, and only the
 * two string resources that already existed. It compiles unmodified against
 * `f54ea19` and goes **red** there on the assertion, which is the matrix this
 * test exists to report. The exhaustive nine-code mapping lives in
 * [TileAddPromptTest], which is new-code coverage and says so.
 *
 * ## How the platform callback is reached off-device
 *
 * `requestAddTileService`'s `Consumer` is only ever invoked by the system, and
 * Robolectric 4.13's `ShadowStatusBarManager` does **not** shadow that method
 * (verified by `javap` over `shadows-framework-4.13.jar`: it carries
 * `disable`/`disable2`/`setNavBarMode` and nothing else). [CapturingStatusBar]
 * below shadows it for the length of this test and keeps the callback so the
 * test can invoke it with a chosen result code.
 *
 * ## 🔴 What else could produce the observable
 *
 * The decline assertion's observable is "the latest toast is not
 * `tile_added`". **An absent toast produces that too** — a shadow that never
 * captured the callback, a click that never reached `promptAddTile`, a
 * `SDK_INT` below `TIRAMISU` taking the manual-instructions branch. Each would
 * make this file red at `f54ea19` for a reason that has nothing to do with the
 * defect, and would leave it just as red after a correct fix.
 *
 * [theHarnessCanSeeAToastAtAll] is therefore the positive control and is
 * asserted **in the same file, through the same plumbing**: feeding
 * `TILE_ADD_REQUEST_RESULT_TILE_ADDED` must produce a non-null toast reading
 * `tile_added`. That arm passes on pre-change code *and* after the fix, which
 * is exactly what makes it a control: it proves the click path, the shadow, the
 * captured callback and `ShadowToast` all work, so the decline arm's redness is
 * attributable to the mapping and to nothing else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [CapturingStatusBar::class])
class TileAddDeclinedTest {

    private lateinit var activity: MainActivity

    @Before
    fun setUp() {
        CapturingStatusBar.lastCallback = null
        ShadowToast.reset()
        activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
    }

    @After
    fun tearDown() {
        CapturingStatusBar.lastCallback = null
    }

    /**
     * Taps **Add tile** and hands the captured callback one result code.
     *
     * @return the text of the resulting toast, or null if there was none.
     */
    private fun resultToast(code: Int): String? {
        activity.findViewById<Button>(R.id.add_tile_button).performClick()
        val callback = CapturingStatusBar.lastCallback
        assertNotNull(
            "the Add tile button did not reach requestAddTileService, so no " +
                "result callback was captured. Nothing below this point is a " +
                "claim about the result mapping — refusing to report on an " +
                "unexercised path.",
            callback,
        )
        ShadowToast.reset()
        callback!!.accept(code)
        return ShadowToast.getTextOfLatestToast()
    }

    private fun string(res: Int): String = RuntimeEnvironment.getApplication().getString(res)

    /**
     * 🔴 The regression assertion. Red at `f54ea19`, green at HEAD.
     *
     * Both halves are asserted, because they fail for different reasons and
     * only one of them is the bug: the toast must **not** be the success
     * string (what the defect did), and it must **be** the decline string
     * (which is what makes `tile_add_declined` a used resource rather than an
     * orphan the next author deletes as dead).
     */
    @Test
    fun decliningThePromptIsNotReportedAsAdded() {
        val shown = resultToast(StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED)

        assertNotEquals(
            "the user DECLINED the system add-tile prompt and was told the " +
                "tile was added. This is issue #14's defect: the result " +
                "callback discarded its argument.",
            string(R.string.tile_added),
            shown,
        )
        assertEquals(
            "TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED (0) must show " +
                "tile_add_declined. Asserted as the whole string rather than " +
                "a keyword: a guard on a word is walkable by rewording, and " +
                "this resource's only job is to be shown on exactly this " +
                "result.",
            string(R.string.tile_add_declined),
            shown,
        )
    }

    /**
     * 🔴 The positive control for the test above — see this class's kdoc.
     * Passes before and after the fix by design; it is here to prove the
     * instrument can observe a toast, not to pin behaviour.
     */
    @Test
    fun theHarnessCanSeeAToastAtAll() {
        val shown = resultToast(StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED)

        assertNotNull(
            "no toast was observed for TILE_ADD_REQUEST_RESULT_TILE_ADDED. " +
                "Then an absent toast — not a corrected one — is what the " +
                "decline assertion above is measuring, and its red is about " +
                "this harness rather than about the mapping.",
            shown,
        )
        assertEquals(
            "a successful add must still report success; the fix must not " +
                "have been bought by pessimising every outcome.",
            string(R.string.tile_added),
            shown,
        )
    }
}

/**
 * Captures the `Consumer` that `MainActivity` hands to
 * `requestAddTileService`, which the platform would otherwise be the only
 * caller of. Scoped to the tests that name it in `@Config(shadows = …)`.
 */
@Implements(StatusBarManager::class)
class CapturingStatusBar {

    companion object {
        /** The most recent callback, or null if the method was never called. */
        @JvmStatic
        var lastCallback: Consumer<Int>? = null
    }

    @Implementation
    protected fun requestAddTileService(
        component: ComponentName,
        label: CharSequence,
        icon: Icon,
        executor: Executor,
        callback: Consumer<Int>,
    ) {
        lastCallback = callback
    }
}
