package smallthingz.ui.graphics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import smallthingz.ui.geometry.MutableRect
import smallthingz.ui.geometry.Rect

class MatrixRectMappingTest {
    private val source = Rect(1f, 2f, 3f, 4f)

    @Test fun perspectiveBoundsIncludeAllFourCornersForBothOutputs() {
        val matrix = Matrix()
        matrix[0, 0] = 2f
        matrix[1, 0] = 1f
        matrix[3, 0] = 3f
        matrix[0, 1] = -1f
        matrix[1, 1] = 2f
        matrix[3, 1] = -4f
        matrix[0, 3] = 1f
        // Projected corners: (3.5,-.5), (4.5,1.5), (2.75,-.75), (3.25,.25).
        assertBoth(matrix, Rect(2.75f, -.75f, 4.5f, 1.5f))
    }

    @Test fun invalidHomogeneousDivisorAndNanCoordinateKeepDistinctResults() {
        for (divisor in floatArrayOf(0f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val matrix = Matrix()
            matrix[3, 3] = divisor
            assertBoth(matrix, Rect.Zero)
        }
        val matrix = Matrix()
        matrix[0, 0] = Float.NaN
        assertBoth(matrix, Rect(Float.NaN, 2f, Float.NaN, 4f))
    }

    @Test fun shortArraysReturnTheOriginalBoundsWithoutReadingCoefficients() {
        for (size in intArrayOf(0, 15)) {
            val matrix = Matrix(FloatArray(size))
            assertSame(source, matrix.map(source))
            assertBoth(matrix, source)
        }
    }

    private fun assertBoth(matrix: Matrix, expected: Rect) {
        assertEquals(expected, matrix.map(source))
        val mutable = MutableRect(source.left, source.top, source.right, source.bottom)
        matrix.map(mutable)
        assertEquals(expected, Rect(mutable.left, mutable.top, mutable.right, mutable.bottom))
    }
}
