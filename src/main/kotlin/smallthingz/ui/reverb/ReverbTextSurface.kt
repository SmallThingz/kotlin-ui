package smallthingz.ui.reverb

import android.view.View
import android.view.ViewGroup
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.text.view.ParagraphView

/** Marks a library settings composition whose text follows the actually painted surface. */
interface ReverbTextSurfaceScope

/** A retained renderer whose ink depends on its ancestors' current paint. */
interface ReverbTextSurfaceTarget {
    fun invalidateTextSurfaceInk()
}

/** Current paint only: no target-palette approximation, retained ancestry, or frame loop. */
object ReverbTextSurface {
    /** Group creation opt-in; a generic paint callback is not proof of ink ownership. */
    fun ownGroupInk(group: NativeGroupLayout): NativeGroupLayout = group.apply {
        setTextSurfaceInkInvalidator(::invalidate)
    }

    fun sample(view: View): Long {
        val ownBackground = view.background
        if (ownBackground != null && ownBackground.alpha != 0) return 0L
        var parent = view.parent as? View
        var settings = false
        var surface = 0L
        var found = false
        while (parent != null) {
            if (parent is ReverbTextSurfaceScope) settings = true
            if (!found) {
                if (parent is NativeGroupLayout) {
                    surface = parent.paintedTextSurface
                    found = true
                } else {
                    val background = parent.background
                    if (background is NoiseDrawable) {
                        surface = background.paintedTextSurface
                        found = true
                    } else if (background != null) return 0L
                }
            }
            if (settings && found) return surface
            parent = parent.parent as? View
        }
        return 0L
    }

    fun invalidate(owner: View) {
        if (!owner.isAttachedToWindow) return
        var parent: View? = owner
        while (parent != null) {
            if (parent is ReverbTextSurfaceScope) {
                invalidateTree(owner, owner)
                return
            }
            parent = parent.parent as? View
        }
    }

    private fun invalidateTree(view: View, owner: View) {
        if (!view.isAttachedToWindow) return
        if (view !== owner && view is NativeGroupLayout && view.hasOwnTextSurfaceInkInvalidator &&
            view.alpha == 1f) {
            val surface = view.paintedTextSurface
            // Its own callback owns this opaque underlay. Unknown, translucent and progress
            // surfaces still traverse; never prune the origin of an actual paint change.
            if ((surface ushr 56) == 255L && ((surface ushr 24) and 255L) == 255L) return
        }
        when (view) {
            is ParagraphView -> if (view.paintedTextSurface != null) view.invalidate()
            is ReverbGlyphView -> if (view.useTextSurfaceContrast) view.invalidate()
            is ReverbTextSurfaceTarget -> view.invalidateTextSurfaceInk()
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            invalidateTree(view.getChildAt(index), owner)
        }
    }
}
