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

import smallthingz.ui.animation.core.DecayAnimationSpec
import smallthingz.ui.animation.core.generateDecayAnimationSpec
import smallthingz.ui.unit.Density
import kotlin.math.abs
import kotlin.math.ln

private const val Inflection = 0.35f // Tension lines cross at (Inflection, 1)
private const val StartTension = 0.5f
private const val EndTension = 1.0f
private const val P1 = StartTension * Inflection
private const val P2 = 1.0f - EndTension * (1.0f - Inflection)

private fun computeSplineInfo(
    splinePositions: FloatArray,
    nbSamples: Int,
) {
    var xMin = 0.0f
    for (i in 0 until nbSamples) {
        val alpha = i.toFloat() / nbSamples
        var xMax = 1.0f
        var x: Float
        var tx: Float
        var coef: Float
        while (true) {
            x = xMin + (xMax - xMin) / 2.0f
            coef = 3.0f * x * (1.0f - x)
            tx = coef * ((1.0f - x) * P1 + x * P2) + x * x * x
            if (abs(tx - alpha) < 1E-5) break
            if (tx > alpha) xMax = x else xMin = x
        }
        splinePositions[i] = coef * ((1.0f - x) * StartTension + x) + x * x * x

    }
    splinePositions[nbSamples] = 1.0f
}

internal object AndroidFlingSpline {
    private const val NbSamples = 100
    private val SplinePositions = FloatArray(NbSamples + 1)

    init {
        // TODO This function used to be directly implemented in this init block, but it causes a
        // crash in the IR compiler.
        computeSplineInfo(SplinePositions, NbSamples)
    }

    
    fun distanceCoefficient(time: Float): Float {
        // Clamp clocks before the start, matching the released spline behavior. See b/313685022.
        val clampedTime = time.coerceIn(0f, 1f)
        val index = (NbSamples * clampedTime).toInt()
        if (index >= NbSamples) return 1f
        val velocityCoef = slope(index)
        val tInf = index.toFloat() / NbSamples
        val dInf = SplinePositions[index]
        return dInf + (clampedTime - tInf) * velocityCoef
    }

    fun velocityCoefficient(time: Float): Float {
        val index = (NbSamples * time.coerceIn(0f, 1f)).toInt()
        return if (index < NbSamples) slope(index) else 0f
    }

    private fun slope(index: Int): Float {
        val tInf = index.toFloat() / NbSamples
        val tSup = (index + 1).toFloat() / NbSamples
        val dInf = SplinePositions[index]
        val dSup = SplinePositions[index + 1]
        return (dSup - dInf) / (tSup - tInf)
    }

    
    fun deceleration(velocity: Float, friction: Float): Double =
        ln(Inflection * abs(velocity) / friction.toDouble())

}

public fun <T> splineBasedDecay(density: Density): DecayAnimationSpec<T> =
    SplineBasedFloatDecayAnimationSpec(density).generateDecayAnimationSpec()
