package smallthingz.ui.reverb

/** Keyed requests only: the browser owns ordering and commits it with bindTabs. */
internal class ReverbBrowserPanelReorder<Key : Any> {
    private var keys: List<Key> = emptyList()
    private var enabled: Set<Key> = emptySet()
    private var dragIdentity: Any? = null
    private var dragKey: Key? = null
    val dragging: Boolean get() = dragIdentity != null

    fun bind(keys: List<Key>, enabled: Set<Key>) {
        require(keys.distinct().size == keys.size)
        cancel()
        this.keys = keys.toList()
        this.enabled = enabled.toSet()
    }

    fun setEnabled(key: Key, value: Boolean) {
        cancel()
        enabled = if (value) enabled + key else enabled - key
    }

    fun canMove(key: Key, target: Key): Boolean =
        key != target && key in keys && target in keys && key in enabled && target in enabled

    fun adjacent(key: Key, step: Int): Key? {
        require(step == -1 || step == 1)
        val index = keys.indexOf(key)
        if (index < 0) return null
        return keys.getOrNull(index + step)?.takeIf { canMove(key, it) }
    }

    fun canStart(key: Key): Boolean = keys.any { canMove(key, it) }

    /** Next surviving neighbor, then preceding neighbors; callers filter current enabled rows. */
    fun continuationAfterRemoval(key: Key): List<Key> {
        val index = keys.indexOf(key)
        return if (index < 0) emptyList() else keys.drop(index + 1) + keys.take(index).asReversed()
    }

    fun begin(key: Key): Any? {
        if (dragging || !canStart(key)) return null
        return Any().also { dragIdentity = it; dragKey = key }
    }

    fun source(identity: Any?): Key? =
        if (identity != null && identity === dragIdentity) dragKey else null

    fun cancel() { dragIdentity = null; dragKey = null }
}
