package smallthingz.ui.reverb

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import smallthingz.ui.common.ToggleState
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.components.NativeChoice
import smallthingz.ui.components.NativeLabeledSegmentedLayout
import smallthingz.ui.components.NativeSettingsRow
import smallthingz.ui.reverb.settings.ReverbSettingsToggle
import smallthingz.ui.reverb.settings.settingsSectionTitle
import smallthingz.ui.text.NativeTypographyRole
import kotlin.math.roundToInt

/** A shared row handle, not an arbitrary application View slot. */
sealed interface ReverbBrowserPanelRow : AutoCloseable { val view: View }

data class ReverbBrowserPanelSetting(
    val title: String,
    val detail: String? = null,
    val value: String? = null,
    val icon: Drawable? = null,
    val actionId: String? = null,
    val enabled: Boolean = true,
)

class ReverbBrowserPanelSettingRow(context: Context, theme: ReverbTheme,
    initial: ReverbBrowserPanelSetting, onAction: (String) -> Unit = {},
) : NativeSettingsRow(context, theme.palette.settingsControls), ReverbBrowserPanelRow {
    override val view: View get() = this
    private var content = initial
    private var callback: ((String) -> Unit)? = onAction
    private var closed = false
    init { filterTouchesWhenObscured = true; bindContent(initial) }
    fun bindContent(next: ReverbBrowserPanelSetting) {
        if (closed) return
        if (content.actionId != next.actionId || !next.enabled) cancelBrowserPanelInput()
        content = next
        isEnabled = next.enabled
        bind(next.title, next.detail, next.icon, next.value, next.actionId?.let { id -> {
            if (!closed && content === next && browserPanelAdmitsInput() && !closed && content === next)
                callback?.invoke(id)
        } })
    }
    override fun close() {
        if (closed) return
        closed = true
        callback = null
        cancelBrowserPanelInput()
        isEnabled = false
        bind(content.title, content.detail, content.icon, content.value)
    }
}

/** Origin direction is semantic; verification and localized connection text belong to the browser. */
fun reverbBrowserPanelSiteIdentity(context: Context, theme: ReverbTheme, origin: String,
    connection: String?, icon: Drawable?): ReverbBrowserPanelSettingRow =
    ReverbBrowserPanelSettingRow(context, theme, ReverbBrowserPanelSetting(origin, connection, icon = icon)).apply {
        titleView.textDirection = View.TEXT_DIRECTION_LTR
    }

class ReverbBrowserPanelToggle internal constructor(context: Context, private val theme: ReverbTheme,
    val id: String, initial: ToggleState, onChange: (String, Boolean) -> Unit,
) : ReverbBrowserPanelRow {
    private var state = initial
    private var callback: ((String, Boolean) -> Unit)? = onChange
    private var closed = false
    private val toggle = ReverbSettingsToggle(context, theme, initial) { value ->
        val before = state
        if (!closed && before.enabled && view.browserPanelAdmitsInput() && !closed && state === before)
            callback?.invoke(id, value)
    }
    override val view: View get() = toggle.view
    fun bind(state: ToggleState) {
        if (closed) return
        this.state = state
        toggle.bind(state, theme)
    }
    override fun close() { closed = true; callback = null; toggle.close() }
}

data class ReverbBrowserPanelOption(
    val id: String,
    val label: String,
    val description: String? = null,
    val enabled: Boolean = true,
)

