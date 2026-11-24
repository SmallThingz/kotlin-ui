package smallthingz.ui.reverb

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.core.view.ViewCompat
import smallthingz.ui.components.NativeDeterminateLinearProgress
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.components.NativeTextField
import smallthingz.ui.components.NativeToolbarActionTone
import smallthingz.ui.components.NativeToolbarBackground
import smallthingz.ui.components.acceptsNativeInput
import smallthingz.ui.text.NativeTypographyRole
import kotlin.math.roundToInt

/** Existing shared glyphs are preferred; a resource is semantic icon content, not a style override. */
sealed interface ReverbBrowserChromeIcon {
    data class Shared(val icon: ReverbIcon) : ReverbBrowserChromeIcon
    data class Resource(@DrawableRes val id: Int) : ReverbBrowserChromeIcon
}

internal fun ReverbBrowserChromeIcon.drawable(context: Context, theme: ReverbTheme, tint: Int = theme.palette.ink): Drawable? = when (this) {
    is ReverbBrowserChromeIcon.Shared -> ReverbIconDrawable(icon, theme.density.density, tint)
    is ReverbBrowserChromeIcon.Resource -> context.getDrawable(id)?.mutate()?.apply { setTint(tint) }
}

data class ReverbBrowserChromeAction(val id: String, val label: String, val icon: ReverbBrowserChromeIcon, val enabled: Boolean = true)

/** Canonical toolbar action with current semantic content and independent input retirement. */
class ReverbBrowserChromeActionView internal constructor(context: Context, private val theme: ReverbTheme,
    initial: ReverbBrowserChromeAction, onAction: (String) -> Unit,
) : ReverbToolbarActionView(context, theme.density.density, theme.palette, { null }, initial.label, rolePresent = true), AutoCloseable {
    private var action = initial
    private var callback: ((String) -> Unit)? = onAction
    private var closed = false
    private var bound = false
    init { bindContent(initial) }
    fun bindContent(next: ReverbBrowserChromeAction) {
        if (closed) return
        if (bound && action == next) return
        bound = true
        if (action.id != next.id || !next.enabled) cancelInput()
        action = next
        setGlyph(requireNotNull(next.icon.drawable(context, theme, sampledIconTint)), sampledIconTint)
        setDescription(next.label)
        accessibilityAction.tooltipText = next.label
        accessibilityAction.filterTouchesWhenObscured = true
        bindToolbarAction(theme.density.density, theme.palette, next.enabled, NativeToolbarActionTone.MUTED) {
            val current = action
            if (!closed && isEnabled && current.enabled && accessibilityAction.browserPanelAdmitsInput() && !closed && action === current)
                callback?.invoke(current.id)
        }
    }
    override fun onCancelPendingInputEvents() {
        super.onCancelPendingInputEvents()
        cancelInput()
    }
    override fun close() {
        if (closed) return
        closed = true
        callback = null
        cancelInput()
        bindToolbarAction(theme.density.density, theme.palette, false, NativeToolbarActionTone.MUTED) {}
    }
}

