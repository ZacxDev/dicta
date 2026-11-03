package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [BubbleDictation.Event.HistoryTapped] — the decisions, with no Android in
 * sight. [BubbleSnipStateTest]'s file, applied to the reveal's other button.
 *
 * The wiring (that a view raises it, that the shell starts the picker) is
 * [BubbleHistoryButtonRevealTest]'s and needs a real service. What is pinned
 * here is what the machine DECIDES, including in the two states no caller can
 * reach today — because "the only caller cannot get there" is a claim about the
 * current wiring and this is a claim about the machine.
 *
 * ## 🔴 These are INVARIANT GUARDS, not regression coverage
 *
 * `Event.HistoryTapped` arrives with this change, so there is no pre-change
 * tree on which any of these could have been watched failing — a run against
 * the base commit would be a compile error, not a red test. What stands in for
 * red-at-base is the mutant batch in `scripts/mutants/history-picker.tsv`, each
 * row of which breaks one expression and names the class whose own assertion
 * must go red. The verdicts are in the pull request, stamped with the commit.
 *
 * The one behaviour here that is NOT new is the thing the operator kept: a tap
 * on a stored dictation opens the card through `DictateLaunch.historyIntent`.
 * That assertion was re-homed from the deleted
 * `BubbleHistoryRevealTest.tappingAChipOpensTheCardOnThatDictationThroughDictateLaunch`
 * into [HistoryPickerActivityTest], where the control that does it now lives.
 */
class BubbleHistoryStateTest {

    private fun idleMachine() = BubbleDictation { 0L }

    @Test
    fun historyTappedInIdleOpensThePickerAndNothingElse() {
        assertEquals(
            listOf(BubbleDictation.Effect.OpenHistoryPicker),
            idleMachine().on(BubbleDictation.Event.HistoryTapped),
        )
    }

    @Test
    fun historyTappedPutsTheButtonsAwayBeforeOpeningThePicker() {
        // 🔴 ORDER is the assertion, and membership alone would pass with the
        // two action buttons left floating over the picker's own window. The
        // prefix comes from `on()`'s one HideActions rule rather than from a
        // second dismissal path in the shell — which is the whole reason this
        // event exists instead of a view that calls startActivity itself.
        val machine = idleMachine()
        assertEquals(
            "precondition: the long press is what reveals the buttons",
            listOf(BubbleDictation.Effect.ShowActions),
            machine.on(BubbleDictation.Event.BubbleLongPressed),
        )
        assertEquals(
            listOf(
                BubbleDictation.Effect.HideActions,
                BubbleDictation.Effect.OpenHistoryPicker,
            ),
            machine.on(BubbleDictation.Event.HistoryTapped),
        )
    }

    @Test
    fun historyTappedWhileRecordingOpensNoPicker() {
        val machine = BubbleDictation { 0L }
        machine.on(BubbleDictation.Event.BubbleTapped(micGranted = true))
        assertEquals(
            "precondition: the machine is recording",
            BubbleDictation.State.RECORDING,
            machine.state,
        )
        assertEquals(
            "a history tap during a recording would put a window over a live " +
                "microphone",
            emptyList<BubbleDictation.Effect>(),
            machine.on(BubbleDictation.Event.HistoryTapped),
        )
    }

    @Test
    fun historyTappedWhileTranscribingOpensNoPicker() {
        var now = 0L
        val machine = BubbleDictation { now }
        machine.on(BubbleDictation.Event.BubbleTapped(micGranted = true))
        now = BubbleDictation.MIN_RECORDING_MS + 1
        machine.on(BubbleDictation.Event.BubbleTapped(micGranted = true))
        assertEquals(
            "precondition: the machine is transcribing",
            BubbleDictation.State.TRANSCRIBING,
            machine.state,
        )
        assertEquals(
            emptyList<BubbleDictation.Effect>(),
            machine.on(BubbleDictation.Event.HistoryTapped),
        )
    }

    @Test
    fun historyTappedNeverEmitsStartRecordingInAnyState() {
        // 🔴 Opening the history picker is not a dictation start. Asserted
        // over EVERY state rather than the one the button is reachable from,
        // because the hazard is a future arm that forwards to onTap.
        //
        // 🔴 RequestMicPermission is refused as well, and that is the arm a
        // reviewer is most likely to think harmless: it opens the card so the
        // card can ask — and a PLAIN card launch auto-starts a capture, which
        // is why StartSurfaceLedgerTest counts BubbleService#requestMicPermission
        // as a start surface in its own right.
        for (state in BubbleDictation.State.values()) {
            val machine = machineIn(state)
            val effects = machine.on(BubbleDictation.Event.HistoryTapped)
            assertFalse(
                "a history tap in $state emitted StartRecording: $effects",
                effects.contains(BubbleDictation.Effect.StartRecording),
            )
            assertFalse(
                "a history tap in $state emitted RequestMicPermission, which " +
                    "opens the card — and a PLAIN card launch auto-starts a " +
                    "capture: $effects",
                effects.contains(BubbleDictation.Effect.RequestMicPermission),
            )
            assertFalse(
                "a history tap in $state emitted SubmitRecording, i.e. it " +
                    "would upload whatever the microphone had: $effects",
                effects.contains(BubbleDictation.Effect.SubmitRecording),
            )
        }
    }

    @Test
    fun historyTappedLeavesTheStateExactlyAsItWas() {
        for (state in BubbleDictation.State.values()) {
            val machine = machineIn(state)
            machine.on(BubbleDictation.Event.HistoryTapped)
            assertEquals(
                "a history tap moved the machine out of $state",
                state,
                machine.state,
            )
        }
    }

    @Test
    fun theTwoRevealButtonsOpenTwoDIFFERENTPickers() {
        // 🔴 The guard that a copy-paste of the snip arm cannot satisfy. The
        // two arms are the same shape by design (BubbleDictation.onHistoryTapped
        // says why they are two arms and not one parameterised one), and the
        // failure that shape invites is an arm that emits the OTHER picker's
        // effect — at which point both buttons open the snippet picker and
        // every per-arm assertion above still passes, because each of them only
        // ever looks at one arm.
        assertEquals(
            listOf(BubbleDictation.Effect.OpenHistoryPicker),
            idleMachine().on(BubbleDictation.Event.HistoryTapped),
        )
        assertEquals(
            listOf(BubbleDictation.Effect.OpenSnipPicker),
            idleMachine().on(BubbleDictation.Event.SnipTapped),
        )
    }

    /** A machine driven into [state] through its own real transitions. */
    private fun machineIn(state: BubbleDictation.State): BubbleDictation {
        var now = 0L
        val machine = BubbleDictation { now }
        when (state) {
            BubbleDictation.State.IDLE -> Unit
            BubbleDictation.State.RECORDING ->
                machine.on(BubbleDictation.Event.BubbleTapped(micGranted = true))
            BubbleDictation.State.TRANSCRIBING -> {
                machine.on(BubbleDictation.Event.BubbleTapped(micGranted = true))
                now = BubbleDictation.MIN_RECORDING_MS + 1
                machine.on(BubbleDictation.Event.BubbleTapped(micGranted = true))
            }
        }
        assertEquals("the fixture did not reach $state", state, machine.state)
        return machine
    }
}
