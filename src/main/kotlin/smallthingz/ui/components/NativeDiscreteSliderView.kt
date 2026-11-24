/*
 * Geometry, colors, track drawing and keyboard behavior from AndroidX Material3 1.4.0 Slider.kt.
 * Copyright 2022 The Android Open Source Project. Apache-2.0:
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package smallthingz.ui.components

import smallthingz.ui.animation.view.ViewColorTransition

import smallthingz.ui.graphics.bindGrainLayer
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import smallthingz.ui.geometry.Offset
import smallthingz.ui.graphics.Color
import smallthingz.ui.graphics.compositeOver
import smallthingz.ui.graphics.toArgb
import smallthingz.ui.input.pointer.PointerType
import smallthingz.ui.unit.Velocity
import smallthingz.ui.util.lerp
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.ViewCompat
import smallthingz.ui.input.phase.NativeDragAxis
import smallthingz.ui.input.phase.NativeDragPhaseNode
import smallthingz.ui.input.phase.NativeDragPointerChange
import smallthingz.ui.input.phase.NativeDragPointerEvent
import smallthingz.ui.input.phase.NativeDragPointerRouter
import smallthingz.ui.input.phase.NativeDragSink
import smallthingz.ui.input.phase.NativePointerPass
import smallthingz.ui.input.phase.NativeViewPhasePointerNode
import smallthingz.ui.text.NativeTypographyRole
import kotlin.math.max
import kotlin.math.roundToInt

/** Native discrete slider geometry shared by drawing, hit testing, and accessibility. */
data class NativeDiscreteSliderStyle(
    val thumbWidthDp: Float = 4f,
    val grabbedThumbWidthDp: Float = 2f,
    val thumbHeightDp: Float = 44f,
    val trackHeightDp: Float = 16f,
    val horizontalSemanticsPaddingDp: Float = 10f,
    val insideCornerDp: Float = 2f,
    val thumbGapDp: Float = 6f,
    val tickRadiusDp: Float = 2f,
    val inactiveTrackShade: Float = .18f,
    val disabledThumbAlpha: Float = .38f,
    val disabledTrackAlpha: Float = .12f,
    val labelRowHorizontalInsetDp: Float = 16f,
    val labelRowVerticalInsetDp: Float = 12f,
    val labelRole: NativeTypographyRole = NativeTypographyRole.BODY_SMALL,
) {
    companion object {
        val Default = NativeDiscreteSliderStyle()
    }
}

/** A controlled 0..maximum slider. Register [pointerNodes] on [pointerView] in the host root. */
class NativeDiscreteSliderView(
    context: Context,
    initialValue: Int,
    maximum: Int,
    initialEnabled: Boolean,
    density: Float,
    style: NativeDiscreteSliderStyle,
    palette: NativeColorScheme,
    label: CharSequence,
    parentInterested: (NativeDragPointerChange) -> Boolean,
    private var onValueChange: ((Int) -> Unit)?,
) : ViewGroup(context), AutoCloseable {
    private var density = density
    private val body = SliderBody(context, initialValue, maximum, initialEnabled, density, style, palette,
        label, parentInterested) { value -> onValueChange?.invoke(value) }
    val pointerView:View get()=body
    /** Source order is tap modifier then draggable; Main pass dispatches the draggable first. */
    val pointerNodes:List<NativeViewPhasePointerNode> get()=body.nodes
    init {
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren=false;clipToPadding=false
        addView(body)
    }
    fun bind(value: Int, enabled: Boolean, density: Float, style: NativeDiscreteSliderStyle, palette: NativeColorScheme) {
        this.density = density
        body.bind(value, enabled, density, style, palette)
        requestLayout()
    }
    override fun onMeasure(w:Int,h:Int) {
        val width=MeasureSpec.getSize(w)
        body.measure(exact(width),unspecified())
        val minimumTouch = (SLIDER_MINIMUM_TOUCH_DP * density).roundToInt()
        setMeasuredDimension(resolveSize(max(minimumTouch, body.measuredWidth),w),
            resolveSize(max(minimumTouch, body.measuredHeight),h))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        val x=((width-body.measuredWidth)/2f).roundToInt()
        val y=((height-body.measuredHeight)/2f).roundToInt()
        body.layout(x,y,x+body.measuredWidth,y+body.measuredHeight)
    }
    override fun close() {onValueChange=null;body.close()}
}

