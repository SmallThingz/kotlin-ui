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
import android.view.FocusFinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
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
 * A drag crosses any number of rows and snaps to a slot; a tap selects while collapsing.
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
    private var sessionIdentity: Any? = null
    private val titleStyle = appearance.typography[NativeTypographyRole.BODY_SMALL]
    private val selectedStyle = appearance.typography[NativeTypographyRole.BODY_LARGE]

    fun <T> createField(content: NativeDropdownContent<T>, onSelected: (T) -> Unit): NativeDropdown<T> =
        face(content) { anchor, value -> open(anchor, value, onSelected) }

    private fun <T> face(content: NativeDropdownContent<T>, optionRow: Boolean = false, wrapValue: Boolean = false,
        activate: (View, NativeDropdownContent<T>) -> Unit): NativeDropdown<T> {
        return NativeDropdown(context,
            ParagraphView(context, titleStyle, density, resolver, palette.muted),
            ParagraphView(context, selectedStyle, density, resolver, palette.ink),
            createDropdownIcon(context, palette.muted), palette, { null }, { _, _ -> }, activate,
        ).apply {
            if (wrapValue) wrapValues()
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
        onTerminal: () -> Unit, onSelected: (T) -> Unit): Boolean {
        close()
        // A terminal callback can synchronously establish a newer session.
        if (sessionIdentity != null || !anchor.isAttachedToWindow || !anchor.isShown || anchor.width <= 0 || anchor.height <= 0) return false
        val options = content.options.toList()
        val selected = options.indexOf(content.selected)
        if (!content.canOpen(selectionPolicy)) return false
        require(options.distinct().size == options.size) { "Choice identities must be unique" }
        val session = Session(anchor, content.copy(options = options), selected, selectionPolicy, onSelected, onTerminal)
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
        val selectionPolicy: DropdownSelectionPolicy, val select: (T) -> Unit,
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
        val rows = ArrayList<NativeDropdown<T>>()
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
            wheel.layoutParams = FrameLayout.LayoutParams(rowWidth, root.height).apply {
                gravity = Gravity.TOP or Gravity.LEFT; leftMargin = x
            }
            repeat(layout.count) { i ->
                val option = content.options[layout.optionAt(i)]
                val row = face(content.copy(selected = option), optionRow = true,
                    wrapValue = selectionPolicy == DropdownSelectionPolicy.EXPLICIT) { source, _ ->
                    if (selectionPolicy == DropdownSelectionPolicy.PROGRESSIVE ||
                        source.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_YES) collapse(i)
                }
                row.optionAccessibilityLabel = content.optionLabel(option)
                row.isSelected = initial >= 0 && i == settled
                repeat(row.childCount) { child -> row.getChildAt(child).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
                rows += row
                wheel.addView(row, FrameLayout.LayoutParams(-1, rowHeight))
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

        fun publish(index: Int, activated: Boolean = false) {
            if (selectionPolicy == DropdownSelectionPolicy.EXPLICIT && !activated) return
            val option = plan?.optionAt(index) ?: return
            rows.forEachIndexed { i, row -> row.isSelected = i == index }
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
            if (closing || disposed || plan == null || rows.isEmpty()) return
            val target = index.coerceIn(0, rows.lastIndex)
            settled = target
            animate(target.toFloat(), 1f, style.settleMillis) {
                publish(target)
                if (!disposed) rows[target].announceForAccessibility(content.optionLabel(content.options[checkNotNull(plan).optionAt(target)]))
            }
        }

        fun cancelSelection() {
            val target = if (selectionPolicy == DropdownSelectionPolicy.EXPLICIT) plan?.selectedPosition ?: settled else settled
            collapse(target, activated = false)
        }

        fun collapse(index: Int, activated: Boolean = true, afterTapIndication: Boolean = false) {
            if (disposed) return
            wheel.pointer = -1; wheel.dragging = false; wheel.earlyTap = false
            if (closing) { if (reveal == 0f) dispose(); return }
            if (plan == null) { dispose(); return }
            closing = true
            val target = index.coerceIn(0, rows.lastIndex)
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

        fun animate(target: Float, opened: Float, duration: Long, move: Boolean = true, complete: () -> Unit) {
            collapseAfterDraw = false
            wheel.removeCallbacks(startCollapse)
            animator?.removeAllListeners(); animator?.cancel()
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
            var failure = closePreservingPrimaryFailure(null) { motion?.removeAllListeners() }
            failure = closePreservingPrimaryFailure(failure) { motion?.cancel() }
            failure = closePreservingPrimaryFailure(failure) {
                if (anchor.viewTreeObserver.isAlive) anchor.viewTreeObserver.removeOnPreDrawListener(geometryGuard)
            }
            failure = closePreservingPrimaryFailure(failure) { anchor.removeOnAttachStateChangeListener(anchorDetach) }
            for (row in rows) failure = closePreservingPrimaryFailure(failure, row::close)
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
            var explicitTap = false
            var earlyTap = false
            var pointer = -1
            var gesturePosition = 0f
            val slop = ViewConfiguration.get(context).scaledTouchSlop
            private val revealTop get() = viewport?.revealTop(position, reveal) ?: anchorY.toFloat()
            private val revealBottom get() = viewport?.revealBottom(position, rows.lastIndex, reveal) ?: (anchorY + rowHeight).toFloat()
            init { setWillNotDraw(false); clipChildren = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
            fun updatePositions() {
                if (plan == null) return
                val top = revealTop
                val bottom = revealBottom
                rows.forEachIndexed { i, row ->
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
            override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(info)
                info.className = "android.widget.NumberPicker"
                info.isScrollable = !closing && !disposed && rows.size > 1
                if (!closing && !disposed && settled < rows.lastIndex) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
                if (!closing && !disposed && settled > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
            }
            override fun performAccessibilityAction(action: Int, args: android.os.Bundle?): Boolean = when (action) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> if (!closing && !disposed && settled < rows.lastIndex) { settle(settled + 1); true } else false
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
            private fun updateRevealPath() {
                rectangle.set(.5f, revealTop + .5f, width - .5f, revealBottom - .5f)
                for (corner in 0..3) {
                    val geometry = checkNotNull(viewport)
                    val rowsFromSlot = if (corner < 2) minOf(position, geometry.rowsAbove) else minOf(rows.lastIndex - position, geometry.rowsBelow)
                    val fraction = rowsFromSlot.coerceIn(0f, 1f)
                    val blend = fraction * fraction * (3f - 2f * fraction) * reveal
                    val radius = radii[corner] + (style.cornerRadiusDp * density.density - radii[corner]) * blend
                    cornerValues[corner * 2] = radius; cornerValues[corner * 2 + 1] = radius
                }
                path.rewind(); path.addRoundRect(rectangle, cornerValues, Path.Direction.CW)
            }

            override fun draw(canvas: Canvas) {
                if (plan == null) return
                updateRevealPath()
                val saved = canvas.save(); canvas.clipPath(path)
                paint.style = Paint.Style.FILL; paint.color = palette.collapsed
                canvas.drawPath(path, paint)
                super.draw(canvas)
                drawOverflowCues(canvas)
                // The slot never moves. Its divider and outline share the existing group border role.
                paint.style = Paint.Style.STROKE; paint.strokeWidth = density.density; paint.color = palette.divider
                canvas.drawLine(0f, anchorY.toFloat(), width.toFloat(), anchorY.toFloat(), paint)
                canvas.drawLine(0f, (anchorY + rowHeight).toFloat(), width.toFloat(), (anchorY + rowHeight).toFloat(), paint)
                canvas.restoreToCount(saved)
                canvas.drawPath(path, paint)
                if (collapseAfterDraw) {
                    collapseAfterDraw = false
                    postOnAnimation(startCollapse)
                }
            }

            private fun drawOverflowCues(canvas: Canvas) {
                val geometry = viewport ?: return
                if (reveal <= 0f) return
                if (geometry.overflowsAbove(position)) drawCue(canvas, revealTop, upward = true)
                if (geometry.overflowsBelow(position, rows.lastIndex)) drawCue(canvas, revealBottom, upward = false)
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
                (position + (y - anchorY) / rowHeight - .5f).roundToInt().coerceIn(0, rows.lastIndex)

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
                        val edges = extents()
                        if (event.y < edges.first || event.y > edges.second) { cancelSelection(); return true }
                        gesturePosition = position
                        downRow = rowAt(event.y)
                        pointer = event.getPointerId(0); downX = event.x; downY = event.y; dragging = false
                        explicitTap = selectionPolicy == DropdownSelectionPolicy.EXPLICIT && event.x >= 0f && event.x < width
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val i = event.findPointerIndex(pointer)
                        if (i < 0) { endGesture(); return true }
                        trackExplicitTap(event, i)
                        val delta = downY - event.getY(i)
                        if (!dragging && abs(delta) > slop) {
                            gesturePosition = position
                            dragging = true
                            closing = false
                            animate(position, 1f, style.expandMillis, move = false) {}
                        }
                        if (dragging) {
                            position = (gesturePosition + delta / rowHeight).coerceIn(0f, rows.lastIndex.toFloat())
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
                        if (moved) {
                            if (!dragging) gesturePosition = position
                            closing = false
                            position = (gesturePosition + delta / rowHeight).coerceIn(0f, rows.lastIndex.toFloat())
                            settled = position.roundToInt()
                            updatePositions()
                            publish(settled)
                            dragging = false
                            if (!disposed) settle(settled)
                        }
                        pointer = -1
                        if (!moved) {
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
                                val row = rows[downRow]
                                val rowY = y - row.translationY
                                if (rowY >= 0f && rowY < rowHeight) {
                                    row.showTapIndication(x, rowY)
                                    collapse(downRow, afterTapIndication = true)
                                }
                            }
                        }
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> explicitTap = false
                    MotionEvent.ACTION_CANCEL -> endGesture()
                    MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == pointer) endGesture()
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
                pointer = -1
                dragging = false
                if (closing && reveal == 0f) dispose() else settle(settled)
            }
        }
    }
}
