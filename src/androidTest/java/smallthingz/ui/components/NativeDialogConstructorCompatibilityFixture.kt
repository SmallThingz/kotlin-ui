package smallthingz.ui.components

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View

/** Compile-only client coverage: these functions are never invoked or used to open windows. */
@Suppress("unused")
private fun originalFloatingDialogCalls(
    anchor: View,
    density: Float,
    onDismiss: () -> Unit,
    createContent: (Context, NativeModalWindowRuntime, () -> Unit) -> NativeFloatingDialogContent,
    protection: NativeStatusBarProtection,
) {
    NativeFloatingDialog(anchor, density, onDismiss, createContent)
    NativeFloatingDialog(anchor, density, onDismiss, { context, runtime, dismiss ->
        createContent(context, runtime, dismiss)
    })
    NativeFloatingDialog(anchor, density, onDismiss) { context, runtime, dismiss ->
        createContent(context, runtime, dismiss)
    }
    NativeFloatingDialog(
        anchor = anchor,
        density = density,
        onDismiss = onDismiss,
        createContent = createContent,
    )
    NativeFloatingDialog(anchor, density, onDismiss, protection, createContent)
}

@Suppress("unused")
private fun originalActionSheetCalls(
    anchor: View,
    style: NativeActionSheetStyle,
    onDismiss: () -> Unit,
    closeIcon: NativeActionSheetIcon,
    background: Drawable,
    protection: NativeStatusBarProtection,
) {
    NativeActionSheet(anchor, style, "Title", onDismiss, closeIcon)
    NativeActionSheet(anchor, style, "Title", onDismiss, closeIcon, background)
    NativeActionSheet(
        anchor = anchor,
        initialStyle = style,
        title = "Title",
        onDismiss = onDismiss,
        closeIcon = closeIcon,
        contentBackground = background,
    )
    NativeActionSheet(anchor, style, "Title", onDismiss, closeIcon, background, protection)
}
