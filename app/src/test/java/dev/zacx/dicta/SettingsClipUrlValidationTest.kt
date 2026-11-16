package dev.zacx.dicta

import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Saving the clip store URL: what the screen refuses, and what it says.
 *
 * ## 🔴 Why this field earns a test of its own
 *
 * It was **free text saved with no validation at all**, and it is the field
 * the capture-loss defect was reached through. Point it at anything that is
 * not the clip store and every `POST /v1/clips` answers 404 — which
 * `ClipHttp.PERMANENT_STATUSES` held, so [ClipFlusher] deleted each queued
 * capture from the only place it existed, one flush per minute, while the
 * capture toast promised *"it will sync when Dicta is on the mesh"*.
 *
 * 🔴 **This screen is NOT the fix** and the test says so — see
 * [ClipEndpointsTest.theValidatorCannotCatchAVALIDUrlForTheWRONGService]. The
 * scenario that was actually measured (the transcription base URL pasted into
 * this field) is a well-formed URL that no syntactic check can reject. What
 * this closes is the detectable half, and what it must never do is silently
 * normalise: a field that quietly rewrites what the operator typed is how they
 * come to believe a setting says something it does not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsClipUrlValidationTest {

    private fun ctx() = RuntimeEnvironment.getApplication()

    private fun clipSettings() = ClipSettings.create(ctx())

    /** Drives the real screen: type [url], press Save, read the status line. */
    private fun save(url: String): String {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.findViewById<EditText>(R.id.clip_url).setText(url)
            activity.findViewById<Button>(R.id.stt_save).performClick()
            return activity.findViewById<TextView>(R.id.stt_status).text?.toString().orEmpty()
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun aGoodUrlIsSavedAndTheScreenSaysSo() {
        // 🔴 POSITIVE CONTROL, first. Every case below asserts that a save was
        // REFUSED, and a Save button wired to nothing — or a validator that
        // rejects everything — satisfies all of them while the screen is
        // simply broken.
        val status = save("http://10.42.0.10:8139")
        assertEquals(ctx().getString(R.string.stt_saved), status)
        assertEquals("http://10.42.0.10:8139", clipSettings().baseUrl)
    }

    @Test
    fun theFullENDPOINTIsREFUSEDAndNothingIsSaved() {
        // The most likely wrong paste: the URL out of a curl line. Accepting
        // it yields `…/v1/clips/v1/clips`, a 404 on every POST.
        //
        // 🔴 Two claims, and the second is the one with teeth: the screen
        // says so, AND the preference is unchanged. A rejection that printed
        // a message and saved anyway would be worse than no validation, since
        // the operator would believe they had been stopped.
        clipSettings().baseUrl = "http://10.42.0.10:8139"
        val status = save("http://10.42.0.10:8139/v1/clips")

        assertNotEquals(
            "the screen reported a successful save for a URL it refused",
            ctx().getString(R.string.stt_saved),
            status,
        )
        assertTrue(
            "the rejection does not name what is wrong, so the operator " +
                "retypes at random: [$status]",
            status.contains(ClipEndpoints.CLIPS_PATH),
        )
        assertEquals(
            "🔴 the refused URL was stored anyway",
            "http://10.42.0.10:8139",
            clipSettings().baseUrl,
        )
    }

    @Test
    fun aRejectedClipUrlDoesNotHalfSaveTheRestOfTheScreen() {
        // 🔴 The validation runs BEFORE any preference is written, which is
        // what lets @string/clip_url_invalid honestly say nothing was saved.
        // Without that ordering the transcription half of this screen would
        // already be committed by the time the clip half was refused, and the
        // message would be a lie.
        //
        // The witness is the transcription URL, because it is the first thing
        // `save()` writes.
        val stt = SttSettings.create(ctx())
        stt.baseUrl = "http://10.42.0.10:8118"

        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.findViewById<EditText>(R.id.stt_url).setText("http://10.42.0.10:9999")
            activity.findViewById<EditText>(R.id.clip_url).setText("not a url at all")
            activity.findViewById<Button>(R.id.stt_save).performClick()
        } finally {
            controller.destroy()
        }

        assertEquals(
            "a refused clip URL still committed the transcription URL, so " +
                "the screen's 'nothing was saved' message is false",
            "http://10.42.0.10:8118",
            SttSettings.create(ctx()).baseUrl,
        )
    }

    @Test
    fun anEmptyFieldRESETSToTheDefaultRatherThanBeingRefused() {
        // Clearing the field is how the operator goes back to the default,
        // and it must not be mistaken for an invalid value — see
        // ClipSettings.baseUrl's setter, which removes the key.
        clipSettings().baseUrl = "http://10.42.0.10:8137"
        val status = save("")
        assertEquals(ctx().getString(R.string.stt_saved), status)
        assertEquals(ClipEndpoints.DEFAULT_BASE_URL, clipSettings().baseUrl)
    }
}
