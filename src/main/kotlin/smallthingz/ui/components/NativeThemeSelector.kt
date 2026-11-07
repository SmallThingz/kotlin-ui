package smallthingz.ui.components

import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import smallthingz.ui.R
import smallthingz.ui.controls.ControlPalette
import kotlin.math.roundToInt

/** Presentation only: applications own persisted codes and their system-theme resolution. */
enum class NativeThemeMode(@param:StringRes @field:StringRes val labelRes: Int) {
    SYSTEM(R.string.native_theme_system), LIGHT(R.string.native_theme_light), DARK(R.string.native_theme_dark),
}

/** Optional retained rendering adapter; localized copy and all selector geometry remain library-owned. */
interface NativeThemePresentation : NativeSegmentedPresentation {
    val title: View
    fun bindTitle(titleText: String, palette: ControlPalette, titleColor: Int)
}

/** A complete localized theme row. A selection request never changes the owner's bound selection. */
class NativeThemeSelector(
    context: Context,
    palette: ControlPalette,
    selected: NativeThemeMode,
    titleColor: Int = palette.content,
    presentation: NativeThemePresentation? = null,
    density: Float = context.resources.displayMetrics.density,
    inputAllowed: () -> Boolean = { true },
    onSelectionRequest: (NativeThemeMode) -> Boolean,
) : ViewGroup(context), NativeInputGate, AutoCloseable {
    private var closed = false
    private var onSelectionRequest: ((NativeThemeMode) -> Boolean)? = onSelectionRequest
    private val presentation = presentation ?: DefaultThemePresentation(context, palette)
    private val capsule = NativeSegmentedControl(context, palette, this.presentation, density, inputAllowed)
    private var palette = palette
    private var titleColor = titleColor
    private var selected = selected
    private var optionDensity = Float.NaN
    private var optionLocales = ""
    private var options: List<NativeChoice> = emptyList()
    private var rowHeight = 0
    private var gap = 0
    private var density = density

    override val acceptsNativeInput: Boolean
        get() = !closed && isEnabled && capsule.acceptsNativeInput

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(this.presentation.title)
        addView(capsule)
        capsule.onSelectionRequest = { id ->
            val mode = NativeThemeMode.entries.singleOrNull { it.name == id }
            mode != null && acceptsNativeInput && this.onSelectionRequest?.invoke(mode) == true
        }
        bind(selected, palette, titleColor, density)
    }

    fun bind(selected: NativeThemeMode, palette: ControlPalette, titleColor: Int = palette.content,
             density: Float = resources.displayMetrics.density) {
        if (closed) return
        require(density.isFinite() && density > 0f)
        this.density = density
        this.selected = selected
        this.palette = palette
        this.titleColor = titleColor
        val locales = resources.configuration.locales.toLanguageTags()
        if (optionDensity != density || optionLocales != locales) {
            optionDensity = density
            optionLocales = locales
            options = NativeThemeMode.entries.map { mode -> NativeChoice(mode.name, context.getString(mode.labelRes),
                NativeThemeIconDrawable(mode, density, palette.muted)) }
        }
        presentation.bindTitle(context.getString(R.string.native_theme_title), palette, titleColor)
        capsule.bind(options, selected.name, palette, density)
        requestLayout()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        bind(selected, palette, titleColor)
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val available = (width - 2 * dp(16)).coerceAtLeast(0)
        presentation.title.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        gap = minOf(dp(16), (available - presentation.title.measuredWidth).coerceAtLeast(0))
        capsule.measure(exact((available - presentation.title.measuredWidth - gap).coerceAtLeast(0)),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        rowHeight = maxOf(presentation.title.measuredHeight, capsule.measuredHeight)
        setMeasuredDimension(resolveSize(width, w), resolveSize(2 * dp(12) + rowHeight + dp(12), h))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        place(presentation.title, dp(16))
        place(capsule, dp(16) + presentation.title.measuredWidth + gap)
    }

    private fun place(child: View, start: Int) {
        val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - start - child.measuredWidth else start
        val y = dp(12) + ((rowHeight - child.measuredHeight) / 2f).roundToInt()
        child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
    }

    override fun close() {
        if (closed) return
        closed = true
        onSelectionRequest = null
        capsule.close()
    }

    private fun dp(value: Int) = (value * density).roundToInt()
    private fun exact(value: Int) = MeasureSpec.makeMeasureSpec(value, MeasureSpec.EXACTLY)
}

private class DefaultThemePresentation(context: Context, palette: ControlPalette) : NativeThemePresentation,
    NativeSegmentedPresentation by DefaultSegmentedPresentation(context, palette) {
    override val title = NativeText(context, palette, NativeTextStyle(sizeSp = 19f, lineHeightSp = 26f, weight = 500))
    override fun bindTitle(titleText: String, palette: ControlPalette, titleColor: Int) {
        title.text = titleText
        title.applyPalette(palette)
        title.animateTextColor(titleColor)
    }
}
