package smallthingz.ui.inject

import android.content.Context
import android.view.View
import smallthingz.ui.components.NativeFloatingPanelSurface

/** Full-width top dialog surface with the same theme and window material as Inject sheets. */
class InjectPanelSurface(context: Context, theme: InjectTheme, viewport: View) :
    NativeFloatingPanelSurface(context, theme.density.density, InjectDesign.floatingWindowRecipe, theme.palette) {
    init { setPanelViewport(viewport) }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.AT_MOST)
            MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY) else widthMeasureSpec
        super.onMeasure(width, heightMeasureSpec)
    }
}
