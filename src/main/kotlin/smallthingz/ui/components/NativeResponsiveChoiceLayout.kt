package smallthingz.ui.components

internal enum class NativeResponsiveChoiceLayout { HIDDEN, SINGLE, PAIRED, COMBINED }

/** Equal columns must each fit the widest field, including the gaps between columns. */
internal fun responsiveChoiceLayout(width: Int, fieldWidths: List<Int>, gap: Int): NativeResponsiveChoiceLayout =
    when (fieldWidths.size) {
        0 -> NativeResponsiveChoiceLayout.HIDDEN
        1 -> NativeResponsiveChoiceLayout.SINGLE
        else -> {
            val column = (width.toLong() - (fieldWidths.size - 1L) * gap) / fieldWidths.size
            if (column >= fieldWidths.max()) NativeResponsiveChoiceLayout.PAIRED
            else NativeResponsiveChoiceLayout.COMBINED
        }
    }
