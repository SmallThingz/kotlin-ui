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

/** The fixed renderer palette. Custom profiles and chromatic-adaptation APIs are not exposed. */
sealed class ColorSpace protected constructor(val name: String, val model: ColorModel, internal val id: Int) {
    val componentCount: Int get() = 3
    open val isSrgb: Boolean get() = false
    open val isWideGamut: Boolean get() = false
    abstract fun getMinValue(component: Int): Float
    abstract fun getMaxValue(component: Int): Float
    abstract fun toXyz(v: FloatArray): FloatArray
    abstract fun fromXyz(v: FloatArray): FloatArray
    fun toXyz(r: Float, g: Float, b: Float): FloatArray = toXyz(floatArrayOf(r, g, b))
    fun fromXyz(x: Float, y: Float, z: Float): FloatArray = fromXyz(floatArrayOf(x, y, z))
    internal abstract fun xyzaToColor(x: Float, y: Float, z: Float, a: Float, colorSpace: ColorSpace): Color
    override fun toString(): String = "$name (id=$id, model=$model)"
    internal companion object {
        const val MinId = -1
        const val MaxId = 63
    }
}

/** Cached named-space connector; identity retains every packed payload bit. */
fun ColorSpace.connect(destination: ColorSpace = ColorSpaces.Srgb,
                       intent: RenderIntent = RenderIntent.Perceptual): Connector {
    val key = connectorKey(id, destination.id, intent)
    Connectors.get(key)?.let { return it }
    val created = createNamedConnector(this, destination, intent)
    return if (Connectors.compareAndSet(key, null, created)) created else Connectors.get(key)
}
