package smallthingz.ui.common

import java.lang.reflect.Modifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import smallthingz.ui.components.NativeAnchoredDropdown
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeUiAppearance
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.text.font.FontStyle
import smallthingz.ui.text.font.FontSynthesis
import smallthingz.ui.text.font.FontWeight
import smallthingz.ui.text.font.ResolvedTypeface
import smallthingz.ui.unit.Density

class DropdownThemeBoundaryTest {
    private fun unusedResolver() = object : FontFamily.Resolver {
        override fun resolve(fontFamily: FontFamily?, fontWeight: FontWeight, fontStyle: FontStyle,
            fontSynthesis: FontSynthesis): ResolvedTypeface = error("Theme binding must not resolve fonts")
    }

    @Test fun windowTypographyReplacementPreservesAppearanceColorsAndIdentityNoOp() {
        val original = ControlTheme(NativeUiAppearance.Default, NativeColorScheme.fallback(false), Density(1f), unusedResolver())
        assertSame(original, original.withTypography(original.density, original.fontResolver))
        val density = Density(2f, 1.5f)
        val resolver = unusedResolver()
        val changed = original.withTypography(density, resolver)
        assertSame(original.appearance, changed.appearance)
        assertSame(original.colors, changed.colors)
        assertSame(density, changed.density)
        assertSame(resolver, changed.fontResolver)
    }

    @Test fun globalColorAndTypographyChangesComposeInEitherOrder() {
        val original = ControlTheme(NativeUiAppearance.Default, NativeColorScheme.fallback(false), Density(1f), unusedResolver())
        val colors = NativeColorScheme.fallback(true)
        val density = Density(2f, 1.5f)
        val resolver = unusedResolver()
        val colorFirst = original.withColorScheme(colors).withTypography(density, resolver)
        val textFirst = original.withTypography(density, resolver).withColorScheme(colors)
        for (theme in listOf(colorFirst, textFirst)) {
            assertSame(original.appearance, theme.appearance)
            assertSame(colors, theme.colors)
            assertSame(density, theme.density)
            assertSame(resolver, theme.fontResolver)
        }
    }

    @Test fun ordinaryPublicJavaConstructorsRequireThemeInsteadOfRawPalette() {
        val publicConstructors = NativeAnchoredDropdown::class.java.constructors.filterNot { it.isSynthetic }
        assertTrue(publicConstructors.isNotEmpty())
        for (constructor in publicConstructors) {
            assertTrue(constructor.parameterTypes.contains(ControlTheme::class.java))
            assertFalse(constructor.parameterTypes.contains(NativeColorScheme::class.java))
        }
        val methods = NativeAnchoredDropdown::class.java.declaredMethods.filter {
            Modifier.isPublic(it.modifiers) && !it.isSynthetic
        }
        assertFalse(methods.any { it.name.startsWith("setNoiseIntensity") || it.name.startsWith("setOutlineWidthDp") })
        val factories = NativeAnchoredDropdown.Companion::class.java.declaredMethods
            .filter { it.name.startsWith("forCommonField") }
        assertTrue(factories.isNotEmpty())
        assertTrue(factories.all { it.isSynthetic })
    }

    @Test fun publicFieldHasNoPerComponentPopupPaintOverride() {
        val methods = DropdownField::class.java.methods.filterNot { it.isSynthetic }
        assertFalse(methods.any { it.name == "setPopupOutline" || it.name == "setNoiseIntensity" })
    }
}
