package smallthingz.ui.reverb

import android.content.Context
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ScrollView
import smallthingz.ui.components.NativeButtonTouchTarget
import smallthingz.ui.components.NativeForeground
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.components.NativeMaterialButtonView
import kotlin.math.roundToInt

/** Main controls become a bounded browser disclosure without changing their retained content. */
class ReverbBrowserChromeControlsScroll(context: Context, private var theme: ReverbTheme) : ScrollView(context) {
    private var disclosure = false
    private var disclosureBackground: android.graphics.drawable.Drawable? = null
    private fun disclosureBackground() = disclosureBackground ?: NativeForeground.foregroundBackground(
        theme.palette.pageControls).also { disclosureBackground = it }

    /** Keep disclosure, layout parameters, children and scroll position; replace only cached paint. */
    fun bindTheme(theme: ReverbTheme) {
        if (this.theme === theme) return
        cancelBrowserPanelInput()
        this.theme = theme
        disclosureBackground = null
        if (disclosure) background = disclosureBackground()
        ReverbTextSurface.invalidate(this)
    }
    fun showBrowserDisclosure(show: Boolean) {
        if (disclosure == show) return
        disclosure = show
        layoutParams = FrameLayout.LayoutParams(-1, if (show) -2 else -1)
        background = if (show) disclosureBackground() else null
        isClickable = show
    }
    override fun onMeasure(w: Int, h: Int) {
        val height = if (disclosure && MeasureSpec.getMode(h) == MeasureSpec.AT_MOST)
            MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(h) * 2 / 3, MeasureSpec.AT_MOST) else h
        super.onMeasure(w, height)
    }
}

/** Transient tab-close feedback; expiry and whether Undo is still valid are host state. */
class ReverbBrowserChromeUndoNotice(context: Context, theme: ReverbTheme, message: String,
    undoLabel: String, onUndo: () -> Unit,
) : ReverbBrowserPanelLayout(context) {
    private var callback: (() -> Unit)? = onUndo
    private val button = NativeMaterialButtonView(context, ReverbDesign.appearance.buttons.text,
        theme.density, theme.fontResolver, onClick = ::undo).apply {
        bind(undoLabel, true, theme.palette.buttonPalette, onClick = ::undo)
        filterTouchesWhenObscured = true
    }
    private val density = theme.density.density
    init {
        val recipe = ReverbDesign.appearance.groupSurfaces.settingsGroup
        addView(recipe.create(context, ReverbTextSurface::invalidate).apply {
            recipe.bind(this, theme.palette)
            val row = ReverbBrowserPanelSettingRow(context, theme, ReverbBrowserPanelSetting(message)).apply {
                titleView.accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
                setAccessory(NativeButtonTouchTarget(button))
            }
            addRow(row, NativeGroupLayout.LayoutParams(-1, -2))
        }, LayoutParams(-1, -2))
    }
    private fun undo() {
        if (!panelClosed && button.browserPanelAdmitsInput() && !panelClosed) callback?.invoke()
    }
    fun attachToContentHost(host: FrameLayout, bottomObstructionPx: Int) {
        check(!panelClosed)
        host.addView(this, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins(dp(12), 0, dp(12), dp(12) + bottomObstructionPx.coerceAtLeast(0))
        })
    }
    override fun close() {
        if (panelClosed) return
        super.close(); callback = null; button.close()
        (parent as? FrameLayout)?.removeView(this)
    }
    private fun dp(value: Int) = (value * density).roundToInt()
}
