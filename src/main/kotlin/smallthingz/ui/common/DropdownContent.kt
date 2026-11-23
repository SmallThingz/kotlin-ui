package smallthingz.ui.common

/** Typed choices. Localized text is presentation and may be identical for different values. */
data class DropdownContent<T>(
    val label: String,
    val selected: T,
    val options: List<T>,
    val optionLabel: (T) -> String,
)
