package smallthingz.ui.reverb

import android.content.Context
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import kotlinx.coroutines.CoroutineScope
import smallthingz.ui.components.NativeToolbarHint
import smallthingz.ui.components.NativeTopBarInsets
import smallthingz.ui.platform.AndroidUiDispatcher

/** Centered Settings title, Back or Undo at start, and Apply at end. */
class ReverbSettingsTopBar(context: Context, initialTheme: ReverbTheme, inputRoot: View? = null) :
    FrameLayout(context) {
    private val style = ReverbDesign.appearance.toolbars
    private val navigation = ReverbHeaderActionView(context, initialTheme, inputRoot)
    private val apply = ReverbHeaderActionView(context, initialTheme, inputRoot)
    private val titleView = initialTheme.paragraph(context, "", style.centeredTitleRole)
    private val bar = ReverbTitledTopBarLayout(context, initialTheme.density.density,
        initialTheme.palette, navigation, titleView, apply)
    private val hint = NativeToolbarHint(this, CoroutineScope(AndroidUiDispatcher.Main), style,
        style.contentColor(initialTheme.palette), navigation.visual, apply.visual)
    private var palette = initialTheme.palette
    private var cue: Any? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        ViewCompat.setAccessibilityHeading(titleView, true)
        addView(bar, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    fun bind(
        theme: ReverbTheme,
        title: String,
        back: ReverbHeaderAction,
        undo: ReverbHeaderAction?,
        apply: ReverbHeaderAction,
        insets: NativeTopBarInsets = NativeTopBarInsets(0, 0, 0),
    ) {
        palette = theme.palette
        bar.bindTitledToolbar(theme.density.density, palette)
        bar.setRemainingWindowInsets(insets)
        theme.bindParagraph(titleView, style.centeredTitleRole)
        titleView.setText(title)
        navigation.bind(theme, undo?.withIcon(ReverbIcon.Undo) ?: back.withIcon(ReverbIcon.Back), product = true)
        this.apply.bind(theme, apply.withIcon(ReverbIcon.Check), product = true)
        hint.bind(style, style.contentColor(palette), cue)
    }

    /** Emphasize the current Settings actions for the shared dirty-action hint interval. */
    fun showPendingCue() {
        if (!isAttachedToWindow) return
        cue = Any()
        hint.bind(style, style.contentColor(palette), cue)
    }

    fun clearPendingCue() {
        cue = null
        hint.bind(style, style.contentColor(palette), null)
    }

    override fun onDetachedFromWindow() {
        clearPendingCue()
        super.onDetachedFromWindow()
    }
}
