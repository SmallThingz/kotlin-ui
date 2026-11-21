package smallthingz.ui.components

import smallthingz.ui.animation.core.spring

/** Visual recipes for decorations retained alongside scrollable content. */
data class NativeListDecorationStyle(
    val refresh: NativePullRefreshIndicatorStyle = NativePullRefreshIndicatorStyle(),
    val leadingDisc: NativeLeadingDiscStyle = NativeLeadingDiscStyle(),
    val alertCard: NativeAlertCardStyle = NativeAlertCardStyle(),
    val pendingSave: NativePendingSaveProgressStyle = NativePendingSaveProgressStyle(),
    val recordingPlayer: NativeRecordingPlayerLayout = NativeRecordingPlayerLayout(),
) {
    companion object {
        val Default = NativeListDecorationStyle()
    }
}

data class NativePendingSaveProgressStyle(
    val spinnerStrokeDp: Float = 2.5f,
    val overlayAlphaByte: Int = 0x22,
) {
    init { require(overlayAlphaByte in 0..255) }

    fun spinnerColor(palette: NativeColorScheme): Int = palette.primary
    fun overlayColor(palette: NativeColorScheme): Int =
        (spinnerColor(palette) and 0x00ffffff) or (overlayAlphaByte shl 24)
}

/** Insets, gaps and fixed slots for an expanded recording row's player. */
data class NativeRecordingPlayerLayout(
    val sideInsetDp: Float = 14f,
    val waveformHeightDp: Float = 128f,
    val controlGapDp: Float = 2f,
    val errorGapDp: Float = 8f,
    val headingBottomGapDp: Float = 5f,
    val headingGlyphDp: Float = 17f,
    val headingGlyphGapDp: Float = 7f,
)

data class NativeAlertCardStyle(
    val radiusDp: Float = 18f,
    val dividerDp: Float = 1f,
    val alertOutlineAlpha: Float = .45f,
    val alertFillAlpha: Float = .42f,
    val dividerAlpha: Float = .55f,
) {
    fun border(palette: NativeColorScheme, alert: Boolean): Int =
        if (alert) srgbColorWithAlpha(palette.error, alertOutlineAlpha) else palette.outlineVariant

    fun fill(palette: NativeColorScheme, alert: Boolean): Int =
        if (alert) srgbColorWithAlpha(palette.errorContainer, alertFillAlpha) else palette.surfaceContainerHigh

    fun dividerColor(border: Int): Int = srgbColorWithAlpha(border, dividerAlpha)

    fun statusTint(palette: NativeColorScheme, alert: Boolean): Int = if (alert) palette.error else palette.muted
}

data class NativePullRefreshIndicatorStyle(
    val diameterDp: Float = 40f,
    val glyphDp: Float = 16f,
    val elevationDp: Float = 3f,
    val strokeDp: Float = 2.5f,
    val arcRadiusDp: Float = 5.5f,
    val arrowWidthDp: Float = 10f,
    val arrowHeightDp: Float = 5f,
    val pullingAlpha: Float = .3f,
    val effectStiffness: Float = 1600f,
) {
    val effects = spring<Float>(dampingRatio = 1f, stiffness = effectStiffness)

    fun fill(palette: NativeColorScheme): Int = palette.surfaceContainerHigh
    fun tint(palette: NativeColorScheme): Int = palette.onSurfaceVariant
}

data class NativeLeadingDiscStyle(
    val slotWidthDp: Float = 72f,
    val slotHeightDp: Float = 68f,
    val diameterDp: Float = 44f,
    val radiusDp: Float = 22f,
    val glyphDp: Float = 24f,
    val contentDp: Float = 22f,
)
