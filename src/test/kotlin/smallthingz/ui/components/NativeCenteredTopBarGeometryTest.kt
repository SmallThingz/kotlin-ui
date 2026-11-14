package smallthingz.ui.components


import org.junit.Assert.assertEquals
import org.junit.Test

class NativeCenteredTopBarGeometryTest {
    @Test fun oddWidthTitleCentersAgainstWholeBarAndMirrorsRelativePlacement() {
        val ltr = centeredTopBarPlacement(301, 64, 52, 48, 100, 31, 52, 48, 1f, false)
        val rtl = centeredTopBarPlacement(301, 64, 52, 48, 100, 31, 52, 48, 1f, true)
        assertEquals(101, ltr.titleX)
        assertEquals(100, rtl.titleX)
        assertEquals(16, ltr.titleY) // Source integer division, unlike center alignment's rounded X.
        assertEquals(8, ltr.navigationY)
        assertEquals(0, ltr.navigationX)
        assertEquals(249, rtl.navigationX)
        assertEquals(249, ltr.actionX)
        assertEquals(0, rtl.actionX)
    }

    @Test fun asymmetricActionsConstrainTitleAfterCenteringAgainstWholeBar() {
        val source = centeredTopBarPlacement(301, 64, 102, 48, 147, 28, 52, 48, 1f, false)
        assertEquals(102, source.titleX)
        val opposite = centeredTopBarPlacement(301, 64, 52, 48, 147, 28, 102, 48, 1f, false)
        assertEquals(52, opposite.titleX)
    }

    @Test fun sourceHeightCanGrowWithMultilineTitleAndPreservesCenterDivision() {
        val source = centeredTopBarPlacement(421, 197, 137, 126, 147, 197, 137, 126, 421f / 160f, false)
        assertEquals(0, source.titleY)
        assertEquals(35, source.navigationY)
        assertEquals(137, source.titleX)
    }

    @Test fun cutoutAndBarsUnionBeforeConsumedInsetSubtractionWithoutImeOrBottom() {
        assertEquals(NativeTopBarInsets(17, 22, 0), NativeTopBarInsets.remaining(
            barsLeft = 20, barsTop = 24, barsRight = 0,
            cutoutLeft = 12, cutoutTop = 30, cutoutRight = 3,
            consumedLeft = 3, consumedTop = 8, consumedRight = 4))
    }

}
