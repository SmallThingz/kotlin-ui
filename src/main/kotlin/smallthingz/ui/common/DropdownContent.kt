package smallthingz.ui.common

/** Typed choices. Localized text is presentation and may be identical for different values. */
data class DropdownContent<T>(
    val label: String,
    val selected: T,
    val options: List<T>,
    val optionLabel: (T) -> String,
)

/** Controls when browsing a dropdown delivers a value to its owner. */
enum class DropdownSelectionPolicy {
    PROGRESSIVE,
    EXPLICIT,
}

internal fun DropdownContent<*>.canOpen(policy: DropdownSelectionPolicy): Boolean =
    when (policy) {
        DropdownSelectionPolicy.PROGRESSIVE -> options.size > 1 && selected in options
        DropdownSelectionPolicy.EXPLICIT -> options.size > 1 || options.size == 1 && options[0] != selected
    }
