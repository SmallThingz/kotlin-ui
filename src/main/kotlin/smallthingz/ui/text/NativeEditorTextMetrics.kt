/*
 * Line-height metric calculation adapted from AndroidX ui-text 1.11.2,
 * LineHeightStyleSpan.android.kt. Copyright 2019 The Android Open Source Project.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package smallthingz.ui.text

import smallthingz.ui.unit.Density
import smallthingz.ui.unit.isSpecified
import smallthingz.ui.unit.sp
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Required construction input: use the reference Density's conversions in the parity harness.
 * There is deliberately no platform-SP default. ui-unit 1.11.2 backports nonlinear font scaling
 * on every supported OS; TypedValue/TextView conversion is not an equivalent substitute.
 * The owner reapplies styles with a current adapter when density/font-scale changes.
 */
interface NativeEditorTextScale {
    val fontScale: Float
    fun spToPixels(sp: Float): Float
    fun pixelsToSp(px: Float): Float
}

data class NativeEditorTextMetrics(
    val fontSizePx: Float,
    val lineHeightPx: Float,
    val letterSpacingEm: Float,
)

/** Mirrors ui-text 1.11.2's paragraph-level SP tracking and resolveLineHeightInPx. */
fun NativeTypographyMetrics.resolveEditorMetrics(scale: NativeEditorTextScale): NativeEditorTextMetrics {
    val sizePx = scale.spToPixels(fontSizeSp)
    val heightPx = if (scale.fontScale > 1.05f) {
        // Nonlinear scaling preserves the requested line-height/font-size proportion.
        lineHeightSp / scale.pixelsToSp(sizePx) * sizePx
    } else {
        scale.spToPixels(lineHeightSp)
    }
    return NativeEditorTextMetrics(
        fontSizePx = sizePx,
        lineHeightPx = heightPx,
        letterSpacingEm = if (sizePx == 0f) 0f else scale.spToPixels(letterSpacingSp) / sizePx,
    )
}

fun NativeTypographyMetrics.resolveEditorMetrics(density: Density): NativeEditorTextMetrics =
    resolveEditorMetrics(object : NativeEditorTextScale {
        override val fontScale get() = density.fontScale
        override fun spToPixels(sp: Float) = with(density) { sp.sp.toPx() }
        override fun pixelsToSp(px: Float) = with(density) { px.toSp().value }
    })

fun TextStyle.resolveEditorMetrics(density: Density): NativeEditorTextMetrics = with(density) {
    val sizePx = fontSize.toPx()
    val heightPx = when {
        lineHeight.isEm -> lineHeight.value * sizePx
        fontScale > 1.05f -> lineHeight.value / sizePx.toSp().value * sizePx
        else -> lineHeight.toPx()
    }
    val tracking = when {
        letterSpacing.isEm -> letterSpacing.value
        letterSpacing.isSpecified && sizePx != 0f -> letterSpacing.toPx() / sizePx
        else -> 0f
    }
    NativeEditorTextMetrics(sizePx, heightPx, tracking)
}

data class NativeEditorLineHeightMetrics(
    val firstAscent: Int,
    val ascent: Int,
    val descent: Int,
    val lastDescent: Int,
)

/** Fixed-mode subset of AndroidX's line-height span; no baseline offsets or visual nudges. */
fun nativeEditorLineHeightMetrics(
    ascent: Int,
    descent: Int,
    lineHeightPx: Float,
    alignment: NativeTypographyLineHeightAlignment,
    trimEdges: Boolean,
): NativeEditorLineHeightMetrics {
    val currentHeight = descent - ascent
    if (currentHeight <= 0) return NativeEditorLineHeightMetrics(ascent, ascent, descent, descent)
    val requestedHeight = ceil(lineHeightPx).toInt()
    val difference = requestedHeight - currentHeight
    val topRatio = when (alignment) {
        NativeTypographyLineHeightAlignment.PROPORTIONAL -> abs(ascent.toFloat()) / currentHeight
        NativeTypographyLineHeightAlignment.CENTER -> 0.5f
    }
    val descentDifference = ceil(difference * if (difference <= 0) topRatio else 1f - topRatio).toInt()
    val targetDescent = descent + descentDifference
    val targetAscent = targetDescent - requestedHeight
    return NativeEditorLineHeightMetrics(
        firstAscent = if (trimEdges) ascent else targetAscent,
        ascent = targetAscent,
        descent = targetDescent,
        lastDescent = if (trimEdges) descent else targetDescent,
    )
}
