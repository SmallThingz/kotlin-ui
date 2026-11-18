package smallthingz.ui.components

import android.content.Context
import android.view.View
import androidx.core.view.ViewCompat
import smallthingz.ui.text.view.NativeMergedRowRole
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Compact toggle with an optional description inside the same actionable and accessible row. */
open class NativeToggleRow(
    context: Context,
    private val title: View,
    private val description: View?,
    private val switch: NativeMaterialSwitchView,
    inputRoot: () -> View?,
    private var onChanged: ((Boolean) -> Unit)?,
) : NativeControlRow(context, NativeMergedRowRole.SWITCH, inputRoot), AutoCloseable {
    private val text = listOfNotNull(title, description)
    private var density = resources.displayMetrics.density
    private var textHeight = 0
    private var textWidth = 0
    private var gap = 0
    private var closed = false

    init {
        for (child in text) {
            ViewCompat.setScreenReaderFocusable(child, false)
            addView(child)
        }
        addView(switch)
        addRowAccessibilityRole()
    }

    override fun accessibilityChildren(): List<View> = text
    override val hasRowAction get() = onChanged != null
    override fun activateRow() { onChanged?.invoke(rowChecked != true) }

    fun bindToggle(checked: Boolean, enabled: Boolean, density: Float, ink: Int,
        colors: NativeMaterialSwitchPalette) {
        if (closed) return
        this.density = density
        bindRow(enabled, ink, checked)
        switch.setPalette(colors)
        switch.setState(checked, enabled)
        requestLayout()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec)
        val inner = (width - 2 * dp(14)).coerceAtLeast(0)
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        switch.measure(MeasureSpec.makeMeasureSpec(inner, MeasureSpec.AT_MOST), unspecified)
        gap = min(dp(12), (inner - switch.measuredWidth).coerceAtLeast(0))
        textWidth = (inner - switch.measuredWidth - gap).coerceAtLeast(0)
        val textSpec = MeasureSpec.makeMeasureSpec(textWidth, MeasureSpec.EXACTLY)
        title.measure(textSpec, unspecified)
        description?.measure(textSpec, unspecified)
        textHeight = title.measuredHeight + if (description != null) dp(2) + description.measuredHeight else 0
        val height = max(dp(64), 2 * dp(8) + max(textHeight, switch.measuredHeight))
        setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height, heightSpec))
        measureRowRole(measuredWidth, measuredHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        fun place(child: View, start: Int, y: Int) {
            val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - start - child.measuredWidth else start
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
        val inset = dp(8)
        fun center(childHeight: Int) = inset + ((height - 2 * inset - childHeight) / 2f).roundToInt()
        val textY = center(textHeight)
        place(title, dp(14), textY)
        description?.let { place(it, dp(14), textY + title.measuredHeight + dp(2)) }
        place(switch, dp(14) + textWidth + gap, center(switch.measuredHeight))
        layoutRowRole()
    }

    override fun close() {
        if (closed) return
        closed = true
        onChanged = null
        retireRow()
    }
    private fun dp(value: Int) = (value * density).roundToInt()
}
