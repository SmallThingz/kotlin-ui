package smallthingz.ui.common

import android.content.Context
import android.view.View
import smallthingz.ui.components.NativeMaterialSwitchView
import smallthingz.ui.components.NativeTextSurface
import smallthingz.ui.components.NativeToggleRow
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.style.TextOverflow
import smallthingz.ui.text.view.ParagraphView
import java.util.function.LongSupplier

/** Current copy and checked state for one toggle row. */
data class ToggleState(val label: String, val checked: Boolean, val enabled: Boolean, val description: String? = null)

/** An actionable title, optional description, and switch with one accessible checked state. */
class ToggleRow(
    context: Context,
    initial: ToggleState,
    theme: ControlTheme,
    inputRoot: (() -> View?)?,
    private val muteDisabledLabel: Boolean,
    private val paintedSurface: ((View) -> Long)?,
    onChanged: (Boolean) -> Unit,
) : AutoCloseable {
    /**
     * Uses current painted underlay only inside a NativeTextSurfaceScope. Elsewhere the
     * sampler returns unknown and retains the theme's original ink. The full constructor
     * allows explicit sampler ownership (or null) and disabled-label policy overrides.
     */
    constructor(context: Context, initial: ToggleState, theme: ControlTheme, inputRoot: (() -> View?)? = null,
        onChanged: (Boolean) -> Unit,
    ) : this(context, initial, theme, inputRoot, true, NativeTextSurface::sample, onChanged)

    private val titleRole = NativeTypographyRole.BODY_LARGE
    private val summaryRole = NativeTypographyRole.BODY_SMALL
    private val title = ParagraphView(context, theme.appearance.typography[titleRole], theme.density, theme.fontResolver,
        labelColor(initial, theme))
    private val summary = ParagraphView(context, theme.appearance.typography[summaryRole], theme.density, theme.fontResolver,
        theme.colors.muted)
    private val switch = NativeMaterialSwitchView(context, theme.colors.switchPalette, initial.checked, initial.enabled)
    private val row = NativeToggleRow(context, title, summary, switch, inputRoot, onChanged)
    val view: View get() = row
    private var closed = false

    init {
        if (paintedSurface != null) {
            title.paintedTextSurface = LongSupplier { paintedSurface.invoke(title) }
            summary.paintedTextSurface = LongSupplier { paintedSurface.invoke(summary) }
        }
        bind(initial, theme)
    }

    /** Decorative caller-owned glyph, laid out at 24dp before the text in either direction. */
    fun setLeadingIcon(icon: View?) { if (!closed) row.setLeadingIcon(icon) }

    fun setTrackNoiseIntensity(intensity: Float) { if (!closed) switch.setTrackNoiseIntensity(intensity) }

    fun setTrackOutline(widthDp: Float, color: Int) { if (!closed) switch.setTrackOutline(widthDp, color) }

    /** Visual summary bound only; its backing text is retained for accessibility/disclosure. */
    fun bindDetails(maxLines: Int, actionLabel: String, onDetails: (() -> Boolean)?) {
        require(maxLines > 0)
        require(onDetails == null || actionLabel.isNotBlank())
        if (closed) return
        summary.setTextLayout(maxLines = maxLines,
            overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis)
        row.bindRowLongClick(actionLabel, onDetails)
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
        switch.bindSurfacePreset(theme.colors.surfacePreset)
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
