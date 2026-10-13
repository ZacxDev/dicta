package dev.zacx.dicta

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The buffer that keeps the card's engine badge and status line honest.
 *
 * In the bubble flow the recording is finished *before* the card exists: tap 2
 * stops capture, submits, and then starts the activity. Everything the session
 * says in between — the tier, "Transcribing on the server…", and on a short
 * recording quite possibly the transcript itself — is said with nothing
 * listening. Dropping it leaves the badge blank over server text, which is a
 * dishonest UI rather than a cosmetic gap: that badge exists specifically so the
 * user knows *which* engine produced what they are reading.
 */
class BubbleSessionBusTest {

    private open class Recorder : DictationSession.Callbacks {
        val events = mutableListOf<String>()
        override fun onTranscript(text: String, fromRemote: Boolean, isFinal: Boolean) {
            events += "transcript:$text:$fromRemote:$isFinal"
        }
        override fun onStatus(text: String) { events += "status:$text" }
        override fun onLevel(level: Double) { events += "level:$level" }
        override fun onTier(tier: AudioCaptureLogic.Tier) { events += "tier:$tier" }
        override fun onClientSilenced(silenced: Boolean) { events += "silenced:$silenced" }
        override fun onRecordingStopped() { events += "stopped" }
    }

    @After
    fun tearDown() = BubbleSessionBus.clearForTest()

    @Test
    fun eventsSaidBeforeTheCardOpensAreReplayedInOrder() {
        BubbleSessionBus.clearForTest()
        BubbleSessionBus.sink.onStatus("uploading")
        BubbleSessionBus.sink.onTier(AudioCaptureLogic.Tier.REMOTE_ONLY_AFTER_TIMEOUT)
        BubbleSessionBus.sink.onTranscript("hello there", fromRemote = true, isFinal = true)

        val card = Recorder()
        BubbleSessionBus.attach(card)

        assertEquals(
            "the card missed what the session said before it opened — the " +
                "engine badge and status line would be blank over server text",
            listOf(
                "status:uploading",
                "tier:REMOTE_ONLY_AFTER_TIMEOUT",
                "transcript:hello there:true:true",
            ),
            card.events,
        )
    }

    @Test
    fun eventsAfterAttachGoStraightThroughAndAreNotAlsoReplayed() {
        BubbleSessionBus.clearForTest()
        val card = Recorder()
        BubbleSessionBus.attach(card)
        BubbleSessionBus.sink.onStatus("live")

        assertEquals(listOf("status:live"), card.events)

        // A second card must not receive the first one's live events: they were
        // delivered, not buffered.
        val second = Recorder()
        BubbleSessionBus.attach(second)
        assertEquals(emptyList<String>(), second.events)
    }

    @Test
    fun levelSamplesAreNeverBuffered() {
        // They arrive on every capture buffer. Replaying them would flood the
        // queue and animate the meter with audio from the past — a level is
        // only meaningful live.
        BubbleSessionBus.clearForTest()
        repeat(500) { BubbleSessionBus.sink.onLevel(it / 500.0) }

        val card = Recorder()
        BubbleSessionBus.attach(card)
        assertEquals(emptyList<String>(), card.events)

        // Positive control: a live level DOES reach an attached card, so the
        // empty replay above is a fact about buffering and not about the sink
        // being wired to nothing.
        BubbleSessionBus.sink.onLevel(0.5)
        assertEquals(listOf("level:0.5"), card.events)
    }

    @Test
    fun resetDropsEverythingBuffered() {
        // Called when a recording is discarded. Replaying a cancelled
        // recording's "Transcribing on the server…" into the next card the user
        // opens would be a lie about a recording that no longer exists.
        BubbleSessionBus.clearForTest()
        BubbleSessionBus.sink.onStatus("uploading")
        BubbleSessionBus.reset()

        val card = Recorder()
        BubbleSessionBus.attach(card)
        assertEquals(emptyList<String>(), card.events)
    }

    @Test
    fun theBufferIsBoundedAndKeepsTheMostRecentEvents() {
        // A session whose card is never opened must not grow the queue without
        // bound; and when it overflows, the events worth keeping are the LAST
        // ones — the transcript arrives at the end, the early chatter does not.
        BubbleSessionBus.clearForTest()
        repeat(500) { BubbleSessionBus.sink.onStatus("s$it") }

        val card = Recorder()
        BubbleSessionBus.attach(card)

        assertTrue(
            "the buffer is unbounded (${card.events.size} events replayed)",
            card.events.size < 200,
        )
        assertEquals(
            "the buffer dropped the newest events instead of the oldest",
            "status:s499",
            card.events.last(),
        )
    }

    @Test
    fun detachStopsDelivery() {
        BubbleSessionBus.clearForTest()
        val card = Recorder()
        BubbleSessionBus.attach(card)
        BubbleSessionBus.detach(card)
        BubbleSessionBus.sink.onStatus("after")
        assertEquals(emptyList<String>(), card.events)
    }

    @Test
    fun oneThrowingCardDoesNotStopAnother() {
        // Same reasoning as OverlayVisibility.setVisible: a detached-but-still
        // registered card throwing from a view call must not swallow the
        // transcript for the one that is on screen.
        BubbleSessionBus.clearForTest()
        val broken = object : Recorder() {
            override fun onStatus(text: String) = throw IllegalStateException("boom")
        }
        val good = Recorder()
        BubbleSessionBus.attach(broken)
        BubbleSessionBus.attach(good)

        BubbleSessionBus.sink.onStatus("important")
        assertEquals(listOf("status:important"), good.events)
    }
}
