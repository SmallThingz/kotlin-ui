package smallthingz.ui.injector

import android.content.Context

/** Injector names for the shared standalone progress switch and indicator factory. */
typealias InjectProgressSwitch = smallthingz.ui.components.NativeProgressSwitch

fun injectActionSpinner(context: Context, color: Int) =
    smallthingz.ui.components.nativeActionSpinner(context, color)
