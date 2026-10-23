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
                ctx.getString(R.string.engine_badge_remote),
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
        // breaking the notification, MainActivity and the mic-permission
        // fallback — all of which rely on onCreate starting a dictation.
        // (The QS tile was first in that list; it opens no card now.)
        //
        // ⚠ The mic-button half of this control is gone, deliberately. It
        // used to assert VISIBLE here, reasoning that a hidden button means
        // "the card cannot record". That retired with the START role: the
        // overlay offers no start control in ANY state now, so it is GONE on
        // a plain launch too and the branch's discriminator is the status
        // line. The full state table is OverlayNeverStartsARecordingTest.
        val controller = Robolectric.buildActivity(DictateActivity::class.java).setup()
        try {
            val a = controller.get()
            assertEquals(
                "a plain launch offers a mic control — the overlay must " +
                    "never present one that starts a recording; starting is " +
                    "the floating bubble's job",
                View.GONE,
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
        // branch. Re-running it would mean a notification tap on an
        // already-open card starts a dictation where today it does nothing —
        // a behaviour change to the other entry points, inside a change about
        // this one. (It said "a Quick Settings tap … three entry points …
        // about a fourth": the tile opens no card now, so the count is two
        // and the narrowness argument is unaffected.) This pins the narrowness so it is a decision rather than an
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
        // stop itself is a DEVICE check: start a dictation from the
        // notification's Dictate action, long-press the bubble, tap a chip,
        // confirm the recording indicator goes away.
        //
        // 🔴 This read "start a dictation from the QS tile", which a human
        // following it CANNOT DO — the tile toggles the bubble and starts no
        // dictation. The check itself is unchanged and still performable; only
        // the surface that opens the card was wrong.
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
        // `hideMicControl()` is the one place `micButton.visibility =
        // View.GONE` is spelled now — the three role assignments were
        // consolidated when the START role was removed. The ordering claim is
        // unchanged.
        val hideAt = body.indexOf("hideMicControl()")
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
    fun aPhoneFallbackTranscriptAfterAnEarlierRemoteOneStillReachesTheCard() {
        // 🔴 Round 5 of #17. `haveRemoteTranscript` is a LATCH: once a remote
        // transcript lands, SessionCallbacks refuses any non-remote one, so
        // the better text is never downgraded by the phone engine's late
        // final. startDictation resets it; attachToBubbleSession did not.
        //
        // So a card that had already received ONE remote transcript — the
        // ORDINARY SUCCESS PATH — silently dropped the NEXT dictation's text
        // whenever that one fell back to the phone engine. Same symptom as
        // the round-4 blocker, on a sibling path: uploaded, stored, never
        // appeared.
        val id = store("the stored dictation")
        val controller = open(DictateLaunch.transcribingIntent(ctx))
        try {
            val a = controller.get()

            // Dictation 1 succeeds remotely — this is what sets the latch.
            a.uiCallbacks().onTranscript("the first dictation", true, true)
            shadowOf(Looper.getMainLooper()).idle()

            controller.newIntent(DictateLaunch.historyIntent(ctx, id))
            awaitStatusChange("") { transcriptOf(a) }
            controller.newIntent(DictateLaunch.transcribingIntent(ctx))
            shadowOf(Looper.getMainLooper()).idle()

            // Dictation 2's server leg fails; the phone engine answers.
            BubbleSessionBus.sink.onTranscript("PHONE FALLBACK TEXT", false, true)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(
                "the phone-fallback transcript was dropped because the " +
                    "remote-wins latch survived the re-attach",
                "PHONE FALLBACK TEXT",
                transcriptOf(a),
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun reAttachingLeavesExactlyOneSinkRegistered() {
        // 🔴 Round 5 of #17: attachToBubbleSession was made idempotent
        // (detach-then-attach) and DELETING that detach left all 571 tests
        // green. What it costs: onDestroy detaches only the LATEST busSink,
        // so every earlier SessionCallbacks stays in the static
        // BubbleSessionBus.sinks holding its outer Activity — one leaked
        // Activity per re-attach — and every bus event is delivered twice
        // whenever no history switch has made the older sink stale.
        //
        // The transcript guards cannot see this: the stale sink drops the
        // text anyway, so the observable is identical. Counting the sinks is
        // the only thing that catches it.
        val sinks = BubbleSessionBus::class.java.getDeclaredField("sinks")
        sinks.isAccessible = true
        fun sinkCount() =
            (sinks.get(BubbleSessionBus) as List<*>).size

        val id = store("the stored dictation")
        val controller = open(DictateLaunch.transcribingIntent(ctx))
        try {
            val a = controller.get()
            assertEquals("precondition: one sink after the first attach", 1, sinkCount())

            controller.newIntent(DictateLaunch.historyIntent(ctx, id))
            awaitStatusChange("") { transcriptOf(a) }
            controller.newIntent(DictateLaunch.transcribingIntent(ctx))
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(
                "re-attaching registered a SECOND sink without detaching the " +
                    "first — every bus event is now delivered twice and the " +
                    "old sink leaks its Activity past onDestroy",
                1,
                sinkCount(),
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun reAttachingTurnsAHistoryCardBackIntoALiveOne() {
        // 🔴 Round 5 of #17: re-running the attach arm re-wired the SINK but
        // left the card dressed as a history card — Re-transcribe visible and
        // enabled against the PREVIOUS recording, whose historyEvents carry
        // no staleness gate and write the same views the live session writes.
        //
        // ⚠ The mic-button half of this assertion INVERTED when the START
        // role was removed. It used to read "a live dictation is attached but
        // the card has no mic control", asserting VISIBLE. The attach state
        // owns no capture of its own — the bubble's recording is already
        // over and uploading — so there is nothing to stop and nothing to
        // start, and the control must be GONE. What remains load-bearing
        // here is the Re-transcribe half: it must not stay offered.
        val id = store("the stored dictation")
        val controller = open(DictateLaunch.transcribingIntent(ctx))
        try {
            val a = controller.get()
            controller.newIntent(DictateLaunch.historyIntent(ctx, id))
            awaitStatusChange("") { transcriptOf(a) }
            assertEquals(
                "precondition: it is dressed as a history card",
                View.GONE,
                a.findViewById<View>(R.id.mic_button).visibility,
            )

            controller.newIntent(DictateLaunch.transcribingIntent(ctx))
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(
                "re-attaching revealed a mic control on a card whose " +
                    "recording is already over — that is a start button on " +
                    "the overlay",
                View.GONE,
                a.findViewById<View>(R.id.mic_button).visibility,
            )
            assertEquals(
                "Re-transcribe is still offered on a live card, bound to the " +
                    "previous recording",
                View.GONE,
                a.findViewById<View>(R.id.retranscribe_button).visibility,
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun aNewBubbleDictationAfterAHistorySwitchStillReachesTheCard() {
        // 🔴 THE WEDGE, driven through the REAL path: the bus, not a
        // hand-built callbacks object.
        //
        // Round 3 "fixed" this and round 4 measured that it had not. Its
        // guard called uiCallbacks() after the switch, which builds a fresh
        // sink and so asserts a recovery production never performs — the
        // exact construction round 3 had criticised in round 2's test. On
        // this path NOTHING rebuilt the sink: onNewIntent returned on the
        // missing history id, micButton is GONE so startDictation is
        // unreachable, and BubbleSessionBus.deliver saw a non-empty sink list
        // so it buffered nothing either. The dictation uploaded, stored, and
        // never appeared, with no recovery but closing the card.
        val id = store("the stored dictation")
        val controller = open(DictateLaunch.transcribingIntent(ctx))
        try {
            val a = controller.get()

            // The user picks a history chip.
            controller.newIntent(DictateLaunch.historyIntent(ctx, id))
            awaitStatusChange("") { transcriptOf(a) }

            // The user starts ANOTHER bubble dictation: the service fires the
            // attach intent at the already-open card.
            controller.newIntent(DictateLaunch.transcribingIntent(ctx))
            shadowOf(Looper.getMainLooper()).idle()

            // ...and that dictation's transcript arrives over the bus.
            BubbleSessionBus.sink.onTranscript("TEXT FROM THE NEW DICTATION", true, true)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(
                "a bubble dictation started after a history switch never " +
                    "reached the card — the sink registered with the bus is " +
                    "still the pre-switch one and the staleness gate mutes it",
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
