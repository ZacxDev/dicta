package dev.zacx.dicta

import android.content.Intent
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 🔴 The double-recording trap, from the card's side.
 *
 * [BubbleServiceFlowTest] proves the bubble *sets*
 * [DictateLaunch.EXTRA_ATTACH_SESSION] on the intent it starts. That is a claim
 * about the sender. A field that exists in an intent is not a guard — only a
 * **branch** on it is, and the branch lives in a different file. Tested only
 * from the sender's side, the flag could be set correctly and read by nobody,
 * and `DictateActivity.onCreate` would go on constructing a second
 * `DictationSession` while the bubble's recording is being uploaded.
 *
 * That failure is silent and misattributed. Two `DictationSession`s mean two
 * `AudioRecord`s, and Android resolves concurrent capture by feeding the loser
 * **bit-exact zeros** — `startRecording()` succeeds, `read()` returns the full
 * byte count, no exception, no error callback. What reaches the user is an
 * empty or invented transcript, which reads as a broken server.
 *
 * ## How the two cases are told apart
 *
 * Not structurally. In attach mode the card sets the transcribing status and
 * returns; in normal mode it runs `startDictation()`, which under Robolectric
 * reaches the phone-only path and overwrites the status line with something
 * else entirely. So the status line is a genuine behavioural discriminator for
 * "did onCreate start a dictation", and the normal-mode assertion is written as
 * "anything but the transcribing status" rather than pinning whichever message
 * the recogniser stub happens to produce.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictateAttachSessionTest {

    @After
    fun tearDown() {
        BubbleSessionBus.clearForTest()
        OverlayVisibility.clearForTest()
    }

    private fun statusOf(intent: Intent?): String {
        val controller = if (intent == null) {
            Robolectric.buildActivity(DictateActivity::class.java)
        } else {
            Robolectric.buildActivity(DictateActivity::class.java, intent)
        }
        val activity = controller.setup().get()
        return activity.findViewById<TextView>(R.id.status_text).text.toString()
    }

    private val transcribing: String
        get() = RuntimeEnvironment.getApplication().getString(R.string.stt_uploading)

    @Test
    fun theCardOpenedByTheBubbleDoesNotStartItsOwnRecording() {
        val intent = DictateLaunch.transcribingIntent(RuntimeEnvironment.getApplication())
        assertEquals(
            "the card opened with EXTRA_ATTACH_SESSION ran its own " +
                "startDictation() — a SECOND DictationSession while the " +
                "bubble's recording is still uploading. Android does not error " +
                "on that; it feeds one of the two exact zeros and the user sees " +
                "an empty transcript blamed on the server.",
            transcribing,
            statusOf(intent),
        )
    }

    @Test
    fun theCardOpenedAnyOtherWayStillStartsRecordingByItself() {
        // 🔴 Positive control AND the real invariant: the notification,
        // MainActivity and the mic-permission fallback all still rely on
        // onCreate starting the dictation. (The QS tile was named first here
        // and no longer opens the card at all.) A conditional that refused
        // in every case would pass the test above and break every entry point
        // that is not the bubble.
        assertNotEquals(
            "the card no longer starts a dictation when opened WITHOUT the " +
                "attach flag, so the notification and the in-app button both " +
                "open a card that does nothing",
            transcribing,
            statusOf(null),
        )
    }

    @Test
    fun thePlainOverlayIntentDoesNotCarryTheAttachFlag() {
        val ctx = RuntimeEnvironment.getApplication()
        assertEquals(
            "DictateLaunch.intent() carries the attach flag, so every entry " +
                "point would open a card that refuses to record",
            false,
            DictateLaunch.intent(ctx)
                .getBooleanExtra(DictateLaunch.EXTRA_ATTACH_SESSION, false),
        )
        assertEquals(
            "DictateLaunch.transcribingIntent() does NOT carry the attach " +
                "flag, so the bubble's card will start a second recording",
            true,
            DictateLaunch.transcribingIntent(ctx)
                .getBooleanExtra(DictateLaunch.EXTRA_ATTACH_SESSION, false),
        )
    }

    @Test
    fun theAttachedCardReceivesWhatTheSessionSaidBeforeItOpened() {
        // The engine badge and the status line must stay honest about which
        // engine produced the text on screen, and on a short recording the
        // transcript can land before the card exists at all.
        BubbleSessionBus.clearForTest()
        BubbleSessionBus.sink.onTranscript(
            "the buffered transcript", fromRemote = true, isFinal = true
        )

        val intent = DictateLaunch.transcribingIntent(RuntimeEnvironment.getApplication())
        val activity = Robolectric.buildActivity(DictateActivity::class.java, intent)
            .setup()
            .get()

        assertEquals(
            "a transcript that landed before the card opened was dropped",
            "the buffered transcript",
            activity.findViewById<android.widget.EditText>(R.id.dictated_text)
                .text.toString(),
        )
    }
}
