package smallthingz.ui.reverb.home

import smallthingz.ui.components.NativeCaptureControlsStyle
import smallthingz.ui.components.captureActionTargetWidth
import kotlin.math.min
import kotlin.math.roundToInt

private const val HOME_ACTION_COUNT = 4

/** CaptureScreen.MainCaptureContent and BufferBlobPage: all edge rounding stays independent. */
data class ReverbHomeGeometry(
    val side: Int,
    val bodyWidth: Int,
    val blobAreaHeight: Int,
    val blobSize: Int,
    val clusterWidth: Int,
    val clusterHeight: Int,
    val bottomSpacer: Int,
)

fun reverbHomeGeometry(
    width: Int,
    height: Int,
    density: Float,
    captureControls: NativeCaptureControlsStyle,
): ReverbHomeGeometry {
    fun dp(value: Float) = (value * density).roundToInt()
    val side = dp(ReverbCaptureLayout.SIDE_DP)
    val bodyWidth = (width - side * 2).coerceAtLeast(0)
    // Column measures non-weight children first, in declaration order. A constrained size modifier
    // can shrink the cluster and then the spacer; it never manufactures additional room.
    val clusterHeight = min(captureControls.preferredHeightPx(density), height.coerceAtLeast(0))
    val bottom = min(dp(18f), (height - clusterHeight).coerceAtLeast(0))
    val blobArea = (height - clusterHeight - bottom).coerceAtLeast(0)
    // BoxWithConstraints reports px/density; Dp arithmetic then roundToPx mirrors the source.
    val blobDp = min(min(bodyWidth / density * .90f, blobArea / density * .94f), 372f)
    // Only the cluster can reclaim the side insets when its action targets need more room.
    val actionTarget = captureActionTargetWidth(density, dp(captureControls.actionSizeDp.toFloat()))
    val actionSpacing = dp(captureControls.actionSpacingDp.toFloat())
    val minimumActionRowWidth = HOME_ACTION_COUNT * actionTarget + (HOME_ACTION_COUNT + 1) * actionSpacing
    val clusterWidth = min(captureControls.preferredWidthPx(density),
        maxOf(bodyWidth, min(width, minimumActionRowWidth)))
    return ReverbHomeGeometry(side, bodyWidth, blobArea, dp(blobDp), clusterWidth, clusterHeight, bottom)
}
