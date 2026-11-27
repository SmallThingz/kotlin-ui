package smallthingz.ui.reverb

import android.view.View
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.components.NativeTextSurface
import smallthingz.ui.components.NativeTextSurfaceScope
import smallthingz.ui.components.NativeTextSurfaceTarget

/** Compatibility marker for settings compositions using the common current-paint sampler. */
interface ReverbTextSurfaceScope : NativeTextSurfaceScope

/** Retains the Reverb JVM interface while sharing the common ink invalidation contract. */
interface ReverbTextSurfaceTarget : NativeTextSurfaceTarget {
    override fun invalidateTextSurfaceInk()
}

/** Compatibility entry points; ownership and traversal live in the common components. */
object ReverbTextSurface {
    fun ownGroupInk(group: NativeGroupLayout): NativeGroupLayout = NativeTextSurface.ownGroupInk(group)
    fun sample(view: View): Long = NativeTextSurface.sample(view)
    fun invalidate(owner: View) = NativeTextSurface.invalidate(owner)
}
