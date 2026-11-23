package smallthingz.ui.reverb.settings

import android.content.Context
import android.view.View
import smallthingz.ui.components.NativeDiscreteSliderLabels
import smallthingz.ui.components.NativeDiscreteSliderView
import smallthingz.ui.components.NativeExpandableSettingsSectionLayout
import smallthingz.ui.input.phase.NativeDragPointerChange
import smallthingz.ui.R
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.components.NativeExpansionContent
import smallthingz.ui.components.NativeExpansionView
import smallthingz.ui.common.ToggleState
import kotlinx.coroutines.CoroutineScope

data class ReverbOutputCompressionState(val enabled:Boolean,val level:Int,val interactionEnabled:Boolean)

/** OutputCompressionSettings31–112, including its Top expansion, source slider and two short labels. */
class ReverbOutputCompressionView(
    context:Context,
    initial:ReverbOutputCompressionState,
    private var environment:ReverbEnvironment,
    inputRoot:()->View?,
    parentScope:CoroutineScope,
    private val parentInterested:(NativeDragPointerChange)->Boolean,
    onEnabledChange:(Boolean)->Unit,
    private var onLevelChange:((Int)->Unit)?,
):NativeExpandableSettingsSectionLayout(context),AutoCloseable {
    private var state=initial
    private val toggle=ReverbSettingsToggle(context,environment.theme,
        ToggleState(context.getString(R.string.output_compression),initial.enabled,initial.interactionEnabled),
        ReverbSettingsToggleRole.OUTPUT_COMPRESSION,inputRoot,onEnabledChange)
    private val expansion=NativeExpansionView(context,initial.enabled,parentScope,alignBottom=false) {
        CompressionBody(context,state,environment,parentInterested) {onLevelChange?.invoke(it)}
    }
    val currentSlider:NativeDiscreteSliderView? get()=(expansion.content as? CompressionBody)?.slider
    init {installExpansion(toggle.view,expansion)}
    fun bind(next:ReverbOutputCompressionState,colors:ReverbEnvironment) {
        state=next;environment=colors
        toggle.bind(ToggleState(context.getString(R.string.output_compression),next.enabled,next.interactionEnabled), colors.theme)
        (expansion.content as? CompressionBody)?.bind(next,colors)
        expansion.visible(next.enabled)
        requestLayout()
    }
    override fun close() {onLevelChange=null;toggle.close();expansion.close()}
}

private class CompressionBody(
    context:Context,
    initial:ReverbOutputCompressionState,
    environment:ReverbEnvironment,
    parentInterested:(NativeDragPointerChange)->Boolean,
    onLevelChange:(Int)->Unit,
):NativeExpansionContent {
    val slider=NativeDiscreteSliderView(context,initial.level,maximum=8,
        initialEnabled=initial.enabled&&initial.interactionEnabled,
        density=environment.textDensity.density,style=environment.appearance.discreteSlider,
        palette=environment.palette,label=context.getString(R.string.compression_level),
        parentInterested=parentInterested,onValueChange=onLevelChange)
    private val faster=environment.paragraph(context,context.getString(R.string.compression_faster),
        environment.appearance.discreteSlider.labelRole,environment.palette.muted)
    private val smaller=environment.paragraph(context,context.getString(R.string.compression_smaller),
        environment.appearance.discreteSlider.labelRole,environment.palette.muted)
    override val view = NativeDiscreteSliderLabels(context, slider, faster, smaller,
        environment.textDensity.density, environment.appearance.discreteSlider)
    fun bind(state:ReverbOutputCompressionState,next:ReverbEnvironment) {
        val style=next.appearance.discreteSlider
        slider.bind(state.level,state.enabled&&state.interactionEnabled,next.textDensity.density,style,next.palette)
        next.bindParagraphStyle(faster,style.labelRole,next.palette.muted)
        next.bindParagraphStyle(smaller,style.labelRole,next.palette.muted)
        view.setAppearance(next.textDensity.density,style)
    }
    override fun close()=slider.close()
}
