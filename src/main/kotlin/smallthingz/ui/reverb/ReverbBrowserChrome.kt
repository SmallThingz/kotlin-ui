package smallthingz.ui.reverb

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.InputFilter
import android.text.Spanned
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView.BufferType
import androidx.annotation.DrawableRes
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import smallthingz.ui.components.NativeDeterminateLinearProgress
import smallthingz.ui.components.NativeForeground
import smallthingz.ui.components.NativeTextField
import smallthingz.ui.components.NativeTextFieldStyle
import smallthingz.ui.components.NativeToolbarActionTone
import smallthingz.ui.components.NativeToolbarBackground
import smallthingz.ui.components.acceptsNativeInput
import smallthingz.ui.controls.ControlPalette
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
    inputAllowed: () -> Boolean = { true },
) : ReverbToolbarActionView(context, theme.density.density, theme.palette, { null }, initial.label,
    sharedSurface = true, rolePresent = true), AutoCloseable {
    private var action = initial
    private var bindingRevision = 0L
    private var callback: ((String) -> Unit)? = onAction
    private var admission: (() -> Boolean)? = inputAllowed
    private var closed = false
    private var bound = false
    init {
        // Browser controls share their parent's material, while each keeps a full input target.
        bindStyle(theme.appearance.toolbars.action.copy(sizeDp = 48f, glyphDp = 22f))
        bindContent(initial)
    }
    fun bindContent(next: ReverbBrowserChromeAction) {
        if (closed) return
        if (bound && action == next) return
        bound = true
        bindingRevision++
        if (action.id != next.id || !next.enabled) cancelInput()
        action = next
        setGlyph(requireNotNull(next.icon.drawable(context, theme, sampledIconTint)), sampledIconTint)
        setDescription(next.label)
        accessibilityAction.tooltipText = next.label
        accessibilityAction.filterTouchesWhenObscured = true
        bindToolbarAction(theme.density.density, theme.palette, next.enabled, NativeToolbarActionTone.MUTED) {
            val current = action
            val revision = bindingRevision
            val deliver = callback
            fun currentOwner() = !closed && isEnabled && current.enabled && action === current && bindingRevision == revision
            if (currentOwner() && accessibilityAction.browserPanelAdmitsInput() && currentOwner() &&
                admission?.invoke() == true && accessibilityAction.browserPanelAdmitsInput() && currentOwner())
                deliver?.invoke(current.id)
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
        admission = null
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
    private fun admitsChromeAction() = !panelClosed && chromeInputAllowed?.invoke() == true
    private fun action(id: String) {
        // All reentrant admission runs inside the button's captured owner/revision check.
        if (!panelClosed) onChromeAction?.invoke(id)
    }
    val siteAction = ReverbBrowserChromeActionView(context, chromeTheme, site, ::action, ::admitsChromeAction)
    val tabsAction = ReverbBrowserChromeActionView(context, chromeTheme, tabs, ::action, ::admitsChromeAction)
    val menuAction = ReverbBrowserChromeActionView(context, chromeTheme, menu, ::action, ::admitsChromeAction)
    private val field = chromeTheme.appearance.compactField.style
    // The address is an editor in the continuous browser toolbar, not another rounded button.
    val addressEditor: NativeTextField = ReverbBrowserChromeAddressEditor(context, chromeTheme.palette.pageControls,
        field.copy(outlined = false, filled = false, minHeightDp = 48f,
            startPaddingDp = 4f, endPaddingDp = 8f, verticalPaddingDp = 8f)).apply {
        hint = addressHint
    }
    val pageHost = FrameLayout(context)
    val chromeToolbar: View = ReverbBrowserPanelSwipe(context, { dp(56).toFloat() },
        canSwipe = { !panelClosed && browserPanelAdmitsInput() && canSwipeToolbar?.invoke() == true },
        onSwipe = { if (!panelClosed && canSwipeToolbar?.invoke() == true) onToolbarSwipe?.invoke(it) }).apply {
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val recipe = chromeTheme.appearance.toolbars
            NativeToolbarBackground(this, recipe, chromeTheme.palette) { ReverbTextSurface.invalidate(this) }
            minimumHeight = recipe.compactHeightPx(chromeTheme.density.density)
            val inset = (recipe.centeredPaddingDp * chromeTheme.density.density).roundToInt()
            setPaddingRelative(inset, 0, inset, 0)
            addView(siteAction)
            addView(addressEditor, LayoutParams(0, -2, 1f))
            addView(tabsAction)
            addView(menuAction)
        }, FrameLayout.LayoutParams(-1, -2))
    }
    private val progress = NativeDeterminateLinearProgress(context, chromeTheme.palette.primary,
        chromeTheme.palette.pageControls.outline, chromeTheme.density.density).apply {
        bindSurfacePreset(chromeTheme.palette.surfacePreset)
        visibility = INVISIBLE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val status = chromeTheme.paragraph(context, "", NativeTypographyRole.BODY_MEDIUM).apply {
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = NativeForeground.foregroundBackground(chromeTheme.palette.pageControls)
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        visibility = GONE
    }
    init {
        background = chromeTheme.appearance.backdrops.pageBackground(chromeTheme.palette)
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
        progress.bindSurfacePreset(chromeTheme.palette.surfacePreset)
        progress.visibility = if (!chromeVisible) GONE else if (loading) VISIBLE else INVISIBLE
        progress.bind(value, chromeTheme.palette.primary, chromeTheme.palette.pageControls.outline, chromeTheme.density.density,
            operationId = operation, terminal = !loading)
    }
    fun bindBrowserStatus(message: String, visible: Boolean) {
        if (panelClosed) return
        status.setText(message)
        status.visibility = if (visible && message.isNotEmpty()) VISIBLE else GONE
    }
    /** Insets remaining after the outer frame and any panel-owned bottom action dock. */
    fun applyContentSafeInsets(insets: Insets) {
        if (panelClosed) return
        if (paddingLeft != insets.left || paddingTop != insets.top ||
            paddingRight != insets.right || paddingBottom != insets.bottom) {
            setPadding(insets.left, insets.top, insets.right, insets.bottom)
        }
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

/** Native single-line editing keeps its input budget; binding a committed URL or draft is lossless. */
private class ReverbBrowserChromeAddressEditor(context: Context, palette: ControlPalette, style: NativeTextFieldStyle) :
    NativeTextField(context, palette, style) {
    private var settingText = 0
    private var accessibilityInput = 0
    private val focusPaint = Paint().apply { color = palette.accent }
    init {
        // TextView otherwise adds its own 5,000-character filter, including on setText(). A
        // LengthFilter subclass also prevents later setSingleLine/inputType calls replacing it.
        val userLimit = object : InputFilter.LengthFilter(5000) {
            override fun filter(source: CharSequence, start: Int, end: Int, dest: Spanned,
                dstart: Int, dend: Int): CharSequence? =
                if (settingText > 0 && accessibilityInput == 0) null else super.filter(source, start, end, dest, dstart, dend)
        }
        filters = filters.filterNot { it is InputFilter.LengthFilter }.toTypedArray() + userLimit
        setSingleLine(true)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isFocused || !isEnabled) return
        // Keep a visible editing cue without taking the host's focus listener or painting a box.
        // TextView scrolls its canvas for long URLs; this rule stays fixed to the editor viewport.
        val density = resources.displayMetrics.density
        val bottom = scrollY + height - 2f * density
        canvas.drawRect(scrollX + paddingLeft.toFloat(), bottom - 2f * density,
            scrollX + width - paddingRight.toFloat(), bottom, focusPaint)
    }
    override fun setText(text: CharSequence?, type: BufferType?) {
        // TextView's constructor can call this override before this subclass is initialized.
        settingText++
        try { super.setText(text, type) } finally { settingText-- }
    }
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action != AccessibilityNodeInfo.ACTION_SET_TEXT) return super.performAccessibilityAction(action, arguments)
        // Native ACTION_SET_TEXT uses setText too, but is user input rather than an owner bind.
        accessibilityInput++
        return try { super.performAccessibilityAction(action, arguments) } finally { accessibilityInput-- }
    }
}
