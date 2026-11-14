package smallthingz.ui.components

import android.view.View

/** Caller-owned content and semantic lifetime; the dialog owns only its window and motion. */
class NativeFloatingDialogContent(
    val view: View,
    val predictiveMotionTarget: View = view,
    val bindWindowAccessibility: (View) -> Unit,
    val dispose: () -> Unit,
)
