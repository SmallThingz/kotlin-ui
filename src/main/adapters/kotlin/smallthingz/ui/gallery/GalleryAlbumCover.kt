package smallthingz.ui.gallery

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeSurface
import smallthingz.ui.components.NativeSurfaceStyle
import smallthingz.ui.components.NativeText
import smallthingz.ui.components.NativeTextRole
import smallthingz.ui.controls.ControlStyle
import smallthingz.ui.input.DEFAULT_MINIMUM_TOUCH_TARGET_DP
import smallthingz.ui.text.NativeTypographyRole
import kotlin.math.roundToInt

/**
 * Retained album presentation. The caller owns images, placeholder drawable, identity and actions.
 * Caption ink belongs to the themed card surface, never to the caller's image.
 * A measured caption gets priority over the preferred card height, without hiding a title line.
 */
class GalleryAlbumCover(context: Context, colors: NativeColorScheme, private val heightBudget: () -> Int?) :
    NativeSurface(context, NativeSurfaceStyle(colors.surfaceContainerHigh)) {
    /** Prefer a 16:9 total card in a readable single-column grid. A compact height budget
     * takes precedence. Both are soft preferences: the measured caption plus a recognizable
     * cover can grow the row. RecyclerView matches rows to their tallest natural measurement. */
    var singleColumnLandscape: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    val placeholder = ImageView(context)
    val title = NativeText(context, colors.pageControls, GalleryDesign.textStyle(NativeTypographyRole.BODY_LARGE)).apply {
        maxLines = 2; ellipsize = TextUtils.TruncateAt.END
    }
    val count = NativeText(context, colors.pageControls, GalleryDesign.textStyle(NativeTypographyRole.LABEL_SMALL), NativeTextRole.MUTED).apply {
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }
    private val minimumTargetHeight = dp(DEFAULT_MINIMUM_TOUCH_TARGET_DP)
    private val caption = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val inset = dp(GalleryDesign.appearance.detailCards.compactPaddingDp)
        setPaddingRelative(inset, inset, inset, inset)
        addView(title, LinearLayout.LayoutParams(-1, -2))
        addView(count, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(GalleryDesign.appearance.detailCards.bodyGapDp)
        })
    }
    private var restoreCaption = false
    private var imageHeight = 0

    init {
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        defaultFocusHighlightEnabled = false
        isFocusable = true
        addView(image, FrameLayout.LayoutParams(-1, -1))
        addView(placeholder, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        addView(caption, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        for (index in 0 until childCount) getChildAt(index).importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        paint(colors)
    }

    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name

    fun paint(colors: NativeColorScheme) {
        applyStyle(NativeSurfaceStyle(colors.surfaceContainerHigh,
            rippleColor = (colors.primary and 0x00ffffff) or (40 shl 24), focusOutlineColor = colors.primary))
        // Reuse native text role/state/motion ownership; no hardcoded white or image sampling.
        val captionColors = colors.pageControls.copy(surface = colors.surfaceContainerHigh)
        title.applyPalette(captionColors)
        count.applyPalette(captionColors)
        placeholder.imageTintList = ControlStyle(context, captionColors).foregroundStates(colors.onSurfaceVariant)
    }

    fun readableRestoredOffset(offset: Int): Int {
        if (!restoreCaption || height <= 0 || offset > -height) return offset
        val visible = maxOf(minimumTargetHeight, caption.measuredHeight).coerceAtMost(height)
        return visible - height
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val side = MeasureSpec.getSize(widthMeasureSpec)
        val width = MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY)
        val budget = heightBudget()
        restoreCaption = budget != null || singleColumnLandscape
        // Measure both title lines and the count/location before allocating any media height.
        // A short viewport never switches the name to one line or shrinks its typography.
        caption.measure(width, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val preferred = when {
            budget != null -> minOf(side, budget.coerceAtLeast(0))
            singleColumnLandscape -> (side * 9f / 16f).roundToInt()
            else -> side
        }
        val minimum = maxOf(minimumTargetHeight, caption.measuredHeight + minimumTargetHeight)
        val resolved = resolveSizeAndState(maxOf(preferred, minimum), heightMeasureSpec, 0)
        val height = resolved and MEASURED_SIZE_MASK
        // A matched grid row is authoritative; an externally undersized EXACTLY constraint
        // is reported as too small, not silently advertised as readable or expanded past it.
        setMeasuredDimension(side, resolved or if (height < minimum) MEASURED_STATE_TOO_SMALL else 0)
        imageHeight = (height - caption.measuredHeight).coerceAtLeast(0)
        image.measure(width, MeasureSpec.makeMeasureSpec(imageHeight, MeasureSpec.EXACTLY))
        val placeholderSize = minOf(dp(28), side, imageHeight)
        placeholder.measure(MeasureSpec.makeMeasureSpec(placeholderSize, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(placeholderSize, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        image.layout(0, 0, width, imageHeight)
        val x = (width - placeholder.measuredWidth) / 2
        val y = (imageHeight - placeholder.measuredHeight) / 2
        placeholder.layout(x, y, x + placeholder.measuredWidth, y + placeholder.measuredHeight)
        caption.layout(0, imageHeight, width, imageHeight + caption.measuredHeight)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