/** Places endpoint labels below one discrete slider, in logical reading order. */
class NativeDiscreteSliderLabels(
    context: Context,
    private val slider: NativeDiscreteSliderView,
    private val start: View,
    private val end: View,
    private var density: Float,
    private var style: NativeDiscreteSliderStyle,
) : ViewGroup(context) {
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(slider)
        addView(start)
        addView(end)
    }

    fun setAppearance(density: Float, style: NativeDiscreteSliderStyle) {
        this.density = density
        this.style = style
        requestLayout()
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val inner = (width - 2 * px(style.labelRowHorizontalInsetDp)).coerceAtLeast(0)
        slider.measure(exact(inner), unspecified())
        start.measure(atMost(inner), unspecified())
        end.measure(atMost((inner - start.measuredWidth).coerceAtLeast(0)), unspecified())
        val labels = max(start.measuredHeight, end.measuredHeight)
        setMeasuredDimension(resolveSize(width, w),
            resolveSize(2 * px(style.labelRowVerticalInsetDp) + slider.measuredHeight + labels, h))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val inset = px(style.labelRowHorizontalInsetDp)
        val top = px(style.labelRowVerticalInsetDp)
        val y = top + slider.measuredHeight
        slider.layout(inset, top, width - inset, y)
        val left = if (layoutDirection == LAYOUT_DIRECTION_RTL) end else start
        val right = if (left === start) end else start
        left.layout(inset, y, inset + left.measuredWidth, y + left.measuredHeight)
        right.layout(width - inset - right.measuredWidth, y, width - inset, y + right.measuredHeight)
    }

    private fun px(dp: Float) = (dp * density).roundToInt()
}

