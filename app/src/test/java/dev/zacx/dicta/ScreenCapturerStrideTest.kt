package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The row-stride arithmetic, which is what shears a MediaProjection capture
 * diagonally when it is wrong.
 *
 * [ScreenCapturer.paddedWidthFor] is the function the shipping path calls —
 * `toBitmap` delegates to it rather than recomputing the expression inline — so
 * these assertions guard production, not a parallel test-only copy.
 *
 * Expected values are computed by hand, not from [ScreenCapturer]: a 1080-wide
 * RGBA_8888 plane whose rows are padded to a 64-byte boundary has rowStride
 * 4352 (1088 * 4), so 8 pixels of padding per row, so the staging bitmap must
 * be 1088 px wide. Bounds deliberately overshoot the step (1080 is not a
 * multiple of 64 / 16 px) so a mutant that drops the division or the multiply
 * cannot land exactly on the correct answer.
 */
class ScreenCapturerStrideTest {

    @Test
    fun paddedRowsWidenTheStagingBitmap() {
        assertEquals(1088, ScreenCapturer.paddedWidthFor(1080, 4352, 4))
    }

    @Test
    fun unpaddedRowsLeaveTheWidthAlone() {
        assertEquals(1080, ScreenCapturer.paddedWidthFor(1080, 4320, 4))
    }

    /** A second, pairwise-distinct point so a hardcoded constant cannot pass. */
    @Test
    fun aSecondGeometryWithADifferentPadding() {
        // 1440 wide, rows padded to 1472 px => rowStride 5888, 32 px padding.
        assertEquals(1472, ScreenCapturer.paddedWidthFor(1440, 5888, 4))
    }

    /**
     * A stride smaller than the row is a broken plane, not something to paper
     * over by copying garbage into a bitmap.
     */
    @Test
    fun aStrideSmallerThanTheRowIsRejected() {
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            ScreenCapturer.paddedWidthFor(1080, 4000, 4)
        }
        // Assert on THIS guard's own message: otherwise the test would stay
        // green off the pixelStride guard's error if the two were reordered.
        assertTrue(
            "wrong guard fired: ${thrown.message}",
            thrown.message!!.contains("rowStride(4000)")
        )
    }

    /**
     * A zero pixelStride is the divisor in `rowPadding / pixelStride`. Before
     * the arithmetic was consolidated, `paddedWidthFor` had no such guard and
     * would have thrown ArithmeticException from the division (while the inline
     * copy in `toBitmap` did guard it, with a different exception type). Pinned
     * here, and reachable: rowStride 4352 with pixelStride 0 gives a
     * non-negative rowPadding, so the row-width guard cannot reject it first.
     */
    @Test
    fun aZeroPixelStrideIsRejectedRatherThanDividingByZero() {
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            ScreenCapturer.paddedWidthFor(1080, 4352, 0)
        }
        assertTrue(
            "wrong guard fired: ${thrown.message}",
            thrown.message!!.contains("pixelStride(0)")
        )
    }

    /** Same, for a negative stride, which would silently invert the padding. */
    @Test
    fun aNegativePixelStrideIsRejected() {
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            ScreenCapturer.paddedWidthFor(1080, 4352, -4)
        }
        assertTrue(
            "wrong guard fired: ${thrown.message}",
            thrown.message!!.contains("pixelStride(-4)")
        )
    }
}
