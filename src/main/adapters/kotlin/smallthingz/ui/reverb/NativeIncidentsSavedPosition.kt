// Reverb application presentation source, retained under the Reverb project GPL-3.0 license.

package smallthingz.ui.reverb

data class ReverbIncidentListPosition(val firstVisibleItemIndex: Int, val firstVisibleItemScrollOffset: Int)

/**
 * LazyColumn's conditional rememberSaveable lifetime, not a retained scroll cache.
 * An unconsumed restored value survives loading/empty history until the first nonempty branch.
 * Once that branch exists, its initial anchor must survive save-before-layout; leaving the branch
 * then retires it just as removing the released rememberLazyListState provider does.
 */
class NativeIncidentsSavedPosition(restored: ReverbIncidentListPosition? = null) {
    private var pending = restored
    private var current: ReverbIncidentListPosition? = null
    private var entered = false

    /** Returns the initial anchor only when entering a new nonempty list branch. */
    fun enterList(): ReverbIncidentListPosition? {
        if (entered) return null
        entered = true
        val initial = pending ?: ReverbIncidentListPosition(0, 0)
        pending = initial
        current = null
        return initial
    }

    /** LazyListMeasure134–138 clamps a vanished restored index only during real measurement. */
    fun pendingForPlacement(itemCount: Int): ReverbIncidentListPosition? {
        if (!entered || itemCount <= 0) return null
        val initial = pending ?: return null
        return if (initial.firstVisibleItemIndex >= itemCount) ReverbIncidentListPosition(itemCount - 1, 0)
        else initial
    }

    /** Only a completed, non-prelayout placement can replace the unapplied restored anchor. */
    fun placed(position: ReverbIncidentListPosition) {
        if (!entered) return
        pending = null
        current = position
    }

    fun save(livePosition: ReverbIncidentListPosition? = null): ReverbIncidentListPosition? =
        pending ?: if (entered) livePosition ?: current ?: ReverbIncidentListPosition(0, 0) else null

    fun leaveList() {
        if (!entered) return // The restored registry value has not been consumed by LazyColumn yet.
        entered = false
        pending = null
        current = null
    }
}
