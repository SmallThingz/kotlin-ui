package smallthingz.ui.components

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.PopupWindow
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.unit.Density
import kotlin.math.abs
import kotlin.math.roundToInt

/** Draft anchored choice wheel. Colors, text, joined geometry and input all belong to the library. */
data class NativeAnchoredDropdownStyle(
    val cornerRadiusDp: Float = 16f,
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
 * A drag selects at most one adjacent slot; a tap can select any visible row while collapsing.
 * The caller owns values and must [close] this host with its screen lifetime.
 */
class NativeAnchoredDropdown(
    private val context: Context,
    private val appearance: NativeUiAppearance,
    private val colors: NativeColorScheme,
    private val density: Density,
    private val resolver: FontFamily.Resolver,
    private val style: NativeAnchoredDropdownStyle = appearance.anchoredDropdown,
) : AutoCloseable {
    private var dismissCurrent: (() -> Unit)? = null
    private var sessionIdentity: Any? = null
    private val palette get() = NativeDropdownColors(colors.field, colors.field, colors.muted, colors.ink, colors.border)

    fun <T> createField(content: NativeDropdownContent<T>, onSelected: (T) -> Unit): NativeDropdown<T> =
        face(content) { anchor, value -> open(anchor, value, onSelected) }

    private fun <T> face(content: NativeDropdownContent<T>, activate: (View, NativeDropdownContent<T>) -> Unit): NativeDropdown<T> {
        val path = Path().apply { moveTo(7f, 10f); lineTo(12f, 15f); lineTo(17f, 10f); close() }
        val glyph = CachedPathIconDrawable({ NativePathIconMask(path) }, density.density, colors.muted)
        val icon = NativeDecorativeIconView(context, glyph, colors.muted)
        return NativeDropdown(context,
            ParagraphView(context, appearance.typography[NativeTypographyRole.BODY_SMALL], density, resolver, colors.muted),
            ParagraphView(context, appearance.typography[NativeTypographyRole.BODY_LARGE], density, resolver, colors.ink),
            NativeDropdownIcon(icon, icon::setColor), palette, { null }, { _, _ -> }, activate,
        ).apply {
            bindDescription(content.label)
            bind(content, false, palette)
        }
    }

    fun <T> open(anchor: View, content: NativeDropdownContent<T>, onSelected: (T) -> Unit) {
        close()
        if (!anchor.isAttachedToWindow || !anchor.isShown || anchor.width <= 0 || anchor.height <= 0) return
        val options = content.options.toList()
        val selected = options.indexOf(content.selected)
        if (options.size < 2 || selected < 0) return
        require(options.distinct().size == options.size) { "Choice identities must be unique" }
        val session = Session(anchor, content.copy(options = options), selected, onSelected)
        sessionIdentity = session
        dismissCurrent = session::dispose
        try { session.show() } catch (failure: RuntimeException) { session.dispose(); throw failure }
    }

    override fun close() {
        val dismiss = dismissCurrent
        dismissCurrent = null; sessionIdentity = null
        dismiss?.invoke()
    }

    private inner class Session<T>(
        val anchor: View, val content: NativeDropdownContent<T>, val initial: Int, val select: (T) -> Unit,
    ) {
        val origin = IntArray(2).also(anchor::getLocationOnScreen)
        val observed = IntArray(2)
        val root = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                    if (event.action == KeyEvent.ACTION_UP) collapse(settled)
                    return true
                }
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0)
                        settle(settled + if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1)
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        val popup = PopupWindow(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, true)
        val scrim = View(context).apply { setBackgroundColor(colors.scrim); alpha = 0f; setOnClickListener { collapse(settled) } }
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
        var slotsAbove = 0
        var slotsBelow = 0
        val rowHeight = anchor.height
        val rowWidth = anchor.width
        val geometryGuard = ViewTreeObserver.OnPreDrawListener {
            anchor.getLocationOnScreen(observed)
            if (!anchor.isShown || !anchor.isAttachedToWindow || observed[0] != origin[0] || observed[1] != origin[1]
                || anchor.width != rowWidth || anchor.height != rowHeight) dispose()
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
            popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            popup.elevation = 0f
            popup.animationStyle = 0
            popup.inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            popup.setOnDismissListener { dispose() }
            root.isFocusableInTouchMode = true
            root.addView(scrim, FrameLayout.LayoutParams(-1, -1))
            root.addView(wheel)
            scrim.isFocusable = false
            scrim.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            root.accessibilityPaneTitle = content.label
            popup.showAtLocation(anchor, Gravity.TOP or Gravity.LEFT, 0, 0)
            root.requestFocus()
            root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    root.viewTreeObserver.removeOnPreDrawListener(this)
                    if (disposed || closing) return true
                    root.getLocationOnScreen(observed)
                    val x = origin[0] - observed[0]
                    anchorY = origin[1] - observed[1]
                    val margin = (style.screenMarginDp * density.density).roundToInt()
                    val up = ((anchorY - margin) / rowHeight).coerceAtLeast(0)
                    val down = ((root.height - margin - anchorY - rowHeight) / rowHeight).coerceAtLeast(0)
                    slotsAbove = up; slotsBelow = down
                    val layout = NativeAnchoredDropdownPlan(content.options.size, initial, up, down)
                    plan = layout; settled = layout.selectedPosition; position = settled.toFloat()
                    wheel.layoutParams = FrameLayout.LayoutParams(rowWidth, root.height).apply {
                        gravity = Gravity.TOP or Gravity.LEFT; leftMargin = x
                    }
                    repeat(layout.count) { i ->
                        val option = content.options[layout.optionAt(i)]
                        val row = face(content.copy(selected = option)) { _, _ -> collapse(i) }
                        row.accessibilityDelegate = object : View.AccessibilityDelegate() {
                            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                                super.onInitializeAccessibilityNodeInfo(host, info)
                                info.className = "android.widget.Button"
                                info.contentDescription = content.optionLabel(option)
                                info.isSelected = i == settled
                            }
                        }
                        repeat(row.childCount) { child -> row.getChildAt(child).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
                        rows += row
                        wheel.addView(row, FrameLayout.LayoutParams(-1, rowHeight))
                    }
                    wheel.requestLayout()
                    anchor.viewTreeObserver.addOnPreDrawListener(geometryGuard)
                    anchor.addOnAttachStateChangeListener(anchorDetach)
                    animate(position, 1f, style.expandMillis) {}
                    return false
                }
            })
        }

        fun publish(index: Int) {
            val option = plan?.optionAt(index) ?: return
            rows.forEachIndexed { i, row -> row.isSelected = i == index }
            if (option != lastPublished) { lastPublished = option; select(content.options[option]) }
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

        fun collapse(index: Int) {
            if (closing || disposed) return
            if (plan == null) { dispose(); return }
            closing = true
            val target = index.coerceIn(0, rows.lastIndex)
            animate(target.toFloat(), 0f, style.collapseMillis) {
                try { settled = target; publish(target) } finally { dispose() }
            }
        }

        fun animate(target: Float, opened: Float, duration: Long, complete: () -> Unit) {
            animator?.removeAllListeners(); animator?.cancel()
            val startPosition = position; val startReveal = reveal
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                this.duration = duration
                interpolator = PathInterpolator(.2f, 0f, 0f, 1f)
                addUpdateListener {
                    val t = it.animatedValue as Float
                    position = startPosition + (target - startPosition) * t
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
            animator?.removeAllListeners(); animator?.cancel(); animator = null
            if (anchor.viewTreeObserver.isAlive) anchor.viewTreeObserver.removeOnPreDrawListener(geometryGuard)
            anchor.removeOnAttachStateChangeListener(anchorDetach)
            rows.forEach { it.close() }; rows.clear()
            popup.setOnDismissListener(null); popup.dismiss()
            if (sessionIdentity === this) { dismissCurrent = null; sessionIdentity = null }
        }

        inner class Wheel : FrameLayout(context) {
            val path = Path()
            val rectangle = RectF()
            val cornerValues = FloatArray(8)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            var downY = 0f
            var dragging = false
            var pointer = -1
            var gesturePosition = 0f
            val slop = ViewConfiguration.get(context).scaledTouchSlop
            init { setWillNotDraw(false); clipChildren = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
            fun updatePositions() {
                val p = plan ?: return
                val top = anchorY - minOf(position, slotsAbove.toFloat()) * rowHeight * reveal
                val bottom = anchorY + rowHeight + minOf(rows.lastIndex - position, slotsBelow.toFloat()) * rowHeight * reveal
                rows.forEachIndexed { i, row ->
                    val y = anchorY + (i - position) * rowHeight
                    row.translationY = y
                    val visible = !closing && y >= top - .5f && y + rowHeight <= bottom + .5f
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
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                rows.forEach { it.layout(0, 0, width, rowHeight) }
                updatePositions()
            }
            fun extents(): Pair<Float, Float> {
                val p = plan ?: return anchorY.toFloat() to (anchorY + rowHeight).toFloat()
                return anchorY - minOf(position, slotsAbove.toFloat()) * rowHeight * reveal to
                    anchorY + rowHeight + minOf(rows.lastIndex - position, slotsBelow.toFloat()) * rowHeight * reveal
            }
            override fun draw(canvas: Canvas) {
                if (plan == null) return
                val p = checkNotNull(plan)
                val top = anchorY - minOf(position, slotsAbove.toFloat()) * rowHeight * reveal
                val bottom = anchorY + rowHeight + minOf(rows.lastIndex - position, slotsBelow.toFloat()) * rowHeight * reveal
                rectangle.set(.5f, top + .5f, width - .5f, bottom - .5f)
                for (corner in 0..3) {
                    val expands = if (corner < 2) top < anchorY else bottom > anchorY + rowHeight
                    val radius = radii[corner] + (if (expands) style.cornerRadiusDp * density.density - radii[corner] else 0f) * reveal
                    cornerValues[corner * 2] = radius; cornerValues[corner * 2 + 1] = radius
                }
                path.rewind(); path.addRoundRect(rectangle, cornerValues, Path.Direction.CW)
                val saved = canvas.save(); canvas.clipPath(path)
                paint.style = Paint.Style.FILL; paint.color = colors.field
                canvas.drawPath(path, paint)
                super.draw(canvas)
                // The slot never moves. Its divider and outline share the existing group border role.
                paint.style = Paint.Style.STROKE; paint.strokeWidth = density.density; paint.color = colors.border
                canvas.drawLine(0f, anchorY.toFloat(), width.toFloat(), anchorY.toFloat(), paint)
                canvas.drawLine(0f, (anchorY + rowHeight).toFloat(), width.toFloat(), (anchorY + rowHeight).toFloat(), paint)
                canvas.restoreToCount(saved)
                canvas.drawPath(path, paint)
            }
            override fun onInterceptTouchEvent(event: MotionEvent): Boolean = true
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (closing || plan == null) return true
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val edges = extents()
                        if (event.y < edges.first || event.y > edges.second) { collapse(settled); return true }
                        animator?.removeAllListeners(); animator?.cancel()
                        gesturePosition = position; reveal = 1f
                        pointer = event.getPointerId(0); downY = event.y; dragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val i = event.findPointerIndex(pointer)
                        if (i < 0) { settle(settled); return true }
                        val delta = downY - event.getY(i)
                        if (abs(delta) > slop) dragging = true
                        if (dragging) {
                            position = (gesturePosition + delta / rowHeight).coerceIn((settled - 1).coerceAtLeast(0).toFloat(), (settled + 1).coerceAtMost(rows.lastIndex).toFloat())
                            updatePositions()
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (pointer < 0) return true
                        if (dragging) settle(position.roundToInt())
                        else collapse((position + (event.y - anchorY) / rowHeight - .5f).roundToInt().coerceIn(0, rows.lastIndex))
                        pointer = -1
                    }
                    MotionEvent.ACTION_CANCEL -> { pointer = -1; settle(settled) }
                    MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == pointer) { pointer = -1; settle(settled) }
                }
                return true
            }
        }
    }
}
