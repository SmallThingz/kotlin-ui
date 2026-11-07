package smallthingz.ui.components

import kotlin.math.abs
import kotlin.math.max

/** Interaction is opt-in; disabling dragging leaves explicit keyboard/accessibility actions. */
data class NativeSheetBehavior(
    val enabled: Boolean = false,
    val dragEnabled: Boolean = true,
    val showDragHandle: Boolean = true,
    val skipPartiallyExpanded: Boolean = false,
)

/** Visual handle tokens. The owning component supplies a minimum 48 dp interaction target. */
data class NativeSheetHandleStyle(
    val widthDp: Float = 32f,
    val heightDp: Float = 4f,
    val verticalPaddingDp: Float = 22f,
    val color: Int? = null,
) {
    init {
        require(widthDp.isFinite() && widthDp > 0f) { "Handle width must be finite and positive" }
        require(heightDp.isFinite() && heightDp > 0f) { "Handle height must be finite and positive" }
        require(verticalPaddingDp.isFinite() && verticalPaddingDp >= 0f) {
            "Handle padding must be finite and nonnegative"
        }
    }
}

enum class NativeSheetState { HIDDEN, PARTIALLY_EXPANDED, EXPANDED }

/**
 * Positions are measured downwards in the stationary sheet host, in pixels.
 *
 * Anchor/settling behavior follows the declared Material3 1.4.0 reference, not a claim about
 * an installed APK. Reference source JAR SHA-256:
 * 3e3209422a382566a780e2c1410edd866e4474498098fdbcf942c690ade1ddaa;
 * commonMain/androidx/compose/material3/internal/AnchoredDraggable.kt, computeTarget.
 * Zero-area geometry is representable for layout, but does not establish a visible presentation.
 */
internal data class SheetAnchors private constructor(
    val expanded: Float,
    val partial: Float?,
    val hidden: Float,
) {
    fun offset(state: NativeSheetState): Float? = when (state) {
        NativeSheetState.HIDDEN -> hidden
        NativeSheetState.PARTIALLY_EXPANDED -> partial
        NativeSheetState.EXPANDED -> expanded
    }

    fun initialVisible(): NativeSheetState =
        if (partial == null) NativeSheetState.EXPANDED else NativeSheetState.PARTIALLY_EXPANDED

    /** Reconcile a removed partial anchor, or recover a visible target after cancelled input. */
    fun visibleFallback(state: NativeSheetState): NativeSheetState = when (state) {
        NativeSheetState.HIDDEN -> initialVisible()
        NativeSheetState.PARTIALLY_EXPANDED ->
            if (partial == null) NativeSheetState.EXPANDED else state
        NativeSheetState.EXPANDED -> state
    }

    fun clamp(offset: Float, allowHidden: Boolean): Float {
        require(offset.isFinite()) { "Sheet offset must be finite" }
        return offset.coerceIn(expanded, if (allowHidden) hidden else partial ?: expanded)
    }

    /**
     * The reference commits a reached anchor within half a pixel when its anchored drag ends,
     * before computing the settle target. Keep the controller's rollback state separately.
     */
    fun releaseOrigin(
        offset: Float,
        origin: NativeSheetState,
        allowHidden: Boolean,
    ): NativeSheetState {
        val position = clamp(offset, allowHidden)
        val current = allowedOrigin(origin, allowHidden)
        // Preserve the reference's Hidden, Partial, Expanded insertion order for equal distances.
        var closest = if (allowHidden) NativeSheetState.HIDDEN else initialVisible()
        var distance = abs(position - requireNotNull(this.offset(closest)))
        if (partial != null) {
            val partialDistance = abs(position - partial)
            if (partialDistance < distance) {
                closest = NativeSheetState.PARTIALLY_EXPANDED
                distance = partialDistance
            }
        }
        val expandedDistance = abs(position - expanded)
        if (expandedDistance < distance) {
            closest = NativeSheetState.EXPANDED
            distance = expandedDistance
        }
        return if (distance <= .5f) closest else current
    }

    /**
     * Select an anchor after release. Positive velocity moves towards the host's bottom.
     * [origin] is the reference's current anchor; apply [releaseOrigin] after a completed drag.
     * Cancellation admission remains the controller's responsibility.
     */
    fun settleTarget(
        offset: Float,
        velocity: Float,
        origin: NativeSheetState,
        allowHidden: Boolean,
        positionalThresholdPx: Float,
        velocityThresholdPx: Float,
    ): NativeSheetState {
        require(velocity.isFinite()) { "Sheet velocity must be finite" }
        require(positionalThresholdPx.isFinite() && positionalThresholdPx >= 0f) {
            "Position threshold must be finite and nonnegative"
        }
        require(velocityThresholdPx.isFinite() && velocityThresholdPx >= 0f) {
            "Velocity threshold must be finite and nonnegative"
        }
        val position = clamp(offset, allowHidden)
        val current = allowedOrigin(origin, allowHidden)
        val start = requireNotNull(this.offset(current))
        if (position == start) return current

        return if (position > start) {
            val next = nextDown(position, allowHidden)
            if (velocity >= velocityThresholdPx || position >= abs(start + positionalThresholdPx)) next
            else current
        } else {
            val next = nextUp(position, allowHidden)
            if (velocity <= -velocityThresholdPx || position <= abs(start - positionalThresholdPx)) next
            else current
        }
    }

    private fun allowedOrigin(origin: NativeSheetState, allowHidden: Boolean): NativeSheetState =
        if ((origin == NativeSheetState.HIDDEN && !allowHidden) || offset(origin) == null) {
            visibleFallback(origin)
        } else origin

    private fun nextDown(position: Float, allowHidden: Boolean): NativeSheetState = when {
        position <= expanded -> NativeSheetState.EXPANDED
        partial != null && position <= partial -> NativeSheetState.PARTIALLY_EXPANDED
        allowHidden -> NativeSheetState.HIDDEN
        else -> initialVisible()
    }

    private fun nextUp(position: Float, allowHidden: Boolean): NativeSheetState = when {
        allowHidden && position >= hidden -> NativeSheetState.HIDDEN
        partial != null && position >= partial -> NativeSheetState.PARTIALLY_EXPANDED
        else -> NativeSheetState.EXPANDED
    }

    companion object {
        fun from(viewportHeightPx: Float, sheetHeightPx: Float, skipPartial: Boolean): SheetAnchors {
            require(viewportHeightPx.isFinite() && viewportHeightPx >= 0f) {
                "Viewport height must be finite and nonnegative"
            }
            require(sheetHeightPx.isFinite() && sheetHeightPx >= 0f) {
                "Sheet height must be finite and nonnegative"
            }
            val half = viewportHeightPx / 2f
            return SheetAnchors(
                expanded = max(0f, viewportHeightPx - sheetHeightPx),
                partial = if (viewportHeightPx > 0f && sheetHeightPx > half && !skipPartial) half else null,
                hidden = viewportHeightPx,
            )
        }
    }
}
