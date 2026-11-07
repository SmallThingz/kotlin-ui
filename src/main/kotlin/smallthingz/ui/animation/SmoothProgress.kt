// SPDX-License-Identifier: Apache-2.0
package smallthingz.ui.animation

import smallthingz.ui.animation.core.SpringSpec

/** Critically damped progress interpolation retaining velocity across successive publications. */
fun smoothProgressSpring(initialValue: Float = 0f) = SpringValue.float(initialValue,
    SpringSpec(dampingRatio = 1f, stiffness = 400f, visibilityThreshold = .0005f))
