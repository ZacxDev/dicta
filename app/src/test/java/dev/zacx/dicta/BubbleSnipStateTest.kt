package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [BubbleDictation.Event.SnipTapped] — the decisions, with no Android in sight.
 *
 * The wiring (that a view raises it, that the shell starts the picker) is
 * [BubbleSnipRevealTest]'s and needs a real service. What is pinned here is
 * what the machine DECIDES, including in the two states no caller can reach
 * today — because "the only caller cannot get there" is a claim about the
 * current wiring and this is a claim about the machine.
 */
class BubbleSnipStateTest {

    private fun idleMachine() = BubbleDictation { 0L }

    @Test
    fun snipTappedInIdleOpensThePickerAndNothingElse() {
        assertEquals(
            listOf(BubbleDictation.Effect.OpenSnipPicker),
            idleMachine().on(BubbleDictation.Event.SnipTapped),
        )
    }

    @Test
    fun snipTappedPutsTheRevealAwayBeforeOpeningThePicker() {
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
                BubbleDictation.Effect.OpenSnipPicker,
            ),
            machine.on(BubbleDictation.Event.SnipTapped),
        )
    }

    @Test
    fun snipTappedWhileRecordingOpensNoPicker() {
        val machine = BubbleDictation { 0L }
        machine.on(BubbleDictation.Event.BubbleTapped(micGranted = true))
        assertEquals(
            "precondition: the machine is recording",
            BubbleDictation.State.RECORDING,
            machine.state,
        )
        assertEquals(
            "a snip tap during a recording would put a window over a live " +
                "microphone",
            emptyList<BubbleDictation.Effect>(),
            machine.on(BubbleDictation.Event.SnipTapped),
        )
    }

    @Test
    fun snipTappedWhileTranscribingOpensNoPicker() {
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
            machine.on(BubbleDictation.Event.SnipTapped),
        )
    }

    @Test
    fun snipTappedNeverEmitsStartRecordingInAnyState() {
        // 🔴 The claim issue #38 makes explicitly: opening the picker is not a
        // dictation start. Asserted over EVERY state rather than the one the
        // button is reachable from, because the hazard is a future arm that
        // forwards to onTap.
        for (state in BubbleDictation.State.values()) {
            val machine = machineIn(state)
            val effects = machine.on(BubbleDictation.Event.SnipTapped)
            assertFalse(
                "a snip tap in $state emitted StartRecording: $effects",
                effects.contains(BubbleDictation.Effect.StartRecording),
            )
            assertFalse(
                "a snip tap in $state emitted RequestMicPermission, which " +
                    "opens the card — and a PLAIN card launch auto-starts a " +
                    "capture: $effects",
                effects.contains(BubbleDictation.Effect.RequestMicPermission),
            )
        }
    }

    @Test
    fun snipTappedLeavesTheStateExactlyAsItWas() {
        for (state in BubbleDictation.State.values()) {
            val machine = machineIn(state)
            machine.on(BubbleDictation.Event.SnipTapped)
            assertEquals(
                "a snip tap moved the machine out of $state",
                state,
                machine.state,
            )
        }
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
