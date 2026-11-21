package smallthingz.ui.components

import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import smallthingz.ui.text.view.MergedRowAccessibility
import smallthingz.ui.text.view.NativeMergedRowAccessibilityHost
import smallthingz.ui.text.view.NativeMergedRowAccessibilityState
import smallthingz.ui.text.view.NativeMergedRowRole

/** A clickable row with source-ordered children and one merged role and checked state. */
abstract class NativeControlRow(
    context: Context,
    role: NativeMergedRowRole,
    inputRoot: () -> View?,
) : NativeClickableRow(context, inputRoot), NativeMergedRowAccessibilityHost {
    protected var rowChecked: Boolean? = null
        private set
    protected open val accessibilityClickLabel: CharSequence? get() = null
    protected abstract fun accessibilityChildren(): List<View>

    protected open fun accessibilityState(): NativeMergedRowAccessibilityState =
        NativeMergedRowAccessibilityState(rowInputEnabled, rowChecked, accessibilityClickLabel,
            accessibilityChildren())

    final override val mergedRowAccessibility = MergedRowAccessibility(this, role, ::accessibilityState, {
        if (canActivate()) { activateRow(); true } else false
    })

    protected fun addRowAccessibilityRole() {
        addView(mergedRowAccessibility.roleView)
        accessibilityDelegate = mergedRowAccessibility
    }

    protected fun bindRow(enabled: Boolean, ink: Int, checked: Boolean? = null) {
        rowChecked = checked
        bindAction(enabled, ink)
    }

    protected fun measureRowRole(width: Int, height: Int) = mergedRowAccessibility.roleView.measure(
        MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))

    protected fun layoutRowRole() {
        mergedRowAccessibility.roleView.layout(0, 0, width, height)
        layoutRowIndication()
    }

    override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo? =
        if (mergedRowAccessibility.isNodePresent()) super.createAccessibilityNodeInfo() else null

    override fun addChildrenForAccessibility(outChildren: ArrayList<View>) =
        mergedRowAccessibility.addChildrenForAccessibility(outChildren)
}
