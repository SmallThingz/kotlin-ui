package smallthingz.ui.components

import android.content.Context
import android.view.View
import android.view.ViewGroup

/** Existing toggle row with a clipped reveal directly below it. */
open class NativeExpandableSettingsSectionLayout(context: Context) : ViewGroup(context) {
    private lateinit var toggle: View
    private lateinit var reveal: NativeExpansionView

    protected fun installExpansion(toggle: View, reveal: NativeExpansionView) {
        this.toggle = toggle
        this.reveal = reveal
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(toggle)
        addView(reveal)
    }

    override fun onMeasure(w:Int,h:Int) {
        val width=MeasureSpec.getSize(w)
        toggle.measure(exact(width),unspecified())
        reveal.measure(atMost(width),unspecified())
        setMeasuredDimension(resolveSize(width,w),resolveSize(toggle.measuredHeight+reveal.measuredHeight,h))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        toggle.layout(0,0,width,toggle.measuredHeight)
        val x=if(layoutDirection==LAYOUT_DIRECTION_RTL)width-reveal.measuredWidth else 0
        reveal.layout(x,toggle.measuredHeight,x+reveal.measuredWidth,toggle.measuredHeight+reveal.measuredHeight)
    }
}
