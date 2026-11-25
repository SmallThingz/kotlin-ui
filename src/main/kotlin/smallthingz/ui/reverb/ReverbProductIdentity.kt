package smallthingz.ui.reverb

import android.content.Context
import android.graphics.Matrix
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.DrawableRes
import smallthingz.ui.components.NativeColorScheme

/** Product artwork supported by the shared toolbar and About panel. */
sealed interface ReverbProductIdentity {
    data object Reverb : ReverbProductIdentity
    /** Original launcher artwork, including adaptive icon layers; never palette-tinted. */
    data class Launcher @JvmOverloads constructor(
        @DrawableRes val drawable: Int,
        val artworkBounds: ReverbLauncherArtworkBounds? = null,
    ) : ReverbProductIdentity
    data class Monochrome(@DrawableRes val drawable: Int) : ReverbProductIdentity
}

internal class ReverbIdentityMarkView(context: Context) : FrameLayout(context) {
    private val reverb = ReverbBrandMarkView(context)
    private val monochrome = object : ImageView(context) {
        private val artworkMatrix = Matrix()
        fun alignArtwork() {
            val bounds = (identity as? ReverbProductIdentity.Launcher)?.artworkBounds
            val art = drawable
            if (bounds == null || art == null || art.intrinsicWidth <= 0 || art.intrinsicHeight <= 0) {
                scaleType = ScaleType.FIT_CENTER
                return
            }
            val fit = launcherArtworkTransform(bounds, art.intrinsicWidth, art.intrinsicHeight, width, height)
            if (fit == null) {
                scaleType = ScaleType.FIT_CENTER
                return
            }
            scaleType = ScaleType.MATRIX
            artworkMatrix.setScale(fit.scale, fit.scale)
            artworkMatrix.postTranslate(fit.translateX, fit.translateY)
            imageMatrix = artworkMatrix
        }
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            alignArtwork()
        }
    }.apply {
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
            monochrome.visibility = if (value != ReverbProductIdentity.Reverb) VISIBLE else GONE
            monochrome.setImageResource(when (value) {
                is ReverbProductIdentity.Monochrome -> value.drawable
                is ReverbProductIdentity.Launcher -> value.drawable
                ReverbProductIdentity.Reverb -> 0
            })
        }
        reverb.refreshResourcePalette()
        monochrome.imageTintList = null
        if (value is ReverbProductIdentity.Monochrome) {
            monochrome.setColorFilter(ReverbDesign.appearance.toolbars.contentColor(palette))
        } else monochrome.clearColorFilter()
        monochrome.alignArtwork()
    }
}
