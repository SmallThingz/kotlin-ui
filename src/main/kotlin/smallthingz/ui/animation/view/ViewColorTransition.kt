package smallthingz.ui.animation.view

import android.animation.ValueAnimator
import android.os.Build
import android.view.Choreographer
import android.view.View
import smallthingz.ui.animation.ColorTransition

/** Retained color-only owner. No layout, drawable creation or initial-bind animation. */
class ViewColorTransition(
    private val view:View,
    initial:IntArray,
    private val publish:()->Unit,
    private val durationScale:(()->Float)?=null,
):Choreographer.FrameCallback,View.OnAttachStateChangeListener,AutoCloseable {
    private val state=ColorTransition(initial)
    private val choreographer=Choreographer.getInstance()
    private var posted=false
    private var closed=false
    private var cachedScale=1f
    init {view.addOnAttachStateChangeListener(this)}
    fun color(index:Int):Int=state.color(index)
    val isInterpolating:Boolean get()=state.isInterpolating

    fun snap(vararg colors:Int) {
        if(closed)return
        state.retarget(colors,System.nanoTime(),0f)
        state.snapToTarget()
        publish();view.invalidate();schedule()
    }

    fun retarget(vararg colors:Int) {
        if(closed)return
        val unchanged=state.hasTarget(colors)
        if(unchanged&&!state.running)return
        cachedScale=if(unchanged)frameScale() else durationScale?.invoke() ?: readAnimatorDurationScale(view.context)
        state.retarget(colors,System.nanoTime(),cachedScale)
        if(!view.isAttachedToWindow||!view.isShown)state.snapToTarget()
        publish();view.invalidate();schedule()
    }

    override fun doFrame(frameTimeNanos:Long) {
        posted=false
        if(closed)return
        if(!view.isAttachedToWindow||!view.isShown)state.snapToTarget()
        else state.frame(frameTimeNanos,frameScale())
        publish();view.invalidate();schedule()
    }

    private fun schedule() {
        if(state.running&&!posted&&!closed){posted=true;choreographer.postFrameCallback(this)}
        else if(!state.running&&posted){choreographer.removeFrameCallback(this);posted=false}
    }
    private fun frameScale():Float = durationScale?.invoke() ?: when {
        !ValueAnimator.areAnimatorsEnabled()->0f
        Build.VERSION.SDK_INT>=33->ValueAnimator.getDurationScale()
        else->cachedScale
    }
    override fun onViewAttachedToWindow(view:View) = Unit
    override fun onViewDetachedFromWindow(view:View) {
        if(closed)return
        state.snapToTarget();choreographer.removeFrameCallback(this);posted=false
        publish();view.invalidate()
    }
    override fun close() {
        if(closed)return
        closed=true;state.snapToTarget();choreographer.removeFrameCallback(this);posted=false
        view.removeOnAttachStateChangeListener(this)
    }
}
