// SPDX-License-Identifier: Apache-2.0
package smallthingz.ui.input.phase

import android.content.Context
import smallthingz.ui.geometry.Offset

/**
 * A retained phase-input subtree that also participates in its parent's hit path.
 * The same nodes handle standalone dispatch and parent dispatch.
 */
open class NativeNestedPointerRoot(
    context: Context,
    private val geometry: NativeWindowPointerCoordinates,
    private val ownedLayout: NativeViewPointerHitLayout,
) : PhasePointerRoot(context, geometry::rootToScreen, geometry::coordinates,
    ownedLayout, geometry::begin) {
    fun parentPointerLayout(windowPosition: Offset): NativeViewPointerHitLayout {
        geometry.beginFromParent(windowPosition)
        return ownedLayout
    }
}
