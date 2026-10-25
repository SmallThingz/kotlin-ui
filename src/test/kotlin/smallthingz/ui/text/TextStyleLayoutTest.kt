package smallthingz.ui.text

import smallthingz.ui.graphics.Color
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.text.font.FontStyle
import smallthingz.ui.text.font.FontSynthesis
import smallthingz.ui.text.font.FontWeight
import smallthingz.ui.text.style.LineHeightStyle
import smallthingz.ui.text.style.TextDecoration
import smallthingz.ui.unit.sp
import smallthingz.ui.unit.LayoutDirection
import smallthingz.ui.unit.TextUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TextStyleLayoutTest {
    @Test fun unspecifiedFieldsResolveWithoutInventingLineHeight() {
        for (direction in LayoutDirection.entries) {
            val expected = TextStyle(
                color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Normal,
                fontStyle = FontStyle.Normal, fontSynthesis = FontSynthesis.All,
                fontFamily = FontFamily.Default, letterSpacing = 0.sp,
                platformStyle = PlatformTextStyle(false), lineHeightStyle = LineHeightStyle.Default,
                textDecoration = TextDecoration.None, layoutDirection = direction,
            )
            val resolved = resolveDefaults(TextStyle(), direction)
            assertEquals(expected, resolved)
            assertEquals(TextUnit.Unspecified, resolved.lineHeight)
            assertSame(resolved, resolveDefaults(resolved, direction))
        }
    }

    @Test fun completeStyleRetainsIdentityAndExplicitDirection() {
        val style = explicitStyle()
        assertSame(style, resolveDefaults(style, LayoutDirection.Ltr))
        assertSame(style, resolveDefaults(style, LayoutDirection.Rtl))
        // A missing line height is intentional: default resolution leaves it unspecified.
        val naturalHeight = style.copy(lineHeight = TextUnit.Unspecified)
        assertSame(naturalHeight, resolveDefaults(naturalHeight, LayoutDirection.Ltr))
    }

    @Test fun missingPlatformStyleReusesDefaultWithoutReplacingExplicitStyles() {
        val first = resolveDefaults(TextStyle(), LayoutDirection.Ltr).platformStyle
        val second = resolveDefaults(TextStyle(fontSize = 18.sp), LayoutDirection.Rtl).platformStyle
        assertEquals(PlatformTextStyle(false), first)
        assertSame(first, second)
        for (padding in listOf(false, true)) {
            val explicit = PlatformTextStyle(padding)
            val resolved = resolveDefaults(TextStyle(platformStyle = explicit), LayoutDirection.Ltr)
            assertSame(explicit, resolved.platformStyle)
        }
    }

    @Test fun eachMissingFieldStillResolvesWhilePreservingExplicitValues() {
        val style = explicitStyle()
        val cases = listOf(
            style.copy(color = Color.Unspecified) to style.copy(color = Color.Black),
            style.copy(fontSize = TextUnit.Unspecified) to style.copy(fontSize = 14.sp),
            style.copy(fontWeight = null) to style.copy(fontWeight = FontWeight.Normal),
            style.copy(fontStyle = null) to style.copy(fontStyle = FontStyle.Normal),
            style.copy(fontSynthesis = null) to style.copy(fontSynthesis = FontSynthesis.All),
            style.copy(fontFamily = null) to style.copy(fontFamily = FontFamily.Default),
            style.copy(letterSpacing = TextUnit.Unspecified) to style.copy(letterSpacing = 0.sp),
            style.copy(platformStyle = null) to style.copy(platformStyle = PlatformTextStyle(false)),
            style.copy(lineHeightStyle = null) to style.copy(lineHeightStyle = LineHeightStyle.Default),
            style.copy(textDecoration = null) to style.copy(textDecoration = TextDecoration.None),
            style.copy(layoutDirection = null) to style.copy(layoutDirection = LayoutDirection.Ltr),
        )
        for ((unresolved, expected) in cases) {
            assertEquals(expected, resolveDefaults(unresolved, LayoutDirection.Ltr))
        }
    }

    private fun explicitStyle() = TextStyle(
        color = Color.Red, fontSize = 23.sp, fontWeight = FontWeight.Bold,
        fontStyle = FontStyle.Italic, fontSynthesis = FontSynthesis.None,
        fontFamily = FontFamily.Serif, letterSpacing = .3.sp, lineHeight = 31.sp,
        platformStyle = PlatformTextStyle(true),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        textDecoration = TextDecoration.Underline, layoutDirection = LayoutDirection.Rtl,
    )

    @Test fun foregroundAndDecorationKeepCachedGeometry() {
        val original = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
        val drawChange = original.copy(color = Color.Red, textDecoration = TextDecoration.Underline)
        assertTrue(original.hasSameLayoutAffectingAttributes(drawChange))
        assertTrue(drawChange.hasSameLayoutAffectingAttributes(original))
        assertFalse(original == drawChange)
    }

    @Test fun unresolvedDefaultsRemainDistinctForCacheInvalidation() {
        val unresolved = TextStyle(fontSize = 14.sp)
        assertFalse(unresolved.hasSameLayoutAffectingAttributes(unresolved.copy(fontWeight = FontWeight.Normal)))
        assertFalse(unresolved.hasSameLayoutAffectingAttributes(unresolved.copy(platformStyle = PlatformTextStyle(false))))
    }

    @Test fun lineHeightTrimmingAndFontTrackingInvalidateGeometry() {
        val original = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None))
        assertFalse(original.hasSameLayoutAffectingAttributes(original.copy(letterSpacing = .1.sp)))
        assertFalse(original.hasSameLayoutAffectingAttributes(original.copy(
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both))))
        assertTrue(original.hasSameLayoutAffectingAttributes(original.copy()))
    }
}
