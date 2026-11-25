package smallthingz.ui.components

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.Shader
import android.os.Build
import android.util.SparseArray
import android.view.FocusFinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.OverScroller
import smallthingz.ui.common.DropdownSelectionPolicy
import smallthingz.ui.common.canOpen
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.unit.Density
import smallthingz.ui.util.closePreservingPrimaryFailure
import kotlin.math.abs
import kotlin.math.roundToInt

/** Draft anchored choice wheel. Colors, text, joined geometry and input all belong to the library. */
data class NativeAnchoredDropdownStyle(
    val cornerRadiusDp: Float = 16f,
    // Retained for source compatibility; vertical bounds now use the safe app viewport.
    val screenMarginDp: Float = 8f,
    val scrimOpacity: Float = .55f,
    val expandMillis: Long = 300,
    val settleMillis: Long = 210,
    val collapseMillis: Long = 240,
) {
    init {
        require(cornerRadiusDp.isFinite() && cornerRadiusDp >= 0f)
        require(screenMarginDp.isFinite() && screenMarginDp >= 0f)
        require(scrimOpacity.isFinite() && scrimOpacity in 0f..1f)
        require(expandMillis >= 0 && settleMillis >= 0 && collapseMillis >= 0)
    }
}

/**
 * A window owner for [NativeDropdown] fields. Each option occurs once in the rotated list.
 * A drag can coast after release, then snaps to a slot. In explicit mode a tap on a partial
 * option brings it into view. Tapping a fully visible option selects it and closes the wheel.
 * The caller owns values and must [close] this host with its screen lifetime.
 */
