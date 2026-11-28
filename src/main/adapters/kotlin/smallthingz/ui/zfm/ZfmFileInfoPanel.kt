package smallthingz.ui.zfm

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import smallthingz.ui.components.NativeFloatingPanelSurface
import smallthingz.ui.components.NativeText
import kotlin.math.roundToInt

data class ZfmFileInfoField(val id: String, val text: String)
data class ZfmFileInfoAction(val id: String, val label: String, val enabled: Boolean, val invoke: () -> Unit)

/** One retained information presentation. The application owns its file, jobs and modal lifetime. */
class ZfmFileInfoPanel(
    context: Context,
    private val theme: ZfmTheme,
    title: String,
    onClose: () -> Unit,
) : NativeFloatingPanelSurface(context, theme.density.density,
    theme.appearance.floatingWindowRecipe, theme.colors), AutoCloseable {
    private val recipe = theme.appearance.floatingWindowRecipe
    private var closed = false
    val closeAction = theme.button(context, "Close") { if (!closed) onClose() }
    private val body = ZfmFileInfoBody(context, theme) { closeAction.requestFocus() }
    val scroll = ScrollView(context).apply {
        isFillViewport = false
        addView(body)
    }

    init {
        accessibilityPaneTitle = title
        // The shared floating window is edge-to-edge; follow its forwarded safe insets.
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val edges = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            setContentInsets(edges.left, edges.top, edges.right, edges.bottom)
            insets
        }
        val footer = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(NativeText(context, theme.palette, theme.nativeTextStyle(recipe.titleRole)).apply {
                text = title
                isAccessibilityHeading = true
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(closeAction, LinearLayout.LayoutParams(-2, -2))
        }
        setPanelViewport(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(recipe.horizontalPaddingDp), dp(recipe.topPaddingDp),
                dp(recipe.horizontalPaddingDp), dp(recipe.bottomPaddingDp))
            addView(scroll, LinearLayout.LayoutParams(-1, -2, 1f))
            addView(footer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(recipe.headingGapDp) })
        })
    }

    fun bind(nextFields: List<ZfmFileInfoField>, nextActions: List<ZfmFileInfoAction>) =
        body.bind(nextFields, nextActions)

    override fun close() {
        if (closed) return
        closed = true
        body.close()
        closeAction.close()
    }

    private fun dp(value: Float) = (value * theme.density.density).roundToInt()
}
