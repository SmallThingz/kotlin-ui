package smallthingz.ui.components

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import smallthingz.ui.controls.ControlPalette
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/** Stable action identity is independent of the row's position and Android View id. */
data class NativeMenuItem(
    val id: Int,
    val label: CharSequence,
    val enabled: Boolean = true,
    val icon: Drawable? = null,
    val contentDescription: CharSequence? = null,
    /** Null is an ordinary action; non-null is an owner-controlled exclusive choice. */
    val selected: Boolean? = null,
    val selectionIcon: Drawable? = null,
    val tag: String? = null,
)

/**
 * Default geometry follows Material 3 menu tokens (112–280 dp width, 48 dp rows,
 * 12 dp horizontal/8 dp outer vertical padding, 4 dp corners and 3 dp elevation).
 * Applications supply their own established typography and surface colors explicitly.
 */
data class NativePopupMenuStyle(
    val minWidthDp: Float = 112f,
    val maxWidthDp: Float = 280f,
    val minRowHeightDp: Float = 48f,
    val cornerRadiusDp: Float = 4f,
    val verticalPaddingDp: Float = 8f,
    val rowHorizontalPaddingDp: Float = 12f,
    val rowVerticalPaddingDp: Float = 0f,
    val iconSizeDp: Float = 24f,
    val iconGapDp: Float = 12f,
    val verticalMarginDp: Float = 48f,
    val horizontalMarginDp: Float = 0f,
    val elevationDp: Float = 3f,
    val horizontalOffsetDp: Float = 0f,
    val verticalOffsetDp: Float = 0f,
    val textStyle: NativeTextStyle = NativeTextStyle(14f, 20f, 500, letterSpacingSp = .1f),
    val surfaceColor: Int? = null,
    val outlineColor: Int? = null,
    val noise: Boolean = false,
    val disabledContentAlpha: Float = .38f,
    val iconColor: Int? = null,
    val disabledIconAlpha: Float = .38f,
    val horizontalPaddingDp: Float = 0f,
) {
    init {
        require(listOf(minWidthDp, maxWidthDp, minRowHeightDp, cornerRadiusDp,
            verticalPaddingDp, horizontalPaddingDp, rowHorizontalPaddingDp, rowVerticalPaddingDp,
            iconSizeDp, iconGapDp, verticalMarginDp, horizontalMarginDp, elevationDp)
            .all { it.isFinite() && it >= 0f })
        require(maxWidthDp > 0f && maxWidthDp >= minWidthDp)
        require(horizontalOffsetDp.isFinite() && verticalOffsetDp.isFinite())
        require(disabledContentAlpha.isFinite() && disabledContentAlpha in 0f..1f)
        require(disabledIconAlpha.isFinite() && disabledIconAlpha in 0f..1f)
    }
}

/**
 * A focusable anchored menu with retained native action rows. No app routing or command state is
 * captured here: click handlers receive an id and must admit against the app's current state.
 */
