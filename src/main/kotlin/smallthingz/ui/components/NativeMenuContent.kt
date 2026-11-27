/*
 * Source-derived from Material3 1.4.0 DropdownMenuContent/DropdownMenuItemContent and released
 * SettingsScreen.kt:2397-2486. Copyright 2022 The Android Open Source Project.
 * Licensed under the Apache License, Version 2.0:
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package smallthingz.ui.components

import smallthingz.ui.interaction.view.isNativeClickActivationKey

import smallthingz.ui.geometry.roundedGroupGeometry
import smallthingz.ui.scroll.NativeVerticalNestedHost
import smallthingz.ui.scroll.NativeVerticalScrollSource
import smallthingz.ui.scroll.NativeVerticalScrollState
import smallthingz.ui.scroll.NativeVerticalViewport

import smallthingz.ui.interaction.view.NativeClickBinding
import smallthingz.ui.interaction.view.NativeClickEnvironment
import smallthingz.ui.interaction.view.RowIndication
import smallthingz.ui.interaction.view.RowIndicationSink
import smallthingz.ui.interaction.view.ViewClickController
import smallthingz.ui.interaction.view.readCachedAnimatorDurationScale
import smallthingz.ui.text.view.ParagraphView
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.text.style.TextOverflow
import smallthingz.ui.unit.Density
import androidx.core.view.ViewCompat
import kotlinx.coroutines.CoroutineScope
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A source Surface: shadow outside its rounded content clip; no tonal overlay at 0dp. */
internal class NativeMenuContent<I>(
    context: Context,
    density: Density,
    labelLarge: TextStyle,
    resolver: FontFamily.Resolver,
    palette: NativeMenuColors,
    recipe: NativeAnchoredMenuRecipe,
    icons: NativeMenuIcons<I>,
    state: NativeVerticalScrollState,
    frames: MonotonicFrameClock,
    scope: CoroutineScope,
    /** Current source gesture-connection interest, supplied by the popup phase owner. */
    interestedParent: (MotionEvent) -> Boolean,
) : NativeMenuSurface(context, density, recipe, palette.raised), AutoCloseable {
    val column = NativeMenuColumn(context, labelLarge, density, resolver, icons, interestedParent)
    val viewport = NativeVerticalViewport(
        context, state, density, frames, scope,
        object : NativeVerticalNestedHost {
            override fun preScroll(availableY: Float, source: NativeVerticalScrollSource) = 0f
            override fun postScroll(consumedY: Float, availableY: Float, source: NativeVerticalScrollSource) = 0f
            override suspend fun preFling(availableY: Float) = 0f
            override suspend fun postFling(consumedY: Float, availableY: Float) = 0f
        },
        glowColor = palette.muted,
    )

    init {
        bindGrain(palette.grainIntensity)
        viewport.setContent(column)
        setBody(viewport)
    }

    fun <T> bind(content: NativeMenuChoices<T>, palette: NativeMenuColors, select: (T) -> Unit) {
        bindFill(palette.raised)
        viewport.setGlowColor(palette.muted)
        bindGrain(palette.grainIntensity)
        column.bind(content, palette, select)
        requestLayout()
        invalidate()
    }

    /** Released Incidents/Library use default12dp horizontal/zero vertical item padding. */
    fun bindActions(actions: List<ResolvedMenuAction<I>>, palette: NativeMenuColors, background: Int = palette.surfaceContainerHigh) {
        bindFill(background)
        viewport.setGlowColor(palette.muted)
        bindGrain(palette.grainIntensity)
        column.bindActions(actions, palette)
        requestLayout()
        invalidate()
    }

    override fun measureBody(body: View, widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED)
            Int.MAX_VALUE else MeasureSpec.getSize(widthMeasureSpec)
        val width = column.maxIntrinsicWidth().coerceAtMost(availableWidth)
        // The surface's vertical padding is outside the scrolling viewport.
        body.measure(exact(width), atMost(MeasureSpec.getSize(heightMeasureSpec)))
    }

    override fun close() = column.close()
}

