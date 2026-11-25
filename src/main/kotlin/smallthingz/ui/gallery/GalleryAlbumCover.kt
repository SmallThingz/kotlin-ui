package smallthingz.ui.gallery

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeSurface
import smallthingz.ui.components.NativeSurfaceStyle
import smallthingz.ui.components.NativeText
import smallthingz.ui.controls.ControlStyle
import smallthingz.ui.input.DEFAULT_MINIMUM_TOUCH_TARGET_DP
import smallthingz.ui.text.NativeTypographyRole
import kotlin.math.roundToInt

/**
 * Retained album presentation. The caller owns images, placeholder drawable, identity and actions.
 * The complete measured caption sits on protected ink coverage; only the area above it fades out.
 */
class GalleryAlbumCover(context: Context, colors: NativeColorScheme, private val heightBudget: () -> Int?) :
    NativeSurface(context, NativeSurfaceStyle(colors.surfaceContainerHigh)) {
    /** Prefer a 16:9 cover in a readable single-column grid. A compact height budget
     * still takes precedence; the natural landscape height retains both title lines
     * and grows to fit the measured caption and minimum touch target. */
    var singleColumnLandscape: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    val placeholder = ImageView(context)
    val title = NativeText(context, colors.pageControls, GalleryDesign.textStyle(NativeTypographyRole.TITLE_MEDIUM)).apply {
        maxLines = 2; ellipsize = TextUtils.TruncateAt.END
    }
    val count = NativeText(context, colors.pageControls, GalleryDesign.textStyle(NativeTypographyRole.LABEL_SMALL)).apply {
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }
    // At the lightest point, even a white image becomes #575757. The 80%-white count
    // composites to approximately #dddddd there (>5:1); title ink has still more contrast.
    // These drawables are retained, with no image sampling or draw-time allocation.
    private val captionShade = View(context).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xa8000000.toInt(), 0xc2000000.toInt()))
    }
    private val shade = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, 0xa8000000.toInt()))
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(captionShade, LinearLayout.LayoutParams(-1, 0))
    }
    private val minimumTargetHeight = dp(DEFAULT_MINIMUM_TOUCH_TARGET_DP)
    private val caption = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPaddingRelative(dp(12), dp(12), dp(12), dp(12))
        title.setTextColor(Color.WHITE)
        count.setTextColor(0xccffffff.toInt())
        addView(title, LinearLayout.LayoutParams(-1, -2))
        addView(count, LinearLayout.LayoutParams(-1, -2))
    }

    init {
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        defaultFocusHighlightEnabled = false
        isFocusable = true
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
            }
        }
        addView(image, FrameLayout.LayoutParams(-1, -1))
        addView(placeholder, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        addView(shade, FrameLayout.LayoutParams(-1, 0, Gravity.BOTTOM))
        addView(caption, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        for (index in 0 until childCount) getChildAt(index).importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        paint(colors)
    }

    fun paint(colors: NativeColorScheme) {
        applyStyle(NativeSurfaceStyle(colors.surfaceContainerHigh,
            rippleColor = (colors.primary and 0x00ffffff) or (40 shl 24), focusOutlineColor = colors.primary))
        placeholder.imageTintList = ControlStyle(context, colors.pageControls).foregroundStates(colors.onSurfaceVariant)
    }

    fun readableRestoredOffset(offset: Int): Int {
        if (height <= 0 || height >= width || offset > -height) return offset
        val visible = maxOf(minimumTargetHeight, caption.measuredHeight).coerceAtMost(height)
        return visible - height
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val side = MeasureSpec.getSize(widthMeasureSpec)
        val width = MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY)
        val budget = heightBudget()
        // GridLayoutManager can remeasure a row to match its tallest cover.
        val landscape = singleColumnLandscape && budget == null
        val matchedRow = (budget != null || landscape) &&
            MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY
        val desired = when {
            matchedRow -> MeasureSpec.getSize(heightMeasureSpec)
            landscape -> (side * 9f / 16f).roundToInt()
            else -> minOf(side, budget ?: side)
        }
        val titleLines = if (!landscape && desired < side) 1 else 2
        if (title.maxLines != titleLines) title.maxLines = titleLines
        caption.measure(width, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        var coverHeight = desired
        if (!matchedRow) {
            val minimum = maxOf(minimumTargetHeight, caption.measuredHeight)
            if (landscape) coverHeight = maxOf(desired, minimum)
            else if (desired < side && desired < minimum) coverHeight = side
        }
        val bandHeight = caption.measuredHeight.coerceAtMost(coverHeight)
        val shadeHeight = maxOf(coverHeight / 2, bandHeight + dp(24)).coerceAtMost(coverHeight)
        // Mutate cached geometry only when it changes, invalidating the nested measure cache
        // without posting another pass. A compact cover may have no space for the upper fade.
        if (captionShade.layoutParams.height != bandHeight) {
            captionShade.layoutParams.height = bandHeight
            captionShade.forceLayout()
            shade.forceLayout()
        }
        if (shade.layoutParams.height != shadeHeight) {
            shade.layoutParams.height = shadeHeight
            shade.forceLayout()
        }
        super.onMeasure(width, MeasureSpec.makeMeasureSpec(coverHeight, MeasureSpec.EXACTLY))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
