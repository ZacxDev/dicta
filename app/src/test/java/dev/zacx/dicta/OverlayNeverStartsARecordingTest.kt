package dev.zacx.dicta

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Looper
import android.speech.RecognitionService
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.File

/**
 * 🔴 **The overlay must never present a control that starts a NEW recording.**
 *
 * Operator, 2026-10-20: *"I still see the start recording button in the
 * overlay. We don't need that. The only way to start a recording should be in
 * the floating icon widget, not the overlay that opens after a recording is
 * done."*
 *
 * ## `@id/mic_button` wore three roles; one of them is gone
 *
 *  * **START** — `@string/start_dictation` ("Speak"), assigned from eleven
 *    sites and the layout default. **Removed**, string included.
 *  * **STOP** — `@string/stop_dictation`. **Preserved**, and it is not
 *    optional: the QS tile, the notification, MainActivity's "Try dictation
 *    now" and the permission-grant path all AUTO-START a recording in
 *    `onCreate`, so without this role those recordings have no end control at
 *    all. [aLiveCaptureStillHasAWorkingStopControl] is that pin.
 *  * **MIC-DENIED FALLBACK** — `@string/use_system_voice_typing`.
 *    **Preserved**: it opens no microphone of ours, it hands the utterance to
 *    the system voice-typing UI, and [BubbleService] documents this activity
 *    as the only place in the app that can ask for `RECORD_AUDIO` — so it is
 *    the only place the copy can live, and the bubble's tap deliberately falls
 *    back here.
 *
 * ## Why this is a STATE guard and not a string search
 *
 * A test that greps the sources for `start_dictation` is walkable by one
 * rename: a new `@string/speak_now_please` assigned at the same site would
 * leave it green with the defect fully present on screen. So the central test
 * below ENUMERATES the reachable card states, drives each one through the real
 * activity, and asserts the control is either `GONE` or carrying one of the
 * two surviving role labels. A relabelled start button fails it, because the
 * label it would have to carry is not in the set.
 *
 * ## What this cannot see
 *
 * Robolectric has no `AudioRecord` and no `MediaCodec`, so the *remote*
 * capture path cannot be started here; the live-capture state is the
 * phone-only recogniser, which Robolectric can run (see `installRecogniser`).
 * Both paths assign the stop role through the same one function, which is why
 * one live path is enough to pin it — but "both" is a read of
 * [theOnlyWaysTheControlBecomesVisibleAreTheTwoSurvivingRoles], not a
 * measurement of the remote path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverlayNeverStartsARecordingTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val controllers = mutableListOf<ActivityController<DictateActivity>>()

    @After
    fun tearDown() {
        controllers.forEach { runCatching { it.close() } }
        controllers.clear()
        MicrophoneOwner.clearForTest()
        BubbleSessionBus.clearForTest()
        OverlayVisibility.clearForTest()
        File(ctx.noBackupFilesDir, "history").deleteRecursively()
    }

    // --- the invariant ------------------------------------------------------

    /** The only two labels the control is ever allowed to wear. */
    private fun allowedLabels(): Set<String> = setOf(
        ctx.getString(R.string.stop_dictation),
        ctx.getString(R.string.use_system_voice_typing),
    )

    /**
     * 🔴 The invariant itself: a VISIBLE control must be one of the two
     * surviving roles, and nothing else. Applied to every state below,
     * including the ones whose own expectation is `GONE` — so a state that
     * starts revealing the control is caught by its label as well as by its
     * visibility.
     */
    private fun assertOffersNoStart(state: String, a: DictateActivity) {
        val control = a.findViewById<Button>(R.id.mic_button)
        val label = control.text.toString()
        if (control.visibility == View.VISIBLE) {
            assertTrue(
                "state <$state>: @id/mic_button is VISIBLE carrying <$label>, " +
                    "which is neither the STOP role nor the mic-denied " +
                    "fallback. The overlay is offering a control that starts " +
                    "a NEW recording — the thing the operator asked to " +
                    "remove. Allowed: ${allowedLabels()}",
                label in allowedLabels(),
            )
        } else {
            assertEquals(
                "state <$state>: @id/mic_button is neither VISIBLE nor GONE. " +
                    "INVISIBLE still reserves its 56dp of layout for a " +
                    "control that must not exist in this state.",
                View.GONE,
                control.visibility,
            )
        }
    }

    // --- the state table ----------------------------------------------------

    /**
     * Every card state that is reachable without a device, each driven through
     * the real activity.
     *
     * The GONE expectations are the change. `postRecordingTranscript` is
     * literally the operator's screen: the bubble recorded, the card shows the
     * transcript, and at `8d39032` the control reverted to "Speak" there.
     */
    @Test
    fun everyReachableCardStateEitherHidesTheControlOrGivesItASurvivingRole() {
        // 🔴 Every state is driven and every violation COLLECTED, rather than
        // the first one throwing. A table that stops at state 1 says nothing
        // about states 2..n — at `8d39032` that would have reported only the
        // at-rest card and left the operator's own screen unmeasured.
        val table = listOf(
            // name                                    expected label (null = GONE)
            State("atRest(plainLaunch,noMicPermission)", null) { plainCard() },
            State("attachedToABubbleUpload", null) { attachedCard() },
            State("postRecordingTranscript", null) { cardShowingAFinishedRecording() },
            State("history", null) { historyCard() },
            State("micGrantedButNoRecogniserOnTheDevice", null) { cardWithNoRecogniser() },
            State("refusedBecauseAnotherOwnerHoldsTheMic", null) { cardRefusedTheMic() },
            State("phoneRecogniserDeliveredItsFinal", null) { cardAfterThePhoneFinal() },
            State("liveCaptureStoppedByScreenshot", null) { cardAfterAScreenshotStop() },
            // 🔴 The two states where the control IS allowed on screen, in the
            // same table so the invariant can never be satisfied vacuously: a
            // change that simply hid the control everywhere fails here.
            State("liveCapture", ctx.getString(R.string.stop_dictation)) { liveCaptureCard() },
            State(
                "micDeniedFallback",
                ctx.getString(R.string.use_system_voice_typing),
            ) { micDeniedCard() },
        )

        val violations = mutableListOf<String>()
        for (state in table) {
            resetStatics()
            violations += violationsIn(state)
        }

        assertEquals(
            "the overlay presents a control that starts a NEW recording, or " +
                "has lost one of the two roles that must survive:\n" +
                violations.joinToString("\n"),
            emptyList<String>(),
            violations,
        )
    }

    private class State(
        val name: String,
        /** The label the control must carry here, or null when it must be GONE. */
        val expectedLabel: String?,
        val build: () -> DictateActivity,
    )

    private fun violationsIn(state: State): List<String> {
        val a = state.build()
        val control = a.findViewById<Button>(R.id.mic_button)
        val label = control.text.toString()
        val found = mutableListOf<String>()

        if (control.visibility == View.VISIBLE && label !in allowedLabels()) {
            found += "<${state.name}>: @id/mic_button is VISIBLE carrying " +
                "<$label>, which is neither the STOP role nor the mic-denied " +
                "fallback — that is a control that starts a NEW recording, " +
                "and the overlay must never present one. Allowed: " +
                "${allowedLabels()}"
        }
        if (control.visibility != View.VISIBLE && control.visibility != View.GONE) {
            found += "<${state.name}>: @id/mic_button is INVISIBLE, which " +
                "still reserves 56dp of layout for a control that must not " +
                "exist in this state"
        }
        if (state.expectedLabel == null && control.visibility != View.GONE) {
            found += "<${state.name}>: the overlay shows a mic control, but " +
                "there is no recording here for it to stop — whatever it " +
                "says, tapping it is how a user starts one, and starting is " +
                "the floating bubble's job"
        }
        if (state.expectedLabel != null && control.visibility != View.VISIBLE) {
            found += "<${state.name}>: the control is hidden in a state that " +
                "NEEDS it. The tile, the notification and MainActivity " +
                "auto-start a recording on open; without this role those " +
                "recordings cannot be ended from the only screen they open"
        }
        if (state.expectedLabel != null && label != state.expectedLabel) {
            found += "<${state.name}>: the control reads <$label>, expected " +
                "<${state.expectedLabel}>"
        }
        return found
    }

    /**
     * The layout's own default, separately from any running activity.
     *
     * Same reason `DictateHistoryCardTest` pins Re-transcribe twice: the
     * activity decides visibility in code, so a code-only assertion stays
     * green while any path that inflates this layout without the wiring ships
     * a start button.
     */
    @Test
    fun theLayoutsOwnDefaultHidesTheControl() {
        val themed = android.view.ContextThemeWrapper(ctx, R.style.Theme_Dicta_Overlay)
        val root = LayoutInflater.from(themed).inflate(R.layout.activity_dictate, null)
        val control = root.findViewById<Button>(R.id.mic_button)
        assertEquals(
            "the layout ships @id/mic_button visible, so the card shows a " +
                "control before any code has decided it has a role",
            View.GONE,
            control.visibility,
        )
        assertTrue(
            "the layout's default label is <${control.text}>, which is not a " +
                "surviving role. Any accidental reveal would show a start " +
                "control.",
            control.text.toString() in allowedLabels(),
        )
    }

    // --- the STOP role, which must keep working -----------------------------

    /**
     * 🔴 The regression this change could plausibly cause, pinned.
     *
     * A live capture must still expose a control that ENDS it, and tapping it
     * must actually end it — not merely relabel. Driven on the phone-only
     * recogniser because that is the capture Robolectric can really run.
     */
    @Test
    fun aLiveCaptureStillHasAWorkingStopControl() {
        val a = liveCaptureCard()

        // Positive control: the microphone really is open, so the assertions
        // below are about a live capture and not about a card that quietly
        // did nothing.
        assertTrue(
            "the phone-only recogniser never started listening — this test " +
                "is not about a live capture at all",
            phoneRecogniserIsListening(a),
        )
        assertTrue(
            "the capture is live but MicrophoneOwner says the mic is free",
            MicrophoneOwner.isHeld,
        )

        val control = a.findViewById<Button>(R.id.mic_button)
        assertEquals(
            "a live capture has no visible stop control",
            View.VISIBLE,
            control.visibility,
        )
        assertEquals(
            "the live capture's control is not labelled as a stop",
            ctx.getString(R.string.stop_dictation),
            control.text.toString(),
        )

        control.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(
            "tapping the stop control left the recogniser listening — the " +
                "control is decorative and the capture cannot be ended from " +
                "the only screen it opens",
            phoneRecogniserIsListening(a),
        )
        assertFalse(
            "the stopped capture kept the microphone, so every later " +
                "recording in the process is refused",
            MicrophoneOwner.isHeld,
        )
        assertOffersNoStart("afterTappingStop", a)
        assertEquals(
            "the control stayed on screen after the capture ended — that is " +
                "the start button, back again by another name",
            View.GONE,
            control.visibility,
        )
    }

    /**
     * The fallback role must still be reachable AND still do its one job:
     * hand over to the system voice-typing UI rather than open a microphone.
     */
    @Test
    fun theMicDeniedFallbackStillHandsOverToTheSystemVoiceUi() {
        val a = micDeniedCard()
        val control = a.findViewById<Button>(R.id.mic_button)
        assertEquals(
            "the mic-denied fallback control is gone. This activity is the " +
                "only place the app can request RECORD_AUDIO from, so it is " +
                "the only place that copy lives, and the bubble's tap falls " +
                "back here when the permission is refused.",
            View.VISIBLE,
            control.visibility,
        )
        assertEquals(
            "the fallback control is on screen but not labelled as the " +
                "system-voice-typing handover",
            ctx.getString(R.string.use_system_voice_typing),
            control.text.toString(),
        )

        control.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        val next = shadowOf(a).nextStartedActivityForResult
        assertTrue(
            "tapping the fallback control started nothing, or started " +
                "something that is not the system recogniser: " +
                "${next?.intent?.action}",
            next != null &&
                next.intent.action == android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH,
        )
        assertFalse(
            "the fallback opened a microphone of our own — the whole point " +
                "of it is that it needs no RECORD_AUDIO from this app",
            MicrophoneOwner.isHeld,
        )
    }

    // --- the role ledger ----------------------------------------------------

    /**
     * 🔴 The growth guard. The state table above can only cover states that
     * exist today; this one fails when a NEW site reveals the control.
     *
     * Asserts that every `micButton.visibility = View.VISIBLE` in
     * `DictateActivity` sits inside one of the two role functions, and that
     * the deleted START string is really gone from the sources and the
     * resources rather than merely unreferenced from the one file this change
     * edited.
     */
    @Test
    fun theOnlyWaysTheControlBecomesVisibleAreTheTwoSurvivingRoles() {
        val source = mainSource("DictateActivity.kt")
        val reveals = Regex("""micButton\.visibility\s*=\s*View\.VISIBLE""")
            .findAll(source).map { it.range.first }.toList()
        assertEquals(
            "DictateActivity reveals @id/mic_button from ${reveals.size} " +
                "places. Exactly two roles survive — showStopControl() and " +
                "showSystemVoiceFallback() — so a third reveal is either a " +
                "start control or a role that needs adding to this ledger " +
                "AND to the state table above.",
            2,
            reveals.size,
        )
        for (role in listOf("showStopControl", "showSystemVoiceFallback")) {
            val body = source
                .substringAfter("private fun $role() {")
                .substringBefore("\n    }")
            assertTrue(
                "$role() does not reveal the control, so one of the two " +
                    "reveals counted above belongs to something else",
                body.contains("micButton.visibility = View.VISIBLE"),
            )
        }

        // The role is deleted, not just unused: a resource left behind is one
        // a new call site can pick up without anybody noticing.
        val strings = File("app/src/main/res/values/strings.xml")
            .takeIf { it.isFile }
            ?: File("src/main/res/values/strings.xml")
        assertTrue("cannot read ${strings.absolutePath}", strings.isFile)
        assertFalse(
            "<string name=\"start_dictation\"> is still declared. The " +
                "overlay has no start role; leaving the string is how the " +
                "role comes back.",
            strings.readText().contains("name=\"start_dictation\""),
        )
    }

    // --- states -------------------------------------------------------------

    private fun resetStatics() {
        MicrophoneOwner.clearForTest()
        BubbleSessionBus.clearForTest()
        OverlayVisibility.clearForTest()
    }

    private fun open(intent: Intent): DictateActivity {
        val c = Robolectric.buildActivity(DictateActivity::class.java, intent)
        controllers += c
        return c.setup().get()
    }

    /** The at-rest card: a plain launch with no microphone permission. */
    private fun plainCard(): DictateActivity = open(DictateLaunch.intent(ctx))

    /** The bubble's own intent: a recording already made, upload in flight. */
    private fun attachedCard(): DictateActivity =
        open(DictateLaunch.transcribingIntent(ctx))

    /**
     * 🔴 The operator's screen. The bubble recorded, the transcript came back,
     * the card is showing it — and at `8d39032` the control said "Speak".
     */
    private fun cardShowingAFinishedRecording(): DictateActivity {
        val a = attachedCard()
        BubbleSessionBus.sink.onTranscript("what the bubble just recorded", true, true)
        BubbleSessionBus.sink.onRecordingStopped()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            "precondition: the transcript is on screen",
            "what the bubble just recorded",
            a.findViewById<EditText>(R.id.dictated_text).text.toString(),
        )
        return a
    }

    private fun historyCard(): DictateActivity {
        val store = DictationHistoryStore(ctx)
        val source = File.createTempFile("dictation-", ".ogg")
        source.writeBytes(ByteArray(64) { it.toByte() })
        val id = try {
            store.add(source, "a dictation from yesterday", fromRemote = true, audioSeconds = 1.5)
        } finally {
            source.delete()
        }
        val a = open(DictateLaunch.historyIntent(ctx, id))
        // The store read is on a real background thread handing back through
        // runOnUiThread, so there is no single idle() guaranteed to see it.
        for (attempt in 0 until 200) {
            if (a.findViewById<EditText>(R.id.dictated_text).text.isNotEmpty()) break
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue(
            "precondition: the stored transcript never loaded, so this state " +
                "is not the history card",
            a.findViewById<EditText>(R.id.dictated_text).text.isNotEmpty(),
        )
        return a
    }

    /** Mic granted, auto-start runs, and the device has no recogniser. */
    private fun cardWithNoRecogniser(): DictateActivity {
        grantMic()
        return open(DictateLaunch.intent(ctx))
    }

    /** The tile/notification path refused because the bubble holds the mic. */
    private fun cardRefusedTheMic(): DictateActivity {
        grantMic()
        MicrophoneOwner.acquire(Any())
        val a = open(DictateLaunch.intent(ctx))
        assertEquals(
            "precondition: this is the mic-busy refusal",
            ctx.getString(R.string.stt_mic_in_use),
            statusOf(a),
        )
        return a
    }

    /** A live phone-only capture: the state the STOP role exists for. */
    private fun liveCaptureCard(): DictateActivity {
        grantMic()
        installRecogniser()
        // The plain intent is what the QS tile, the notification and
        // MainActivity all wrap, so onCreate auto-starts the capture itself —
        // the real route to this state, not a hand-driven one.
        return open(DictateLaunch.intent(ctx))
    }

    /** That same capture after the recogniser returned its final result. */
    private fun cardAfterThePhoneFinal(): DictateActivity {
        val a = liveCaptureCard()
        speechListenerOf(a).onFinal("the recogniser's final answer")
        shadowOf(Looper.getMainLooper()).idle()
        return a
    }

    /** Screenshot mid-capture: stops the recording and keeps the partial. */
    private fun cardAfterAScreenshotStop(): DictateActivity {
        val a = liveCaptureCard()
        assertTrue("precondition: a capture is live", phoneRecogniserIsListening(a))
        a.findViewById<View>(R.id.screenshot_button).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        return a
    }

    /** The permission was refused, so the system voice UI is offered. */
    private fun micDeniedCard(): DictateActivity {
        val a = plainCard()
        a.onRequestPermissionsResult(
            1001,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            intArrayOf(PackageManager.PERMISSION_DENIED),
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            "precondition: this is the mic-denied state",
            ctx.getString(R.string.mic_denied_fallback),
            statusOf(a),
        )
        return a
    }

    // --- harness ------------------------------------------------------------

    private fun statusOf(a: DictateActivity): String =
        a.findViewById<android.widget.TextView>(R.id.status_text).text.toString()

    private fun grantMic() {
        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    /**
     * Makes `SpeechRecognizer.isRecognitionAvailable` true — without it
     * `startPhoneOnlyDictation` returns at its `!helper.isAvailable` check and
     * nothing ever opens. Same step, same reason, as `MicOpenerSeamTest`.
     */
    private fun installRecogniser() {
        val info = ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply {
                packageName = ctx.packageName
                name = "dev.zacx.dicta.NotARealRecognitionService"
            }
        }
        shadowOf(ctx.packageManager).addResolveInfoForIntent(
            Intent(RecognitionService.SERVICE_INTERFACE),
            info,
        )
    }

    private fun phoneRecogniserIsListening(a: DictateActivity): Boolean =
        (readField(a, "speech") as SpeechHelper?)?.isListening == true

    /** The card's own `SpeechHelper.Listener` — the real callback route. */
    private fun speechListenerOf(a: DictateActivity): SpeechHelper.Listener {
        val helper = readField(a, "speech") as SpeechHelper?
            ?: throw AssertionError(
                "the card has no SpeechHelper, so no capture was started and " +
                    "this state is about nothing"
            )
        return SpeechHelper::class.java.getDeclaredField("listener")
            .apply { isAccessible = true }
            .get(helper) as SpeechHelper.Listener
    }

    private fun readField(target: Any, name: String): Any? =
        DictateActivity::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
            .get(target)

    private fun mainSource(file: String): String {
        val candidates = listOf(
            File("app/src/main/java/dev/zacx/dicta/$file"),
            File("src/main/java/dev/zacx/dicta/$file"),
            File("../app/src/main/java/dev/zacx/dicta/$file"),
        )
        val found = candidates.firstOrNull { it.isFile }
        assertTrue(
            "could not locate $file from ${File(".").absolutePath} — " +
                "refusing to report a pass on a scan that read nothing",
            found != null,
        )
        return found!!.readText()
    }
}
