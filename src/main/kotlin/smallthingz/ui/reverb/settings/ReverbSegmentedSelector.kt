package smallthingz.ui.reverb.settings

import android.content.Context
import android.view.View
import smallthingz.ui.common.SegmentOption
import smallthingz.ui.common.SegmentedSelector
import smallthingz.ui.components.NativeThemeMode
import smallthingz.ui.components.NativeUiAppearance
import smallthingz.ui.reverb.ReverbTextSurface
import smallthingz.ui.reverb.ReverbTheme

/** Reverb's shared Settings capsule for named modes. */
class ReverbSegmentedSelector<T : Any> internal constructor(
    context: Context,
    theme: ReverbTheme,
    options: List<SegmentOption<T>>,
    selected: T,
    appearance: NativeUiAppearance,
    inputAllowed: () -> Boolean,
    onSelectionRequest: (T) -> Boolean,
) : AutoCloseable {
    constructor(context: Context, theme: ReverbTheme, options: List<SegmentOption<T>>, selected: T,
        inputAllowed: () -> Boolean, onSelectionRequest: (T) -> Boolean,
    ) : this(context, theme, options, selected, theme.appearance, inputAllowed, onSelectionRequest)

    private val control = SegmentedSelector(appearance.settingsSelectors.createSegmentedControl(context,
        theme.palette.settingsControls, theme.density.density, inputAllowed, ReverbTextSurface::invalidate),
        options, selected, theme.controls, onSelectionRequest)
    val view: View get() = control.view

    fun bind(options: List<SegmentOption<T>>, selected: T, theme: ReverbTheme) =
        control.bind(options, selected, theme.controls)

    fun layoutWithTitle(title: View) = control.layoutWithTitle(title)
    override fun close() = control.close()
}

/** The same capsule with Theme's localized title, modes and glyphs. */
class ReverbThemeSelector(
    context: Context,
    theme: ReverbTheme,
    selected: NativeThemeMode,
    inputAllowed: () -> Boolean,
    onSelectionRequest: (NativeThemeMode) -> Boolean,
) : AutoCloseable {
    private val row = theme.appearance.settingsSelectors.createThemeSelector(context,
        theme.palette.settingsControls, selected, theme.density, theme.fontResolver, inputAllowed,
        ReverbTextSurface::invalidate, ReverbTextSurface::sample, onSelectionRequest)
    val view: View get() = row

    fun bind(selected: NativeThemeMode, theme: ReverbTheme) =
        row.bind(selected, theme.palette.settingsControls, theme.density, theme.fontResolver)

    /** Bind the owner selection and the enabled state of both the host and its choices. */
    fun bind(selected: NativeThemeMode, enabled: Boolean, theme: ReverbTheme) {
        bind(selected, theme)
        row.bindEnabled(enabled)
    }

    override fun close() = row.close()
}
