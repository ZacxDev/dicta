package dev.zacx.dicta

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The channel that takes the floating bubble off the screen for a capture.
 * Without it, MediaProjection composites Dicta's own overlay into Dicta's own
 * screenshot.
 */
class OverlayVisibilityTest {

    @Before
    fun setUp() = OverlayVisibility.clearForTest()

    @After
    fun tearDown() = OverlayVisibility.clearForTest()

    @Test
    fun sinksReceiveBothStates() {
        val seen = mutableListOf<Boolean>()
        OverlayVisibility.register { seen.add(it) }

        OverlayVisibility.setVisible(false)
        OverlayVisibility.setVisible(true)

        assertEquals(listOf(false, true), seen)
    }

    @Test
    fun anUnregisteredSinkStopsReceiving() {
        val seen = mutableListOf<Boolean>()
        val sink: (Boolean) -> Unit = { seen.add(it) }
        OverlayVisibility.register(sink)
        OverlayVisibility.setVisible(false)
        OverlayVisibility.unregister(sink)
        OverlayVisibility.setVisible(true)

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

        OverlayVisibility.setVisible(true)

        assertEquals(listOf(true), seen)
    }
}
