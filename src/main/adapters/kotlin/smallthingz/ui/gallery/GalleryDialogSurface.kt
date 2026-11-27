package smallthingz.ui.gallery

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import smallthingz.ui.animation.view.ViewColorTransition
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeSurfaceBorderDrawable
import smallthingz.ui.components.acceptsNativeInput
import smallthingz.ui.components.modalEdgeWidthDp
import smallthingz.ui.components.nativeTextStyle
import kotlin.math.min
import smallthingz.ui.reverb.ReverbDesign

/** Width includes dialog padding; both policies retain the host's available-width cap. */
enum class GalleryDialogWidthPolicy {
    Natural,
    FillAvailable;

    fun measureSpec(parentWidthSpec: Int, maximumWidthPx: Int): Int {
        require(maximumWidthPx >= 0)
        val width = if (MeasureSpec.getMode(parentWidthSpec) == MeasureSpec.UNSPECIFIED) maximumWidthPx
            else minOf(maximumWidthPx, MeasureSpec.getSize(parentWidthSpec))
        return MeasureSpec.makeMeasureSpec(width,
            if (this == FillAvailable) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST)
    }
}

/**
 * Gallery's centered dialog content host. Ordinary FrameLayout child operations remain unchanged;
 * the private painter resolves fill, outline and grain only from the selected window theme.
 * Framework background setters cannot replace this theme-owned material; background remains null.
 * A caller foreground is a content overlay, with the themed outline painted above it.
 * NativeSurface upcasts and raw applyStyle/noise overrides are intentionally no longer supported.
 */
class GalleryDialogSurface(context: Context, dark: Boolean) : FrameLayout(context) {
    private var paintOwner: DialogPaint? = null

    /** Read-only samples include the currently animated fill and active grain coverage. */
    val paintedFillColor: Int get() = checkNotNull(paintOwner).paintedFillColor
    val paintedTextSurface: Long get() = checkNotNull(paintOwner).paintedTextSurface

    init {
        val palette = ReverbDesign.appearance.resolveColorScheme(context, dark)
        paintOwner = DialogPaint(this, palette)
        setWillNotDraw(false)
    }

    fun bindTheme(dark: Boolean) {
        val palette = ReverbDesign.appearance.resolveColorScheme(context, dark)
        checkNotNull(paintOwner).bind(palette)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        paintOwner?.updateGeometry()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        paintOwner?.updateGeometry()
    }

    @Suppress("DEPRECATION")
    override fun setBackgroundDrawable(background: Drawable?) {
        // All framework background setters funnel here, including constructor/XML assignment.
        // Keep the private drawable out of the public background/tint/mutation API.
        super.setBackgroundDrawable(null)
        setWillNotDraw(false)
    }

    override fun verifyDrawable(who: Drawable): Boolean =
        paintOwner?.owns(who) == true || super.verifyDrawable(who)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paintOwner?.drawBackground(canvas)
    }

    override fun onDrawForeground(canvas: Canvas) {
        super.onDrawForeground(canvas)
        paintOwner?.drawOutline(canvas)
    }

    override fun performClick(): Boolean = acceptsNativeInput() && super.performClick()

    /** One shared background material and centered outline; neither escapes through View paint APIs. */
    private class DialogPaint(
        private val host: View,
        private var palette: NativeColorScheme,
    ) {
        private val backdrops = ReverbDesign.appearance.backdrops
        private val fill = backdrops.windowBackground(palette)
        private val border = NativeSurfaceBorderDrawable()
        private val corners = FloatArray(8)
        private val clip = Path()
        private val bounds = RectF()
        private var radius = 0f
        private var boundDensity = Float.NaN
        private val colors = ViewColorTransition(host,
            intArrayOf(palette.background, palette.border), ::publishColors)

        val paintedFillColor: Int get() = colors.color(0)
        val paintedTextSurface: Long get() = fill.paintedTextSurface

        init {
            fill.callback = host
            border.callback = host
            host.clipToOutline = true
            host.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    if (view.width <= 0 || view.height <= 0) outline.setEmpty()
                    else outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            if (Build.VERSION.SDK_INT >= 29) host.isForceDarkAllowed = false
            updateGeometry()
        }

        fun owns(drawable: Drawable): Boolean = drawable === fill || drawable === border

        fun bind(next: NativeColorScheme) {
            if (palette == next && boundDensity == host.resources.displayMetrics.density) return
            palette = next
            // Drawable invalidation can synchronously reenter through the attached parent.
            // Bind the shared grain policy with the sampled fill, never a transient target.
            val sampledFill = colors.color(0)
            backdrops.bindWindowBackground(fill,
                if (next.background == sampledFill) next else next.copy(background = sampledFill))
            updateGeometry()
            colors.retarget(next.background, next.border)
            publishColors()
        }

        fun updateGeometry() {
            val density = host.resources.displayMetrics.density
            boundDensity = density
            radius = min(modals.sheet.radiusDp * density,
                min(host.width, host.height).coerceAtLeast(0) / 2f)
            corners.fill(radius)
            bounds.set(0f, 0f, host.width.toFloat(), host.height.toFloat())
            clip.rewind()
            clip.addRoundRect(bounds, radius, radius, Path.Direction.CW)
            fill.setBounds(0, 0, host.width, host.height)
            border.setBounds(0, 0, host.width, host.height)
            border.applyGeometry(colors.color(1), palette.modalEdgeWidthDp() * density, corners)
            host.invalidateOutline()
            host.invalidate()
        }

        fun drawBackground(canvas: Canvas) {
            val checkpoint = canvas.save()
            try {
                // Like View's background, paint stays at the viewport origin when content scrolls.
                canvas.translate(host.scrollX.toFloat(), host.scrollY.toFloat())
                canvas.clipPath(clip)
                fill.draw(canvas)
            } finally { canvas.restoreToCount(checkpoint) }
        }

        fun drawOutline(canvas: Canvas) {
            val checkpoint = canvas.save()
            try {
                canvas.translate(host.scrollX.toFloat(), host.scrollY.toFloat())
                border.draw(canvas)
            } finally { canvas.restoreToCount(checkpoint) }
        }

        private fun publishColors() {
            fill.setBackgroundColor(colors.color(0))
            border.setColor(colors.color(1))
            host.invalidate()
        }
    }

    companion object {
        private val modals = ReverbDesign.appearance.modals
        val titleStyle = ReverbDesign.appearance.typography.nativeTextStyle(modals.floating.headlineRole)
        val horizontalPaddingDp: Int = modals.sheet.horizontalPaddingDp.toInt()
        val verticalPaddingDp: Int = modals.sheet.bottomPaddingDp.toInt()
        val bodyGapDp: Int = modals.floating.headingGapDp.toInt()
        // Short date wheels and IME-resized inputs retain their actual content height.
        const val compactVerticalPaddingDp: Int = 4

    }
}
