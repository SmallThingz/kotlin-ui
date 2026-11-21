package smallthingz.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeCaptureControlsStyleTest {
    @Test fun defaultUsesIndependentDpRoundingForRowsAndProportionsForTheOutline() {
        val style = NativeCaptureControlsStyle.Default
        val pixels = style.resolve(1.1f, NativeBufferSegmentStyle())

        assertEquals(282, pixels.preferredWidth)
        assertEquals(123, pixels.preferredHeight)
        assertEquals(266, pixels.selectorWidth)
        assertEquals(59, pixels.selectorHeight)
        assertEquals(7, pixels.selectorSpacing)
        assertEquals(123, pixels.segmentWidth)
        assertEquals(77, pixels.actionHeight)
        assertEquals(9, pixels.actionSpacing)
        assertEquals(59, pixels.actionSize)
        assertEquals(28, style.actionGlyphSizePx(1.1f))
        assertEquals(266.578125f, style.outlineSelectorWidth(282f), .0001f)
        assertEquals(59.30357f, style.outlineSelectorHeight(123f), .0001f)
        assertEquals(46.125f, style.outlineActionTop(123f), .0001f)
    }

    @Test fun customAppearanceChangesBothRowMetricsAndOutlineProportions() {
        val style = NativeCaptureControlsStyle.Default.copy(
            widthDp = 240, heightDp = 100, selectorWidthDp = 200, selectorHeightDp = 48,
            selectorSpacingDp = 4, actionHeightDp = 60, actionSpacingDp = 5, actionSizeDp = 40,
            actionGlyphSizeDp = 22, disabledActionInkAlpha = .5f, disabledActionRippleAlpha = .25f,
        )
        val colors = NativeColorScheme.fallback(false).copy(
            onSurface = 0xff253040.toInt(), onSurfaceVariant = 0xff506070.toInt(), error = 0xffa01223.toInt(),
        )
        val pixels = style.resolve(1.25f, NativeBufferSegmentStyle(preferredWidthDp = 96))

        assertEquals(300, pixels.preferredWidth)
        assertEquals(125, pixels.preferredHeight)
        assertEquals(250, pixels.selectorWidth)
        assertEquals(60, pixels.selectorHeight)
        assertEquals(5, pixels.selectorSpacing)
        assertEquals(120, pixels.segmentWidth)
        assertEquals(75, pixels.actionHeight)
        assertEquals(6, pixels.actionSpacing)
        assertEquals(50, pixels.actionSize)
        assertEquals(28, style.actionGlyphSizePx(1.25f))
        assertEquals(150f, style.outlineSelectorWidth(180f), .0001f)
        assertEquals(38.4f, style.outlineSelectorHeight(80f), .0001f)
        assertEquals(32f, style.outlineActionTop(80f), .0001f)
        assertEquals(colors.ink, style.actionInk(colors, enabled = true, destructive = false))
        assertEquals(colors.error, style.actionInk(colors, enabled = true, destructive = true))
        assertEquals(0x80506070.toInt(), style.actionInk(colors, enabled = false, destructive = true))
        assertEquals(colors.ink, style.actionRipple(colors, enabled = true))
        assertEquals(0x40253040, style.actionRipple(colors, enabled = false))
    }
}
