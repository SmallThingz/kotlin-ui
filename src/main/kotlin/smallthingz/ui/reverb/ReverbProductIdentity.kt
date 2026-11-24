package smallthingz.ui.reverb

import android.content.Context
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.DrawableRes
import smallthingz.ui.components.NativeColorScheme

/** Product artwork supported by the shared toolbar and About panel. */
sealed interface ReverbProductIdentity {
    data object Reverb : ReverbProductIdentity
    data class Monochrome(@DrawableRes val drawable: Int) : ReverbProductIdentity
}

internal class ReverbIdentityMarkView(context: Context) : FrameLayout(context) {
    private val reverb = ReverbBrandMarkView(context)
    private val monochrome = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        visibility = GONE
    }
    private var identity: ReverbProductIdentity = ReverbProductIdentity.Reverb

    init {
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        reverb.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        monochrome.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(reverb, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(monochrome, LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    override fun getAccessibilityClassName(): CharSequence = "android.widget.ImageView"

    fun bind(value: ReverbProductIdentity, palette: NativeColorScheme) {
        if (value != identity) {
            identity = value
            reverb.visibility = if (value == ReverbProductIdentity.Reverb) VISIBLE else GONE
            monochrome.visibility = if (value is ReverbProductIdentity.Monochrome) VISIBLE else GONE
            monochrome.setImageResource((value as? ReverbProductIdentity.Monochrome)?.drawable ?: 0)
        }
        reverb.refreshResourcePalette()
        monochrome.setColorFilter(ReverbDesign.appearance.toolbars.contentColor(palette))
    }
}
