package smallthingz.ui.gallery

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.view.Gravity
import android.view.View
import android.widget.Switch
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeMaterialSwitchView
import smallthingz.ui.components.NativeText
import smallthingz.ui.components.NativeTextRole
import smallthingz.ui.components.NativeToggleRow
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

/** A compact Gallery action that requests a change to the caller's bound audio state. */
class GalleryVideoAudioToggle(context: Context, private val colors: NativeColorScheme,
    onChanged: (Boolean) -> Unit,
) : AutoCloseable {
    private val title = NativeText(context, colors.pageControls, GalleryDesign.textStyle(NativeTypographyRole.BODY_MEDIUM))
        .apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val row = NativeToggleRow(context, title, null, NativeMaterialSwitchView(context, colors, enabled = false),
        inputRoot = null, geometry = NativeToggleRow.Geometry.COMPACT, onChanged = onChanged)
    val view: View get() = row
    private var closed = false

    init { row.bindToggle(checked = false, enabled = false, scheme = colors) }

    /** Refresh the label and model state without issuing a change request. */
    fun bind(label: String, checked: Boolean, enabled: Boolean) {
        if (closed) return
        if (title.text.toString() != label) title.text = label
        title.setRole(if (enabled) NativeTextRole.CONTENT else NativeTextRole.MUTED)
        row.bindToggle(checked, enabled, colors)
    }

    override fun close() {
        if (closed) return
        closed = true
        row.close()
    }
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
