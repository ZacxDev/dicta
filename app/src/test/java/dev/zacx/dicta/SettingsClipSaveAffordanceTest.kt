package dev.zacx.dicta

import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * The Save affordance beside the SHARED CLIPBOARD fields — and the keyboard's
 * Done key, which does the same thing.
 *
 * ## 🔴 The footgun this closes, which cost a real token twice
 *
 * `@id/stt_save` was the only Save on this screen and it sits in the STT
 * group, hundreds of layout lines ABOVE the clipboard fields. The token field
 * is `textPassword` and the stored value is deliberately never rendered back,
 * so a token typed into it and then abandoned — Back, HOME, a focus change, or
 * the IME eating the scroll gesture that would have reached the button — was
 * **silently discarded**, with the hint still reading "Paste the clipstore
 * bearer token" and nothing distinguishing that from a save that worked. One
 * blind recovery tap during that episode landed on `@id/clip_clear_token` and
 * deleted the stored token instead.
 *
 * It is a human footgun first and an automation one second, which is why the
 * fix is on the screen rather than only in the programmatic door.
 *
 * ## ⚠ Regression or invariant? Both, and they are separated
 *
 *  * [theClipboardGroupHasItsOwnSaveButton] and
 *    [theTokenFieldCommitsOnTheKeyboardsDoneKey] cover a control that did not
 *    exist before this change, so neither is regression coverage in the strict
 *    sense: at the base ref `R.id.clip_save` does not resolve, which is a
 *    compile error and not a guard watched failing. What WAS watched is the
 *    wiring: with the new layout left in place, both listeners were deleted
 *    from `SettingsActivity.onCreate` and **4 of this class's 5 tests failed**.
 *    The fifth, [theTokenFieldDeclaresActionDoneSoTheKeyIsThereToPress],
 *    correctly survived — it is a claim about the layout, which the mutant did
 *    not touch. Full output in the commit message.
 *  * [theSaveButtonsAreTheSAMESaveAndNotTwoHalves] is an **invariant** guard —
 *    nothing has regressed, and it exists because two Save buttons on one
 *    screen is exactly the shape that grows a second, subtly different idea of
 *    what saving means.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsClipSaveAffordanceTest {

    private fun ctx() = RuntimeEnvironment.getApplication()

    private fun clipSettings() = ClipSettings.create(ctx())

    // --- the button -----------------------------------------------------------

    @Test
    fun theClipboardGroupHasItsOwnSaveButton() {
        clipSettings().baseUrl = ClipEndpoints.DEFAULT_BASE_URL
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.findViewById<EditText>(R.id.clip_url)
                .setText("https://clips.example:8443")
            activity.findViewById<Button>(R.id.clip_save).performClick()

            assertEquals(
                "pressing the clipboard group's own Save did not save the URL",
                "https://clips.example:8443",
                clipSettings().baseUrl,
            )
            assertEquals(
                ctx().getString(R.string.stt_saved),
                activity.findViewById<TextView>(R.id.stt_status).text?.toString(),
            )
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun theTokenFieldCommitsOnTheKeyboardsDoneKey() {
        // 🔴 The affordance that needs no scrolling at all, which matters
        // because the IME is what swallows the scroll.
        clipSettings().baseUrl = ClipEndpoints.DEFAULT_BASE_URL
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.findViewById<EditText>(R.id.clip_url)
                .setText("https://clips.example:9000")
            val token = activity.findViewById<EditText>(R.id.clip_token)

            token.onEditorAction(EditorInfo.IME_ACTION_DONE)
            assertEquals(
                "the Done key did not save",
                "https://clips.example:9000",
                clipSettings().baseUrl,
            )

            // 🔴 And the NEGATIVE half: an action that is not Done must NOT
            // save, or the listener is really "save on any editor event" and
            // this test would pass against a wiring nobody intended.
            clipSettings().baseUrl = ClipEndpoints.DEFAULT_BASE_URL
            activity.findViewById<EditText>(R.id.clip_url)
                .setText("https://clips.example:9001")
            token.onEditorAction(EditorInfo.IME_ACTION_SEARCH)
            assertEquals(
                "a non-Done editor action saved, so the listener does not " +
                    "branch on IME_ACTION_DONE at all",
                ClipEndpoints.DEFAULT_BASE_URL,
                clipSettings().baseUrl,
            )
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun theTokenFieldDeclaresActionDoneSoTheKeyIsThereToPress() {
        // 🔴 The listener is useless without the imeOptions: the keyboard shows
        // a newline or a next-field key instead, so IME_ACTION_DONE never
        // arrives and the test above would be the only thing that ever sends
        // it. Read off the layout SOURCE, which is where this attribute is
        // decided — SettingsThemingTest's split, for its stated reason.
        val element = controlElement("clip_token")
        assertEquals(
            "@id/clip_token does not declare imeOptions=actionDone",
            "actionDone",
            element.getAttribute("android:imeOptions"),
        )
    }

    // --- one Save means one save ---------------------------------------------

    @Test
    fun theSaveButtonsAreTheSAMESaveAndNotTwoHalves() {
        // 🔴 Both buttons must write BOTH groups. A clip-only save would be a
        // second opinion about what "Save" does, and the operator would have to
        // know which button they pressed to know what is stored — the
        // ambiguity ClipSettings' kdoc already refuses for "clear the token".
        //
        // Driven by SETTING both groups' URL fields and pressing ONE button,
        // then the other, from a fresh screen each time.
        for (button in listOf(R.id.stt_save, R.id.clip_save)) {
            SttSettings.create(ctx()).baseUrl = SttEndpoints.DEFAULT_BASE_URL
            clipSettings().baseUrl = ClipEndpoints.DEFAULT_BASE_URL
            val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
            try {
                val activity = controller.get()
                activity.findViewById<EditText>(R.id.stt_url)
                    .setText("http://10.42.0.10:9118")
                activity.findViewById<EditText>(R.id.clip_url)
                    .setText("http://10.42.0.10:9139")
                activity.findViewById<Button>(button).performClick()

                val name = ctx().resources.getResourceEntryName(button)
                assertEquals(
                    "$name did not save the transcription URL, so the two Save " +
                        "buttons do different things",
                    "http://10.42.0.10:9118",
                    SttSettings.create(ctx()).baseUrl,
                )
                assertEquals(
                    "$name did not save the clip URL",
                    "http://10.42.0.10:9139",
                    clipSettings().baseUrl,
                )
            } finally {
                controller.destroy()
            }
        }
    }

    @Test
    fun theClipboardSaveAlsoREFUSESAnInvalidUrlAndSavesNothing() {
        // 🔴 The validation is `save()`'s, so it must apply through the new
        // door too — otherwise the button beside the field is the one way to
        // get past the check that protects it. ClipEndpoints.rejectionFor is
        // the single predicate; this asserts the new control goes through it.
        clipSettings().baseUrl = "http://10.42.0.10:8139"
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.findViewById<EditText>(R.id.clip_url)
                .setText("http://10.42.0.10:8139/v1/clips")
            activity.findViewById<Button>(R.id.clip_save).performClick()

            assertEquals(
                "the clipboard Save wrote a URL the screen refuses",
                "http://10.42.0.10:8139",
                clipSettings().baseUrl,
            )
            val status = activity.findViewById<TextView>(R.id.stt_status).text?.toString()
            assertTrue(
                "the refusal was not reported: <$status>",
                status.orEmpty().contains("was not saved"),
            )
        } finally {
            controller.destroy()
        }
    }

    // --- the layout source ----------------------------------------------------

    private fun controlElement(id: String): Element {
        val candidates = listOf(
            File("src/main/res/layout/activity_settings.xml"),
            File("app/src/main/res/layout/activity_settings.xml"),
            File("../app/src/main/res/layout/activity_settings.xml"),
        )
        val file = candidates.firstOrNull { it.isFile }
        assertNotNull(
            "cannot find activity_settings.xml from ${File(".").absolutePath} — " +
                "refusing to report a pass on a file that was never read",
            file,
        )
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
        val root = factory.newDocumentBuilder().parse(file!!).documentElement

        val found = mutableListOf<Element>()
        fun walk(n: Node) {
            if (n is Element &&
                n.getAttribute("android:id").substringAfterLast('/') == id
            ) {
                found += n
            }
            val children = n.childNodes
            for (i in 0 until children.length) walk(children.item(i))
        }
        walk(root)
        assertEquals("expected exactly one @id/$id in the layout", 1, found.size)
        return found.first()
    }
}