/** Capsule with optional labeled heading and an independently admitted Android-recovery action. */
class ReverbBrowserPanelChoice internal constructor(context: Context, private val theme: ReverbTheme,
    val id: String, private val label: String?, options: List<ReverbBrowserPanelOption>, selected: String,
    onSelect: (String, String) -> Boolean, onAction: (String) -> Unit,
) : ReverbBrowserPanelLayout(context), ReverbBrowserPanelRow {
    override val view: View get() = this
    private val environment = ReverbEnvironment(theme).forSettings()
    private var callback: ((String, String) -> Boolean)? = onSelect
    private val request: (String) -> Boolean = { value ->
        browserPanelAdmitsInput() && !panelClosed && callback?.invoke(id, value) == true
    }
    private val selector = theme.appearance.settingsSelectors.createSegmentedControl(context,
        theme.palette.settingsControls, theme.density.density, { browserPanelAdmitsInput() }, ReverbTextSurface::invalidate)
    private val heading = label?.let { settingsSectionTitle(context, it, environment).apply {
        ViewCompat.setAccessibilityHeading(this, true)
    } }
    private val labeled = heading?.let { title -> object : ViewGroup(context) {
        private val recipe = theme.appearance.settingsSelectors.retentionSection
        private val layout = NativeLabeledSegmentedLayout(title, selector)
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; addView(title); addView(selector) }
        private fun dp(value: Int) = (value * theme.density.density).roundToInt()
        override fun onMeasure(w: Int, h: Int) {
            val width = if (MeasureSpec.getMode(w) == MeasureSpec.UNSPECIFIED)
                layout.preferredWidth(dp(recipe.inlineGapDp)) + 2 * dp(recipe.sideInsetDp) else MeasureSpec.getSize(w)
            val inset = minOf(dp(recipe.sideInsetDp), width / 2)
            layout.measure((width - 2 * inset).coerceAtLeast(0), dp(recipe.inlineGapDp), dp(recipe.stackedGapDp),
                dp(recipe.preferredInlineWidthDp))
            setMeasuredDimension(resolveSize(width, w), resolveSize(layout.measuredHeight, h))
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            layout.layout(width, minOf(dp(recipe.sideInsetDp), width / 2), 0, layoutDirection == LAYOUT_DIRECTION_RTL)
        }
    } }
    private val recovery = ReverbBrowserPanelSettingRow(context, theme, ReverbBrowserPanelSetting(""), onAction)
    init {
        val inset = (8 * theme.density.density).roundToInt()
        setPaddingRelative(0, inset, 0, inset)
        addView(labeled ?: selector, LayoutParams(-1, -2))
        addView(recovery, LayoutParams(-1, -2))
        recovery.visibility = GONE
        selector.onSelectionRequest = request
        bind(options, selected)
    }
    fun bind(options: List<ReverbBrowserPanelOption>, selected: String, recovery: ReverbBrowserPanelSetting? = null) {
        if (panelClosed) return
        require(options.map { it.id }.toSet().size == options.size && options.any { it.id == selected })
        selector.bind(options.map { NativeChoice(it.id, it.label, enabled = it.enabled, contentDescription = it.description) },
            selected, theme.palette.settingsControls, theme.density.density)
        this.recovery.bindContent(recovery ?: ReverbBrowserPanelSetting("", enabled = false))
        this.recovery.visibility = if (recovery == null) GONE else VISIBLE
    }
    override fun close() {
        if (panelClosed) return
        super.close()
        callback = null
        selector.close(); recovery.close()
    }
}

enum class ReverbBrowserPanelSettingsAction { BACK, UNDO, APPLY }

