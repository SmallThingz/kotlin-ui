package smallthingz.ui.reverb

import android.content.Context
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import smallthingz.ui.components.NativeButtonTouchTarget
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.components.NativeScreenSpacing
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.util.closePreservingPrimaryFailure
import kotlin.math.roundToInt

/** Immutable failure presentation; the host owns the failed document identity and any retry. */
class ReverbBrowserChromeFailurePage(
    context: Context,
    private var theme: ReverbTheme,
    message: String,
    private val retryLabel: String,
    inputAllowed: () -> Boolean,
    onRetry: () -> Unit,
) : ReverbBrowserPanelLayout(context) {
    private var admission: (() -> Boolean)? = inputAllowed
    private var callback: (() -> Unit)? = onRetry
    private var bindingRevision = 0L
    private val reason = theme.paragraph(context, message, NativeTypographyRole.BODY_LARGE).apply {
        // The full reason belongs to the document viewport, including at large font scales.
        setTextLayout(softWrap = true, maxLines = Int.MAX_VALUE)
    }
    private val retry = NativeMaterialButtonView(context, theme.appearance.buttons.filled,
        theme.density, theme.fontResolver, onClick = ::retryRequested).apply {
        bind(retryLabel, true, theme.palette.buttonPalette, onClick = ::retryRequested)
        filterTouchesWhenObscured = true
    }
    init {
        background = theme.appearance.backdrops.pageBackground(theme.palette)
        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            setPaddingRelative(dp(NativeScreenSpacing.HORIZONTAL_DP), dp(NativeScreenSpacing.SECTION_VERTICAL_DP),
                dp(NativeScreenSpacing.HORIZONTAL_DP), dp(NativeScreenSpacing.BOTTOM_DP))
            addView(reason, LayoutParams(-1, -2))
            addView(NativeButtonTouchTarget(retry, NativeButtonTouchTarget.HorizontalAlignment.START),
                LayoutParams(-2, -2).apply { topMargin = dp(NativeScreenSpacing.CONTROL_GAP_DP) })
        }
        addView(ScrollView(context).apply {
            addView(column, FrameLayout.LayoutParams(-1, -2))
        }, LayoutParams(-1, -1))
    }

    /** Same content and scroll owner; retire old input before publishing the fresh theme. */
    fun bindTheme(theme: ReverbTheme) {
        if (panelClosed || this.theme === theme) return
        bindingRevision++
        cancelBrowserPanelInput()
        this.theme = theme
        background = theme.appearance.backdrops.pageBackground(theme.palette)
        theme.bindParagraph(reason, NativeTypographyRole.BODY_LARGE)
        retry.bind(retryLabel, true, theme.palette.buttonPalette, onClick = ::retryRequested)
        ReverbTextSurface.invalidate(this)
    }

    private fun retryRequested() {
        val deliver = callback ?: return
        val admit = admission ?: return
        val revision = bindingRevision
        fun currentOwner() = !panelClosed && callback === deliver && admission === admit && bindingRevision == revision
        if (currentOwner() && retry.browserPanelAdmitsInput() && currentOwner() && admit() &&
            currentOwner() && retry.browserPanelAdmitsInput() && currentOwner()) deliver()
    }

    override fun close() {
        if (panelClosed) return
        callback = null
        admission = null
        bindingRevision++
        val failure = closePreservingPrimaryFailure(null) { super.close() }
        closePreservingPrimaryFailure(failure, retry::close)?.let { throw it }
    }
    private fun dp(value: Int) = (value * theme.density.density).roundToInt()
}
