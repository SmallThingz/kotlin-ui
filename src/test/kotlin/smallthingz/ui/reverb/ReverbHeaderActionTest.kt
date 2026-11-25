package smallthingz.ui.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class ReverbHeaderActionTest {
    @Test fun omittedIconRetainsBrowserAndSettingsSlotDefaults() {
        val action = ReverbHeaderAction("Open", {})

        assertEquals(ReverbIcon.Browser, action.withIcon(ReverbIcon.Browser).icon)
        assertEquals(ReverbIcon.Settings, action.withIcon(ReverbIcon.Settings).icon)
    }

    @Test fun explicitIconOverridesEitherSlotWithoutChangingActionContent() {
        val onClick: () -> Unit = {}
        val action = ReverbHeaderAction("Incidents", onClick, enabled = false, icon = ReverbIcon.Incidents)

        for (defaultIcon in listOf(ReverbIcon.Browser, ReverbIcon.Settings)) {
            val content = action.withIcon(defaultIcon)
            assertEquals(ReverbIcon.Incidents, content.icon)
            assertEquals("Incidents", content.label)
            assertFalse(content.enabled)
            assertSame(onClick, content.onClick)
        }
    }
}
