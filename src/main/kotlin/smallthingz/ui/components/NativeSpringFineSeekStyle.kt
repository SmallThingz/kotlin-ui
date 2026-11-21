package smallthingz.ui.components

import kotlin.math.cosh
import kotlin.math.ln
import kotlin.math.roundToInt

/** Field, puck, and return motion for the spring fine-seek control. */
data class NativeSpringFineSeekStyle(
    val fieldInsetDp: Float = 10f,
    val puckDiameterDp: Float = 48f,
    val hitDiameterDp: Float = 64f,
    val glyphSizeDp: Float = 23f,
    val fieldCenterHandle: Float = .30f,
    val fieldPuckHandle: Float = .28f,
    val fieldEdgeAlpha: Float = .018f,
    val fieldAlpha: Float = .12f,
    val fieldHorizontalAlpha: Float = .13f,
    val fieldVerticalAlpha: Float = .06f,
    val fieldOverlayAlpha: Float = .035f,
    val fieldOverlayVerticalAlpha: Float = .045f,
    val horizontalTintExponent: Float = .72f,
    val verticalTintSpan: Float = .72f,
    val centerDotRadiusDp: Float = 1.6f,
    val centerDotAlpha: Float = .13f,
    val draggingDotAlpha: Float = .20f,
    val draggingGlowScale: Float = 1.28f,
    val draggingGlowAlpha: Float = .055f,
    val draggingGlowHorizontalAlpha: Float = .055f,
    val idleElevationDp: Float = 2f,
    val returnDurationNanos: Long = 210_000_000L,
    val returnStrength: Float = 3.4f,
    val rangeCompactHeightDp: Float = 120f,
    val rangeExpandedHeightDp: Float = 132f,
    val rangeCompactGapDp: Float = 2f,
    val rangeExpandedGapDp: Float = 8f,
    val libraryHeightDp: Float = 96f,
    val playbackGestureHeightDp: Float = 120f,
    val trimGestureHeightDp: Float = 104f,
) {
    init {
        require(verticalTintSpan.isFinite() && verticalTintSpan > 0f)
        require(returnDurationNanos >= 0L)
        require(returnStrength.isFinite() && returnStrength > 0f)
    }

    private val returnDenominator = cosh(returnStrength) - 1f
    internal val puckTonalAlpha = (4.5f * ln(idleElevationDp + 1f) + 2f) / 100f

    /** The same fraction applies to both axes, and reaches zero exactly at the terminal frame. */
    fun returnRemaining(elapsedNanos: Long): Float {
        if (returnDurationNanos == 0L) return 0f
        val t = (elapsedNanos.toFloat() / returnDurationNanos).coerceIn(0f, 1f)
        return (cosh(returnStrength * (1f - t)) - 1f) / returnDenominator
    }

    companion object {
        val Default = NativeSpringFineSeekStyle()
    }
}

/** The physical input uses a one-pixel minimum, while drawing keeps a one-dp minimum. */
class NativeSpringFineSeekGeometry(
    val width: Int,
    val height: Int,
    val density: Float,
    val verticalGestureHeight: Int = height,
    style: NativeSpringFineSeekStyle = NativeSpringFineSeekStyle.Default,
) {
    private val puckRadiusDp = style.puckDiameterDp * .5f
    val inputHorizontalTravel = (width * .5f - style.fieldInsetDp * density - puckRadiusDp * density).coerceAtLeast(1f)
    val inputVerticalTravel = (height * .5f - style.fieldInsetDp * density - puckRadiusDp * density).coerceAtLeast(1f)
    val verticalGestureTravel = (verticalGestureHeight * .5f - style.fieldInsetDp * density - puckRadiusDp * density)
        .coerceAtLeast(1f)
    val drawHorizontalTravel = (width / density * .5f - style.fieldInsetDp - puckRadiusDp).coerceAtLeast(1f) * density
    val drawVerticalTravel = (height / density * .5f - style.fieldInsetDp - puckRadiusDp).coerceAtLeast(1f) * density
    val inputCircleRadius = style.hitDiameterDp * .5f * density
    val fullHitSize = (style.hitDiameterDp * density).roundToInt()
    val hitWidth = minOf(width, fullHitSize)
    val hitHeight = minOf(height, fullHitSize)

    fun puckLeft(horizontal: Float): Int =
        ((width - hitWidth) * .5f).roundToInt() + (horizontal * drawHorizontalTravel).roundToInt()

    fun puckTop(constrainedVertical: Float): Int =
        ((height - hitHeight) * .5f).roundToInt() + (constrainedVertical * drawVerticalTravel).roundToInt()
}
