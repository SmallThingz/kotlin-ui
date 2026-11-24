package smallthingz.ui.zfm

/**
 * Responsive placement for a retained native search field, its actions and result status.
 *
 * [preferredEditorWidth] is a fit budget, not an exact field width. The editor keeps the
 * remaining width of its row. [inputWidth] includes the close control, actions and input
 * container padding; status occupies a separate row unless [inlineStatus] is true.
 * No text, selection, focus or native horizontal scrolling is changed by this calculation.
 */
data class ZfmSearchLayout(
    val preferredEditorWidth: Int,
    val inputWidth: Int,
    val stackActions: Boolean,
    val inlineStatus: Boolean,
) {
    companion object {
        /**
         * All dimensions are nonnegative physical pixels, rounded up when measuring text.
         * [availableWidth] excludes the outer chrome's padding, but includes [inputPaddingWidth].
         * [editorPaddingWidth] is the field's compound start plus end padding; [cursorWidth]
         * reserves room for its native cursor. Measure query/hint with the field's actual
         * typography and single-line transformation. Only visible controls contribute widths.
         *
         * Call once for the whole chrome width, before allocating an inline status width.
         * Both fit decisions use that same budget. A long query can use the entire editor row
         * after actions/status move out; it never demands unbounded width. Recompute on text,
         * font/configuration, visible-action or status-size changes, retaining the existing
         * NativeTextField and common controls. The caller owns normal View measurement/layout.
         */
        fun measure(
            availableWidth: Int,
            queryTextWidth: Int,
            hintTextWidth: Int,
            editorPaddingWidth: Int,
            cursorWidth: Int,
            closeWidth: Int,
            actionsWidth: Int,
            inputPaddingWidth: Int = 0,
            minimumEditorWidth: Int = 0,
            statusWidth: Int = 0,
            allowInlineStatus: Boolean = false,
        ): ZfmSearchLayout {
            require(availableWidth >= 0 && queryTextWidth >= 0 && hintTextWidth >= 0 &&
                editorPaddingWidth >= 0 && cursorWidth >= 0 && closeWidth >= 0 &&
                actionsWidth >= 0 && inputPaddingWidth >= 0 && minimumEditorWidth >= 0 &&
                statusWidth >= 0) { "Search layout dimensions must be nonnegative" }

            val fixedWidth = inputPaddingWidth.toLong() + closeWidth
            val maximumEditorWidth = (availableWidth.toLong() - fixedWidth).coerceAtLeast(0)
            val naturalEditorWidth = maxOf(minimumEditorWidth.toLong(),
                maxOf(queryTextWidth, hintTextWidth).toLong() + editorPaddingWidth + cursorWidth)
            val editorWidth = minOf(naturalEditorWidth, maximumEditorWidth)
            val inlineInputWidth = fixedWidth + editorWidth + actionsWidth
            val inlineStatus = allowInlineStatus && inlineInputWidth + statusWidth <= availableWidth
            val inputWidth = availableWidth - if (inlineStatus) statusWidth else 0
            return ZfmSearchLayout(
                preferredEditorWidth = editorWidth.toInt(),
                inputWidth = inputWidth,
                stackActions = actionsWidth > 0 && inlineInputWidth > inputWidth,
                inlineStatus = inlineStatus,
            )
        }
    }
}
