package smallthingz.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import smallthingz.ui.text.NativeTypography
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.PlatformTextStyle
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.text.font.FontStyle
import smallthingz.ui.unit.em
import smallthingz.ui.unit.sp

class NativeStyleBoundaryTest {
    private val nonnegative: List<Pair<String, (Float) -> Any>> = listOf(
        "choice radius" to { NativeChoiceStyle(radiusDp = it) },
        "choice trailing icon" to { NativeChoiceStyle(trailingIconSizeDp = it) },
        "text field radius" to { NativeTextFieldStyle(radiusDp = it) },
        "text field vertical padding" to { NativeTextFieldStyle(verticalPaddingDp = it) },
        "text field compound gap" to { NativeTextFieldStyle(compoundDrawablePaddingDp = it) },
        "slider minimum height" to { NativeSliderStyle(minHeightDp = it) },
        "slider vertical padding" to { NativeSliderStyle(verticalPaddingDp = it) },
        "labeled outline" to { NativeLabeledTextFieldStyle(errorColor = 0, outlineWidthDp = it) },
        "labeled trailing gap" to { NativeLabeledTextFieldStyle(errorColor = 0, trailingRowGapDp = it) },
        "settings minimum height" to { NativeSettingsRowStyle(minHeightDp = it) },
        "settings detail gap" to { NativeSettingsRowStyle(detailGapDp = it) },
        "corner top start" to { NativeCornerRadii(it, 1f, 1f, 1f) },
        "corner bottom start" to { NativeCornerRadii(1f, 1f, 1f, it) },
        "menu corner radius" to { NativeAnchoredMenuRecipe(cornerRadiusDp = it) },
        "menu vertical padding" to { NativeAnchoredMenuRecipe(verticalPaddingDp = it) },
        "menu shadow" to { NativeAnchoredMenuRecipe(shadowDp = it) },
        "menu window shadow inset" to { NativeAnchoredMenuRecipe(windowShadowInsetDp = it) },
        "menu collapsed scale" to { NativeAnchoredMenuMotionStyle(collapsedScale = it) },
    )
    private val positiveText: List<Pair<String, (Float) -> Any>> = listOf(
        "settings title" to { NativeSettingsRowStyle(titleSizeSp = it) },
        "settings detail" to { NativeSettingsRowStyle(detailSizeSp = it) },
        "settings value" to { NativeSettingsRowStyle(valueSizeSp = it) },
    )

