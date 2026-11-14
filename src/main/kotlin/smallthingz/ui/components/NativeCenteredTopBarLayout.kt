package smallthingz.ui.components

import android.content.Context
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Centered title, independently measured navigation/action slots and clipped content viewport. */
open class NativeCenteredTopBarLayout(
    context: Context,
    private var density: Float,
    val navigationView: View,
    val titleView: View,
    val actionView: View,
) : ViewGroup(context) {
    private var logicalHeight = 0
    private var titleWrapperWidth = 0
    private var navigationWrapperWidth = 0
    private var actionWrapperWidth = 0
    private var navigationPadding = 0
    private var actionPadding = 0
    private var titlePadding = 0


    private val navigation get() = navigationView
    private val title get() = titleView
    private val done get() = actionView
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false; clipToPadding = false
        addView(navigation); addView(title); addView(done)
    }
    fun bindDensity(value: Float) { if (density != value) { density = value; requestLayout() } }
    private fun dp(value: Float) = (value * density).roundToInt()
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val maxH = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
            else MeasureSpec.getSize(heightMeasureSpec)
        val childH = if (maxH == Int.MAX_VALUE) unspecified() else atMost(maxH)
        navigationPadding = dp(4f)
        actionPadding = dp(4f)
        navigation.measure(atMost((w - navigationPadding).coerceAtLeast(0)), childH)
        done.measure(atMost((w - actionPadding).coerceAtLeast(0)), childH)
        navigationWrapperWidth = min(w, navigation.measuredWidth + navigationPadding)
        actionWrapperWidth = min(w, done.measuredWidth + actionPadding)
        val titleMax = (w - navigationWrapperWidth - actionWrapperWidth).coerceAtLeast(0)
        titlePadding = dp(4f)
        title.measure(atMost((titleMax - titlePadding * 2).coerceAtLeast(0)), childH)
        titleWrapperWidth = min(titleMax, title.measuredWidth + titlePadding * 2)
        logicalHeight = max(dp(64f), title.measuredHeight)
        setMeasuredDimension(w, resolveSize(logicalHeight, heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val p = centeredTopBarPlacement(width, logicalHeight, navigationWrapperWidth,
            navigation.measuredHeight, titleWrapperWidth, title.measuredHeight,
            actionWrapperWidth, done.measuredHeight, density, rtl)
        // Placeable apparentToRealOffset centers a source layout that exceeds constraints.
        val yOffset = (height - logicalHeight) / 2
        val nx = p.navigationX + if (rtl)
            navigationWrapperWidth - navigation.measuredWidth - navigationPadding else navigationPadding
        navigation.layout(nx, p.navigationY + yOffset, nx + navigation.measuredWidth,
            p.navigationY + yOffset + navigation.measuredHeight)
        val tx = p.titleX + if (rtl) titleWrapperWidth - title.measuredWidth - titlePadding else titlePadding
        title.layout(tx, p.titleY + yOffset, tx + title.measuredWidth,
            p.titleY + yOffset + title.measuredHeight)
        val ax = p.actionX + if (rtl) actionWrapperWidth - done.measuredWidth else 0
        done.layout(ax, p.actionY + yOffset, ax + done.measuredWidth,
            p.actionY + yOffset + done.measuredHeight)
    }
    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        try {
            // Source windowInsetsPadding precedes clipToBounds: status/cutout area stays clear.
            canvas.clipRect(0, 0, width, height)
            super.draw(canvas)
        } finally { canvas.restoreToCount(save) }
    }
    private companion object {
        fun atMost(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.AT_MOST)
        fun unspecified() = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
    }
}
