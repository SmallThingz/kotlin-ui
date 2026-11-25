package smallthingz.ui.reverb

import android.content.Context
import android.view.View
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeThemedToolbarAction
import smallthingz.ui.controls.textSurfaceForeground

/** Compatibility adapter; shared toolbar paint, motion and input live in the common control. */
open class ReverbToolbarActionView(
    context: Context,
    density: Float,
    initialPalette: NativeColorScheme,
    inputRoot: () -> View?,
    label: String,
    selectionWrapper: Boolean = false,
    sharedSurface: Boolean = false,
    rolePresent: Boolean = false,
    onPaintedSurfaceChanged: (View) -> Unit = ReverbTextSurface::invalidate,
    foreground: (View, Int) -> Int = { view, tint -> textSurfaceForeground(ReverbTextSurface.sample(view), tint) },
) : NativeThemedToolbarAction(context, density, initialPalette, inputRoot, label, selectionWrapper,
    sharedSurface, rolePresent, onPaintedSurfaceChanged, foreground, ReverbDesign.appearance.toolbars),
    ReverbTextSurfaceTarget
