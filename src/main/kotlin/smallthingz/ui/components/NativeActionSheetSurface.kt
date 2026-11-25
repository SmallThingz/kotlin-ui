package smallthingz.ui.components

import smallthingz.ui.scroll.NativeVerticalViewport

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import smallthingz.ui.dsl.child
import smallthingz.ui.dsl.nativeView
import smallthingz.ui.geometry.Offset
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.animation.view.ViewColorTransition
import smallthingz.ui.input.DEFAULT_MINIMUM_TOUCH_TARGET_DP
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class NativeSheetPredictiveTransform(
    val scaleX: Float, val scaleY: Float, val pivotY: Float,
    val childScaleY: Float, val overflowScaleY: Float, val overflowChildScaleY: Float,
)
internal fun nativeSheetPredictiveTransform(
    width: Float, height: Float, offset: Float, minimumAnchor: Float, density: Float, progress: Float,
    entryEdge: NativeDialogEdge = NativeDialogEdge.BOTTOM,
): NativeSheetPredictiveTransform {
    val x = if (width.isNaN() || width == 0f) 1f else 1f-min(48f*density,width)*progress/width
    val y = if (height.isNaN() || height == 0f) 1f else 1f-min(24f*density,height)*progress/height
    val overflow = if (offset < minimumAnchor) minimumAnchor-offset else 0f
    val up = if (height != 0f && overflow > 0f) (height+overflow)/height else 1f
    val pivotY = if (entryEdge == NativeDialogEdge.TOP) -offset else offset + height
    return NativeSheetPredictiveTransform(x,y,pivotY,if(y!=0f)x/y else 1f,up,1f/up)
}

/** Distinct real graphics-layer owners preserve source transform and clip order. */
internal open class NativeSheetFrame(context: Context) : ViewGroup(context) {
    init {
        clipChildren=false;clipToPadding=false
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        val child=if(childCount>0)getChildAt(0)else null
        child?.measure(widthMeasureSpec,heightMeasureSpec)
        setMeasuredDimension(resolveSize(child?.measuredWidth?:0,widthMeasureSpec),
            resolveSize(child?.measuredHeight?:0,heightMeasureSpec))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        if(childCount>0)getChildAt(0).let {it.layout(0,0,it.measuredWidth,it.measuredHeight)}
    }
}

