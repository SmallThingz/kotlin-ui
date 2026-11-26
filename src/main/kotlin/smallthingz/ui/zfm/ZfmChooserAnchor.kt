package smallthingz.ui.zfm

/** Physical content coordinates, independent of Android View or any storage/backend identity. */
internal data class ZfmChooserRootBounds(val key: String, val top: Int, val bottom: Int)
internal data class ZfmChooserVisibleRoot(val key: String, val offset: Long)
internal data class ZfmChooserAnchor(val visible: List<ZfmChooserVisibleRoot>, val scrollY: Int)

internal object ZfmChooserAnchors {
    fun <T> physicalOrder(logical: List<T>, bottomFirst: Boolean): List<T> =
        if (bottomFirst) logical.reversed() else logical.toList()

    fun capture(
        rows: List<ZfmChooserRootBounds>, scrollY: Int, viewportHeight: Int, bottomFirst: Boolean,
        clipTop: Int = 0, clipBottom: Int = 0,
    ): ZfmChooserAnchor {
        val start = scrollY.toLong() + clipTop.coerceAtLeast(0)
        val end = scrollY.toLong() + viewportHeight - clipBottom.coerceAtLeast(0)
        val visible = if (end <= start) emptyList() else rows.filter {
            it.bottom > it.top && it.bottom.toLong() > start && it.top.toLong() < end
        }.sortedBy { it.top }
        // Prefer the reading edge, but retain every visible candidate in case that key disappears.
        return ZfmChooserAnchor(physicalOrder(visible, bottomFirst).map {
            ZfmChooserVisibleRoot(it.key, it.top.toLong() - scrollY)
        }, scrollY)
    }

    fun restore(anchor: ZfmChooserAnchor, rows: List<ZfmChooserRootBounds>, maximum: Int): Int {
        val live = rows.filter { it.bottom > it.top }.associateBy { it.key }
        val survivor = anchor.visible.firstOrNull { it.key in live }
        val target = if (survivor == null) anchor.scrollY.toLong()
            else live.getValue(survivor.key).top.toLong() - survivor.offset
        return target.coerceIn(0L, maximum.coerceAtLeast(0).toLong()).toInt()
    }
}

/** One opening only. Reattachment and input can never rearm its initial placement. */
internal class ZfmChooserPlacementGate(private val opening: Long) {
    internal data class Ticket(val opening: Long, val attachment: Long, val window: Long, val input: Long)
    private var attachment = 0L
    private var window = 0L
    private var input = 0L
    private var attached = false
    private var closed = false
    private var initial = true
    val awaitsInitialPlacement get() = initial

    fun attach() { if (!closed) { attachment++; attached = true } }
    fun detach() { attachment++; attached = false; initial = false }
    fun windowChanged(focused: Boolean) { window++; if (!focused) initial = false }
    fun input() { input++; initial = false }
    fun close() { closed = true; detach() }
    fun ticket(): Ticket? = if (attached && !closed) Ticket(opening, attachment, window, input) else null
    fun accepts(candidate: Ticket?): Boolean = candidate != null && candidate == ticket()

    /** Called exactly at the first usable committed layout, even when its ticket was retired. */
    fun commitInitial(ticket: Ticket?): Boolean {
        val place = initial && accepts(ticket)
        initial = false
        return place
    }
}
