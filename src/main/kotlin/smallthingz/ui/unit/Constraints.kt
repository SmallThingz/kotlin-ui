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

@file:Suppress("NOTHING_TO_INLINE", "KotlinRedundantDiagnosticSuppress")

package smallthingz.ui.unit

import smallthingz.ui.util.fastCoerceIn
import smallthingz.ui.util.requirePrecondition
import kotlin.jvm.JvmInline
import kotlin.math.min

/**
 * Pixel bounds for native text measurement.
 *
 * [Constraints] uses a [Long] to represent four values, [minWidth], [minHeight], [maxWidth], and
 * [maxHeight]. The range of the values varies to allow for at most 256K in one dimension. There are
 * four possible maximum ranges, 13 bits/18 bits, and 15 bits/16 bits for either width or height,
 * depending on the needs. For example, a width could range up to 18 bits and the height up to 13
 * bits. Alternatively, the width could range up to 16 bits and the height up to 15 bits. The height
 * and width requirements can be reversed, with a height of up to 18 bits and width of 13 bits or
 * height of 16 bits and width of 15 bits. Any constraints exceeding this range will fail.
 */
@JvmInline
value class Constraints(@PublishedApi internal val value: Long) {
    /**
     * Selects the width/height bit allocation described by [FocusMask].
     */
    private inline val focusIndex
        get() = (value and FocusMask).toInt()

    /** The minimum width that the measurement can take, in pixels. */
    val minWidth: Int
        get() {
            val mask = widthMask(indexToBitOffset(focusIndex))
            return ((value shr 2).toInt() and mask)
        }

    /**
     * The maximum width that the measurement can take, in pixels. This will either be a positive
     * value greater than or equal to [minWidth] or [Constraints.Infinity].
     */
    val maxWidth: Int
        get() {
            val mask = widthMask(indexToBitOffset(focusIndex))
            val width = ((value shr 33).toInt() and mask)
            return if (width == 0) Infinity else width - 1
        }

    /** The minimum height that the measurement can take, in pixels. */
    val minHeight: Int
        get() {
            val bitOffset = indexToBitOffset(focusIndex)
            val mask = heightMask(bitOffset)
            val offset = minHeightOffsets(bitOffset)
            return (value shr offset).toInt() and mask
        }

    /**
     * The maximum height that the measurement can take, in pixels. This will either be a positive
     * value greater than or equal to [minHeight] or [Constraints.Infinity].
     */
    val maxHeight: Int
        get() {
            val bitOffset = indexToBitOffset(focusIndex)
            val mask = heightMask(bitOffset)
            val offset = minHeightOffsets(bitOffset) + 31
            val height = (value shr offset).toInt() and mask
            return if (height == 0) Infinity else height - 1
        }

    /** Whether [maxWidth] is finite. */
    val hasBoundedWidth: Boolean
        get() {
            val mask = widthMask(indexToBitOffset(focusIndex))
            return ((value shr 33).toInt() and mask) != 0
        }

    /** Whether [maxHeight] is finite. */
    val hasBoundedHeight: Boolean
        get() {
            val bitOffset = indexToBitOffset(focusIndex)
            val mask = heightMask(bitOffset)
            val offset = minHeightOffsets(bitOffset) + 31
            return ((value shr offset).toInt() and mask) != 0
        }

    override fun toString(): String {
        val maxWidth = maxWidth
        val maxWidthStr = if (maxWidth == Infinity) "Infinity" else maxWidth.toString()
        val maxHeight = maxHeight
        val maxHeightStr = if (maxHeight == Infinity) "Infinity" else maxHeight.toString()
        return "Constraints(minWidth = $minWidth, maxWidth = $maxWidthStr, " +
            "minHeight = $minHeight, maxHeight = $maxHeightStr)"
    }

    companion object {
        /** An unbounded maximum width or height. */
        const val Infinity = Int.MAX_VALUE

        /** Fits the bounds into the packed value, allocating width bits before height bits. */
        fun fitPrioritizingWidth(
            minWidth: Int,
            maxWidth: Int,
            minHeight: Int,
            maxHeight: Int,
        ): Constraints {
            val minW = min(minWidth, MaxFocusMask - 1)
            val maxW =
                if (maxWidth == Infinity) {
                    Infinity
                } else {
                    min(maxWidth, MaxFocusMask - 1)
                }
            val consumed = if (maxW == Infinity) minW else maxW
            val maxAllowed = maxAllowedForSize(consumed)
            val maxH = if (maxHeight == Infinity) Infinity else min(maxAllowed, maxHeight)
            val minH = min(maxAllowed, minHeight)
            return Constraints(minW, maxW, minH, maxH)
        }
    }
}

