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

package smallthingz.ui.unit

import smallthingz.ui.geometry.Rect
import smallthingz.ui.util.fastRoundToInt
import smallthingz.ui.util.midpoint

/**
 * An immutable, 2D, axis-aligned, integer bounds rectangle whose coordinates are relative to a
 * given origin.
 */
@Suppress("DataClassDefinition")
data class IntRect(
    /** The offset of the left edge of this rectangle from the x axis. */
    val left: Int,

    /** The offset of the top edge of this rectangle from the y axis. */
    val top: Int,

    /** The offset of the right edge of this rectangle from the x axis. */
    val right: Int,

    /** The offset of the bottom edge of this rectangle from the y axis. */
    val bottom: Int,
) {
    companion object {

        /** A rectangle with left, top, right, and bottom edges all at zero. */
        val Zero: IntRect = IntRect(0, 0, 0, 0)
    }

    /** The distance between the left and right edges of this rectangle. */
    val width: Int
        get() {
            return right - left
        }

    /** The distance between the top and bottom edges of this rectangle. */
    val height: Int
        get() {
            return bottom - top
        }

    /**
     * Whether `other` has a nonzero area of overlap with this rectangle.
     * Empty or inverted rectangles do not overlap.
     */
    fun overlaps(other: IntRect): Boolean {
        return kotlin.math.max(left, other.left) < kotlin.math.min(right, other.right) &&
            kotlin.math.max(top, other.top) < kotlin.math.min(bottom, other.bottom)
    }

    /** The offset to the center of the top edge of this rectangle. */
    val topCenter: IntOffset
        get() = IntOffset(midpoint(left, right), top)

    /** The offset to the center of the left edge of this rectangle. */
    val centerLeft: IntOffset
        get() = IntOffset(left, midpoint(top, bottom))

    /**
     * The offset to the point halfway between the left and right and the top and bottom edges of
     * this rectangle.
     *
     * See also [IntSize.center].
     */
    val center: IntOffset
        get() = IntOffset(midpoint(left, right), midpoint(top, bottom))

    /** The offset to the center of the right edge of this rectangle. */
    val centerRight: IntOffset
        get() = IntOffset(right, midpoint(top, bottom))

    /** The offset to the center of the bottom edge of this rectangle. */
    val bottomCenter: IntOffset
        get() {
            return IntOffset(midpoint(left, right), bottom)
        }

    override fun toString() = "IntRect.fromLTRB(" + "$left, " + "$top, " + "$right, " + "$bottom)"
}

/** Rounds a [Rect] to an [IntRect] */
fun Rect.roundToIntRect(): IntRect =
    IntRect(
        left = left.fastRoundToInt(),
        top = top.fastRoundToInt(),
        right = right.fastRoundToInt(),
        bottom = bottom.fastRoundToInt(),
    )
