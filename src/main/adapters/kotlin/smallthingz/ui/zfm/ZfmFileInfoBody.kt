package smallthingz.ui.zfm

import android.content.Context
import android.widget.LinearLayout
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.components.NativeText
import kotlin.math.roundToInt

/** Retained fields/actions only. The host owns scrolling, Close and explicit final disposal.
 * Temporary detach does not retire callbacks or native button resources.
 */
class ZfmFileInfoBody(
    context: Context,
    private val theme: ZfmTheme,
    private val moveFocusBeforeRemoval: () -> Unit = {},
) : LinearLayout(context), AutoCloseable {
    private val recipe = theme.appearance.floatingWindowRecipe
    private val fields = LinearLayout(context).apply { orientation = VERTICAL }
    private val actions = LinearLayout(context).apply { orientation = VERTICAL }
    private val labels = linkedMapOf<String, NativeText>()
    private val buttons = linkedMapOf<String, NativeMaterialButtonView>()
    private var callbacks = emptyMap<String, ZfmFileInfoAction>()
    private var closed = false

    init {
        orientation = VERTICAL
        addView(fields, LayoutParams(-1, -2))
        addView(actions, LayoutParams(-1, -2))
    }

    /** Unchanged fields never receive setText; their selection and layout stay owned by the user. */
    fun bind(nextFields: List<ZfmFileInfoField>, nextActions: List<ZfmFileInfoAction>) {
        check(!closed)
        require(nextFields.map { it.id }.distinct().size == nextFields.size)
        require(nextActions.map { it.id }.distinct().size == nextActions.size)
        val fieldIds = nextFields.mapTo(HashSet()) { it.id }
        labels.keys.filter { it !in fieldIds }.forEach { id ->
            val label = labels.remove(id)!!
            if (label.hasFocus()) moveFocusBeforeRemoval()
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
            if (button.hasFocus()) moveFocusBeforeRemoval()
            actions.removeView(button)
            button.close()
        }
        nextActions.forEachIndexed { index, action ->
            val actionId = action.id
            val button = buttons.getOrPut(action.id) {
                theme.button(context, action.label, enabled = action.enabled) {
                    callbacks[actionId]?.let { if (!closed && it.enabled) it.invoke() }
                }
            }
            // Let a rebinding host move focus before disabling or removing its current action.
            // Disabled native buttons cannot keep focus;
            // a later completion merely re-enables the button and never steals focus back.
            if (!action.enabled && button.hasFocus()) moveFocusBeforeRemoval()
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
    }

    private fun dp(value: Float) = (value * theme.density.density).roundToInt()
}
