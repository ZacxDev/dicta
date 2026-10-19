package dev.zacx.dicta

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The channel that takes the floating bubble off the screen.
 *
 * Two mechanisms use it — the transcription card being up
 * ([OverlayVisibility.Holder.CARD]) and a MediaProjection capture being in
 * flight ([OverlayVisibility.Holder.CAPTURE]) — and they overlap, because the
 * screenshot is taken *from* the card. These are the unit-level guards on the
 * composition; [BubbleHiddenWhileTheCardIsUpTest] drives the same thing end to
 * end through the real Activity and the real [DictaWindows].
 */
class OverlayVisibilityTest {

    private val card = OverlayVisibility.Holder.CARD
    private val capture = OverlayVisibility.Holder.CAPTURE

    @Before
    fun setUp() = OverlayVisibility.clearForTest()

    @After
    fun tearDown() = OverlayVisibility.clearForTest()

    @Test
    fun sinksReceiveBothStates() {
        val seen = mutableListOf<Boolean>()
        OverlayVisibility.register { seen.add(it) }

        OverlayVisibility.setHidden(card, true)
        OverlayVisibility.setHidden(card, false)

        assertEquals(listOf(false, true), seen)
    }

    @Test
    fun anUnregisteredSinkStopsReceiving() {
        val seen = mutableListOf<Boolean>()
        val sink: (Boolean) -> Unit = { seen.add(it) }
        OverlayVisibility.register(sink)
        OverlayVisibility.setHidden(card, true)
        OverlayVisibility.unregister(sink)
        OverlayVisibility.setHidden(card, false)

        assertEquals(listOf(false), seen)
    }

    /**
     * One broken sink must not prevent the others from being restored — a
     * half-restored UI is the failure mode that strands the user.
     */
    @Test
    fun aThrowingSinkDoesNotBlockTheOthers() {
        val seen = mutableListOf<Boolean>()
        OverlayVisibility.register { throw IllegalStateException("window gone") }
        OverlayVisibility.register { seen.add(it) }

        OverlayVisibility.setHidden(card, true)
        OverlayVisibility.setHidden(card, false)

        assertEquals(listOf(false, true), seen)
    }

    // --- composition ---------------------------------------------------------

    /**
     * 🔴 THE case the single boolean got wrong: the capture finishes while the
     * card is still up, and `restoreAfterCapture()` shows the bubble again —
     * on top of the card.
     */
    @Test
    fun releasingOneHolderDoesNotShowTheOverlayWhileAnotherHoldsIt() {
        val seen = mutableListOf<Boolean>()
        OverlayVisibility.register { seen.add(it) }

        OverlayVisibility.setHidden(card, true)
        OverlayVisibility.setHidden(capture, true)
        OverlayVisibility.setHidden(capture, false)

        assertFalse(
            "the capture's release made the overlay visible while the card " +
                "still holds it hidden",
            OverlayVisibility.isVisible()
        )
        assertEquals(
            "the overlay was hidden once and is still hidden, so the sinks " +
                "must have been told exactly once: $seen",
            listOf(false),
            seen,
        )

        OverlayVisibility.setHidden(card, false)
        assertTrue("the last holder released and nothing came back", OverlayVisibility.isVisible())
        assertEquals(listOf(false, true), seen)
    }

    /** The same sequence the other way round — the card closing first. */
    @Test
    fun theOverlayStaysHiddenUntilTheLastHolderReleases() {
        OverlayVisibility.setHidden(card, true)
        OverlayVisibility.setHidden(capture, true)

        OverlayVisibility.setHidden(card, false)
        assertFalse(
            "the card closed mid-capture and the bubble reappeared INTO the " +
                "screenshot being taken",
            OverlayVisibility.isVisible()
        )

        OverlayVisibility.setHidden(capture, false)
        assertTrue(OverlayVisibility.isVisible())
    }

    /**
     * 🔴 Idempotence is the reason this is a SET and not a depth counter. The
     * real call sites are unbalanced on purpose — `onDestroy` releases both
     * holders whatever happened, `ScreenshotSequencer` restores even when the
     * capture throws, `onStart`/`onStop` can run repeatedly inside one capture
     * — and every one of those would drive a counter permanently off zero.
     */
    @Test
    fun repeatedHidesAndReleasesAreNoOps() {
        val seen = mutableListOf<Boolean>()
        OverlayVisibility.register { seen.add(it) }

        OverlayVisibility.setHidden(card, true)
        OverlayVisibility.setHidden(card, true)
        OverlayVisibility.setHidden(card, true)
        OverlayVisibility.setHidden(card, false)
        OverlayVisibility.setHidden(card, false)
        // A release from a holder that never held it at all.
        OverlayVisibility.setHidden(capture, false)

        assertTrue(
            "three hides and three releases did not balance; a counter would " +
                "be stuck at +2 here and the bubble would never come back",
            OverlayVisibility.isVisible()
        )
        assertEquals(
            "the sinks saw $seen — they must be told only when the aggregate " +
                "actually changes, or every redundant call is another window " +
                "update",
            listOf(false, true),
            seen,
        )
    }

    @Test
    fun theOverlayStartsVisible() {
        assertTrue(OverlayVisibility.isVisible())
    }
}
