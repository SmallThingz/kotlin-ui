/*
 * Copyright 2019 The Android Open Source Project
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
package smallthingz.ui.graphics.colorspace

import smallthingz.ui.graphics.Color

/** Internal fixed-range RGB implementation; no user profiles or transfer callbacks. */
internal class NamedRgb(name: String, id: Int, private val gamma: Double,
                        matrix: FloatArray? = null) : ColorSpace(name, ColorModel.Rgb, id) {
    private val inverseGamma = 1.0 / gamma
    internal val transform = matrix ?: computeXYZMatrix(
        floatArrayOf(0.640f, 0.330f, 0.300f, 0.600f, 0.150f, 0.060f), NamedD65)
    internal val inverseTransform = inverse3x3(transform)
    override val isSrgb: Boolean = id == 0
    override fun getMinValue(component: Int): Float = 0f
    override fun getMaxValue(component: Int): Float = 1f
    internal fun eotf(value: Double): Double =
        response(value.coerceIn(0.0, 1.0), 1 / 1.055, 0.055 / 1.055, 1 / 12.92, 0.04045, gamma)
    internal fun oetf(value: Double): Double =
        rcpResponse(value, 1 / 1.055, 0.055 / 1.055, 1 / 12.92, 0.04045, inverseGamma).coerceIn(0.0, 1.0)
    internal fun adaptedToD50(): NamedRgb = NamedRgb(name, MinId, gamma,
        mul3x3(chromaticAdaptation(Bradford, NamedD65.toXyz(), NamedD50.toXyz()), transform))

    override fun toXyz(v: FloatArray): FloatArray {
        // Compiler hint to avoid extra bounds checks
        if (v.size < 3) return v
        v[0] = eotf(v[0].toDouble()).toFloat()
        v[1] = eotf(v[1].toDouble()).toFloat()
        v[2] = eotf(v[2].toDouble()).toFloat()
        return mul3x3Float3(transform, v)
    }

    override fun xyzaToColor(
        x: Float,
        y: Float,
        z: Float,
        a: Float,
        colorSpace: ColorSpace,
    ): Color {
        var v0 = mul3x3Float3_0(inverseTransform, x, y, z)
        var v1 = mul3x3Float3_1(inverseTransform, x, y, z)
        var v2 = mul3x3Float3_2(inverseTransform, x, y, z)

        v0 = oetf(v0.toDouble()).toFloat()
        v1 = oetf(v1.toDouble()).toFloat()
        v2 = oetf(v2.toDouble()).toFloat()

        return Color(v0, v1, v2, a, colorSpace)
    }

    override fun fromXyz(v: FloatArray): FloatArray {
        mul3x3Float3(inverseTransform, v)
        // Compiler hint to avoid extra bounds checks
        if (v.size < 3) return v
        v[0] = oetf(v[0].toDouble()).toFloat()
        v[1] = oetf(v[1].toDouble()).toFloat()
        v[2] = oetf(v[2].toDouble()).toFloat()
        return v
    }

}