internal class NativeMenuColumn<I>(
    context: Context,
    private val style: TextStyle,
    private val density: Density,
    private val resolver: FontFamily.Resolver,
    private val icons: NativeMenuIcons<I>,
    private val interestedParent: (MotionEvent) -> Boolean,
) : ViewGroup(context), AutoCloseable {
    val rows = ArrayList<NativeMenuRowView<I>>()
    var rowInputAllowed: (() -> Boolean)? = null
    init { clipChildren = false; clipToPadding = false }

    fun <T> bind(content: NativeMenuChoices<T>, palette: NativeMenuColors, select: (T) -> Unit) {
        while (rows.size > content.options.size) {
            val row = rows.removeAt(rows.lastIndex)
            row.close()
            removeView(row)
        }
        content.options.forEachIndexed { index, option ->
            val row = rows.getOrNull(index) ?: NativeMenuRowView(
                context, style, density, resolver, palette, icons, interestedParent,
            ).also { rows += it; addView(it) }
            // The released forEach has no key; node identity belongs to position, not option value.
            row.bind(content.optionLabel(option), option == content.selected, palette) { select(option) }
        }
        requestLayout()
    }

    fun bindActions(actions: List<ResolvedMenuAction<I>>, palette: NativeMenuColors) {
        while (rows.size > actions.size) {
            val row = rows.removeAt(rows.lastIndex)
            row.close()
            removeView(row)
        }
        actions.forEachIndexed { index, action ->
            val row = rows.getOrNull(index) ?: NativeMenuRowView(
                context, style, density, resolver, palette, icons, interestedParent,
            ).also { rows += it; addView(it) }
            row.bindAction(action, palette)
        }
        requestLayout()
    }

    fun maxIntrinsicWidth(): Int = rows.maxOfOrNull { it.maxIntrinsicWidth() } ?: 0

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        var height = 0
        for (row in rows) {
            row.measure(exact(width), unspecified())
            height += row.measuredHeight
        }
        setMeasuredDimension(width, resolveSize(height, h))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var y = 0
        for (row in rows) {
            row.layout(0, y, width, y + row.measuredHeight)
            y += row.measuredHeight
        }
    }
    override fun close() { rows.forEach { it.close() } }
}

