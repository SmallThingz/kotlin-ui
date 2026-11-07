package smallthingz.ui.components


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