class NativePopupMenu(
    private val context: Context,
    palette: ControlPalette,
    private val anchor: View,
    style: NativePopupMenuStyle = NativePopupMenuStyle(),
) {
    private var palette = palette
    private var style = style
    private val surface = NativeSurface(context, surfaceStyle())
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context).apply {
        isFillViewport = false
        isFocusable = false
        clipToPadding = false
        addView(rows, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private val window = PopupWindow(surface, 0, 0, true)
    private val windowBackground = GradientDrawable().apply { setColor(style.surfaceColor ?: palette.raised) }
    private val windowColors = smallthingz.ui.animation.view.ViewColorTransition(surface,
        intArrayOf(style.surfaceColor ?: palette.raised), ::publishWindowColor)
    private fun publishWindowColor() { windowBackground.setColor(windowColors.color(0)) }
    private val entries = LinkedHashMap<Int, Entry>()
    private var orderedIds = emptyList<Int>()
    private var presentationActive = false
    private var generation = 0L
    private var geometry: Geometry? = null
    private var horizontalWindowOffset = 0
    private var verticalWindowOffset = 0
    private var observedTree: ViewTreeObserver? = null
    private var focusReturn: FocusReturn? = null
    private val location = IntArray(2)
    private val visibleFrame = Rect()
    private val visibleAnchor = Rect()
    private val globalLayout = ViewTreeObserver.OnGlobalLayoutListener { refreshGeometry() }
    private val anchorScroll = ViewTreeObserver.OnScrollChangedListener { refreshGeometry() }
    private val anchorAttachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) { dismiss() }
    }

    /** Return true to dismiss after the action; false leaves the menu open. Explicit dismissal is allowed. */
    var onItemClick: ((Int) -> Boolean)? = null
    var onDismiss: (() -> Unit)? = null
    /** Rechecks route/editor ownership immediately before a posted focus restoration. */
    var canRestoreFocus: (() -> Boolean)? = null
    val isShowing: Boolean get() = window.isShowing
    var accessibilityPaneTitle: CharSequence?
        get() = surface.accessibilityPaneTitle
        set(value) { requireMainThread(); surface.accessibilityPaneTitle = value }

    init {
        surface.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        surface.isFocusable = true
        surface.isFocusableInTouchMode = true
        surface.setOnKeyListener { _, key, event -> handleKey(key, event) }
        window.isOutsideTouchable = true
        window.isClippingEnabled = true
        window.overlapAnchor = false
        window.inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        window.setOnDismissListener { finishDismissal() }
        applyGeometryAndPaint()
    }

    /** Binding never invokes actions. Existing ids keep their row and keyboard focus. */
    fun bind(items: List<NativeMenuItem>) {
        requireMainThread()
        require(items.map { it.id }.toSet().size == items.size) { "Menu item ids must be unique" }
        val focusedId = entries.entries.firstOrNull { it.value.row.hasFocus() }?.key
        val nextIds = items.map { it.id }
        entries.keys.filter { it !in nextIds }.forEach { id ->
            rows.removeView(entries.remove(id)!!.row)
        }
        for (item in items) {
            val entry = entries[item.id] ?: Entry(createRow(item.id)).also {
                entries[item.id] = it
                rows.addView(it.row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            entry.item = item
            bindRow(entry)
        }
        // Reorder without detaching retained nodes from their window.
        if (orderedIds != nextIds) items.forEach { rows.bringChildToFront(entries.getValue(it.id).row) }
        orderedIds = nextIds
        geometry = null
        if (items.isEmpty() && isShowing) dismiss()
        else if (isShowing) {
            refreshGeometry()
            if (focusedId != null && entries[focusedId]?.item?.enabled != true) focusBoundary(first = true)
        }
    }

    /** Missing optional actions are a no-op, matching nullable menu-item lookup. */
    fun setItemEnabled(id: Int, enabled: Boolean) {
        requireMainThread()
        val entry = entries[id] ?: return
        val item = entry.item ?: return
        if (item.enabled == enabled) return
        val wasFocused = entry.row.hasFocus()
        entry.item = item.copy(enabled = enabled)
        entry.row.isEnabled = enabled
        entry.row.isFocusable = enabled
        if (!enabled && wasFocused && isShowing) focusBoundary(first = true)
    }

    fun itemView(id: Int): NativeButton? = entries[id]?.row

    fun applyPalette(palette: ControlPalette) {
        requireMainThread()
        if (this.palette == palette) return
        this.palette = palette
        applyGeometryAndPaint()
    }

    fun applyStyle(style: NativePopupMenuStyle) {
        requireMainThread()
        if (this.style == style) return
        this.style = style
        applyGeometryAndPaint()
        geometry = null
        refreshGeometry()
    }

    /** Returns false when there is no visible, attached anchor or no menu content. */
    fun show(): Boolean {
        requireMainThread()
        if (isShowing) return true
        if (orderedIds.isEmpty() || !anchor.isAttachedToWindow || !anchor.isShown || !anchor.isEnabled) return false
        val token = anchor.windowToken ?: return false
        geometry = null
        if (!refreshGeometry(updateWindow = false)) return false
        generation++
        val root = anchor.rootView
        focusReturn = FocusReturn(WeakReference(root), root.findFocus()?.let(::WeakReference), token, generation)
        presentationActive = true
        anchor.addOnAttachStateChangeListener(anchorAttachment)
        observedTree = root.viewTreeObserver.also {
            it.addOnGlobalLayoutListener(globalLayout)
            it.addOnScrollChangedListener(anchorScroll)
        }
        try {
            window.showAsDropDown(anchor, horizontalWindowOffset, verticalWindowOffset, Gravity.START)
        } catch (_: WindowManager.BadTokenException) {
            removeObservers()
            presentationActive = false
            focusReturn = null
            // showAsDropDown marks the framework popup showing before adding its window.
            // Reset that state and its anchor observers even when the token was rejected.
            window.dismiss()
            return false
        }
        val shownGeneration = generation
        surface.post {
            if (isShowing && generation == shownGeneration) {
                if (anchor.isInTouchMode) surface.requestFocus() else focusBoundary(first = true)
            }
        }
        return true
    }

    fun dismiss() {
        requireMainThread()
        if (window.isShowing) window.dismiss()
    }

    private fun createRow(itemId: Int) = MenuRow(context, palette, rowStyle()).apply {
        this.id = View.generateViewId()
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setOnClickListener {
            val current = entries[itemId]?.item
            if (isShowing && anchor.isAttachedToWindow && anchor.isShown && anchor.isEnabled && current?.enabled == true) {
                val clickedGeneration = generation
                if (onItemClick?.invoke(itemId) == true && generation == clickedGeneration && isShowing) dismiss()
            }
        }
        setOnKeyListener { _, key, event -> handleKey(key, event) }
    }

    private fun bindRow(entry: Entry) {
        val item = entry.item ?: return
        val row = entry.row
        row.applyStyle(rowStyle())
        row.applyPalette(palette)
        if (!TextUtils.equals(row.text, item.label)) row.text = item.label
        row.contentDescription = item.contentDescription
        row.tag = item.tag
        row.bindSelection(item.selected)
        row.isEnabled = item.enabled
        row.isFocusable = item.enabled
        val iconColor = style.iconColor ?: palette.muted
        row.bindIconTint(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf((iconColor and 0x00ffffff) or ((Color.alpha(iconColor) * style.disabledIconAlpha).roundToInt() shl 24), iconColor)))
        if (entry.icon !== item.icon || entry.iconSize != dp(style.iconSizeDp)) {
            entry.icon = item.icon
            entry.iconSize = dp(style.iconSizeDp)
            entry.iconDrawable = cloneIcon(item.icon, entry.iconSize)
        }
        if (entry.selectionIcon !== item.selectionIcon || entry.selectionIconSize != dp(style.iconSizeDp)) {
            entry.selectionIcon = item.selectionIcon
            entry.selectionIconSize = dp(style.iconSizeDp)
            entry.selectionDrawable = cloneIcon(item.selectionIcon, entry.selectionIconSize)
            entry.selectionSpace.setBounds(0, 0, entry.selectionIconSize, entry.selectionIconSize)
        }
        val trailing = when (item.selected) {
            null -> null
            true -> entry.selectionDrawable ?: entry.selectionSpace
            false -> entry.selectionSpace
        }
        val current = row.compoundDrawablesRelative
        if (current[0] !== entry.iconDrawable || current[2] !== trailing)
            row.setCompoundDrawablesRelative(entry.iconDrawable, null, trailing, null)
        row.compoundDrawablePadding = dp(style.iconGapDp)
    }

    private fun cloneIcon(icon: Drawable?, size: Int): Drawable? = icon?.let {
        (it.constantState?.newDrawable(context.resources)?.mutate() ?: it.mutate()).apply {
            setBounds(0, 0, size, size)
        }
    }

    private fun rowStyle() = NativeButtonStyle(radiusDp = 0f,
        minHeightDp = style.minRowHeightDp, minWidthDp = 0f,
        horizontalPaddingDp = style.rowHorizontalPaddingDp,
        verticalPaddingDp = style.rowVerticalPaddingDp, textStyle = style.textStyle,
        disabledContentColor = (palette.content and 0x00ffffff) or
            ((Color.alpha(palette.content) * style.disabledContentAlpha).roundToInt() shl 24))

    private fun surfaceStyle() = NativeSurfaceStyle(fillColor = style.surfaceColor ?: palette.raised,
        cornerRadiusDp = style.cornerRadiusDp, outlineColor = style.outlineColor, noise = style.noise)

    private fun applyGeometryAndPaint() {
        surface.applyStyle(surfaceStyle())
        rows.setPadding(dp(style.horizontalPaddingDp), dp(style.verticalPaddingDp),
            dp(style.horizontalPaddingDp), dp(style.verticalPaddingDp))
        windowColors.retarget(style.surfaceColor ?: palette.raised)
        windowBackground.cornerRadius = style.cornerRadiusDp * context.resources.displayMetrics.density
        if (window.background !== windowBackground) window.setBackgroundDrawable(windowBackground)
        window.elevation = style.elevationDp * context.resources.displayMetrics.density
        entries.values.forEach(::bindRow)
    }

    private fun refreshGeometry(updateWindow: Boolean = true): Boolean {
        if (updateWindow && !isShowing) return false
        if (!anchor.isAttachedToWindow || !anchor.isShown || !anchor.isEnabled || !anchor.getGlobalVisibleRect(visibleAnchor)) {
            if (isShowing) dismiss()
            return false
        }
        anchor.getWindowVisibleDisplayFrame(visibleFrame)
        anchor.getLocationOnScreen(location)
        if (visibleFrame.width() <= 0 || visibleFrame.height() <= 0) {
            if (isShowing) dismiss()
            return false
        }
        val key = Geometry(visibleFrame.left, visibleFrame.top, visibleFrame.right, visibleFrame.bottom,
            location[0], location[1], anchor.width, anchor.height, anchor.layoutDirection,
            context.resources.displayMetrics.density, context.resources.configuration.fontScale)
        if (key == geometry) return true
        if (geometry?.density != key.density || geometry?.fontScale != key.fontScale) applyGeometryAndPaint()
        geometry = key
        surface.layoutDirection = anchor.layoutDirection
        val frame = NativePopupPlacement.insetFrame(
            NativePopupBounds(visibleFrame.left, visibleFrame.top, visibleFrame.right, visibleFrame.bottom),
            dp(style.horizontalMarginDp), dp(style.verticalMarginDp))
        val anchorBounds = NativePopupBounds(location[0], location[1], location[0] + anchor.width, location[1] + anchor.height)
        val maxWidth = minOf(dp(style.maxWidthDp).coerceAtLeast(1), frame.width)
        val offset = signedDp(style.verticalOffsetDp)
        val maxHeight = NativePopupPlacement.maxHeight(frame, anchorBounds, offset)
        surface.measure(View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val width = surface.measuredWidth.coerceAtLeast(dp(style.minWidthDp).coerceAtMost(maxWidth)).coerceAtMost(maxWidth)
        surface.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
        val height = surface.measuredHeight.coerceAtLeast(1)
        val rtl = anchor.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val position = NativePopupPlacement.position(frame, anchorBounds, width, height,
            signedDp(style.horizontalOffsetDp), offset, rtl)
        // PopupWindow's START gravity uses the anchor's right edge in RTL. Supplying the
        // chosen screen position as anchor-relative offsets retains native anchor tracking,
        // above/below animation selection and Back/outside handling while honoring our gutters.
        horizontalWindowOffset = position.left - (if (rtl) anchorBounds.right - width else anchorBounds.left)
        verticalWindowOffset = position.top - anchorBounds.bottom
        if (updateWindow) window.update(anchor, horizontalWindowOffset, verticalWindowOffset, width, height)
        else { window.width = width; window.height = height }
        return true
    }

    private fun handleKey(key: Int, event: KeyEvent): Boolean {
        if (key == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) {
            dismiss()
            return true
        }
        if (key == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP) dismiss()
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when (key) {
            KeyEvent.KEYCODE_DPAD_DOWN -> moveFocus(1, wrap = false)
            KeyEvent.KEYCODE_DPAD_UP -> moveFocus(-1, wrap = false)
            KeyEvent.KEYCODE_TAB -> moveFocus(if (event.isShiftPressed) -1 else 1, wrap = true)
            KeyEvent.KEYCODE_MOVE_HOME -> { focusBoundary(first = true); true }
            KeyEvent.KEYCODE_MOVE_END -> { focusBoundary(first = false); true }
            else -> false // Native buttons handle activation; PopupWindow owns Back and outside touch.
        }
    }

    private fun enabledRows() = orderedIds.mapNotNull { entries[it]?.takeIf { entry -> entry.item?.enabled == true }?.row }

    private fun moveFocus(delta: Int, wrap: Boolean): Boolean {
        val enabled = enabledRows()
        if (enabled.isEmpty()) { surface.requestFocus(); return true }
        val current = enabled.indexOfFirst { it.hasFocus() }
        val index = if (current < 0) { if (delta > 0) 0 else enabled.lastIndex }
        else if (wrap) (current + delta + enabled.size) % enabled.size
        else (current + delta).coerceIn(0, enabled.lastIndex)
        enabled[index].requestFocus()
        return true
    }

    private fun focusBoundary(first: Boolean) {
        val enabled = enabledRows()
        if ((if (first) enabled.firstOrNull() else enabled.lastOrNull())?.requestFocus() != true) surface.requestFocus()
    }

    private fun finishDismissal() {
        if (!presentationActive) return
        presentationActive = false
        removeObservers()
        val restore = focusReturn
        val restoreAdmission = canRestoreFocus
        focusReturn = null
        onDismiss?.invoke()
        if (restore != null) anchor.post {
            val root = restore.root.get() ?: return@post
            if (generation != restore.generation || isShowing || !anchor.isAttachedToWindow ||
                !anchor.isShown || !anchor.isEnabled || anchor.rootView !== root ||
                anchor.windowToken != restore.windowToken || !root.hasWindowFocus()) return@post
            if (restoreAdmission?.invoke() == false) return@post
            val previous = restore.previousFocus?.get()
            val focused = root.findFocus()
            if (focused != null && focused !== previous && focused !== anchor) return@post
            val target = previous?.takeIf { it.isAttachedToWindow && it.isShown && it.isEnabled && it.isFocusable && it.rootView === root } ?: anchor
            target.requestFocus()
        }
    }

    private fun removeObservers() {
        anchor.removeOnAttachStateChangeListener(anchorAttachment)
        observedTree?.takeIf { it.isAlive }?.let {
            it.removeOnGlobalLayoutListener(globalLayout)
            it.removeOnScrollChangedListener(anchorScroll)
        }
        observedTree = null
    }

    private fun dp(value: Float) = context.componentDp(value)
    private fun signedDp(value: Float) = (value * context.resources.displayMetrics.density).roundToInt()
    private fun requireMainThread() { check(Looper.myLooper() == Looper.getMainLooper()) }
    private class Entry(val row: MenuRow) {
        var item: NativeMenuItem? = null
        var icon: Drawable? = null
        var iconDrawable: Drawable? = null
        var iconSize = -1
        var selectionIcon: Drawable? = null
        var selectionDrawable: Drawable? = null
        var selectionIconSize = -1
        val selectionSpace = ColorDrawable(Color.TRANSPARENT)
    }
    private class MenuRow(context: Context, palette: ControlPalette, style: NativeButtonStyle) :
        NativeButton(context, palette, style) {
        private var menuSelection: Boolean? = null
        private var menuIconTint: ColorStateList? = null
        private var iconColors:smallthingz.ui.animation.view.ViewColorTransition?=null

        fun bindSelection(selected: Boolean?) {
            val changed = menuSelection != selected
            menuSelection = selected
            // Exclusive menu selection is semantic, not a selected surface/color treatment.
            isSelected = false
            if (changed) sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }

        fun bindIconTint(colors: ColorStateList) {
            menuIconTint = colors
            restoreIconTint()
        }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.isSelected = menuSelection == true
            info.isCheckable = false
            info.isChecked = false
        }

        override fun onConfigurationChanged(newConfig: Configuration) {
            super.onConfigurationChanged(newConfig)
            restoreIconTint()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            restoreIconTint()
        }

        private fun restoreIconTint() {
            val colors = menuIconTint ?: return
            val target=colors.getColorForState(drawableState,colors.defaultColor)
            val motion=iconColors ?: smallthingz.ui.animation.view.ViewColorTransition(this,intArrayOf(target),::publishIconColor).also {iconColors=it}
            motion.retarget(target)
            publishIconColor()
        }
        private fun publishIconColor() {
            val color = iconColors?.color(0) ?: return
            if (compoundDrawableTintList?.defaultColor != color)
                compoundDrawableTintList = ColorStateList.valueOf(color)
        }
        override fun drawableStateChanged(){super.drawableStateChanged();restoreIconTint()}
    }
    private data class Geometry(val left: Int, val top: Int, val right: Int, val bottom: Int,
        val anchorX: Int, val anchorY: Int, val anchorWidth: Int, val anchorHeight: Int,
        val direction: Int, val density: Float, val fontScale: Float)
    private data class FocusReturn(val root: WeakReference<View>, val previousFocus: WeakReference<View>?,
        val windowToken: IBinder, val generation: Long)
}
