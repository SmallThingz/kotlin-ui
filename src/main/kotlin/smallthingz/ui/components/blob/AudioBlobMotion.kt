// SPDX-License-Identifier: Apache-2.0
package smallthingz.ui.components.blob

/** Smoothstep gives quiet normalized activity a soft onset and reaches full motion at 1. */
internal fun audioBlobMotionEnergy(activity: Float, silence: Float): Float {
    val level = activity.coerceIn(0f, 1f)
    return level * level * (3f - 2f * level) * (1f - .92f * silence.coerceIn(0f, 1f))
}

/** Broad liquid folds and a smaller treble ripple, bounded inside the existing render footprint. */
internal fun audioBlobFold(h3: Float, h7: Float, energy: Float, low: Float, high: Float): Float =
    energy * (h3 * (.035f + .035f * low) + h7 * (.008f + .018f * high))

