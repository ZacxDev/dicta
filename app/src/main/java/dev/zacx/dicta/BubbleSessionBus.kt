package dev.zacx.dicta

import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Carries a [DictationSession]'s results from [BubbleService], which owns the
 * session, to [DictateActivity], which renders them — and **buffers them while
 * no card is attached**.
 *
 * ## Why a buffer rather than just a listener
 *
 * In the bubble flow the recording is already finished when the card opens: tap
 * 2 stops capture, submits, and *then* starts the activity. Everything the
 * session says between those two moments — which tier it ran in, that the
 * upload started, and quite possibly the transcript itself if the service is
 * fast or the recording short — happens with nothing listening. Dropping it
 * would leave the card's engine badge and status line blank or, worse, stale:
 * the badge exists specifically so the user knows *which engine* produced the
 * text on screen, and a blank badge over server text is a dishonest UI, not a
 * cosmetic one. So the events are replayed in order on attach.
 *
 * ## What is deliberately NOT buffered
 *
 * [DictationSession.Callbacks.onLevel]. Level samples arrive on every capture
 * buffer, so replaying them would both flood the queue and animate a meter with
 * audio from the past. A level is only meaningful live.
 *
 * ## Why this shape
 *
 * A plain listener registry, following [OverlayVisibility] — which exists for
 * the same cross-component problem in the same process — rather than a binder,
 * a broadcast or a static `Service` reference. Like that object it is
 * unit-testable and cannot leak a destroyed component. [DictationSession]
 * already posts its callbacks to the main looper, so everything here runs on
 * the main thread and needs no further synchronisation beyond the collections.
 */
object BubbleSessionBus {

    private val sinks = CopyOnWriteArrayList<DictationSession.Callbacks>()
    private val pending = ArrayDeque<(DictationSession.Callbacks) -> Unit>()

    /** The session's end of the bus. Call from the session's callbacks. */
    val sink: DictationSession.Callbacks = object : DictationSession.Callbacks {

        override fun onTranscript(text: String, fromRemote: Boolean, isFinal: Boolean) =
            deliver { it.onTranscript(text, fromRemote, isFinal) }

        override fun onStatus(text: String) = deliver { it.onStatus(text) }

        /** Live-only; see the class header. */
        override fun onLevel(level: Double) {
            for (s in sinks) runCatching { s.onLevel(level) }
        }

        override fun onTier(tier: AudioCaptureLogic.Tier) = deliver { it.onTier(tier) }

        override fun onClientSilenced(silenced: Boolean) =
            deliver { it.onClientSilenced(silenced) }

        override fun onRecordingStopped() = deliver { it.onRecordingStopped() }
    }

    private fun deliver(action: (DictationSession.Callbacks) -> Unit) {
        if (sinks.isEmpty()) {
            // Deliberately unbounded. A `MAX_PENDING = 64` ring buffer stood
            // here and was removed as dead code, not relaxed: [reset] clears the
            // queue at the start of every recording, on the discard path and on
            // service destroy, so `pending` can only ever hold ONE recording's
            // events — a handful of status lines, a tier and a transcript. The
            // scenario the bound was justified by (a session whose card is never
            // opened, accumulating) is exactly what those `reset` calls make
            // unreachable. Add the bound back only together with a path that
            // can buffer across recordings.
            pending.addLast(action)
            return
        }
        // Never let one misbehaving sink stop the others — same reasoning as
        // OverlayVisibility.setVisible.
        for (s in sinks) runCatching { action(s) }
    }

    /** Attaches a card and replays, in order, everything it missed. */
    fun attach(callbacks: DictationSession.Callbacks) {
        sinks.add(callbacks)
        while (pending.isNotEmpty()) {
            val action = pending.pollFirst() ?: break
            runCatching { action(callbacks) }
        }
    }

    fun detach(callbacks: DictationSession.Callbacks) {
        sinks.remove(callbacks)
    }

    /**
     * Drops anything buffered. Called when a recording is **discarded** — a
     * cancelled recording must not have its status lines replayed into the next
     * card the user opens — and at the start of every new recording.
     */
    fun reset() {
        pending.clear()
    }

    /** Test seam only. */
    fun clearForTest() {
        sinks.clear()
        pending.clear()
    }
}
