package smallthingz.ui.animation.view

import androidx.annotation.MainThread
import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.MotionDurationScale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One animation job on the owning window clock. Attachment only admits frame delivery; it does not
 * reset the transition's elapsed time. The window runtime owns START/STOP and queued-frame gating.
 */
@MainThread
class AttachedFrameLoop(
    private val scope:CoroutineScope,
    private val hasWork:()->Boolean,
    private val sample:(Long,Float)->Unit,
):AutoCloseable {
    private val clock=requireNotNull(scope.coroutineContext[MonotonicFrameClock])
    private var attached=false
    private var closed=false
    private var generation=0L
    private var job:Job?=null

    fun attached() {if(!closed){attached=true;schedule()}}
    fun detached() {
        attached=false
        generation++
        job?.cancel()
        job=null
    }
    fun schedule() {
        if(closed||!attached||!scope.isActive||!hasWork()||job?.isActive==true)return
        val owner=++generation
        val next=scope.launch(start=CoroutineStart.LAZY) {
            try {
                while(isActive&&attached&&!closed&&hasWork()) {
                    clock.withFrameNanos {time->
                        // Detach or scope cancellation can follow dispatcher's frame dequeue.
                        if(owner==generation&&isActive&&attached&&!closed) {
                            sample(time,coroutineContext[MotionDurationScale]?.scaleFactor?:1f)
                        }
                    }
                }
            }finally {
                if(owner==generation)job=null
            }
        }
        job=next
        next.start()
    }
    override fun close() {
        if(closed)return
        closed=true
        detached()
    }
}
