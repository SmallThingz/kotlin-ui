package smallthingz.ui.reverb

import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import smallthingz.ui.R
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeToolbarActionTone
import smallthingz.ui.components.NativeToolbarBackground
import smallthingz.ui.components.NativeToolbarContentAction
import smallthingz.ui.components.NativeTopBarInsets
import smallthingz.ui.components.NativeTopBarLayout
import smallthingz.ui.components.NativeTopBarLayout.Slot

/** Compact Reverb slot geometry and chrome for hosts with their own semantic action owners. */
open class ReverbTopBarLayout(
    context: Context,
    density: Float,
    palette: NativeColorScheme,
) : NativeTopBarLayout(context, ReverbDesign.appearance.toolbars) {
    private val visualStyle = ReverbDesign.appearance.toolbars
    private val chrome = NativeToolbarBackground(this, visualStyle, visualStyle.backgroundColor(palette))

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        bindToolbar(density, palette)
    }

    fun addToolbarSlot(view: View, slot: Slot, index: Int = -1) {
        addView(view, index, ActionLayoutParams(slot, minimumVisualOffset = slot == Slot.CENTER))
    }

    fun bindToolbar(density: Float, palette: NativeColorScheme, statusInsetPx: Int = 0) {
        bindStyle(density, statusInsetPx, visualStyle)
        chrome.bind(visualStyle, visualStyle.backgroundColor(palette), palette.surfacePreset?.backgroundGrain ?: 1f)
    }
}

/** The consumer supplies a glyph, its accessible name, state and behavior. */
data class ReverbTopBarActionContent(
    val icon: ReverbIcon,
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val alert: Boolean = false,
)

sealed interface ReverbTopBarCenterContent {
    data class Brand(val onClick: () -> Unit, val label: String? = null) : ReverbTopBarCenterContent
    data class Product(val identity: ReverbProductIdentity, val label: String, val onClick: () -> Unit) : ReverbTopBarCenterContent
    data class Custom(val content: View, val label: String, val onClick: () -> Unit) : ReverbTopBarCenterContent
}

/** Start and end actions flank a centered Reverb mark. Supply center content to replace the mark. */
class ReverbTopBar(
    context: Context,
    initialTheme: ReverbTheme,
    private val inputRoot: View? = null,
) : FrameLayout(context) {
    private val slots = ReverbTopBarLayout(context, initialTheme.density.density, initialTheme.palette)
    private val mark = ReverbBrandMarkView(context)
    private val productMark = ReverbIdentityMarkView(context)
    private val defaultLabel = context.getString(R.string.reverb_brand_name)
    private val start = ReverbHeaderActionView(context, initialTheme, inputRoot)
    private val end = ReverbHeaderActionView(context, initialTheme, inputRoot)
    private var centerView: View = mark
    private var centerAction: ReverbToolbarContentActionView? = null
    private var centerContent: View? = null
    internal val centerAnchor: View? get() = centerAction

    internal fun requestEndActionFocus(): Boolean {
        val action = end.accessibilityAction
        return action.isAttachedToWindow && action.isShown && action.isEnabled && action.requestFocus()
    }

    init {
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(slots, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        slots.addToolbarSlot(mark, Slot.CENTER)
        slots.addToolbarSlot(start, Slot.START)
        slots.addToolbarSlot(end, Slot.END)
        start.visibility = GONE
        end.visibility = GONE
    }

    fun bind(theme: ReverbTheme, start: ReverbTopBarActionContent? = null,
             center: ReverbTopBarCenterContent? = null, end: ReverbTopBarActionContent? = null,
             insets: NativeTopBarInsets = NativeTopBarInsets(0, 0, 0)) {
        slots.bindToolbar(theme.density.density, theme.palette, insets.top)
        slots.setActionInsets(insets.left, insets.right)
        val product = center is ReverbTopBarCenterContent.Product
        this.start.bind(theme, start, product)
        this.end.bind(theme, end, product)
        mark.refreshResourcePalette()
        if (center == null) {
            if (centerView !== mark) {
                slots.removeView(centerView)
                centerAction?.removeAllViews()
                centerAction = null
                centerContent = null
                centerView = mark
                slots.addToolbarSlot(mark, Slot.CENTER, index = 0)
            }
            return
        }
        val (content, label, callback) = when (center) {
            is ReverbTopBarCenterContent.Brand -> Triple(mark, center.label ?: defaultLabel, center.onClick)
            is ReverbTopBarCenterContent.Product -> {
                productMark.bind(center.identity, theme.palette)
                Triple(productMark, center.label, center.onClick)
            }
            is ReverbTopBarCenterContent.Custom -> Triple(center.content, center.label, center.onClick)
        }
        if (centerContent !== content || centerAction == null) {
            slots.removeView(centerView)
            centerAction?.removeAllViews()
            centerAction = ReverbToolbarContentActionView(context, content, label, inputRoot,
                theme.density.density, theme.palette, standaloneAccessibility = true).also {
                centerView = it
                centerContent = content
                slots.addToolbarSlot(it, Slot.CENTER, index = 0)
            }
        }
        centerAction?.apply {
            setDescription(label)
            filterTouchesWhenObscured = product
            tooltipText = label.takeIf { product }
            bindToolbarContent(theme.density.density, theme.palette, callback)
        }
    }
}

internal class ReverbHeaderActionView(context: Context, theme: ReverbTheme, inputRoot: View?) : ReverbToolbarActionView(
    context, theme.density.density, theme.palette, { inputRoot }, "", rolePresent = true,
) {
    private var icon: ReverbIcon? = null
    private var density = theme.density.density

    fun bind(theme: ReverbTheme, content: ReverbTopBarActionContent?, product: Boolean = false) {
        accessibilityAction.filterTouchesWhenObscured = product
        accessibilityAction.tooltipText = content?.label?.takeIf { product }
        visibility = if (content != null) VISIBLE else GONE
        if (content == null) {
            cancelInput()
            return
        }
        val tone = if (content.alert) NativeToolbarActionTone.ALERT else NativeToolbarActionTone.MUTED
        val nextDensity = theme.density.density
        if (icon != content.icon || density != nextDensity) {
            icon = content.icon
            density = nextDensity
            setGlyph(ReverbIconDrawable(content.icon, density, sampledIconTint), sampledIconTint)
        }
        setDescription(content.label)
        bindToolbarAction(density, theme.palette, content.enabled, tone, content.onClick)
    }
}

/** Circular action wrapper for caller-owned semantic content, including the default Reverb mark. */
open class ReverbToolbarContentActionView(
    context: Context,
    content: View,
    label: String,
    inputRoot: View?,
    density: Float,
    palette: NativeColorScheme,
    standaloneAccessibility: Boolean = false,
) : NativeToolbarContentAction(context, content, label, inputRoot, density,
    ReverbDesign.appearance.toolbars.action.minimumTargetDp,
    ReverbDesign.appearance.toolbars.contentColor(palette), standaloneAccessibility) {
    fun bindToolbarContent(density: Float, palette: NativeColorScheme, callback: () -> Unit) {
        val style = ReverbDesign.appearance.toolbars
        bindAction(density, style.action.minimumTargetDp, style.contentColor(palette), callback)
    }
}