private class SliderBody(
    context:Context,
    initialValue: Int,
    private val maximum: Int,
    enabled: Boolean,
    private var density: Float,
    private var style: NativeDiscreteSliderStyle,
    private var palette: NativeColorScheme,
    label: CharSequence,
    parentInterested: (NativeDragPointerChange) -> Boolean,
    private var changed: ((Int) -> Unit)?,
) : View(context), AutoCloseable {
    private val model = DiscreteSliderModel(initialValue, 0..maximum)
    private val colorMotion by lazy {ViewColorTransition(this,targetColors(isEnabled),{invalidate()})}
    private val configuration=ViewConfiguration.get(context)
    private var dragging=false
    private var grain = bindGrainLayer(null, palette.surfacePreset?.foregroundGrain ?: 0f)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val path=Path()
    private val radii=FloatArray(8)
    private var geometry = DiscreteSliderGeometry.measure(0, density, model.fraction, false, false, style)
    private val tap = SliderTapNode(this, model, { isEnabled }, { SLIDER_MINIMUM_TOUCH_DP * density }, ::requestValue)
    private val drag=NativeDragPointerRouter(NativeDragAxis.HORIZONTAL,
        {configuration.scaledTouchSlop.toFloat()},{configuration.scaledMaximumFlingVelocity.toFloat()},
        {dragging},object:NativeDragSink {
            override fun start(position:Offset) {setDragging(true)}
            override fun delta(physicalDelta:Offset) {model.dragByPhysical(physicalDelta.x)?.let(::requestValue)}
            override fun stop(physicalVelocity:Velocity) {setDragging(false)}
            override fun cancel() {setDragging(false)}
        },parentInterested)
    val nodes:List<NativeViewPhasePointerNode> = listOf(tap,NativeDragPhaseNode(this,drag))
    init {
        // M3 Slider keeps keyboard focus but draws only its source thumb and track.
        defaultFocusHighlightEnabled=false
        isEnabled=enabled;isFocusable=enabled
        isClickable=false
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setScreenReaderFocusable(this,true)
        contentDescription=label
        if(Build.VERSION.SDK_INT>=29)isForceDarkAllowed=false
        drag.enabled=enabled
    }
    fun bind(value: Int, enabled: Boolean, density: Float, style: NativeDiscreteSliderStyle, palette: NativeColorScheme) {
        this.density = density
        this.style = style
        this.palette = palette
        grain = bindGrainLayer(grain, palette.surfacePreset?.foregroundGrain ?: 0f)
        model.bind(value)
        drag.enabled=enabled
        if(!enabled)tap.cancel()
        isEnabled=enabled;isFocusable=enabled
        colorMotion.retarget(*targetColors(enabled))
        requestLayout();invalidate()
    }
    private fun targetColors(enabled:Boolean):IntArray {
        val secondary=if(enabled)palette.secondaryContainer else Color(palette.onSurface).copy(alpha=style.disabledTrackAlpha).toArgb()
        return intArrayOf(
            if(enabled)palette.primary else Color(palette.onSurface).copy(alpha=style.disabledThumbAlpha).toArgb(),
            if(enabled)Color.Black.copy(alpha=style.inactiveTrackShade).compositeOver(Color(secondary)).toArgb() else secondary,
            if(enabled)palette.primary else Color(palette.onSurface).copy(alpha=style.disabledThumbAlpha).compositeOver(Color(palette.surface)).toArgb(),
            secondary)
    }
    private fun requestValue(value:Float) {
        val next=value.roundToInt().coerceIn(0,maximum)
        // The owner accepts each request by binding its new value.
        if(next!=model.value.roundToInt())changed?.invoke(next)
    }
    private fun setDragging(value:Boolean) {
        if(dragging==value)return
        dragging=value;requestLayout();invalidate()
    }
    override fun onMeasure(w:Int,h:Int) {
        val width=MeasureSpec.getSize(w)
        geometry=DiscreteSliderGeometry.measure(width,density,model.fraction,
            dragging,layoutDirection==LAYOUT_DIRECTION_RTL,style)
        model.width=width;model.thumbWidth=geometry.thumbWidth;model.rtl=layoutDirection==LAYOUT_DIRECTION_RTL
        setMeasuredDimension(resolveSize(width,w),resolveSize(max(geometry.thumbHeight,geometry.trackHeight),h))
    }
    override fun onRtlPropertiesChanged(layoutDirection:Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        model.rtl=layoutDirection==LAYOUT_DIRECTION_RTL;requestLayout();invalidate()
    }
    override fun onDraw(canvas:Canvas) {
        val g=geometry
        val primary=colorMotion.color(0)
        val secondary=colorMotion.color(1)
        val thumbColor=colorMotion.color(2)
        val save=canvas.save()
        try {
            canvas.translate(g.trackX.toFloat(),(g.trackY-g.visualTop).toFloat())
            drawTrack(canvas,g,primary,secondary,colorMotion.color(3))
        }finally{canvas.restoreToCount(save)}
        paint.color=thumbColor
        val x=g.thumbX.toFloat();val y=(g.thumbY-g.visualTop).toFloat()
        val radius=minOf(g.thumbWidth,g.thumbHeight)/2f
        canvas.drawRoundRect(x,y,x+g.thumbWidth,y+g.thumbHeight,radius,radius,paint)
        grain?.draw(canvas, x, y, x+g.thumbWidth, y+g.thumbHeight, radius, paint.alpha)
    }
    private fun drawTrack(canvas:Canvas,g:DiscreteSliderGeometry,active:Int,inactive:Int,activeTick:Int) {
        val width=g.trackWidth.toFloat()
        val height=g.trackHeight.toFloat()
        // Keep source px→dp→px round trips; do not algebraically remove Float rounding.
        val corner=(height/2f/density)*density
        val inside=style.insideCornerDp*density
        val thumbPixels=(g.thumbWidth/density)*density
        val gap=thumbPixels/2f+style.thumbGapDp*density
        val fraction=model.fraction
        val end=if(fraction==0f||fraction==1f)width*fraction else (width-corner*2)*fraction+corner
        val rtl=layoutDirection==LAYOUT_DIRECTION_RTL
        if(end<width-gap-corner) {
            val start=end+gap
            shape(canvas,if(rtl)0f else start,0f,width-start,height,
                if(rtl)corner else inside,if(rtl)inside else corner,inactive)
            circle(canvas,if(rtl)corner else width-corner,height/2f,style.tickRadiusDp*density,active)
        }
        val activeEnd=end-gap
        val startCorner=if(rtl)inside else corner
        val endCorner=if(rtl)corner else inside
        if(activeEnd>startCorner)shape(canvas,if(rtl)width-activeEnd else 0f,0f,activeEnd,height,startCorner,endCorner,active)
        // Last tick is represented by the stop indicator. Remaining tick centers use float lerp.
        for(index in 0 until maximum) {
            val center=lerp(corner,width-corner,index/maximum.toFloat())
            if(center>=end-gap&&center<=end+gap)continue
            circle(canvas,if(rtl)width-center else center,height/2f,style.tickRadiusDp*density,
                if(center>=0f&&center<=activeEnd)activeTick else active)
        }
    }
    private fun shape(canvas:Canvas,x:Float,y:Float,width:Float,height:Float,left:Float,right:Float,color:Int) {
        radii[0]=left;radii[1]=left;radii[2]=right;radii[3]=right
        radii[4]=right;radii[5]=right;radii[6]=left;radii[7]=left
        path.rewind();path.addRoundRect(x,y,x+width,y+height,radii,Path.Direction.CCW)
        paint.color=color;canvas.drawPath(path,paint)
        grain?.draw(canvas, path, paint.alpha)
    }
    private fun circle(canvas:Canvas,x:Float,y:Float,radius:Float,color:Int) {
        paint.color=color;canvas.drawCircle(x,y,radius,paint)
    }
    override fun onKeyDown(keyCode:Int,event:KeyEvent):Boolean {
        val command=sliderKey(keyCode)?:return super.onKeyDown(keyCode,event)
        if(!isEnabled||changed==null)return false
        requestValue(model.key(command));return true
    }
    override fun onKeyUp(keyCode:Int,event:KeyEvent):Boolean {
        if(sliderKey(keyCode)==null)return super.onKeyUp(keyCode,event)
        return isEnabled&&changed!=null // Released onValueChangeFinished is null.
    }
    override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className=android.widget.SeekBar::class.java.name
        info.rangeInfo=AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,0f,maximum.toFloat(),model.value)
        AccessibilityNodeInfoCompat.wrap(info).stateDescription="${(model.value*100).roundToInt()/100f}"
        info.isClickable=false;info.isCheckable=false
        if(isEnabled) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
            if(model.value<maximum)info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            if(model.value>0f)info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }
        // Source IncreaseHorizontalSemanticsBounds:10dp per horizontal edge, not vertical padding.
        val bounds=Rect();info.getBoundsInScreen(bounds)
        val pad=(style.horizontalSemanticsPaddingDp*density).roundToInt()
        bounds.left-=pad;bounds.right+=pad;info.setBoundsInScreen(bounds)
        info.getBoundsInParent(bounds)
        bounds.left-=pad;bounds.right+=pad;info.setBoundsInParent(bounds)
    }
    override fun performAccessibilityAction(action:Int,args:Bundle?):Boolean {
        val next=when(action) {
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id->{
                if(!isEnabled||args?.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)!=true)return false
                val requested=args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)
                if(!requested.isFinite())return false
                model.semanticProgress(requested)
            }
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD->{if(!isEnabled)return false;model.semanticProgress(model.value+1)}
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD->{if(!isEnabled)return false;model.semanticProgress(model.value-1)}
            else->return super.performAccessibilityAction(action,args)
        }?:return false
        requestValue(next);return true
    }
    override fun onDetachedFromWindow() {tap.cancel();drag.reset();super.onDetachedFromWindow()}
    override fun close() {changed=null;tap.cancel();drag.reset();drag.enabled=false;isEnabled=false;isFocusable=false}
    private fun sliderKey(code:Int):DiscreteSliderKey?=when(code) {
        KeyEvent.KEYCODE_DPAD_UP->DiscreteSliderKey.UP
        KeyEvent.KEYCODE_DPAD_DOWN->DiscreteSliderKey.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT->DiscreteSliderKey.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT->DiscreteSliderKey.RIGHT
        KeyEvent.KEYCODE_MOVE_HOME->DiscreteSliderKey.HOME
        KeyEvent.KEYCODE_MOVE_END->DiscreteSliderKey.END
        KeyEvent.KEYCODE_PAGE_UP->DiscreteSliderKey.PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN->DiscreteSliderKey.PAGE_DOWN
        else->null
    }
}

