package smallthingz.ui.reverb

/** Pixel budget after the scroll viewport's padding and one grid row's margins are removed. */
internal fun browserTabPreviewHeight(width: Int, preferredMaximum: Int, viewport: Int?, header: Int): Int {
    val preferred = minOf(width, preferredMaximum).coerceAtLeast(0)
    return if (viewport == null) preferred else minOf(preferred, (viewport - header).coerceAtLeast(0))
}
