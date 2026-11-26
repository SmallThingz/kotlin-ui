package smallthingz.ui.components

import org.junit.Assert.*
import org.junit.Test

class NativeJoinedGridGeometryTest {
    @Test fun fourThenTwoHasOneInwardCornerAtTheShortRowBoundary() {
        val corners = joinedGridCorners(6, 4, 320f, 88f, 16f)
        assertEquals(6, corners.size)
        val corner = corners.single { it.concave }
        assertEquals(JoinedGridPoint(160f, 88f), corner.vertex)
        assertEquals(JoinedGridPoint(176f, 88f), corner.start)
        assertEquals(JoinedGridPoint(160f, 104f), corner.end)
    }
    @Test fun completeRowsHaveOnlyTheFourOuterCorners() {
        for (count in listOf(4, 8, 12)) {
            val corners = joinedGridCorners(count, 4, 320f, 88f, 16f)
            assertEquals(4, corners.size)
            assertTrue(corners.none { it.concave })
        }
    }
    @Test fun shortFirstRowDoesNotStretchToFillMissingCells() {
        val corners = joinedGridCorners(2, 4, 320f, 88f, 16f)
        assertEquals(160f, corners.maxOf { it.vertex.x }, 0f)
        assertEquals(4, corners.size)
    }
    @Test fun rtlMirrorsTheWholeShapeIncludingTheInwardTurn() {
        val ltr = joinedGridCorners(6, 4, 320f, 88f, 16f)
        val rtl = joinedGridCorners(6, 4, 320f, 88f, 16f, true)
        ltr.zip(rtl).forEach { (a, b) ->
            assertEquals(a.concave, b.concave)
            assertEquals(320f - a.vertex.x, b.vertex.x, 0f)
            assertEquals(320f - a.start.x, b.start.x, 0f)
            assertEquals(a.end.y, b.end.y, 0f)
        }
    }
    @Test fun everyPartialRowHasOneConcaveCornerAndFiniteBoundedCurves() {
        for (columns in 1..6) for (count in 1..80) for (width in listOf(1f, 221f, 480f)) {
            val corners = joinedGridCorners(count, columns, width, 88f, 16f)
            val rows = (count - 1) / columns + 1
            assertEquals(if (rows > 1 && count % columns != 0) 1 else 0, corners.count { it.concave })
            for (corner in corners) for (p in listOf(corner.start, corner.vertex, corner.end)) {
                assertTrue(p.x.isFinite() && p.x in 0f..width)
                assertTrue(p.y.isFinite() && p.y in 0f..rows * 88f)
            }
        }
    }
    @Test fun emptyAndZeroWidthGridsHaveNoOutline() {
        assertTrue(joinedGridCorners(0, 4, 320f, 88f, 16f).isEmpty())
        assertTrue(joinedGridCorners(6, 4, 0f, 88f, 16f).isEmpty())
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsZeroColumns() {
        joinedGridCorners(6, 0, 320f, 88f, 16f)
    }
}
