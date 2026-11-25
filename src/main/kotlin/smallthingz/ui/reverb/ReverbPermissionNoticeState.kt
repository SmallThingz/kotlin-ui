package smallthingz.ui.reverb

/** One authoritative snapshot for visibility and action admission, including permission revocation. */
internal class ReverbPermissionNoticeState {
    var items: List<ReverbPermissionOnboardingState> = emptyList()
        private set
    var expanded = false
        private set

    fun setExpanded(value: Boolean) { expanded = value && items.isNotEmpty() }

    var revision = 0L
        private set

    fun bind(values: List<ReverbPermissionOnboardingState>, shown: Boolean): Boolean {
        require(values.map { it.kind }.distinct().size == values.size)
        val next = if (shown) values.filter { it.allowed != true }.sortedBy { it.kind.ordinal } else emptyList()
        if (next == items) return false
        items = next
        if (items.isEmpty()) expanded = false
        revision++
        return true
    }

    fun admits(kind: ReverbPermissionOnboardingKind, expectedRevision: Long): Boolean =
        revision == expectedRevision && items.any { it.kind == kind && it.allowed == false && it.canRequest }
}

/** Budget the entire pinned notice, leaving at least half the offered space to its host. */
internal fun permissionNoticeHeightBudget(offeredHeight: Int?, maximumHeight: Int): Int {
    require(maximumHeight >= 0)
    require(offeredHeight == null || offeredHeight >= 0)
    return minOf(maximumHeight, offeredHeight?.div(2) ?: maximumHeight)
}
