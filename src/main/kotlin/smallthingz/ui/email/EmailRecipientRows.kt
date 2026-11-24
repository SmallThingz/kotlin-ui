package smallthingz.ui.email

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt
import smallthingz.ui.common.ControlTheme
import smallthingz.ui.controls.ControlStyle

/** Visual treatment of a native autocomplete or alternate-address row. Its host owns input. */
open class EmailRecipientRow(
    context: Context,
    initialTheme: ControlTheme,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private var ready = false
    private var theme = initialTheme
    private val selection = theme.appearance.groupSurfaces.settingsChoices.create(context)
    private var active: Boolean? = null

    init {
        background = null
        setWillNotDraw(false)
        ready = true
        updateSelection()
    }

    fun bindTheme(theme: ControlTheme) {
        this.theme = theme
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
    val shape = controls.shape(
        recipe.surfaceColor(theme.colors), recipe.cornerRadiusDp.roundToInt(), theme.colors.border,
    )
    return LayerDrawable(arrayOf(shape)).apply { setPadding(padding, padding, padding, padding) }
}

/** Keeps matched address text readable on both ordinary and selected row surfaces. */
fun emailRecipientMatchColor(theme: ControlTheme): Int {
    val colors = theme.colors
    val background = theme.appearance.modals.anchoredPanel.surfaceColor(colors)
    return colors.primary.takeIf {
        ColorUtils.calculateContrast(it, background) >= 4.5 &&
            ColorUtils.calculateContrast(it, colors.surfaceContainerHighest) >= 4.5
    } ?: colors.onSurface
}
