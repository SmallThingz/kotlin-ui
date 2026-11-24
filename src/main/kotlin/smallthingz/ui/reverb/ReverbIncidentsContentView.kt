// Reverb application presentation source, retained under the Reverb project GPL-3.0 license.

package smallthingz.ui.reverb

import smallthingz.ui.components.NativeBackdropStyle
import smallthingz.ui.components.NativeCircularProgress
import smallthingz.ui.animation.view.ViewColorTransition
import smallthingz.ui.scroll.NativeSourceRecyclerView
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.components.NativeToolbarActionTone
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import smallthingz.ui.components.NativeGroupEdges
import smallthingz.ui.text.style.TextOverflow
import smallthingz.ui.components.NativeToolbarBackground
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.text.view.NativeSourceAccessibilityNode
import smallthingz.ui.text.view.NativeTextSemanticsClip
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.text.view.NativePlainTextViewGeometry
import smallthingz.ui.text.view.PlainTextAccessibilityWindowScope
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.View
import android.view.ViewGroup
import smallthingz.ui.text.TextStyle
import smallthingz.ui.runtime.MonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import androidx.core.graphics.Insets
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max
import kotlin.math.roundToInt

/** Keep the tool strip clear even when a focused menu reveals the system status bar. */
fun reverbIncidentSafeInsets(insets: WindowInsetsCompat): Insets = insets.getInsetsIgnoringVisibility(
    WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout(),
)

private data class IncidentsContentAppearance(
    val card: IncidentCardAppearance,
    val titleMedium: TextStyle,
    val backdrops: NativeBackdropStyle,
)

private fun incidentAppearance(theme: ReverbTheme): IncidentsContentAppearance {
    val appearance = ReverbDesign.appearance
    val typography = appearance.typography
    return IncidentsContentAppearance(
        IncidentCardAppearance(theme.palette, typography[NativeTypographyRole.BODY_LARGE],
            typography[NativeTypographyRole.BODY_MEDIUM], typography[NativeTypographyRole.BODY_SMALL],
            theme.density, theme.fontResolver, appearance.listDecorations.alertCard),
        typography[NativeTypographyRole.TITLE_MEDIUM], appearance.backdrops)
}

/**
 * Native contents of released IncidentsScreen. The caller retains the existing panel transform,
 * reversible open-progress, predictive Back, navigation, and history-observer lifetime.
 * No collection, accepted storage work, or Home changes are performed by this layout.
 */
