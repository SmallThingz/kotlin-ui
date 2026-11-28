package smallthingz.ui.reverb

import smallthingz.ui.components.NativeMergedActionLayout
import smallthingz.ui.components.NativeButtonInputPolicy
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.util.closePreservingPrimaryFailure
import android.content.Context
import smallthingz.ui.runtime.MonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import android.graphics.Canvas
import smallthingz.ui.animation.view.ViewColorTransition
import smallthingz.ui.components.NativeCircularProgress
import android.view.View
import android.view.ViewGroup
import smallthingz.ui.common.formatRangeTimeInput
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class PlayerTrimHeading(context: Context, private var environment: ReverbEnvironment, titleRes: Int) : ViewGroup(context) {
    private val layoutStyle = environment.appearance.listDecorations.recordingPlayer
    private val icon = PlayerGlyph(context, environment, ReverbIcon.Trim)
    val title = environment.paragraph(context, context.getString(titleRes), ReverbTextRole.LABEL_LARGE)
    val duration = environment.paragraph(context, "", ReverbTextRole.BODY_SMALL)
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; addView(icon); addView(title); addView(duration) }
    fun bind(value: ReverbEnvironment, state: LibraryPlayerUiState) {
        val densityChanged = environment.textDensity.density != value.textDensity.density
        environment = value
        if (densityChanged) requestLayout()
        icon.bind(value, value.palette.primary)
        value.bindParagraphStyle(title, ReverbTextRole.LABEL_LARGE, value.palette.ink)
        duration.setText(formatRangeTimeInput((state.trimEndMillis - state.trimStartMillis).coerceAtLeast(0) / 1000.0))
        duration.setTypography(value.appearance.readoutTypography.playerTimestamp,
            value.textDensity, value.sharedFontResolver)
        duration.animateTextColor(value.palette.muted)
    }
    private fun dp(value: Float) = (value * environment.textDensity.density).roundToInt()
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val hs = heightMeasureSpec.toAtMost()
        val glyph = dp(layoutStyle.headingGlyphDp)
        val glyphHeight = if (View.MeasureSpec.getMode(hs) == MeasureSpec.UNSPECIFIED) glyph
            else min(glyph, MeasureSpec.getSize(hs))
        icon.measure(libraryPlayerControlsExact(min(width, glyph)), libraryPlayerControlsExact(glyphHeight))
        val textWidth = max(0, width - icon.measuredWidth - dp(layoutStyle.headingGlyphGapDp))
        title.measure(libraryPlayerControlsAtMost(textWidth), hs)
        duration.measure(libraryPlayerControlsAtMost(max(0, textWidth - title.measuredWidth)), hs)
        setMeasuredDimension(width, resolveSize(max(icon.measuredHeight, max(title.measuredHeight, duration.measuredHeight)), heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        fun place(child: View, logical: Int) {
            val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - logical - child.measuredWidth else logical
            val y = ((height - child.measuredHeight) / 2f).roundToInt()
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
        place(icon, 0)
        place(title, icon.measuredWidth + dp(layoutStyle.headingGlyphGapDp))
        place(duration, width - duration.measuredWidth)
    }
}

class PlayerTrimButtons(
    context: Context,
    private var environment: ReverbEnvironment,
    inputRoot: View,
    private val frames: MonotonicFrameClock,
    private val scope: CoroutineScope,
    private val labels: ReverbPlayerLabels,
    private val durationScale: () -> Float,
) : ViewGroup(context), AutoCloseable {
    private var revision: String? = null
    private var saving = false
    private var saveEnabled = false
    private var closed = false
    private var locales = resources.configuration.locales
    private var cancelAction: (() -> Unit)? = null
    private var saveAction: (() -> Unit)? = null
    private var spinner: NativeCircularProgress? = null
    private var glyphDensity = Float.NaN
    private val cancelClick: () -> Unit = { cancelAction?.invoke() }
    private val saveClick: () -> Unit = { saveAction?.invoke() }
    private val recipes = environment.appearance.buttons.trimActions
    private val input = NativeButtonInputPolicy(inputRoot = { inputRoot }, revisionKey = { revision }, delayPressForParent = true)
    private val cancel = NativeMaterialButtonView(context, recipes.cancel,
        environment.textDensity, environment.sharedFontResolver, input, cancelClick)
    private val save = NativeMaterialButtonView(context, recipes.save.button,
        environment.textDensity, environment.sharedFontResolver, input, saveClick)
    private val group = NativeMergedActionLayout(context, listOf(cancel, save), environment.palette, recipes.cornerRadiusDp)
        .asActionFooter(density = environment.textDensity.density)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false
        addView(group)
    }

    fun bind(value: ReverbEnvironment, state: LibraryPlayerUiState, actions: LibraryPlayerActions) {
        if (closed) return
        cancelAction = actions.cancelTrim
        saveAction = actions.saveTrim
        val nextLocales = resources.configuration.locales
        val nextSaveEnabled = !state.trimSaving && state.trimEndMillis > state.trimStartMillis
        val environmentChanged = environment !== value
        val firstBind = revision == null
        if (!environmentChanged && revision == state.recordingRevisionKey && saving == state.trimSaving &&
            saveEnabled == nextSaveEnabled && locales == nextLocales) return

        revision = state.recordingRevisionKey
        val leaveSaving = saving && !state.trimSaving
        saving = state.trimSaving
        saveEnabled = nextSaveEnabled
        locales = nextLocales
        val palette = value.palette.buttonPalette
        if (environmentChanged) {
            environment = value
            cancel.bindTypography(value.textDensity, value.sharedFontResolver)
            save.bindTypography(value.textDensity, value.sharedFontResolver)
            group.bindPalette(value.palette)
            group.asActionFooter(density = value.textDensity.density)
        }
        val density = value.textDensity.density
        if (glyphDensity != density) {
            cancel.setLeadingIcon(ReverbIconDrawable(ReverbIcon.Close, density, recipes.cancel.contentColor(palette, !saving)))
            glyphDensity = density
            if (!saving) save.setLeadingIcon(ReverbIconDrawable(ReverbIcon.Save, density, recipes.save.button.contentColor(palette, saveEnabled)))
        } else if (leaveSaving) {
            save.setLeadingIcon(ReverbIconDrawable(ReverbIcon.Save, density, recipes.save.button.contentColor(palette, saveEnabled)))
        }
        if (saving) {
            val spinnerColor = recipes.save.progressColor(palette)
            if (spinner == null) {
                save.setLeadingIcon(null)
                spinner = NativeCircularProgress(context, spinnerColor, frames, scope, durationScale)
                    .also { save.setLeadingView(it) }
            }
            spinner?.bindSurfacePreset(value.palette.surfacePreset)
            spinner?.setColor(spinnerColor)
        } else if (spinner != null) {
            save.setLeadingView(null)
            spinner = null
        }
        cancel.bind(context.getString(labels.cancel), !saving, palette)
        save.bind(context.getString(if (saving) labels.saving else labels.saveCopy),
            saveEnabled, palette, busy = saving)
        if (firstBind || environmentChanged) requestLayout()
    }

    override fun onMeasure(w: Int, h: Int) {
        group.measure(w, h.toAtMost())
        setMeasuredDimension(group.measuredWidth, resolveSize(group.measuredHeight, h))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) 0 else width - group.measuredWidth
        val y = (height - group.measuredHeight) / 2
        group.layout(x, y, x + group.measuredWidth, y + group.measuredHeight)
    }

    override fun close() {
        if (closed) return
        closed = true
        cancelAction = null
        saveAction = null
        spinner = null
        val failure = closePreservingPrimaryFailure(null, cancel::close)
        closePreservingPrimaryFailure(failure, save::close)?.let { throw it }
    }

    private fun dp(value: Int) = (value * environment.textDensity.density).roundToInt()
}

