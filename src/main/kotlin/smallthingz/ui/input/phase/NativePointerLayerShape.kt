package smallthingz.ui.input.phase

/** Read-only actual layer geometry for a concrete View participating in shared phase hit testing. */
interface NativePointerLayerShape {
    fun containsPointerLayer(x: Float, y: Float): Boolean
}
