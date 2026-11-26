package smallthingz.ui.reverb

import org.junit.Assert.*
import org.junit.Test

class ReverbHeaderActionTest {
    @Test fun alertReachesTheCanonicalToolbarWithoutChangingItsAction() {
        var calls = 0
        val bound = ReverbHeaderAction("Incidents", { calls++ }, icon = ReverbIcon.Incidents,
            alert = true).withIcon(ReverbIcon.Browser)
        assertTrue(bound.alert)
        assertEquals(ReverbIcon.Incidents, bound.icon)
        assertEquals("Incidents", bound.label)
        bound.onClick()
        assertEquals(1, calls)
    }
    @Test fun ordinaryActionsRemainMuted() {
        val bound = ReverbHeaderAction("Settings", {}).withIcon(ReverbIcon.Settings)
        assertFalse(bound.alert)
        assertEquals(ReverbIcon.Settings, bound.icon)
    }
}
