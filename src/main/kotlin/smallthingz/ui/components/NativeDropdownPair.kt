/* Row weight/intrinsic arithmetic adapted from AndroidX foundation-layout 1.11.2.
 * Copyright 2019, 2022 The Android Open Source Project. Licensed under Apache-2.0.
 */
package smallthingz.ui.components

import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlin.math.roundToInt

/** Equal-width choices with one shared intrinsic row height and a single internal divider. */
class NativeDropdownPair(context: Context, private val first: NativeDropdown<*>, private val second: NativeDropdown<*>) : ViewGroup(context) {
    private val fields = arrayOf(first, second)
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; addView(first); addView(second) }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val count = (if (first.visibility != GONE) 1 else 0) + (if (second.visibility != GONE) 1 else 0)
        if (count == 0) { setMeasuredDimension(width, 0); return }
        val unit = (width.toFloat() / count).roundToInt()
        val height = resolveSize(maxOf(if (first.visibility != GONE) first.intrinsicHeight(unit) else 0,
            if (second.visibility != GONE) second.intrinsicHeight(unit) else 0), h)
        var remaining = width - unit * count
        for (child in fields) {
            if (child.visibility == GONE) continue
            val correction = remaining.coerceIn(-1, 1); remaining -= correction
            child.measure(exact(unit + correction), exact(height))
        }
        second.dividerAtStart = first.visibility != GONE
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var start = 0
        for (child in fields) {
            if (child.visibility == GONE) continue
            val x = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) width - start - child.measuredWidth else start
            child.layout(x, 0, x + child.measuredWidth, height)
            start += child.measuredWidth
        }
    }
}
