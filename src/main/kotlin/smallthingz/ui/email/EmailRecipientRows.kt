package smallthingz.ui.email

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.LayerDrawable
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt
import smallthingz.ui.common.ControlTheme
import smallthingz.ui.components.NativeForeground
import smallthingz.ui.controls.ControlStyle
import smallthingz.ui.controls.srgbLuminance
import smallthingz.ui.controls.textSurfaceForeground
import smallthingz.ui.controls.textSurfaceRange
import smallthingz.ui.graphics.noiseSurfaceRange
import smallthingz.ui.graphics.noiseSurfaceRangeOver

/** Visual treatment of a native autocomplete or alternate-address row. Its host owns input. */
open class EmailRecipientRow(
    context: Context,
    initialTheme: ControlTheme,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private var ready = false
    private var theme = initialTheme
    private val selection = theme.appearance.groupSurfaces.settingsChoices.create(context).apply {
        setGrainInvalidationOwner(this@EmailRecipientRow)
    }
    private var active: Boolean? = null

    init {
        background = null
        setWillNotDraw(false)
        NativeForeground.bindGroup(selection, theme.colors.pageControls)
        ready = true
        updateSelection()
    }

    fun bindTheme(theme: ControlTheme) {
        this.theme = theme
        NativeForeground.bindGroup(selection, theme.colors.pageControls)
        active = null
        updateSelection()
    }

    fun bindAddressText(label: TextView, secondary: Boolean) {
        label.setTextColor(if (secondary) theme.colors.onSurfaceVariant else theme.colors.onSurface)
    }

    fun bindAddressAction(icon: ImageView) {
        ControlStyle(context, theme.colors.pageControls).applyIcon(icon)
    }

    fun addressActionBackground(): Drawable =
        ControlStyle(context, theme.colors.pageControls).background()

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        if (ready) updateSelection()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        selection.layout(0, 0, width, height)
    }

    override fun onDraw(canvas: Canvas) {
        // Keep the paint-only surface out of the view hierarchy so it cannot intercept row actions.
        selection.draw(canvas)
        super.onDraw(canvas)
    }

    private fun updateSelection() {
        val selected = isSelected || isPressed
        if (active == selected) return
        active = selected
        selection.setPaletteImmediately(
            if (selected) theme.colors.surfaceContainerHighest else Color.TRANSPARENT,
            if (selected) emailRecipientMatchColor(theme) else Color.TRANSPARENT,
        )
        invalidate()
    }
}

/** Returns a separately mutable window background for a platform autocomplete/list popup. */
fun emailRecipientPopupBackground(context: Context, theme: ControlTheme): Drawable {
    val recipe = theme.appearance.modals.anchoredPanel
    val controls = ControlStyle(context, theme.colors.pageControls)
    val padding = controls.dp(recipe.verticalPaddingDp.roundToInt())
    val radius = recipe.cornerRadiusDp.roundToInt()
    val fill = recipe.surfaceColor(theme.colors)
    val layers = if ((theme.colors.surfacePreset?.foregroundGrain ?: 0f) == 0f) {
        arrayOf<Drawable>(controls.shape(fill, radius, theme.colors.border))
    } else {
        val surface = object : DrawableWrapper(NativeForeground.foregroundDrawable(fill, theme.colors.pageControls)) {
            private val clip = Path()
            private val radiusPx = controls.dp(radius).toFloat()

            override fun onBoundsChange(bounds: Rect) {
                super.onBoundsChange(bounds)
                clip.rewind()
                clip.addRoundRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(),
                    bounds.bottom.toFloat(), radiusPx, radiusPx, Path.Direction.CW)
            }

            override fun draw(canvas: Canvas) {
                val checkpoint = canvas.save()
                try {
                    canvas.clipPath(clip)
                    super.draw(canvas)
                } finally { canvas.restoreToCount(checkpoint) }
            }
        }
        arrayOf(surface, controls.shape(Color.TRANSPARENT, radius, theme.colors.border))
    }
    return LayerDrawable(layers).apply { setPadding(padding, padding, padding, padding) }
}

/** Keeps matched address text readable on both ordinary and selected row surfaces. */
fun emailRecipientMatchColor(theme: ControlTheme): Int {
    val colors = theme.colors
    val background = theme.appearance.modals.anchoredPanel.surfaceColor(colors)
    val intensity = colors.surfacePreset?.foregroundGrain ?: 0f
    // Match the popup drawable and the group's coverage-scaled grain, even before texture readiness.
    val popupIntensity = intensity * (background ushr 24) / 255f
    val popup = noiseSurfaceRange(background, (popupIntensity * 255f).roundToInt())
    val selected = noiseSurfaceRangeOver(popup, colors.surfaceContainerHighest,
        ((intensity * 255f).roundToInt() * (colors.surfaceContainerHighest ushr 24) + 127) / 255)
    if (popup == 0L || selected == 0L) return colors.onSurface
    fun readableOn(surface: Long, ink: Int): Boolean {
        val low = srgbLuminance((surface ushr 32).toInt())
        val high = srgbLuminance(surface.toInt())
        val luminance = srgbLuminance(ink)
        val nearest = luminance.coerceIn(low, high)
        return (maxOf(luminance, nearest) + .05) / (minOf(luminance, nearest) + .05) >= 4.5
    }
    fun readable(ink: Int): Boolean = ink ushr 24 == 255 &&
        readableOn(popup, ink) && readableOn(selected, ink)
    if (readable(colors.primary)) return colors.primary
    if (readable(colors.onSurface)) return colors.onSurface
    val popupLow = (popup ushr 32).toInt()
    val selectedLow = (selected ushr 32).toInt()
    val popupHigh = popup.toInt()
    val selectedHigh = selected.toInt()
    val low = if (srgbLuminance(popupLow) < srgbLuminance(selectedLow)) popupLow else selectedLow
    val high = if (srgbLuminance(popupHigh) > srgbLuminance(selectedHigh)) popupHigh else selectedHigh
    return textSurfaceForeground(textSurfaceRange(low, high), Color.BLACK)
}
