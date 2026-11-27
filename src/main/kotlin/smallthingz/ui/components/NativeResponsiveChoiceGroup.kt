package smallthingz.ui.components

import android.content.Context
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import smallthingz.ui.animation.view.transitionForegroundInk
import smallthingz.ui.common.ControlTheme
import smallthingz.ui.common.dropdownColors
import smallthingz.ui.text.NativeTypographyRole.BODY_LARGE
import smallthingz.ui.text.NativeTypographyRole.BODY_SMALL
import smallthingz.ui.text.view.ParagraphView
import kotlin.math.roundToInt

/**
 * Retained choices shown as equal columns, a single field, or a summary expander.
 * The host owns expansion binding, labels, availability and choice lifetime. Layout only changes
 * existing children's parameters: it never reparents anchors or publishes selection callbacks.
 * Recreate with the window's ControlTheme when its typography/configuration changes.
 */
abstract class NativeResponsiveChoiceGroup(
    context: Context,
    private val theme: ControlTheme,
    private val label: String,
    private val expandDescription: String,
    private val collapseDescription: String,
    private val heading: View? = null,
    private val minimumColumnWidthDp: Float = 192f,
) : LinearLayout(context), AutoCloseable {
    protected abstract var choicesExpanded: Boolean
    protected abstract val summaryEnabled: Boolean
    protected abstract fun selectionSummary(): String

    private class Field(val view: View, val focusTarget: View, val minimumWidth: Int,
        val popupOpen: () -> Boolean, val bindPresentationEnabled: (Boolean) -> Unit)

    private val fields = mutableListOf<Field>()
    private val choices = LinearLayout(context).apply { isBaselineAligned = false }
    private val gap = (NativeScreenSpacing.CONTROL_GAP_DP * resources.displayMetrics.density).roundToInt()
    private var panelWidth = Int.MAX_VALUE
    private var mode = NativeResponsiveChoiceLayout.HIDDEN
    private var closed = false
    private var renderedSummary: Pair<Boolean, String>? = null
    private val arrow = createDropdownIcon(context, theme.colors.muted)
    private val selection = ParagraphView(context, theme.appearance.typography[BODY_LARGE],
        theme.density, theme.fontResolver, theme.colors.ink)
    private val trigger = NativeDropdown<Boolean>(context,
        ParagraphView(context, theme.appearance.typography[BODY_SMALL], theme.density,
            theme.fontResolver, theme.colors.muted), selection, arrow, theme.dropdownColors(),
        inputRoot = { arrow.view.rootView.takeIf { arrow.view.isAttachedToWindow } },
        onBinding = { _, _ -> },
        open = { _, _ -> choicesExpanded = !choicesExpanded; refreshPresentation() },
        foregroundColor = ::transitionForegroundInk)
    private val combined = theme.appearance.groupSurfaces.settingsChoices.let { recipe ->
        recipe.create(context).apply {
            recipe.bindImmediately(this, theme.colors)
            addRow(trigger, NativeGroupLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    init {
        require(minimumColumnWidthDp.isFinite() && minimumColumnWidthDp >= 0f)
        orientation = VERTICAL
        selection.setTextLayout()
        addView(combined, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(choices, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    /** Ownership stays with the host; hidden fields do not reserve a column or a gap. */
    fun addChoiceView(view: View, focusTarget: View, caption: String, optionLabels: List<String>,
        popupOpen: () -> Boolean, bindPresentationEnabled: (Boolean) -> Unit) {
        check(!closed)
        require(view.parent == null) { "A choice must be unparented" }
        val minimumWidth = dropdownColumnWidth(context, theme, caption, optionLabels, minimumColumnWidthDp)
        fields += Field(view, focusTarget, minimumWidth, popupOpen, bindPresentationEnabled)
        choices.addView(view, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        refreshPresentation()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        panelWidth = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
            else (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        refreshPresentation()
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun refreshPresentation() {
        if (closed) return
        val visible = fields.filter { it.view.visibility != GONE }
        val next = responsiveChoiceLayout(panelWidth, visible.map(Field::minimumWidth), gap)
        if (next == NativeResponsiveChoiceLayout.COMBINED && mode != next &&
            visible.any { it.focusTarget.hasFocus() || it.popupOpen() }) choicesExpanded = true
        mode = next
        val compact = mode == NativeResponsiveChoiceLayout.COMBINED
        val paired = mode == NativeResponsiveChoiceLayout.PAIRED
        val showChoices = mode != NativeResponsiveChoiceLayout.HIDDEN && (!compact || choicesExpanded)
        val rowVisibility = if (mode == NativeResponsiveChoiceLayout.HIDDEN) GONE else VISIBLE
        visibility = rowVisibility
        heading?.visibility = rowVisibility
        if (compact) {
            combined.visibility = VISIBLE
            bindSummary()
        }
        if (showChoices) choices.visibility = VISIBLE
        choices.orientation = if (paired) HORIZONTAL else VERTICAL
        val choicesParams = choices.layoutParams as LayoutParams
        val topMargin = if (compact) gap else 0
        if (choicesParams.topMargin != topMargin) {
            choicesParams.topMargin = topMargin
            choices.layoutParams = choicesParams
        }
        visible.forEachIndexed { index, field ->
            val params = field.view.layoutParams as LayoutParams
            val width = if (paired) 0 else MATCH_PARENT
            val weight = if (paired) 1f else 0f
            val start = if (paired && index > 0) gap else 0
            val top = if (!paired && index > 0) gap else 0
            if (params.width != width || params.weight != weight ||
                params.marginStart != start || params.topMargin != top) {
                params.width = width
                params.weight = weight
                params.marginStart = start
                params.topMargin = top
                field.view.layoutParams = params
            }
        }
        if (!showChoices && choices.hasFocus() && compact) trigger.requestFocus()
        fields.forEach { it.bindPresentationEnabled(showChoices) }
        if (!compact && trigger.hasFocus()) {
            visible.firstOrNull { it.focusTarget.isEnabled }?.focusTarget?.requestFocus()
        }
        if (!showChoices) choices.visibility = GONE
        if (!compact) combined.visibility = GONE
    }

    private fun bindSummary() {
        val expanded = choicesExpanded
        val summary = if (expanded) "" else selectionSummary()
        val content = expanded to summary
        if (renderedSummary != content) {
            renderedSummary = content
            trigger.bindDescription(if (expanded) collapseDescription else expandDescription)
            arrow.view.rotation = if (expanded) 180f else 0f
            trigger.bind(NativeDropdownContent(label, expanded, listOf(false, true)) { summary },
                expanded, theme.colors)
        }
        val enabled = summaryEnabled
        trigger.isEnabled = enabled
        trigger.isFocusable = enabled
        trigger.alpha = if (enabled) 1f else theme.appearance.joinedFields.fieldStyle.disabledContentAlpha
    }

    override fun close() {
        if (closed) return
        closed = true
        trigger.close()
        fields.clear()
    }
}