/** Surface fill is outside the rounded child clip, matching background(shape).clip(shape). */
internal class NativeSheetSurface(
    context:Context,
    private var style:NativeActionSheetStyle,
    handle:NativeSheetDragHandle,
    val appContent:NativeSheetContent,
    contentBackground: android.graphics.drawable.Drawable?,
    private val entryEdge: NativeDialogEdge = NativeDialogEdge.BOTTOM,
) : NativeSheetFrame(context) {
    var sourceChildPresent:((View)->Boolean)?=null
    val predictiveLayer=nativeView(context, ::NativeSheetFrame)
    val overflowLayer=nativeView(context, ::NativeSheetFrame)
    val painted=PaintedSurface(context,style,entryEdge)
    val insets=SheetInsets(context)
    val predictiveContent=nativeView(context, ::NativeSheetFrame)
    val overflowContent=nativeView(context, ::NativeSheetFrame)
    val column=SheetColumn(context,handle,appContent,contentBackground,entryEdge)
    init {
        child(predictiveLayer);predictiveLayer.child(overflowLayer);overflowLayer.child(painted)
        painted.clip.child(insets);insets.child(predictiveContent)
        predictiveContent.child(overflowContent);overflowContent.child(column)
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityPaneTitle=context.getString(smallthingz.ui.R.string.native_sheet_pane_title)
        isScreenReaderFocusable=false
    }
    fun updateStyle(next:NativeActionSheetStyle) {
        if (style == next) return
        style=next;painted.updateStyle(next)
    }
    fun applyMotion(offset:Float,minimumAnchor:Float,progress:Float) {
        val value=nativeSheetPredictiveTransform(width.toFloat(),height.toFloat(),offset,
            minimumAnchor,style.textDensity.density,progress,entryEdge)
        predictiveLayer.pivotX=width*.5f;predictiveLayer.pivotY=value.pivotY
        predictiveLayer.scaleX=value.scaleX;predictiveLayer.scaleY=value.scaleY
        overflowLayer.pivotX=width*.5f
        overflowLayer.pivotY=edgePivotY(overflowLayer)
        overflowLayer.scaleY=value.overflowScaleY
        predictiveContent.pivotX=predictiveContent.width*.5f
        predictiveContent.pivotY=edgePivotY(predictiveContent)
        // Source uses this inner layer's size after content window-insets padding.
        val inner=nativeSheetPredictiveTransform(predictiveContent.width.toFloat(),
            predictiveContent.height.toFloat(),offset,minimumAnchor,style.textDensity.density,progress,entryEdge)
        predictiveContent.scaleY=inner.childScaleY
        overflowContent.pivotX=overflowContent.width*.5f;overflowContent.pivotY=edgePivotY(overflowContent)
        overflowContent.scaleY=inner.overflowChildScaleY
    }
    private fun edgePivotY(view: View): Float =
        if (entryEdge == NativeDialogEdge.TOP) view.height.toFloat() else 0f

    fun containsShape(p:Offset)=painted.containsShape(p)

    override fun addChildrenForAccessibility(outChildren:ArrayList<View>) {
        val start=outChildren.size
        super.addChildrenForAccessibility(outChildren)
        val present=sourceChildPresent?:return
        for(index in outChildren.lastIndex downTo start)if(!present(outChildren[index]))outChildren.removeAt(index)
    }

    internal class PaintedSurface(
        context: Context,
        private var env: NativeActionSheetStyle,
        private val entryEdge: NativeDialogEdge,
    ) : NativeSheetFrame(context), NativeModalPaintedSurface {
        private val fill=Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            color=env.palette.surfaceContainerLow
        }
        private val grain = NoiseDrawable(fill.color, env.recipe.grainSeed).apply {
            setNoiseIntensity(env.recipe.grainIntensity)
        }
        override val paintedTextSurface: Long get() = grain.paintedTextSurface
        private val shape=Path()
        private val colorMotion:ViewColorTransition by lazy {ViewColorTransition(this,intArrayOf(fill.color),{
            val next = colorMotion.color(0)
            if (fill.color != next) {
                fill.color=next;grain.setBackgroundColor(fill.color)
                NativeModalTextSurface.invalidate(this);invalidate()
            }
        })}
        val clip=SheetClip(context)
        private var radius=0f
        init {setWillNotDraw(false);addView(clip);grain.callback=this}
        fun updateStyle(next:NativeActionSheetStyle) {
            val shapeChanged=env.textDensity!=next.textDensity || env.recipe.radiusDp!=next.recipe.radiusDp
            val grainChanged=env.recipe.grainSeed!=next.recipe.grainSeed ||
                env.recipe.grainIntensity!=next.recipe.grainIntensity
            env=next;colorMotion.retarget(next.palette.surfaceContainerLow)
            if (grainChanged) {
                grain.setGrainSeed(next.recipe.grainSeed)
                grain.setNoiseIntensity(next.recipe.grainIntensity)
                NativeModalTextSurface.invalidate(this)
                invalidate()
            }
            if(shapeChanged) { onSizeChanged(width,height,width,height);invalidate() }
        }
        override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
            grain.setBounds(0,0,w,h)
            val raw=env.recipe.radiusDp*env.textDensity.density
            // CornerBasedShape first normalizes each start/end pair against minDimension;
            // the path backend then normalizes the exposed pair if it exceeds the actual width.
            val minimum=min(w,h).toFloat()
            val resolved=if(raw>minimum)raw*(minimum/raw)else raw
            radius=min(resolved,w*.5f)
            shape.rewind()
            val rounds = if (entryEdge == NativeDialogEdge.TOP) {
                floatArrayOf(0f,0f,0f,0f,resolved,resolved,resolved,resolved)
            } else {
                floatArrayOf(resolved,resolved,resolved,resolved,0f,0f,0f,0f)
            }
            shape.addRoundRect(RectF(0f,0f,w.toFloat(),h.toFloat()),rounds,Path.Direction.CW)
            clip.setShape(shape)
        }
        override fun verifyDrawable(who: android.graphics.drawable.Drawable): Boolean =
            who === grain || super.verifyDrawable(who)
        override fun onDraw(canvas:Canvas) {
            canvas.drawPath(shape,fill)
            grain.drawGrain(canvas,shape)
        }
        fun containsShape(p:Offset):Boolean {
            if(p.x<0f||p.y<0f||p.x>=width||p.y>=height)return false
            val edgeDistance = if (entryEdge == NativeDialogEdge.TOP) height-p.y else p.y
            if(edgeDistance>=radius || p.x>=radius&&p.x<width-radius)return true
            val cx=if(p.x<radius)radius else width-radius
            val dx=p.x-cx;val dy=edgeDistance-radius
            return dx*dx+dy*dy<=radius*radius
        }
    }
    internal class SheetClip(context:Context):NativeSheetFrame(context) {
        private val shape=Path()
        private var nativeClip=false
        init {
            outlineProvider=object:ViewOutlineProvider() {
                override fun getOutline(view:View,outline:Outline)=fillOutline(outline)
            }
        }
        fun setShape(value:Path) {
            shape.set(value)
            val outline=Outline();fillOutline(outline)
            nativeClip=outline.canClip();clipToOutline=nativeClip
            invalidateOutline();invalidate()
        }
        @Suppress("DEPRECATION")
        private fun fillOutline(outline:Outline) {
            if(Build.VERSION.SDK_INT>=30)outline.setPath(shape)else outline.setConvexPath(shape)
        }
        override fun dispatchDraw(canvas:Canvas) {
            if(canvas.isHardwareAccelerated&&nativeClip)super.dispatchDraw(canvas)
            else {val save=canvas.save();try {canvas.clipPath(shape);super.dispatchDraw(canvas)}
                finally {canvas.restoreToCount(save)}}
        }
    }
    internal class SheetInsets(context:Context):NativeSheetFrame(context) {
        var contentTop=0;private set
        var contentBottom=0;private set
        fun update(top:Int,bottom:Int):Boolean {
            if(contentTop==top&&contentBottom==bottom)return false
            contentTop=top;contentBottom=bottom;requestLayout();return true
        }
        override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
            val maximum=MeasureSpec.getSize(heightMeasureSpec)
            val constrained=max(0,maximum-contentTop-contentBottom)
            val child=getChildAt(0)
            child.measure(widthMeasureSpec,MeasureSpec.makeMeasureSpec(constrained,MeasureSpec.AT_MOST))
            setMeasuredDimension(child.measuredWidth,resolveSize(child.measuredHeight+contentTop+contentBottom,heightMeasureSpec))
        }
        override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
            getChildAt(0).let {it.layout(0,contentTop,it.measuredWidth,contentTop+it.measuredHeight)}
        }
    }
    internal class SheetColumn(context:Context,val handle:NativeSheetDragHandle,var content:View,
        private val contentBackground: android.graphics.drawable.Drawable?,
        private val entryEdge: NativeDialogEdge,
    ):ViewGroup(context) {
        private val handleAtBottom = entryEdge == NativeDialogEdge.TOP
        init {clipChildren=false;clipToPadding=false;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
            background=contentBackground
            addView(handle);addView(content, if (handleAtBottom) 0 else 1)}
        fun wrapContent(wrapper:NativeVerticalViewport) {
            val index = indexOfChild(content)
            removeView(content)
            wrapper.setContent(content)
            content=wrapper
            addView(wrapper, index)
        }
        override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
            val width=MeasureSpec.getSize(widthMeasureSpec)
            val remaining=MeasureSpec.getSize(heightMeasureSpec)
            handle.measure(sheetSurfaceAtMost(width),sheetSurfaceAtMost(remaining))
            val handleSpace=handle.verticalTouchInsetPx+handle.measuredHeight
            content.measure(sheetSurfaceExact(width),sheetSurfaceAtMost(max(0,remaining-handleSpace)))
            setMeasuredDimension(width,resolveSize(handleSpace+content.measuredHeight,heightMeasureSpec))
        }
        override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
            val x=((width-handle.measuredWidth)*.5f).roundToInt()
            // Mirror the compact handle and its expanded target at the sheet's entry edge.
            val handleTop = if (handleAtBottom) content.measuredHeight else handle.verticalTouchInsetPx
            val contentTop = if (handleAtBottom) 0 else handleTop + handle.measuredHeight
            handle.layout(x,handleTop,x+handle.measuredWidth,handleTop+handle.measuredHeight)
            content.layout(0,contentTop,width,contentTop+content.measuredHeight)
        }
    }
}

