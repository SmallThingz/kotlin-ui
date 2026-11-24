package smallthingz.ui.reverb.settings

import android.content.Context
import android.graphics.Rect
import android.view.ViewTreeObserver
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.scroll.NativeListSavedPosition
import smallthingz.ui.scroll.NativePendingListPosition
import smallthingz.ui.scroll.NativeSourceRecyclerView
import kotlin.math.roundToInt

/** Released LazyColumn item identities; retained state belongs to the item, never its current index. */
enum class ReverbSettingsSectionKey(val sourceKey: String) {
    BACKGROUND("background-reliability"), THEME("theme"), RETENTION("retention"),
    RECORDING("recording"), QUICK_TILES("quick-save-tiles"), STORAGE("storage"),
    AUTO_START("auto-start");
    companion object {
        fun visible(restricted: Boolean): List<ReverbSettingsSectionKey> =
            if (restricted) entries else entries.drop(1)
        fun retentionIndex(restricted: Boolean) = if (restricted) 2 else 1
    }
}

interface ReverbSettingsSection : AutoCloseable {
    val view: View
    /** Reads the current screen snapshot; never performs persistence or canonicalization. */
    fun bind()
}
fun interface ReverbSettingsSectionFactory {
    fun create(key: ReverbSettingsSectionKey): ReverbSettingsSection
}

/**
 * Finite Settings list host. The existing screen owns Scaffold insets, palette/noise, input admission,
 * draft/controller and section factories. This host owns item membership and scroll position only.
 * Native recycling/scroll dispatch still requires observable release comparison before cutover.
 */
