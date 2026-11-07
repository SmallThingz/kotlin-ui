package smallthingz.ui.components

import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper

/**
 * Centers a button's visible container inside its unchanged interaction bounds.
 *
 * [setVisualHeight] receives the content-aware height from the owning button. Padding,
 * intrinsic dimensions, state, tint and callbacks remain those of the wrapped drawable;
 * the extra interaction space must never become text padding.
 */
internal class NativeButtonContainerDrawable(child: Drawable) : DrawableWrapper(child) {
    private var visualHeightPx = Int.MAX_VALUE
    private var hasExplicitHotspotBounds = false
    private val requestedHotspotBounds = Rect()
    private val visualHotspotBounds = Rect()
    private var initialized = false

    init {
        initialized = true
        updateChildBounds()
    }

    override fun setDrawable(drawable: Drawable?) {
        super.setDrawable(drawable)
        // DrawableWrapper invokes this from its constructor, before these fields
        // exist. Later replacements must immediately inherit the visual geometry.
        if (initialized) updateChildBounds()
    }

    fun setVisualHeight(heightPx: Int) {
        require(heightPx >= 0) { "Visual height must be nonnegative" }
        if (visualHeightPx == heightPx) return
        visualHeightPx = heightPx
        updateChildBounds()
        invalidateSelf()
    }

    override fun onBoundsChange(bounds: Rect) {
        updateChildBounds()
    }

    private fun updateChildBounds() {
        val child = drawable ?: return
        val availableHeight = bounds.height().coerceAtLeast(0)
        val height = visualHeightPx.coerceAtMost(availableHeight)
        val top = bounds.top + (availableHeight - height) / 2
        child.setBounds(bounds.left, top, bounds.right.coerceAtLeast(bounds.left), top + height)
        updateHotspotBounds()
    }

    override fun draw(canvas: Canvas) {
        val child = drawable ?: return
        val saveCount = canvas.save()
        try {
            // A ripple triggered from the surrounding touch strip still stays in the
            // visible container. Its own mask retains the rounded container shape.
            canvas.clipRect(child.bounds)
            child.draw(canvas)
        } finally {
            canvas.restoreToCount(saveCount)
        }
    }

    override fun getPadding(padding: Rect): Boolean {
        val child = drawable
        if (child != null) return child.getPadding(padding)
        padding.setEmpty()
        return false
    }

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int {
        val child = drawable ?: return PixelFormat.TRANSPARENT
        if (bounds.isEmpty || child.bounds.isEmpty) return PixelFormat.TRANSPARENT
        val opacity = child.opacity
        if (opacity == PixelFormat.TRANSPARENT) return opacity
        // View may cache background opacity. A later visual-height reduction can
        // expose transparent strips, even when this particular size fills them.
        return PixelFormat.TRANSLUCENT
    }

    override fun setHotspotBounds(left: Int, top: Int, right: Int, bottom: Int) {
        requestedHotspotBounds.set(left, top, right, bottom)
        hasExplicitHotspotBounds = true
        updateHotspotBounds()
    }

    private fun updateHotspotBounds() {
        val child = drawable ?: return
        val visible = child.bounds
        if (hasExplicitHotspotBounds) {
            // Clamp each edge so even a disjoint requested region becomes an empty
            // region on the visual edge, rather than reverting to full touch bounds.
            val left = requestedHotspotBounds.left.coerceIn(visible.left, visible.right)
            val top = requestedHotspotBounds.top.coerceIn(visible.top, visible.bottom)
            visualHotspotBounds.set(
                left,
                top,
                requestedHotspotBounds.right.coerceIn(left, visible.right),
                requestedHotspotBounds.bottom.coerceIn(top, visible.bottom),
            )
        } else {
            visualHotspotBounds.set(visible)
        }
        child.setHotspotBounds(
            visualHotspotBounds.left,
            visualHotspotBounds.top,
            visualHotspotBounds.right,
            visualHotspotBounds.bottom,
        )
    }
}
