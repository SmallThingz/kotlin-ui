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

/** Immutable cached conversion between the three named spaces. No custom callback profiles. */
open class Connector internal constructor(
    val source: ColorSpace,
    val destination: ColorSpace,
    private val transformSource: ColorSpace,
    private val transformDestination: ColorSpace,
    val renderIntent: RenderIntent,
    private val transform: FloatArray?,
) {
    fun transform(r: Float, g: Float, b: Float): FloatArray = transform(floatArrayOf(r, g, b))
    open fun transform(v: FloatArray): FloatArray {
        val xyz = transformSource.toXyz(v)
        if (transform != null) {
            xyz[0] *= transform[0]
            xyz[1] *= transform[1]
            xyz[2] *= transform[2]
        }
        return transformDestination.fromXyz(xyz)
    }

    internal open fun transformToColor(color: Color): Color {
        val (r, g, b, a) = color
        if (transformSource is Oklab) return transformSource.toConnectorColor(r, g, b, a, this)
        val from = transformSource as NamedRgb
        val v0 = from.eotf(r.toDouble()).toFloat()
        val v1 = from.eotf(g.toDouble()).toFloat()
        val v2 = from.eotf(b.toDouble()).toFloat()
        val matrix = from.transform
        return xyzToColor(mul3x3Float3_0(matrix, v0, v1, v2),
            mul3x3Float3_1(matrix, v0, v1, v2), mul3x3Float3_2(matrix, v0, v1, v2), a)
    }

    internal fun xyzToColor(x: Float, y: Float, z: Float, a: Float): Color {
        if (transform != null) return transformDestination.xyzaToColor(
            x * transform[0], y * transform[1], z * transform[2], a, destination)
        return transformDestination.xyzaToColor(x, y, z, a, destination)
    }
}

/** Both named RGB spaces have the same primaries and D65 white point. */
private class NamedRgbConnector(private val from: NamedRgb, private val to: NamedRgb, intent: RenderIntent) :
    Connector(from, to, from, to, intent, null) {
    private val matrix = mul3x3(to.inverseTransform, from.transform)
    override fun transform(v: FloatArray): FloatArray {
        v[0] = from.eotf(v[0].toDouble()).toFloat()
        v[1] = from.eotf(v[1].toDouble()).toFloat()
        v[2] = from.eotf(v[2].toDouble()).toFloat()
        mul3x3Float3(matrix, v)
        v[0] = to.oetf(v[0].toDouble()).toFloat()
        v[1] = to.oetf(v[1].toDouble()).toFloat()
        v[2] = to.oetf(v[2].toDouble()).toFloat()
        return v
    }
    override fun transformToColor(color: Color): Color {
        val (r, g, b, a) = color
        val v0 = from.eotf(r.toDouble()).toFloat()
        val v1 = from.eotf(g.toDouble()).toFloat()
        val v2 = from.eotf(b.toDouble()).toFloat()
        val v01 = mul3x3Float3_0(matrix, v0, v1, v2)
        val v11 = mul3x3Float3_1(matrix, v0, v1, v2)
        val v21 = mul3x3Float3_2(matrix, v0, v1, v2)
        val v02 = to.oetf(v01.toDouble()).toFloat()
        val v12 = to.oetf(v11.toDouble()).toFloat()
        val v22 = to.oetf(v21.toDouble()).toFloat()
        return Color(v02, v12, v22, a, to)
    }
}

private fun namedConnector(source: ColorSpace, destination: ColorSpace, intent: RenderIntent): Connector {
    if (source === destination) return object : Connector(source, destination, source, destination, RenderIntent.Relative, null) {
        override fun transform(v: FloatArray): FloatArray = v
        override fun transformToColor(color: Color): Color = color
    }
    if (source is NamedRgb && destination is NamedRgb) return NamedRgbConnector(source, destination, intent)
    val from = if (source is NamedRgb) source.adaptedToD50() else source
    val to = if (destination is NamedRgb) destination.adaptedToD50() else destination
    val transform = if (intent == RenderIntent.Absolute) {
        val srcXYZ = if (source is NamedRgb) NamedD65.toXyz() else NamedD50Xyz
        val dstXYZ = if (destination is NamedRgb) NamedD65.toXyz() else NamedD50Xyz
        floatArrayOf(srcXYZ[0] / dstXYZ[0], srcXYZ[1] / dstXYZ[1], srcXYZ[2] / dstXYZ[2])
    } else null
    return Connector(source, destination, from, to, intent, transform)
}

// Same dense bounded cache and dispatch specialization; no per-frame connector/vector allocation.
internal val Connectors = java.util.concurrent.atomic.AtomicReferenceArray<Connector>(36).apply {
    set(connectorKey(0, 0, RenderIntent.Perceptual), namedConnector(ColorSpaces.Srgb, ColorSpaces.Srgb, RenderIntent.Perceptual))
    set(connectorKey(0, 19, RenderIntent.Perceptual), namedConnector(ColorSpaces.Srgb, ColorSpaces.Oklab, RenderIntent.Perceptual))
    set(connectorKey(19, 0, RenderIntent.Perceptual), namedConnector(ColorSpaces.Oklab, ColorSpaces.Srgb, RenderIntent.Perceptual))
}
internal fun connectorKey(src: Int, dst: Int, renderIntent: RenderIntent): Int {
    fun index(id: Int) = when (id) { 0 -> 0; 16 -> 1; 19 -> 2; else -> error("Unknown named color space: $id") }
    return (index(src) * 3 + index(dst)) * 4 + renderIntent.value
}
internal fun createNamedConnector(source: ColorSpace, destination: ColorSpace, intent: RenderIntent): Connector =
    namedConnector(source, destination, intent)