class ReverbIncidentsContentView(
    context: Context,
    initialTheme: ReverbTheme,
    private val texts: ReverbIncidentTexts,
    private val actions: ReverbIncidentActions,
    private val onBack: () -> Unit,
    frames: MonotonicFrameClock,
    scope: CoroutineScope,
    durationScale: () -> Float,
    inputRoot: View,
    private val savedPosition: NativeIncidentsSavedPosition = NativeIncidentsSavedPosition(),
) : ViewGroup(context) {
    private var appearance = incidentAppearance(initialTheme)
    private val backdrop = appearance.backdrops.attachPage(this, appearance.card.palette)
    private var theme = initialTheme
    private var history = ReverbIncidentHistory()
    private var selecting = false
    private val selection = linkedMapOf<String, ReverbIncidentTarget>()
    private var clearSheet: ReverbIncidentClearSheet? = null
    private val header = IncidentsHeader(context, initialTheme, texts, inputRoot, ::back,
        ::selectAllOrEnter, ::deleteSelected, ::requestClear)
    private val empty = IncidentsEmptyContent(context, appearance, texts, frames, scope, durationScale)
    private val menus = ReverbIncidentMenuHost(initialTheme, frames, scope, texts.copy, texts.delete)
    private val rowAuthority = object : IncidentCardAuthority {
        override fun activate(row: ReverbIncidentOccurrence, showOptions: () -> Boolean): Boolean =
            activateOccurrence(row, showOptions)

        override fun menuTarget(row: ReverbIncidentOccurrence, requiresMutation: Boolean): ReverbIncidentTarget? {
            if (selecting || (requiresMutation && !history.canMutate)) return null
            return currentOccurrence(row)?.target
        }
    }
    private val adapter = IncidentsAdapter(context, appearance.card, texts, menus, actions, rowAuthority, inputRoot)
    private var accessibilityMembershipChanged: () -> Unit = {}
    private var panelProgress = 1f
    private var safeInsets = Insets.NONE
    private val policyDurationTick = Runnable { updatePolicyDuration() }
    private val manager: LinearLayoutManager = object : LinearLayoutManager(context) {
        override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
            if (!state.isPreLayout) {
                savedPosition.pendingForPlacement(state.itemCount)?.let { position ->
                    scrollToPositionWithOffset(position.firstVisibleItemIndex, -position.firstVisibleItemScrollOffset)
                }
            }
            super.onLayoutChildren(recycler, state)
        }
        override fun onLayoutCompleted(state: RecyclerView.State) {
            super.onLayoutCompleted(state)
            if (!state.isPreLayout && state.itemCount > 0) {
                currentListPosition()?.let(savedPosition::placed)
            }
        }
    }
    private val list = NativeSourceRecyclerView(context).apply {
        layoutManager = manager
        adapter = this@ReverbIncidentsContentView.adapter
        itemAnimator = ReverbIncidentItemAnimator(frames, scope, durationScale)
        clipToPadding = false
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) = accessibilityMembershipChanged()
            override fun onChildViewDetachedFromWindow(view: View) = accessibilityMembershipChanged()
        })
        addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                val position = parent.getChildAdapterPosition(view)
                val joined = this@ReverbIncidentsContentView.adapter.joinsNext(position)
                val hasNext = position >= 0 && position < state.itemCount - 1
                outRect.set(0, 0, 0, if (hasNext && !joined) dp(2f) else 0)
            }
        })
    }

    init {
        if (android.os.Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        addView(header)
        addView(list)
        addView(empty)
        updateListPadding()
        bind(ReverbIncidentHistory())
    }

    val selectionActive: Boolean get() = selecting

    fun bindHeaderAccessibility(cursor: PlainTextAccessibilityWindowScope, geometry: NativePlainTextViewGeometry,
                                sourceNode: (View) -> NativeSourceAccessibilityNode): AutoCloseable =
        header.bindAccessibility(cursor, geometry, sourceNode)

    fun observeAccessibilityMembership(changed: () -> Unit) { accessibilityMembershipChanged = changed }
    fun bindSourceAccessibility(sourceNode: ((View) -> NativeSourceAccessibilityNode)?) {
        empty.bindSourceAccessibility(sourceNode)
    }
    /** The source LazyColumn exists only in the nonempty history branch. */
    val pointerList: NativeSourceRecyclerView?
        get() = list.takeIf { it.visibility == VISIBLE && it.adapter != null }
    /** Same index/offset values as LazyListState.Saver; pending restore wins before placement. */
    fun savePosition(): ReverbIncidentListPosition? = savedPosition.save(currentListPosition())

    private fun currentListPosition(): ReverbIncidentListPosition? {
        if (list.visibility != VISIBLE || list.adapter == null) return null
        val index = manager.findFirstVisibleItemPosition()
        if (index == RecyclerView.NO_POSITION) return null
        val item = manager.findViewByPosition(index) ?: return null
        return ReverbIncidentListPosition(index, (list.paddingTop - manager.getDecoratedTop(item)).coerceAtLeast(0))
    }

    fun setStatusBarInsets(insets: Insets) {
        if (safeInsets == insets) return
        safeInsets = insets
        header.setWindowInsets(insets)
        requestLayout()
    }
    fun semanticsClip(view: View): NativeTextSemanticsClip = when {
        view === this || view === list ||
            (view.parent is NativeGroupLayout && (view.parent as NativeGroupLayout).getChildAt(0) === view) ->
            NativeTextSemanticsClip.Rectangle(smallthingz.ui.geometry.Rect(0f, 0f, view.width.toFloat(), view.height.toFloat()))
        else -> NativeTextSemanticsClip.Unclipped
    }

    /** Already prepared history only; timestamp formatting and storage stay off the UI thread. */
    fun bind(state: ReverbIncidentHistory) {
        history = state
        if (state.canMutate && selecting) {
            val previous = selection.toMap()
            selection.clear()
            val current = state.rows.associateBy { it.key }
            previous.forEach { (key, incident) ->
                val row = current[key] ?: current.values.singleOrNull {
                    it.target.matches(incident)
                }
                if (row != null) selection[row.key] = row.target
            }
            if (state.rows.isEmpty()) selecting = false
        }
        clearSheet?.setEnabled(state.canMutate)
        bindHeader()
        val hasRows = state.rows.isNotEmpty()
        list.visibility = if (hasRows) VISIBLE else GONE
        empty.visibility = if (hasRows) GONE else VISIBLE
        if (hasRows) {
            if (list.adapter == null) list.adapter = adapter
            adapter.submit(state, selection.keys.takeIf { selecting })
            savedPosition.enterList()?.let { position ->
                manager.scrollToPositionWithOffset(position.firstVisibleItemIndex, -position.firstVisibleItemScrollOffset)
            }
        } else {
            savedPosition.leaveList()
            // Match removal of LazyColumn from composition, including owned popup/input disposal.
            list.itemAnimator?.endAnimations()
            adapter.disposeVisible(list)
            list.adapter = null
            adapter.submit(state, selection.keys.takeIf { selecting })
            list.scrollToPosition(0)
            empty.bind(state)
        }
        requestLayout()
        accessibilityMembershipChanged()
        updatePolicyDuration()
    }

    fun consumeSelectionBack(): Boolean {
        if (!selecting) return false
        selecting = false
        selection.clear()
        updateSelection()
        return true
    }

    private fun back() {
        if (!consumeSelectionBack()) onBack()
    }

    private fun bindHeader() = header.bind(theme, selecting, selection.size,
        history.rows.size, history.canMutate)

    private fun updateSelection() {
        adapter.setSelection(selection.keys.takeIf { selecting })
        bindHeader()
    }

    private fun selectAllOrEnter() {
        if (!history.canMutate || history.rows.isEmpty()) return
        if (!selecting) selecting = true
        else if (selection.size == history.rows.size) selection.clear()
        else history.rows.forEach { selection[it.key] = it.target }
        updateSelection()
    }

    private fun currentOccurrence(requested: ReverbIncidentOccurrence): ReverbIncidentOccurrence? =
        adapter.occurrence(requested.key)?.takeIf { it.target.matches(requested.target) }

    private fun activateOccurrence(requested: ReverbIncidentOccurrence, showOptions: () -> Boolean): Boolean {
        val current = currentOccurrence(requested) ?: return false
        if (!history.canMutate) return !selecting && showOptions()
        if (selecting) {
            val selected = selection.remove(current.key) == null
            if (selected) selection[current.key] = current.target
            adapter.setSelected(current.key, selected)
            bindHeader()
        } else {
            actions.toggleAcknowledged(current.target)
        }
        return true
    }

    private fun deleteSelected() {
        if (!history.canMutate || selection.isEmpty()) return
        actions.deleteMany(selection.values.toList())
        consumeSelectionBack()
    }

    private fun requestClear() {
        if (!history.canMutate || clearSheet != null) return
        val targets = history.rows.map { it.target }
        if (targets.isEmpty()) return
        lateinit var sheet: ReverbIncidentClearSheet
        sheet = ReverbIncidentClearSheet(this, theme, texts, { if (clearSheet === sheet) dismissClear() }) {
            if (clearSheet === sheet && history.canMutate) {
                actions.deleteMany(targets)
                dismissClear()
            }
        }
        clearSheet = sheet
        try {
            sheet.show()
        } catch (failure: Throwable) {
            try { dismissClear() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private fun dismissClear() {
        val sheet = clearSheet
        clearSheet = null
        sheet?.close()
    }

    private fun updatePolicyDuration() {
        removeCallbacks(policyDurationTick)
        if (!isAttachedToWindow || !isShown || windowVisibility != VISIBLE) return
        if (history.rows.none { it.liveEnd?.ongoing == true }) return
        val now = SystemClock.elapsedRealtime()
        for (index in 0 until list.childCount) {
            (list.getChildAt(index) as? ReverbIncidentCardView)?.updatePolicyDuration(now)
        }
        postDelayed(policyDurationTick, 1000)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) updatePolicyDuration() else removeCallbacks(policyDurationTick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(policyDurationTick)
        super.onDetachedFromWindow()
    }

    fun bindTheme(theme: ReverbTheme) {
        this.theme = theme
        val value = incidentAppearance(theme)
        appearance = value
        value.backdrops.bindPage(backdrop, value.card.palette)
        menus.bindPalette(theme.palette)
        bindHeader()
        clearSheet?.bindTheme(theme)
        empty.setAppearance(value)
        adapter.setAppearance(value.card)
        updateListPadding()
        requestLayout()
    }

    private fun dp(value: Float) = (value * appearance.card.density.density).roundToInt()
    private fun updateListPadding() = list.setPaddingRelative(dp(18f), dp(12f), dp(18f), dp(28f))

    fun setPanelProgress(value: Float) {
        panelProgress = value.coerceIn(0f, 1f)
        applyPanelMotion()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyPanelMotion()
    }

    private fun applyPanelMotion() {
        translationX = reverbIncidentPanelTranslationX(width.toFloat(), panelProgress)
        alpha = .82f + panelProgress * .18f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        header.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
        val body = max(0, height - header.measuredHeight)
        val bodyWidth = max(0, width - safeInsets.left - safeInsets.right)
        val childWidth = MeasureSpec.makeMeasureSpec(bodyWidth, MeasureSpec.EXACTLY)
        val childHeight = MeasureSpec.makeMeasureSpec(body, MeasureSpec.EXACTLY)
        for (index in 0..1) {
            val child = if (index == 0) list else empty
            if (child.visibility != GONE) child.measure(childWidth, childHeight)
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        header.layout(0, 0, width, header.measuredHeight)
        for (index in 0..1) {
            val child = if (index == 0) list else empty
            if (child.visibility != GONE) {
                val x = safeInsets.left.coerceAtMost(width)
                child.layout(x, header.measuredHeight, x + child.measuredWidth, height)
            }
        }
    }

    /** Root owner calls this when the source overlay leaves composition at fully closed progress. */
    fun dispose() {
        var failure = smallthingz.ui.util.closePreservingPrimaryFailure(null, ::dismissClear)
        failure = smallthingz.ui.util.closePreservingPrimaryFailure(failure) {
            removeCallbacks(policyDurationTick)
            bindSourceAccessibility(null)
            savedPosition.leaveList()
            list.itemAnimator?.endAnimations()
            adapter.disposeVisible(list)
            list.adapter = null
        }
        failure = smallthingz.ui.util.closePreservingPrimaryFailure(failure, menus::close)
        failure?.let { throw it }
    }
}

private class IncidentsHeader(
    context: Context,
    theme: ReverbTheme,
    private val texts: ReverbIncidentTexts,
    inputRoot: View,
    private val back: () -> Unit,
    private val select: () -> Unit,
    private val deleteSelected: () -> Unit,
    private val clearAll: () -> Unit,
) : FrameLayout(context) {
    private val style = ReverbDesign.appearance.toolbars
    private var selectionMode = false
    private fun action(theme: ReverbTheme, inputRoot: View, label: String) =
        ReverbToolbarActionView(context, theme.density.density, theme.palette,
            { inputRoot }, label, selectionWrapper = true)
    private val navigation = action(theme, inputRoot, texts.back)
    private val destructive = action(theme, inputRoot, texts.clearAll)
    private val selection = action(theme, inputRoot, texts.multiSelect)
    private val title = theme.paragraph(context, "", style.leadingTitleRole).apply {
        setTextLayout(maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    private val actions = LinearLayout(context)
    private val bar = ReverbSelectionTopBarLayout(context, navigation, title, actions, listOf(destructive, selection))
    private val chrome = NativeToolbarBackground(this, style, style.backgroundColor(theme.palette))
    private var glyphDensity: Float? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false
        clipToPadding = false
        actions.orientation = LinearLayout.HORIZONTAL
        actions.clipChildren = false
        actions.addView(destructive, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        actions.addView(selection, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun bindAccessibility(cursor: PlainTextAccessibilityWindowScope, geometry: NativePlainTextViewGeometry,
                          sourceNode: (View) -> NativeSourceAccessibilityNode): AutoCloseable {
        val leases = listOf(navigation, destructive, selection).map { it.bindAccessibility(cursor, geometry, sourceNode) }
        return AutoCloseable { leases.forEach(AutoCloseable::close) }
    }

    fun setWindowInsets(insets: Insets) = setPadding(insets.left, insets.top, insets.right, insets.bottom)

    fun bind(theme: ReverbTheme, selecting: Boolean, selected: Int, count: Int, ready: Boolean) {
        val palette = theme.palette
        val density = theme.density.density
        val modeChanged = selectionMode != selecting
        if (modeChanged) {
            selectionMode = selecting
            navigation.cancelInput()
            destructive.cancelInput()
            selection.cancelInput()
        }
        if (glyphDensity != density || modeChanged) {
            val tint = style.contentColor(palette)
            val icon = if (selecting) ReverbIcon.Close else ReverbIcon.Back
            navigation.setGlyph(ReverbIconDrawable(icon, density, tint), tint)
            if (glyphDensity != density) {
                destructive.setGlyph(ReverbIconDrawable(ReverbIcon.Delete, density, tint), tint)
                selection.setGlyph(ReverbIconDrawable(ReverbIcon.MultiSelect, density, tint), tint)
                glyphDensity = density
            }
        }
        bar.bindStyle(density, 0, style)
        chrome.bind(style, style.backgroundColor(palette))
        title.setText(if (selecting) texts.selectionCount(selected) else texts.title)
        theme.bindParagraph(title, if (selecting) style.selectionTitleRole else style.leadingTitleRole)
        title.animateTextColor(style.contentColor(palette))
        navigation.setDescription(if (selecting) texts.clearSelection else texts.back)
        navigation.bindToolbarAction(density, palette, true, NativeToolbarActionTone.MUTED, back)
        destructive.visibility = if (count > 0) VISIBLE else INVISIBLE
        destructive.setDescription(if (selecting) texts.deleteSelected else texts.clearAll)
        destructive.bindToolbarAction(density, palette, ready && (if (selecting) selected > 0 else count > 0),
            NativeToolbarActionTone.DESTRUCTIVE, if (selecting) deleteSelected else clearAll)
        selection.visibility = if (count > 0) VISIBLE else INVISIBLE
        selection.setDescription(when {
            !selecting -> texts.multiSelect
            selected == count -> texts.deselectAll
            else -> texts.selectAll
        })
        selection.bindToolbarAction(density, palette, ready && count > 0, NativeToolbarActionTone.NEUTRAL, select)
    }
}

private class IncidentsEmptyContent(
    context: Context,
    private var appearance: IncidentsContentAppearance,
    private val texts: ReverbIncidentTexts,
    frames: MonotonicFrameClock,
    scope: CoroutineScope,
    durationScale: () -> Float,
) : ViewGroup(context) {
    private val loading = IncidentsLoadingView(context, appearance.card.palette.primary, frames, scope, durationScale)
    private val icon = IncidentStatusIcon(context, appearance.card.palette.muted)
    private val title = paragraph(context, appearance.card, appearance.titleMedium, appearance.card.palette.ink)
    private val detail = paragraph(context, appearance.card, appearance.card.bodyMedium, appearance.card.palette.muted)
    private var state = ReverbIncidentHistory()
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(loading); addView(icon); addView(title); addView(detail)
        bind(state)
    }

    private var sourceAccessibilityNode: ((View) -> NativeSourceAccessibilityNode)? = null

    fun bindSourceAccessibility(sourceNode: ((View) -> NativeSourceAccessibilityNode)?) {
        sourceAccessibilityNode = sourceNode
        loading.bindSourceAccessibility(sourceNode?.let { node -> { node(loading) } })
    }

    override fun addChildrenForAccessibility(outChildren: ArrayList<View>) {
        val children = ArrayList<View>()
        super.addChildrenForAccessibility(children)
        children.filterTo(outChildren) { it !== loading || sourceAccessibilityNode?.invoke(it)?.present != false }
    }

    fun bind(value: ReverbIncidentHistory) {
        state = value
        loading.visibility = if (value.status == ReverbIncidentLoadStatus.LOADING) VISIBLE else GONE
        icon.visibility = if (loading.visibility == VISIBLE) GONE else VISIBLE
        title.visibility = if (value.empty) VISIBLE else GONE
        if (value.empty) {
            title.setText(texts.empty)
            detail.setText(texts.emptyDetail)
        } else {
            detail.setText(if (value.status == ReverbIncidentLoadStatus.LOADING) texts.loading else texts.unavailable)
        }
        requestLayout()
    }

    fun setAppearance(value: IncidentsContentAppearance) {
        appearance = value
        loading.setColor(value.card.palette.primary)
        icon.setColor(value.card.palette.muted)
        title.setTypography(value.titleMedium, value.card.density, value.card.fontResolver)
        title.animateTextColor(value.card.palette.ink)
        detail.setTypography(value.card.bodyMedium, value.card.density, value.card.fontResolver)
        detail.animateTextColor(value.card.palette.muted)
        requestLayout()
    }

    private fun dp(value: Float) = (value * appearance.card.density.density).roundToInt()
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val padding = dp(28f)
        val availableWidth = max(0, width - padding * 2)
        val availableHeight = max(0, height - padding * 2)
        var usedHeight = 0
        var count = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            if (count++ > 0) usedHeight += dp(10f)
            if (child === loading || child === icon) {
                val size = dp(if (child === loading) 28f else 34f)
                child.measure(MeasureSpec.makeMeasureSpec(minOf(size, availableWidth), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(minOf(size, max(0, availableHeight - usedHeight)), MeasureSpec.EXACTLY))
            } else child.measure(MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(max(0, availableHeight - usedHeight), MeasureSpec.AT_MOST))
            usedHeight += child.measuredHeight
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        // These four private children keep fixed indices. Snapshot membership before layout callbacks.
        var visibleChildren = 0
        var visibleCount = 0
        var total = 0
        var columnWidth = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            visibleChildren = visibleChildren or (1 shl index)
            visibleCount++
            total += child.measuredHeight
            columnWidth = max(columnWidth, child.measuredWidth)
        }
        total += max(0, visibleCount - 1) * dp(10f)
        val padding = dp(28f)
        // Box centers the intrinsic Column first; Column then centers each child independently.
        val columnX = padding + ((max(0, width - padding * 2) - columnWidth) / 2f).roundToInt()
        var y = padding + ((max(0, height - padding * 2) - total) / 2f).roundToInt()
        for (index in 0 until childCount) {
            if (visibleChildren and (1 shl index) == 0) continue
            val child = getChildAt(index)
            val x = columnX + ((columnWidth - child.measuredWidth) / 2f).roundToInt()
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            y += child.measuredHeight + dp(10f)
        }
    }
}

/** Incidents' unmerged progress leaf; other callers retain their own description/merge policy. */
private class IncidentsLoadingView(
    context: Context,
    color: Int,
    frames: MonotonicFrameClock,
    scope: CoroutineScope,
    durationScale: () -> Float,
) : NativeCircularProgress(context, color, frames, scope, durationScale) {
    private var sourceAccessibilityNode: (() -> NativeSourceAccessibilityNode)? = null

    init { isScreenReaderFocusable = true }

    fun bindSourceAccessibility(sourceNode: (() -> NativeSourceAccessibilityNode)?) {
        sourceAccessibilityNode = sourceNode
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.text = null
        info.contentDescription = null
        info.isFocusable = false
        info.isClickable = false
        info.isScreenReaderFocusable = true
        info.movementGranularities = 0
        val actions = info.actionList
        for (index in actions.size - 1 downTo 0) info.removeAction(actions[index])
        info.addAction(if (isAccessibilityFocused) AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
            else AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
        sourceAccessibilityNode?.invoke()?.populate(info)
    }

    override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo? =
        if (sourceAccessibilityNode?.invoke()?.present == false) null else super.createAccessibilityNodeInfo()

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean =
        if (sourceAccessibilityNode?.invoke()?.present == false) false else when (action) {
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
            AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> super.performAccessibilityAction(action, arguments)
            else -> false
        }
}

private class IncidentStatusIcon(context: Context, color: Int) : View(context) {
    private val icon = ReverbIconDrawable(ReverbIcon.Incidents, resources.displayMetrics.density, color)
    private val colorMotion = ViewColorTransition(this, intArrayOf(color), ::applyColor)
    private fun applyColor() { icon.setTint(colorMotion.color(0)); invalidate() }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun setColor(color: Int) {colorMotion.retarget(color)}
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { icon.setBounds(0, 0, w, h) }
    override fun onDraw(canvas: Canvas) { icon.draw(canvas) }
}

private class IncidentStaleView(context: Context, private var appearance: IncidentCardAppearance, message: String) : ViewGroup(context) {
    private val text = paragraph(context, appearance, appearance.bodyMedium, appearance.palette.muted).apply {
        setText(message)
    }
    init { addView(text); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun bind(value: IncidentCardAppearance) {
        appearance = value
        text.setTypography(value.bodyMedium, value.density, value.fontResolver)
        text.animateTextColor(value.palette.muted)
        requestLayout()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val inset = (4f * appearance.density.density).roundToInt()
        val width = MeasureSpec.getSize(widthMeasureSpec)
        text.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        setMeasuredDimension(resolveSize(text.measuredWidth, widthMeasureSpec), text.measuredHeight + inset * 2)
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val inset = (4f * appearance.density.density).roundToInt()
        val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - text.measuredWidth else 0
        text.layout(x, inset, x + text.measuredWidth, inset + text.measuredHeight)
    }
}

private class IncidentsAdapter(
    private val context: Context,
    private var appearance: IncidentCardAppearance,
    private val texts: ReverbIncidentTexts,
    private val menus: IncidentCardMenuHost,
    private val actions: ReverbIncidentActions,
    private val authority: IncidentCardAuthority,
    private val inputRoot: View,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private data class Item(
        val key: Any,
        val id: Long,
        val row: ReverbIncidentOccurrence? = null,
        val heading: String? = null,
        val edges: NativeGroupEdges? = null,
    )
    private data class HeadingKey(val key: String)
    private object SelectionPayload
    private var items = emptyList<Item>()
    private var occurrencePositions = emptyMap<String, Int>()
    private var selecting = false
    private var selectedKeys = hashSetOf<String>()
    private var nextId = 1L
    private var canMutate = false
    init { setHasStableIds(true) }

    fun submit(state: ReverbIncidentHistory, selection: Set<String>?) {
        val old = items
        val oldCanMutate = canMutate
        val oldSelecting = selecting
        val oldSelected = selectedKeys
        val ids = old.associate { it.key to it.id }
        val next = buildList {
            if (state.rows.isNotEmpty() && state.status == ReverbIncidentLoadStatus.UNAVAILABLE) {
                add(Item("incident-history-status", ids["incident-history-status"] ?: nextId++))
            }
            state.groups.forEach { group ->
                val heading = HeadingKey(group.key)
                add(Item(heading, ids[heading] ?: nextId++, heading = group.message))
                group.occurrences.forEachIndexed { index, row ->
                    add(Item(row.key, ids[row.key] ?: nextId++, row = row,
                        edges = NativeGroupEdges(index == 0, index == group.occurrences.lastIndex)))
                }
            }
        }
        val difference = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = next.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                old[oldItemPosition].key == next[newItemPosition].key
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val before = old[oldItemPosition]
                val after = next[newItemPosition]
                if (before != after) return false
                val key = after.row?.key ?: return true
                val previousFlag = if (oldSelecting) key in oldSelected else null
                return oldCanMutate == state.canMutate && previousFlag == selection?.contains(key)
            }
        })
        items = next
        occurrencePositions = buildMap {
            next.forEachIndexed { index, item -> item.row?.let { put(it.key, index) } }
        }
        selecting = selection != null
        selectedKeys = selection.orEmpty().toHashSet()
        canMutate = state.canMutate
        difference.dispatchUpdatesTo(this)
    }

    fun occurrence(key: String): ReverbIncidentOccurrence? = occurrencePositions[key]?.let { items[it].row }

    fun setSelected(key: String, selected: Boolean) {
        val position = occurrencePositions[key] ?: return
        if (!selecting) return
        val changed = if (selected) selectedKeys.add(key) else selectedKeys.remove(key)
        if (changed) notifyItemChanged(position, SelectionPayload)
    }

    fun setSelection(selection: Set<String>?) {
        val oldSelecting = selecting
        val oldSelected = selectedKeys
        selecting = selection != null
        selectedKeys = selection.orEmpty().toHashSet()
        if (oldSelecting == selecting && oldSelected == selectedKeys) return
        // Consecutive changed occurrences share one notification. Heading/stale rows never rebind.
        var rangeStart = -1
        for (position in 0..items.size) {
            val key = items.getOrNull(position)?.row?.key
            val changed = key != null && (oldSelecting != selecting ||
                (key in oldSelected) != (key in selectedKeys))
            if (changed && rangeStart < 0) rangeStart = position
            if (!changed && rangeStart >= 0) {
                notifyItemRangeChanged(rangeStart, position - rangeStart, SelectionPayload)
                rangeStart = -1
            }
        }
    }

    private fun selectionFor(key: String): Boolean? = if (selecting) key in selectedKeys else null

    fun setAppearance(value: IncidentCardAppearance) {
        appearance = value
        notifyItemRangeChanged(0, itemCount)
    }
    fun joinsNext(position: Int): Boolean = items.getOrNull(position)?.edges?.last == false

    override fun getItemCount() = items.size
    override fun getItemId(position: Int) = items[position].id
    override fun getItemViewType(position: Int) = when {
        items[position].heading != null -> 2
        items[position].row != null -> 1
        else -> 0
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = when (viewType) {
            0 -> IncidentStaleView(context, appearance, texts.stale)
            2 -> paragraph(context, appearance, appearance.bodyLarge, appearance.palette.ink).apply {
                ViewCompat.setAccessibilityHeading(this, true)
            }
            else -> ReverbIncidentCardView(context, appearance, menus, texts, authority).apply {
                parentInterestedInDown = { true }
                pointerInputRoot = inputRoot
            }
        }
        view.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return object : RecyclerView.ViewHolder(view) {}
    }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (val view = holder.itemView) {
            is IncidentStaleView -> view.bind(appearance)
            is ParagraphView -> {
                val density = appearance.density.density
                view.setPaddingRelative((8f * density).roundToInt(), (12f * density).roundToInt(),
                    (8f * density).roundToInt(), (4f * density).roundToInt())
                view.setTypography(appearance.bodyLarge.copy(fontWeight = smallthingz.ui.text.font.FontWeight.SemiBold),
                    appearance.density, appearance.fontResolver)
                view.animateTextColor(appearance.palette.ink)
                view.setText(checkNotNull(item.heading))
            }
            is ReverbIncidentCardView -> {
                view.setAppearance(appearance)
                val row = checkNotNull(item.row)
                view.bind(row, canMutate, selectionFor(row.key), actions, checkNotNull(item.edges))
            }
        }
    }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        val row = items[position].row
        val view = holder.itemView as? ReverbIncidentCardView
        if (row != null && view != null && payloads.isNotEmpty() && payloads.all { it === SelectionPayload } &&
            view.bindSelection(row.key, selectionFor(row.key))) return
        onBindViewHolder(holder, position)
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        (holder.itemView as? ReverbIncidentCardView)?.unbind()
        super.onViewRecycled(holder)
    }
    fun disposeVisible(list: RecyclerView) {
        for (index in 0 until list.childCount) (list.getChildAt(index) as? ReverbIncidentCardView)?.unbind()
    }
}

private fun paragraph(context: Context, appearance: IncidentCardAppearance, style: TextStyle, color: Int) =
    ParagraphView(context, style, appearance.density, appearance.fontResolver, color)
