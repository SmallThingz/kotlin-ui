package smallthingz.ui.controls

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import smallthingz.ui.animation.view.ViewColorTransition
import kotlin.math.min

/** Retained native surface, ripple and paired ink; state/palette admission never rebuilds per frame. */
class StateColorSurface(
    private val view:View,
    fill:ColorStateList,
    stroke:ColorStateList,
    ink:ColorStateList,
    ripple:ColorStateList,
    radiusPx:Float,
    strokePx:Float,
    secondaryInk:ColorStateList=ink,
    accentInk:ColorStateList=ink,
    underlay:Int=0,
    private val focusedStrokeScale:Float=2f,
    private val animateState:Boolean=true,
    private val publishInk:(Int,Int,Int)->Unit,
):AutoCloseable {
    init {
        require(radiusPx.isFinite()&&radiusPx>=0f&&strokePx.isFinite()&&strokePx>=0f)
        require(focusedStrokeScale.isFinite()&&focusedStrokeScale>=0f)
    }
    private var fills=fill
    private var strokes=stroke
    private var inks=ink
    private var ripples=ripple
    private var secondaryInks=secondaryInk
    private var accentInks=accentInk
    private var underlayColor=underlay
    private var radius=radiusPx
    private var strokeWidth=strokePx
    private var ready=false
    internal var qualifyAccentInk=false
    internal var paintFill=true
        set(value) {field=value;view.invalidate()}
    internal var parentOwnsForeground=false
        set(value) {
            if(field==value)return
            field=value
            if(ready) {
                retarget()
                // An idle, unchanged target skips the motion callback. Ownership still changed:
                // restore raw ink/ripple immediately when the parent stops qualifying them.
                publish()
            }
        }
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val mask=GradientDrawable().apply {setColor(-1);cornerRadius=radiusPx}
    private val surface=object:Drawable() {
        private var opacity=255
        override fun isStateful()=true
        override fun onStateChange(state:IntArray):Boolean {
            if(ready) {if(animateState)retarget(state)else motion.snap(*colors(state))}
            return true
        }
        override fun draw(canvas:Canvas) {
            val corner=min(radius,min(bounds.width(),bounds.height())/2f)
            paint.style=Paint.Style.FILL;paint.color=motion.color(0)
            paint.alpha=(paint.alpha*opacity+127)/255
            if(paintFill)canvas.drawRoundRect(bounds.left.toFloat(),bounds.top.toFloat(),bounds.right.toFloat(),bounds.bottom.toFloat(),corner,corner,paint)
            paint.style=Paint.Style.STROKE;paint.color=motion.color(1)
            paint.alpha=(paint.alpha*opacity+127)/255
            paint.strokeWidth=strokeWidth*if(android.R.attr.state_enabled in state&&android.R.attr.state_focused in state)focusedStrokeScale else 1f
            val inset=paint.strokeWidth/2f
            if(paint.alpha!=0)canvas.drawRoundRect(bounds.left+inset,bounds.top+inset,bounds.right-inset,bounds.bottom-inset,corner,corner,paint)
        }
        override fun getOutline(outline:Outline) {outline.setRoundRect(bounds,radius)}
        override fun setAlpha(alpha:Int){opacity=alpha.coerceIn(0,255);invalidateSelf()}
        override fun setColorFilter(filter:ColorFilter?){paint.colorFilter=filter;invalidateSelf()}
        @Suppress("DEPRECATION") override fun getOpacity()=PixelFormat.TRANSLUCENT
    }
    private val motion=ViewColorTransition(view,colors(view.drawableState),{publish()})
    val background=RippleDrawable(ColorStateList.valueOf(motion.color(3)),surface,mask)
    private var publishedRippleColor = motion.color(3)
    internal val animatedRippleColor: Int get() = motion.color(3)
    internal fun foregroundOnSurface(preferred: Int, alternate: Int): Int =
        selectionForeground(motion.color(0), motion.color(6), preferred, alternate)

    internal fun publishParentRipple(color: Int) {
        if (parentOwnsForeground) publishRipple(color)
    }
    private fun publishRipple(color: Int) {
        if (publishedRippleColor == color) return
        publishedRippleColor = color
        background.setColor(ColorStateList.valueOf(color))
    }
    init {ready=true;surface.state=view.drawableState;publish()}

    fun bind(fill:ColorStateList,stroke:ColorStateList,ink:ColorStateList,ripple:ColorStateList,
        radiusPx:Float,strokePx:Float,secondaryInk:ColorStateList=ink,accentInk:ColorStateList=ink,underlay:Int=0) {
        require(radiusPx.isFinite()&&radiusPx>=0f&&strokePx.isFinite()&&strokePx>=0f)
        fills=fill;strokes=stroke;inks=ink;ripples=ripple
        secondaryInks=secondaryInk;accentInks=accentInk;underlayColor=underlay
        if(radius!=radiusPx){radius=radiusPx;mask.cornerRadius=radiusPx;view.invalidateOutline()}
        strokeWidth=strokePx
        retarget()
    }
    private fun colors(state:IntArray)=intArrayOf(
        if(parentOwnsForeground)0 else fills.getColorForState(state,fills.defaultColor),strokes.getColorForState(state,strokes.defaultColor),
        inks.getColorForState(state,inks.defaultColor),ripples.getColorForState(state,ripples.defaultColor),
        secondaryInks.getColorForState(state,secondaryInks.defaultColor),accentInks.getColorForState(state,accentInks.defaultColor),underlayColor)
    private fun retarget(state:IntArray=view.drawableState){motion.retarget(*colors(state))}
    fun updateState(state:IntArray){retarget(state)}
    private fun publish() {
        fun readable(color:Int)=if(!parentOwnsForeground&&motion.isInterpolating&&color ushr 24==255&&motion.color(6) ushr 24==255)
            selectionForeground(motion.color(0),motion.color(6),color,color) else color
        val accent = motion.color(5)
        publishInk(readable(motion.color(2)),readable(motion.color(4)),if (qualifyAccentInk) readable(accent) else accent)
        if (!parentOwnsForeground) publishRipple(motion.color(3))
        surface.invalidateSelf()
    }
    override fun close()=motion.close()
}
