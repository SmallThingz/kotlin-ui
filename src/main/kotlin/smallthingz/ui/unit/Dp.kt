/*
 * Copyright 2018 The Android Open Source Project
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
@file:Suppress("NOTHING_TO_INLINE", "KotlinRedundantDiagnosticSuppress")

package smallthingz.ui.unit

import smallthingz.ui.unit.Dp.Companion.Hairline
import smallthingz.ui.util.fastIsFinite
import smallthingz.ui.util.lerp
import kotlin.jvm.JvmInline
import kotlin.math.max
import kotlin.math.min

/**
 * Dimension value representing device-independent pixels (dp). Component APIs specify their
 * dimensions such as line thickness in DP with Dp objects. Hairline (1 pixel) thickness may be
 * specified with [Hairline], a dimension that take up no space. Dp are normally defined using [dp],
 * which can be applied to [Int], [Double], and [Float].
 *
 * @sample smallthingz.ui.unit.samples.DpSample
 *
 * Drawing and Layout are done in pixels. To retrieve the pixel size of a Dp, use [Density.toPx]:
 *
 * @sample smallthingz.ui.unit.samples.ToPxSample
 */
@JvmInline
value class Dp(val value: Float) : Comparable<Dp> {
    /** Add two [Dp]s together. */
    inline operator fun plus(other: Dp) = Dp(this.value + other.value)

    /** Subtract a Dp from another one. */
    inline operator fun minus(other: Dp) = Dp(this.value - other.value)

    /** This is the same as multiplying the Dp by -1.0. */
    inline operator fun unaryMinus() = Dp(-value)

    /** Divide a Dp by a scalar. */
    inline operator fun div(other: Float): Dp = Dp(value / other)

    inline operator fun div(other: Int): Dp = Dp(value / other)

    /** Divide by another Dp to get a scalar. */
    inline operator fun div(other: Dp): Float = value / other.value

    /** Multiply a Dp by a scalar. */
    inline operator fun times(other: Float): Dp = Dp(value * other)

    inline operator fun times(other: Int): Dp = Dp(value * other)

    /** Support comparing Dimensions with comparison operators. */
    @OptIn(ExperimentalUnitApi::class)
    override /* TODO: inline */  operator fun compareTo(other: Dp) =
        // Unspecified values should compare false against all other values. This always sets
        // them as comparing == 0, but the equality check fails, so Unspecified < 1.dp == false
        // and 1.dp < Unspecified == false
        if (value.isNaN() || other.value.isNaN()) 0 else value.compareTo(other.value)

    override fun toString() = if (isUnspecified) "Dp.Unspecified" else "$value.dp"

    companion object {
        /**
         * A dimension used to represent a hairline drawing element. Hairline elements take up no
         * space, but will draw a single pixel, independent of the device's resolution and density.
         */
        val Hairline = Dp(0f)

        /** Infinite dp dimension. */
        val Infinity = Dp(Float.POSITIVE_INFINITY)

        /**
         * Constant that means unspecified Dp. Instead of comparing a [Dp] value to this constant,
         * consider using [isSpecified] and [isUnspecified] instead.
         */
        val Unspecified = Dp(Float.NaN)
    }
}

/** `false` when this is [Dp.Unspecified]. */
inline val Dp.isSpecified: Boolean
    get() = !value.isNaN()

/** `true` when this is [Dp.Unspecified]. */
inline val Dp.isUnspecified: Boolean
    get() = value.isNaN()

/**
 * If this [Dp] [isSpecified] then this is returned, otherwise [block] is executed and its result is
 * returned.
 */
inline fun Dp.takeOrElse(block: () -> Dp): Dp = if (isSpecified) this else block()

/** Create a [Dp] using an [Int]: val left = 10 val x = left.dp // -- or -- val y = 10.dp */
inline val Int.dp: Dp
    get() = Dp(this.toFloat())

/** Create a [Dp] using a [Double]: val left = 10.0 val x = left.dp // -- or -- val y = 10.0.dp */
inline val Double.dp: Dp
    get() = Dp(this.toFloat())

/** Create a [Dp] using a [Float]: val left = 10f val x = left.dp // -- or -- val y = 10f.dp */
inline val Float.dp: Dp
    get() = Dp(this)

inline operator fun Float.times(other: Dp) = Dp(this * other.value)

inline operator fun Double.times(other: Dp) = Dp(this.toFloat() * other.value)

inline operator fun Int.times(other: Dp) = Dp(this * other.value)

inline fun min(a: Dp, b: Dp): Dp = Dp(min(a.value, b.value))

inline fun max(a: Dp, b: Dp): Dp = Dp(max(a.value, b.value))

/**
 * Ensures that this value lies in the specified range [minimumValue]..[maximumValue].
 *
 * @return this value if it's in the range, or [minimumValue] if this value is less than
 *   [minimumValue], or [maximumValue] if this value is greater than [maximumValue].
 */
inline fun Dp.coerceIn(minimumValue: Dp, maximumValue: Dp): Dp =
    Dp(value.coerceIn(minimumValue.value, maximumValue.value))

/**
 * Ensures that this value is not less than the specified [minimumValue].
 *
 * @return this value if it's greater than or equal to the [minimumValue] or the [minimumValue]
 *   otherwise.
 */
inline fun Dp.coerceAtLeast(minimumValue: Dp): Dp = Dp(value.coerceAtLeast(minimumValue.value))

/**
 * Ensures that this value is not greater than the specified [maximumValue].
 *
 * @return this value if it's less than or equal to the [maximumValue] or the [maximumValue]
 *   otherwise.
 */
inline fun Dp.coerceAtMost(maximumValue: Dp): Dp = Dp(value.coerceAtMost(maximumValue.value))

/** Return `true` when it is finite or `false` when it is [Dp.Infinity] */
inline val Dp.isFinite: Boolean
    get() = value.fastIsFinite()

/**
 * Linearly interpolate between two [Dp]s.
 *
 * The [fraction] argument represents position on the timeline, with 0.0 meaning that the
 * interpolation has not started, returning [start] (or something equivalent to [start]), 1.0
 * meaning that the interpolation has finished, returning [stop] (or something equivalent to
 * [stop]), and values in between meaning that the interpolation is at the relevant point on the
 * timeline between [start] and [stop]. The interpolation can be extrapolated beyond 0.0 and 1.0, so
 * negative values and values greater than 1.0 are valid.
 */
fun lerp(start: Dp, stop: Dp, fraction: Float): Dp {
    return Dp(lerp(start.value, stop.value, fraction))
}
