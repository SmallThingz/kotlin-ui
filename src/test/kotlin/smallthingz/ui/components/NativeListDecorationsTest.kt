package smallthingz.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeListDecorationsTest {
    @Test fun arrowDiscardsTheFirstFortyPercentAndTensionSaturates() {
        assertEquals(0f, nativePullRefreshArrow(.4f).scale, 0f)
        val full = nativePullRefreshArrow(1f)
        assertEquals(1f, full.scale, 0f)
        assertEquals(288f, full.endAngle - full.startAngle, .0001f)
        assertEquals(nativePullRefreshArrow(3f), nativePullRefreshArrow(10f))
    }

    @Test fun alertDividerReplacesTheOutlineAlphaAndKeepsTheSameHue() {
        val style = NativeListDecorationStyle.Default.alertCard
        val palette = NativeColorScheme.fallback(false)
        val outline = style.border(palette, alert = true)
        val divider = style.dividerColor(outline)
        assertEquals(115, outline ushr 24)
        assertEquals(140, divider ushr 24)
        assertEquals(outline and 0xffffff, divider and 0xffffff)

        val alternate = style.copy(dividerAlpha = .25f)
        assertEquals(64, alternate.dividerColor(outline) ushr 24)
    }
}
