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

@file:Suppress("NOTHING_TO_INLINE", "KotlinRedundantDiagnosticSuppress")

package smallthingz.ui.graphics

import smallthingz.ui.geometry.Offset
import smallthingz.ui.geometry.Rect
import smallthingz.ui.util.fastIsFinite
import smallthingz.ui.util.fastMaxOf
import smallthingz.ui.util.fastMinOf

// The 16-slot layout and explicit length checks preserve the source float operation order
// and give the AOT compiler a bound for the indexed accesses.
@kotlin.jvm.JvmInline
value class Matrix(
    val values: FloatArray =
        floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
) {
    inline operator fun get(row: Int, column: Int) = values[(row * 4) + column]

    inline operator fun set(row: Int, column: Int, v: Float) {
        values[(row * 4) + column] = v
    }

    /** Does the 3D transform on [point] and returns the `x` and `y` values in an [Offset]. */
    fun map(point: Offset): Offset {
        // See top-level comment
        if (values.size < 16) return point

        val v00 = this[0, 0]
        val v01 = this[0, 1]
        val v03 = this[0, 3]
        val v10 = this[1, 0]
        val v11 = this[1, 1]
        val v13 = this[1, 3]
        val v30 = this[3, 0]
        val v31 = this[3, 1]
        val v33 = this[3, 3]

        val x = point.x
        val y = point.y
        val z = v03 * x + v13 * y + v33
        val inverseZ = 1 / z
        val pZ = if (inverseZ.fastIsFinite()) inverseZ else 0f

        return Offset(x = pZ * (v00 * x + v10 * y + v30), y = pZ * (v01 * x + v11 * y + v31))
    }

    /** Does a 3D transform on [rect] and returns its bounds after the transform. */
    fun map(rect: Rect): Rect {
        // See top-level comment
        if (values.size < 16) return rect
        val l = rect.left
        val t = rect.top
        val r = rect.right
        val b = rect.bottom
        val v00 = this[0, 0]
        val v01 = this[0, 1]
        val v03 = this[0, 3]
        val v10 = this[1, 0]
        val v11 = this[1, 1]
        val v13 = this[1, 3]
        val v30 = this[3, 0]
        val v31 = this[3, 1]
        val v33 = this[3, 3]

        var x = l
        var y = t
        var inverseZ = 1.0f / (v03 * x + v13 * y + v33)
        var pZ = if (inverseZ.fastIsFinite()) inverseZ else 0f
        val x0 = pZ * (v00 * x + v10 * y + v30)
        val y0 = pZ * (v01 * x + v11 * y + v31)

        x = l
        y = b
        inverseZ = 1.0f / (v03 * x + v13 * y + v33)
        pZ = if (inverseZ.fastIsFinite()) inverseZ else 0f
        val x1 = pZ * (v00 * x + v10 * y + v30)
        val y1 = pZ * (v01 * x + v11 * y + v31)

        x = r
        y = t
        inverseZ = 1.0f / (v03 * x + v13 * y + v33)
        pZ = if (inverseZ.fastIsFinite()) inverseZ else 0f
        val x2 = pZ * (v00 * x + v10 * y + v30)
        val y2 = pZ * (v01 * x + v11 * y + v31)

        x = r
        y = b
        inverseZ = 1.0f / (v03 * x + v13 * y + v33)
        pZ = if (inverseZ.fastIsFinite()) inverseZ else 0f
        val x3 = pZ * (v00 * x + v10 * y + v30)
        val y3 = pZ * (v01 * x + v11 * y + v31)

        return Rect(
            fastMinOf(x0, x1, x2, x3),
            fastMinOf(y0, y1, y2, y3),
            fastMaxOf(x0, x1, x2, x3),
            fastMaxOf(y0, y1, y2, y3),
        )
    }

    /** Multiply this matrix by [m] and assign the result to this matrix. */
    operator fun timesAssign(m: Matrix) {
        // See top-level comment
        val v = values
        if (v.size < 16) return
        if (m.values.size < 16) return

        val v00 = dot(this, 0, m, 0)
        val v01 = dot(this, 0, m, 1)
        val v02 = dot(this, 0, m, 2)
        val v03 = dot(this, 0, m, 3)
        val v10 = dot(this, 1, m, 0)
        val v11 = dot(this, 1, m, 1)
        val v12 = dot(this, 1, m, 2)
        val v13 = dot(this, 1, m, 3)
        val v20 = dot(this, 2, m, 0)
        val v21 = dot(this, 2, m, 1)
        val v22 = dot(this, 2, m, 2)
        val v23 = dot(this, 2, m, 3)
        val v30 = dot(this, 3, m, 0)
        val v31 = dot(this, 3, m, 1)
        val v32 = dot(this, 3, m, 2)
        val v33 = dot(this, 3, m, 3)

        v[0] = v00
        v[1] = v01
        v[2] = v02
        v[3] = v03
        v[4] = v10
        v[5] = v11
        v[6] = v12
        v[7] = v13
        v[8] = v20
        v[9] = v21
        v[10] = v22
        v[11] = v23
        v[12] = v30
        v[13] = v31
        v[14] = v32
        v[15] = v33
    }

    /** Resets the `this` to the identity matrix. */
    fun reset() {
        // See top-level comment
        val v = values
        if (v.size < 16) return
        v[0] = 1f
        v[1] = 0f
        v[2] = 0f
        v[3] = 0f
        v[4] = 0f
        v[5] = 1f
        v[6] = 0f
        v[7] = 0f
        v[8] = 0f
        v[9] = 0f
        v[10] = 1f
        v[11] = 0f
        v[12] = 0f
        v[13] = 0f
        v[14] = 0f
        v[15] = 1f
    }

    /** Sets the entire matrix to the matrix in [matrix]. */
    fun setFrom(matrix: Matrix) {
        val src = values
        val dst = matrix.values

        // See top-level comment
        if (src.size < 16) return
        if (dst.size < 16) return

        src[0] = dst[0]
        src[1] = dst[1]
        src[2] = dst[2]
        src[3] = dst[3]
        src[4] = dst[4]
        src[5] = dst[5]
        src[6] = dst[6]
        src[7] = dst[7]
        src[8] = dst[8]
        src[9] = dst[9]
        src[10] = dst[10]
        src[11] = dst[11]
        src[12] = dst[12]
        src[13] = dst[13]
        src[14] = dst[14]
        src[15] = dst[15]
    }

    /** Applies a translation before the existing transform. */
    fun translate(x: Float = 0f, y: Float = 0f, z: Float = 0f) {
        // See top-level comment
        if (values.size < 16) return
        val t1 = this[0, 0] * x + this[1, 0] * y + this[2, 0] * z + this[3, 0]
        val t2 = this[0, 1] * x + this[1, 1] * y + this[2, 1] * z + this[3, 1]
        val t3 = this[0, 2] * x + this[1, 2] * y + this[2, 2] * z + this[3, 2]
        val t4 = this[0, 3] * x + this[1, 3] * y + this[2, 3] * z + this[3, 3]
        this[3, 0] = t1
        this[3, 1] = t2
        this[3, 2] = t3
        this[3, 3] = t4
    }

    companion object {
        /** Index of the flattened array that represents the scale factor along the X axis */
        const val ScaleX = 0

        /** Index of the flattened array that represents the skew factor along the Y axis */
        const val SkewY = 1

        /** Index of the flattened array that represents the perspective factor along the X axis */
        const val Perspective0 = 3

        /** Index of the flattened array that represents the skew factor along the X axis */
        const val SkewX = 4

        /** Index of the flattened array that represents the scale factor along the Y axis */
        const val ScaleY = 5

        /** Index of the flattened array that represents the perspective factor along the Y axis */
        const val Perspective1 = 7

        /** Index of the flattened array that represents the scale factor along the Z axis */
        const val ScaleZ = 10

        /** Index of the flattened array that represents the translation along the X axis */
        const val TranslateX = 12

        /** Index of the flattened array that represents the translation along the Y axis */
        const val TranslateY = 13

        /** Index of the flattened array that represents the perspective factor along the Z axis */
        const val Perspective2 = 15
    }
}

private inline fun dot(m1: Matrix, row: Int, m2: Matrix, column: Int): Float {
    return m1[row, 0] * m2[0, column] +
        m1[row, 1] * m2[1, column] +
        m1[row, 2] * m2[2, column] +
        m1[row, 3] * m2[3, column]
}