    @Test fun nonnegativeDimensionsAcceptSignedZeroAndRejectInvalidValues() {
        val rejected = floatArrayOf(-0.25f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
        val accepted = floatArrayOf(0f, -0.0f, 0.25f)
        for ((label, construct) in nonnegative) {
            for (value in rejected) assertThrows("$label: $value", IllegalArgumentException::class.java) { construct(value) }
            for (value in accepted) assertAccepted("$label: $value") { construct(value) }
        }
    }

    private fun assertAccepted(label: String, construct: () -> Unit) {
        try { construct() } catch (failure: Throwable) { throw AssertionError(label, failure) }
    }

    @Test fun settingsTextSizesRequirePositiveFiniteValues() {
        val rejected = floatArrayOf(0f, -0.0f, -0.25f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
        for ((label, construct) in positiveText) {
            for (value in rejected) assertThrows("$label: $value", IllegalArgumentException::class.java) { construct(value) }
            assertAccepted("$label: smallest positive") { construct(Float.MIN_VALUE) }
        }
    }

    @Test fun sliderStepsAndThumbDimensionsRespectLimits() {
        assertAccepted("zero steps") { NativeSliderStyle(steps = 0) }
        assertAccepted("largest supported steps") { NativeSliderStyle(steps = Int.MAX_VALUE - 1) }
        assertThrows(IllegalArgumentException::class.java) { NativeSliderStyle(steps = -1) }
        assertThrows(IllegalArgumentException::class.java) { NativeSliderStyle(steps = Int.MAX_VALUE) }
        val thumbs: List<Pair<String, (Float) -> Any>> = listOf(
            "thumb width" to { NativeSliderStyle(thumbWidthDp = it) },
            "thumb height" to { NativeSliderStyle(thumbHeightDp = it) },
            "pressed thumb width" to { NativeSliderStyle(pressedThumbWidthDp = it) },
        )
        for ((label, construct) in thumbs) {
            assertAccepted("$label: smallest positive") { construct(Float.MIN_VALUE) }
            for (value in floatArrayOf(0f, -0.0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
                assertThrows("$label: $value", IllegalArgumentException::class.java) { construct(value) }
            }
        }
    }

    @Test fun textStyleRetainsOptionalHeightWeightAndFiniteSpacingRules() {
        assertAccepted("no line height") { NativeTextStyle(lineHeightSp = null) }
        assertAccepted("smallest positive line height") { NativeTextStyle(lineHeightSp = Float.MIN_VALUE) }
        assertAccepted("minimum weight") { NativeTextStyle(weight = 1) }
        assertAccepted("maximum weight") { NativeTextStyle(weight = 1000) }
        assertAccepted("negative finite letter spacing") { NativeTextStyle(letterSpacing = -0.5f, letterSpacingSp = -0.5f) }
        for (value in floatArrayOf(0f, -0.0f, -0.25f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertThrows("line height: $value", IllegalArgumentException::class.java) { NativeTextStyle(lineHeightSp = value) }
        }
        for (weight in intArrayOf(0, 1001)) {
            assertThrows("weight: $weight", IllegalArgumentException::class.java) { NativeTextStyle(weight = weight) }
        }
        for (value in floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertThrows("letter spacing: $value", IllegalArgumentException::class.java) { NativeTextStyle(letterSpacing = value) }
            assertThrows("letter spacing sp: $value", IllegalArgumentException::class.java) { NativeTextStyle(letterSpacingSp = value) }
        }
    }

    @Test fun nativeTextUsesRoleFallbacksAndConvertsRelativeMetrics() {
        val title = NativeTypographyRole.TITLE_LARGE
        val body = NativeTypographyRole.BODY_MEDIUM
        val typography = NativeTypography(mapOf(
            title to TextStyle(fontSize = 1.em, lineHeight = 1.5.em, letterSpacing = .02.em,
                platformStyle = PlatformTextStyle(includeFontPadding = true)),
            body to TextStyle(fontSize = 17.sp, letterSpacing = .5.sp),
        ))
        val appearance = NativeUiAppearance(typography = typography)
        assertEquals(NativeTextStyle(sizeSp = 22f, lineHeightSp = 33f, weight = 700,
            letterSpacing = .02f, includeFontPadding = true), appearance.modals.typography.nativeTextStyle(title))
        assertEquals(NativeTextStyle(sizeSp = 17f, lineHeightSp = 20f, letterSpacingSp = .5f),
            appearance.compactField.style.textStyle)
    }

    @Test fun compactFieldInheritsWindowBodyTypographyUntilExplicitlyOverridden() {
        val appearance = NativeUiAppearance.Default
        val default = appearance.compactField.style
        assertTrue(default.filled)
        assertFalse(default.outlined)
        assertEquals(48f, default.minHeightDp, 0f)
        assertEquals(8f, checkNotNull(default.compoundDrawablePaddingDp), 0f)
        assertEquals(14f, default.textStyle.sizeSp, 0f)
        assertEquals(20f, checkNotNull(default.textStyle.lineHeightSp), 0f)
        val role = NativeTypographyRole.BODY_MEDIUM
        val typography = NativeTypography(mapOf(role to appearance.typography[role].copy(
            fontSize = 20.sp, lineHeight = 26.sp, fontFamily = FontFamily.Monospace,
            fontStyle = FontStyle.Italic,
        )))
        val inherited = appearance.copy(typography = typography).compactField.style.textStyle
        assertEquals(20f, inherited.sizeSp, 0f)
        assertEquals(26f, checkNotNull(inherited.lineHeightSp), 0f)
        assertEquals("monospace", inherited.fontFamily)
        assertTrue(inherited.italic)

        val explicit = NativeCompactFieldRecipe(default.copy(horizontalPaddingDp = 18f))
        val customized = appearance.copy(compactField = explicit).copy(typography = typography)
        assertSame(explicit, customized.compactField)
        assertEquals(inherited, customized.copy(compactField = null).compactField.style.textStyle)
    }
}
