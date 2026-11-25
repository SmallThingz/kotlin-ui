/*
 * Default determinate LinearProgressIndicator rendering from AndroidX Material3 1.4.0.
 * Copyright 2024 The Android Open Source Project. Apache-2.0:
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package smallthingz.ui.components

import android.content.Context
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Choreographer
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.core.view.accessibility.AccessibilityEventCompat
import smallthingz.ui.interaction.view.readCachedAnimatorDurationScale
import kotlin.math.min
import kotlin.math.roundToInt

/** Determinate progress keeps authoritative semantics separate from its smooth visual fill. */
open class NativeDeterminateLinearProgress(
    context: Context,
    indicatorColor: Int,
    trackColor: Int,
    density: Float,
) : NativeLinearProgressView(context, indicatorColor, trackColor, density), Choreographer.FrameCallback {
    private var progress = 0f
    override val accessibilityProgress: Float get() = progress
    private val motion=ClearProgressMotion()
    private val choreographer=Choreographer.getInstance()
    private var motionPosted=false
    private var scaleReadDenied=false
    private val manager=context.getSystemService(AccessibilityManager::class.java)
    private var lastSemanticsProgress=0f
    private var semanticsPosted=false
    private val semanticsChanged=Runnable {
        semanticsPosted=false
        val next=progress
        val previous=lastSemanticsProgress
        lastSemanticsProgress=next
        if(next!=previous && isAttachedToWindow && manager?.isEnabled==true && sourceNode()?.present!=false) {
            sourceContentEvent(AccessibilityEventCompat.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION)
            // Pinned delegate2643–2658 refreshes its accessibility-focused node with a second event.
            sourceContentEvent(AccessibilityEventCompat.CONTENT_CHANGE_TYPE_UNDEFINED)
        }
    }

    fun bind(value: Float, indicatorColor: Int, trackColor: Int, density: Float,
        operationId: Long, terminal: Boolean) {
        val changedProgress = calibrationProgressValue(value)
        val progressChanged = progress != changedProgress
        if (progressChanged) scaleReadDenied = false
        val densityChanged = bindAppearance(indicatorColor, trackColor, density)
        progress=changedProgress
        motion.bind(operationId,progress,terminal,frameScale())
        if(!isAttachedToWindow||!isShown)motion.snap()
        scheduleMotion()
        if(densityChanged)requestLayout()
        invalidate()
        if(progressChanged && isAttachedToWindow && manager?.isEnabled==true && !semanticsPosted) {
            semanticsPosted=true
            post(semanticsChanged)
        } else if(!isAttachedToWindow || manager?.isEnabled!=true) lastSemanticsProgress=progress
    }

    override fun onDraw(canvas:Canvas) {
        val visibleProgress=motion.value
        val stroke=height.toFloat()
        val adjustedGap=if(height>width)4f else 4f+stroke/density
        val gapFraction=adjustedGap/(width/density)
        val trackStart=visibleProgress+min(visibleProgress,gapFraction)
        if(trackStart<=1f)drawProgressLine(canvas,trackStart,1f,colors.color(1))
        drawProgressLine(canvas,0f,visibleProgress,colors.color(0))
        val size=min(4f*density,stroke)
        val offset=min((stroke-size)/2f,6f*density)
        paint.style=Paint.Style.FILL;paint.color=colors.color(0)
        val save=canvas.save()
        try {
            if(layoutDirection==LAYOUT_DIRECTION_RTL) {
                canvas.translate(width/2f,height/2f);canvas.scale(-1f,1f);canvas.translate(-width/2f,-height/2f)
            }
            drawProgressDot(canvas,width-size/2f-offset,height/2f,size/2f)
        }finally {canvas.restoreToCount(save)}
    }
    private fun sourceContentEvent(change:Int) {
        @Suppress("DEPRECATION")
        val event=AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
            className="android.view.View";isEnabled=true;packageName=context.packageName
            contentChangeTypes=change;setSource(this@NativeDeterminateLinearProgress)
        }
        parent?.requestSendAccessibilityEvent(this,event)
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastSemanticsProgress=progress
    }
    override fun onDetachedFromWindow() {
        motion.snap();scheduleMotion()
        removeCallbacks(semanticsChanged);semanticsPosted=false
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos:Long) {
        motionPosted=false
        if(!isAttachedToWindow||!isShown)motion.snap()
        else motion.frame(frameTimeNanos,frameScale())
        invalidate();scheduleMotion()
    }
    private fun scheduleMotion() {
        if(motion.running&&!motionPosted) {
            motionPosted=true;choreographer.postFrameCallback(this)
        } else if(!motion.running&&motionPosted) {
            choreographer.removeFrameCallback(this);motionPosted=false
        }
    }
    private fun frameScale():Float {
        if(!ValueAnimator.areAnimatorsEnabled())return 0f
        if(scaleReadDenied)return 1f
        val scale=try {readCachedAnimatorDurationScale(context)} catch(_:SecurityException) {
            scaleReadDenied=true;1f
        }
        return if(scale.isFinite()&&scale>=0f)scale else 1f
    }
}

/** Released caller rejects non-finite samples before M3's coercedProgress lambda. */
internal fun calibrationProgressValue(value:Float)=if(value.isFinite())value.coerceIn(0f,1f)else 0f

/** Pinned accessibility delegate exposes endpoints only for exact endpoint values. */
internal fun calibrationProgressPercent(value:Float):Int=when(value) {
    0f->0
    1f->100
    else->(value*100).roundToInt().coerceIn(1,99)
}
