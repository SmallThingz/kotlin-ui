package smallthingz.ui.reverb.settings

import android.content.Context
import android.view.View
import smallthingz.ui.components.NativeProgressActionView
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.reverb.ReverbIcon
import smallthingz.ui.reverb.ReverbIconDrawable

/** Shared Reverb Settings text action with optional calibration progress. */
class ReverbSettingsActionView(
    context: Context,
    environment: ReverbEnvironment,
    inputRoot: () -> View?,
    leadingIcon: ReverbIcon? = null,
    stableLabels: List<String> = emptyList(),
    progressSurface: Boolean = false,
    samplingLabel: () -> String = { "" },
    action: () -> Unit,
) : NativeProgressActionView(context, environment, inputRoot,
    leadingIcon?.let { icon -> { density, color -> ReverbIconDrawable(icon, density, color) } },
    stableLabels, progressSurface, samplingLabel, action)