/** Presentation-only browser chrome. The host owns the editor's navigation and all renderer lifecycle. */
open class ReverbBrowserChrome(context: Context, private val chromeTheme: ReverbTheme,
    addressHint: String, site: ReverbBrowserChromeAction, tabs: ReverbBrowserChromeAction, menu: ReverbBrowserChromeAction,
) : ReverbBrowserPanelLayout(context) {
    var onChromeAction: ((String) -> Unit)? = null
    var chromeInputAllowed: (() -> Boolean)? = null
    var canSwipeToolbar: (() -> Boolean)? = null
    var onToolbarSwipe: ((Int) -> Unit)? = null
    private fun action(id: String) {
        if (!panelClosed && chromeInputAllowed?.invoke() == true && !panelClosed && browserPanelAdmitsInput())
            onChromeAction?.invoke(id)
    }
    val siteAction = ReverbBrowserChromeActionView(context, chromeTheme, site, ::action)
    val tabsAction = ReverbBrowserChromeActionView(context, chromeTheme, tabs, ::action)
    val menuAction = ReverbBrowserChromeActionView(context, chromeTheme, menu, ::action)
    private val field = ReverbDesign.appearance.compactField.style
    // The surrounding compact surface owns the fill and leading action; retain the canonical editor metrics.
    val addressEditor = NativeTextField(context, chromeTheme.palette.pageControls, field.copy(filled = false, startPaddingDp = 0f)).apply {
        hint = addressHint
        setSingleLine(true)
    }
    val pageHost = FrameLayout(context)
    val chromeToolbar: View = ReverbBrowserPanelSwipe(context, { dp(56).toFloat() },
        canSwipe = { !panelClosed && browserPanelAdmitsInput() && canSwipeToolbar?.invoke() == true },
        onSwipe = { if (!panelClosed && canSwipeToolbar?.invoke() == true) onToolbarSwipe?.invoke(it) }).apply {
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val recipe = ReverbDesign.appearance.toolbars
            NativeToolbarBackground(this, recipe, recipe.backgroundColor(chromeTheme.palette)) { ReverbTextSurface.invalidate(this) }
            val inset = (recipe.centeredPaddingDp * chromeTheme.density.density).roundToInt()
            setPaddingRelative(inset, 0, inset, 0)
            addView(NativeGroupLayout(context, field.radiusDp).apply {
                setRowOrientation(HORIZONTAL)
                setPaletteImmediately(chromeTheme.palette.raised, android.graphics.Color.TRANSPARENT)
                addRow(siteAction, NativeGroupLayout.LayoutParams(-2, -2))
                addRow(addressEditor, NativeGroupLayout.LayoutParams(0, -2, 1f))
            }, LayoutParams(0, -2, 1f).apply { marginEnd = dp(2) })
            addView(tabsAction)
            addView(menuAction, LayoutParams(-2, -2).apply { marginStart = dp(2) })
        }, FrameLayout.LayoutParams(-1, -2))
    }
    private val progress = NativeDeterminateLinearProgress(context, chromeTheme.palette.primary,
        chromeTheme.palette.pageControls.outline, chromeTheme.density.density).apply {
        visibility = INVISIBLE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val status = chromeTheme.paragraph(context, "", NativeTypographyRole.BODY_MEDIUM).apply {
        setPadding(dp(16), dp(8), dp(16), dp(8))
        setBackgroundColor(chromeTheme.palette.raised)
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        visibility = GONE
    }
    init {
        background = ReverbDesign.appearance.backdrops.pageBackground(chromeTheme.palette)
        addView(chromeToolbar, LayoutParams(-1, -2))
        addView(progress, LayoutParams(-1, -2))
        addView(status, LayoutParams(-1, -2))
        addView(pageHost, LayoutParams(-1, 0, 1f))
    }
    /** Retained-owner admission while a shared modal owns window focus. The modal must gate its own input. */
    protected fun browserActionOwnerPresent(owner: View): Boolean {
        fun present() = !panelClosed && owner.isEnabled && owner.isAttachedToWindow && owner.isShown &&
            owner.windowVisibility == VISIBLE
        return present() && owner.acceptsNativeInput() && present()
    }
    fun bindSiteAction(content: ReverbBrowserChromeAction, state: String) {
        if (panelClosed) return
        siteAction.bindContent(content)
        ViewCompat.setStateDescription(siteAction.accessibilityAction, state)
    }
    fun bindTabCount(description: String) = ViewCompat.setStateDescription(tabsAction.accessibilityAction, description)
    fun bindLoading(value: Float, operation: Long, loading: Boolean, chromeVisible: Boolean) {
        if (panelClosed) return
        progress.visibility = if (!chromeVisible) GONE else if (loading) VISIBLE else INVISIBLE
        progress.bind(value, chromeTheme.palette.primary, chromeTheme.palette.pageControls.outline, chromeTheme.density.density,
            operationId = operation, terminal = !loading)
    }
    fun bindBrowserStatus(message: String, visible: Boolean) {
        if (panelClosed) return
        status.setText(message)
        status.visibility = if (visible && message.isNotEmpty()) VISIBLE else GONE
    }
    /** Renderer and shared panels are semantic content slots, not caller-configurable geometry. */
    fun attachRenderer(renderer: View) { pageHost.addView(renderer, FrameLayout.LayoutParams(-1, -1)) }
    fun attachStartPage(content: ReverbBrowserChromeStartPage) { pageHost.addView(content, FrameLayout.LayoutParams(-1, -1)) }
    fun attachBrowserPanel(panel: ReverbBrowserPanelLayout) { addView(panel, LayoutParams(-1, 0, 1f)) }
    fun attachToBrowserHost(host: FrameLayout) { host.addView(this, FrameLayout.LayoutParams(-1, -1)) }
    override fun close() {
        if (panelClosed) return
        super.close()
        onChromeAction = null; chromeInputAllowed = null; canSwipeToolbar = null; onToolbarSwipe = null
        siteAction.close(); tabsAction.close(); menuAction.close()
        addressEditor.setOnEditorActionListener(null)
        addressEditor.onFocusChangeListener = null
        addressEditor.setOnScrollChangeListener(null)
    }
    private fun dp(value: Int) = (value * chromeTheme.density.density).roundToInt()
}
