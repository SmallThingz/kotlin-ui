package smallthingz.ui.reverb.home

import android.view.View
import android.view.View.MeasureSpec
import kotlin.math.roundToInt

/** Places the capture body and the save cue without encroaching on the fixed action cluster. */
object ReverbCaptureLayout {
    internal const val SIDE_DP = 18f
    private const val STATUS_BOTTOM_DP = 142

    fun measure(width: Int, height: Int, density: Float, home: View?, range: View?, status: View) {
        val side = (SIDE_DP * density).roundToInt()
        val innerWidth = (width - side * 2).coerceAtLeast(0)
        home?.measure(exact(width), exact(height))
        range?.measure(exact(innerWidth), exact(height))
        status.measure(atMost(innerWidth), atMost((height - (STATUS_BOTTOM_DP * density).roundToInt()).coerceAtLeast(0)))
    }

    fun layout(width: Int, height: Int, density: Float, home: View?, range: View?, status: View) {
        home?.layout(0, 0, width, height)
        val side = (SIDE_DP * density).roundToInt()
        range?.let { it.layout(side, 0, side + it.measuredWidth, height) }
        val x = ((width - status.measuredWidth) * .5f).roundToInt()
        val bottom = (height - (STATUS_BOTTOM_DP * density).roundToInt()).coerceAtLeast(0)
        status.layout(x, bottom - status.measuredHeight, x + status.measuredWidth, bottom)
    }

    private fun exact(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
    private fun atMost(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.AT_MOST)
}
