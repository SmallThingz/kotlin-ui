package smallthingz.ui.zfm

import smallthingz.ui.components.NativeTextStyle

/** Shared filename/metadata hierarchy for grid rendering and adaptive width measurement. */
data class ZfmFileGridRecipe(
    val titleStyle: NativeTextStyle = NativeTextStyle(12f, weight = 500),
    val detailStyle: NativeTextStyle = NativeTextStyle(11f),
    val metadataGapDp: Int = 2,
) {
    init { require(metadataGapDp >= 0) }

    companion object {
        val Default = ZfmFileGridRecipe()
    }
}
