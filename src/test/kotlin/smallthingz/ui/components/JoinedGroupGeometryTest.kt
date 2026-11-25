package smallthingz.ui.components


import smallthingz.ui.geometry.roundedGroupGeometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JoinedGroupGeometryTest {
    @Test fun recordingBorderRetainsRawDensityInsteadOfSettingsCeil() {
        val recording = joinedGroupGeometry(300f, 180f, 2.625f, NativeGroupEdges(true, true))
        val settings = roundedGroupGeometry(300f, 180f, 2.625f)
        assertEquals(2.625f, recording.strokeWidth, 0f)
        assertEquals(3f, settings.strokeWidth, 0f)
        assertEquals(1.3125f, recording.strokeInset, 0f)
        assertEquals(40.6875f, recording.strokeRadius, 0f)
    }

    @Test fun sectionEdgesRoundOnlyItsOuterRows() {
        val first = joinedGroupGeometry(300f, 180f, 2.625f, NativeGroupEdges(true, false))
        val middle = joinedGroupGeometry(300f, 180f, 2.625f, NativeGroupEdges(false, false))
        val last = joinedGroupGeometry(300f, 180f, 2.625f, NativeGroupEdges(false, true))
        assertEquals(42f, first.topRadius, 0f)
        assertEquals(0f, first.bottomRadius, 0f)
        assertEquals(0f, middle.topRadius, 0f)
        assertEquals(0f, middle.bottomRadius, 0f)
        assertEquals(0f, last.topRadius, 0f)
        assertEquals(42f, last.bottomRadius, 0f)
    }

    @Test fun narrowFirstRowNeedsPathHitTestingButItsOutlineHasItsOwnRadius() {
        val first = joinedGroupGeometry(20f, 100f, 1f, NativeGroupEdges(true, false))
        assertEquals(16f, first.topRadius, 0f)
        assertFalse(first.cornersFit)
        assertEquals(9.5f, first.strokeRadius, 0f)
        val singleton = joinedGroupGeometry(20f, 100f, 1f, NativeGroupEdges(true, true))
        assertEquals(10f, singleton.topRadius, 0f)
        assertEquals(10f, singleton.bottomRadius, 0f)
        assertTrue(singleton.cornersFit)
    }

    @Test fun solidTopicCapsKeepGapFacingCornersSquareAtEveryDensity() {
        for (density in listOf(1f, 1.5f, 2.625f, 3f)) {
            val aboveGap = joinedGroupGeometry(300f, 180f, density,
                NativeGroupEdges(first = true, last = false, squareBottom = true))
            val betweenGaps = joinedGroupGeometry(300f, 180f, density,
                NativeGroupEdges(first = false, last = false, squareTop = true, squareBottom = true))
            val belowGap = joinedGroupGeometry(300f, 180f, density,
                NativeGroupEdges(first = false, last = true, squareTop = true))
            assertEquals(16f * density, aboveGap.topRadius, 0f)
            assertEquals(0f, aboveGap.bottomRadius, 0f)
            assertEquals(0f, betweenGaps.topRadius, 0f)
            assertEquals(0f, betweenGaps.bottomRadius, 0f)
            assertEquals(0f, belowGap.topRadius, 0f)
            assertEquals(16f * density, belowGap.bottomRadius, 0f)
            for (geometry in listOf(aboveGap, betweenGaps, belowGap)) {
                assertEquals(density, geometry.strokeWidth, 0f)
                assertTrue(geometry.cornersFit)
            }
        }
    }

    @Test fun shortSingletonUsesSourceVerticalCornerNormalization() {
        val geometry = joinedGroupGeometry(300f, 20f, 2f, NativeGroupEdges(true, true))
        assertEquals(10f, geometry.topRadius, 0f)
        assertEquals(10f, geometry.bottomRadius, 0f)
        assertEquals(9f, geometry.strokeRadius, 0f)
        assertTrue(geometry.cornersFit)
    }
}
