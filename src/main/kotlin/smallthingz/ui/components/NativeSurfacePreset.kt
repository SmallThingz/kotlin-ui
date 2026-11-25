package smallthingz.ui.components

/** Paint policy carried through surface/control palettes, independent of color-scheme resolution. */
sealed interface NativeSurfacePreset {
    val backgroundGrain: Float
    val foregroundGrain: Float
    /** Null preserves each control's existing Material outline. */
    val borderWidthDp: Float? get() = null
    val borderColor: Int? get() = null
}

/** Opt into cached grain without changing fills, content colors or outline geometry. */
data class NativeGrainPreset(
    override val backgroundGrain: Float,
    override val foregroundGrain: Float,
) : NativeSurfacePreset {
    init {
        require(backgroundGrain in 0f..1f && foregroundGrain in 0f..1f)
    }
}
