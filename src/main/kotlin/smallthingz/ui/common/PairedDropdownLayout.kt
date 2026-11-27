/*
 * Row weight/intrinsic arithmetic adapted from AndroidX foundation-layout 1.11.2.
 * Copyright 2019, 2022 The Android Open Source Project.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package smallthingz.ui.common

import android.content.Context
import android.view.View
import android.view.ViewGroup
import smallthingz.ui.components.NativeDropdown

private fun exactly(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

/** Source Row.weight + IntrinsicSize.Min. Each child text remains wrap-content inside its weight. */
internal class PairedDropdownLayout(
    context: Context,
    private val first: NativeDropdown<*>,
    private val second: NativeDropdown<*>,
) : ViewGroup(context) {
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        addView(first)
        addView(second)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec)
        val firstVisible = first.visibility != GONE
        val secondVisible = second.visibility != GONE
        if (!firstVisible && !secondVisible) {
            setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(0, heightSpec))
            return
        }
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
        val height = resolveSize(targetHeight, heightSpec)
        if (firstVisible) first.measure(exactly(firstWidth), exactly(height))
        if (secondVisible) second.measure(exactly(secondWidth), exactly(height))
        setMeasuredDimension(resolveSize(width, widthSpec), height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var start = 0
        repeat(2) { index ->
            val child = if (index == 0) first else second
            if (child.visibility == GONE) return@repeat
            val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - start - child.measuredWidth else start
            child.layout(x, 0, x + child.measuredWidth, height)
            start += child.measuredWidth
        }
    }
}
