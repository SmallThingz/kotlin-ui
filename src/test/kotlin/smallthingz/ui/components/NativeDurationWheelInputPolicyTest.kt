package smallthingz.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDurationWheelInputPolicyTest {
    @Test
    fun disabledWheel_rejectsNewPointerInput() {
        assertFalse(
            rangeDurationWheelInputAllowed(
                enabled = false,
                interactionAlreadyActive = false,
            ),
        )
    }

    @Test
    fun disabledWheel_allowsAcceptedPointerInteractionToReachTerminalEvent() {
        assertTrue(
            rangeDurationWheelInputAllowed(
                enabled = false,
                interactionAlreadyActive = true,
            ),
        )
    }

    @Test
    fun enabledWheel_acceptsPointerInput() {
        assertTrue(
            rangeDurationWheelInputAllowed(
                enabled = true,
                interactionAlreadyActive = false,
            ),
        )
    }
}
