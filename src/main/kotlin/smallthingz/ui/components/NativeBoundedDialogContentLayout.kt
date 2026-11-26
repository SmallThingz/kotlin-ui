package smallthingz.ui.components

import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import kotlin.math.roundToInt

/**
 * Natural-height dialog content with independently scrolling body and footer. The footer is measured
 * first, so a long body cannot push actions outside the window. An oversized footer scrolls without
 * compressing its children. Optional header content stays above both viewports.
 *
 * [availableHeight] supplies the visible height in pixels only when the parent offers UNSPECIFIED
 * height. A bounded parent supplies the budget directly. Request layout when that fallback budget
 * changes. Width must be bounded by the dialog host.
 * The caller owns surface styling, window sizing, child semantics, callbacks and disposal; this
 * layout neither creates a window nor requires an Activity. Pass unparented body/footer content,
 * not existing scroll viewports. Insets and footer spacing remain the caller's shared recipe.
 */
class NativeBoundedDialogContentLayout(
    context: Context,
    body: View,
    footer: View,
    private val contentInsetDp: Int,
    private val availableHeight: () -> Int,
    private val header: View? = null,
) : ViewGroup(context) {
    private val density = resources.displayMetrics.density
    private var topInset = 0
    private val scroll = ScrollView(context).apply {
        isFillViewport = false
        addView(body)
    }
    private val footerScroll = ScrollView(context).apply { addView(footer) }

    init {
        require(contentInsetDp >= 0)
        header?.let { addView(it) }
        addView(scroll)
        addView(footerScroll)
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val inset = minOf(dp(contentInsetDp), width / 8)
        val innerWidth = (width - inset * 2).coerceAtLeast(1)
        val limit = if (MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED)
            availableHeight().coerceAtLeast(1) else MeasureSpec.getSize(h)
        topInset = minOf(inset, limit / 8)
        header?.measure(
            MeasureSpec.makeMeasureSpec(innerWidth, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(limit / 2, MeasureSpec.AT_MOST),
        )
        val headerHeight = header?.measuredHeight ?: 0
        val innerHeight = limit - topInset - headerHeight
        val minimumBody = minOf(dp(contentInsetDp), innerHeight / 3)
        val footerLimit = innerHeight - minimumBody
        footerScroll.measure(exact(innerWidth), MeasureSpec.makeMeasureSpec(footerLimit, MeasureSpec.AT_MOST))
        val remaining = (innerHeight - footerScroll.measuredHeight).coerceAtLeast(0)
        scroll.measure(exact(innerWidth), MeasureSpec.makeMeasureSpec(remaining, MeasureSpec.AT_MOST))
        setMeasuredDimension(width, minOf(limit, scroll.measuredHeight + footerScroll.measuredHeight + topInset + headerHeight))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val inset = minOf(dp(contentInsetDp), width / 8)
        val bodyTop = topInset + (header?.measuredHeight ?: 0)
        header?.let {
            val left = (width - it.measuredWidth) / 2
            it.layout(left, topInset, left + it.measuredWidth, bodyTop)
        }
        scroll.layout(inset, bodyTop, width - inset, bodyTop + scroll.measuredHeight)
        footerScroll.layout(inset, scroll.bottom, width - inset, scroll.bottom + footerScroll.measuredHeight)
    }

    private fun dp(value: Int) = (value * density).roundToInt()
    private fun exact(value: Int) = MeasureSpec.makeMeasureSpec(value, MeasureSpec.EXACTLY)
}
