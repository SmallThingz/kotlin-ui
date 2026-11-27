package smallthingz.ui.zfm

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import smallthingz.ui.components.NativeSurface
import kotlin.math.min
import kotlin.math.roundToInt

/** Presentation only: the host owns thumbnail admission, invalidation and bitmap lifetime. */
class ZfmFilePreview(
    context: Context,
    theme: ZfmTheme,
    private val fallback: Drawable,
) : NativeSurface(context, theme.cardStyle()) {
    private val fallbackTint = ColorStateList.valueOf(theme.muted)
    private var hasBitmap = false
    private val image = ImageView(context).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }

    init {
        theme.bindCard(this)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isClickable = false
        isFocusable = false
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        showFallback()
    }

    /** Paint changes never change the square slot, listeners or ownership of the supplied raster. */
    fun bindBitmap(bitmap: Bitmap) {
        hasBitmap = true
        image.imageTintList = null
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        image.setPadding(0, 0, 0, 0)
        image.setImageBitmap(bitmap)
    }

    fun showFallback() {
        hasBitmap = false
        image.imageTintList = fallbackTint
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        image.setImageDrawable(fallback)
        fitFallback(measuredWidth, measuredHeight)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private fun fitFallback(width: Int, height: Int) {
        if (hasBitmap) return
        val side = min(width, height).coerceAtLeast(0)
        val glyph = (side * 0.4f).roundToInt().coerceIn(dp(32), dp(72)).coerceAtMost(side)
        val horizontal = (width - glyph).coerceAtLeast(0) / 2
        val vertical = (height - glyph).coerceAtLeast(0) / 2
        image.setPadding(horizontal, vertical, horizontal, vertical)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize(dp(96), widthMeasureSpec)
        val height = resolveSize(width, heightMeasureSpec)
        fitFallback(width, height)
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }
}