/** detectTapGestures with ONLY onPress/onTap; no click indication, timeout or long-press branch. */
private class SliderTapNode(
    override val view:View,
    private val model:DiscreteSliderModel,
    private val enabled:()->Boolean,
    private val minimumTouchPixels:()->Float,
    private val changed:(Float)->Unit,
):NativeViewPhasePointerNode {
    private var tracking=false
    private var verifyFinal=false
    override fun dispatch(event:NativeDragPointerEvent,pass:NativePointerPass) {
        if(!enabled()) {cancel();return}
        if(pass==NativePointerPass.INITIAL)return
        if(!tracking) {
            // Android awaitFirstDown accepts any mouse button; event.isDown() is primary-only.
            if(pass==NativePointerPass.MAIN&&event.changes.isNotEmpty()&&
                event.changes.all {it.changedToDown&&!it.consumed}) {
                val down=event.changes.first()
                down.consume();tracking=true;verifyFinal=false
                model.press(down.position.x)
            }
            return
        }
        if(pass==NativePointerPass.MAIN) {
            if(event.changes.all {it.changedToUp&&!it.consumed}) {
                event.changes.firstOrNull()?.consume()
                tracking=false;verifyFinal=false;model.tap()?.let(changed)
            } else if(event.changes.any {it.consumed||outside(it)})cancel()
            else verifyFinal=true
        } else if(verifyFinal&&event.changes.any {it.consumed})cancel()
    }
    private fun outside(change:NativeDragPointerChange):Boolean {
        val minimum=minimumTouchPixels()
        val horizontal=if(change.type==PointerType.Touch)max(0f,(minimum-view.width)/2f)else 0f
        val vertical=if(change.type==PointerType.Touch)max(0f,(minimum-view.height)/2f)else 0f
        return change.position.x< -horizontal||change.position.x>view.width+horizontal||
            change.position.y< -vertical||change.position.y>view.height+vertical
    }
    override fun cancel() {tracking=false;verifyFinal=false}
}