private class PlayerGlyph(context: Context, private var environment: ReverbEnvironment,
                           private val glyph: ReverbIcon) : View(context) {
    private var drawable = ReverbIconDrawable(glyph, environment.textDensity.density, environment.palette.ink)
    private val colorMotion: ViewColorTransition = ViewColorTransition(
        this, intArrayOf(environment.palette.ink), ::applyColor,
    )
    private fun applyColor() { drawable.setTint(colorMotion.color(0)); invalidate() }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; if (android.os.Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false }
    fun bind(value: ReverbEnvironment, color: Int) {
        if (value.textDensity.density != environment.textDensity.density) drawable = ReverbIconDrawable(glyph, value.textDensity.density, color)
        environment = value
        colorMotion.retarget(color)
        drawable.setTint(colorMotion.color(0))
        drawable.layoutDirection = layoutDirection
        drawable.setBounds(0, 0, width, height)
        invalidate()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { drawable.setBounds(0, 0, w, h) }
    override fun onRtlPropertiesChanged(layoutDirection: Int) { super.onRtlPropertiesChanged(layoutDirection); drawable.layoutDirection = layoutDirection }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        drawable.setVisible(isVisible, false)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        drawable.setVisible(false, false)
        invalidate()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) { drawable.draw(canvas) }
}

private fun libraryPlayerControlsExact(value: Int) = View.MeasureSpec.makeMeasureSpec(value.coerceAtLeast(0), View.MeasureSpec.EXACTLY)
private fun libraryPlayerControlsAtMost(value: Int) = View.MeasureSpec.makeMeasureSpec(value.coerceAtLeast(0), View.MeasureSpec.AT_MOST)
private fun Int.toAtMost(): Int = if (View.MeasureSpec.getMode(this) == View.MeasureSpec.UNSPECIFIED) this else libraryPlayerControlsAtMost(View.MeasureSpec.getSize(this))
