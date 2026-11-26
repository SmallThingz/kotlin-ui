package smallthingz.ui.reverb

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.DragEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.Space
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import smallthingz.ui.R
import smallthingz.ui.components.NativeForeground
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.util.closePreservingPrimaryFailure
import kotlin.math.roundToInt

/** Content only. Preview bitmap ownership and tab identity remain with the browser. */
data class ReverbBrowserPanelTab<Key : Any>(
    val key: Key,
    val title: String,
    val origin: String?,
    val preview: Bitmap?,
    val fallbackIcon: Drawable?,
    val current: Boolean,
    val currentLabel: String,
    val closeLabel: String,
    val enabled: Boolean = true,
)

enum class ReverbBrowserPanelTabAction { BACK, NEW, SELECT, CLOSE }

/** Candidate tab overview contract, built from Reverb toolbar, group and action recipes. */
open class ReverbBrowserPanelTabs<Key : Any>(context: Context, private val theme: ReverbTheme,
    title: String, backLabel: String, newLabel: String,
) : ReverbBrowserPanelLayout(context) {
    var onTabAction: ((Key?, ReverbBrowserPanelTabAction) -> Unit)? = null
    /**
     * Request moving key into targetKey's current slot: after it when moving forward, before it
     * when moving backward. The host owns ordering and may synchronously commit with bindTabs.
     * Null removes drag and accessibility move affordances; it does not change tab actions.
     */
    var onTabMove: ((key: Key, targetKey: Key) -> Unit)? = null
        set(value) {
            if (field === value) return
            cancelReorder()
            field = value
            cards.values.forEach { it.bindReorderActions() }
        }
    private val environment = ReverbEnvironment(theme)
    private val back = browserPanelToolbarAction(context, theme, backLabel, ReverbIcon.Back) {
        if (!panelClosed) onTabAction?.invoke(null, ReverbBrowserPanelTabAction.BACK)
    }
    private val heading = theme.paragraph(context, title, theme.appearance.toolbars.centeredTitleRole)
    private val newTab = NativeMaterialButtonView(context, ReverbBottomActionDock.buttonStyle(theme),
        theme.density, theme.fontResolver, onClick = ::newTabRequested).apply {
        bind(newLabel, true, theme.palette.buttonPalette, onClick = ::newTabRequested)
        setLeadingIcon(ReverbIconDrawable(ReverbIcon.Add, theme.density.density, theme.palette.ink))
        filterTouchesWhenObscured = true
    }
    private val cards = linkedMapOf<Key, Card>()
    private val reorder = ReverbBrowserPanelReorder<Key>()
    private var dragSource: View? = null
    private var focusHandoffToken: Any? = null
    private var pendingKeyboardContinuation: List<Key>? = null
    private var pendingAccessibilityContinuation: List<Key>? = null
    private var edgeScrollIdentity: Any? = null
    private var edgeScrollX = Float.NaN
    private var edgeScrollY = Float.NaN
    private var edgeScrollPosted = false
    private var edgeScrollLastFrame = 0L
    private val edgeScrollFrame = object : Runnable {
        override fun run() {
            edgeScrollPosted = false
            val identity = edgeScrollIdentity
            val direction = browserTabDragEdgeDirection(edgeScrollX, edgeScrollY, scroll.width, scroll.height, dp(48))
            if (panelClosed || reorder.source(identity) == null || onTabMove == null ||
                !scroll.browserPanelAdmitsInput() || direction == 0 || !scroll.canScrollVertically(direction)) {
                if (edgeScrollIdentity === identity) stopEdgeScroll()
                return
            }
            if (edgeScrollIdentity !== identity || reorder.source(identity) == null) return
            val now = SystemClock.uptimeMillis()
            val elapsed = (now - edgeScrollLastFrame).coerceIn(1L, 32L)
            edgeScrollLastFrame = now
            val distance = (dp(240) * elapsed / 1000f).roundToInt().coerceAtLeast(1)
            val before = scroll.scrollY
            scroll.scrollBy(0, direction * distance)
            // One frame at a time, only while this live drag can make progress. Scroll callbacks
            // may synchronously rebind/retire the panel, so recheck identity before scheduling.
            if (edgeScrollIdentity !== identity) return
            if (reorder.source(identity) != null && scroll.scrollY != before &&
                scroll.canScrollVertically(direction)) scheduleEdgeScroll()
            else stopEdgeScroll()
        }
    }
    private val spacing = dp(8)
    private var columns = 1
    private var cardWidth = 0
    private var cardViewportHeight: Int? = null
    private val grid = GridLayout(context).apply { setPadding(spacing, spacing, spacing, spacing) }
    private val scroll = object : ScrollView(context) {
        override fun dispatchDragEvent(event: DragEvent): Boolean {
            // Observe native viewport coordinates without intercepting the card's drag/drop route.
            val identity = event.localState
            val location = event.action == DragEvent.ACTION_DRAG_LOCATION
            val x = event.x
            val y = event.y
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED, DragEvent.ACTION_DRAG_EXITED,
                DragEvent.ACTION_DROP, DragEvent.ACTION_DRAG_ENDED ->
                    if (reorder.source(event.localState) != null ||
                        (event.localState != null && edgeScrollIdentity === event.localState)) stopEdgeScroll()
            }
            val handled = super.dispatchDragEvent(event)
            // Native drag-focus changes send EXITED directly to the old innermost receiver.
            // Rearm only after that handoff, using the viewport coordinates saved above.
            if (location) updateEdgeScroll(identity, x, y)
            return handled
        }
        override fun onMeasure(w: Int, h: Int) {
            // LinearLayout has already allocated the top bar and inset-owning footer. A grid
            // row may use this remaining viewport; further rows still scroll normally.
            val available = if (MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED) null else
                (MeasureSpec.getSize(h) - paddingTop - paddingBottom - grid.paddingTop -
                    grid.paddingBottom - 2 * (spacing / 2)).coerceAtLeast(0)
            if (cardViewportHeight != available) {
                cardViewportHeight = available
                cards.values.forEach { it.body.requestLayout() }
            }
            super.onMeasure(w, h)
        }
    }.apply {
        setOnDragListener { _, event ->
            val current = reorder.source(event.localState) != null
            when (event.action) {
                // Keep native drag focus in the viewport even over gaps between cards.
                DragEvent.ACTION_DRAG_STARTED, DragEvent.ACTION_DRAG_LOCATION -> current
                DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> {
                    if (current) stopEdgeScroll()
                    current
                }
                else -> false // Only a card accepts DROP and requests a move.
            }
        }
        addView(grid, FrameLayout.LayoutParams(-1, -2))
    }
    private val footer = ReverbBottomActionDock(context, theme, newTab)
    init {
        background = theme.appearance.backdrops.pageBackground(theme.palette)
        ViewCompat.setAccessibilityPaneTitle(this, title)
        ViewCompat.setAccessibilityHeading(heading, true)
        addView(ReverbTitledTopBarLayout(context, theme.density.density, theme.palette, back, heading, Space(context),
            style = theme.appearance.toolbars),
            LayoutParams(-1, -2))
        addView(scroll, LayoutParams(-1, 0, 1f))
        addView(footer, LayoutParams(-1, -2))
    }
    private fun newTabRequested() {
        if (!panelClosed && newTab.browserPanelAdmitsInput() && !panelClosed)
            onTabAction?.invoke(null, ReverbBrowserPanelTabAction.NEW)
    }
    fun focusBackAction() { if (!back.accessibilityAction.requestFocus()) requestFocus() }
    val scrollPosition: Int get() = scroll.scrollY
    val footerHeight: Int get() = footer.height
    /** Remaining safe insets only: the footer includes the full bottom inset in footerHeight. */
    fun applySafeInsets(insets: Insets) { if (!panelClosed) footer.applySafeInsets(insets) }
    /** Current preview bounds in this panel, for anchored presentation and geometry consumers. */
    fun tabPreviewBounds(key: Key): Rect? = cards[key]?.preview?.let { preview ->
        Rect(0, 0, preview.width, preview.height).also { offsetDescendantRectToMyCoords(preview, it) }
    }
    fun restoreScrollPosition(position: Int) {
        if (position != 0) scroll.doOnLayout { if (!panelClosed) scroll.scrollTo(0, position) }
    }
    fun bindTabs(title: String, entries: List<ReverbBrowserPanelTab<Key>>) {
        if (panelClosed) return
        val keys = entries.map { it.key }.toSet()
        require(keys.size == entries.size) { "Tab keys must be unique" }
        cancelReorder()
        // Capture each focus channel before closing a node. Keyboard focus does not imply
        // TalkBack focus, including the close button's separate semantic role child.
        for (card in cards.values) if (card.content.key !in keys) {
            val continuation = (reorder.continuationAfterRemoval(card.content.key) + entries.map { it.key }).distinct()
            if (card.gesture.hasFocus()) pendingKeyboardContinuation = continuation
            if (card.ownsAccessibilityFocus()) pendingAccessibilityContinuation = continuation
        }
        // A second authoritative bind before layout must not lose an already pending handoff.
        pendingKeyboardContinuation = pendingKeyboardContinuation?.let { (it + entries.map { entry -> entry.key }).distinct() }
        pendingAccessibilityContinuation = pendingAccessibilityContinuation?.let { (it + entries.map { entry -> entry.key }).distinct() }
        focusHandoffToken = null
        reorder.bind(entries.map { it.key }, entries.filter { it.enabled }.map { it.key }.toSet())
        val iterator = cards.values.iterator()
        while (iterator.hasNext()) {
            val card = iterator.next()
            if (card.content.key !in keys) {
                card.close()
                grid.removeView(card.gesture)
                iterator.remove()
            }
        }
        heading.setText(title)
        entries.forEachIndexed { index, entry ->
            val card = cards.getOrPut(entry.key) { Card(entry) }
            card.gesture.restoreRetainedTab()
            card.bind(entry)
            if (grid.indexOfChild(card.gesture) != index) {
                val focus = card.gesture.findFocus()
                grid.removeView(card.gesture)
                grid.addView(card.gesture, index, cardParams(index))
                focus?.requestFocus()
            } else card.gesture.layoutParams = cardParams(index)
        }
        cards.values.forEach { it.bindReorderActions() }
        handoffRemovedFocus()
    }
    @Suppress("DEPRECATION") // Nodes returned by providers may be pooled on older Android versions.
    private fun hasAccessibilityFocus(view: View, includeProviders: Boolean = false): Boolean {
        if (view.isAccessibilityFocused) return true
        if (includeProviders) view.accessibilityNodeProvider?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.let {
            it.recycle()
            return true
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            val child = view.getChildAt(index) ?: continue
            if (hasAccessibilityFocus(child, includeProviders)) return true
        }
        return false
    }

    private fun cancelFocusHandoff() {
        focusHandoffToken = null
        pendingKeyboardContinuation = null
        pendingAccessibilityContinuation = null
    }

    private fun currentFocusableTab(candidates: List<Key>): View? = candidates.firstNotNullOfOrNull { key ->
        cards[key]?.takeIf { it.content.enabled && it.row.browserPanelAdmitsInput() }?.row
    }

    private fun View.isPresentForFocus(): Boolean = isEnabled && isAttachedToWindow && isShown && hasWindowFocus()

    private fun handoffRemovedFocus() {
        if (pendingKeyboardContinuation == null && pendingAccessibilityContinuation == null) return
        val token = Any().also { focusHandoffToken = it }
        fun keyboardUnclaimed(): Boolean {
            val focused = rootView.findFocus()
            // Removing a focused child can make the focusable panel the framework's fallback.
            return !panelClosed && focusHandoffToken === token &&
                (focused == null || focused === this)
        }
        val keyboard = pendingKeyboardContinuation
        pendingKeyboardContinuation = null
        if (keyboard != null && keyboardUnclaimed()) {
            val target = currentFocusableTab(keyboard)
            if (keyboardUnclaimed() && target?.requestFocus() != true &&
                browserPanelAdmitsInput() && keyboardUnclaimed()) focusBackAction()
        }
        if (focusHandoffToken !== token) return
        if (pendingAccessibilityContinuation == null) {
            focusHandoffToken = null
            return
        }
        grid.doOnLayout {
            if (panelClosed || focusHandoffToken !== token) return@doOnLayout
            val accessibility = pendingAccessibilityContinuation
            pendingAccessibilityContinuation = null
            fun accessibilityUnclaimed(): Boolean = !panelClosed && focusHandoffToken === token &&
                !hasAccessibilityFocus(rootView, includeProviders = true) &&
                !panelClosed && focusHandoffToken === token
            if (accessibility != null && accessibilityUnclaimed()) {
                val target = currentFocusableTab(accessibility) ?: back.accessibilityAction
                // The surviving neighbor may be outside the scroll viewport after a removal.
                if (target.browserPanelAdmitsInput() && accessibilityUnclaimed() && target.isPresentForFocus()) {
                    target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height), true)
                    if (target.browserPanelAdmitsInput() && accessibilityUnclaimed() &&
                        target.isPresentForFocus()) target.performAccessibilityAction(
                        AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
                }
            }
            if (focusHandoffToken === token) focusHandoffToken = null
        }
    }
    fun updateTab(entry: ReverbBrowserPanelTab<Key>) {
        if (panelClosed) return
        val card = cards[entry.key] ?: return
        val eligibilityChanged = card.content.enabled != entry.enabled
        if (eligibilityChanged) {
            cancelReorder()
            reorder.setEnabled(entry.key, entry.enabled)
        }
        card.bind(entry)
        if (eligibilityChanged) cards.values.forEach { it.bindReorderActions() }
    }
    private fun cancelReorder() {
        stopEdgeScroll()
        val source = dragSource
        dragSource = null
        reorder.cancel()
        source?.cancelDragAndDrop()
    }
    private fun stopEdgeScroll() {
        edgeScrollIdentity = null
        edgeScrollX = Float.NaN
        edgeScrollY = Float.NaN
        if (edgeScrollPosted) scroll.removeCallbacks(edgeScrollFrame)
        edgeScrollPosted = false
    }
    private fun scheduleEdgeScroll() {
        if (!edgeScrollPosted) {
            edgeScrollPosted = true
            scroll.postOnAnimation(edgeScrollFrame)
        }
    }
    private fun updateEdgeScroll(identity: Any?, x: Float, y: Float) {
        if (reorder.source(identity) == null) return
        val direction = browserTabDragEdgeDirection(x, y, scroll.width, scroll.height, dp(48))
        if (onTabMove == null || direction == 0 ||
            !scroll.browserPanelAdmitsInput() || !scroll.canScrollVertically(direction)) {
            stopEdgeScroll()
            return
        }
        if (reorder.source(identity) == null) return
        if (edgeScrollIdentity !== identity) edgeScrollLastFrame = SystemClock.uptimeMillis()
        edgeScrollIdentity = identity
        edgeScrollX = x
        edgeScrollY = y
        scheduleEdgeScroll()
    }
    private fun moveTab(key: Key, target: Key): Boolean {
        val callback = onTabMove ?: return false
        if (panelClosed || !reorder.canMove(key, target)) return false
        val source = cards[key] ?: return false
        val destination = cards[target] ?: return false
        fun current() = !panelClosed && onTabMove === callback && reorder.canMove(key, target) &&
            cards[key] === source && cards[target] === destination &&
            source.content.enabled && destination.content.enabled
        fun admitted() = current() && source.row.browserPanelAdmitsInput() &&
            destination.row.browserPanelAdmitsInput() && current()
        if (!admitted()) return false
        cancelReorder()
        if (!admitted()) return false
        val restoreAccessibilityFocus = source.row.isAccessibilityFocused
        callback(key, target)
        // A synchronous host rebind may detach/reinsert the moved row. Resolve it afresh by key.
        if (restoreAccessibilityFocus && !panelClosed && onTabMove === callback) cards[key]?.row?.let { row ->
            if (row.browserPanelAdmitsInput() && !hasAccessibilityFocus(rootView, includeProviders = true) &&
                !panelClosed && onTabMove === callback &&
                cards[key]?.row === row && row.isPresentForFocus()) row.performAccessibilityAction(
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        }
        return true
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        if (!hasWindowFocus) { cancelFocusHandoff(); cancelReorder() }
        super.onWindowFocusChanged(hasWindowFocus)
    }
    override fun onCancelPendingInputEvents() {
        // Long-press takeover cancels only the child gesture; panel-wide retirement owns this token.
        if (dragSource != null) cancelReorder()
        cancelFocusHandoff()
        super.onCancelPendingInputEvents()
    }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) cancelFocusHandoff()
        // Visibility callbacks may arrive during View construction, before reorder is initialized.
        if (visibility != VISIBLE && dragSource != null) cancelReorder()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) cancelFocusHandoff()
        if (visibility != VISIBLE && dragSource != null) cancelReorder()
    }
    override fun onDetachedFromWindow() {
        cancelFocusHandoff()
        cancelReorder()
        super.onDetachedFromWindow()
    }
    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val count = columnCount(width / resources.displayMetrics.density)
        val nextWidth = ((width - 2 * spacing) / count - 2 * (spacing / 2)).coerceAtLeast(0)
        if (columns != count || cardWidth != nextWidth) {
            columns = count; cardWidth = nextWidth
            for (index in 0 until grid.childCount) grid.getChildAt(index).layoutParams = cardParams(index)
        }
        super.onMeasure(w, h)
    }
    private fun cardParams(index: Int) = GridLayout.LayoutParams(
        GridLayout.spec(index / columns), GridLayout.spec(index % columns)).apply {
        width = cardWidth; height = LayoutParams.WRAP_CONTENT
        val margin = spacing / 2
        setMargins(margin, margin, margin, margin)
    }
    private inner class Card(initial: ReverbBrowserPanelTab<Key>) : AutoCloseable {
        private var snapshot: ReverbBrowserPanelTab<Key>? = null
        val content get() = checkNotNull(snapshot)
        private var closed = false
        private val recipe = theme.appearance.groupSurfaces.settingsChoices
        private val surface = recipe.create(context, ReverbTextSurface::invalidate).also { recipe.bind(it, theme.palette) }
        val gesture = ReverbBrowserPanelSwipe(context, { it * .35f },
            canSwipe = { !closed && onTabAction != null && !reorder.dragging && content.enabled &&
                surface.browserPanelAdmitsInput() },
            onDrag = { surface.translationX = it }, onSwipe = { swipeCloseRequested() })
        val row = ReverbBrowserPanelSettingRow(context, theme, ReverbBrowserPanelSetting(initial.title, actionId = "select")) {
            dispatch(ReverbBrowserPanelTabAction.SELECT)
        }.apply {
            titleView.setLines(1)
            titleView.ellipsize = TextUtils.TruncateAt.END
            // A tab title needs less inset than a settings row. The adjacent close slot
            // already supplies its own visual inset inside a full 48dp touch target.
            setPaddingRelative(dp(8), dp(4), 0, dp(4))
            minimumHeight = dp(48)
        }
        // The input/semantic owner must fill the slot; a 48dp wrapper around a 40dp
        // ActionVisual does not receive DOWN in its outer band. The glyph stays 24dp.
        private val close = ReverbStandardIconButtonView(context, environment,
            { this@ReverbBrowserPanelTabs }, actionSizeDp = 48f)
        private val moveActions = mutableListOf<Int>()
        fun ownsAccessibilityFocus(): Boolean = hasAccessibilityFocus(row) || hasAccessibilityFocus(close)
        private val header = FrameLayout(context).apply {
            // Keep SELECT and CLOSE separate. Reserve the close target before measuring the
            // single-line title, rather than overlaying it or stacking it below long text.
            addView(row, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL).apply {
                marginEnd = dp(48)
            })
            addView(close, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER_VERTICAL or Gravity.END))
        }
        private val image = object : ImageView(context) {
            private val topFit = Matrix()
            fun alignPreview() {
                if (scaleType != ScaleType.MATRIX) return
                val art = drawable ?: return
                if (width <= 0 || height <= 0 || art.intrinsicWidth <= 0 || art.intrinsicHeight <= 0) return
                val scale = maxOf(width.toFloat() / art.intrinsicWidth, height.toFloat() / art.intrinsicHeight)
                topFit.setScale(scale, scale)
                topFit.postTranslate((width - art.intrinsicWidth * scale) / 2f, 0f)
                imageMatrix = topFit
            }
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                alignPreview()
            }
        }
        val preview = FrameLayout(context).apply {
            setOnClickListener { dispatch(ReverbBrowserPanelTabAction.SELECT, this) }
            filterTouchesWhenObscured = true
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            image.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(image, FrameLayout.LayoutParams(-1, -1))
        }
        val body = object : ViewGroup(context) {
            init { addView(header); addView(preview) }
            override fun onMeasure(w: Int, h: Int) {
                val width = MeasureSpec.getSize(w)
                val exactWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
                header.measure(exactWidth, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                val previewHeight = browserTabPreviewHeight(width, dp(200), cardViewportHeight, header.measuredHeight)
                preview.measure(exactWidth, MeasureSpec.makeMeasureSpec(previewHeight, MeasureSpec.EXACTLY))
                // Never shrink text or the close target if the header alone exceeds a short
                // viewport. The full header remains scrollable, with a zero-height preview.
                setMeasuredDimension(resolveSize(width, w), header.measuredHeight + preview.measuredHeight)
            }
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                header.layout(0, 0, width, header.measuredHeight)
                preview.layout(0, header.bottom, width, header.bottom + preview.measuredHeight)
            }
        }
        init {
            close.visual.mergedRowAccessibility.enableStandaloneAccessibility({ !closed }) { snapshot?.closeLabel }
            close.visual.glyph.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            close.visual.filterTouchesWhenObscured = true
            close.visual.background = NativeForeground.foregroundBackground(theme.palette.pageControls)
            surface.addRow(body, NativeGroupLayout.LayoutParams(-1, -2))
            gesture.addView(surface, FrameLayout.LayoutParams(-1, -2))
            surface.setOnDragListener { _, event ->
                val key = reorder.source(event.localState)
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> key != null && !closed && content.enabled && onTabMove != null
                    DragEvent.ACTION_DROP -> key != null && moveTab(key, content.key)
                    DragEvent.ACTION_DRAG_EXITED -> {
                        if (key != null) stopEdgeScroll()
                        key != null
                    }
                    DragEvent.ACTION_DRAG_ENDED -> {
                        if (key != null) { dragSource = null; reorder.cancel() }
                        true
                    }
                    else -> key != null
                }
            }
            bind(initial)
        }
        fun bindReorderActions() {
            moveActions.forEach { ViewCompat.removeAccessibilityAction(row, it) }
            moveActions.clear()
            row.setOnLongClickListener(null)
            row.isLongClickable = false
            val entry = snapshot ?: return
            val owner = onTabMove ?: return
            if (closed || panelClosed || !entry.enabled) return
            val key = entry.key
            fun ownsActions() = !closed && !panelClosed && onTabMove === owner &&
                snapshot?.enabled == true && cards[key] === this
            if (reorder.canStart(key)) row.setOnLongClickListener {
                if (!ownsActions() || !row.browserPanelAdmitsInput() || !ownsActions()) return@setOnLongClickListener false
                val token = reorder.begin(key) ?: return@setOnLongClickListener false
                dragSource = row
                gesture.cancelBrowserPanelInput()
                val started = try { row.startDragAndDrop(null, View.DragShadowBuilder(surface), token, 0) }
                catch (failure: RuntimeException) { cancelReorder(); throw failure }
                if (!started) cancelReorder()
                started
            }
            for ((step, label) in listOf(-1 to R.string.reverb_browser_tab_move_earlier,
                1 to R.string.reverb_browser_tab_move_later)) {
                if (reorder.adjacent(key, step) == null) continue
                moveActions += ViewCompat.addAccessibilityAction(row, context.getString(label)) { _, _ ->
                    if (!ownsActions()) false else {
                        val target = reorder.adjacent(key, step)
                        target != null && moveTab(key, target)
                    }
                }
            }
        }
        private fun swipeCloseRequested() {
            if (!dispatch(ReverbBrowserPanelTabAction.CLOSE)) gesture.restoreRetainedTab()
        }
        private fun dispatch(action: ReverbBrowserPanelTabAction, source: View = surface): Boolean {
            val before = snapshot ?: return false
            val owner = onTabAction ?: return false
            if (!closed && !panelClosed && !reorder.dragging && before.enabled && cards[before.key] === this &&
                source.browserPanelAdmitsInput() && !closed && !panelClosed && snapshot === before &&
                cards[before.key] === this && onTabAction === owner) {
                owner(before.key, action)
                return true
            }
            return false
        }
        fun bind(entry: ReverbBrowserPanelTab<Key>) {
            if (closed) return
            val previous = snapshot
            snapshot = entry
            if (!entry.enabled) gesture.cancelBrowserPanelInput()
            if (previous?.title != entry.title || previous.enabled != entry.enabled) {
                row.bindContent(ReverbBrowserPanelSetting(entry.title, actionId = "select", enabled = entry.enabled))
            }
            if (previous?.title != entry.title || previous.origin != entry.origin) {
                row.contentDescription = listOfNotNull(entry.title, entry.origin).joinToString(", ")
            }
            if (previous?.current != entry.current || previous.currentLabel != entry.currentLabel) {
                ViewCompat.setStateDescription(row, entry.currentLabel.takeIf { entry.current })
                surface.setSurfacePreset(theme.palette.surfacePreset)
                surface.setPaletteImmediately(recipe.fillColor(theme.palette),
                    if (entry.current) theme.palette.primary else recipe.borderColor(theme.palette))
            }
            if (previous?.closeLabel != entry.closeLabel || previous.enabled != entry.enabled) {
                close.bind(environment, ReverbIcon.Close, entry.closeLabel, entry.enabled, theme.palette.muted, theme.palette.ink) {
                    dispatch(ReverbBrowserPanelTabAction.CLOSE, close.visual)
                }
            }
            if (previous == null || previous.preview !== entry.preview ||
                (entry.preview == null && previous.fallbackIcon !== entry.fallbackIcon)) {
                if (entry.preview != null) {
                    image.layoutParams = FrameLayout.LayoutParams(-1, -1)
                    image.imageTintList = null
                    image.scaleType = ImageView.ScaleType.MATRIX
                    image.setImageBitmap(entry.preview)
                    image.alignPreview()
                } else {
                    val size = dp(theme.appearance.buttons.raisedIcon.surface.iconSizeDp)
                    image.layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
                    image.imageTintList = ColorStateList.valueOf(theme.palette.muted)
                    image.scaleType = ImageView.ScaleType.FIT_CENTER
                    image.setImageDrawable(entry.fallbackIcon)
                }
            }
        }
        override fun close() {
            if (closed) return
            closed = true
            moveActions.forEach { ViewCompat.removeAccessibilityAction(row, it) }
            moveActions.clear()
            row.setOnLongClickListener(null)
            row.isLongClickable = false
            surface.setOnDragListener(null)
            // Preview ownership stays with the host; release references even if input retirement throws.
            preview.setOnClickListener(null)
            var failure = closePreservingPrimaryFailure(null) { gesture.cancelBrowserPanelInput() }
            failure = closePreservingPrimaryFailure(failure, row::close)
            failure = closePreservingPrimaryFailure(failure) {
                close.bind(environment, ReverbIcon.Close, "", false, theme.palette.muted, theme.palette.ink) {}
            }
            failure = closePreservingPrimaryFailure(failure) { image.setImageDrawable(null) }
            snapshot = null
            failure?.let { throw it }
        }
    }
    override fun close() {
        if (panelClosed) return
        cancelFocusHandoff()
        onTabAction = null
        onTabMove = null
        cancelReorder()
        var failure = closePreservingPrimaryFailure(null) { super.close() }
        cards.values.forEach { failure = closePreservingPrimaryFailure(failure, it::close) }
        cards.clear()
        failure = closePreservingPrimaryFailure(failure) { grid.removeAllViews() }
        failure = closePreservingPrimaryFailure(failure, newTab::close)
        failure?.let { throw it }
    }
    private fun dp(value: Int) = (value * theme.density.density).roundToInt()
    private fun dp(value: Float) = (value * theme.density.density).roundToInt()
    companion object {
        fun columnCount(widthDp: Float): Int = when { widthDp >= 720f -> 3; widthDp >= 296f -> 2; else -> 1 }
    }
}
