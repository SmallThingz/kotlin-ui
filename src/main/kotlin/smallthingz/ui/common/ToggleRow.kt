package smallthingz.ui.common

import android.content.Context
import android.view.View
import smallthingz.ui.components.NativeMaterialSwitchView
import smallthingz.ui.components.NativeToggleRow
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.view.ParagraphView
import java.util.function.LongSupplier

/** Current copy and checked state for one toggle row. */
data class ToggleState(val label: String, val checked: Boolean, val enabled: Boolean, val description: String? = null)

/** An actionable title, optional description, and switch with one accessible checked state. */
class ToggleRow internal constructor(
    context: Context,
    initial: ToggleState,
    theme: ControlTheme,
    inputRoot: (() -> View?)?,
    private val muteDisabledLabel: Boolean,
    private val paintedSurface: ((View) -> Long)?,
    onChanged: (Boolean) -> Unit,
) : AutoCloseable {
    constructor(context: Context, initial: ToggleState, theme: ControlTheme, inputRoot: (() -> View?)? = null,
        onChanged: (Boolean) -> Unit,
    ) : this(context, initial, theme, inputRoot, true, null, onChanged)

    private val titleRole = NativeTypographyRole.BODY_LARGE
    private val summaryRole = NativeTypographyRole.BODY_SMALL
    private val title = ParagraphView(context, theme.appearance.typography[titleRole], theme.density, theme.fontResolver,
        labelColor(initial, theme))
    private val summary = ParagraphView(context, theme.appearance.typography[summaryRole], theme.density, theme.fontResolver,
        theme.colors.muted)
    private val row = NativeToggleRow(context, title, summary,
        NativeMaterialSwitchView(context, theme.colors.switchPalette, initial.checked, initial.enabled), inputRoot, onChanged)
    val view: View get() = row
    private var closed = false

    init {
        if (paintedSurface != null) {
            title.paintedTextSurface = LongSupplier { paintedSurface.invoke(title) }
            summary.paintedTextSurface = LongSupplier { paintedSurface.invoke(summary) }
        }
        bind(initial, theme)
    }

    fun bind(next: ToggleState, theme: ControlTheme) {
        if (closed) return
        title.setText(next.label)
        title.setTypography(theme.appearance.typography[titleRole], theme.density, theme.fontResolver)
        title.animateTextColor(labelColor(next, theme))
        summary.visibility = if (next.description == null) View.GONE else View.VISIBLE
        summary.setText(next.description.orEmpty())
        summary.setTypography(theme.appearance.typography[summaryRole], theme.density, theme.fontResolver)
        summary.animateTextColor(theme.colors.muted)
        row.bindToggle(next.checked, next.enabled, theme.density.density, theme.colors.ink, theme.colors.switchPalette)
    }

    private fun labelColor(state: ToggleState, theme: ControlTheme) =
        if (!state.enabled && muteDisabledLabel) theme.colors.muted else theme.colors.ink

    override fun close() {
        if (closed) return
        closed = true
        row.close()
    }
}
