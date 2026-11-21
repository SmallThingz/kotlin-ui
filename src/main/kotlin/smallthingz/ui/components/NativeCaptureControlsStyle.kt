package smallthingz.ui.components

import kotlin.math.roundToInt

/** Geometry and state colors for a two-row capture control. */
data class NativeCaptureControlsStyle(
    val widthDp: Int = 256,
    val heightDp: Int = 112,
    val selectorWidthDp: Int = 242,
    val selectorHeightDp: Int = 54,
    val selectorSpacingDp: Int = 6,
    val actionHeightDp: Int = 70,
    val actionSpacingDp: Int = 8,
    val actionSizeDp: Int = 54,
    val actionGlyphSizeDp: Int = 25,
    val actionCameraDepth: Float = 24f,
    val disabledActionInkAlpha: Float = .28f,
    val disabledActionRippleAlpha: Float = .38f,
) {
    init {
        require(widthDp > 0 && heightDp > 0)
        require(selectorWidthDp in 1..widthDp && selectorHeightDp in 1..heightDp)
        require(actionHeightDp in 1..heightDp && selectorHeightDp >= heightDp - actionHeightDp)
        require(selectorSpacingDp >= 0 && actionSpacingDp >= 0 && actionSizeDp >= 0 && actionGlyphSizeDp >= 0)
        require(actionCameraDepth.isFinite() && actionCameraDepth > 0f)
        require(disabledActionInkAlpha in 0f..1f && disabledActionRippleAlpha in 0f..1f)
    }

    fun preferredWidthPx(density: Float): Int = (widthDp * density).roundToInt()
    fun preferredHeightPx(density: Float): Int = (heightDp * density).roundToInt()
    fun selectorSpacingPx(density: Float): Int = (selectorSpacingDp * density).roundToInt()
    fun actionGlyphSizePx(density: Float): Int = (actionGlyphSizeDp * density).roundToInt()

    fun actionInk(palette: NativeColorScheme, enabled: Boolean, destructive: Boolean): Int = when {
        !enabled -> srgbColorWithAlpha(palette.muted, disabledActionInkAlpha)
        destructive -> palette.error
        else -> palette.ink
    }

    fun actionRipple(palette: NativeColorScheme, enabled: Boolean): Int =
        if (enabled) palette.ink else srgbColorWithAlpha(palette.ink, disabledActionRippleAlpha)

    internal fun resolve(density: Float, segment: NativeBufferSegmentStyle): NativeCaptureControlMetrics =
        NativeCaptureControlMetrics(
            preferredWidth = preferredWidthPx(density),
            preferredHeight = preferredHeightPx(density),
            selectorWidth = (selectorWidthDp * density).roundToInt(),
            selectorHeight = (selectorHeightDp * density).roundToInt(),
            selectorSpacing = selectorSpacingPx(density),
            segmentWidth = segment.preferredWidthPx(density),
            actionHeight = (actionHeightDp * density).roundToInt(),
            actionSpacing = (actionSpacingDp * density).roundToInt(),
            actionSize = (actionSizeDp * density).roundToInt(),
        )

    // The original path scales with the measured View. Rounded child placements are resolved independently.
    internal fun outlineSelectorHeight(height: Float): Float = height * (selectorHeightDp.toFloat() / heightDp)
    internal fun outlineActionTop(height: Float): Float = height * ((heightDp - actionHeightDp).toFloat() / heightDp)
    internal fun outlineSelectorWidth(width: Float): Float = width * (selectorWidthDp.toFloat() / widthDp)

    companion object {
        val Default = NativeCaptureControlsStyle()
    }
}

internal data class NativeCaptureControlMetrics(
    val preferredWidth: Int,
    val preferredHeight: Int,
    val selectorWidth: Int,
    val selectorHeight: Int,
    val selectorSpacing: Int,
    val segmentWidth: Int,
    val actionHeight: Int,
    val actionSpacing: Int,
    val actionSize: Int,
)
