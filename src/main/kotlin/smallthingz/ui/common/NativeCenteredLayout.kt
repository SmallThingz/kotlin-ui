package smallthingz.ui.common

import kotlin.math.roundToInt

/** Foundation 1.11.2 Arrangement.Center places physical-order children before rounding. */
fun nativeCenteredRowChildOffset(totalSize: Int, usedSize: Int, beforeChildSize: Int): Int =
    ((totalSize - usedSize) / 2f + beforeChildSize).roundToInt()
