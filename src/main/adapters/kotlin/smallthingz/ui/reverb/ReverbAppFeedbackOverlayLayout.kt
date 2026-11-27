package smallthingz.ui.reverb

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Window-local placement for passive Reverb feedback. No persistent screen row is reserved.
 * The owner supplies its feedback view and delivery lifecycle; the library owns geometry.
 * Insets include the larger of the IME and system bottom edges, not both added together.
 */
abstract class ReverbAppFeedbackOverlayLayout(context: Context) : ViewGroup(context) {
    protected abstract val feedbackView: View
    protected abstract val feedbackDensity: Float
    protected abstract val feedbackBottomClearanceDp: Int
    private var safe = androidx.core.graphics.Insets.NONE

    /** Attach only for transient delivery; the owner removes the overlay on retirement. */
    fun attachTo(host: FrameLayout) {
        host.addView(this, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun refreshInsets(host: View) {
        val insets = ViewCompat.getRootWindowInsets(host)
        val bars = insets?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            ?: androidx.core.graphics.Insets.NONE
        val ime = insets?.getInsets(WindowInsetsCompat.Type.ime()) ?: androidx.core.graphics.Insets.NONE
        val next = androidx.core.graphics.Insets.of(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
        if (next != safe) { safe = next; requestLayout() }
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val height = MeasureSpec.getSize(h)
        feedbackView.measure(MeasureSpec.makeMeasureSpec((width - safe.left - safe.right - dp(36)).coerceAtLeast(0), MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec((height - safe.top - safe.bottom - dp(feedbackBottomClearanceDp)).coerceAtLeast(0), MeasureSpec.AT_MOST))
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val x = safe.left + (width - safe.left - safe.right - feedbackView.measuredWidth) / 2
        val bottom = height - safe.bottom - dp(feedbackBottomClearanceDp)
        feedbackView.layout(x, bottom - feedbackView.measuredHeight, x + feedbackView.measuredWidth, bottom)
    }

    private fun dp(value: Int) = (value * feedbackDensity).roundToInt()
}
