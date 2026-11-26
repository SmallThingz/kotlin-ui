package smallthingz.ui.common

/** Typed choices. Localized text is presentation and may be identical for different values. */
data class DropdownContent<T>(
    val label: String,
    val selected: T,
    val options: List<T>,
    val optionLabel: (T) -> String,
)

/** Controls selection delivery while the dropdown is open. See [DropdownDismissPolicy]. */
enum class DropdownSelectionPolicy {
    /** Deliver changes as options reach the selection slot. */
    PROGRESSIVE,
    /** Browse freely and deliver a value when the user activates an option. */
    EXPLICIT,
}

/**
 * Controls whether an outside tap, Back, or accessibility dismissal delivers a value.
 * This applies independently of [DropdownSelectionPolicy]. Escape and lifecycle closure discard
 * pending choices.
 */
enum class DropdownDismissPolicy {
    /** Deliver the current choice if it represents a change. This is the default. */
    COMMIT,
    /** Close and retain the last value already delivered to the owner. */
    CANCEL,
}

internal fun DropdownContent<*>.canOpen(policy: DropdownSelectionPolicy): Boolean =
    when (policy) {
        DropdownSelectionPolicy.PROGRESSIVE -> options.size > 1 && selected in options
        DropdownSelectionPolicy.EXPLICIT -> options.size > 1 || options.size == 1 && options[0] != selected
    }