/** App column; a caller-owned viewport may measure its complete content without a height cap. */
internal class NativeSheetContent(
    context:Context,
    private var style:NativeActionSheetStyle,
    title:String,
    inputRoot:()->View?,
    private val closeIcon: NativeActionSheetIcon?,
    dismiss:()->Unit,
):ViewGroup(context) {
    private var currentDismiss=dismiss
    val titleView=style.title(context,title)
    val close = closeIcon?.let { icon ->
        NativeStandardIconButtonView(context,style.textDensity.density,style.palette.onSurface,
            icon.view,icon.bindAccessibility,inputRoot)
    }
    private val header=Header(context,titleView,close)
    private var body:View?=null
    private var actions:View?=null
    private var topHeader=0
    private var topBody=0
    private var topActions=0
    private val density get()=style.textDensity.density
    val minimumTouchTargetPx:Float get()=DEFAULT_MINIMUM_TOUCH_TARGET_DP * density
    init {
        clipChildren=false;clipToPadding=false;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(header)
        bindClose(style,dismiss)
        NativeModalTextSurface.bindTree(titleView)
    }
    fun setContent(body:View,actions:View) {
        this.body?.let(::removeView);this.actions?.let(::removeView)
        this.body=body;this.actions=actions;addView(body);addView(actions)
        NativeModalTextSurface.bindTree(this);requestLayout()
    }
    fun bind(title:String,dismiss:()->Unit) {
        currentDismiss=dismiss
        titleView.setText(title)
        bindClose(style,dismiss)
        NativeModalTextSurface.bindTree(titleView)
    }
    fun updateStyle(next:NativeActionSheetStyle) {
        if(style===next)return
        val layoutChanged=style.textDensity!=next.textDensity || style.recipe!=next.recipe
        style=next
        next.bindTitle(titleView)
        bindClose(next,currentDismiss)
        if(layoutChanged)requestLayout()
    }
    private fun bindClose(value:NativeActionSheetStyle,dismiss:()->Unit) {
        closeIcon?.setColor(value.palette.onSurfaceVariant)
        close?.bindAction(value.textDensity.density,context.getString(smallthingz.ui.R.string.close_sheet),
            true,value.palette.onSurface,dismiss)
    }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        val width=MeasureSpec.getSize(widthMeasureSpec)
        val pad=(style.recipe.horizontalPaddingDp*density).roundToInt()
        val inner=max(0,width-pad*2)
        val bounded=MeasureSpec.getMode(heightMeasureSpec)!=MeasureSpec.UNSPECIFIED
        val maximum=if(bounded)MeasureSpec.getSize(heightMeasureSpec)else Int.MAX_VALUE
        val bottom = if ((actions as? NativeMergedActionLayout)?.isActionFooter == true) 0
            else min((style.recipe.bottomPaddingDp*density).roundToInt(),maximum)
        val available = max(0, maximum - bottom)
        topHeader = (style.recipe.topPaddingDp * density).roundToInt().coerceIn(0, available)
        var used = topHeader
        var remaining = available - topHeader
        fun childHeight()=if(bounded)sheetSurfaceAtMost(remaining)else MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED)
        header.measure(sheetSurfaceExact(inner),childHeight());used+=header.measuredHeight;remaining-=header.measuredHeight
        val bodyGap=min((style.recipe.bodyGapDp*density).roundToInt(),remaining);used+=bodyGap;remaining-=bodyGap
        topBody=used
        body?.let {it.measure(sheetSurfaceExact(inner),childHeight());used+=it.measuredHeight;remaining-=it.measuredHeight}
        val actionsGap=min((style.recipe.actionsGapDp*density).roundToInt(),remaining);used+=actionsGap;remaining-=actionsGap
        topActions=used
        actions?.let {it.measure(sheetSurfaceExact(inner),childHeight());used+=it.measuredHeight}
        setMeasuredDimension(width,resolveSize(used+bottom,heightMeasureSpec))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        val pad=(style.recipe.horizontalPaddingDp*density).roundToInt()
        header.layout(pad, topHeader, pad + header.measuredWidth, topHeader + header.measuredHeight)
        body?.let {it.layout(pad,topBody,pad+it.measuredWidth,topBody+it.measuredHeight)}
        actions?.let {it.layout(pad,topActions,pad+it.measuredWidth,topActions+it.measuredHeight)}
    }
    private class Header(context:Context,val title:View,val close:View?):ViewGroup(context) {
        init {
            importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
            clipChildren=false
            addView(title)
            close?.let { addView(it) }
        }
        override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
            val width=MeasureSpec.getSize(widthMeasureSpec)
            close?.measure(sheetSurfaceAtMost(width),heightMeasureSpec)
            title.measure(sheetSurfaceExact(max(0,width-(close?.measuredWidth ?: 0))),heightMeasureSpec)
            setMeasuredDimension(width,resolveSize(max(title.measuredHeight,close?.measuredHeight ?: 0),heightMeasureSpec))
        }
        override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
            val rtl=layoutDirection==LAYOUT_DIRECTION_RTL
            val titleX=if(rtl)close?.measuredWidth ?: 0 else 0
            val closeX=if(rtl)0 else title.measuredWidth
            fun place(view:View,x:Int) {val y=((height-view.measuredHeight)*.5f).roundToInt()
                view.layout(x,y,x+view.measuredWidth,y+view.measuredHeight)}
            place(title,titleX);close?.let { place(it,closeX) }
        }
    }
}
private fun sheetSurfaceExact(value:Int)=View.MeasureSpec.makeMeasureSpec(value,View.MeasureSpec.EXACTLY)
private fun sheetSurfaceAtMost(value:Int)=View.MeasureSpec.makeMeasureSpec(value,View.MeasureSpec.AT_MOST)