// Redefinition of Constraints.Infinity to bypass the companion object
private const val Infinity = Int.MAX_VALUE

// The low two bits select how the other 62 bits are divided into two 31-bit halves.
// Each half stores width followed by height, with the same allocation in both halves.
// Index  Offset  Width bits/mask  Height bits/mask  Min height shift
//   0       0       13 / 0x1fff     18 / 0x3ffff          15
//   1       2       15 / 0x7fff     16 / 0xffff           17
//   2       3       16 / 0xffff     15 / 0x7fff           18
//   3       5       18 / 0x3ffff    13 / 0x1fff           20
// The low half encodes minimums. The upper half encodes maximums as value + 1,
// reserving zero for infinity. Thus the capacity for a finite value is its mask minus one.
private const val FocusMask = 0x3L

/** The number of bits used for the focused dimension when there is minimal focus. */
private const val MinFocusBits = 16
private const val MaxAllowedForMinFocusBits = (1 shl (31 - MinFocusBits)) - 2

/** The mask to use for the focused dimension when there is minimal focus. */
private const val MinFocusMask = 0xFFFF // 64K (16 bits)

/** The number of bits used for the non-focused dimension when there is minimal focus. */
private const val MinNonFocusBits = 15
private const val MaxAllowedForMinNonFocusBits = (1 shl (31 - MinNonFocusBits)) - 2

/** The mask to use for the non-focused dimension when there is minimal focus. */
private const val MinNonFocusMask = 0x7FFF // 32K (15 bits)

/** The number of bits to use for the focused dimension when there is maximal focus. */
private const val MaxFocusBits = 18
private const val MaxAllowedForMaxFocusBits = (1 shl (31 - MaxFocusBits)) - 2

/** The mask to use for the focused dimension when there is maximal focus. */
private const val MaxFocusMask = 0x3FFFF // 256K-1 (18 bits)

/** The number of bits to use for the non-focused dimension when there is maximal focus. */
private const val MaxNonFocusBits = 13
private const val MaxAllowedForMaxNonFocusBits = (1 shl (31 - MaxNonFocusBits)) - 2

/** The mask to use for the non-focused dimension when there is maximal focus. */
private const val MaxNonFocusMask = 0x1FFF // 8K (13 bits)

// Wrap those throws in functions to avoid inlining the string building at the call sites
// Keep internal for codegen
internal fun throwInvalidConstraintException(widthVal: Int, heightVal: Int) {
    throw IllegalArgumentException(
        "Can't represent a width of $widthVal and height of $heightVal in Constraints"
    )
}

// Keep internal for codegen
internal fun throwInvalidConstraintsSizeException(size: Int): Nothing {
    throw IllegalArgumentException("Can't represent a size of $size in Constraints")
}

