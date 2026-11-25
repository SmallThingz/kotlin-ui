package smallthingz.ui.controls

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import smallthingz.ui.R
import smallthingz.ui.components.NativeButtonPalette
import smallthingz.ui.components.NativeButtonRecipe
import kotlin.math.roundToInt

/** Semantic colors supplied by the app's current theme, including contrast on its accent. */
data class ControlPalette(
    val surface: Int, val raised: Int, val content: Int, val muted: Int,
    val accent: Int, val onAccent: Int, val selection: Int, val outline: Int,
    internal val surfacePreset: smallthingz.ui.components.NativeSurfacePreset? = null,
)

/** Paint for actual Android Views, without a second UI tree or a global theme registry. */
class ControlStyle(context: Context, val palette: ControlPalette) {
    private val density = context.resources.displayMetrics.density
    private val selectedInk = selectionForeground(palette.selection, palette.surface, palette.onAccent, palette.content)
    private val contentColors = makeForeground(palette.content, selectedInk, disabledForeground())
    private val accentColors = makeForeground(palette.accent, selectedInk, disabledForeground())
    private val filledColors = makeForeground(palette.onAccent, palette.onAccent, disabledForeground())

    fun dp(value: Int): Int = (value * density).roundToInt()

    fun shape(color: Int, radius: Int = 12, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(1).coerceAtLeast(1), it) }
    }

    fun foregroundStates(normal: Int = palette.content, selected: Int = selectedInk,
                         disabled: Int = disabledForeground()): ColorStateList = when {
        disabled != disabledForeground() -> makeForeground(normal, selected, disabled)
        normal == palette.content && selected == selectedInk -> contentColors
        normal == palette.accent && selected == selectedInk -> accentColors
        normal == palette.onAccent && selected == palette.onAccent -> filledColors
        else -> makeForeground(normal, selected, disabled)
    }

    /** Canonical feedback colors for retained platform ripples, including selected-state contrast. */
    fun rippleColors(filled: Boolean = false): ColorStateList {
        val selected = if (filled) palette.accent else palette.selection
        val preferredRipple = if (filled) palette.onAccent else palette.accent
        val selectedRipple = selectionIndication(selected, palette.surface,
            if (filled) palette.onAccent else selectedInk, preferredRipple)
        return ColorStateList(arrayOf(SELECTED, DEFAULT),
            intArrayOf(withAlpha(selectedRipple, 40), withAlpha(preferredRipple, 40)))
    }

    /** Each view gets independent mutable drawables; only immutable color lists are reused. */
    fun background(filled: Boolean = false, outlined: Boolean = false, radius: Int = 12): RippleDrawable {
        val normal = if (filled) palette.accent else Color.TRANSPARENT
        val selected = if (filled) palette.accent else palette.selection
        val focusStroke = if (filled) palette.onAccent else palette.accent
        fun stateShape(color: Int, stroke: Int? = null, focused: Boolean = false): android.graphics.drawable.Drawable {
            val base = shape(color, radius, stroke).apply {
                if (focused && stroke != null) setStroke(dp(2).coerceAtLeast(1), stroke)
            }
            val intensity = palette.surfacePreset?.foregroundGrain ?: 0f
            if (intensity == 0f || color ushr 24 == 0) return base
            val noise = smallthingz.ui.graphics.NoiseDrawable().apply {
                val alpha = ((intensity * 255f).roundToInt() * (color ushr 24) + 127) / 255
                setNoiseIntensity(alpha / 255f)
            }
            val overlay = object : android.graphics.drawable.DrawableWrapper(noise) {
                private val clip = android.graphics.Path()
                private val radiusPx = dp(radius).toFloat()

                override fun onBoundsChange(bounds: android.graphics.Rect) {
                    super.onBoundsChange(bounds)
                    clip.rewind()
                    clip.addRoundRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(),
                        bounds.bottom.toFloat(), radiusPx, radiusPx, android.graphics.Path.Direction.CW)
                }

                override fun draw(canvas: android.graphics.Canvas) {
                    val checkpoint = canvas.save()
                    try {
                        canvas.clipPath(clip)
                        // Keep Drawable alpha/filter composition in NoiseDrawable; the ordinary
                        // opaque, unfiltered draw still needs no offscreen layer.
                        super.draw(canvas)
                    } finally { canvas.restoreToCount(checkpoint) }
                }
            }
            val border = shape(Color.TRANSPARENT, radius, stroke).apply {
                if (focused && stroke != null) setStroke(dp(2).coerceAtLeast(1), stroke)
            }
            return android.graphics.drawable.LayerDrawable(arrayOf(shape(color, radius), overlay, border))
        }
        val states = StateListDrawable().apply {
            addState(DISABLED_SELECTED, stateShape(if (filled) palette.raised else palette.selection,
                if (outlined) palette.outline else null))
            addState(DISABLED, stateShape(if (filled) palette.raised else Color.TRANSPARENT))
            addState(FOCUSED_SELECTED, stateShape(selected, focusStroke, focused = true))
            addState(FOCUSED, stateShape(normal, focusStroke, focused = true))
            addState(SELECTED, stateShape(selected, if (outlined) palette.accent else null))
            addState(DEFAULT, stateShape(normal, if (outlined) palette.outline else null))
        }
        return RippleDrawable(rippleColors(filled), states, shape(Color.WHITE, radius))
    }

    /** One-time geometry; semantic values, layout parameters and callbacks stay owned by the app. */
    fun configureButton(button: Button) {
        typography(button, 14f, medium = true)
        button.isAllCaps = false
        touchTarget(button)
        button.minWidth = dp(48); button.minHeight = dp(48)
        button.setPaddingRelative(dp(12), dp(8), dp(12), dp(8))
        button.stateListAnimator = null; button.elevation = 0f
    }

    fun configureIcon(view: ImageView) {
        touchTarget(view)
        view.scaleType = ImageView.ScaleType.CENTER_INSIDE
        view.setPadding(dp(12), dp(12), dp(12), dp(12))
        view.stateListAnimator = null; view.elevation = 0f
    }

    fun typography(view: TextView, sizeSp: Float, medium: Boolean = false) {
        view.textSize = sizeSp; view.includeFontPadding = false
        view.typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }

    /** Platform Button paint. Material widgets can use the factories with their own APIs. */
    fun applyButton(button: Button, filled: Boolean = false, outlined: Boolean = false, radius: Int = 12) {
        if (alreadyApplied(button, BUTTON, filled, outlined, radius)) return
        val colors = if (filled) filledColors else contentColors
        button.backgroundTintList = null
        val surface = bindSurface(button, BUTTON, filled, outlined, radius, colors) { ink, icon, _ ->
            button.setTextColor(ink);button.compoundDrawableTintList=ColorStateList.valueOf(icon)
        }
        if(button.background !== surface.background)button.background=surface.background
        applied(button, BUTTON, filled, outlined, radius, surface)
    }

    /** Paint a retained platform button from a native recipe, keeping its layout and input owner. */
    internal fun applyButton(button: Button, recipe: NativeButtonRecipe, colors: NativeButtonPalette) {
        val previous = button.getTag(R.id.native_ui_control_style)
        val retained = previous as? RecipeButton
        if (retained != null && retained.recipe == recipe && retained.colors == colors &&
            retained.density == density && retained.underlay == palette.surface &&
            button.background === retained.surface.background) return

        retained?.let { button.removeOnLayoutChangeListener(it.layoutListener) }
        (previous as? Applied)?.surface?.close()

        val enabled = recipe.paint(enabled = true, emphasized = true)
        val disabled = recipe.paint(enabled = false, emphasized = true)
        val enabledInk = enabled.content.resolve(colors, enabled.contentOpacity)
        val enabledIndication = enabled.indication?.resolve(colors) ?: enabledInk
        val states = arrayOf(DISABLED, DEFAULT)
        val fills = ColorStateList(states, intArrayOf(
            disabled.container.resolve(colors, disabled.containerOpacity),
            enabled.container.resolve(colors, enabled.containerOpacity),
        ))
        val inks = ColorStateList(states, intArrayOf(
            disabled.content.resolve(colors, disabled.contentOpacity), enabledInk,
        ))
        val strokes = ColorStateList(arrayOf(DISABLED, FOCUSED, DEFAULT),
            intArrayOf(Color.TRANSPARENT, enabledIndication, Color.TRANSPARENT))
        val ripples = ColorStateList(states, intArrayOf(Color.TRANSPARENT,
            withAlpha(enabledIndication, ((enabledIndication ushr 24) * 40 + 127) / 255)))
        val radiusLimit = recipe.surface.radiusDp?.times(density) ?: Float.MAX_VALUE
        fun radius(width: Int, height: Int) = minOf(radiusLimit, minOf(width, height) / 2f)
        val strokeWidth = dp(1).coerceAtLeast(1).toFloat()
        val surface = retained?.surface ?: StateColorSurface(button, fills, strokes, inks, ripples,
            radius(button.width, button.height), strokeWidth, underlay = palette.surface) { ink, icon, _ ->
            button.setTextColor(ink)
            button.compoundDrawableTintList = ColorStateList.valueOf(icon)
        }
        fun bindRadius(width: Int, height: Int) {
            surface.bind(fills, strokes, inks, ripples, radius(width, height), strokeWidth, underlay = palette.surface)
        }
        surface.setSurfacePreset(colors.surfacePreset)
        bindRadius(button.width, button.height)
        button.backgroundTintList = null
        if (button.background !== surface.background) {
            val start = button.paddingStart
            val top = button.paddingTop
            val end = button.paddingEnd
            val bottom = button.paddingBottom
            val relative = button.isPaddingRelative
            val left = button.paddingLeft
            val right = button.paddingRight
            button.background = surface.background
            if (relative) button.setPaddingRelative(start, top, end, bottom)
            else button.setPadding(left, top, right, bottom)
        }
        val layoutListener = View.OnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            bindRadius(right - left, bottom - top)
        }
        button.addOnLayoutChangeListener(layoutListener)
        button.setTag(R.id.native_ui_control_style,
            RecipeButton(recipe, colors, density, palette.surface, surface, layoutListener))
    }

    fun applyIcon(view: ImageView, filled: Boolean = false, radius: Int = 12) {
        if (alreadyApplied(view, ICON, filled, false, radius)) return
        view.backgroundTintList = null
        val colors=if(filled)filledColors else contentColors
        val surface=bindSurface(view,ICON,filled,false,radius,colors) { ink, _, _ ->view.imageTintList=ColorStateList.valueOf(ink)}
        if(view.background !== surface.background)view.background=surface.background
        applied(view, ICON, filled, false, radius,surface)
    }

    /** Paint only: text, selection, composing spans and IME configuration remain untouched. */
    fun applyInput(input: EditText, radius: Int = 12) {
        if (alreadyApplied(input, INPUT, false, false, radius)) return
        input.backgroundTintList = null
        val states=arrayOf(DISABLED,FOCUSED,DEFAULT)
        val fills=ColorStateList(states,intArrayOf(palette.surface,palette.raised,palette.raised))
        val strokes=ColorStateList(states,intArrayOf(palette.outline,palette.accent,palette.outline))
        val inks=foregroundStates(normal=palette.content,selected=palette.content)
        val hints=foregroundStates(normal=palette.muted,selected=palette.muted)
        val highlight=ColorStateList.valueOf(withAlpha(palette.accent,64))
        val surface=surface(input,INPUT,fills,strokes,inks,ColorStateList.valueOf(0),radius,hints,highlight) {ink,hint,accent->
            input.setTextColor(ink);input.setHintTextColor(hint);input.highlightColor=accent
        }
        if(input.background !== surface.background)input.background=surface.background
        applied(input, INPUT, false, false, radius,surface)
    }

    private fun bindSurface(view:View,kind:Int,filled:Boolean,outlined:Boolean,radius:Int,ink:ColorStateList,
        publish:(Int,Int,Int)->Unit):StateColorSurface {
        val normal=if(filled)palette.accent else Color.TRANSPARENT
        val selected=if(filled)palette.accent else palette.selection
        val focus=if(filled)palette.onAccent else palette.accent
        val states=arrayOf(DISABLED_SELECTED,DISABLED,FOCUSED_SELECTED,FOCUSED,SELECTED,DEFAULT)
        val fills=ColorStateList(states,intArrayOf(if(filled)palette.raised else palette.selection,
            if(filled)palette.raised else Color.TRANSPARENT,selected,normal,selected,normal))
        val strokes=ColorStateList(states,intArrayOf(if(outlined)palette.outline else 0,0,focus,focus,
            if(outlined)palette.accent else 0,if(outlined)palette.outline else 0))
        val ripples=rippleColors(filled)
        return surface(view,kind,fills,strokes,ink,ripples,radius,ink,ink,publish)
    }

    private fun surface(view:View,kind:Int,fill:ColorStateList,stroke:ColorStateList,ink:ColorStateList,
        ripple:ColorStateList,radius:Int,secondary:ColorStateList,accent:ColorStateList,
        publish:(Int,Int,Int)->Unit):StateColorSurface {
        val binding = view.getTag(R.id.native_ui_control_style)
        if (binding is RecipeButton) {
            view.removeOnLayoutChangeListener(binding.layoutListener)
            binding.surface.close()
        }
        val previous = binding as? Applied
        val retained=previous?.surface?.takeIf {previous.kind==kind}
        if(retained==null)previous?.surface?.close()
        val result=retained ?: StateColorSurface(view,fill,stroke,ink,ripple,dp(radius).toFloat(),
            dp(1).coerceAtLeast(1).toFloat(),secondary,accent,palette.surface,
            focusedStrokeScale=if(kind==INPUT)1f else 2f,publishInk=publish)
        result.setSurfacePreset(palette.surfacePreset)
        result.bind(fill,stroke,ink,ripple,dp(radius).toFloat(),dp(1).coerceAtLeast(1).toFloat(),secondary,accent,palette.surface)
        return result
    }

    private fun touchTarget(view: View) { view.minimumWidth = dp(48); view.minimumHeight = dp(48) }
    private fun disabledForeground(): Int = blend(palette.surface, palette.muted, .5f)
    private fun alreadyApplied(view: View, kind: Int, filled: Boolean, outlined: Boolean, radius: Int): Boolean {
        val previous = view.getTag(R.id.native_ui_control_style) as? Applied ?: return false
        return previous.kind == kind && previous.palette == palette && previous.density == density &&
            previous.filled == filled && previous.outlined == outlined && previous.radius == radius
    }
    private fun applied(view: View, kind: Int, filled: Boolean, outlined: Boolean, radius: Int,surface:StateColorSurface?=null) {
        view.setTag(R.id.native_ui_control_style, Applied(kind, palette, density, filled, outlined, radius,surface))
    }
    private data class Applied(val kind: Int, val palette: ControlPalette, val density: Float,
                               val filled: Boolean, val outlined: Boolean, val radius: Int,val surface:StateColorSurface?=null)

    private data class RecipeButton(
        val recipe: NativeButtonRecipe,
        val colors: NativeButtonPalette,
        val density: Float,
        val underlay: Int,
        val surface: StateColorSurface,
        val layoutListener: View.OnLayoutChangeListener,
    )

    private companion object {
        const val BUTTON = 1; const val ICON = 2; const val INPUT = 3
        val DISABLED_SELECTED = intArrayOf(-android.R.attr.state_enabled, android.R.attr.state_selected)
        val DISABLED = intArrayOf(-android.R.attr.state_enabled)
        val FOCUSED_SELECTED = intArrayOf(android.R.attr.state_focused, android.R.attr.state_selected)
        val FOCUSED = intArrayOf(android.R.attr.state_focused)
        val SELECTED = intArrayOf(android.R.attr.state_selected)
        val DEFAULT = intArrayOf()
        fun makeForeground(normal: Int, selected: Int, disabled: Int) =
            ColorStateList(arrayOf(DISABLED, SELECTED, DEFAULT), intArrayOf(disabled, selected, normal))
        fun withAlpha(color: Int, alpha: Int) = (color and 0x00ffffff) or (alpha shl 24)
        fun blend(start: Int, end: Int, amount: Float): Int {
            fun channel(shift: Int): Int {
                val a = start ushr shift and 0xff; val b = end ushr shift and 0xff
                return (a + (b - a) * amount).roundToInt()
            }
            return Color.argb(channel(24), channel(16), channel(8), channel(0))
        }
    }
}
