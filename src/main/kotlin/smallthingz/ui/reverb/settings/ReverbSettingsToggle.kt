package smallthingz.ui.reverb.settings

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import smallthingz.ui.common.ToggleRow
import smallthingz.ui.common.ToggleState
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.reverb.ReverbGlyphView
import smallthingz.ui.reverb.ReverbIcon
import smallthingz.ui.reverb.ReverbTextSurface
import smallthingz.ui.reverb.ReverbTheme
import kotlin.math.roundToInt

enum class ReverbSettingsToggleRole { AUTO_START, OUTPUT_COMPRESSION, SAVE_NOTIFICATION, SILENCE_DETECTION }

class ReverbSettingsToggle private constructor(
    context: Context,
    theme: ReverbTheme,
    initial: ToggleState,
    inputRoot: (() -> View?)?,
    muteDisabledLabel: Boolean,
    private val icon: ReverbIcon?,
    onChanged: (Boolean) -> Unit,
) : AutoCloseable {
    /** A standard Settings switch with caller-supplied copy, state, and change handling. */
    constructor(
        context: Context,
        theme: ReverbTheme,
        initial: ToggleState,
        inputRoot: (() -> View?)? = null,
        onChanged: (Boolean) -> Unit,
    ) : this(context, theme, initial, inputRoot, true, null, onChanged)

    constructor(
        context: Context,
        theme: ReverbTheme,
        initial: ToggleState,
        role: ReverbSettingsToggleRole,
        inputRoot: () -> View?,
        onChanged: (Boolean) -> Unit,
    ) : this(context, theme, initial, inputRoot, when (role) {
        ReverbSettingsToggleRole.AUTO_START, ReverbSettingsToggleRole.OUTPUT_COMPRESSION -> true
        ReverbSettingsToggleRole.SAVE_NOTIFICATION, ReverbSettingsToggleRole.SILENCE_DETECTION -> false
    }, when (role) {
        ReverbSettingsToggleRole.AUTO_START -> ReverbIcon.Reset
        ReverbSettingsToggleRole.OUTPUT_COMPRESSION -> ReverbIcon.AudioFile
        ReverbSettingsToggleRole.SAVE_NOTIFICATION -> ReverbIcon.Save
        ReverbSettingsToggleRole.SILENCE_DETECTION -> ReverbIcon.Capture
    }, onChanged)

    private val glyph = icon?.let {
        ReverbGlyphView(context, it,
            if (initial.enabled) theme.controls.colors.ink else theme.controls.colors.muted).apply {
            useTextSurfaceContrast = true
        }
    }
    private val row = ToggleRow(context, initial, theme.controls, inputRoot,
        muteDisabledLabel = muteDisabledLabel, paintedSurface = ReverbTextSurface::sample,
        onChanged = onChanged).apply { glyph?.let(::setLeadingIcon) }
    val view: View get() = row.view
    private var closed = false
    private var appIcon: ImageView? = null

    /** Installed-app identity is decorative; actions belong to the retained row. */
    fun bindAppIcon(drawable: Drawable?) {
        if (closed) return
        if (drawable == null) {
            appIcon?.setImageDrawable(null)
            row.setLeadingIcon(glyph)
        } else {
            val image = appIcon ?: ImageView(view.context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                isFocusable = false
            }.also { appIcon = it }
            image.setImageDrawable(drawable)
            row.setLeadingIcon(image)
        }
    }

    fun bind(next: ToggleState, theme: ReverbTheme) {
        if (closed) return
        row.bind(next, theme.controls)
        if (icon != null) glyph?.bind(icon, if (next.enabled) theme.controls.colors.ink else theme.controls.colors.muted)
    }

    /** Optional secondary disclosure, sharing the row's native pointer and semantic owner. */
    fun bindDetails(maxLines: Int, actionLabel: String, onDetails: (() -> Boolean)?) {
        if (!closed) row.bindDetails(maxLines, actionLabel, onDetails)
    }
    override fun close() {
        closed = true
        appIcon?.setImageDrawable(null)
        row.close()
    }
}

/** Auto-start's standalone Settings surface and its row. */
class ReverbAutoStartSettings(
    context: Context,
    theme: ReverbTheme,
    initial: ToggleState,
    inputRoot: () -> View?,
    onChanged: (Boolean) -> Unit,
) : AutoCloseable {
    private val recipe = ReverbDesign.appearance.groupSurfaces.settingsGroup
    private val group = recipe.create(context, ReverbTextSurface::invalidate)
    private val toggle = ReverbSettingsToggle(context, theme, initial, ReverbSettingsToggleRole.AUTO_START,
        inputRoot, onChanged)
    val view: View = FrameLayout(context).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(group, FrameLayout.LayoutParams(-1, -2))
    }

    init {
        group.addRow(toggle.view, NativeGroupLayout.LayoutParams(-1, -2))
        bind(initial, theme)
    }

    fun bind(state: ToggleState, theme: ReverbTheme) {
        val horizontal = (16 * theme.density.density).roundToInt()
        val top = (12 * theme.density.density).roundToInt()
        val totalVertical = (24 * theme.density.density).roundToInt()
        view.setPadding(horizontal, top, horizontal, totalVertical - top)
        recipe.bind(group, theme.palette)
        toggle.bind(state, theme)
    }

    override fun close() = toggle.close()
}
