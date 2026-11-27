package smallthingz.ui.reverb

import android.view.View
import android.view.ViewGroup
import smallthingz.ui.text.view.NativeMergedRowAccessibilityHost
import smallthingz.ui.text.view.NativePlainTextViewGeometry
import smallthingz.ui.text.view.NativeSourceAccessibilityNode
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.text.view.PlainTextAccessibilityWindowScope
import java.util.IdentityHashMap

/** Owns bindings on existing list Views, never a copied semantic tree or a native fallback. */
internal class ReverbIncidentListAccessibility(
    private val root: ViewGroup,
    private val cursor: PlainTextAccessibilityWindowScope,
    private val geometry: NativePlainTextViewGeometry,
    private val sourceNode: (View) -> NativeSourceAccessibilityNode,
) : AutoCloseable {
    private val leases = IdentityHashMap<View, AutoCloseable>()
    private var closed = false

    fun refresh(retiring: View? = null) {
        if (closed) return
        val present = IdentityHashMap<View, Boolean>()
        fun visit(view: View) {
            if (view === retiring || view.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) return
            if (view is ParagraphView || view is NativeMergedRowAccessibilityHost) present[view] = true
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(root)
        leases.keys.filter { !present.containsKey(it) }.forEach { leases.remove(it)?.close() }
        for (view in present.keys) {
            if (view is ParagraphView) view.isScreenReaderFocusable = !hasMergingAncestor(view)
            if (!leases.containsKey(view)) leases[view] = when (view) {
                is ParagraphView -> view.bindPlainTextAccessibility(cursor, geometry, { sourceNode(view) }) { exposed(view) }
                is NativeMergedRowAccessibilityHost -> view.mergedRowAccessibility.bindSourceGeometry(
                    geometry, sourceNode) { exposed(view) }
                else -> error("Unexpected incident accessibility binding")
            }
        }
    }

    private fun exposed(view: View): Boolean {
        if (closed || !view.isAttachedToWindow || !view.isShown) return false
        var ancestor: View? = view
        while (ancestor != null) {
            if (ancestor === root) return sourceNode(view).present
            ancestor = ancestor.parent as? View
        }
        return false
    }

    private fun hasMergingAncestor(view: View): Boolean {
        var ancestor = view.parent as? View
        while (ancestor != null) {
            if ((ancestor is NativeMergedRowAccessibilityHost && ancestor.mergesAccessibilityDescendants) ||
                androidx.core.view.ViewCompat.isScreenReaderFocusable(ancestor)) return true
            if (ancestor === root) break
            ancestor = ancestor.parent as? View
        }
        return false
    }

    override fun close() {
        if (closed) return
        closed = true
        val owned = leases.values.toList()
        leases.clear()
        var failure: Throwable? = null
        for (lease in owned) failure = smallthingz.ui.util.closePreservingPrimaryFailure(failure, lease::close)
        failure?.let { throw it }
    }
}