/** Candidate browser Settings presentation; drafts, permission recovery and persistence stay in the host. */
open class ReverbBrowserPanelSettings(context: Context, private val theme: ReverbTheme, title: String,
    backLabel: String, undoLabel: String, applyLabel: String,
) : ReverbBrowserPanelLayout(context) {
    var onPanelAction: ((ReverbBrowserPanelSettingsAction) -> Unit)? = null
    private var dirty = false
    private val back = browserPanelToolbarAction(context, theme, backLabel, ReverbIcon.Back) {
        if (!panelClosed) onPanelAction?.invoke(ReverbBrowserPanelSettingsAction.BACK)
    }
    private val undo = browserPanelToolbarAction(context, theme, undoLabel, ReverbIcon.Undo) {
        if (!panelClosed && dirty) onPanelAction?.invoke(ReverbBrowserPanelSettingsAction.UNDO)
    }
    private val apply = browserPanelToolbarAction(context, theme, applyLabel, ReverbIcon.Check) {
        if (!panelClosed && dirty) onPanelAction?.invoke(ReverbBrowserPanelSettingsAction.APPLY)
    }
    private val draftActions = LinearLayout(context).apply { addView(undo); addView(apply) }
    private val groups = LinearLayout(context).apply {
        orientation = VERTICAL
        setPaddingRelative(dp(16), dp(12), dp(16), dp(16))
    }
    private val feedback = createReverbSupportingParagraph(context, "", NativeTypographyRole.BODY_SMALL, theme).apply {
        setPaddingRelative(dp(16), dp(8), dp(16), 0)
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        visibility = GONE
    }
    private var message = ""
    private val owned = linkedSetOf<ReverbBrowserPanelRow>()
    init {
        background = theme.appearance.backdrops.pageBackground(theme.palette)
        ViewCompat.setAccessibilityPaneTitle(this, title)
        val heading = theme.paragraph(context, title, theme.appearance.toolbars.centeredTitleRole).apply {
            ViewCompat.setAccessibilityHeading(this, true)
        }
        addView(ReverbTitledTopBarLayout(context, theme.density.density, theme.palette, back, heading, draftActions,
            style = theme.appearance.toolbars),
            LayoutParams(-1, -2))
        addView(feedback, LayoutParams(-1, -2))
        addView(ScrollView(context).apply { isFillViewport = true; addView(groups, FrameLayout.LayoutParams(-1, -2)) },
            LayoutParams(-1, 0, 1f))
        bindPendingActions(false)
    }
    fun focusBackAction() { if (!back.accessibilityAction.requestFocus()) requestFocus() }
    fun bindPendingActions(dirty: Boolean) {
        if (panelClosed) return
        this.dirty = dirty
        if (!dirty) {
            draftActions.cancelBrowserPanelInput()
            if (draftActions.hasFocus()) focusBackAction()
        }
        undo.visibility = if (dirty) VISIBLE else GONE
        apply.visibility = undo.visibility
    }
    fun showMessage(value: String) {
        if (panelClosed) return
        message = value; feedback.setText(value); feedback.visibility = VISIBLE
    }
    fun clearMessage(value: String) {
        if (message != value || panelClosed) return
        message = ""; feedback.setText(""); feedback.visibility = GONE
    }
    fun addGroup(label: String?, rows: List<ReverbBrowserPanelRow>) {
        check(!panelClosed)
        require(rows.all { it.view.parent == null }) { "Browser rows must be unparented" }
        val environment = ReverbEnvironment(theme).forSettings()
        label?.let {
            val recipe = theme.appearance.settingsSelectors
            groups.addView(settingsSectionTitle(context, it, environment).apply {
                ViewCompat.setAccessibilityHeading(this, true)
                setPaddingRelative(dp(recipe.recordingSection.sideInsetDp),
                    if (groups.childCount > 0) dp(recipe.recordingSection.titleTopInsetDp) else 0,
                    dp(recipe.recordingSection.sideInsetDp), dp(recipe.retentionSection.stackedGapDp))
            })
        }
        val recipe = theme.appearance.groupSurfaces.settingsGroup
        groups.addView(recipe.create(context, ReverbTextSurface::invalidate).apply {
            recipe.bind(this, theme.palette)
            rows.forEachIndexed { index, row ->
                addRow(row.view, NativeGroupLayout.LayoutParams(-1, -2).apply { dividerTop = index > 0 })
                owned.add(row)
            }
        }, LayoutParams(-1, -2))
    }
    fun createToggle(id: String, initial: ToggleState, onChange: (String, Boolean) -> Unit) =
        ReverbBrowserPanelToggle(context, theme, id, initial, onChange).also { check(!panelClosed); owned.add(it) }
    fun createChoice(id: String, label: String?, options: List<ReverbBrowserPanelOption>, selected: String,
        onSelect: (String, String) -> Boolean, onAction: (String) -> Unit = {}) =
        ReverbBrowserPanelChoice(context, theme, id, label, options, selected, onSelect, onAction)
            .also { check(!panelClosed); owned.add(it) }
    fun createSetting(content: ReverbBrowserPanelSetting, onAction: (String) -> Unit = {}) =
        ReverbBrowserPanelSettingRow(context, theme, content, onAction).also { check(!panelClosed); owned.add(it) }
    override fun close() {
        if (panelClosed) return
        super.close()
        onPanelAction = null
        owned.forEach { it.close() }; owned.clear()
    }
    private fun dp(value: Int) = (value * theme.density.density).roundToInt()
    private fun dp(value: Float) = (value * theme.density.density).roundToInt()
}
