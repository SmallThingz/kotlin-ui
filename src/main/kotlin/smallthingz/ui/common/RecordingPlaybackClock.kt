package smallthingz.ui.common

/**
 * An actual transport sample. Elapsed realtime keeps background playback correct across sleep.
 * transportIdentity is an immutable token, never the player, descriptor or lifecycle owner itself.
 */
data class PlaybackPositionAnchor(
    val positionMillis: Int,
    val sampledAtElapsedRealtimeMillis: Long,
    val playing: Boolean,
    val endMillis: Int,
    val epoch: Long,
    val transportIdentity: Any,
    val startMillis: Int = 0,
) {
    private val lowerBound: Int get() = startMillis.coerceAtLeast(0)
    private val upperBound: Int get() = endMillis.coerceAtLeast(lowerBound)

    fun positionAt(nowElapsedRealtimeMillis: Long): Int {
        val position = positionMillis.coerceIn(lowerBound, upperBound)
        if (!playing || sampledAtElapsedRealtimeMillis < 0L ||
            nowElapsedRealtimeMillis < sampledAtElapsedRealtimeMillis) return position
        val elapsed = nowElapsedRealtimeMillis - sampledAtElapsedRealtimeMillis
        // Bound before adding: both Long timestamp extremes and Int endpoint extremes are safe.
        val remaining = upperBound.toLong() - position.toLong()
        return if (elapsed >= remaining) upperBound else (position.toLong() + elapsed).toInt()
    }

    fun isAdvancingAt(nowElapsedRealtimeMillis: Long): Boolean = playing &&
        sampledAtElapsedRealtimeMillis >= 0L && nowElapsedRealtimeMillis >= sampledAtElapsedRealtimeMillis &&
        positionAt(nowElapsedRealtimeMillis) < upperBound
}

/** Ordering is local to one recording/mode/transport owner, never across independent players. */
class PlaybackPositionDisplayClock {
    private data class Owner(val revision: String, val trimMode: Boolean, val identity: Any)
    private var owner: Owner? = null
    private var latest: PlaybackPositionAnchor? = null
    var anchor: PlaybackPositionAnchor? = null
        private set
    private var displayActive = true
    private var heldPositionMillis: Int? = null
    private var heldPositionNeedsRefresh = false

    /** Freeze this presentation while its screen is inactive; the transport keeps its own clock. */
    fun setDisplayActive(active: Boolean, nowElapsedRealtimeMillis: Long) {
        if (active) heldPositionMillis = null
        else if (displayActive || heldPositionNeedsRefresh) {
            heldPositionMillis = anchor?.positionAt(nowElapsedRealtimeMillis)
        }
        displayActive = active
        heldPositionNeedsRefresh = false
    }

    fun positionAt(nowElapsedRealtimeMillis: Long): Int? =
        if (displayActive) anchor?.positionAt(nowElapsedRealtimeMillis) else heldPositionMillis

    fun isAdvancingAt(nowElapsedRealtimeMillis: Long): Boolean =
        displayActive && anchor?.isAdvancingAt(nowElapsedRealtimeMillis) == true

    fun bind(recordingRevisionKey: String, trimMode: Boolean, next: PlaybackPositionAnchor?) {
        if (next == null) {
            anchor = null
            heldPositionMillis = null
            heldPositionNeedsRefresh = false
            return
        }
        val previousOwner = owner
        val ownerChanged = previousOwner == null || previousOwner.revision != recordingRevisionKey ||
            previousOwner.trimMode != trimMode || previousOwner.identity != next.transportIdentity
        if (ownerChanged) {
            owner = Owner(recordingRevisionKey, trimMode, next.transportIdentity)
            latest = null
        }
        val previous = latest
        if (previous != null && (next.epoch < previous.epoch ||
            (next.epoch == previous.epoch && next.sampledAtElapsedRealtimeMillis < previous.sampledAtElapsedRealtimeMillis))) return
        // Unrelated row publications can carry equal sample copies while this display is inactive.
        if (ownerChanged || anchor != next) heldPositionNeedsRefresh = true
        latest = next
        anchor = next
    }
}
