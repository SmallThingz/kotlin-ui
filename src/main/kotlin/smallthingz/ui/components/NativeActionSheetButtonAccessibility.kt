package smallthingz.ui.components

import android.view.View
import smallthingz.ui.input.DEFAULT_MINIMUM_TOUCH_TARGET_DP

/**
 * Bind a button using this sheet's input root to the same clipped semantic tree.
 * Explicit-root buttons do not use standalone accessibility. Keep this lease for the
 * button's lifetime and close it when retiring the content, before closing the button.
 */
fun NativeActionSheetContentContext.bindButtonAccessibility(button: NativeMaterialButtonView): AutoCloseable {
    fun node(view: View) = accessibility.sourceNode(view,
        if (view === button) DEFAULT_MINIMUM_TOUCH_TARGET_DP * style.textDensity.density else 0f)
    val role = button.mergedRowAccessibility.bindSourceGeometry(accessibility.geometry, ::node) {
        canAcceptAction() && node(button).present
    }
    val text = try {
        button.label.bindPlainTextAccessibility(accessibility.cursor, accessibility.geometry,
            sourceNode = { node(button.label) }) { canAcceptAction() && node(button.label).present }
    } catch (failure: Throwable) {
        try { role.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
        throw failure
    }
    return AutoCloseable {
        try { text.close() } finally { role.close() }
    }
}
