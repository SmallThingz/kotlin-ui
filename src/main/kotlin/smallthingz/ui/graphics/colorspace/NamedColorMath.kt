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

import kotlin.math.pow

// Fixed named-space inputs. Keep the original Float operation order, including initialization.
internal data class NamedWhitePoint(val x: Float, val y: Float) {
    fun toXyz(): FloatArray = floatArrayOf(x / y, 1.0f, (1f - x - y) / y)
}
internal val NamedD50 = NamedWhitePoint(0.34567f, 0.35850f)
internal val NamedD65 = NamedWhitePoint(0.31271f, 0.32902f)
internal val NamedD50Xyz = floatArrayOf(0.964212f, 1.0f, 0.825188f)
internal val Bradford = floatArrayOf(
    0.8951f, -0.7502f, 0.0389f,
    0.2664f, 1.7135f, -0.0685f,
    -0.1614f, 0.0367f, 1.0296f,
)

internal fun rcpResponse(x: Double, a: Double, b: Double, c: Double, d: Double, inverseGamma: Double): Double {
    return if (x >= d * c) (x.pow(inverseGamma) - b) / a else x / c
}

internal fun response(x: Double, a: Double, b: Double, c: Double, d: Double, g: Double): Double {
    return if (x >= d) (a * x + b).pow(g) else c * x
}

/**
 * Inverts a 3x3 matrix. This method assumes the matrix is invertible.
 *
 * @param m A 3x3 matrix as a non-null array of 9 floats
 * @return A new array of 9 floats containing the inverse of the input matrix
 */
internal fun inverse3x3(m: FloatArray): FloatArray {
    val a = m[0]
    val b = m[3]
    val c = m[6]
    val d = m[1]
    val e = m[4]
    val f = m[7]
    val g = m[2]
    val h = m[5]
    val i = m[8]

    val xA = e * i - f * h
    val xB = f * g - d * i
    val xC = d * h - e * g

    val det = a * xA + b * xB + c * xC

    val inverted = FloatArray(m.size)
    inverted[0] = xA / det
    inverted[1] = xB / det
    inverted[2] = xC / det
    inverted[3] = (c * h - b * i) / det
    inverted[4] = (a * i - c * g) / det
    inverted[5] = (b * g - a * h) / det
    inverted[6] = (b * f - c * e) / det
    inverted[7] = (c * d - a * f) / det
    inverted[8] = (a * e - b * d) / det
    return inverted
}

/**
 * Multiplies two 3x3 matrices, represented as non-null arrays of 9 floats.
 *
 * @param lhs 3x3 matrix, as a non-null array of 9 floats
 * @param rhs 3x3 matrix, as a non-null array of 9 floats
 * @return A new array of 9 floats containing the result of the multiplication of rhs by lhs
 */
internal fun mul3x3(lhs: FloatArray, rhs: FloatArray): FloatArray {
    val r = FloatArray(9)
    // Compiler hint to bypass extra bound checks
    if (lhs.size < 9) return r
    if (rhs.size < 9) return r
    r[0] = lhs[0] * rhs[0] + lhs[3] * rhs[1] + lhs[6] * rhs[2]
    r[1] = lhs[1] * rhs[0] + lhs[4] * rhs[1] + lhs[7] * rhs[2]
    r[2] = lhs[2] * rhs[0] + lhs[5] * rhs[1] + lhs[8] * rhs[2]
    r[3] = lhs[0] * rhs[3] + lhs[3] * rhs[4] + lhs[6] * rhs[5]
    r[4] = lhs[1] * rhs[3] + lhs[4] * rhs[4] + lhs[7] * rhs[5]
    r[5] = lhs[2] * rhs[3] + lhs[5] * rhs[4] + lhs[8] * rhs[5]
    r[6] = lhs[0] * rhs[6] + lhs[3] * rhs[7] + lhs[6] * rhs[8]
    r[7] = lhs[1] * rhs[6] + lhs[4] * rhs[7] + lhs[7] * rhs[8]
    r[8] = lhs[2] * rhs[6] + lhs[5] * rhs[7] + lhs[8] * rhs[8]
    return r
}

/**
 * Multiplies a vector of 3 components by a 3x3 matrix and stores the result in the input vector.
 *
 * @param lhs 3x3 matrix, as a non-null array of 9 floats
 * @param rhs Vector of 3 components, as a non-null array of 3 floats
 * @return The array of 3 passed as the [rhs] parameter
 */
internal fun mul3x3Float3(lhs: FloatArray, rhs: FloatArray): FloatArray {
    // Compiler hint to bypass extra bounds checks
    if (lhs.size < 9) return rhs
    if (rhs.size < 3) return rhs

    val r0 = rhs[0]
    val r1 = rhs[1]
    val r2 = rhs[2]
    rhs[0] = lhs[0] * r0 + lhs[3] * r1 + lhs[6] * r2
    rhs[1] = lhs[1] * r0 + lhs[4] * r1 + lhs[7] * r2
    rhs[2] = lhs[2] * r0 + lhs[5] * r1 + lhs[8] * r2
    return rhs
}

/**
 * Multiplies a vector of 3 components by a 3x3 matrix and returns the first element.
 *
 * @param lhs 3x3 matrix, as a non-null array of 9 floats
 * @param r0: The first element of the vector
 * @param r1: The second element of the vector
 * @param r2: The third element of the vector
 * @return The first element of the resulting multiplication.
 */
