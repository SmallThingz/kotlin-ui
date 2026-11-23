package smallthingz.ui.reverb

import android.content.Context
import smallthingz.ui.common.NativeSpringFineSeekState
import smallthingz.ui.common.SpringFineSeekBinding
import smallthingz.ui.components.NativeSpringFineSeekEnvironment
import smallthingz.ui.components.NativeSpringFineSeekStyle
import smallthingz.ui.components.NativeSpringFineSeekView

typealias ReverbSpringFineSeekView = NativeSpringFineSeekView<SpringFineSeekBinding>
typealias SpringFineSeekEnvironment = NativeSpringFineSeekEnvironment

fun createReverbSpringFineSeekView(
    context: Context,
    environment: SpringFineSeekEnvironment,
    style: NativeSpringFineSeekStyle = NativeSpringFineSeekStyle.Default,
    playbackLabel: (playing: Boolean) -> String,
): ReverbSpringFineSeekView = NativeSpringFineSeekView(
    context, environment, style,
    playbackLabel,
    ::NativeSpringFineSeekState,
)
