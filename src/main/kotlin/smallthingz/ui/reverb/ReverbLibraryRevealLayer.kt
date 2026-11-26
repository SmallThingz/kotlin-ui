package smallthingz.ui.reverb

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.graphics.drawable.ColorDrawable
import smallthingz.ui.animation.view.ViewColorTransition
import smallthingz.ui.scroll.NativeSourceRecyclerView

/**
 * The three released MainScreen Library Boxes, with the same transform/alpha ordering.
 * Background noise belongs below statusTop + AppTopBarContentHeight, not the whole overlay.
 */
class ReverbLibraryRevealLayer(
    context: Context,
    initialEnvironment: ReverbEnvironment,
    val content: View,
) : ViewGroup(context) {
    private var environment = initialEnvironment
    private var statusTop = 0
    private var contentTop = 0
    private var progress = 0f
    private val scrim = View(context)
    private val translated = object : ViewGroup(context) {
        init { clipChildren = false; clipToPadding = false }
        // Parent owns these two source Box placements; do not measure the content twice.
        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h))
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = Unit
    }
    private val backgroundPanel = View(context)
    private val noise = initialEnvironment.appearance.backdrops.pageBackground(initialEnvironment.palette)
    private var scrollIndicatorViewport: NativeSourceRecyclerView? = null
    private val scrimPaint = ColorDrawable(initialEnvironment.appearance.backdrops.panelScrimColor(initialEnvironment.palette))
    private val paletteMotion:ViewColorTransition by lazy {ViewColorTransition(this,
        intArrayOf(initialEnvironment.palette.background,scrimPaint.color),{
            val fill = paletteMotion.color(0)
            noise.setBackgroundColor(fill)
            scrimPaint.color = paletteMotion.color(1)
            publishScrollIndicatorUnderlay(fill)
        })}

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false
        clipToPadding = false
        scrim.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        translated.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        backgroundPanel.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        backgroundPanel.background = noise
        scrim.background=scrimPaint
        addView(scrim)
        addView(translated)
        translated.addView(backgroundPanel)
        translated.addView(content)
    }

    fun bindScrollIndicatorViewport(view: NativeSourceRecyclerView) {
        scrollIndicatorViewport = view
    }

    private fun publishScrollIndicatorUnderlay(fill: Int) {
        scrollIndicatorViewport?.bindScrollIndicatorTransitionUnderlay(
            if (fill == environment.palette.background) 0L else noise.paintedTextSurface)
    }

    fun bind(environment: ReverbEnvironment, statusTop: Int, progress: Float, visible: Boolean) {
        if (this.environment !== environment) {
            this.environment = environment
            val palette = environment.palette
            val backdrop = environment.appearance.backdrops
            noise.setGrainSeed(backdrop.pageGrainSeed)
            noise.setNoiseIntensity(palette.backgroundGrain)
            val motion = paletteMotion
            motion.retarget(palette.background, backdrop.panelScrimColor(palette))
            // An unchanged, settled color target can skip the motion's paint callback.
            publishScrollIndicatorUnderlay(motion.color(0))
        }
        val nextContentTop = statusTop + environment.appearance.toolbars.compactHeightPx(environment.textDensity.density)
        if (this.statusTop != statusTop || contentTop != nextContentTop) {
            this.statusTop = statusTop
            contentTop = nextContentTop
            requestLayout()
        }
        this.progress = progress
        alpha = if (progress > 0f || visible) 1f else .01f
        applyMotion()
    }

    private fun applyMotion() {
        scrim.alpha = progress
        translated.translationY = (1f - progress) * height
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val height = MeasureSpec.getSize(h)
        scrim.measure(exact(width), exact((height - contentTop).coerceAtLeast(0)))
        translated.measure(exact(width), exact(height))
        backgroundPanel.measure(exact(width), exact((height - contentTop).coerceAtLeast(0)))
        content.measure(exact(width), exact((height - statusTop).coerceAtLeast(0)))
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        scrim.layout(0, contentTop, width, contentTop + scrim.measuredHeight)
        translated.layout(0, 0, width, height)
        backgroundPanel.layout(0, contentTop, width, contentTop + backgroundPanel.measuredHeight)
        content.layout(0, statusTop, width, statusTop + content.measuredHeight)
        applyMotion()
    }

    private fun exact(value: Int) = MeasureSpec.makeMeasureSpec(value, MeasureSpec.EXACTLY)
}
