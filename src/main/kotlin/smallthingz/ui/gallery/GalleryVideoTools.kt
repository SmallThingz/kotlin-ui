package smallthingz.ui.gallery

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.view.Gravity
import android.widget.Switch
import smallthingz.ui.components.componentTypography
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.text.NativeTypographyRole
import kotlin.math.roundToInt

/** Quiet video-tool choices; visual density never reduces the shared touch target. */
object GalleryVideoTools {
    val cropChoiceStyle = GalleryDesign.settingsChoiceStyle.copy(
        radiusDp = GalleryDesign.appearance.toolbars.action.radiusDp,
        minHeightDp = GalleryDesign.appearance.toolbars.action.minimumTargetDp,
        visualMinHeightDp = 40f,
        verticalPaddingDp = 4f,
        outlined = false,
        selectedOutlined = false,
        unselectedOutlined = false,
        textStyle = GalleryDesign.textStyle(NativeTypographyRole.LABEL_MEDIUM),
        selectedTextStyle = GalleryDesign.textStyle(NativeTypographyRole.LABEL_MEDIUM),
    )
}

/** Keeps platform Switch input/focus semantics while using Gallery's typography and palette. */
class GalleryVideoAudioSwitch(context: Context, palette: ControlPalette) : Switch(context) {
    init {
        showText = false
        splitTrack = false
        gravity = Gravity.CENTER_VERTICAL
        maxLines = Int.MAX_VALUE
        ellipsize = null
        setTextColor(ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(palette.muted, palette.content),
        ))
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        trackTintList = ColorStateList(states, intArrayOf(palette.accent, palette.raised))
        thumbTintList = ColorStateList(states, intArrayOf(palette.onAccent, palette.muted))
        bindMetrics()
    }

    private fun bindMetrics() {
        val density = resources.displayMetrics.density
        minimumHeight = (GalleryDesign.appearance.toolbars.action.minimumTargetDp * density).roundToInt()
        switchPadding = (12 * density).roundToInt()
        setPaddingRelative((12 * density).roundToInt(), (4 * density).roundToInt(),
            (12 * density).roundToInt(), (4 * density).roundToInt())
        componentTypography(GalleryDesign.textStyle(NativeTypographyRole.BODY_MEDIUM))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        bindMetrics()
    }
}
