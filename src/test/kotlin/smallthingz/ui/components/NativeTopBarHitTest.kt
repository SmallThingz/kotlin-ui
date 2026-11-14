package smallthingz.ui.components

import org.junit.Assert.*
import org.junit.Test

class NativeTopBarHitTest {
    @Test fun lowerZDirectBrandBeatsIncidentExpandedEdge() {
        val targets = listOf(NativeTopBarHitBounds(0f, 0f, 46f, 46f, 15f),
            NativeTopBarHitBounds(45f, 0f, 46f, 46f, 23f))
        assertEquals(1, nativeTopBarHitIndex(targets, 46.5f, 23f, true, 48f))
    }
    @Test fun circleCornerIsNotAnAdmittedDirectHit() {
        val targets = listOf(NativeTopBarHitBounds(0f, 0f, 46f, 46f, 23f),
            NativeTopBarHitBounds(0f, 0f, 46f, 46f, 0f))
        assertEquals(1, nativeTopBarHitIndex(targets, 1f, 1f, true, 48f))
        assertEquals(1, nativeTopBarHitIndex(targets, 1f, 1f, false, 48f))
    }
    @Test fun closestNearHitBeatsEarlierFartherNearHit() {
        val targets = listOf(NativeTopBarHitBounds(0f, 0f, 46f, 46f, 0f),
            NativeTopBarHitBounds(47f, 0f, 46f, 46f, 0f))
        assertEquals(1, nativeTopBarHitIndex(targets, 46.75f, 23f, true, 48f))
    }
    @Test fun equalNearDistanceKeepsHighestZSibling() {
        val targets = listOf(NativeTopBarHitBounds(0f, 0f, 46f, 46f, 0f),
            NativeTopBarHitBounds(47f, 0f, 46f, 46f, 0f))
        assertEquals(0, nativeTopBarHitIndex(targets, 46.5f, 23f, true, 48f))
    }
    @Test fun mouseDoesNotUseTouchExpansionOrClippedCorners() {
        val targets = listOf(NativeTopBarHitBounds(0f, 0f, 46f, 46f, 23f))
        assertNull(nativeTopBarHitIndex(targets, 46.5f, 23f, false, 48f))
        assertNull(nativeTopBarHitIndex(targets, 1f, 1f, false, 48f))
    }
}
