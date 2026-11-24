package smallthingz.ui.reverb

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout

/** Renderer-owned media slot. Window bars, renderer callbacks and sessions remain with the host. */
class ReverbBrowserPanelFullscreen(context: Context) : FrameLayout(context) {
    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        isFocusableInTouchMode = true
    }

    fun attach(container: FrameLayout, media: View) {
        check(parent == null && media.parent == null) { "Fullscreen content must be unparented" }
        addView(media, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        container.addView(this, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun detach(container: FrameLayout) {
        cancelBrowserPanelInput()
        removeAllViews()
        container.removeView(this)
    }
}
