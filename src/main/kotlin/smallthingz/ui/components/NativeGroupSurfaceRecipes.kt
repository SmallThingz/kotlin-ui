package smallthingz.ui.components

import android.content.Context
import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import smallthingz.ui.graphics.Color
import smallthingz.ui.graphics.compositeOver
import smallthingz.ui.graphics.toArgb
import kotlin.math.ln

/** Shared surface recipes. Children, dividers and any selection animation belong to the caller. */
data class NativeGroupSurfaceRecipes(
    val settingsGroup: NativeGroupSurfaceRecipe = NativeGroupSurfaceRecipe(20f),
    val settingsChoices: NativeGroupSurfaceRecipe = NativeGroupSurfaceRecipe(16f),
    val settingsWarning: NativeGroupSurfaceRecipe = NativeGroupSurfaceRecipe(14f),
    val rangeControls: NativeGroupSurfaceRecipe = NativeGroupSurfaceRecipe(28f),
    val emptyLibrary: NativeGroupSurfaceRecipe = NativeGroupSurfaceRecipe(28f, NativeGroupFill.RAISED, outlined = false),
    val recordingInfoRow: NativeGroupSurfaceRecipe = NativeGroupSurfaceRecipe(14f, NativeGroupFill.HIGHEST, outlined = false),
    val rangeBufferPill: NativeGroupSurfaceRecipe = NativeGroupSurfaceRecipe(16f, NativeGroupFill.PRIMARY_CONTAINER,
        outlined = false, fillAlpha = .32f),
    val rangeTimeBubble: NativeRangeTimeBubbleStyle = NativeRangeTimeBubbleStyle(),
    val feedback: NativeFeedbackSurfaceStyle = NativeFeedbackSurfaceStyle(),
    val recordingRow: NativeRecordingRowStyle = NativeRecordingRowStyle(),
    val rangeTimeline: NativeRangeTimelineStyle = NativeRangeTimelineStyle(),
    val recordingInfoLayout: NativeRecordingInfoRowLayout = NativeRecordingInfoRowLayout(),
) {
    companion object {
        val Default = NativeGroupSurfaceRecipes()
    }
}

enum class NativeGroupFill {
    FIELD, RAISED, HIGHEST, PRIMARY_CONTAINER;

    internal fun color(colors: NativeColorScheme): Int = when (this) {
        FIELD -> colors.field
        RAISED -> colors.raised
        HIGHEST -> colors.surfaceContainerHighest
        PRIMARY_CONTAINER -> colors.primaryContainer
    }
}

/** A retained group's shape and paint. Outlined groups use the subtle group border role. */
data class NativeGroupSurfaceRecipe(
    val cornerRadiusDp: Float,
    val fill: NativeGroupFill = NativeGroupFill.FIELD,
    val outlined: Boolean = true,
    val fillAlpha: Float? = null,
) {
    fun create(context: Context, onPaintedSurfaceChanged: (View) -> Unit = {}): NativeGroupLayout =
        NativeGroupLayout(context, cornerRadiusDp, onPaintedSurfaceChanged = onPaintedSurfaceChanged)

    fun fillColor(colors: NativeColorScheme): Int {
        val color = fill.color(colors)
        return if (fillAlpha == null) color else srgbColorWithAlpha(color, fillAlpha)
    }

    fun borderColor(colors: NativeColorScheme): Int = if (outlined) colors.border else 0

    fun bind(surface: NativeGroupLayout, colors: NativeColorScheme) {
        surface.setSurfacePreset(colors.surfacePreset)
        surface.setPalette(fillColor(colors), borderColor(colors))
    }
}

/** Waveform viewport and boundary-control geometry within a range editor. */
data class NativeRangeTimelineStyle(
    val sideInsetDp: Float = 12f,
    val waveformTopDp: Float = 42f,
    val waveformHeightDp: Float = 146f,
    val endpointWidthDp: Float = 44f,
)

data class NativeRecordingInfoRowLayout(
    val horizontalInsetDp: Float = 14f,
    val verticalInsetDp: Float = 11f,
    val labelGapDp: Float = 2f,
)

