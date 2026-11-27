/* Row weight/intrinsic arithmetic adapted from AndroidX foundation-layout 1.11.2.
 * Copyright 2019, 2022 The Android Open Source Project. Licensed under Apache-2.0.
 */
package smallthingz.ui.components

import android.content.Context
import android.view.View
import android.view.ViewGroup

/** Equal-width choices with one shared intrinsic row height and a single internal divider. */
class NativeDropdownPair(context: Context, private val first: NativeDropdown<*>, private val second: NativeDropdown<*>) : ViewGroup(context) {
    private val fields = arrayOf(first, second)
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; addView(first); addView(second) }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val firstVisible = first.visibility != GONE
        val secondVisible = second.visibility != GONE
        if (!firstVisible && !secondVisible) { setMeasuredDimension(width, 0); return }
        val firstWidth = when {
            !firstVisible -> 0
            secondVisible -> width / 2
            else -> width
        }
        val secondWidth = width - firstWidth
        val targetHeight = maxOf(
            if (firstVisible) first.intrinsicHeight(firstWidth) else 0,
            if (secondVisible) second.intrinsicHeight(secondWidth) else 0,
        )
        val height = resolveSize(targetHeight, h)
        if (firstVisible) first.measure(exact(firstWidth), exact(height))
        if (secondVisible) second.measure(exact(secondWidth), exact(height))
        second.dividerAtStart = firstVisible
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
