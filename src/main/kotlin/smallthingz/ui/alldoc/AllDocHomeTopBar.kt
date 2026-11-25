package smallthingz.ui.alldoc

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import smallthingz.ui.common.mergedButtonGroup
import smallthingz.ui.controls.ControlPalette
import kotlin.math.roundToInt

/**
 * Retained Licences / full-color identity / Open + Settings composition.
 * Home owns collapse timing and focus restoration through these stable action handles.
 * Recreate with the window configuration; bind colors without replacing its controls.
 */
class AllDocHomeTopBar(
    context: Context,
    initialPalette: ControlPalette,
    licensesLabel: String,
    aboutLabel: String,
    openLabel: String,
    settingsLabel: String,
    licensesIcon: Drawable?,
    identityIcon: Drawable?,
    openIcon: Drawable?,
    settingsIcon: Drawable?,
    onLicenses: () -> Unit,
    onAbout: () -> Unit,
    onOpen: () -> Unit,
    onSettings: () -> Unit,
) : FrameLayout(context), AutoCloseable {
    private val actions = AllDocHomeActions(context)
    private var palette = initialPalette
    val licenses = actions.header(licensesLabel, licenses = true, action = onLicenses).apply {
        setLeadingIcon(licensesIcon?.mutate())
    }
    val identity = actions.header(aboutLabel, identity = true, action = onAbout).apply {
        setLeadingView(ImageView(context).apply {
            setImageDrawable(identityIcon)
            scaleType = ImageView.ScaleType.FIT_CENTER
        })
    }
    val open = actions.header(openLabel, action = onOpen).apply { setLeadingIcon(openIcon?.mutate()) }
    val settings = actions.header(settingsLabel, action = onSettings).apply { setLeadingIcon(settingsIcon?.mutate()) }
    private val trailing = mergedButtonGroup(context, 15f, listOf(open, settings))

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(licenses, LayoutParams(dp(48), dp(48), Gravity.START or Gravity.CENTER_VERTICAL))
        addView(identity, LayoutParams(dp(56), dp(48), Gravity.CENTER))
        addView(trailing, LayoutParams(2 * dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL))
        bindPalette(initialPalette)
    }

    fun bindPalette(palette: ControlPalette) {
        this.palette = palette
        trailing.setPaletteImmediately(palette.raised, palette.outline)
        for (button in listOf(licenses, identity, open, settings))
            actions.bindHeader(button, palette, button.isEnabled)
    }

    fun setActionsEnabled(enabled: Boolean) {
        for (button in listOf(licenses, identity, open, settings))
            if (button.isEnabled != enabled) actions.bindHeader(button, palette, enabled)
    }

    /** The caller applies this only at its existing safe layout boundary. */
    fun requiresStackedLayout(availableContentWidth: Int): Boolean = availableContentWidth < dp(248)

    fun bindLayout(compact: Boolean, stacked: Boolean) {
        val target = dp(48)
        val innerHeight = maxOf(headerMinimumHeight(compact, stacked) - 2 * headerVerticalPadding(compact, stacked),
            if (stacked) target * 2 else target)
        if (minimumHeight != innerHeight) minimumHeight = innerHeight
        val side = if (stacked) Gravity.BOTTOM else Gravity.CENTER_VERTICAL
        place(licenses, Gravity.START or side, target, target)
        place(identity, Gravity.CENTER_HORIZONTAL or if (stacked) Gravity.TOP else Gravity.CENTER_VERTICAL,
            dp(56), if (compact || stacked) target else dp(56))
        place(trailing, Gravity.END or side, 2 * target, target)
    }

    fun headerMinimumHeight(compact: Boolean, stacked: Boolean): Int = dp(if (stacked) 104 else if (compact) 48 else 64)
    fun headerVerticalPadding(compact: Boolean, stacked: Boolean): Int = dp(if (compact && !stacked) 0 else 4)

    private fun place(view: View, gravity: Int, width: Int, height: Int) {
        val params = view.layoutParams as LayoutParams
        if (params.gravity != gravity || params.height != height || params.width != width) {
            params.gravity = gravity
            params.height = height
            params.width = width
            view.layoutParams = params
        }
    }

    override fun close() {
        listOf(licenses, identity, open, settings).forEach { it.close() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
