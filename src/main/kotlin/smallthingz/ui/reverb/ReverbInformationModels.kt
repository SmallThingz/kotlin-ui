package smallthingz.ui.reverb

/** Localized information and opaque action IDs; opening a destination remains host-owned. */
data class ReverbInformationLink<K : Any>(val id: K, val label: String)
data class ReverbInformationStep<K : Any>(val paragraph: String, val link: ReverbInformationLink<K>? = null)

data class ReverbInformationToggle<K : Any>(
    val id: K,
    val title: String,
    val detail: String,
    val checked: Boolean,
    val enabled: Boolean,
    val stateDescription: String? = null,
)

/** Rebinding or closing retires captured actions, including a callback returning after navigation. */
internal class ReverbInformationActions<K : Any> {
    private var revision = 0L
    private var keys = emptySet<K>()
    private var closed = false

    fun bind(values: Collection<K>): Long {
        check(!closed)
        val next = values.toSet()
        require(next.size == values.size) { "Information actions require unique IDs." }
        keys = next
        return ++revision
    }

    fun admits(version: Long, key: K): Boolean = !closed && version == revision && key in keys

    fun close() {
        closed = true
        keys = emptySet()
        revision++
    }
}