@Suppress("NOTHING_TO_INLINE", "KotlinRedundantDiagnosticSuppress")
internal inline fun mul3x3Float3_0(lhs: FloatArray, r0: Float, r1: Float, r2: Float): Float {
    return lhs[0] * r0 + lhs[3] * r1 + lhs[6] * r2
}

/**
 * Multiplies a vector of 3 components by a 3x3 matrix and returns the second element.
 *
 * @param lhs 3x3 matrix, as a non-null array of 9 floats
 * @param r0: The first element of the vector
 * @param r1: The second element of the vector
 * @param r2: The third element of the vector
 * @return The second element of the resulting multiplication.
 */
@Suppress("NOTHING_TO_INLINE", "KotlinRedundantDiagnosticSuppress")
internal inline fun mul3x3Float3_1(lhs: FloatArray, r0: Float, r1: Float, r2: Float): Float {
    return lhs[1] * r0 + lhs[4] * r1 + lhs[7] * r2
}

/**
 * Multiplies a vector of 3 components by a 3x3 matrix and returns the third element.
 *
 * @param lhs 3x3 matrix, as a non-null array of 9 floats
 * @param r0: The first element of the vector
 * @param r1: The second element of the vector
 * @param r2: The third element of the vector
 * @return The third element of the resulting multiplication.
 */
@Suppress("NOTHING_TO_INLINE", "KotlinRedundantDiagnosticSuppress")
internal inline fun mul3x3Float3_2(lhs: FloatArray, r0: Float, r1: Float, r2: Float): Float {
    return lhs[2] * r0 + lhs[5] * r1 + lhs[8] * r2
}

/**
 * Multiplies a diagonal 3x3 matrix lhs, represented as an array of 3 floats, by a 3x3 matrix
 * represented as an array of 9 floats.
 *
 * @param lhs Diagonal 3x3 matrix, as a non-null array of 3 floats
 * @param rhs 3x3 matrix, as a non-null array of 9 floats
 * @return A new array of 9 floats containing the result of the multiplication of [rhs] by [lhs].
 */
internal fun mul3x3Diag(lhs: FloatArray, rhs: FloatArray): FloatArray {
    return floatArrayOf(
        lhs[0] * rhs[0],
        lhs[1] * rhs[1],
        lhs[2] * rhs[2],
        lhs[0] * rhs[3],
        lhs[1] * rhs[4],
        lhs[2] * rhs[5],
        lhs[0] * rhs[6],
        lhs[1] * rhs[7],
        lhs[2] * rhs[8],
    )
}

/**
 * Computes the chromatic adaptation transform from the specified source white point to the
 * specified destination white point.
 *
 * The transform is computed using the von Kries method, described in more details in the
 * original Bradford transform. This specialization uses the retained fixed Bradford matrix.
 *
 * @param matrix The adaptation matrix
 * @param srcWhitePoint The white point to adapt from, *will be modified*
 * @param dstWhitePoint The white point to adapt to, *will be modified*
 * @return A 3x3 matrix as a non-null array of 9 floats
 */
internal fun chromaticAdaptation(
    matrix: FloatArray,
    srcWhitePoint: FloatArray,
    dstWhitePoint: FloatArray,
): FloatArray {
    val srcLMS = mul3x3Float3(matrix, srcWhitePoint)
    val dstLMS = mul3x3Float3(matrix, dstWhitePoint)
    // LMS is a diagonal matrix stored as a float[3]
    val LMS = floatArrayOf(dstLMS[0] / srcLMS[0], dstLMS[1] / srcLMS[1], dstLMS[2] / srcLMS[2])
    return mul3x3(inverse3x3(matrix), mul3x3Diag(LMS, matrix))
}

internal fun computeXYZMatrix(primaries: FloatArray, whitePoint: NamedWhitePoint): FloatArray {
            val rx = primaries[0]
            val ry = primaries[1]
            val gx = primaries[2]
            val gy = primaries[3]
            val bx = primaries[4]
            val by = primaries[5]
            val wx = whitePoint.x
            val wy = whitePoint.y

            val oneRxRy = (1 - rx) / ry
            val oneGxGy = (1 - gx) / gy
            val oneBxBy = (1 - bx) / by
            val oneWxWy = (1 - wx) / wy

            val rxRy = rx / ry
            val gxGy = gx / gy
            val bxBy = bx / by
            val wxWy = wx / wy

            val byNumerator =
                (oneWxWy - oneRxRy) * (gxGy - rxRy) - (wxWy - rxRy) * (oneGxGy - oneRxRy)
            val byDenominator =
                (oneBxBy - oneRxRy) * (gxGy - rxRy) - (bxBy - rxRy) * (oneGxGy - oneRxRy)
            val bY = byNumerator / byDenominator
            val gY = (wxWy - rxRy - bY * (bxBy - rxRy)) / (gxGy - rxRy)
            val rY = 1f - gY - bY

            val rYRy = rY / ry
            val gYGy = gY / gy
            val bYBy = bY / by

            return floatArrayOf(
                rYRy * rx,
                rY,
                rYRy * (1f - rx - ry),
                gYGy * gx,
                gY,
                gYGy * (1f - gx - gy),
                bYBy * bx,
                bY,
                bYBy * (1f - bx - by),
            )
        }
