package smallthingz.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsMenuGeometryTest {
    @Test fun belowStartAndRtlStart() {
        val anchor = NativeMenuBounds(100, 100, 240, 172)
        assertEquals(NativeMenuPosition(100,172,.5f,0f),
            nativeMenuPosition(anchor,400,800,140,144,false,48))
        assertEquals(NativeMenuPosition(40,172,.65f,0f),
            nativeMenuPosition(anchor,400,800,200,144,true,48))
    }

    @Test fun flipsAboveWhenBottomDoesNotFit() {
        assertEquals(NativeMenuPosition(210,456,100f/140f,1f),
            nativeMenuPosition(NativeMenuBounds(270,600,350,672),400,720,140,144,false,48))
    }

    @Test fun oversizedMenuUsesSourceBiasRoundingAndOverlapOrigin() {
        assertEquals(NativeMenuPosition(-1,-1,51f/103f,41f/103f),
            nativeMenuPosition(NativeMenuBounds(20,25,80,55),100,100,103,103,false,48))
    }

    @Test fun zeroSizeAndDisconnectedAnchorHaveSourceOrigins() {
        assertEquals(NativeMenuPosition(120,100,0f,0f),
            nativeMenuPosition(NativeMenuBounds(120,100,120,100),500,500,0,0,false,48))
    }
}
