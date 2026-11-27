package smallthingz.ui.reverb

import smallthingz.ui.components.NativeActionSheetContentContext
import smallthingz.ui.components.NativeMergedActionLayout

import android.content.Context
import smallthingz.ui.input.phase.NativeViewPointerHitLayout
import smallthingz.ui.geometry.Size

/** Sheet actions share the same merged surface and narrow-width stacking as inline actions. */
class ReverbSheetActions(context:Context,private val host:NativeActionSheetContentContext,
                                  initialEnvironment:ReverbTheme,
                                  val buttons:List<ReverbSheetButton>):NativeMergedActionLayout(context, buttons, initialEnvironment.palette),AutoCloseable {
    init { if (buttons.size > 1) asActionFooter(density = initialEnvironment.density.density) }
    private var environment=initialEnvironment
    private var closed=false
    private val hitChildren=java.util.Collections.unmodifiableList(buttons.asReversed().map{it.hitLayout})
    private val surfaceHit = host.hit(actionSurface, emptyList(), children = { hitChildren },
        minimumTouchTarget = Size.Zero, clip = { actionSurface.containsVisualPoint(it.x, it.y) })
    private val surfaceHits = listOf(surfaceHit)
    val hitLayout:NativeViewPointerHitLayout=host.hit(this,emptyList(),children={surfaceHits},
        minimumTouchTarget=Size.Zero,
        clip={it.x>=0f && it.y>=0f && it.x<width && it.y<height})
    fun bindEnvironment(next:ReverbTheme) {
        if(environment===next)return
        val densityChanged=environment.density!=next.density
        environment=next
        bindPalette(next.palette)
        buttons.forEach { it.bindEnvironment(next) }
        if(densityChanged) {
            if (isActionFooter) asActionFooter(density = next.density.density)
            requestLayout()
        }
    }
    override fun close() {
        if(closed)return
        closed=true
        var failure:Throwable?=null
        // Each button adds a scoped clip mapping. Retire them in reverse installation order.
        buttons.asReversed().forEach {button->try{button.close()}catch(error:Throwable){
            if(failure==null)failure=error else if(failure!==error)failure!!.addSuppressed(error)
        }}
        failure?.let{throw it}
    }
}
