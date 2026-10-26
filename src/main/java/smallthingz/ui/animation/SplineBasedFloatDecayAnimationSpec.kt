/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package smallthingz.ui.animation

import smallthingz.ui.animation.core.FloatDecayAnimationSpec
import smallthingz.ui.unit.Density
import kotlin.math.sign

public class SplineBasedFloatDecayAnimationSpec(density: Density) : FloatDecayAnimationSpec {

    private val flingCalculator =
        FlingCalculator(friction = platformFlingScrollFriction, density = density)

    @Volatile private var cachedFling: FlingCalculator.FlingInfo? = null

    private fun flingInfo(velocity: Float): FlingCalculator.FlingInfo {
        val cached = cachedFling
        if (cached != null && cached.initialVelocity.toRawBits() == velocity.toRawBits()) return cached
        // The calculator captures immutable friction and density coefficients. Concurrent misses
        // may duplicate calculation, but each caller uses its own coherent immutable result.
        return flingCalculator.flingInfo(velocity).also { cachedFling = it }
    }

    override val absVelocityThreshold: Float
        get() = 0f

    private fun flingDistance(startVelocity: Float): Float =
        flingInfo(startVelocity).distance * sign(startVelocity)

    override fun getTargetValue(initialValue: Float, initialVelocity: Float): Float =
        initialValue + flingDistance(initialVelocity)

    @Suppress("MethodNameUnits")
    override fun getValueFromNanos(
        playTimeNanos: Long,
        initialValue: Float,
        initialVelocity: Float,
    ): Float {
        val playTimeMillis = playTimeNanos / 1_000_000L
        return initialValue + flingInfo(initialVelocity).position(playTimeMillis)
    }

    @Suppress("MethodNameUnits")
    override fun getDurationNanos(initialValue: Float, initialVelocity: Float): Long =
        flingInfo(initialVelocity).duration * 1_000_000L

    @Suppress("MethodNameUnits")
    override fun getVelocityFromNanos(
        playTimeNanos: Long,
        initialValue: Float,
        initialVelocity: Float,
    ): Float {
        val playTimeMillis = playTimeNanos / 1_000_000L
        return flingInfo(initialVelocity).velocity(playTimeMillis)
    }
}

internal val platformFlingScrollFriction: Float = android.view.ViewConfiguration.getScrollFriction()
