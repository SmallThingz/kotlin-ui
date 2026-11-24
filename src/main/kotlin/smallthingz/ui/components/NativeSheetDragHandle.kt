package smallthingz.ui.components

import smallthingz.ui.interaction.view.NativeClickBinding
import smallthingz.ui.interaction.view.NativeClickEnvironment
import smallthingz.ui.interaction.view.RowIndication
import smallthingz.ui.interaction.view.RowIndicationSink
import smallthingz.ui.interaction.view.ViewClickController
import smallthingz.ui.interaction.view.readCachedAnimatorDurationScale
import smallthingz.ui.text.view.NativeSourceAccessibilityNode
import smallthingz.ui.util.closePreservingPrimaryFailure

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Outline
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityNodeInfo
import smallthingz.ui.input.phase.NativeClickPhaseHost
import smallthingz.ui.input.phase.NativeClickPhaseNode
import smallthingz.ui.input.phase.NativeDragPointerChange
import smallthingz.ui.input.phase.NativeViewPhasePointerNode
import smallthingz.ui.input.phase.ViewClickPhaseEndpoint
import kotlin.math.max
import kotlin.math.roundToInt
import smallthingz.ui.animation.view.ViewColorTransition

/** 32dp mark with recipe spacing; the retained finger target expands to at least 48dp. */
class NativeSheetDragHandle(
    context:Context,
    private var style:NativeActionSheetStyle,
    inputRoot:()->View?,
    runtime:NativeModalWindowRuntime,
    private val action:()->Unit,
    private val dismissSheet:()->Unit,
):ViewGroup(context),NativeClickPhaseHost,AutoCloseable {
    private val identity=Any()
    internal val verticalTouchInsetPx: Int get() =
        kotlin.math.ceil(max(0f, 48f * style.textDensity.density - measuredHeight) * .5f).toInt()
    internal val mark=HandleMark(context,style)
    private val indication=RowIndication.install(mark,style.palette.onSurface,
        { readCachedAnimatorDurationScale(context) })
    private val controller=ViewClickController(this,object:NativeClickEnvironment {
        override val nativeInputRoot get()=inputRoot()
        override val minimumTouchWidthPx get()=48f*style.textDensity.density
        override val minimumTouchHeightPx get()=minimumTouchWidthPx
        override fun admitDown(event:MotionEvent):Boolean {
            val finger=event.getToolType(0)==MotionEvent.TOOL_TYPE_FINGER
            val expand=if(finger)
                max(0f,minimumTouchWidthPx-width)*.5f else 0f
            val vertical=if(finger)max(0f,minimumTouchHeightPx-height)*.5f else 0f
            return event.x>=-expand&&event.x<=width+expand&&event.y>=-vertical&&event.y<=height+vertical
        }
        override fun interestedParent(event:MotionEvent)=false
        override fun longPressOwnershipChanged(owns:Boolean)=Unit
    },RowIndicationSink(indication) {x,y,out->out.set(x-mark.left,y-mark.top)})
    override val nativeClickEndpoint=ViewClickPhaseEndpoint(controller)
    internal val tooltip=NativeSheetTooltip(this,style,runtime,mark.contentDescription.toString())
    var sourceNode:(()->NativeSourceAccessibilityNode)?=null
    init {
        clipChildren=false;clipToPadding=false
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES
        isClickable=true;isFocusable=true;isScreenReaderFocusable=true
        setOnClickListener {action()}
        addView(mark)
        controller.bind(NativeClickBinding(identity,true,{performClick()}))
    }
    fun pointerNodes(interestedParent: (NativeDragPointerChange) -> Boolean): List<NativeViewPhasePointerNode> =
        listOf(tooltip.pointerNode, NativeClickPhaseNode(this, nativeClickEndpoint, interestedParent))

    fun bindSourceGeometry(source: (View) -> NativeSourceAccessibilityNode) {
        sourceNode = { source(this) }
        mark.sourceNode = { source(mark) }
    }

    fun updateStyle(next:NativeActionSheetStyle) {
        if(style===next)return
        val layoutChanged = style.textDensity != next.textDensity || style.recipe.handleHeightDp != next.recipe.handleHeightDp ||
            style.recipe.handleVerticalPaddingDp != next.recipe.handleVerticalPaddingDp
        style=next
        mark.bindColor(next.palette.onSurfaceVariant)
        indication.updateColor(next.palette.onSurface)
        tooltip.updateStyle(next)
        if(layoutChanged)requestLayout()
    }
    fun refreshState()=invalidate()
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        val d=style.textDensity.density
        val w=resolveSize((32f*d).roundToInt(),widthMeasureSpec)
        val pad=(style.recipe.handleVerticalPaddingDp*d).roundToInt()
        val markHeight = (style.recipe.handleHeightDp * d).roundToInt().coerceAtLeast(1)
        val h=resolveSize(pad*2+markHeight,heightMeasureSpec)
        mark.measure(sheetHandleExact(w),sheetHandleExact(minOf(h, markHeight)))
        setMeasuredDimension(w,h)
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        val y=((height-mark.measuredHeight)*.5f).roundToInt()
        mark.layout(0,y,width,y+mark.measuredHeight)
        indication.onMeasured(mark.width,mark.height,style.textDensity.density)
    }
    override fun addChildrenForAccessibility(outChildren:ArrayList<View>) {
        if(mark.visibility==VISIBLE&&mark.sourceNode?.invoke()?.present!=false)outChildren.add(mark)
    }
    override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className="android.view.View";info.text=null;info.contentDescription=null
        info.isClickable=true;info.isLongClickable=true;info.isEnabled=true
        info.isFocusable=true;info.isFocused=isFocused;info.isScreenReaderFocusable=true
        info.isSelected=false;info.isCheckable=false;info.isChecked=false
        val actions = info.actionList
        for (index in actions.size - 1 downTo 0) info.removeAction(actions[index])
        info.addAction(if(isAccessibilityFocused)AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
            else AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
        info.addAction(if(isFocused)AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_FOCUS
            else AccessibilityNodeInfo.AccessibilityAction.ACTION_FOCUS)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK,
            context.getString(smallthingz.ui.R.string.tooltip_label)))
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_DISMISS,
            context.getString(smallthingz.ui.R.string.native_sheet_dismiss_description)))
        sourceNode?.invoke()?.populate(info)
    }
    override fun createAccessibilityNodeInfo():AccessibilityNodeInfo? =
        if(sourceNode?.invoke()?.present!=false)super.createAccessibilityNodeInfo()else null
    override fun performAccessibilityAction(action:Int,args:Bundle?):Boolean=when(action) {
        AccessibilityNodeInfo.ACTION_CLICK->{this.action();true}
        AccessibilityNodeInfo.ACTION_LONG_CLICK->{tooltip.showFromAccessibility();true}
        AccessibilityNodeInfo.ACTION_DISMISS->{dismissSheet();true}
        AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS,
        AccessibilityNodeInfo.ACTION_FOCUS,AccessibilityNodeInfo.ACTION_CLEAR_FOCUS->
            super.performAccessibilityAction(action,args)
        else->false
    }
    override fun onTouchEvent(event:MotionEvent)=controller.touch(event)
    override fun dispatchKeyEvent(event:KeyEvent):Boolean=when(event.keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_NUMPAD_ENTER,KeyEvent.KEYCODE_SPACE->
            controller.key(event)
        else->super.dispatchKeyEvent(event)
    }
    override fun onHoverEvent(event:MotionEvent):Boolean {
        val platform=super.onHoverEvent(event)
        tooltip.hover(event)
        return controller.hover(event)||platform
    }
    override fun onFocusChanged(focused:Boolean,direction:Int,old:Rect?) {
        super.onFocusChanged(focused,direction,old)
        controller.focusChanged(focused);tooltip.focusChanged(focused)
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow();controller.attached()
        controller.bind(NativeClickBinding(identity,true,{performClick()}));controller.focusChanged(isFocused)
    }
    override fun onDetachedFromWindow() {
        var failure=closePreservingPrimaryFailure(null) {controller.detached()}
        failure=closePreservingPrimaryFailure(failure) {tooltip.retire()}
        failure=closePreservingPrimaryFailure(failure) {super.onDetachedFromWindow()}
        failure?.let {throw it}
    }
    override fun close() {
        var failure=closePreservingPrimaryFailure(null) {controller.detached()}
        failure=closePreservingPrimaryFailure(failure) {tooltip.close()}
        failure?.let {throw it}
    }
    override fun getAccessibilityClassName():CharSequence="android.view.View"
    internal class HandleMark(context:Context,style:NativeActionSheetStyle):View(context) {
        var sourceNode:(()->NativeSourceAccessibilityNode)?=null
        private val clip=Path()
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            color=sheetColorWithAlpha(style.palette.onSurfaceVariant,.45f)
        }
        private val colorMotion:ViewColorTransition by lazy {ViewColorTransition(this,intArrayOf(paint.color),{
            paint.color=colorMotion.color(0);invalidate()
        })}
        init {contentDescription=context.getString(smallthingz.ui.R.string.native_sheet_drag_handle_description)
            importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES;isScreenReaderFocusable=false
            // Input stays on the expanded parent; every visual state is confined to the mark.
            clipToOutline=true
            outlineProvider=object:ViewOutlineProvider() {
                override fun getOutline(view:View,outline:Outline) {
                    outline.setRoundRect(0,0,width,height,minOf(width,height)*.5f)
                }
            }
        }
        fun bindColor(color:Int) {colorMotion.retarget(sheetColorWithAlpha(color,.45f))}
        override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
            val r=minOf(w,h)*.5f
            clip.rewind();clip.addRoundRect(RectF(0f,0f,w.toFloat(),h.toFloat()),r,r,Path.Direction.CW)
            invalidateOutline()
        }
        override fun draw(canvas:Canvas) {
            if(canvas.isHardwareAccelerated)super.draw(canvas) else {
                val save=canvas.save()
                try {canvas.clipPath(clip);super.draw(canvas)} finally {canvas.restoreToCount(save)}
            }
        }
        override fun onDraw(canvas:Canvas) {
            val r=minOf(width,height)*.5f
            canvas.drawRoundRect(0f,0f,width.toFloat(),height.toFloat(),r,r,paint)
        }
        override fun getAccessibilityClassName():CharSequence="android.view.View"
        override fun createAccessibilityNodeInfo():AccessibilityNodeInfo?=
            if(sourceNode?.invoke()?.present!=false)super.createAccessibilityNodeInfo()else null
    }
}
private fun sheetHandleExact(value:Int)=View.MeasureSpec.makeMeasureSpec(value,View.MeasureSpec.EXACTLY)
