package dev.zacx.dicta

import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * The card opened on a **stored** dictation, and the `onNewIntent` that makes
 * picking a second one work.
 *
 * ## The two failures this file is about
 *
 * 1. **A history card must not open the microphone.** It is the third arm of
 *    `onCreate`'s branch and the third way to reach the double-capture bug:
 *    Android resolves two concurrent captures by feeding one of them bit-exact
 *    zeros, with no exception and no error callback, so the only observable is
 *    an empty transcript that reads as a server fault.
 * 2. **`DictateActivity` is `launchMode="singleTop"` and overrode
 *    `onNewIntent` nowhere.** With the card already on top, a second launch
 *    never reaches `onCreate`, the new extra is silently discarded, and the
 *    card keeps showing what it had — the user picks item 3 and sees item 1.
 *    The reveal makes that routine rather than exotic, because the bubble is
 *    reachable while the card is up.
 *
 * ## Against a real store
 *
 * These write real files under `noBackupFilesDir` rather than stubbing the
 * store, because the thing under test is the activity's *branch* and the read
 * it performs, and a stubbed store would leave "does the card actually find
 * the item" unasserted. The directory is cleared between tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictateHistoryCardTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    private fun historyDir(): File = File(ctx.noBackupFilesDir, "history")

    @After
    fun tearDown() {
        BubbleSessionBus.clearForTest()
        OverlayVisibility.clearForTest()
        MicrophoneOwner.clearForTest()
        historyDir().deleteRecursively()
    }

    /** Puts a real item in the real store and returns its id. */
    private fun store(text: String): String {
        val store = DictationHistoryStore(ctx)
        val source = File.createTempFile("dictation-", ".ogg")
        source.writeBytes(ByteArray(64) { it.toByte() })
        try {
            return store.add(source, text, fromRemote = true, audioSeconds = 1.5)
        } finally {
            source.delete()
        }
    }

    /**
     * Drains the activity's history worker onto the main looper.
     *
     * The read runs on a real background thread and hands back through
     * `runOnUiThread`, so there is no single `idle()` that is guaranteed to
     * see it. Same shape as `SettingsHistoryControlsTest.awaitChange`, and
     * same reason.
     */
    private fun awaitStatusChange(from: String, read: () -> String): String {
        repeat(200) {
            val now = read()
            if (now != from) return now
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        return read()
    }

    private fun open(intent: Intent) =
        Robolectric.buildActivity(DictateActivity::class.java, intent).setup()

    private fun transcriptOf(a: DictateActivity): String =
        a.findViewById<EditText>(R.id.dictated_text).text.toString()

    private fun statusOf(a: DictateActivity): String =
        a.findViewById<TextView>(R.id.status_text).text.toString()

    // --- the branch ---------------------------------------------------------

    @Test
    fun aHistoryCardShowsTheStoredTranscriptAndOpensNoMicrophone() {
        val id = store("a dictation from yesterday")
        val controller = open(DictateLaunch.historyIntent(ctx, id))
        try {
            val a = controller.get()
            val text = awaitStatusChange("") { transcriptOf(a) }

            assertEquals("a dictation from yesterday", text)
            assertFalse(
                "the history card acquired the microphone. It has no capture " +
                    "of its own to run, and the bubble's may well be live — " +
                    "Android answers two captures by feeding one exact zeros",
                MicrophoneOwner.isHeld,
            )
            assertEquals(
                "mic_button is still on screen on a history card, one tap " +
                    "away from toggleDictation() — the one thing this screen " +
                    "must never do",
                View.GONE,
                a.findViewById<View>(R.id.mic_button).visibility,
            )
            assertEquals(
                View.VISIBLE,
                a.findViewById<View>(R.id.retranscribe_button).visibility,
            )
            assertTrue(
                "the Re-transcribe button is disabled after the item loaded, " +
                    "so the card's only action is unusable",
                a.findViewById<Button>(R.id.retranscribe_button).isEnabled,
            )
            assertEquals(
                "the level meter is live on a card with no live capture",
                View.INVISIBLE,
                a.findViewById<View>(R.id.level_meter).visibility,
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun aHistoryCardNamesTheEngineThatProducedTheStoredText() {
        val id = store("from the server")
        val controller = open(DictateLaunch.historyIntent(ctx, id))
        try {
            val a = controller.get()
            awaitStatusChange("") { transcriptOf(a) }
            val badge = a.findViewById<TextView>(R.id.engine_badge)
            assertEquals(View.VISIBLE, badge.visibility)
            assertEquals(
                "the badge must name the engine the STORED text came from, " +
                    "not the one a fresh dictation would use",
                ctx.getString(R.string.stt_remote_done),
                badge.text.toString(),
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun anIdThatIsNoLongerStoredSaysSoAndOffersNoAction() {
        // Retention is allowed to remove things; the chip the user tapped may
        // have been evicted or cleared since the row was drawn. Not an error
        // state, and not a crash.
        val controller = open(DictateLaunch.historyIntent(ctx, "no-such-item"))
        try {
            val a = controller.get()
            val status = awaitStatusChange(ctx.getString(R.string.history_saved)) {
                statusOf(a)
            }
            assertEquals(ctx.getString(R.string.history_missing), status)
            assertFalse(
                "Re-transcribe is offered for an item that does not exist",
                a.findViewById<Button>(R.id.retranscribe_button).isEnabled,
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun aCardOpenedAnyOtherWayIsUnchanged() {
        // 🔴 Positive control for the new branch: a condition that matched
        // every launch would pass every assertion above while silently
        // breaking the tile, the notification, MainActivity and the
        // mic-permission fallback — all of which rely on onCreate starting a
        // dictation.
        val controller = Robolectric.buildActivity(DictateActivity::class.java).setup()
        try {
            val a = controller.get()
            assertEquals(
                "a plain launch hid the mic button, so the card cannot record",
                View.VISIBLE,
                a.findViewById<View>(R.id.mic_button).visibility,
            )
            assertEquals(
                View.GONE,
                a.findViewById<View>(R.id.retranscribe_button).visibility,
            )
            assertNotEquals(
                ctx.getString(R.string.history_saved),
                statusOf(a),
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun theRetranscribeControlIsHiddenInTheLayoutsOwnDefault() {
        // Separate from the running-activity assertion above, for the reason
        // OverlayMinimalLayoutTest records: the activity forces visibility in
        // code, so deleting `android:visibility="gone"` from the XML leaves
        // that assertion green while any path that inflates this layout
        // without the wiring ships a Re-transcribe button on a live card.
        val themed = android.view.ContextThemeWrapper(ctx, R.style.Theme_Dicta_Overlay)
        val root = android.view.LayoutInflater.from(themed)
            .inflate(R.layout.activity_dictate, null)
        assertEquals(
            View.GONE,
            root.findViewById<View>(R.id.retranscribe_button).visibility,
        )
    }

    // --- onNewIntent --------------------------------------------------------

    @Test
    fun aSecondHistoryIntentReplacesWhatTheCardIsShowing() {
        // 🔴 THE onNewIntent assertion. Without the override this activity is
        // singleTop and the second extra is silently dropped: the user picks
        // item 2 and goes on looking at item 1, with no error anywhere.
        val first = store("the first dictation")
        val second = store("the second dictation")
        val controller = open(DictateLaunch.historyIntent(ctx, first))
        try {
            val a = controller.get()
            assertEquals(
                "precondition: the card loaded the first item",
                "the first dictation",
                awaitStatusChange("") { transcriptOf(a) },
            )

            controller.newIntent(DictateLaunch.historyIntent(ctx, second))

            assertEquals(
                "the card still shows the FIRST item after a second history " +
                    "intent was delivered — the extra was dropped, which is " +
                    "what singleTop does to an activity with no onNewIntent",
                "the second dictation",
                awaitStatusChange("the first dictation") { transcriptOf(a) },
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun aSecondHistoryIntentBecomesTheIntentTheActivityReads() {
        // 🔴 `setIntent` is the half of the fix that is invisible from the
        // screen: without it `getIntent()` keeps answering for the ORIGINAL
        // launch for the rest of this activity's life, so every later read of
        // an extra — here or in anything added afterwards — is two taps stale.
        val first = store("one")
        val second = store("two")
        val controller = open(DictateLaunch.historyIntent(ctx, first))
        try {
            val a = controller.get()
            awaitStatusChange("") { transcriptOf(a) }
            controller.newIntent(DictateLaunch.historyIntent(ctx, second))
            assertEquals(
                "getIntent() still returns the launch intent, so setIntent() " +
                    "was not called",
                second,
                a.intent.getStringExtra(DictateLaunch.EXTRA_HISTORY_ID),
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun anIntentWithNoHistoryIdDoesNotRestartTheCard() {
        // 🔴 Deliberately narrower than re-running the whole three-way
        // branch. Re-running it would mean a Quick Settings tap on an
        // already-open card starts a dictation where today it does nothing —
        // a behaviour change to three entry points, inside a change about a
        // fourth. This pins the narrowness so it is a decision rather than an
        // omission someone "fixes" later by accident.
        val id = store("the stored one")
        val controller = open(DictateLaunch.historyIntent(ctx, id))
        try {
            val a = controller.get()
            awaitStatusChange("") { transcriptOf(a) }

            controller.newIntent(DictateLaunch.intent(ctx))
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(
                "a plain intent delivered to an open card restarted it",
                "the stored one",
                transcriptOf(a),
            )
            assertFalse(
                "a plain intent delivered to an open card opened the microphone",
                MicrophoneOwner.isHeld,
            )
        } finally {
            controller.close()
        }
    }
}
