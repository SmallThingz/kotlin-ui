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
    val view = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(group, LinearLayout.LayoutParams(-1, -2))
        addView(note, LinearLayout.LayoutParams(-1, -2))
    }

    private val focusLease = cancelInformationInputOnFocusLoss(view, view::cancelPendingInputEvents)

    fun bind(values: List<ReverbInformationToggle<K>>, message: String?) {
        if (closed || (bound == values && boundMessage == message)) return
        val next = values.associateBy { it.id }
        require(next.size == values.size) { "Settings rows require unique IDs." }
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

    override fun close() {
        if (closed) return
        closed = true
        focusLease.close()
        bound = null
        state = emptyMap()
        rows.values.forEach(ReverbSettingsToggle::close)
        rows.clear()
        group.removeAllViews()
    }
}
