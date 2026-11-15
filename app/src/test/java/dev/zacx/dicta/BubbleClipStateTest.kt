package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [BubbleDictation.Event.ClipTapped] — the decisions, with no Android in sight.
 *
 * [BubbleSnipStateTest]'s file, applied to the third reveal button. The wiring
 * (that a view raises it, that the shell starts the picker) is
 * [BubbleClipRevealTest]'s and needs a real service. What is pinned here is what
 * the machine DECIDES, including in the two states no caller can reach today —
 * because "the only caller cannot get there" is a claim about the current
 * wiring and this is a claim about the machine.
 *
 * 🔴 **Invariant guards, not regression coverage** — the event is new.
 */
class BubbleClipStateTest {

    private fun idleMachine() = BubbleDictation { 0L }

    @Test
    fun clipTappedInIdleOpensThePickerAndNothingElse() {
        assertEquals(
            listOf(BubbleDictation.Effect.OpenClipPicker),
            idleMachine().on(BubbleDictation.Event.ClipTapped),
        )
    }

    @Test
    fun clipTappedPutsTheRevealAwayBeforeOpeningThePicker() {
        // 🔴 ORDER is the assertion, and membership alone would pass with
        // THREE action buttons left floating over the picker's own window. The
        // prefix comes from `on()`'s one HideActions rule rather than from a
        // second dismissal path in the shell.
        val machine = idleMachine()
        assertEquals(
            "precondition: the long press is what reveals the buttons",
            listOf(BubbleDictation.Effect.ShowActions),
            machine.on(BubbleDictation.Event.BubbleLongPressed),
        )
        assertEquals(
            listOf(
                BubbleDictation.Effect.HideActions,
                BubbleDictation.Effect.OpenClipPicker,
            ),
            machine.on(BubbleDictation.Event.ClipTapped),
        )
    }

    @Test
    fun clipTappedInEveryBusyStateOpensNoPicker() {
        for (state in listOf(
            BubbleDictation.State.RECORDING,
            BubbleDictation.State.TRANSCRIBING,
        )) {
            val machine = machineIn(state)
            assertEquals(
                "a clip tap in $state would put a window over a live " +
                    "microphone or an in-flight upload",
                emptyList<BubbleDictation.Effect>(),
                machine.on(BubbleDictation.Event.ClipTapped),
            )
        }
    }

    @Test
    fun clipTappedNeverEmitsStartRecordingInAnyState() {
        // 🔴 The claim at its widest: opening the picker is not a dictation
        // start. Asserted over EVERY state rather than the one the button is
        // reachable from, because the hazard is a future arm that forwards to
        // onTap — and this picker touches no microphone at all, so the claim is
        // stronger here than for its two siblings.
        for (state in BubbleDictation.State.values()) {
            val effects = machineIn(state).on(BubbleDictation.Event.ClipTapped)
            assertFalse(
                "a clip tap in $state emitted StartRecording: $effects",
                effects.contains(BubbleDictation.Effect.StartRecording),
            )
            assertFalse(
                "a clip tap in $state emitted RequestMicPermission, which " +
                    "opens the card — and a PLAIN card launch auto-starts a " +
                    "capture: $effects",
                effects.contains(BubbleDictation.Effect.RequestMicPermission),
            )
        }
    }

    @Test
    fun clipTappedLeavesTheStateExactlyAsItWas() {
        for (state in BubbleDictation.State.values()) {
            val machine = machineIn(state)
            machine.on(BubbleDictation.Event.ClipTapped)
            assertEquals(
                "a clip tap moved the machine out of $state",
                state,
                machine.state,
            )
        }
    }

    @Test
    fun theThreeRevealEventsOpenTHREEDIFFERENTPickers() {
        // 🔴 A RELATIONSHIP, not three restatements. Three same-shaped arms
        // added one after another is exactly where a copied line keeps the
        // previous effect — and the symptom is a button that opens the wrong
        // surface, which no single-arm test can see. Fails if any two agree.
        val effects = listOf(
            BubbleDictation.Event.HistoryTapped,
            BubbleDictation.Event.SnipTapped,
            BubbleDictation.Event.ClipTapped,
        ).map { idleMachine().on(it).single() }

        assertEquals(
            "two reveal buttons emit the same effect, so one of them opens " +
                "the wrong picker: $effects",
            3,
            effects.toSet().size,
        )
        assertEquals(
            listOf(
                BubbleDictation.Effect.OpenHistoryPicker,
                BubbleDictation.Effect.OpenSnipPicker,
                BubbleDictation.Effect.OpenClipPicker,
            ),
            effects,
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
    org.junit.Assert.assertEquals("the fixture did not reach $state", state, machine.state)
    return machine
}
