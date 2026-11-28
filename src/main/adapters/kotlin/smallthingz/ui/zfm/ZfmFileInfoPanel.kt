package smallthingz.ui.zfm

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import smallthingz.ui.components.NativeFloatingPanelSurface
import smallthingz.ui.components.NativeMaterialButtonView
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
    private val fields = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val actions = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val labels = linkedMapOf<String, NativeText>()
    private val buttons = linkedMapOf<String, NativeMaterialButtonView>()
    private var callbacks = emptyMap<String, ZfmFileInfoAction>()
    private var closed = false
    val scroll = ScrollView(context).apply {
        isFillViewport = false
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(fields, LinearLayout.LayoutParams(-1, -2))
            addView(actions, LinearLayout.LayoutParams(-1, -2))
        })
    }
    val closeAction = theme.button(context, "Close") { if (!closed) onClose() }

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

    /** Unchanged fields never receive setText; their selection and layout stay owned by the user. */
    fun bind(nextFields: List<ZfmFileInfoField>, nextActions: List<ZfmFileInfoAction>) {
        check(!closed)
        require(nextFields.map { it.id }.distinct().size == nextFields.size)
        require(nextActions.map { it.id }.distinct().size == nextActions.size)
        val fieldIds = nextFields.mapTo(HashSet()) { it.id }
        labels.keys.filter { it !in fieldIds }.forEach { id ->
            val label = labels.remove(id)!!
            if (label.hasFocus()) closeAction.requestFocus()
            fields.removeView(label)
        }
        nextFields.forEachIndexed { index, field ->
            val label = labels.getOrPut(field.id) {
                NativeText(context, theme.palette,
                    theme.nativeTextStyle(recipe.summaryRole).copy(includeFontPadding = true)).apply {
                    setTextIsSelectable(true)
                    setPaddingRelative(0, dp(recipe.summaryGapDp), 0, dp(recipe.summaryGapDp))
                }
            }
            if (label.text.toString() != field.text) label.text = field.text
            if (label.parent == null) fields.addView(label, index, LinearLayout.LayoutParams(-1, -2))
            else if (fields.indexOfChild(label) != index) {
                fields.removeView(label)
                fields.addView(label, index, LinearLayout.LayoutParams(-1, -2))
            }
        }
        callbacks = nextActions.associateBy { it.id }
        buttons.keys.filter { it !in callbacks }.forEach { id ->
            val button = buttons.remove(id)!!
            if (button.hasFocus()) closeAction.requestFocus()
            actions.removeView(button)
            button.close()
        }
        nextActions.forEachIndexed { index, action ->
            val button = buttons.getOrPut(action.id) {
                theme.button(context, action.label, enabled = action.enabled) {
                    callbacks[action.id]?.let { if (!closed && it.enabled) it.invoke() }
                }
            }
            // Disabled native buttons cannot keep focus. Choose a visible stable target first;
            // a later completion merely re-enables the button and never steals focus back.
            if (!action.enabled && button.hasFocus()) closeAction.requestFocus()
            if (button.isEnabled != action.enabled || button.label.getText() != action.label)
                theme.bindButton(button, label = action.label, enabled = action.enabled)
            if (button.parent == null) actions.addView(button, index, LinearLayout.LayoutParams(-1, -2))
            else if (actions.indexOfChild(button) != index) {
                actions.removeView(button)
                actions.addView(button, index, LinearLayout.LayoutParams(-1, -2))
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        callbacks = emptyMap()
        buttons.values.forEach { it.close() }
        closeAction.close()
    }

    private fun dp(value: Float) = (value * theme.density.density).roundToInt()
}
