package smallthingz.ui.controls

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import smallthingz.ui.animation.view.ViewColorTransition
import kotlin.math.min
import smallthingz.ui.graphics.bindGrainLayer

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
    private var grain: smallthingz.ui.graphics.ViewGrain? = null
    private var grainIntensity = 0f
    private var fillBounds: RectF? = null
    private var useFillBounds = false

    /** A copied view-local paint rectangle. Never changes Drawable/mask/outline bounds. */
    internal fun setFillBounds(bounds: RectF?) {
        if (bounds == null) {
            if (!useFillBounds) return
            useFillBounds = false
        } else {
            if (useFillBounds && fillBounds == bounds) return
            (fillBounds ?: RectF().also { fillBounds = it }).set(bounds)
            useFillBounds = true
        }
        if (ready) publish()
    }

    internal fun setSurfacePreset(preset: smallthingz.ui.components.NativeSurfacePreset?) {
        val intensity = preset?.foregroundGrain ?: 0f
        if (grainIntensity == intensity) return
        grainIntensity = intensity
        grain = view.bindGrainLayer(grain, intensity)
        if (ready) publish()
    }

    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
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
            if (paintFill) {
                val fill = if (useFillBounds) fillBounds else null
                val left = fill?.left ?: bounds.left.toFloat()
                val top = fill?.top ?: bounds.top.toFloat()
                val right = fill?.right ?: bounds.right.toFloat()
                val bottom = fill?.bottom ?: bounds.bottom.toFloat()
                val fillCorner = min(radius, min(right - left, bottom - top) / 2f)
                canvas.drawRoundRect(left, top, right, bottom, fillCorner, fillCorner, paint)
                grain?.draw(canvas, left, top, right, bottom, fillCorner, paint.alpha)
            }
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
    // Both children belong to this View. The content reads its live animation, so it cannot
    // be cloned for another owner. A framework background tint may call mutate on installation.
    val background: RippleDrawable = object : RippleDrawable(
        ColorStateList.valueOf(motion.color(3)), surface,
        GradientDrawable().apply { setColor(-1); cornerRadius = radiusPx },
    ) {
        override fun getConstantState(): Drawable.ConstantState? = null
        override fun mutate(): Drawable = this
    }
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
        if (radius != radiusPx) {
            radius = radiusPx
            (background.findDrawableByLayerId(android.R.id.mask) as? GradientDrawable)?.cornerRadius = radiusPx
            view.invalidateOutline()
        }
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
        fun readable(color: Int): Int {
            if (parentOwnsForeground || color ushr 24 != 255 || motion.color(6) ushr 24 != 255) return color
            if (grainIntensity > 0f && paintFill) {
                val fill = motion.color(0)
                val background = androidx.core.graphics.ColorUtils.compositeColors(fill, motion.color(6))
                return textSurfaceForeground(checkNotNull(grain).surfaceRange(background, fill ushr 24), color)
            }
            return if (motion.isInterpolating) selectionForeground(motion.color(0), motion.color(6), color, color) else color
        }
        val accent = motion.color(5)
        if (useFillBounds && !parentOwnsForeground && view.isEnabled && underlayColor ushr 24 == 255) {
            // The label is on the page; the icon is on animated fill/grain. Qualify each
            // against its own background and retain one full-target ripple, using the
            // opacity prefix admitted by BOTH opaque inks independently. Translucent
            // caller ink is retained; disabled styling uses the established path. Historical platform ripple paints are
            // outside the existing sampled-current-color indication contract.
            // The host paints the new palette surface synchronously. The local fill and
            // inks still animate over that surface, including on the first bind frame.
            val page = textSurfaceRange(underlayColor)
            val fill = motion.color(0)
            val composite = androidx.core.graphics.ColorUtils.compositeColors(fill, underlayColor)
            val iconRange = if (grainIntensity > 0f)
                checkNotNull(grain).surfaceRange(composite, fill ushr 24) else textSurfaceRange(composite)
            val ripple = motion.color(3)
            fun qualified(range: Long, ink: Int): Long = if (ink ushr 24 == 255)
                textSurfaceForegroundWithIndication(range, ink, ripple)
                else ((ripple ushr 24).toLong() shl 32) or (ink.toLong() and 0xffffffffL)
            val label = qualified(page, motion.color(2))
            val icon = qualified(iconRange, motion.color(4))
            val alpha = min((label ushr 32).toInt(), (icon ushr 32).toInt())
            publishInk(label.toInt(), icon.toInt(), if (qualifyAccentInk) readable(accent) else accent)
            publishRipple((ripple and 0x00ffffff) or (alpha shl 24))
        } else {
            publishInk(readable(motion.color(2)),readable(motion.color(4)),if (qualifyAccentInk) readable(accent) else accent)
            if (!parentOwnsForeground) publishRipple(motion.color(3))
        }
        surface.invalidateSelf()
    }
    override fun close()=motion.close()
}
