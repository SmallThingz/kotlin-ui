package smallthingz.ui.reverb

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import smallthingz.ui.common.ToggleState
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.reverb.settings.ReverbSettingsToggle
import smallthingz.ui.text.NativeTypographyRole

/** Retained, keyed Settings toggles with a live loading/empty/error note. No discovery or grants. */
class ReverbInformationToggleList<K : Any>(
    context: Context,
    private val theme: ReverbTheme,
    private val onChanged: (K, Boolean) -> Unit,
) : AutoCloseable {
    private val recipe = theme.appearance.groupSurfaces.settingsGroup
    private val group = recipe.create(context, ReverbTextSurface::invalidate).also { recipe.bind(it, theme.palette) }
    private val note = createReverbSupportingParagraph(context, "", NativeTypographyRole.BODY_SMALL, theme).apply {
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val rows = linkedMapOf<K, ReverbSettingsToggle>()
    private var state = emptyMap<K, ReverbInformationToggle<K>>()
    private var closed = false
    private var bound: List<ReverbInformationToggle<K>>? = null
    private var boundMessage: String? = null
    private var detailMaxLines = Int.MAX_VALUE
    private var detailActionLabel = ""
    private var onDetails: ((K, View) -> Unit)? = null
    private var detailsRevision = Any()
    val view = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(group, LinearLayout.LayoutParams(-1, -2))
        addView(note, LinearLayout.LayoutParams(-1, -2))
    }

    private val focusLease = cancelInformationInputOnFocusLoss(view, view::cancelPendingInputEvents)

    /**
     * Opt-in visual preview and named long action. Disabled rows expose neither input action.
     * The callback receives the current key and its actual row anchor, never copied provider data.
     * Null removes the action; Int.MAX_VALUE restores the original unlimited description layout.
     */
    fun bindDetails(maxLines: Int, actionLabel: String, onDetails: ((K, View) -> Unit)?) {
        require(maxLines > 0)
        require(onDetails == null || actionLabel.isNotBlank())
        if (closed) return
        detailsRevision = Any()
        detailMaxLines = maxLines
        detailActionLabel = actionLabel
        this.onDetails = onDetails
        rows.forEach { (key, row) -> state[key]?.let { bindRowDetails(it, row) } }
    }

    fun bind(values: List<ReverbInformationToggle<K>>, message: String?) {
        if (closed || (bound == values && boundMessage == message)) return
        val next = values.associateBy { it.id }
        require(next.size == values.size) { "Settings rows require unique IDs." }
        // A gesture admitted against old copy/state may not disclose or toggle the replacement.
        rows.values.forEach { it.view.cancelPendingInputEvents() }
        bound = values.toList()
        boundMessage = message
        val orderChanged = rows.keys.toList() != values.map { it.id }
        if (orderChanged) rows.values.forEach { it.view.cancelPendingInputEvents(); group.removeRow(it.view) }
        rows.keys.filterNot(next::containsKey).forEach { rows.remove(it)?.close() }
        state = next
        val ordered = linkedMapOf<K, ReverbSettingsToggle>()
        values.forEachIndexed { index, item ->
            val toggle = rows[item.id] ?: run {
                lateinit var created: ReverbSettingsToggle
                created = ReverbSettingsToggle(view.context, theme, item.toggle()) { checked ->
                    val current = state[item.id]
                    if (!closed && rows[item.id] === created && current?.enabled == true &&
                        reverbInformationInputAllowed(created.view) && !closed && rows[item.id] === created &&
                        state[item.id] === current) onChanged(item.id, checked)
                }
                created.also { it.view.filterTouchesWhenObscured = true }
            }
            toggle.bind(item.toggle(), theme)
            toggle.bindAppIcon(item.appIcon)
            bindRowDetails(item, toggle)
            ViewCompat.setStateDescription(toggle.view, item.stateDescription)
            ordered[item.id] = toggle
            if (orderChanged) group.addRow(toggle.view, NativeGroupLayout.LayoutParams(-1, -2).apply {
                dividerTop = index > 0
            })
        }
        rows.clear()
        rows.putAll(ordered)
        group.visibility = if (values.isEmpty()) View.GONE else View.VISIBLE
        note.setText(message.orEmpty())
        note.visibility = if (message.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun ReverbInformationToggle<K>.toggle() = ToggleState(title, checked, enabled, detail)

    private fun bindRowDetails(item: ReverbInformationToggle<K>, row: ReverbSettingsToggle) {
        val action = onDetails
        val revision = detailsRevision
        val invoke: (() -> Boolean)? = if (action == null || !item.enabled) null else {
            {
                fun current() = !closed && detailsRevision === revision &&
                    rows[item.id] === row && state[item.id] === item && item.enabled
                if (current() && reverbInformationInputAllowed(row.view) && current()) {
                    action(item.id, row.view)
                    true
                } else false
            }
        }
        row.bindDetails(detailMaxLines, detailActionLabel, invoke)
    }

    override fun close() {
        if (closed) return
        closed = true
        detailsRevision = Any()
        onDetails = null
        focusLease.close()
        bound = null
        state = emptyMap()
        rows.values.forEach(ReverbSettingsToggle::close)
        rows.clear()
        group.removeAllViews()
    }
}