class ReverbSettingsSectionListView(
    context: Context,
    private val factory: ReverbSettingsSectionFactory,
    private var environment: ReverbEnvironment,
    restricted: Boolean,
    restored: NativeListSavedPosition? = null,
    private val onStructureChanged: () -> Unit = {},
) : FrameLayout(context), AutoCloseable {
    private val pendingPosition = NativePendingListPosition(restored)
    private val manager: LinearLayoutManager = object : LinearLayoutManager(context, RecyclerView.VERTICAL, false) {
        override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
            if (!state.isPreLayout) pendingPosition.pendingForPlacement(state.itemCount)?.let {
                super.scrollToPositionWithOffset(it.firstVisibleItemIndex, -it.firstVisibleItemScrollOffset)
            }
            super.onLayoutChildren(recycler, state)
        }
        override fun onLayoutCompleted(state: RecyclerView.State) {
            super.onLayoutCompleted(state)
            if (!state.isPreLayout) pendingPosition.placed(placedPosition())
        }
    }
    private val rows = Rows(restricted)
    val viewport: NativeSourceRecyclerView get() = list
    private val list = NativeSourceRecyclerView(context).apply {
        layoutManager = manager
        adapter = rows
        itemAnimator = null
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        clipToPadding = false
        setHasFixedSize(false)
    }
    private var closed = false
    private var cancelledFocusReveal: View? = null
    private val focusRectangle = Rect()
    private var lastFocusedLeaf: View? = null
    private var imeVisible = false
    private val focusChanged = ViewTreeObserver.OnGlobalFocusChangeListener { _, _ ->
        val focused = list.findFocus()
        if (lastFocusedLeaf !== focused) {
            lastFocusedLeaf = focused
            cancelledFocusReveal = null
        }
    }
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(list, LayoutParams(-1, -1))
        // An intentional drag, wheel or accessibility scroll owns the viewport until focus changes.
        list.sourceOnUserScroll = { cancelledFocusReveal = list.findFocus() }
        applyPadding()
    }

    fun bind(restricted: Boolean, next: ReverbEnvironment) {
        check(!closed)
        environment = next
        applyPadding()
        rows.membership(restricted)
        rows.bindAttached()
    }

    fun bindSection(key: ReverbSettingsSectionKey) {
        check(!closed)
        rows.bindAttached(key)
    }

    fun focusRetention() {
        // Released effect calls scrollToItem2/1 immediately when the focus-buffer owner changes.
        val index = ReverbSettingsSectionKey.retentionIndex(rows.restricted)
        list.stopScroll()
        pendingPosition.request(NativeListSavedPosition(index, 0))
        manager.scrollToPositionWithOffset(index, 0)
    }

    fun savePosition(): NativeListSavedPosition = pendingPosition.save(placedPosition())

    private fun placedPosition(): NativeListSavedPosition? {
        val index = manager.findFirstVisibleItemPosition()
        if (index == RecyclerView.NO_POSITION) return null
        val item = manager.findViewByPosition(index)
        val offset = if (item == null) 0 else (list.paddingTop - manager.getDecoratedTop(item)).coerceAtLeast(0)
        return NativeListSavedPosition(index, offset)
    }

    private fun applyPadding() {
        val bottom = (24f * environment.textDensity.density).roundToInt()
        if (list.paddingBottom != bottom) list.setPadding(0, 0, 0, bottom)
    }

    fun setImeVisible(visible: Boolean) {
        if (imeVisible == visible) return
        imeVisible = visible
        cancelledFocusReveal = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFocusedLeaf = list.findFocus()
        viewTreeObserver.addOnGlobalFocusChangeListener(focusChanged)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnGlobalFocusChangeListener(focusChanged)
        lastFocusedLeaf = null
        cancelledFocusReveal = null
        super.onDetachedFromWindow()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val focused = list.findFocus()
        if (focused == null) cancelledFocusReveal = null
        val oldHeight = list.height
        val reveal = focused != null && focused !== list && focused !== cancelledFocusReveal &&
            focused.onCheckIsTextEditor() && oldHeight > 0
        var wasVisible = false
        if (reveal) {
            focusRectangle.set(0, 0, focused!!.width, focused.height)
            list.offsetDescendantRectToMyCoords(focused, focusRectangle)
            wasVisible = focusRectangle.top >= 0 && focusRectangle.bottom <= oldHeight
        }
        super.onLayout(changed, l, t, r, b)
        if (!wasVisible || list.height >= oldHeight || list.findFocus() !== focused) return
        val child = list.findContainingItemView(focused!!) as? ViewGroup ?: return
        focusRectangle.set(0, 0, focused.width, focused.height)
        child.offsetDescendantRectToMyCoords(focused, focusRectangle)
        // Reveal the retained editor inside RV only; do not request another window pan or focus.
        list.requestChildRectangleOnScreen(child, focusRectangle, true)
    }

    override fun close() {
        if (closed) return
        closed = true
        list.sourceOnUserScroll = null
        viewTreeObserver.removeOnGlobalFocusChangeListener(focusChanged)
        lastFocusedLeaf = null
        cancelledFocusReveal = null
        list.stopScroll()
        list.adapter = null
        rows.close()
    }

    private inner class Rows(initialRestricted: Boolean) : RecyclerView.Adapter<Holder>(), AutoCloseable {
        var restricted = initialRestricted
            private set
        private var keys = ReverbSettingsSectionKey.visible(initialRestricted)
        private val holders = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Holder, Boolean>())
        init { setHasStableIds(true) }
        fun membership(value: Boolean) {
            if (restricted == value) return
            val oldIndex = manager.findFirstVisibleItemPosition()
            val anchor = keys.getOrNull(oldIndex)
            val offset = manager.findViewByPosition(oldIndex)?.let { manager.getDecoratedTop(it) - list.paddingTop }
            restricted = value
            keys = ReverbSettingsSectionKey.visible(value)
            if (value) notifyItemInserted(0) else notifyItemRemoved(0)
            if (anchor != null && offset != null) {
                val index = keys.indexOf(anchor)
                if (index >= 0) {
                    pendingPosition.request(NativeListSavedPosition(index, (-offset).coerceAtLeast(0)))
                    manager.scrollToPositionWithOffset(index, offset)
                }
            }
        }
        fun bindAttached(key: ReverbSettingsSectionKey? = null) {
            for (index in 0 until list.childCount) {
                val holder = list.getChildViewHolder(list.getChildAt(index)) as? Holder ?: continue
                if (key == null || holder.itemViewType == key.ordinal) holder.section.bind()
            }
        }
        override fun getItemCount() = keys.size
        override fun getItemId(position: Int) = keys[position].ordinal.toLong()
        override fun getItemViewType(position: Int) = keys[position].ordinal
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val section = factory.create(ReverbSettingsSectionKey.entries[viewType])
            check(section.view.parent == null) { "A Settings section factory must return an unparented owned View" }
            section.view.layoutParams = RecyclerView.LayoutParams(-1, -2)
            return Holder(section).also { holders.add(it) }
        }
        override fun onBindViewHolder(holder: Holder, position: Int) = holder.section.bind()
        override fun onViewAttachedToWindow(holder: Holder) {
            super.onViewAttachedToWindow(holder)
            // RV may reattach a cached holder without rebinding its unchanged stable item ID.
            holder.section.bind()
            onStructureChanged()
        }
        override fun onViewDetachedFromWindow(holder: Holder) {
            super.onViewDetachedFromWindow(holder)
            onStructureChanged()
        }
        override fun onViewRecycled(holder: Holder) {
            // Reuse retains the same keyed section type. No durable/draft operation is triggered.
            super.onViewRecycled(holder)
        }
        override fun close() {
            var failure: Throwable? = null
            holders.forEach { holder ->
                try {
                    holder.section.close()
                } catch (error: Throwable) {
                    if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
                }
            }
            holders.clear()
            failure?.let { throw it }
        }
    }
    private class Holder(val section: ReverbSettingsSection) : RecyclerView.ViewHolder(section.view)
}
