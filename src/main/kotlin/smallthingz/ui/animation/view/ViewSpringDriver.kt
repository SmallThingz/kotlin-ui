// SPDX-License-Identifier: Apache-2.0
package smallthingz.ui.animation.view

import android.animation.ValueAnimator
import android.view.Choreographer
import android.view.View
import androidx.annotation.MainThread
import smallthingz.ui.animation.SpringValue
import smallthingz.ui.animation.core.AnimationVector

/**
 * Optional thin View scheduler. The owner supplies its normal paint/property update and
 * closes this with the same lifetime that previously owned the animation composition.
 * A retained but hidden screen must not be disposed merely because it lost window focus.
 */
@MainThread
class ViewSpringDriver<T, V : AnimationVector>(
    private val view: View,
    private val state: SpringValue<T, V>,
    private val publish: (T) -> Unit,
    private val durationScale: () -> Float = { ValueAnimator.getDurationScale() },
) : Choreographer.FrameCallback, AutoCloseable {
    private val choreographer = Choreographer.getInstance()
    private var framePosted = false
    private var closed = false

    fun retarget(next: T) {
        if (closed || !state.retarget(next, durationScale())) return
        publish(state.value)
        view.invalidate()
        postIfRunning()
    }

    override fun doFrame(frameTimeNanos: Long) {
        framePosted = false
        if (closed) return
        state.frame(frameTimeNanos, durationScale())
        publish(state.value)
        view.invalidate()
        postIfRunning()
    }

    private fun postIfRunning() {
        if (!closed && state.isRunning && !framePosted) {
            framePosted = true
            choreographer.postFrameCallback(this)
        }
    }

    override fun close() {
        closed = true
        choreographer.removeFrameCallback(this)
        framePosted = false
        state.stop()
    }
}
