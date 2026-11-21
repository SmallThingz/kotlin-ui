package smallthingz.ui.components

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import smallthingz.ui.input.NativeAndroidInteropHost
import smallthingz.ui.input.NativeAndroidInteropRequests
import smallthingz.ui.text.NativeTypographyRole
import kotlin.math.max
import kotlin.math.roundToInt

/** Lays out the graph, controls and the optional error in their source traversal order. */
open class NativeCalibrationBodyLayout(
    context: Context,
    private var environment: NativeSettingsVisualEnvironment,
    headingText: String,
    hintText: String,
) : ViewGroup(context), NativeAndroidInteropHost {
    override val nativeInteropRequests = NativeAndroidInteropRequests()
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        nativeInteropRequests.request(disallowIntercept)
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    private val heading = environment.paragraph(context, headingText, NativeTypographyRole.TITLE_SMALL)
    private val hint = environment.paragraph(context, hintText, NativeTypographyRole.BODY_SMALL, environment.palette.muted)
    private val error = environment.paragraph(context, "", NativeTypographyRole.BODY_SMALL, environment.palette.error)
    private lateinit var curve: View
    private lateinit var buttonRow: CalibrationButtonRow

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        ViewCompat.setAccessibilityHeading(heading, true)
        ViewCompat.setAccessibilityLiveRegion(error, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)
    }

    protected fun installControls(curve: View, calibrate: View, fit: View) {
        this.curve = curve
        buttonRow = CalibrationButtonRow(context, calibrate, fit)
        addView(heading)
        addView(hint)
        addView(curve)
        addView(buttonRow)
        addView(error)
    }

    protected fun bindContent(next: NativeSettingsVisualEnvironment, errorText: String?) {
        environment = next
        next.bindParagraphStyle(heading, NativeTypographyRole.TITLE_SMALL)
        next.bindParagraphStyle(hint, NativeTypographyRole.BODY_SMALL, next.palette.muted)
        next.bindParagraphStyle(error, NativeTypographyRole.BODY_SMALL, next.palette.error)
        error.setText(errorText.orEmpty())
        error.visibility = if (!errorText.isNullOrBlank()) VISIBLE else GONE
        requestLayout()
    }

    override fun onMeasure(w:Int,h:Int) {
        val width=MeasureSpec.getSize(w)
        val inner=(width-2*dp(16)).coerceAtLeast(0)
        curve.measure(exact(inner),unspecified())
        buttonRow.measure(exact(inner),unspecified())
        heading.measure(atMost(inner),unspecified())
        hint.measure(atMost(inner),unspecified())
        val errorHeight=if(error.visibility==VISIBLE) {
            error.measure(atMost(inner),unspecified())
            dp(8)+error.measuredHeight
        } else 0
        val height=dp(12)+3*dp(8)+heading.measuredHeight+hint.measuredHeight+curve.measuredHeight+buttonRow.measuredHeight+errorHeight
        setMeasuredDimension(resolveSize(width,w),resolveSize(height,h))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        var y=dp(12)
        fun place(child:View) {
            val x=if(layoutDirection==LAYOUT_DIRECTION_RTL)width-dp(16)-child.measuredWidth else dp(16)
            child.layout(x,y,x+child.measuredWidth,y+child.measuredHeight)
        }
        place(heading);y+=heading.measuredHeight+dp(8)
        place(hint);y+=hint.measuredHeight+dp(8)
        place(curve);y+=curve.measuredHeight+dp(8)
        place(buttonRow)
        if(error.visibility==VISIBLE) {y+=buttonRow.measuredHeight+dp(8);place(error)}
    }
    private fun dp(value: Int) = (value * environment.textDensity.density).roundToInt()
}

private class CalibrationButtonRow(
    context:Context,
    private val calibrate:View,
    private val fit:View,
):ViewGroup(context),smallthingz.ui.input.NativeAndroidInteropHost {
    override val nativeInteropRequests=smallthingz.ui.input.NativeAndroidInteropRequests()
    override fun requestDisallowInterceptTouchEvent(disallowIntercept:Boolean) {
        nativeInteropRequests.request(disallowIntercept)
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }
    init {importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;addView(calibrate);addView(fit)}
    override fun onMeasure(w:Int,h:Int) {
        val width=MeasureSpec.getSize(w)
        fit.measure(atMost(width/3),unspecified())
        val trailing=fit.measuredWidth
        calibrate.measure(exact((width-trailing).coerceAtLeast(0)),unspecified())
        setMeasuredDimension(resolveSize(width,w),resolveSize(max(calibrate.measuredHeight,fit.measuredHeight),h))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        val rtl=layoutDirection==LAYOUT_DIRECTION_RTL
        val first=if(rtl)width-calibrate.measuredWidth else 0
        val top=(height-calibrate.measuredHeight)/2
        calibrate.layout(first,top,first+calibrate.measuredWidth,top+calibrate.measuredHeight)
        val fx=if(rtl)0 else width-fit.measuredWidth
        fit.layout(fx,0,fx+fit.measuredWidth,fit.measuredHeight)
    }
}
