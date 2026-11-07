package smallthingz.ui.animation

import smallthingz.ui.animation.core.FastOutSlowInEasing
import smallthingz.ui.graphics.Color
import smallthingz.ui.graphics.lerp
import smallthingz.ui.graphics.toArgb

/** One bounded timeline for a related set of foreground, background and icon colors. */
class ColorTransition(initial:IntArray) {
    private val displayed=initial.copyOf()
    private val start=initial.copyOf()
    private val target=initial.copyOf()
    private var elapsed=0.0
    private var lastFrame:Long?=null
    var running=false
        private set
    val size:Int get()=displayed.size
    val isInterpolating:Boolean get()=running&&elapsed>0.0
    fun color(index:Int):Int=displayed[index]
    fun hasTarget(colors:IntArray):Boolean {require(colors.size==size);return target.contentEquals(colors)}

    fun retarget(next:IntArray,nowNanos:Long,durationScale:Float=1f):Boolean {
        require(next.size==size)
        val sampleTime=maxOf(nowNanos,lastFrame ?: nowNanos)
        frame(sampleTime,durationScale)
        if(target.contentEquals(next))return running
        displayed.copyInto(start);next.copyInto(target)
        elapsed=0.0;lastFrame=sampleTime
        running=!displayed.contentEquals(target)
        if(durationScale<=0f)snapToTarget()
        return running
    }

    fun frame(nowNanos:Long,durationScale:Float=1f) {
        if(!running)return
        if(durationScale<=0f){snapToTarget();return}
        val scale=if(durationScale.isFinite())durationScale.toDouble() else 1.0
        val previous=lastFrame
        lastFrame=maxOf(nowNanos,previous ?: nowNanos)
        if(previous!=null)elapsed+=(nowNanos-previous).coerceAtLeast(0L)/scale
        val fraction=(elapsed/DURATION_NANOS).coerceIn(0.0,1.0).toFloat()
        if(fraction>=1f){snapToTarget();return}
        val eased=FastOutSlowInEasing.transform(fraction)
        for(index in displayed.indices)displayed[index]=lerp(Color(start[index]),Color(target[index]),eased).toArgb()
    }

    fun snapToTarget() {
        target.copyInto(displayed);target.copyInto(start)
        running=false;lastFrame=null;elapsed=0.0
    }

    companion object { const val DURATION_NANOS=160_000_000L }
}
