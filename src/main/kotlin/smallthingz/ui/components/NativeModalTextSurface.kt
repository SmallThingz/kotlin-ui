package smallthingz.ui.components

import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import java.util.function.LongSupplier
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.text.view.ParagraphView

/** Current opaque modal paint, including grain; never the destination palette of an animation. */
interface NativeModalPaintedSurface {
    val paintedTextSurface: Long
}

/** Modal labels follow their nearest painted underlay. Controls with their own paint keep ownership. */
internal object NativeModalTextSurface {
    // Only the shared handle opts into caller-owned flat panels; paragraph ownership stays unchanged.
    @Suppress("DEPRECATION") // ColorDrawable reports tint through its opacity.
    fun sample(view: View, forHandle: Boolean = false): Long {
        if (view.background != null) return 0L
        var parent = view.parent as? View
        while (parent != null) {
            when (parent) {
                is NativeModalPaintedSurface -> return parent.paintedTextSurface
                is NativeGroupLayout -> return parent.paintedTextSurface
                is NativeSurface -> return if (forHandle) parent.paintedTextSurface else 0L
                is NativeMaterialButtonView -> return 0L // Buttons and fields own their own foreground pairing.
            }
            val background = parent.background
            if (background is NoiseDrawable) return background.paintedTextSurface
            if (forHandle && background is ColorDrawable && background.colorFilter == null) {
                if (background.alpha == 255) return smallthingz.ui.controls.textSurfaceRange(background.color)
                if (background.alpha == 0 && background.opacity == PixelFormat.TRANSPARENT) {
                    parent = parent.parent as? View
                    continue
                }
            }
            if (background != null) return 0L
            parent = parent.parent as? View
        }
        return 0L
    }

    fun bindTree(view: View) {
        if (view is NativeSurface || view is NativeMaterialButtonView || view.background != null) return
        if (view is ParagraphView && view.paintedTextSurface == null) {
            view.bindPaintedTextSurface(LongSupplier { sample(view) })
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) bindTree(view.getChildAt(index))
    }

    fun invalidate(owner: View) {
        if (owner is NativeSurface || owner is NativeMaterialButtonView || owner.background != null) return
        when (owner) {
            is ParagraphView -> if (owner.paintedTextSurface != null) owner.invalidate()
            is NativeTextSurfaceTarget -> owner.invalidateTextSurfaceInk()
        }
        if (owner is ViewGroup) for (index in 0 until owner.childCount) invalidate(owner.getChildAt(index))
    }
}
