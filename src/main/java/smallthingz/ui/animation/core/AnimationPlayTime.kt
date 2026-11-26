// SPDX-License-Identifier: Apache-2.0
package smallthingz.ui.animation.core

import kotlin.math.roundToLong

/** Integrates admitted frame intervals at the scale observed by that frame. */
internal class AnimationPlayTime(private val roundToNearest: Boolean = false) {
    private var previousFrame = 0L
    private var initialized = false
    private var elapsed = 0.0

    fun reset() {
        initialized = false
        elapsed = 0.0
    }

    fun advance(frame: Long, scale: Float, end: Long = Long.MAX_VALUE): Long {
        require(scale >= 0f) { "negative scale factor" }
        if (scale == 0f) return end
        if (initialized) {
            elapsed += (frame - previousFrame).coerceAtLeast(0L) / scale.toDouble()
        }
        initialized = true
        previousFrame = frame
        // Preserve the source sampler's Float conversion at constant unit scale.
        return if (roundToNearest) elapsed.roundToLong() else elapsed.toFloat().toLong()
    }
}
