package smallthingz.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeCenteredStatusStyleTest {
    @Test fun statusSpacingReusesTheTruncatedOuterUnit() {
        assertEquals(NativeCenteredStatusSpacing(32, 64, 16),
            NativeUiAppearance.Default.statusContent.spacing(1f))
        assertEquals(NativeCenteredStatusSpacing(41, 82, 20),
            NativeUiAppearance.Default.statusContent.spacing(1.3f))
    }

    @Test fun alternatePaddingStillSharesThatUnitAcrossThePanel() {
        val preset = NativeUiAppearance.Default.copy(statusContent = NativeCenteredStatusStyle(paddingDp = 24f))
        assertEquals(NativeCenteredStatusSpacing(31, 62, 15), preset.statusContent.spacing(1.3f))
    }
}