class NativeAnchoredDropdown(
    private val context: Context,
    private val overlayHost: FrameLayout,
    private val appearance: NativeUiAppearance,
    private val colors: NativeColorScheme,
    private val density: Density,
    private val resolver: FontFamily.Resolver,
    private val style: NativeAnchoredDropdownStyle = appearance.anchoredDropdown,
    private val palette: NativeDropdownColors = NativeDropdownColors(colors.field, colors.field, colors.muted, colors.ink, colors.border),
) : AutoCloseable {
    private var dismissCurrent: (() -> Unit)? = null
    internal var noiseIntensity = 0f
    internal var outlineWidthDp: Float? = null
    private var sessionIdentity: Any? = null
    private val titleStyle = appearance.typography[NativeTypographyRole.BODY_SMALL]
    private val selectedStyle = appearance.typography[NativeTypographyRole.BODY_LARGE]

    fun <T> createField(content: NativeDropdownContent<T>, onSelected: (T) -> Unit): NativeDropdown<T> =
        face(content) { anchor, value -> open(anchor, value, onSelected) }

    private fun <T> face(content: NativeDropdownContent<T>, optionRow: Boolean = false, wrapValue: Boolean = false,
        choiceRow: Boolean = false,
        activate: (View, NativeDropdownContent<T>) -> Unit): NativeDropdown<T> {
        return NativeDropdown(context,
            ParagraphView(context, titleStyle, density, resolver, palette.muted),
            ParagraphView(context, selectedStyle, density, resolver, palette.ink),
            if (choiceRow) createDropdownChoiceMark(context, palette) else createDropdownIcon(context, palette.muted),
            palette, { null }, { _, _ -> }, activate,
        ).apply {
            if (choiceRow) useChoiceLabel()
            if (wrapValue) wrapValues()
            setNoiseIntensity(noiseIntensity)
            bindDescription(content.label)
            bind(content, false, palette, present = optionRow || content.options.size > 1)
        }
    }

    fun <T> open(anchor: View, content: NativeDropdownContent<T>, onSelected: (T) -> Unit) {
        open(anchor, content, {}, onSelected)
    }

    /** Returns true when this session is still mounted. Each created session reports terminal once. */
    fun <T> open(anchor: View, content: NativeDropdownContent<T>, onTerminal: () -> Unit,
        onSelected: (T) -> Unit): Boolean =
        open(anchor, content, DropdownSelectionPolicy.PROGRESSIVE, onTerminal, onSelected)

    fun <T> open(anchor: View, content: NativeDropdownContent<T>, selectionPolicy: DropdownSelectionPolicy,
        onTerminal: () -> Unit, onSelected: (T) -> Unit): Boolean =
        open(anchor, content, selectionPolicy, false, onTerminal, onSelected)

    internal fun <T> open(anchor: View, content: NativeDropdownContent<T>, selectionPolicy: DropdownSelectionPolicy,
        choiceRows: Boolean, onTerminal: () -> Unit, onSelected: (T) -> Unit): Boolean {
        close()
        // A terminal callback can synchronously establish a newer session.
        if (sessionIdentity != null || !anchor.isAttachedToWindow || !anchor.isShown || anchor.width <= 0 || anchor.height <= 0) return false
        val options = content.options.toList()
        val selected = options.indexOf(content.selected)
        if (!content.canOpen(selectionPolicy)) return false
        require(options.distinct().size == options.size) { "Choice identities must be unique" }
        val session = Session(anchor, content.copy(options = options), selected, selectionPolicy, choiceRows, onSelected, onTerminal)
        sessionIdentity = session
        dismissCurrent = session::dispose
        try {
            session.show()
        } catch (failure: Throwable) {
            closePreservingPrimaryFailure(failure, session::dispose)
            throw failure
        }
        return sessionIdentity === session
    }

    override fun close() {
        val dismiss = dismissCurrent
        dismissCurrent = null; sessionIdentity = null
        dismiss?.invoke()
    }

    private inner class Session<T>(
        val anchor: View, val content: NativeDropdownContent<T>, val initial: Int,
        val selectionPolicy: DropdownSelectionPolicy, val choiceRows: Boolean, val select: (T) -> Unit,
        val onTerminal: () -> Unit,
    ) {
        val origin = IntArray(2).also(anchor::getLocationOnScreen)
        val observed = IntArray(2)
        val root = object : FrameLayout(context) {
            private var confirmationKey = KeyEvent.KEYCODE_UNKNOWN

            override fun onFocusChanged(gainFocus: Boolean, direction: Int, previous: Rect?) {
                super.onFocusChanged(gainFocus, direction, previous)
                if (!gainFocus) confirmationKey = KeyEvent.KEYCODE_UNKNOWN
            }

            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (!hasWindowFocus) confirmationKey = KeyEvent.KEYCODE_UNKNOWN
            }

            override fun focusSearch(focused: View?, direction: Int): View? =
                FocusFinder.getInstance().findNextFocus(this, focused, direction) ?: this
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                    if (event.action == KeyEvent.ACTION_UP) cancelSelection()
                    return true
                }
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        confirmationKey = KeyEvent.KEYCODE_UNKNOWN
                        if (selectionPolicy == DropdownSelectionPolicy.EXPLICIT) requestFocus()
                        settle(settled + if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1)
                    }
                    return true
                }
                if (selectionPolicy == DropdownSelectionPolicy.EXPLICIT && isFocused &&
                    (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                        event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_SPACE)) {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) confirmationKey = event.keyCode
                    if (event.action == KeyEvent.ACTION_UP) {
                        val activate = confirmationKey == event.keyCode && !event.isCanceled
                        confirmationKey = KeyEvent.KEYCODE_UNKNOWN
                        if (activate) collapse(settled)
                    }
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        val overlay = NativeModalOverlay(overlayHost, root, ::cancelSelection)
        val scrim = View(context).apply { setBackgroundColor(colors.scrim); alpha = 0f; setOnClickListener { cancelSelection() } }
        val wheel = Wheel()
        val rows = SparseArray<NativeDropdown<T>>()
        private val optionLabels = ArrayList<String>(content.options.size)
        val lastPosition = content.options.lastIndex
        var selectedPosition = -1
        val radii = nearestGroup()?.descendantCornerRadii(anchor) ?: FloatArray(4) { style.cornerRadiusDp * density.density }
        var plan: NativeAnchoredDropdownPlan? = null
        var position = 0f
        var settled = 0
        var lastPublished = initial
        var reveal = 0f
        var closing = false
        var disposed = false
        var animator: ValueAnimator? = null
        var anchorY = 0
        var viewport: NativeDropdownViewport? = null
        val windowBounds = Rect()
        val visibleFrame = Rect()
        var safeTop = 0
        var safeBottom = 0
        var modalX = 0
        var modalY = 0
        var modalWidth = 0
        var modalHeight = 0
        val rowHeight = anchor.height
        val rowWidth = anchor.width
        private var collapseAfterDraw = false
        private val startCollapse = Runnable {
            if (closing && !disposed) animate(settled.toFloat(), 0f, style.collapseMillis) {
                if (wheel.pointer < 0) dispose()
            }
        }
        val geometryGuard = ViewTreeObserver.OnPreDrawListener {
            anchor.getLocationOnScreen(observed)
            if (!anchor.isShown || !anchor.isAttachedToWindow || observed[0] != origin[0] || observed[1] != origin[1]
                || anchor.width != rowWidth || anchor.height != rowHeight) dispose()
            if (!disposed) {
                root.getLocationOnScreen(observed)
                if (observed[0] != modalX || observed[1] != modalY || root.width != modalWidth || root.height != modalHeight) dispose()
                if (!disposed) {
                    readSafeEdges()
                    if (safeTop != viewport?.top || safeBottom != viewport?.bottom) dispose()
                }
            }
            true
        }
        val anchorDetach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) = dispose()
        }

        fun nearestGroup(): NativeGroupLayout? {
            var p = anchor.parent
            while (p is View) { if (p is NativeGroupLayout) return p; p = p.parent }
            return null
        }

        fun show() {
            // A public caller's formatter may depend on mutable state or throw. Capture its
            // labels during open so scrolling cannot run caller code or change the open list.
            for (option in content.options) {
                if (disposed) return
                optionLabels.add(content.optionLabel(option))
            }
            if (disposed) return
            root.layoutDirection = anchor.layoutDirection
            root.filterTouchesWhenObscured = anchor.filterTouchesWhenObscured
            root.isFocusableInTouchMode = true
            root.addView(scrim, FrameLayout.LayoutParams(-1, -1))
            root.addView(wheel)
            scrim.isFocusable = false
            scrim.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            root.accessibilityPaneTitle = content.label
            overlay.show()
            if (disposed) return
            root.getLocationOnScreen(observed)
            modalX = observed[0]; modalY = observed[1]
            modalWidth = root.width; modalHeight = root.height
            val x = origin[0] - observed[0]
            anchorY = origin[1] - observed[1]
            if (Build.VERSION.SDK_INT >= 30) {
                windowBounds.set(context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds)
            }
            readSafeEdges()
            if (anchorY < safeTop || anchorY.toLong() + rowHeight > safeBottom) { dispose(); return }
            val geometry = NativeDropdownViewport(safeTop, safeBottom, anchorY, rowHeight)
            viewport = geometry
            val up = geometry.slotsAbove(content.options.size)
            val down = geometry.slotsBelow(content.options.size)
            // An unavailable stored value has no wheel row. Browse from the first real option.
            val layout = NativeAnchoredDropdownPlan(content.options.size, initial.coerceAtLeast(0), up, down)
            plan = layout; settled = layout.selectedPosition; position = settled.toFloat()
            if (initial >= 0) selectedPosition = settled
            wheel.layoutParams = FrameLayout.LayoutParams(rowWidth, root.height).apply {
                gravity = Gravity.TOP or Gravity.LEFT; leftMargin = x
            }
            overlay.relayout()
            if (disposed) return
            anchor.viewTreeObserver.addOnPreDrawListener(geometryGuard)
            anchor.addOnAttachStateChangeListener(anchorDetach)
            animate(position, 1f, style.expandMillis) {}
        }

        private fun readSafeEdges() {
            root.getWindowVisibleDisplayFrame(visibleFrame)
            var top = visibleFrame.top
            var bottom = visibleFrame.bottom
            if (Build.VERSION.SDK_INT >= 30) {
                val insets = root.rootWindowInsets?.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                if (insets != null) {
                    // Insets are relative to the window, not an already padded/panned/resized decor.
                    top = maxOf(top, windowBounds.top + insets.top)
                    bottom = minOf(bottom, windowBounds.bottom - insets.bottom)
                }
            }
            safeTop = nativeDropdownLocalEdge(top, modalY, root.height)
            safeBottom = nativeDropdownLocalEdge(bottom, modalY, root.height)
        }

        fun createRow(index: Int): NativeDropdown<T> {
            val optionIndex = checkNotNull(plan).optionAt(index)
            val option = content.options[optionIndex]
            val label = optionLabels[optionIndex]
            return face(content.copy(selected = option, optionLabel = { label }), optionRow = true,
                wrapValue = selectionPolicy == DropdownSelectionPolicy.EXPLICIT, choiceRow = choiceRows) { source, _ ->
                if (!disposed && rows[index] === source && (selectionPolicy == DropdownSelectionPolicy.PROGRESSIVE ||
                        source.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_YES)) collapse(index)
            }.apply {
                optionAccessibilityLabel = label
                isSelected = index == selectedPosition
                repeat(childCount) { getChildAt(it).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
            }
        }

        fun publish(index: Int, activated: Boolean = false) {
            if (selectionPolicy == DropdownSelectionPolicy.EXPLICIT && !activated) return
            val option = plan?.optionAt(index) ?: return
            if (selectedPosition != index) {
                rows[selectedPosition]?.isSelected = false
                selectedPosition = index
                rows[index]?.isSelected = true
            }
            if (option == lastPublished) return
            lastPublished = option
            try {
                select(content.options[option])
            } catch (failure: Throwable) {
                closePreservingPrimaryFailure(failure, ::dispose)
                throw failure
            }
        }

        fun settle(index: Int) {
            if (closing || disposed || plan == null) return
            wheel.stopFling()
            val target = index.coerceIn(0, lastPosition)
            settled = target
            animate(target.toFloat(), 1f, style.settleMillis) {
                publish(target)
                if (!disposed) rows[target]?.announceForAccessibility(optionLabels[checkNotNull(plan).optionAt(target)])
            }
        }

        fun cancelSelection() {
            val target = if (selectionPolicy == DropdownSelectionPolicy.EXPLICIT) plan?.selectedPosition ?: settled else settled
            collapse(target, activated = false)
        }

        fun collapse(index: Int, activated: Boolean = true, afterTapIndication: Boolean = false) {
            if (disposed) return
            wheel.cancelMotion()
            wheel.earlyTap = false
            if (closing) { if (reveal == 0f) dispose(); return }
            if (plan == null) { dispose(); return }
            closing = true
            val target = index.coerceIn(0, lastPosition)
            settled = target
            publish(target, activated)
            if (disposed) return
            if (afterTapIndication && ValueAnimator.areAnimatorsEnabled()) {
                animator?.removeAllListeners()
                animator?.cancel()
                collapseAfterDraw = true
                wheel.invalidate()
            } else startCollapse.run()
        }

        fun stopAnimation() {
            collapseAfterDraw = false
            wheel.removeCallbacks(startCollapse)
            val previous = animator
            animator = null
            previous?.removeAllListeners(); previous?.cancel()
        }

        fun animate(target: Float, opened: Float, duration: Long, move: Boolean = true, complete: () -> Unit) {
            stopAnimation()
            val startPosition = position; val startReveal = reveal
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                val travel = maxOf(abs(opened - startReveal), if (move) abs(target - startPosition).coerceAtMost(1f) else 0f)
                this.duration = (duration * travel).toLong()
                interpolator = PathInterpolator(.2f, 0f, 0f, 1f)
                addUpdateListener {
                    val t = it.animatedValue as Float
                    if (move && !wheel.dragging) position = startPosition + (target - startPosition) * t
                    reveal = startReveal + (opened - startReveal) * t
                    wheel.updatePositions()
                    scrim.alpha = reveal * style.scrimOpacity
                }
                addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(animation: Animator) { if (!disposed) complete() } })
                start()
            }
        }

        fun dispose() {
            if (disposed) return
            disposed = true
            if (sessionIdentity === this) { dismissCurrent = null; sessionIdentity = null }
            val motion = animator
            animator = null
            wheel.removeCallbacks(startCollapse)
            var failure = closePreservingPrimaryFailure(null, wheel::retireMotion)
            failure = closePreservingPrimaryFailure(failure) { motion?.removeAllListeners() }
            failure = closePreservingPrimaryFailure(failure) { motion?.cancel() }
            failure = closePreservingPrimaryFailure(failure) {
                if (anchor.viewTreeObserver.isAlive) anchor.viewTreeObserver.removeOnPreDrawListener(geometryGuard)
            }
            failure = closePreservingPrimaryFailure(failure) { anchor.removeOnAttachStateChangeListener(anchorDetach) }
            for (i in 0 until rows.size()) failure = closePreservingPrimaryFailure(failure, rows.valueAt(i)::close)
            rows.clear()
            failure = closePreservingPrimaryFailure(failure, overlay::close)
            failure = closePreservingPrimaryFailure(failure, onTerminal)
            failure?.let { throw it }
        }

        inner class Wheel : FrameLayout(context) {
            val path = Path()
            val rectangle = RectF()
            val cornerValues = FloatArray(8)
            private val hitShape by lazy { Region() }
            private val hitClip by lazy { Region() }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val cuePaint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val cueHeight = 20f * density.density
            private val cueFade = LinearGradient(0f, 0f, 0f, cueHeight,
                palette.collapsed, palette.collapsed and 0x00ffffff, Shader.TileMode.CLAMP)
            var downX = 0f
            var downY = 0f
            var dragging = false
            var downRow = 0
            private var downRowWasFullyVisible = false
            var explicitTap = false
            var earlyTap = false
            var pointer = -1
            var gesturePosition = 0f
            private val configuration = ViewConfiguration.get(context)
            val slop = configuration.scaledTouchSlop
            private val minimumFlingVelocity = configuration.scaledMinimumFlingVelocity
            private val maximumFlingVelocity = configuration.scaledMaximumFlingVelocity
            private var velocityTracker: VelocityTracker? = null
            private var scroller: OverScroller? = null
            private var flinging = false
            private var stopTap = false
            private var motionGeneration = 0
            private val flingFrame = Runnable { advanceFling() }
            private val revealTop get() = viewport?.revealTop(position, reveal) ?: anchorY.toFloat()
            private val revealBottom get() = viewport?.revealBottom(position, lastPosition, reveal) ?: (anchorY + rowHeight).toFloat()
            private var firstMounted = -1
            private var lastMounted = -1
            init { setWillNotDraw(false); clipChildren = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }

            fun stopFling() {
                flinging = false
                motionGeneration++
                removeCallbacks(flingFrame)
                // Keep the displayed position; abortAnimation would jump the scroller to its end.
                scroller?.forceFinished(true)
            }

            fun cancelMotion() {
                stopFling()
                pointer = -1
                dragging = false
                explicitTap = false
                stopTap = false
                velocityTracker?.clear()
            }

            fun retireMotion() {
                cancelMotion()
                velocityTracker?.recycle()
                velocityTracker = null
                scroller = null
            }

            private fun flingOrSettle(velocity: Float) {
                if (disposed || closing) return
                stopFling()
                if (!velocity.isFinite() || abs(velocity) < minimumFlingVelocity ||
                    velocity <= 0f && position <= 0f || velocity >= 0f && position >= lastPosition) {
                    settle(settled)
                    return
                }
                // Pixel units retain native density-aware friction and bounded velocity. No flywheel
                // accumulation or overshoot: the finite list and its existing end cues stay authoritative.
                // stopFling() forces finished before reuse, so the public constructor's flywheel cannot accumulate.
                val motion = scroller ?: OverScroller(context).also { scroller = it }
                val maximum = (lastPosition.toLong() * rowHeight).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                motion.fling(0, (position * rowHeight).roundToInt().coerceIn(0, maximum),
                    0, velocity.coerceIn(-maximumFlingVelocity.toFloat(), maximumFlingVelocity.toFloat()).roundToInt(),
                    0, 0, 0, maximum)
                flinging = true
                postOnAnimation(flingFrame)
            }

            private fun advanceFling() {
                if (!flinging || disposed || closing) return
                val motion = scroller ?: return
                val generation = motionGeneration
                if (motion.computeScrollOffset()) {
                    val previous = settled
                    position = (motion.currY.toFloat() / rowHeight).coerceIn(0f, lastPosition.toFloat())
                    settled = position.roundToInt()
                    updatePositions()
                    if (settled != previous) publish(settled)
                }
                // Selection callbacks may close, settle, or establish a new touch/fling owner.
                if (!flinging || disposed || closing || generation != motionGeneration) return
                if (motion.isFinished) {
                    stopFling()
                    settle(settled)
                } else postOnAnimation(flingFrame)
            }

            override fun onDetachedFromWindow() {
                retireMotion()
                stopAnimation()
                super.onDetachedFromWindow()
            }

            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (!hasWindowFocus) {
                    cancelMotion()
                    stopAnimation()
                    if (closing) dispose()
                } else if (!disposed && !closing && plan != null && pointer < 0 &&
                    (position != settled.toFloat() || reveal != 1f)) {
                    settle(settled)
                }
            }

            override fun onCancelPendingInputEvents() {
                endGesture()
                super.onCancelPendingInputEvents()
            }
            fun updatePositions() {
                val layout = plan ?: return
                updateMountedRows(layout.firstMounted(position), layout.lastMounted(position))
                val top = revealTop
                val bottom = revealBottom
                for (slot in 0 until rows.size()) {
                    val i = rows.keyAt(slot)
                    val row = rows.valueAt(slot)
                    val y = anchorY + (i - position) * rowHeight
                    if (width == rowWidth && y < bottom && y + rowHeight > top) prepareRow(row)
                    row.translationY = y
                    val visible = !closing && row.width == rowWidth && row.height == rowHeight &&
                        y >= top - .5f && y + rowHeight <= bottom + .5f
                    row.isFocusable = visible
                    row.importantForAccessibility = if (visible) View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                }
                invalidate()
            }
            private fun updateMountedRows(first: Int, last: Int) {
                if (first == firstMounted && last == lastMounted) return
                for (slot in rows.size() - 1 downTo 0) {
                    if (rows.keyAt(slot) in first..last) continue
                    val row = rows.valueAt(slot)
                    rows.removeAt(slot)
                    row.close()
                    removeViewInLayout(row)
                }
                for (i in first..last) {
                    if (rows[i] != null) continue
                    val row = createRow(i)
                    rows.put(i, row)
                    // The wheel owns exact row geometry. Avoid requesting another traversal
                    // from onLayout or from a drag frame, and retain logical traversal order.
                    addViewInLayout(row, rows.indexOfKey(i), LayoutParams(-1, rowHeight), true)
                }
                firstMounted = first
                lastMounted = last
            }
            override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(info)
                info.className = "android.widget.NumberPicker"
                info.isScrollable = !closing && !disposed && lastPosition > 0
                info.isDismissable = !closing && !disposed
                if (!closing && !disposed) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS)
                if (!closing && !disposed && settled < lastPosition) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
                if (!closing && !disposed && settled > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
            }
            override fun performAccessibilityAction(action: Int, args: android.os.Bundle?): Boolean = when (action) {
                AccessibilityNodeInfo.ACTION_DISMISS -> if (!closing && !disposed) { cancelSelection(); true } else false
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> if (!closing && !disposed && settled < lastPosition) { settle(settled + 1); true } else false
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> if (!closing && !disposed && settled > 0) { settle(settled - 1); true } else false
                else -> super.performAccessibilityAction(action, args)
            }
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                // Window geometry fixes the size independently of its rows. A side row first
                // needs a paragraph layout when reveal or scrolling brings it into the clip.
                setMeasuredDimension(resolveSize(rowWidth, widthMeasureSpec), resolveSize(0, heightMeasureSpec))
            }
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = updatePositions()

            private fun prepareRow(row: View) {
                if (!row.isLayoutRequested && row.width == rowWidth && row.height == rowHeight) return
                row.measure(MeasureSpec.makeMeasureSpec(rowWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY))
                row.layout(0, 0, rowWidth, rowHeight)
            }
            fun extents(): Pair<Float, Float> {
                return revealTop to revealBottom
            }
            private fun customOutlineStroke(): Float? = outlineWidthDp?.let {
                (it * density.density).coerceAtMost(minOf(width.toFloat(), revealBottom - revealTop).coerceAtLeast(0f) / 2f)
            }

            private fun updateRevealPath(stroke: Float? = customOutlineStroke()) {
                val inset = (stroke ?: density.density) / 2f
                rectangle.set(inset, revealTop + inset, width - inset, revealBottom - inset)
                for (corner in 0..3) {
                    val geometry = checkNotNull(viewport)
                    val rowsFromSlot = if (corner < 2) minOf(position, geometry.rowsAbove) else minOf(lastPosition - position, geometry.rowsBelow)
                    val fraction = rowsFromSlot.coerceIn(0f, 1f)
                    val blend = fraction * fraction * (3f - 2f * fraction) * reveal
                    val radius = radii[corner] + (style.cornerRadiusDp * density.density - radii[corner]) * blend
                    cornerValues[corner * 2] = radius; cornerValues[corner * 2 + 1] = radius
                }
                path.rewind(); path.addRoundRect(rectangle, cornerValues, Path.Direction.CW)
            }

            override fun draw(canvas: Canvas) {
                if (plan == null) return
                val customStroke = customOutlineStroke()
                updateRevealPath(customStroke)
                val saved = canvas.save(); canvas.clipPath(path)
                paint.style = Paint.Style.FILL; paint.color = palette.collapsed
                canvas.drawPath(path, paint)
                super.draw(canvas)
                drawOverflowCues(canvas)
                // The slot never moves. Its divider and outline share the existing group border role.
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = customStroke ?: density.density
                paint.color = palette.divider
                if (paint.strokeWidth > 0f) {
                    canvas.drawLine(0f, anchorY.toFloat(), width.toFloat(), anchorY.toFloat(), paint)
                    canvas.drawLine(0f, (anchorY + rowHeight).toFloat(), width.toFloat(), (anchorY + rowHeight).toFloat(), paint)
                }
                canvas.restoreToCount(saved)
                if (paint.strokeWidth > 0f) canvas.drawPath(path, paint)
                if (collapseAfterDraw) {
                    collapseAfterDraw = false
                    postOnAnimation(startCollapse)
                }
            }

            private fun drawOverflowCues(canvas: Canvas) {
                val geometry = viewport ?: return
                if (reveal <= 0f) return
                if (geometry.overflowsAbove(position)) drawCue(canvas, revealTop, upward = true)
                if (geometry.overflowsBelow(position, lastPosition)) drawCue(canvas, revealBottom, upward = false)
            }

            private fun drawCue(canvas: Canvas, edge: Float, upward: Boolean) {
                val saved = canvas.save()
                canvas.translate(0f, edge)
                if (!upward) canvas.scale(1f, -1f)
                cuePaint.style = Paint.Style.FILL
                cuePaint.shader = cueFade
                cuePaint.alpha = (255 * reveal).roundToInt()
                canvas.drawRect(0f, 0f, width.toFloat(), cueHeight, cuePaint)
                cuePaint.shader = null
                cuePaint.color = palette.ink
                cuePaint.alpha = (220 * reveal).roundToInt()
                cuePaint.strokeWidth = 1.5f * density.density
                cuePaint.strokeCap = Paint.Cap.ROUND
                val center = width / 2f
                val half = 3f * density.density
                val y = 5f * density.density
                canvas.drawLine(center - half, y + half, center, y, cuePaint)
                canvas.drawLine(center, y, center + half, y + half, cuePaint)
                canvas.restoreToCount(saved)
            }
            override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
                // The wheel's clip already hides these rows. Skip recording their contents until
                // they reach the reveal, including the first rasterization of their arrow masks.
                val shift = child.translationY
                if (child.bottom + shift <= rectangle.top || child.top + shift >= rectangle.bottom) return false
                return super.drawChild(canvas, child, drawingTime)
            }
            override fun onInterceptTouchEvent(event: MotionEvent): Boolean = true
            private fun rowAt(y: Float): Int =
                (position + (y - anchorY) / rowHeight - .5f).roundToInt().coerceIn(0, lastPosition)

            private fun trackExplicitTap(event: MotionEvent, index: Int) {
                if (!explicitTap) return
                fun withinSlop(x: Float, y: Float) = abs(x - downX) <= slop && abs(y - downY) <= slop
                explicitTap = withinSlop(event.getX(index), event.getY(index)) &&
                    (0 until event.historySize).all { sample ->
                        withinSlop(event.getHistoricalX(index, sample), event.getHistoricalY(index, sample))
                    }
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (disposed || closing && selectionPolicy == DropdownSelectionPolicy.EXPLICIT) return true
                if (plan == null || earlyTap) {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> earlyTap = true
                        MotionEvent.ACTION_UP -> dispose()
                        MotionEvent.ACTION_CANCEL -> earlyTap = false
                    }
                    return true
                }
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val wasFlinging = flinging
                        cancelMotion()
                        stopTap = wasFlinging
                        // A closing gesture can reopen on drag/tap. Until then its terminal
                        // animation must keep running, including after cancellation/pointer loss.
                        if (!closing) stopAnimation()
                        if (event.y < revealTop || event.y > revealBottom) { cancelSelection(); return true }
                        downRow = rowAt(event.y)
                        downRowWasFullyVisible = rows[downRow]?.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_YES
                        if (!closing && reveal < 1f) animate(position, 1f, style.expandMillis, move = false) {}
                        gesturePosition = position
                        pointer = event.getPointerId(0); downX = event.x; downY = event.y; dragging = false
                        explicitTap = selectionPolicy == DropdownSelectionPolicy.EXPLICIT && event.x >= 0f && event.x < width
                        val tracker = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
                        tracker.addMovement(event)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (pointer < 0) return true
                        val i = event.findPointerIndex(pointer)
                        if (i < 0) { endGesture(); return true }
                        velocityTracker?.addMovement(event)
                        trackExplicitTap(event, i)
                        val delta = downY - event.getY(i)
                        if (!dragging && abs(delta) > slop) {
                            gesturePosition = position
                            dragging = true
                            closing = false
                            animate(position, 1f, style.expandMillis, move = false) {}
                        }
                        if (dragging) {
                            position = (gesturePosition + delta / rowHeight).coerceIn(0f, lastPosition.toFloat())
                            settled = position.roundToInt()
                            updatePositions()
                            publish(settled)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (pointer < 0) return true
                        val i = event.findPointerIndex(pointer)
                        if (i < 0) { endGesture(); return true }
                        trackExplicitTap(event, i)
                        val delta = downY - event.getY(i)
                        val moved = dragging || abs(delta) > slop
                        val tracker = velocityTracker
                        tracker?.addMovement(event)
                        if (moved) tracker?.computeCurrentVelocity(1000, maximumFlingVelocity.toFloat())
                        val velocity = if (moved) -(tracker?.getYVelocity(pointer) ?: 0f) else 0f
                        val stoppedFling = stopTap
                        stopTap = false
                        pointer = -1
                        tracker?.clear()
                        if (moved) {
                            if (!dragging) {
                                gesturePosition = position
                                closing = false
                                // UP may be the first event beyond slop. Retire a preserved collapse
                                // exactly as MOVE promotion does before giving position to a fling.
                                animate(position, 1f, style.expandMillis, move = false) {}
                            }
                            closing = false
                            position = (gesturePosition + delta / rowHeight).coerceIn(0f, lastPosition.toFloat())
                            settled = position.roundToInt()
                            dragging = false
                            val generation = motionGeneration
                            updatePositions()
                            publish(settled)
                            if (!disposed && !closing && pointer < 0 && generation == motionGeneration) flingOrSettle(velocity)
                        }
                        if (!moved) {
                            if (stoppedFling) {
                                settle(settled)
                                return true
                            }
                            val x = event.getX(i)
                            val y = event.getY(i)
                            if (!insideReveal(x, y)) {
                                if (closing && reveal == 0f) dispose()
                                return true
                            }
                            if (closing) {
                                closing = false
                                animate(settled.toFloat(), 1f, style.expandMillis) {}
                            } else if (selectionPolicy == DropdownSelectionPolicy.PROGRESSIVE ||
                                explicitTap && rowAt(y) == downRow) {
                                val row = rows[downRow] ?: return true
                                val rowY = y - row.translationY
                                if (rowY >= 0f && rowY < rowHeight) {
                                    row.showTapIndication(x, rowY)
                                    // A partial option is a scroll affordance. Bring it into the
                                    // slot before accepting a separate explicit activation.
                                    if (selectionPolicy == DropdownSelectionPolicy.EXPLICIT &&
                                        (!downRowWasFullyVisible || row.importantForAccessibility != View.IMPORTANT_FOR_ACCESSIBILITY_YES)) {
                                        settle(downRow)
                                    } else {
                                        collapse(downRow, afterTapIndication = true)
                                    }
                                }
                            }
                        }
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        explicitTap = false
                        if (pointer >= 0) velocityTracker?.addMovement(event)
                    }
                    MotionEvent.ACTION_CANCEL -> endGesture()
                    MotionEvent.ACTION_POINTER_UP -> {
                        if (event.getPointerId(event.actionIndex) == pointer) endGesture()
                        else if (pointer >= 0) velocityTracker?.addMovement(event)
                    }
                }
                return true
            }

            private fun insideReveal(x: Float, y: Float): Boolean {
                if (x < 0f || x >= width || y < revealTop || y >= revealBottom) return false
                updateRevealPath()
                hitClip.set(0, 0, width, height)
                hitShape.setPath(path, hitClip)
                return hitShape.contains(x.toInt(), y.toInt())
            }

            private fun endGesture() {
                cancelMotion()
                if (closing && reveal == 0f) dispose() else settle(settled)
            }
        }
    }
}
