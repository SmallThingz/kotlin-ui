package smallthingz.ui.components

import org.junit.Assert.assertThrows
import org.junit.Test

class NativeStyleBoundaryTest {
    private val nonnegative: List<Pair<String, (Float) -> Any>> = listOf(
        "choice radius" to { NativeChoiceStyle(radiusDp = it) },
        "choice trailing icon" to { NativeChoiceStyle(trailingIconSizeDp = it) },
        "switch text" to { NativeSwitchStyle(textSizeSp = it) },
        "switch padding" to { NativeSwitchStyle(switchPaddingDp = it) },
        "button radius" to { NativeButtonStyle(radiusDp = it) },
        "button vertical padding" to { NativeButtonStyle(verticalPaddingDp = it) },
        "icon button radius" to { NativeIconButtonStyle(radiusDp = it) },
        "icon button inset" to { NativeIconButtonStyle(backgroundInsetDp = it) },
        "text field radius" to { NativeTextFieldStyle(radiusDp = it) },
        "text field vertical padding" to { NativeTextFieldStyle(verticalPaddingDp = it) },
        "slider minimum height" to { NativeSliderStyle(minHeightDp = it) },
        "slider vertical padding" to { NativeSliderStyle(verticalPaddingDp = it) },
        "labeled outline" to { NativeLabeledTextFieldStyle(errorColor = 0, outlineWidthDp = it) },
        "labeled trailing gap" to { NativeLabeledTextFieldStyle(errorColor = 0, trailingRowGapDp = it) },
        "settings minimum height" to { NativeSettingsRowStyle(minHeightDp = it) },
        "settings detail gap" to { NativeSettingsRowStyle(detailGapDp = it) },
        "corner top start" to { NativeCornerRadii(it, 1f, 1f, 1f) },
        "corner bottom start" to { NativeCornerRadii(1f, 1f, 1f, it) },
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

    @Test fun iconSizeInsetAndOptionalAlphaRespectTheirBoundaries() {
        assertAccepted("icon equal to target") { NativeIconButtonStyle(minSizeDp = 48f, iconSizeDp = 48f) }
        assertAccepted("inset below half target") { NativeIconButtonStyle(backgroundInsetDp = Math.nextDown(24f)) }
        assertThrows(IllegalArgumentException::class.java) { NativeIconButtonStyle(minSizeDp = 0f, iconSizeDp = 0f) }
        assertThrows(IllegalArgumentException::class.java) { NativeIconButtonStyle(minSizeDp = 48f, iconSizeDp = 49f) }
        assertThrows(IllegalArgumentException::class.java) { NativeIconButtonStyle(backgroundInsetDp = 24f) }
        val alphaFields: List<Pair<String, (Float?) -> Any>> = listOf(
            "container alpha" to { NativeIconButtonStyle(disabledContainerAlpha = it) },
            "content alpha" to { NativeIconButtonStyle(disabledContentAlpha = it) },
        )
        for ((label, construct) in alphaFields) {
            for (value in listOf(null, 0f, 1f)) assertAccepted("$label: $value") { construct(value) }
            for (value in floatArrayOf(-0.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
                assertThrows("$label: $value", IllegalArgumentException::class.java) { construct(value) }
            }
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
}
