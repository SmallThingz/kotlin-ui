package smallthingz.ui.text

/**
 * UTF-16 offsets for a layout that replaces source [start, end) with one ellipsis character.
 * The first removed code unit owns the replacement glyph; later removed units have zero width
 * at its trailing caret. Retained prefix/suffix offsets remain reversible, including text end.
 */
internal class NativeEllipsisOffsets(private val start: Int, private val end: Int) {
    init {
        require(start >= 0 && end > start)
    }

    private val removed = end - start - 1

    fun toLayoutOffset(sourceOffset: Int): Int = when {
        sourceOffset <= start -> sourceOffset
        sourceOffset < end -> start + 1
        else -> sourceOffset - removed
    }

    /** Hidden characters use the replacement's bidi run, not the suffix after its trailing caret. */
    fun toLayoutCharacter(sourceOffset: Int): Int = when {
        sourceOffset < start -> sourceOffset
        sourceOffset < end -> start
        else -> sourceOffset - removed
    }

    fun toSourceOffset(layoutOffset: Int): Int =
        if (layoutOffset <= start) layoutOffset else layoutOffset + removed
}
