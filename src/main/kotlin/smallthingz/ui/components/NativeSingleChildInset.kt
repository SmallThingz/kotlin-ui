package smallthingz.ui.components

import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlin.math.roundToInt

/** A concrete padding modifier for a single section leaf; edges round independently as upstream. */
class NativeSingleChildInset(
    context:Context,
    val content:View,
    private var startDp:Int,
    private var topDp:Int,
    private var endDp:Int,
    private var bottomDp:Int,
    private var environment:NativeSettingsVisualEnvironment,
    private val fillContentWidth:Boolean=true,
):ViewGroup(context) {
    init {importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;addView(content)}
    fun setEnvironment(next:NativeSettingsVisualEnvironment) {environment=next;requestLayout()}
    internal fun setEnvironment(next: NativeSettingsVisualEnvironment, uniformInsetDp: Int) {
        startDp = uniformInsetDp
        topDp = uniformInsetDp
        endDp = uniformInsetDp
        bottomDp = uniformInsetDp
        setEnvironment(next)
    }
    override fun onMeasure(w:Int,h:Int) {
        val horizontal=dp(startDp)+dp(endDp);val vertical=dp(topDp)+dp(bottomDp)
        val childW=getChildMeasureSpec(w,horizontal,if(fillContentWidth)LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT)
        val childH=getChildMeasureSpec(h,vertical,LayoutParams.WRAP_CONTENT)
        content.measure(childW,childH)
        val desiredWidth = (content.measuredWidth + horizontal).coerceIn(0, MEASURED_SIZE_MASK)
        val desiredHeight = (content.measuredHeight + vertical).coerceIn(0, MEASURED_SIZE_MASK)
        setMeasuredDimension(resolveSize(desiredWidth, w), resolveSize(desiredHeight, h))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {placeLogical(content,dp(startDp),dp(topDp))}
    private fun dp(v: Int) = (v * environment.textDensity.density).roundToInt()
        .coerceIn(-MEASURED_SIZE_MASK, MEASURED_SIZE_MASK)
}

private fun ViewGroup.placeLogical(child: View, start: Int, top: Int) {
    val x = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) width - start - child.measuredWidth else start
    child.layout(x, top, x + child.measuredWidth, top + child.measuredHeight)
}
