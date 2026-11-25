package smallthingz.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import smallthingz.ui.text.PlatformTextStyle
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.font.FontWeight
import smallthingz.ui.text.style.LineHeightStyle
import smallthingz.ui.unit.sp

class NativeButtonRecipeTest {
    private val recipes = NativeUiAppearance.Default.buttons
    private val palette = NativeButtonPalette(
        accent = 0xff111122.toInt(), onAccent = 0xff222233.toInt(),
        content = 0xff333344.toInt(), muted = 0xff444455.toInt(),
        quietSurface = 0xff555566.toInt(), tonalSurface = 0xff666677.toInt(),
        onTonal = 0xff777788.toInt(), errorSurface = 0xff888899.toInt(),
        onError = 0xff9999aa.toInt(),
    )

    @Test fun naturalAndFooterActionsKeepSourceGeometryAndTypography() {
        val inheritedBody = TextStyle.Default.copy(
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        ).merge(TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal))
        val label = inheritedBody.merge(TextStyle(fontSize = 14.sp, lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp))
        assertEquals(label, recipes.filled.labelStyle)
        assertSame(recipes.filled.labelStyle, recipes.footerContinue.labelStyle)
        assertSame(recipes.text.labelStyle, recipes.footerBack.labelStyle)
        assertEquals(NativeMaterialButtonStyle(24, 8, 58, 40, 19, 7), recipes.filled.surface)
        assertEquals(NativeMaterialButtonStyle(12, 8, 58, 40, 19, 7), recipes.text.surface)
        assertEquals(recipes.filled.surface.copy(assignedSize = true), recipes.footerContinue.surface)
        assertEquals(recipes.text.surface.copy(assignedSize = true), recipes.footerBack.surface)
        assertEquals(48, recipes.filled.minimumTouchTargetDp)
        assertEquals(48, recipes.text.minimumTouchTargetDp)
        assertTrue(recipes.filled.elevated)
        assertTrue(recipes.footerContinue.elevated)
        assertFalse(recipes.text.elevated)
        assertFalse(recipes.footerBack.elevated)
    }

    @Test fun naturalActionsKeepEnabledDisabledAndDestructiveColors() {
        assertPaint(recipes.filled, palette.accent, palette.onAccent)
        assertPaint(recipes.text, 0, palette.accent)
        assertEquals(palette.accent, recipes.text.enabledPaint.indication?.resolve(palette))
        assertPaint(recipes.destructive, palette.errorSurface, palette.onError)
        assertPaint(recipes.destructiveText, 0, palette.onError)
        assertPaint(recipes.tonal, palette.tonalSurface, palette.onTonal)
        assertPaint(recipes.filled, 0x1f333344, 0x61333344, enabled = false)
        assertPaint(recipes.destructive, 0x1f333344, 0x61333344, enabled = false)
        assertPaint(recipes.text, 0, 0x61333344, enabled = false)
    }

    @Test fun footerEmphasisDoesNotChangeEnablednessOrOverrideDisabledPaint() {
        assertPaint(recipes.footerBack, 0, palette.accent)
        assertPaint(recipes.footerBack, 0, 0x61444455, enabled = false)
        assertPaint(recipes.footerContinue, palette.accent, palette.onAccent)
        assertPaint(recipes.footerContinue, palette.quietSurface, palette.content, emphasized = false)
        assertPaint(recipes.footerContinue, 0x1a333344, 0x61444455, enabled = false, emphasized = false)
    }

    @Test fun customPresetDerivesVariantsFromItsChosenRecipesAndTypography() {
        val label = recipes.text.labelStyle.copy(fontSize = 18.sp)
        val filled = recipes.filled.copy(
            surface = recipes.filled.surface.copy(horizontalPaddingDp = 26),
            minimumTouchTargetDp = 56,
        )
        val icon = recipes.raisedIcon.copy(surface = recipes.raisedIcon.surface.copy(verticalPaddingDp = 2))
        val preset = NativeButtonRecipes(labelStyle = label, filled = filled, raisedIcon = icon)
        assertSame(filled, preset.filled)
        assertSame(filled.labelStyle, preset.footerContinue.labelStyle)
        assertEquals(26, preset.destructive.surface.horizontalPaddingDp)
        assertEquals(26, preset.footerContinue.surface.horizontalPaddingDp)
        assertEquals(56, preset.footerContinue.minimumTouchTargetDp)
        assertEquals(56, preset.destructive.minimumTouchTargetDp)
        assertSame(label, preset.text.labelStyle)
        assertSame(label, preset.footerBack.labelStyle)
        assertSame(label, preset.neutralText.labelStyle)
        assertSame(label, preset.tonal.labelStyle)
        assertSame(icon, preset.raisedIcon)
        assertEquals(2, preset.largeRaisedIcon.surface.verticalPaddingDp)
    }

    private fun assertPaint(recipe: NativeButtonRecipe, container: Int, content: Int,
                            enabled: Boolean = true, emphasized: Boolean = true) {
        val paint = recipe.paint(enabled, emphasized)
        assertEquals(container, paint.container.resolve(palette, paint.containerOpacity))
        assertEquals(content, paint.content.resolve(palette, paint.contentOpacity))
    }
}