internal class NativeMenuRowView<I>(
    context: Context,
    style: TextStyle,
    private val density: Density,
    private val resolver: FontFamily.Resolver,
    palette: NativeMenuColors,
    private val icons: NativeMenuIcons<I>,
    private val interestedParent: (MotionEvent) -> Boolean,
) : ViewGroup(context), AutoCloseable {
    // DropdownMenuItem's ProvideTextStyle(labelLarge) merges the inherited default body style.
    private val text = ParagraphView(context, style, density, resolver, palette.muted).also {
        NativeModalTextSurface.bindTree(it)
    }
    private val check = icons.create(context, icons.check, palette.primary)
    private val horizontal = dp(16f)
    private val vertical = dp(8f)
    private val radius = 12f * density.density
    private val clip = Path()
    private val indication = RowIndication.install(this, palette.ink, { readCachedAnimatorDurationScale(context) })
    private val sink = RowIndicationSink(indication) { x, y, point -> point.set(x, y) }
    var pointerRoot: View? = null
    val input = ViewClickController(this, object : NativeClickEnvironment {
        override val nativeInputRoot get() = pointerRoot
        override val minimumTouchWidthPx get() = 48f * density.density
        override val minimumTouchHeightPx get() = minimumTouchWidthPx
        override fun admitDown(event: MotionEvent) =
            roundedGroupGeometry(width.toFloat(),height.toFloat(),density.density,12f)
                .acceptsPointer(event.x,event.y,event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER,minimumTouchWidthPx)
        override fun interestedParent(event: MotionEvent) = this@NativeMenuRowView.interestedParent(event)
        override fun longPressOwnershipChanged(owns: Boolean) = Unit
    }, sink)
    private var selected = false
    private var leading = false
    private var actionCheck: NativeMenuCheckState? = null
    private var action: (() -> Unit)? = null
    private var closed = false
    private var supportingText: ParagraphView? = null
    private var customContent = false
    private val ownerAdmitsInput: Boolean
        get() = (parent as? NativeMenuColumn<*>)?.rowInputAllowed?.invoke() != false

    init {
        clipChildren = false
        clipToPadding = false
        clipToOutline = true
        isClickable = true
        isFocusable = true
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setScreenReaderFocusable(this, true)
        ViewCompat.setScreenReaderFocusable(text, false)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, out: Outline) =
                out.setRoundRect(0,0,view.width,view.height,radius)
        }
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        addView(text)
        addView(check)
        input.bind(NativeClickBinding(this, true, { performClick() }))
    }
    private fun dp(value: Float) = (value * density.density).roundToInt()

    fun bind(label: String, selected: Boolean, palette: NativeMenuColors, action: () -> Unit) {
        resetCustomContent()
        this.action = action
        val selectionChanged = this.selected != selected || actionCheck != null
        this.selected = selected
        actionCheck = null
        leading = false
        isEnabled = true
        input.bind(NativeClickBinding(this, true, { performClick() }))
        text.setText(label)
        text.animateTextColor(if (selected) palette.ink else palette.muted)
        icons.bind(check, icons.check, palette.primary)
        check.visibility = if (selected) VISIBLE else GONE
        indication.updateColor(palette.ink)
        requestLayout()
        if (selectionChanged) publishSelectionChange()
    }

    fun bindAction(value: ResolvedMenuAction<I>, palette: NativeMenuColors) {
        resetCustomContent()
        action = value.onClick
        val checkState = (icons as? NativeCheckableMenuIcons<I>)?.checkState(value.icon)
        val selectionChanged = actionCheck != checkState || selected != (checkState?.checked == true)
        actionCheck = checkState
        selected = checkState?.checked == true
        leading = checkState == null
        isEnabled = value.enabled
        text.setText(value.label)
        text.animateTextColor(value.textColor)
        icons.bind(check, if (selected) icons.check else value.icon, value.iconColor)
        check.visibility = if (actionCheck != null && !selected) INVISIBLE else VISIBLE
        indication.updateColor(palette.ink)
        input.bind(NativeClickBinding(this, value.enabled, { performClick() }))
        requestLayout()
        if (selectionChanged) publishSelectionChange()
    }

    /** Plain snapshot actions may omit an icon and include muted supporting text. */
    fun bindContent(label: String, detail: String?, detailStyle: TextStyle, icon: I?, enabled: Boolean,
                    palette: NativeMenuColors, action: (() -> Unit)?) =
        bindContent(label, detail, detailStyle, icon, enabled, palette, twoLineTitle = false, action = action)

    fun bindContent(label: String, detail: String?, detailStyle: TextStyle, icon: I?, enabled: Boolean,
                    palette: NativeMenuColors, twoLineTitle: Boolean, action: (() -> Unit)?) {
        customContent = true
        this.action = action
        val selectionChanged = selected || actionCheck != null
        selected = false
        actionCheck = null
        leading = icon != null
        isEnabled = enabled
        isClickable = action != null
        isFocusable = action != null
        importantForAccessibility = if (action != null) IMPORTANT_FOR_ACCESSIBILITY_YES else IMPORTANT_FOR_ACCESSIBILITY_NO
        ViewCompat.setScreenReaderFocusable(this, action != null)
        text.importantForAccessibility = if (action != null) IMPORTANT_FOR_ACCESSIBILITY_NO else IMPORTANT_FOR_ACCESSIBILITY_YES
        if (twoLineTitle) text.setTextLayout(maxLines = 2, overflow = TextOverflow.Ellipsis)
        else text.setTextLayout()
        text.setText(label)
        text.animateTextColor(palette.ink)
        if (icon != null) icons.bind(check, icon, palette.ink)
        check.visibility = if (leading) VISIBLE else GONE
        val supporting = detail?.takeIf { it.isNotEmpty() }
        if (supporting != null) {
            val paragraph = supportingText ?: ParagraphView(context, detailStyle, density, resolver, palette.muted).also {
                NativeModalTextSurface.bindTree(it)
                ViewCompat.setScreenReaderFocusable(it, false)
                it.setTextLayout(maxLines = 1, overflow = TextOverflow.Ellipsis)
                supportingText = it
                addView(it)
            }
            paragraph.setTypography(detailStyle, density, resolver)
            paragraph.setText(supporting)
            paragraph.animateTextColor(palette.muted)
            paragraph.importantForAccessibility = text.importantForAccessibility
            paragraph.visibility = VISIBLE
        } else supportingText?.visibility = GONE
        contentDescription = if (action != null) listOfNotNull(label, supporting).joinToString(", ") else null
        indication.updateColor(palette.ink)
        input.bind(if (action == null) null else NativeClickBinding(this, enabled, { performClick() }))
        requestLayout()
        if (selectionChanged) publishSelectionChange()
    }

    /** Original bindings retain their original shape, labels and merged accessibility policy. */
    private fun resetCustomContent() {
        if (!customContent) return
        customContent = false
        text.setTextLayout()
        supportingText?.visibility = GONE
        contentDescription = null
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setScreenReaderFocusable(this, true)
        text.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun maxIntrinsicWidth(): Int =
        (max(text.maxIntrinsicTextWidth(), supportingText?.takeIf { it.visibility == VISIBLE }?.maxIntrinsicTextWidth() ?: 0) +
            2 * (if (leading) dp(12f) else horizontal) +
            if (leading || selected || actionCheck != null) dp(24f) + dp(12f) else 0)
            .coerceIn(dp(112f), dp(280f))

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val inset = if (leading) dp(12f) else horizontal
        val inner = (width - 2 * inset).coerceAtLeast(0)
        val hasIcon = leading || selected || actionCheck != null
        val iconWidth = if (hasIcon) min(dp(24f), inner) else 0
        val gap = if (hasIcon) min(dp(12f), (inner - iconWidth).coerceAtLeast(0)) else 0
        if (hasIcon) check.measure(exact(iconWidth), exact(dp(24f)))
        text.measure(atMost((inner-iconWidth-gap).coerceAtLeast(0)), unspecified())
        val detail = supportingText?.takeIf { it.visibility == VISIBLE }
        detail?.measure(atMost((inner-iconWidth-gap).coerceAtLeast(0)), unspecified())
        val textHeight = text.measuredHeight + (detail?.measuredHeight ?: 0)
        val height = max(dp(48f), max(textHeight, if (hasIcon) check.measuredHeight else 0) +
            if (leading) 0 else 2 * vertical)
        setMeasuredDimension(width, resolveSize(height, h))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val inset = if (leading) dp(12f) else horizontal
        val textStart = inset + if (leading) check.measuredWidth + dp(12f) else 0
        val textX = if (rtl) width - textStart - text.measuredWidth else textStart
        val detail = supportingText?.takeIf { it.visibility == VISIBLE }
        val textHeight = text.measuredHeight + (detail?.measuredHeight ?: 0)
        val textY = ((height-textHeight)/2f).roundToInt()
        text.layout(textX,textY,textX+text.measuredWidth,textY+text.measuredHeight)
        detail?.let {
            val x = if (rtl) width - textStart - it.measuredWidth else textStart
            val y = textY + text.measuredHeight
            it.layout(x, y, x + it.measuredWidth, y + it.measuredHeight)
        }
        if (leading || selected || actionCheck != null) {
            val logicalStart = if (leading) inset else width-inset-check.measuredWidth
            val x = if (rtl) width-logicalStart-check.measuredWidth else logicalStart
            val y = ((height-check.measuredHeight)/2f).roundToInt()
            check.layout(x,y,x+check.measuredWidth,y+check.measuredHeight)
        }
        indication.onMeasured(width,height,density.density)
    }

    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
        clip.rewind()
        clip.addRoundRect(0f,0f,w.toFloat(),h.toFloat(),radius,radius,Path.Direction.CW)
        invalidateOutline()
    }
    override fun draw(canvas: Canvas) {
        if (canvas.isHardwareAccelerated) super.draw(canvas)
        else {
            val save=canvas.save()
            try { canvas.clipPath(clip); super.draw(canvas) }
            finally { canvas.restoreToCount(save) }
        }
    }
    override fun performClick(): Boolean {
        if (closed || !isEnabled || !ownerAdmitsInput) return false
        val currentAction = action ?: return false
        super.performClick()
        currentAction()
        return true
    }
    override fun onTouchEvent(event: MotionEvent) = input.touch(event)
    override fun onKeyDown(code: Int,event: KeyEvent): Boolean =
        if (isNativeClickActivationKey(code)) input.key(event) else super.onKeyDown(code,event)
    override fun onKeyUp(code: Int,event: KeyEvent): Boolean =
        if (isNativeClickActivationKey(code)) input.key(event) else super.onKeyUp(code,event)
    override fun onHoverEvent(event: MotionEvent): Boolean {
        val handled = input.hover(event)
        val accessibilityHandled = super.onHoverEvent(event)
        return handled || accessibilityHandled
    }
    override fun onFocusChanged(gain: Boolean,direction: Int,previously: Rect?) {
        super.onFocusChanged(gain,direction,previously)
        input.focusChanged(gain)
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        input.attached()
        if (!closed && (!customContent || action != null)) input.bind(NativeClickBinding(this, isEnabled, { performClick() }))
    }
    override fun onDetachedFromWindow() { input.detached(); super.onDetachedFromWindow() }
    override fun onCancelPendingInputEvents() { super.onCancelPendingInputEvents(); input.cancel() }
    override fun performAccessibilityAction(action: Int, args: Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.ACTION_CLICK && !ownerAdmitsInput) return false
        return super.performAccessibilityAction(action, args)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (!ownerAdmitsInput) {
            info.isEnabled = false
            info.isClickable = false
            info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
        }
        val state = actionCheck
        info.className = when {
            state == null -> View::class.java.name
            state.exclusive -> android.widget.RadioButton::class.java.name
            else -> android.widget.CheckBox::class.java.name
        }
        info.isSelected = state == null && selected
        if (state != null) {
            info.isCheckable = true
            info.isChecked = state.checked
        }
    }
    private fun publishSelectionChange() {
        sendAccessibilityEvent(if (actionCheck == null && selected) AccessibilityEvent.TYPE_VIEW_SELECTED
            else AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }
    override fun close() { closed=true; action=null; input.bind(null); input.cancel() }
}
