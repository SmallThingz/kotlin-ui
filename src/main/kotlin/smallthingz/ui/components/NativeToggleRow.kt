package smallthingz.ui.components

import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import smallthingz.ui.text.view.NativeMergedRowRole
import smallthingz.ui.text.view.ParagraphView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Compact toggle with an optional description inside the same actionable and accessible row. */
open class NativeToggleRow internal constructor(
    context: Context,
    private val title: View,
    private val description: View?,
    private val switch: NativeMaterialSwitchView,
    inputRoot: (() -> View?)?,
    private val geometry: Geometry,
    private var onChanged: ((Boolean) -> Unit)?,
) : NativeControlRow(context, NativeMergedRowRole.SWITCH, inputRoot ?: { null }), AutoCloseable {
    constructor(context: Context, title: View, description: View?, switch: NativeMaterialSwitchView,
        inputRoot: (() -> View?)? = null, onChanged: ((Boolean) -> Unit)?,
    ) : this(context, title, description, switch, inputRoot, Geometry.DEFAULT, onChanged)

    internal enum class Geometry(val minHeightDp: Int, val horizontalInsetDp: Int, val verticalInsetDp: Int) {
        DEFAULT(64, 14, 8),
        COMPACT(48, 12, 4),
    }

    private val text = listOfNotNull(title, description)
    private var density = resources.displayMetrics.density
    private var horizontalInset = 0
    private var textHeight = 0
    private var textWidth = 0
    private var gap = 0
    private var leadingIcon: View? = null
    private var leadingWidth = 0
    private var closed = false

    init {
        for (child in text) {
            ViewCompat.setScreenReaderFocusable(child, false)
            addView(child)
        }
        addView(switch)
        addRowAccessibilityRole()
        if (inputRoot == null) {
            mergedRowAccessibility.enableStandaloneAccessibility({ !closed }) {
                contentDescription ?: text.mapNotNull { child ->
                    if (child.visibility != View.VISIBLE) return@mapNotNull null
                    (child.contentDescription ?: when (child) {
                        is TextView -> child.text
                        is ParagraphView -> child.getText()
                        else -> null
                    })?.takeIf { it.isNotBlank() }
                }.joinToString("\n")
            }
        }
    }

    override fun accessibilityChildren(): List<View> = text.filter { it.visibility == VISIBLE }

    internal fun setLeadingIcon(icon: View?) {
        if (leadingIcon === icon) return
        leadingIcon?.let(::removeView)
        leadingIcon = icon
        icon?.let {
            it.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            it.isFocusable = false
            addView(it)
        }
    }
    override val hasRowAction get() = onChanged != null
    override fun activateRow() { onChanged?.invoke(rowChecked != true) }

    internal fun bindSurfacePreset(preset: NativeSurfacePreset?) = switch.bindSurfacePreset(preset)

    /** Keep surface policy and state together when rebinding a themed row. */
    fun bindToggle(checked: Boolean, enabled: Boolean, scheme: NativeColorScheme,
        density: Float = resources.displayMetrics.density) {
        bindSurfacePreset(scheme.surfacePreset)
        bindToggle(checked, enabled, density, scheme.ink, scheme.switchPalette)
    }

    fun bindToggle(checked: Boolean, enabled: Boolean, density: Float, ink: Int,
        colors: NativeMaterialSwitchPalette) {
        if (closed) return
        if (this.density != density) {
            this.density = density
            requestLayout()
        }
        bindRow(enabled, ink, checked)
        switch.setPalette(colors)
        switch.setState(checked, enabled)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (geometry == Geometry.COMPACT) {
            density = resources.displayMetrics.density
            requestLayout()
        }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val compact = geometry == Geometry.COMPACT
        val width = MeasureSpec.getSize(widthSpec)
        val bounded = !compact || MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED
        horizontalInset = dp(geometry.horizontalInsetDp)
        if (compact && bounded) horizontalInset = min(horizontalInset, width / 2)
        val inner = (width - 2 * horizontalInset).coerceAtLeast(0)
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        switch.measure(if (bounded) MeasureSpec.makeMeasureSpec(inner, MeasureSpec.AT_MOST) else unspecified,
            unspecified)
        val remaining = if (bounded) (inner - switch.measuredWidth).coerceAtLeast(0) else Int.MAX_VALUE
        // When the action is narrow, let the label wrap before the gap consumes its entire width.
        gap = min(dp(12), if (compact) remaining / 2 else remaining)
        val available = remaining - gap
        leadingWidth = leadingIcon?.let {
            val size = min(dp(24), available)
            val spec = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
            it.measure(spec, spec)
            min(available, size + dp(12))
        } ?: 0
        val textLimit = available - leadingWidth
        val textSpec = when {
            !bounded -> unspecified
            compact -> MeasureSpec.makeMeasureSpec(textLimit, MeasureSpec.AT_MOST)
            else -> MeasureSpec.makeMeasureSpec(textLimit, MeasureSpec.EXACTLY)
        }
        title.measure(textSpec, unspecified)
        val visibleDescription = description?.takeIf { it.visibility != GONE }
        visibleDescription?.measure(textSpec, unspecified)
        textHeight = title.measuredHeight + (visibleDescription?.let { dp(2) + it.measuredHeight } ?: 0)
        textWidth = if (compact) max(title.measuredWidth, visibleDescription?.measuredWidth ?: 0) else textLimit
        val desiredWidth = if (compact) 2 * horizontalInset + leadingWidth + textWidth + gap + switch.measuredWidth
            else width
        val height = max(dp(geometry.minHeightDp), 2 * dp(geometry.verticalInsetDp) + max(textHeight, switch.measuredHeight))
        setMeasuredDimension(resolveSize(desiredWidth, widthSpec), resolveSize(height, heightSpec))
        measureRowRole(measuredWidth, measuredHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        fun place(child: View, start: Int, y: Int) {
            val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - start - child.measuredWidth else start
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
        val inset = dp(geometry.verticalInsetDp)
        fun center(childHeight: Int) = inset + ((height - 2 * inset - childHeight) / 2f).roundToInt()
        val textY = center(textHeight)
        leadingIcon?.let { place(it, horizontalInset, center(it.measuredHeight)) }
        val textStart = horizontalInset + leadingWidth
        place(title, textStart, textY)
        description?.takeIf { it.visibility != GONE }?.let { place(it, textStart, textY + title.measuredHeight + dp(2)) }
        place(switch, textStart + textWidth + gap, center(switch.measuredHeight))
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
