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
package smallthingz.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import smallthingz.ui.animation.view.ViewColorTransition
import smallthingz.ui.text.view.ParagraphView
import kotlin.math.max
import kotlin.math.roundToInt

/** Choice identity remains typed even when two labels translate to the same text. */
data class NativeDropdownContent<T>(
    val label: String,
    val selected: T,
    val options: List<T>,
    val optionLabel: (T) -> String,
)

data class NativeDropdownColors(
    val collapsed: Int,
    val expanded: Int,
    val muted: Int,
    val ink: Int,
    val divider: Int,
)

class NativeDropdownIcon(val view: View, val setTint: (Int) -> Unit)

/** Dropdown trigger and live label presentation. The owner retains selection and popup lifetime. */
class NativeDropdown<T>(
    context: Context,
    private val title: ParagraphView,
    private val selected: ParagraphView,
    private val arrow: NativeDropdownIcon,
    initialColors: NativeDropdownColors,
    inputRoot: () -> View?,
    private val onBinding: (View, NativeDropdownContent<T>) -> Unit,
    private val open: (View, NativeDropdownContent<T>) -> Unit,
    private val foregroundColor: (ViewColorTransition, Int, Int) -> Int = { _, _, ink -> ink },
) : NativeClickableRow(context, inputRoot), AutoCloseable {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val divider = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        strokeWidth = 1f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private var colorMotion:ViewColorTransition?=null
    private var content: NativeDropdownContent<T>? = null
    private var textHeight = 0
    private var textGap = 0
    private var textWidth = 0
    private var iconGap = 0
    private var closed = false
    override val hasRowAction get() = content != null
    override fun activateRow() { content?.let { open(this, it) } }

    var dividerAtStart: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }

    init {
        setWillNotDraw(false)
        ViewCompat.setScreenReaderFocusable(this, true)
        // A source merging clickable keeps separate unmerged Text/Icon nodes. No joined label.
        ViewCompat.setScreenReaderFocusable(title, false)
        ViewCompat.setScreenReaderFocusable(selected, false)
        arrow.view.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        selected.setTextLayout(maxLines = 1)
        fill.color = initialColors.collapsed
        addView(title)
        addView(selected)
        addView(arrow.view)
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
    }

    fun bind(next: NativeDropdownContent<T>, expanded: Boolean, palette: NativeDropdownColors) {
        if (closed) return
        content = next
        val present = next.options.size > 1
        if (visibility != VISIBLE || !present) {
            colorMotion?.close()
            colorMotion = null
        }
        visibility = if (present) VISIBLE else GONE
        title.setText(next.label)
        selected.setText(next.optionLabel(next.selected))
        val colors = intArrayOf(if (expanded) palette.expanded else palette.collapsed,
            palette.muted, palette.ink, palette.divider)
        val motion = colorMotion ?: ViewColorTransition(this, colors, ::publishColors)
            .also { colorMotion = it }
        motion.retarget(*colors)
        publishColors()
        bindAction(present, palette.ink)
        onBinding(this, next)
        invalidate()
    }

    private fun publishColors() {
        val colors = colorMotion ?: return
        fill.color = colors.color(0)
        val muted = foregroundColor(colors, fill.color, colors.color(1))
        title.setTextColor(muted)
        selected.setTextColor(foregroundColor(colors, fill.color, colors.color(2)))
        arrow.setTint(muted)
        divider.color = colors.color(3)
        invalidate()
    }

    fun intrinsicHeight(width: Int): Int {
        val textWidth = availableTextWidth(width)
        val stack = title.intrinsicTextHeight(textWidth) + dp(2) + selected.intrinsicTextHeight(textWidth)
        return max(dp(72), dp(10) * 2 + max(stack, dp(24)))
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec)
        val innerWidth = (width - dp(14) * 2).coerceAtLeast(0)
        val unboundedHeight = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED
        val innerMaximumHeight = if (unboundedHeight) {
            Int.MAX_VALUE
        } else (MeasureSpec.getSize(heightSpec) - dp(10) * 2).coerceAtLeast(0)
        arrow.view.measure(exact(minOf(dp(24), innerWidth)), exact(minOf(dp(24), innerMaximumHeight)))
        iconGap = minOf(dp(10), (innerWidth - arrow.view.measuredWidth).coerceAtLeast(0))
        textWidth = (innerWidth - arrow.view.measuredWidth - iconGap).coerceAtLeast(0)
        fun heightLimit(limit: Int) = if (unboundedHeight) unspecified() else atMost(limit)
        title.measure(atMost(textWidth), heightLimit(innerMaximumHeight))
        val afterTitle = (innerMaximumHeight - title.measuredHeight).coerceAtLeast(0)
        textGap = minOf(dp(2), afterTitle)
        selected.measure(atMost(textWidth), heightLimit((afterTitle - textGap).coerceAtLeast(0)))
        textHeight = title.measuredHeight + textGap + selected.measuredHeight
        val height = max(dp(72), dp(10) * 2 + max(textHeight, arrow.view.measuredHeight))
        setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height, heightSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val rowHeight = (height - dp(10) * 2).coerceAtLeast(0)
        val textY = dp(10) + ((rowHeight - textHeight) / 2f).roundToInt()
        placeStart(title, dp(14), textY)
        placeStart(selected, dp(14), textY + title.measuredHeight + textGap)
        placeStart(arrow.view, dp(14) + textWidth + iconGap,
            dp(10) + ((rowHeight - arrow.view.measuredHeight) / 2f).roundToInt())
        layoutRowIndication()
    }

    private fun placeStart(child: View, start: Int, top: Int) {
        val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - start - child.measuredWidth else start
        child.layout(x, top, x + child.measuredWidth, top + child.measuredHeight)
    }
    private fun availableTextWidth(width: Int): Int =
        (width - dp(14) * 2 - dp(10) - dp(24)).coerceAtLeast(0)

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        super.onDraw(canvas)
    }
    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        if (dividerAtStart) {
            val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - 0.5f else 0.5f
            canvas.drawLine(x, 0f, x, height.toFloat(), divider)
        }
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        // The released clickable Surface supplies no Role.Button and no parent Text value.
        info.className = View::class.java.name
        info.text = null
        info.contentDescription = null
    }
    override fun close() {
        if (closed) return
        closed = true
        retireRow()
        colorMotion?.close()
        colorMotion = null
        content = null
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}

