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

    @Test
    fun populateFromHistoryClosesTheMicrophoneBeforeItHidesTheStopControl() {
        // 🔴 A STRUCTURAL guard, and the weaker kind — say so rather than let
        // the name imply more. What it pins: that `populateFromHistory` stops
        // any live capture, and that it does so BEFORE hiding `micButton`,
        // which is the card's only stop control. Audit round 1 of #17, F3:
        // without it a history intent arriving at a card with a live
        // dictation left the recording running with no reachable control
        // while the status line said the microphone was off.
        //
        // 🔴 WHAT IT DOES NOT PROVE: that the capture actually stops. A live
        // DictationSession is not constructible under Robolectric, so the
        // stop itself is a DEVICE check: start a dictation from the QS tile,
        // long-press the bubble, tap a chip, confirm the recording indicator
        // goes away.
        //
        // ⚠ An earlier version of this comment also said the activity
        // "exposes no session seam". That was FALSE — `uiCallbacks()` is
        // `internal` and already driven by OverlayMinimalLayoutTest — and it
        // was written in the same commit that retracted an identical
        // "no JVM path" claim elsewhere. The consequence of believing it is
        // the test below, which that sentence said could not be written.
        val src = File("src/main/java/dev/zacx/dicta/DictateActivity.kt")
        assertTrue("cannot read ${src.absolutePath}", src.isFile)
        val body = src.readText()
            .substringAfter("private fun populateFromHistory(")
            .substringBefore("\n    private fun ")

        val stopAt = body.indexOf("stopAnyLiveCapture()")
        val hideAt = body.indexOf("micButton.visibility = View.GONE")
        assertTrue(
            "populateFromHistory does not stop a live capture at all — it " +
                "hides the only stop control and leaves the microphone open",
            stopAt >= 0,
        )
        assertTrue(
            "populateFromHistory hides micButton BEFORE stopping the " +
                "capture, so there is a window with an open mic and no " +
                "reachable control",
            hideAt >= 0 && stopAt < hideAt,
        )
    }

    @Test
    fun aFreshSessionAfterAHistorySwitchIsNotMuted() {
        // 🔴 THE OTHER HALF, and the one round 2 got wrong. Round 2 gated on
        // "is this a history card", so after attach → history → start another
        // bubble dictation the card was muted FOR THE REST OF ITS LIFE: the
        // attach intent carries no history id, onNewIntent returns early by
        // design, and nothing ever cleared the flag. micButton is GONE on a
        // history card, so there was no recovery but closing it.
        //
        // A generation cannot wedge that way — a NEW callbacks object is born
        // current. Suppressing the stale sink is worthless if the live one is
        // suppressed with it, so both directions are pinned.
        val id = store("the stored dictation")
        val controller = open(Intent(ctx, DictateActivity::class.java))
        try {
            val a = controller.get()
            controller.newIntent(DictateLaunch.historyIntent(ctx, id))
            awaitStatusChange("") { transcriptOf(a) }

            // A new session attaches and reports in.
            val fresh = a.uiCallbacks()
            fresh.onTranscript("TEXT FROM THE NEW DICTATION", true, true)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(
                "a session created AFTER the history switch was muted too, " +
                    "so the card is permanently dead and the user's new " +
                    "dictation never appears",
                "TEXT FROM THE NEW DICTATION",
                transcriptOf(a),
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun aLateTranscriptFromTheStoppedSessionCannotOverwriteTheHistoryCard() {
        // 🔴 BEHAVIOURAL, through the seam the comment above once claimed did
        // not exist. `stopAnyLiveCapture()` calls DictationSession.stop(),
        // which keeps the session alive to finish its upload — so the card
        // that has since become a history card is still wired to callbacks
        // that write its transcript, its status line and its level meter.
        // Audit round 2 of #17: round 1's choice of stop() over discard()
        // made this certain rather than contingent.
        val id = store("the stored dictation")
        // 🔴 Open LIVE first and capture the callbacks the live session owns,
        // THEN switch the card to history. Capturing them afterwards builds a
        // fresh object that is born current and is correctly not stale — the
        // round-2 version of this test did exactly that and only passed
        // because the gate was a global flag rather than a generation.
        val controller = open(Intent(ctx, DictateActivity::class.java))
        try {
            val a = controller.get()
            val late = a.uiCallbacks()          // the live session's sink

            controller.newIntent(DictateLaunch.historyIntent(ctx, id))
            val storedText = awaitStatusChange("") { transcriptOf(a) }
            val storedStatus = statusOf(a)
            assertTrue("precondition: the stored text is shown", storedText.isNotEmpty())

            late.onTranscript("THE LIVE DICTATION TEXT", true, true)
            late.onStatus("uploading the live one")
            late.onTier(AudioCaptureLogic.Tier.REMOTE_ONLY_NO_API)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(
                "a late transcript from the stopped capture replaced the " +
                    "stored dictation the user had just tapped",
                storedText,
                transcriptOf(a),
            )
            assertEquals(
                "a late status from the stopped capture replaced the " +
                    "history card's own status line",
                storedStatus,
                statusOf(a),
            )
            assertEquals(
                "onTier turned the level meter on for a card that owns no " +
                    "capture",
                View.INVISIBLE,
                a.findViewById<View>(R.id.level_meter).visibility,
            )
        } finally {
            controller.close()
        }
    }
}
