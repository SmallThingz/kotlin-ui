package smallthingz.ui.common

import android.content.Context
import android.view.View
import smallthingz.ui.components.NativeChoice
import smallthingz.ui.components.NativeLabeledSegmentedLayout
import smallthingz.ui.components.NativeSegmentedControl

/** Stable identity and display text for a segment. Distinct values may display identical text. */
data class SegmentOption<T : Any>(val value: T, val label: String, val enabled: Boolean = true)

/** Retained single-selection capsule. Owners bind the authoritative selection through [bind]. */
class SegmentedSelector<T : Any> internal constructor(
    private val capsule: NativeSegmentedControl,
    options: List<SegmentOption<T>>,
    selected: T,
    theme: ControlTheme,
    onSelectionRequest: (T) -> Boolean,
) : AutoCloseable {
    constructor(context: Context, theme: ControlTheme, options: List<SegmentOption<T>>, selected: T,
        inputAllowed: () -> Boolean = { true }, onSelectionRequest: (T) -> Boolean,
    ) : this(NativeSegmentedControl(context, theme.colors.settingsControls,
        theme.appearance.settingsSelectors.segmented, theme.density.density, inputAllowed),
        options, selected, theme, onSelectionRequest)

    val view: View get() = capsule
    private var closed = false
    private var lastOptions: List<SegmentOption<T>>? = null
    private var nativeOptions: List<NativeChoice> = emptyList()
    private var ids: Map<T, String> = emptyMap()
    private var values: Map<String, T> = emptyMap()
    private var nextId = 0L

    init {
        capsule.onSelectionRequest = { id -> values[id]?.let(onSelectionRequest) == true }
        bind(options, selected, theme)
    }

    fun bind(options: List<SegmentOption<T>>, selected: T, theme: ControlTheme) {
        if (closed) return
        if (lastOptions != options) {
            val nextIds = options.associate { it.value to (ids[it.value] ?: (++nextId).toString()) }
            require(nextIds.size == options.size) { "Segment values must be unique" }
            require(selected in nextIds) { "Selected value must be an option" }
            ids = nextIds
            values = ids.entries.associate { (value, id) -> id to value }
            nativeOptions = options.map { NativeChoice(ids.getValue(it.value), it.label, enabled = it.enabled) }
            lastOptions = options.toList()
        }
        capsule.bind(nativeOptions, ids.getValue(selected), theme.colors.settingsControls, theme.density.density)
    }

    fun layoutWithTitle(title: View) = NativeLabeledSegmentedLayout(title, capsule)

    override fun close() {
        if (closed) return
        closed = true
        capsule.close()
        lastOptions = null
        ids = emptyMap()
        values = emptyMap()
        nativeOptions = emptyList()
    }
}
