package smallthingz.ui.components

import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import smallthingz.ui.animation.view.ViewColorTransition
import kotlin.math.roundToInt

/** Decorative page indicator and spacing for assigned-size onboarding actions. */
data class NativeOnboardingFooterRecipe(
    val indicatorTopGapDp: Float = 16f,
    val indicatorRowHeightDp: Float = 8f,
    val actionsTopGapDp: Float = 14f,
    val actionGapDp: Float = 10f,
    val minimumPageSpaceDp: Float = 48f,
    val selectedWidthDp: Float = 18f,
    val unselectedWidthDp: Float = 6f,
    val indicatorHeightDp: Float = 6f,
    val indicatorGapDp: Float = 8f,
) {
    init {
        require(floatArrayOf(indicatorTopGapDp, indicatorRowHeightDp, actionsTopGapDp, actionGapDp,
            minimumPageSpaceDp, selectedWidthDp, unselectedWidthDp, indicatorHeightDp, indicatorGapDp)
            .all { it.isFinite() && it >= 0f })
    }

    fun actionsTopPx(density: Float): Int = (indicatorTopGapDp * density).roundToInt() +
        (indicatorRowHeightDp * density).roundToInt() + (actionsTopGapDp * density).roundToInt()

    fun createIndicator(owner: View, pageCount: Int): NativePageIndicator = NativePageIndicator(owner, pageCount, this)

    companion object {
        val Default = NativeOnboardingFooterRecipe()
    }
}

/** Draws inside its existing owner and adds no touch target or accessibility node. */
class NativePageIndicator internal constructor(
    private val owner: View,
    private val pageCount: Int,
    private val recipe: NativeOnboardingFooterRecipe,
) {
    init { require(pageCount > 0) }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val targets = IntArray(pageCount)
    private var selectedPage = 0
    private var colors: ViewColorTransition? = null

    fun bind(page: Int, palette: NativeColorScheme) {
        require(page in 0 until pageCount)
        val current = colors
        selectedPage = page
        targets.fill(palette.outlineVariant)
        targets[page] = palette.primary
        if (current == null) colors = ViewColorTransition(owner, targets, owner::invalidate)
        else current.retarget(*targets)
        owner.invalidate()
    }

    fun draw(canvas: Canvas) {
        val motion = colors ?: return
        val density = owner.resources.displayMetrics.density
        val selectedWidth = (recipe.selectedWidthDp * density).roundToInt()
        val unselectedWidth = (recipe.unselectedWidthDp * density).roundToInt()
        val gap = (recipe.indicatorGapDp * density).roundToInt()
        val dotHeight = (recipe.indicatorHeightDp * density).roundToInt()
        val rowHeight = (recipe.indicatorRowHeightDp * density).roundToInt()
        val total = selectedWidth + (unselectedWidth + gap) * (pageCount - 1)
        var x = (owner.width - total) / 2f
        val top = (recipe.indicatorTopGapDp * density).roundToInt() + ((rowHeight - dotHeight) / 2f).roundToInt()
        val rtl = owner.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val radius = dotHeight / 2f
        for (visualIndex in 0 until pageCount) {
            val page = if (rtl) pageCount - 1 - visualIndex else visualIndex
            val dotWidth = if (page == selectedPage) selectedWidth else unselectedWidth
            val start = x.roundToInt().toFloat()
            paint.color = motion.color(page)
            canvas.drawRoundRect(start, top.toFloat(), start + dotWidth, (top + dotHeight).toFloat(), radius, radius, paint)
            x += dotWidth + gap
        }
    }
}
