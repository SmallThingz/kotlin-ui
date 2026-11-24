package smallthingz.ui.components

import android.view.View
import kotlin.math.roundToInt

/** A pointer origin in the anchor View's local coordinates; contains no View or MotionEvent. */
data class NativeMenuOrigin(val x: Float, val y: Float) {
    companion object {
        /** Convert once at long-press dispatch, including transformed children and parent scrolling. */
        fun fromDescendant(anchor: View, descendant: View, x: Float, y: Float): NativeMenuOrigin? {
            if (!x.isFinite() || !y.isFinite()) return null
            val point = floatArrayOf(x, y)
            var current = descendant
            while (current !== anchor) {
                val parent = current.parent as? View ?: return null
                current.matrix.mapPoints(point)
                point[0] += current.left - parent.scrollX
                point[1] += current.top - parent.scrollY
                current = parent
            }
            return if (point[0].isFinite() && point[1].isFinite()) NativeMenuOrigin(point[0], point[1]) else null
        }
    }
}

/** Popup-owned scratch, reused on geometry frames; works on API28 as well as newer Android. */
internal class NativeMenuOriginMapper {
    private val point = FloatArray(4)
    private val location = IntArray(2)
    var x: Int = 0
        private set
    var y: Int = 0
        private set

    fun map(anchor: View, origin: NativeMenuOrigin) {
        point[0] = origin.x
        point[1] = origin.y
        point[2] = 0f
        point[3] = 0f
        var current = anchor
        while (true) {
            val parent = current.parent as? View ?: break
            current.matrix.mapPoints(point, 0, point, 0, 1)
            point[0] += current.left - parent.scrollX
            point[1] += current.top - parent.scrollY
            current = parent
        }
        // getLocationInWindow already includes the top View's transformed origin. Apply its
        // linear transform to the point without counting that translation a second time.
        current.matrix.mapPoints(point)
        current.getLocationInWindow(location)
        x = (point[0] - point[2] + location[0]).roundToInt()
        y = (point[1] - point[3] + location[1]).roundToInt()
    }
}
