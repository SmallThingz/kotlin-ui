/*
 * No-label OutlinedTextField layout/border adapted from AndroidX Material3 1.4.0 and
 * foundation 1.11.2. Copyright The Android Open Source Project. Apache-2.0.
 */
package smallthingz.ui.components

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import smallthingz.ui.animation.core.Animatable
import smallthingz.ui.animation.core.VectorConverter
import smallthingz.ui.animation.core.spring
import smallthingz.ui.animation.view.ViewColorTransition
import smallthingz.ui.text.NativeTypography
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.unit.Dp
import smallthingz.ui.unit.dp
import smallthingz.ui.util.closePreservingPrimaryFailure
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/** Geometry of a single-line outlined field and its supporting row. */
data class NativeOutlinedFieldRecipe(
    private val typography: NativeTypography = NativeTypography.Default,
    val paddingDp: Float = 16f,
    val minimumTextHeightDp: Float = 24f,
    val minimumFieldHeightDp: Float = 56f,
    val supportingPaddingDp: Float = 4f,
    val minimumSupportingHeightDp: Float = 16f,
    val cornerRadiusDp: Float = 4f,
    val strokeDp: Float = 1f,
    val focusedStrokeDp: Float = 2f,
    val selectionOpacity: Float = .4f,
) {
    val textStyle = typography.defaultTextStyle
    val supportingTextStyle = textStyle.merge(typography[NativeTypographyRole.BODY_SMALL])

    /** The field height rounds the sum of the two Float paddings and the text height once. */
    fun height(textHeight: Int, supportingHeight: Int, density: Float, minimum: Int, maximum: Int): Int =
        ((paddingDp * density + textHeight + paddingDp * density).roundToInt() + supportingHeight)
            .coerceIn(minimum, maximum)

    fun createOutline(view: View, scope: CoroutineScope, density: Float, initialColor: Int): NativeOutlinedFieldOutline =
        NativeOutlinedFieldOutline(view, scope, this, density, initialColor)

    fun selectionColor(accent: Int): Int = (accent and 0x00ffffff) or
        ((selectionOpacity.coerceIn(0f, 1f) * 255f + .5f).toInt() shl 24)
}

/** Retained outline motion painted after the caller's native editor and supporting paragraph. */
class NativeOutlinedFieldOutline internal constructor(
    private val view: View,
    parentScope: CoroutineScope,
    private val recipe: NativeOutlinedFieldRecipe,
    private var density: Float,
    initialColor: Int,
) : AutoCloseable {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bounds = RectF()
    private val color = ViewColorTransition(view, intArrayOf(initialColor), view::invalidate)
    private val thickness = Animatable(recipe.strokeDp.dp, Dp.VectorConverter)
    private val targets = Channel<Dp>(Channel.CONFLATED)
    private val ownerJob = Job(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + ownerJob)
    private val stroke = recipe.strokeDp.dp
    private val focusedStroke = recipe.focusedStrokeDp.dp

    init {
        scope.launch {
            for (target in targets) {
                val next = targets.tryReceive().getOrNull() ?: target
                launch {
                    if (next != thickness.targetValue) {
                        thickness.animateTo(next, spring(dampingRatio = .9f, stiffness = 1400f)) { view.invalidate() }
                    }
                }
            }
        }
    }

    fun bind(density: Float, outline: Int, accent: Int, error: Int, focused: Boolean, invalid: Boolean) {
        this.density = density
        color.retarget(when {
            invalid -> error
            focused -> accent
            else -> outline
        })
        targets.trySend(if (focused) focusedStroke else stroke)
    }

    fun draw(canvas: Canvas, width: Int, height: Int) {
        val minimum = min(width.toFloat(), height.toFloat())
        if (minimum <= 0f) return
        val strokeWidth = min(ceil(thickness.value.value * density), ceil(minimum * .5f))
        val radius = min(recipe.cornerRadiusDp * density, minimum * .5f)
        paint.color = color.color(0)
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        if (strokeWidth * 2f > minimum) {
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(bounds, radius, radius, paint)
        } else if (radius < strokeWidth * .5f) {
            val save = canvas.save()
            try {
                canvas.clipOutRect(strokeWidth, strokeWidth, width - strokeWidth, height - strokeWidth)
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(bounds, radius, radius, paint)
            } finally {
                canvas.restoreToCount(save)
            }
        } else {
            val halfStroke = strokeWidth * .5f
            bounds.inset(halfStroke, halfStroke)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidth
            canvas.drawRoundRect(bounds, radius - halfStroke, radius - halfStroke, paint)
        }
    }

    override fun close() {
        var failure = closePreservingPrimaryFailure(null, color::close)
        failure = closePreservingPrimaryFailure(failure) { targets.close() }
        closePreservingPrimaryFailure(failure) { ownerJob.cancel() }?.let { throw it }
    }
}