/** Active time bubbles gain elevation and use the primary-container colors. */
data class NativeRangeTimeBubbleStyle(
    val cornerRadiusDp: Float = 10f,
    val activeElevationDp: Float = 1f,
    val widthDp: Float = 84f,
    val heightDp: Float = 28f,
    val topDp: Float = 3f,
    val textHorizontalInsetDp: Float = 5f,
    val textVerticalInsetDp: Float = 2f,
) {
    fun create(
        context: Context,
        density: () -> Float,
        onPaintedSurfaceChanged: (View) -> Unit = {},
    ): NativeGroupLayout = NativeGroupLayout(context, cornerRadiusDp, onPaintedSurfaceChanged = onPaintedSurfaceChanged).apply {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height,
                    minOf(cornerRadiusDp * density(), minOf(view.width, view.height) * .5f))
            }
        }
    }

    fun bind(surface: NativeGroupLayout, colors: NativeColorScheme, active: Boolean, density: Float): Int {
        surface.setPalette(if (active) colors.primaryContainer else colors.surfaceContainerHighest, 0)
        surface.elevation = if (active) activeElevationDp * density else 0f
        return if (active) colors.onPrimaryContainer else colors.onSurfaceVariant
    }
}

enum class NativeFeedbackTone { INFO, SUCCESS, ERROR }

data class NativeFeedbackSurfaceStyle(
    val cornerRadiusDp: Float = 18f,
    val tonalElevationDp: Float = 2f,
    val contentStartDp: Float = 16f,
    val contentEndDp: Float = 8f,
    val contentVerticalInsetDp: Float = 8f,
    val actionGapDp: Float = 8f,
) {
    private val tonalAlpha = ((4.5f * ln(tonalElevationDp + 1f)) + 2f) / 100f

    fun create(context: Context, onPaintedSurfaceChanged: (View) -> Unit = {}): NativeGroupLayout =
        NativeGroupLayout(context, cornerRadiusDp, onPaintedSurfaceChanged = onPaintedSurfaceChanged)

    fun fillColor(colors: NativeColorScheme, tone: NativeFeedbackTone): Int {
        val fill = when (tone) {
            NativeFeedbackTone.INFO -> colors.raised
            NativeFeedbackTone.SUCCESS -> colors.primaryContainer
            NativeFeedbackTone.ERROR -> colors.errorContainer
        }
        return if (fill == colors.surface && tonalElevationDp > 0f)
            Color(colors.surfaceTint).copy(alpha = tonalAlpha).compositeOver(Color(fill)).toArgb() else fill
    }

    fun inkColor(colors: NativeColorScheme, tone: NativeFeedbackTone): Int = when (tone) {
        NativeFeedbackTone.INFO -> colors.onSurface
        NativeFeedbackTone.SUCCESS -> colors.onPrimaryContainer
        NativeFeedbackTone.ERROR -> colors.onErrorContainer
    }

    fun bind(surface: NativeGroupLayout, colors: NativeColorScheme, tone: NativeFeedbackTone): Int {
        surface.setPalette(fillColor(colors, tone), 0)
        return inkColor(colors, tone)
    }
}

/** Paint values sampled by the owner's recording row transition. */
data class NativeRecordingRowPaint(
    val background: Int,
    val border: Int,
    val disc: Int,
    val icon: Int,
    val contentAlpha: Float,
)

data class NativeRecordingRowStyle(
    val surface: NativeGroupSurfaceRecipe = NativeGroupSurfaceRecipe(16f),
    val selectedFillAlpha: Float = .56f,
    val selectedBorderAlpha: Float = .48f,
    val selectedDiscAlpha: Float = .18f,
) {
    fun target(colors: NativeColorScheme, selected: Boolean) = NativeRecordingRowPaint(
        background = if (selected) srgbColorWithAlpha(colors.primaryContainer, selectedFillAlpha) else surface.fillColor(colors),
        border = if (selected && surface.outlined) srgbColorWithAlpha(colors.primary, selectedBorderAlpha) else surface.borderColor(colors),
        disc = if (selected) srgbColorWithAlpha(colors.primary, selectedDiscAlpha) else colors.raised,
        icon = if (selected) colors.primary else colors.ink,
        contentAlpha = 1f,
    )
}
