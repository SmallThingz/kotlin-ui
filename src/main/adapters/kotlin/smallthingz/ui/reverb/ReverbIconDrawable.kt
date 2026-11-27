package smallthingz.ui.reverb

import smallthingz.ui.components.CachedPathIconDrawable

/** Library-owned Reverb semantic adapter; the shared renderer retains the exact mask/cache/tint contract. */
class ReverbIconDrawable(val icon: ReverbIcon, density: Float, tint: Int) :
    CachedPathIconDrawable({ icon.mask }, density, tint, icon.autoMirrored)