/** Creates a [Constraints], only checking that the values fit in the packed Long. */
internal fun createConstraints(
    minWidth: Int,
    maxWidth: Int,
    minHeight: Int,
    maxHeight: Int,
): Constraints {
    val heightVal = if (maxHeight == Infinity) minHeight else maxHeight
    val heightBits = bitsNeedForSizeUnchecked(heightVal)

    val widthVal = if (maxWidth == Infinity) minWidth else maxWidth
    val widthBits = bitsNeedForSizeUnchecked(widthVal)

    if (widthBits + heightBits > 31) {
        throwInvalidConstraintException(widthVal, heightVal)
    }

    // Same as if (maxWidth == Infinity) 0 else maxWidth + 1 but branchless
    // in DEX and saves 2 instructions on aarch64. Relies on integer overflow
    // since Infinity == Int.MAX_VALUE
    var maxWidthValue = maxWidth + 1
    maxWidthValue = maxWidthValue and (maxWidthValue shr 31).inv()

    var maxHeightValue = maxHeight + 1
    maxHeightValue = maxHeightValue and (maxHeightValue shr 31).inv()

    // widthBits can be one of 13, 15, 16, or 18
    // by subtracting 13 we obtain a bit offset as expected by bitOffsetToIndex()
    val bitOffset = widthBits - 13
    val focus = bitOffsetToIndex(bitOffset)

    val minHeightOffset = minHeightOffsets(bitOffset)
    val maxHeightOffset = minHeightOffset + 31

    val value =
        focus.toLong() or
            (minWidth.toLong() shl 2) or
            (maxWidthValue.toLong() shl 33) or
            (minHeight.toLong() shl minHeightOffset) or
            (maxHeightValue.toLong() shl maxHeightOffset)
    return Constraints(value)
}

internal fun bitsNeedForSizeUnchecked(size: Int): Int {
    return when {
        size < MaxNonFocusMask -> MaxNonFocusBits
        size < MinNonFocusMask -> MinNonFocusBits
        size < MinFocusMask -> MinFocusBits
        size < MaxFocusMask -> MaxFocusBits
        else -> 255
    }
}

private inline fun maxAllowedForSize(size: Int): Int {
    return when {
        size < MaxNonFocusMask -> MaxAllowedForMaxNonFocusBits
        size < MinNonFocusMask -> MaxAllowedForMinNonFocusBits
        size < MinFocusMask -> MaxAllowedForMinFocusBits
        size < MaxFocusMask -> MaxAllowedForMaxFocusBits
        else -> throwInvalidConstraintsSizeException(size)
    }
}

/**
 * Create a [Constraints]. Both minimum dimensions must be nonnegative. [maxWidth] and
 * [maxHeight] must be greater than or equal to [minWidth] and [minHeight], respectively, or
 * [Constraints.Infinity].
 */
fun Constraints(
    minWidth: Int = 0,
    maxWidth: Int = Infinity,
    minHeight: Int = 0,
    maxHeight: Int = Infinity,
): Constraints {
    requirePrecondition(
        (maxWidth >= minWidth) and (maxHeight >= minHeight) and (minWidth >= 0) and (minHeight >= 0)
    ) {
        """
        maxWidth must be >= than minWidth,
        maxHeight must be >= than minHeight,
        minWidth and minHeight must be >= 0
        """
            .trimIndent()
    }
    return createConstraints(minWidth, maxWidth, minHeight, maxHeight)
}

/** Takes a size and returns the closest size to it that satisfies the constraints. */
fun Constraints.constrain(size: IntSize) =
    IntSize(
        width = size.width.fastCoerceIn(minWidth, maxWidth),
        height = size.height.fastCoerceIn(minHeight, maxHeight),
    )

/**
 * Maps an index (MaxFocusHeight, MinFocusHeight, MinFocusWidth, MaxFocusWidth) to a "bit offset":
 * 0, 2, 3 or 5. The low index bit contributes two extra width bits and the high index bit
 * contributes three. Adding this offset to the 13-bit base width and subtracting it from the
 * 18-bit base height yields the mask sizes without a lookup table.
 */
private inline fun indexToBitOffset(index: Int) =
    (index and 0x1 shl 1) + ((index and 0x2 shr 1) * 3)

/** Maps a bit offset (0, 2, 3, or 5) to an index. It's the inverse of indexToBitOffset() */
private inline fun bitOffsetToIndex(bits: Int) = (bits shr 1) + (bits and 0x1)

/**
 * The minimum height follows two index bits and `13 + bitOffset` width bits. Add 31 to
 * either dimension's minimum shift to reach its maximum. Width shifts are 2 and 33.
 */
private inline fun minHeightOffsets(bitOffset: Int) = 15 + bitOffset

/** The mask to use for both minimum and maximum width. */
private inline fun widthMask(bitOffset: Int) = (1 shl (13 + bitOffset)) - 1

/** The mask to use for both minimum and maximum height. */
private inline fun heightMask(bitOffset: Int) = (1 shl (18 - bitOffset)) - 1
