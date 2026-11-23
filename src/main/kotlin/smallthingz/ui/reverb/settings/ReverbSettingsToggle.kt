package smallthingz.ui.reverb.settings

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import smallthingz.ui.common.ToggleRow
import smallthingz.ui.common.ToggleState
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.reverb.ReverbTextSurface
import smallthingz.ui.reverb.ReverbTheme
import kotlin.math.roundToInt

enum class ReverbSettingsToggleRole { AUTO_START, OUTPUT_COMPRESSION, SAVE_NOTIFICATION, SILENCE_DETECTION }

class ReverbSettingsToggle(
    context: Context,
    theme: ReverbTheme,
    initial: ToggleState,
    role: ReverbSettingsToggleRole,
    inputRoot: () -> View?,
    onChanged: (Boolean) -> Unit,
) : AutoCloseable {
    private val row = ToggleRow(context, initial, theme.controls, inputRoot,
        muteDisabledLabel = when (role) {
            ReverbSettingsToggleRole.AUTO_START, ReverbSettingsToggleRole.OUTPUT_COMPRESSION -> true
            ReverbSettingsToggleRole.SAVE_NOTIFICATION, ReverbSettingsToggleRole.SILENCE_DETECTION -> false
        }, paintedSurface = ReverbTextSurface::sample, onChanged = onChanged)
    val view: View get() = row.view

    fun bind(next: ToggleState, theme: ReverbTheme) = row.bind(next, theme.controls)
    override fun close() = row.close()
}

/** Auto-start's standalone Settings surface and its row. */
class ReverbAutoStartSettings(
    context: Context,
    theme: ReverbTheme,
    initial: ToggleState,
    inputRoot: () -> View?,
    onChanged: (Boolean) -> Unit,
) : AutoCloseable {
    private val recipe = ReverbDesign.appearance.groupSurfaces.settingsGroup
    private val group = recipe.create(context, ReverbTextSurface::invalidate)
    private val toggle = ReverbSettingsToggle(context, theme, initial, ReverbSettingsToggleRole.AUTO_START,
        inputRoot, onChanged)
    val view: View = FrameLayout(context).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(group, FrameLayout.LayoutParams(-1, -2))
    }

    init {
        group.addRow(toggle.view, NativeGroupLayout.LayoutParams(-1, -2))
        bind(initial, theme)
    }

    fun bind(state: ToggleState, theme: ReverbTheme) {
        val horizontal = (16 * theme.density.density).roundToInt()
        val top = (12 * theme.density.density).roundToInt()
        val totalVertical = (24 * theme.density.density).roundToInt()
        view.setPadding(horizontal, top, horizontal, totalVertical - top)
        recipe.bind(group, theme.palette)
        toggle.bind(state, theme)
    }

    override fun close() = toggle.close()
}
