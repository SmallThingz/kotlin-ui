package smallthingz.ui.gallery

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.view.ViewTreeObserver
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.scroll.NativeScrollIndicatorHorizontalScrollView
import smallthingz.ui.scroll.NativeScrollIndicatorStyle
import smallthingz.ui.scroll.horizontalContentGutterPx

/** Looks presentation only; selection, retained position and wrap admission stay with the caller.
 * [paintedSurface] samples the actual panel beneath this transparent viewport, including transitions.
 * This adapter owns physical bottom padding; the same gutter is reserved when content fits so a
 * wrap/overflow transition never changes its own height budget.
 */
open class GalleryLooksScrollView(
    context: Context,
    palette: ControlPalette,
    private val paintedSurface: () -> Long,
) : NativeScrollIndicatorHorizontalScrollView(context) {
    private var galleryIndicatorStyle = GalleryDesign.appearance.scrollIndicator
    private var underlayObserver: ViewTreeObserver? = null
    private val updateUnderlay = ViewTreeObserver.OnPreDrawListener {
        // Parent paint can change without invalidating this child's hardware display list.
        bindIndicatorUnderlay(paintedSurface())
        true
    }

    init {
        clipIndicatorContentToBottomPadding = true
        bindAppearance(palette)
    }

    fun bindAppearance(palette: ControlPalette,
        style: NativeScrollIndicatorStyle = GalleryDesign.appearance.scrollIndicator) {
        galleryIndicatorStyle = style
        bindIndicator(palette, style)
        // Padding follows density/style even when the palette is unchanged.
        refreshIndicatorGutter()
    }

    /** Call before an intrinsic fit probe as well as ordinary measurement. */
    fun refreshIndicatorGutter(): Int {
        val gutter = galleryIndicatorStyle.horizontalContentGutterPx(resources.displayMetrics.density)
        if (paddingBottom != gutter) setPadding(paddingLeft, paddingTop, paddingRight, gutter)
        return gutter
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        refreshIndicatorGutter()
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshIndicatorGutter()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        underlayObserver = viewTreeObserver.also { it.addOnPreDrawListener(updateUnderlay) }
    }

    override fun onDetachedFromWindow() {
        underlayObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(updateUnderlay)
        underlayObserver = null
        super.onDetachedFromWindow()
    }

    override fun draw(canvas: Canvas) {
        // Also cover detached rendering before an observer exists.
        bindIndicatorUnderlay(paintedSurface())
        super.draw(canvas)
    }
}
