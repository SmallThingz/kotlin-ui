package smallthingz.ui.components

/** Screen-coordinate geometry kept independent of Android windows for edge-placement tests. */
internal data class NativePopupBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

internal data class NativePopupPosition(val left: Int, val top: Int)

internal object NativePopupPlacement {
    fun insetFrame(frame: NativePopupBounds, horizontalMargin: Int, verticalMargin: Int): NativePopupBounds {
        require(frame.width > 0 && frame.height > 0)
        val horizontal = horizontalMargin.coerceIn(0, (frame.width - 1) / 2)
        val vertical = verticalMargin.coerceIn(0, (frame.height - 1) / 2)
        return NativePopupBounds(frame.left + horizontal, frame.top + vertical,
            frame.right - horizontal, frame.bottom - vertical)
    }

    fun maxHeight(frame: NativePopupBounds, anchor: NativePopupBounds, verticalOffset: Int): Int =
        maxOf(frame.bottom - (anchor.bottom + verticalOffset),
            anchor.top + verticalOffset - frame.top).coerceIn(1, frame.height)

    /** Prefer logical-start alignment below the anchor, then its opposite edge/above, then clamp. */
    fun position(frame: NativePopupBounds, anchor: NativePopupBounds, width: Int, height: Int,
        horizontalOffset: Int, verticalOffset: Int, rtl: Boolean): NativePopupPosition {
        require(width in 1..frame.width && height in 1..frame.height)
        val start = (if (rtl) anchor.right - width else anchor.left) + horizontalOffset
        val end = (if (rtl) anchor.left else anchor.right - width) + horizontalOffset
        val left = when {
            start >= frame.left && start + width <= frame.right -> start
            end >= frame.left && end + width <= frame.right -> end
            else -> start.coerceIn(frame.left, frame.right - width)
        }
        val below = anchor.bottom + verticalOffset
        val above = anchor.top - height + verticalOffset
        val top = when {
            below >= frame.top && below + height <= frame.bottom -> below
            above >= frame.top && above + height <= frame.bottom -> above
            else -> below.coerceIn(frame.top, frame.bottom - height)
        }
        return NativePopupPosition(left, top)
    }
}
