package smallthingz.ui.components

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.text.NativeTypography

private val defaultStatusText = NativeTextStyle(20f, lineHeightSp = 28f)

/** Centered framework status typography and padding. */
data class NativeCenteredStatusStyle(
    val paddingDp: Float = 32f,
    val text: NativeTextStyle = defaultStatusText,
) {
    internal constructor(typography: NativeTypography) : this(text = defaultStatusText.copy(
        fontFamily = typography.defaultTextStyle.fontFamily?.familyName ?: defaultStatusText.fontFamily,
        weight = typography.defaultTextStyle.fontWeight?.weight ?: defaultStatusText.weight,
    ))

    internal fun spacing(density: Float): NativeCenteredStatusSpacing {
        // Derive the mark and text insets after truncating the shared outer unit.
        val padding = (paddingDp * density).toInt()
        return NativeCenteredStatusSpacing(padding, padding * 2, padding / 2)
    }

    fun windowBackground(palette: NativeColorScheme) = ColorDrawable(palette.background)
}

internal data class NativeCenteredStatusSpacing(val paddingPx: Int, val markPx: Int, val textPaddingPx: Int)

/** A scrollable status panel for a caller-owned operation and brand mark. */
open class NativeCenteredStatusContent(
    context: Context,
    palette: NativeColorScheme,
    style: NativeCenteredStatusStyle,
    mark: View,
    pendingText: CharSequence,
) : LinearLayout(context) {
    private val controls = ControlPalette(palette.surface, palette.surfaceContainer, palette.onBackground,
        palette.onSurfaceVariant, palette.primary, palette.onPrimary, palette.primaryContainer, palette.outline)
    private val detail = NativeText(context, controls, style.text)
    private val progress = ProgressBar(context).apply {
        indeterminateTintList = ColorStateList.valueOf(palette.primary)
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(palette.background)
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val spacing = style.spacing(resources.displayMetrics.density)
        val padding = spacing.paddingPx
        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            setPadding(padding, padding, padding, padding)
        }
        column.addView(mark, LayoutParams(spacing.markPx, spacing.markPx))
        column.addView(detail.apply {
            text = pendingText
            gravity = Gravity.CENTER
            setPadding(0, spacing.textPaddingPx, 0, spacing.textPaddingPx)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            isAccessibilityHeading = true
        })
        column.addView(progress)
        addView(ScrollView(context).apply {
            isFillViewport = true
            addView(column, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun showCompleted(message: CharSequence) {
        detail.text = message
        // Preserve the progress slot through the completion hold.
        progress.visibility = View.INVISIBLE
    }
}
