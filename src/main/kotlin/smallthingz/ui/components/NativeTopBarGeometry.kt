package smallthingz.ui.components

import smallthingz.ui.geometry.Offset
import smallthingz.ui.geometry.Size
import smallthingz.ui.input.phase.NativePointerHitScore
import smallthingz.ui.input.phase.nativeMinimumTouchDistance
import kotlin.math.min
import kotlin.math.max
import kotlin.math.roundToInt

internal fun compactToolbarActionTop(contentHeight: Int, actionHeight: Int): Int =
    ((contentHeight - actionHeight) / 2f).roundToInt()

/** Actual clipped46dp visual bounds, not the optional48dp wrapper's rectangular hit box. */
data class NativeTopBarHitBounds(
    val left: Float, val top: Float, val width: Float, val height: Float, val radius: Float,
)

/** Source sibling priority: any admitted direct hit beats near hits; equal near hits retain z order. */
internal fun nativeTopBarHitIndex(
    highestZFirst: List<NativeTopBarHitBounds>, x: Float, y: Float, touch: Boolean, minimumTarget: Float,
): Int? {
    if (!x.isFinite() || !y.isFinite()) return null
    var best: NativePointerHitScore? = null
    var bestIndex: Int? = null
    for ((index, box) in highestZFirst.withIndex()) {
        val localX = x - box.left; val localY = y - box.top
        val radius = min(box.radius, min(box.width, box.height) / 2f).coerceAtLeast(0f)
        val inBounds = localX >= 0f && localY >= 0f && localX < box.width && localY < box.height
        val dx = localX - localX.coerceIn(radius, box.width - radius)
        val dy = localY - localY.coerceIn(radius, box.height - radius)
        val withinShape = inBounds && dx * dx + dy * dy <= radius * radius
        if (withinShape) return index
        if (!touch) continue
        val distance = nativeMinimumTouchDistance(Offset(localX, localY), Size(box.width, box.height),
            Size(minimumTarget, minimumTarget))
        if (!distance.isFinite()) continue
        val score = NativePointerHitScore(distance, false)
        if (best == null || score < best) { best = score; bestIndex = index }
    }
    return bestIndex
}

/** Concrete action geometry used by a toolbar's retained touch routing. */
interface NativeTopBarHitTarget { fun hitBounds(): NativeTopBarHitBounds }

/**
 * M3 1.4.0 MinimumInteractiveModifierNode reports the minimum even when size(46.dp)
 * constrains its apparent size. UI 1.11.2 Placeable first centers that real layout with
 * integer division; the minimum node then centers the visual using Float rounding.
 * An odd pixel difference therefore leaves a real one-pixel placement residual.
 */
fun nativeMinimumInteractiveVisualOffset(visualSize: Int, minimumSize: Int): Int {
    val realSize = max(visualSize, minimumSize)
    return (visualSize - realSize) / 2 + ((realSize - visualSize) / 2f).roundToInt()
}

