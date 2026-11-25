package smallthingz.ui.components

/** Paint policy carried through surface/control palettes, independent of color-scheme resolution. */
sealed interface NativeSurfacePreset {
    /** Background grain intensity in 0–1. Zero disables grain when this value is selected. */
    val backgroundGrain: Float

    /** Foreground grain intensity in 0–1. Zero disables grain when this value is selected. */
    val foregroundGrain: Float

    /** Null keeps the control's default outline width. */
    val borderWidthDp: Float? get() = null

    /** Null keeps the control's default outline color. */
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
