package smallthingz.ui.animation.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SpringStiffnessTest {
    @Test fun nonPositiveStiffnessIsRejectedAtConstruction() {
        for (stiffness in floatArrayOf(0f, -0f, -Float.MIN_VALUE, -1f, Float.NEGATIVE_INFINITY)) {
            try {
                FloatSpringSpec(stiffness = stiffness)
                fail("Accepted nonpositive stiffness $stiffness")
            } catch (expected: IllegalArgumentException) {
                assertEquals("Spring stiffness constant must be positive.", expected.message)
            }
        }
    }

    @Test fun rejectedAssignmentPreservesThePreviousSpring() {
        val spring = SpringSimulation(1f).also { it.stiffness = 200f }
        val before = spring.updateValues(-3f, 5f, 48L)
        for (stiffness in floatArrayOf(0f, -1f)) {
            try {
                spring.stiffness = stiffness
                fail("Accepted nonpositive stiffness $stiffness")
            } catch (_: IllegalArgumentException) {
                assertEquals(before.packedValue, spring.updateValues(-3f, 5f, 48L).packedValue)
            }
        }
        spring.stiffness = 1500f
        assertEquals(1500f, spring.stiffness, 0.001f)
        assertTrue(spring.updateValues(-3f, 5f, 48L).value.isFinite())
    }
}
