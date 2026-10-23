package smallthingz.ui.platform
interface AccessibilityManager {
    fun calculateRecommendedTimeoutMillis(originalTimeoutMillis: Long, containsIcons: Boolean = false,
        containsText: Boolean = false, containsControls: Boolean = false): Long
}
