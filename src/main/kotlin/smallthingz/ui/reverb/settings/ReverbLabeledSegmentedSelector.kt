package smallthingz.ui.reverb.settings

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import smallthingz.ui.common.SegmentOption
import smallthingz.ui.components.acceptsNativeInput
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.reverb.ReverbGlyphView
import smallthingz.ui.reverb.ReverbIcon
import smallthingz.ui.reverb.ReverbStandardIconButtonView
import smallthingz.ui.reverb.bindSupportingParagraph
import smallthingz.ui.reverb.createReverbSupportingParagraph
import smallthingz.ui.text.NativeTypographyRole
import kotlin.math.roundToInt

/** A localized Help label and an independently available action; appearance belongs to Reverb. */
data class ReverbSelectorHelp(val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

/** A settings section label and Reverb's responsive capsule for named modes. */
class ReverbLabeledSegmentedSelector<T : Any>(
    context: Context,
    environment: ReverbEnvironment,
    label: String,
    options: List<SegmentOption<T>>,
    selected: T,
    enabled: Boolean = true,
    help: ReverbSelectorHelp? = null,
    disabledReason: String? = null,
    inputAllowed: () -> Boolean,
    onSelectionRequest: (T) -> Boolean,
) : ViewGroup(context), AutoCloseable {
    /** Retain positional callers and the original constructor, including its trailing lambda. */
    constructor(context: Context, environment: ReverbEnvironment, label: String,
        options: List<SegmentOption<T>>, selected: T, enabled: Boolean = true,
        inputAllowed: () -> Boolean, onSelectionRequest: (T) -> Boolean,
    ) : this(context, environment, label, options, selected, enabled, null, null,
        inputAllowed, onSelectionRequest)

    private var environment = environment
    private val recipe get() = environment.appearance.settingsSelectors.retentionSection
    private var help = help
    private var disabledReason = disabledReason
    private var inputAdmission: (() -> Boolean)? = inputAllowed
    private var closed = false
    private val title = settingsSectionTitle(context, label, environment)
    private val helpButton = ReverbStandardIconButtonView(context, environment, { this },
        glyph = ReverbGlyphView(context, ReverbIcon.Info, environment.palette.muted))
    private val titleRow = TitleRow(context)
    private val reason = createReverbSupportingParagraph(context, "", NativeTypographyRole.BODY_SMALL,
        environment.theme)
    private val selector = ReverbSegmentedSelector(context, environment.theme,
        availableOptions(options, enabled, visibleReason(enabled, disabledReason)), selected,
        inputAllowed, onSelectionRequest)
    private val content = selector.layoutWithTitle(titleRow)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        helpButton.visual.mergedRowAccessibility.enableStandaloneAccessibility({ !closed && this.help != null }) {
            this.help?.label
        }
        // The standalone action owns its localized description; don't expose the same glyph label twice.
        helpButton.visual.glyph.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(titleRow)
        addView(selector.view)
        addView(reason)
        bindHelp()
        bindReason(enabled)
    }

    /** Bind silently, retaining the optional Help and disabled reason previously supplied. */
    fun bind(label: String, options: List<SegmentOption<T>>, selected: T, enabled: Boolean,
             environment: ReverbEnvironment) =
        bind(label, options, selected, enabled, environment, help, disabledReason)

    /** Bind authoritative state. Pass null to remove Help or the selector-wide disabled reason. */
    fun bind(label: String, options: List<SegmentOption<T>>, selected: T, enabled: Boolean,
             environment: ReverbEnvironment, help: ReverbSelectorHelp? = this.help,
             disabledReason: String? = this.disabledReason) {
        if (closed) return
        val environmentChanged = this.environment !== environment
        // These dimensions belong to this wrapper, rather than to a child's text or visibility.
        val geometryChanged = this.environment.textDensity != environment.textDensity ||
            recipe != environment.appearance.settingsSelectors.retentionSection ||
            this.environment.appearance.outlinedField.supportingPaddingDp !=
            environment.appearance.outlinedField.supportingPaddingDp
        val helpChanged = environmentChanged || this.help?.label != help?.label ||
            this.help?.enabled != help?.enabled
        if (environmentChanged) {
            this.environment = environment
            bindSectionTitle(title, environment)
            environment.theme.bindSupportingParagraph(reason, NativeTypographyRole.BODY_SMALL)
        }
        this.help = help
        this.disabledReason = disabledReason
        title.setText(label)
        bindHelp(rebind = helpChanged)
        bindReason(enabled)
        if (!enabled) selector.view.findFocus()?.clearFocus()
        selector.bind(availableOptions(options, enabled, visibleReason(enabled, disabledReason)), selected,
            environment.theme)
        // Text, Help/reason visibility and capsule option changes request their own layout.
        // Selection-only binds retain the capsule's existing geometry and animation bounds.
        if (geometryChanged) requestLayout()
    }

    private fun bindHelp(rebind: Boolean = true) {
        val action = help
        val enabled = !closed && action?.enabled == true
        if (!enabled) {
            helpButton.cancelInput()
            clearActionFocus(helpButton.visual)
        }
        if (rebind) {
            // The shared icon action requests layout on bind. Its callback reads the current Help
            // owner, so a callback-only replacement needs neither rebinding nor a new traversal.
            helpButton.bind(environment, ReverbIcon.Info, action?.label.orEmpty(), enabled,
                environment.palette.muted, environment.palette.ink) {
                val current = help
                if (current != null && canDispatchHelp(current) && inputAdmission?.invoke() == true &&
                    canDispatchHelp(current) && helpButton.visual.acceptsNativeInput() &&
                    canDispatchHelp(current)) current.onClick()
            }
        }
        helpButton.visibility = if (action == null || closed) GONE else VISIBLE
    }

    private fun canDispatchHelp(action: ReverbSelectorHelp) =
        !closed && help === action && action.enabled && helpButton.isShown && isEnabled &&
            isAttachedToWindow && hasWindowFocus()

    private fun bindReason(enabled: Boolean) {
        val text = visibleReason(enabled, disabledReason)
        if (text == null) clearActionFocus(reason)
        reason.setText(text.orEmpty())
        reason.visibility = if (text == null) GONE else VISIBLE
    }

    private fun clearActionFocus(view: View) {
        view.clearFocus()
        if (view.isAccessibilityFocused) {
            view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null)
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        val inlineGap = dp(recipe.inlineGapDp)
        val width = if (MeasureSpec.getMode(w) == MeasureSpec.UNSPECIFIED)
            content.preferredWidth(inlineGap) else MeasureSpec.getSize(w)
        val inner = width.coerceAtLeast(0)
        content.measure(inner, inlineGap, dp(recipe.stackedGapDp),
            preferredInlineWidth = dp(recipe.preferredInlineWidthDp))
        val supportingHeight = if (reason.visibility != GONE) {
            reason.measure(MeasureSpec.makeMeasureSpec(inner, MeasureSpec.EXACTLY), unspecified())
            supportingPadding() + reason.measuredHeight
        } else 0
        setMeasuredDimension(resolveSize(width, w), resolveSize(content.measuredHeight + supportingHeight, h))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val inset = 0
        content.layout(width, inset, 0, layoutDirection == LAYOUT_DIRECTION_RTL)
        if (reason.visibility != GONE) {
            val top = content.measuredHeight + supportingPadding()
            reason.layout(inset, top, width - inset, top + reason.measuredHeight)
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE && isAttachedToWindow) {
            helpButton.cancelInput()
            clearActionFocus(helpButton.visual)
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) helpButton.cancelInput()
    }

    override fun close() {
        if (closed) return
        closed = true
        help = null
        inputAdmission = null
        findFocus()?.clearFocus()
        bindHelp()
        selector.close()
    }

    private fun supportingPadding() = dp(environment.appearance.outlinedField.supportingPaddingDp)
    private fun dp(value: Int) = (value * environment.textDensity.density).roundToInt()
    private fun dp(value: Float) = (value * environment.textDensity.density).roundToInt()
    private fun unspecified() = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)

    private fun visibleReason(enabled: Boolean, reason: String?) = reason?.takeIf { !enabled && it.isNotBlank() }

    private fun availableOptions(options: List<SegmentOption<T>>, enabled: Boolean, visibleReason: String?) =
        if (enabled) options else options.map { option ->
            // A selector-wide reason has its own paragraph, so don't repeat it on every disabled chip.
            val ownReason = option.disabledReason?.takeIf {
                !option.enabled && it.isNotBlank() && it.trim() != visibleReason?.trim()
            }
            option.copy(enabled = false, disabledReason = ownReason)
        }

    /** Keep Help's standard footprint before wrapping the title; the shared layout reflows the capsule. */
    private inner class TitleRow(context: Context) : ViewGroup(context) {
        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(title)
            addView(helpButton)
        }

        override fun onMeasure(w: Int, h: Int) {
            val bounded = MeasureSpec.getMode(w) != MeasureSpec.UNSPECIFIED
            val available = if (bounded) MeasureSpec.getSize(w) else 0
            val helpVisible = helpButton.visibility != GONE
            if (helpVisible) helpButton.measure(w, unspecified())
            val helpWidth = if (helpVisible) helpButton.measuredWidth else 0
            title.measure(if (bounded) MeasureSpec.makeMeasureSpec((available - helpWidth).coerceAtLeast(0),
                MeasureSpec.AT_MOST) else unspecified(), unspecified())
            val height = maxOf(title.measuredHeight, if (helpVisible) helpButton.measuredHeight else 0)
            setMeasuredDimension(resolveSize(title.measuredWidth + helpWidth, w), resolveSize(height, h))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
            fun place(view: View, start: Int) {
                val x = if (rtl) width - start - view.measuredWidth else start
                val y = (height - view.measuredHeight) / 2
                view.layout(x, y, x + view.measuredWidth, y + view.measuredHeight)
            }
            place(title, 0)
            if (helpButton.visibility != GONE) place(helpButton, title.measuredWidth)
        }
    }
}
