package smallthingz.ui.components

import smallthingz.ui.geometry.roundedGroupGeometry
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import smallthingz.ui.geometry.Offset
import smallthingz.ui.geometry.Size
import smallthingz.ui.unit.Density
import smallthingz.ui.input.phase.NativeClickPhaseNode
import smallthingz.ui.input.phase.NativeDragPointerEvent
import smallthingz.ui.input.phase.PhasePointerRoot
import smallthingz.ui.input.phase.NativePointerPass
import smallthingz.ui.scroll.NativeVerticalPhaseBinding
import smallthingz.ui.input.phase.NativeViewPhasePointerNode
import smallthingz.ui.input.phase.NativeViewPointerHitLayout
import smallthingz.ui.input.phase.NativeWindowPointerCoordinates
import smallthingz.ui.input.phase.ViewClickPhaseEndpoint
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/** Direct source modifier/child order for one menu, using the shared per-window phase router. */
internal class NativeMenuPointerBinding<I>(
    private val content: NativeMenuContent<I>,
    private val density: Density,
) : AutoCloseable {
    private var root: PhasePointerRoot? = null
    val geometry = NativeWindowPointerCoordinates { checkNotNull(root) }
    private val config = ViewConfiguration.get(content.context)
    private val vertical = NativeVerticalPhaseBinding(
        content.viewport,
        touchSlop = { config.scaledTouchSlop.toFloat() },
        maximumVelocity = { config.scaledMaximumFlingVelocity.toFloat() },
        parentInterested = { false }, // A sub-panel has no parent scroll/drag connection.
    )
    private val minimum = NativeMinimumTouchTarget { Size(48f*density.density,48f*density.density) }
    private val rowLayouts = IdentityHashMap<NativeMenuRowView<I>, NativeRetainedViewHitLayout>()
    private var reversedRows: List<NativeViewPointerHitLayout> = emptyList()
    private val columnLayout = NativeRetainedViewHitLayout(content.column,geometry,minimum,emptyList(),{ reversedRows })
    private val viewportChildren = listOf(columnLayout)
    private val viewportLayout = NativeRetainedViewHitLayout(
        content.viewport,geometry,minimum,vertical.nodes,{ viewportChildren },
        clip = { local ->
            val extra = (30f*density.density).roundToInt()
            local.x >= -extra && local.x < content.viewport.width+extra &&
                local.y >= 0 && local.y < content.viewport.height
        },
    )
    private val shield = object : NativeViewPhasePointerNode {
        override val view: View get() = content
        override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) = Unit
        override fun cancel() = Unit
    }
    private val surfaceChildren = listOf(viewportLayout)
    private val surfaceLayout = NativeRetainedViewHitLayout(content,geometry,minimum,listOf(shield),{ surfaceChildren },clip = {
        roundedGroupGeometry(content.width.toFloat(),content.height.toFloat(),density.density,18f)
            .contains(it.x,it.y)
    })
    val layout: NativeViewPointerHitLayout = object : NativeViewPointerHitLayout {
        override val placed get() = root?.isAttachedToWindow == true
        override val size get() = Size((root?.width ?: 0).toFloat(),(root?.height ?: 0).toFloat())
        override val minimumTouchTarget get() = Size(48f*density.density,48f*density.density)
        override val nodes = emptyList<NativeViewPhasePointerNode>()
        override val childrenInHitOrder = listOf(surfaceLayout)
        override fun screenToLocal(screen: Offset) = geometry.coordinates(checkNotNull(root)).local(screen)
        override fun withinLayerBounds(local: Offset) = true
    }

    fun bindRoot(root: PhasePointerRoot) {
        check(this.root == null)
        this.root = root
        refreshRows()
    }

    fun refreshRows() {
        val retained = content.column.rows
        rowLayouts.keys.removeAll { it !in retained }
        for (row in retained) {
            row.pointerRoot = root
            rowLayouts.getOrPut(row) {
                val node = NativeClickPhaseNode(row,ViewClickPhaseEndpoint(row.input),vertical::interested)
                NativeRetainedViewHitLayout(row,geometry,minimum,listOf(node),{ emptyList() },clip = {
                    roundedGroupGeometry(row.width.toFloat(),row.height.toFloat(),density.density,12f)
                        .contains(it.x,it.y)
                })
            }
        }
        reversedRows = retained.asReversed().map { checkNotNull(rowLayouts[it]) }
    }

    // Touch receives current ancestor interest explicitly through NativeClickPhaseNode. The
    // ordinary View fallback has no synthetic captured MotionEvent pretending to be that change.
    fun interestedParent(event: MotionEvent): Boolean = false

    override fun close() {
        root?.cancelPointerInput()
        vertical.close()
        rowLayouts.clear()
        reversedRows=emptyList()
    }
}
