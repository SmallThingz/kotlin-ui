package smallthingz.ui.alldoc

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.view.KeyEvent
import android.view.View
import smallthingz.ui.common.Button
import smallthingz.ui.components.NativeButtonColorRole
import smallthingz.ui.components.NativeButtonPalette
import smallthingz.ui.components.NativeButtonRecipe
import smallthingz.ui.components.NativeUiAppearance
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.graphics.ViewGrain
import smallthingz.ui.graphics.bindGrainLayer
import smallthingz.ui.interaction.view.isNativeClickActivationKey
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density

/** Window-scoped Home actions. Content and admission belong to the caller; recipes live here. */
class AllDocHomeActions(private val context: Context) {
    private val density = Density(context)
    private val resolver = createFontFamilyResolver(context)
    private val buttons = NativeUiAppearance.Default.buttons
    private val retryRecipe = buttons.neutralText.copy(
        surface = buttons.neutralText.surface.copy(minimumWidthDp = 48, minimumHeightDp = 48),
    )
    private val headerRecipe = buttons.raisedIcon.copy(
        // Preserve AllDoc's existing targets and joined-action shape.
        surface = buttons.raisedIcon.surface.copy(verticalPaddingDp = 8, radiusDp = null),
    )
    private val licensesRecipe = headerRecipe.copy(
        enabledPaint = headerRecipe.enabledPaint.copy(container = NativeButtonColorRole.QUIET_SURFACE),
        disabledPaint = headerRecipe.disabledPaint.copy(container = NativeButtonColorRole.QUIET_SURFACE),
    )
    private val identityRecipe = headerRecipe.copy(
        surface = headerRecipe.surface.copy(iconSizeDp = 48, iconGapDp = 0),
    )

    fun retry(label: String, palette: ControlPalette, action: () -> Unit): Button =
        createAction(retryRecipe, action).also { it.bind(label, true, palette.buttons()) }

    fun bindRetry(button: Button, palette: ControlPalette, enabled: Boolean = button.isEnabled) {
        button.bind(button.label.getText(), enabled, palette.buttons())
    }

    internal fun header(label: String, licenses: Boolean = false, identity: Boolean = false,
                        action: () -> Unit): Button =
        createAction(when { identity -> identityRecipe; licenses -> licensesRecipe; else -> headerRecipe }, action).apply {
            contentDescription = label
            tooltipText = label
        }

    internal fun bindHeader(button: Button, palette: ControlPalette, enabled: Boolean) {
        button.bind("", enabled, palette.buttons())
    }

    private fun createAction(recipe: NativeButtonRecipe, callback: () -> Unit): Button =
        Button(context, recipe, density, resolver, onClick = callback).apply {
            // A repeat of a retired activation must not arm a newly bound command.
            setOnKeyListener { _, key, event ->
                if (!isNativeClickActivationKey(key)) return@setOnKeyListener false
                when {
                    event.action == KeyEvent.ACTION_UP && event.isCanceled -> { cancelInput(); true }
                    event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0 -> true
                    else -> false
                }
            }
        }

    private fun ControlPalette.buttons() = NativeButtonPalette(
        accent = accent, onAccent = onAccent, content = content, muted = muted,
        quietSurface = raised, tonalSurface = selection, onTonal = content,
        errorSurface = raised, onError = content, surfacePreset = surfacePreset,
    )
}

/** Shared cached foreground grain for Home's recycler-owned document enclosures. */
class AllDocHomeGroupGrain {
    private var intensity = 0f
    private var owner: View? = null
    private var grain: ViewGrain? = null

    fun bind(palette: ControlPalette?) {
        val next = palette?.surfacePreset?.foregroundGrain ?: 0f
        if (intensity == next) return
        intensity = next
        grain?.setIntensity(next)
    }

    /** The recycler retains its own fill, bounds, rounded geometry and outline. */
    fun draw(canvas: Canvas, view: View, bounds: RectF, cornerRadius: Float, fillColor: Int) {
        if (intensity == 0f || fillColor ushr 24 == 0) return
        if (owner !== view) {
            owner = view
            grain = null
        }
        val layer = grain ?: view.bindGrainLayer(null, intensity).also { grain = it }
        layer?.draw(canvas, bounds.left, bounds.top, bounds.right, bounds.bottom,
            cornerRadius, fillColor ushr 24)
    }
}
