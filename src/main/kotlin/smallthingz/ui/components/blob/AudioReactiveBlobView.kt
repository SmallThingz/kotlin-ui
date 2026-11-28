// SPDX-License-Identifier: Apache-2.0
package smallthingz.ui.components.blob

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LightingColorFilter
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.view.Choreographer
import android.view.View
import androidx.annotation.RequiresApi
import smallthingz.ui.animation.view.ViewColorTransition
import smallthingz.ui.interaction.view.readCachedAnimatorDurationScale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Audio-reactive surface driven by normalized amplitude and frequency samples.
 * Rendering and decorative motion advance independently of the host's sample delivery.
 */
class AudioReactiveBlobView(
    context: Context,
    private val durationScale: () -> Float = { readCachedAnimatorDurationScale(context) },
) : View(context) {
    private val targetBands = FloatArray(BAND_COUNT)
    private val currentBands = FloatArray(BAND_COUNT)
    private var targetActivity = 0f
    private var currentActivity = 0f
    private var targetLife = COLLAPSED_LIFE
    private var currentLife = COLLAPSED_LIFE
    private var active = false
    private var enabledState = true
    private var saving = false
    private var skipSilenceEnabled = false
    private var silenceSuppressed = false
    private var currentSilence = 0f
    private var currentPresence = 0f
    private val targetPresence: Float get() = if (active && !saving) 1f else 0f
    var onReadoutAppearanceChanged: ((Boolean, Boolean, Boolean) -> Unit)? = null
        set(value) { field = value; lastReadoutAppearance = -1; publishReadoutAppearance() }
    private var lastReadoutAppearance = -1
    private var materialTransitionOwnsPalette = false
    private var materialPrimary = 0
    private var materialTertiary = 0
    private var materialPaused = 0
    private var readoutRadiusPixels = 0f
    private var readoutMaterialCorrection = 0
    private var darkeningCorrection = 0
    private var darkeningFilter: LightingColorFilter? = null
    private var lighteningCorrection = 0
    private var lighteningFilter: LightingColorFilter? = null
    private var readoutBackground = 0
    private var readoutUsesBackdropInk = false
    private var readoutLayerOpacity = 1f
    private var readoutBackdropUsesDarkInk = false
    private var readoutAgslMin = 0f
    private var readoutAgslMax = 1f
    private var readoutFallbackMin = 0f
    private var readoutFallbackMax = 1f

    fun updateReadoutGeometry(radiusPixels: Float, background: Int, agslMin: Float = 0f, agslMax: Float = 1f,
        fallbackMin: Float = 0f, fallbackMax: Float = 1f) {
        updateReadoutGeometry(radiusPixels, background, agslMin, agslMax, fallbackMin, fallbackMax,
            useBackdropInk = false, layerOpacity = 1f)
    }

    internal fun updateReadoutGeometry(radiusPixels: Float, background: Int, agslMin: Float, agslMax: Float,
        fallbackMin: Float, fallbackMax: Float, useBackdropInk: Boolean, layerOpacity: Float) {
        if (readoutRadiusPixels == radiusPixels && readoutBackground == background &&
            readoutUsesBackdropInk == useBackdropInk && readoutLayerOpacity == layerOpacity &&
            readoutAgslMin == agslMin && readoutAgslMax == agslMax &&
            readoutFallbackMin == fallbackMin && readoutFallbackMax == fallbackMax) return
        readoutRadiusPixels = radiusPixels
        if (readoutBackground != background) {
            // Black and white offer equal contrast at this backdrop luminance.
            readoutBackdropUsesDarkInk = audioBlobMaterialLuminance(background) >= .179
            readoutBackground = background
        }
        readoutUsesBackdropInk = useBackdropInk
        readoutLayerOpacity = layerOpacity
        readoutAgslMin = agslMin
        readoutAgslMax = agslMax
        readoutFallbackMin = fallbackMin
        readoutFallbackMax = fallbackMax
        publishReadoutAppearance()
    }

    internal fun setReadoutMaterialCorrection(correction: Int) {
        if (readoutMaterialCorrection == correction) return
        readoutMaterialCorrection = correction
        // Correct pigment without painting outside the renderer. Retain the last filter
        // of each polarity so a coverage change during audio motion can reuse it.
        val filter = when {
            correction < 0 -> {
                if (darkeningCorrection != correction) {
                    darkeningCorrection = correction
                    darkeningFilter = LightingColorFilter(0x00ffffff + correction * 0x010101, 0)
                }
                darkeningFilter
            }
            correction > 0 -> {
                if (lighteningCorrection != correction) {
                    lighteningCorrection = correction
                    val amount = correction * 0x010101
                    lighteningFilter = LightingColorFilter(0x00ffffff - amount, amount)
                }
                lighteningFilter
            }
            else -> null
        }
        renderer.setReadoutColorFilter(filter)
        invalidate()
    }

    fun readoutNeedsSupport(darkInk: Boolean): Boolean {
        val size = minOf(width, height).toFloat()
        return readoutNeedsSupport(darkInk, size, materialCoversReadout(size))
    }

    private fun materialCoversReadout(size: Float): Boolean {
        if (size <= 0f || readoutRadiusPixels <= 0f) return false
        if (renderer is FallbackRenderer && materialPaused ushr 24 != 255 &&
            audioBlobColorLife(currentLife, currentPresence) < 1f
        ) return false
        // Full bands can fold inward by .096 * smoothstep(activity), while activity
        // grows the base by .018 * activity. The net inward loss peaks at .078288;
        // scaling .079 by life and presence stays conservative because base growth
        // scales by life alone. Use that envelope and keep the antialiasing margin.
        val inwardLoss = .079f * currentLife * currentPresence
        return readoutRadiusPixels / size <= baseRadiusFraction(activity = 0f) - inwardLoss - .012f
    }

    private fun readoutNeedsSupport(darkInk: Boolean, size: Float, covered: Boolean): Boolean {
        if (size <= 0f || readoutRadiusPixels <= 0f) return true
        val fallback = renderer is FallbackRenderer
        if (readoutUsesBackdropInk) {
            // The shared control can put ink across the contour at any size. Bound its
            // entire material against the same ink on both the body and the page.
            return audioBlobInkNeedsSupport(materialPrimary, materialTertiary, materialPaused,
                currentLife, currentPresence, currentSilence, fallback, darkInk,
                radiusFraction = 1f, covered = false, background = readoutBackground,
                layerOpacity = readoutLayerOpacity)
        }
        val radius = readoutRadiusPixels / size
        // The quiet base gives an upper bound on radial drain without following raw audio.
        val base = baseRadiusFraction(activity = 0f)
        return audioBlobInkNeedsSupport(materialPrimary, materialTertiary, materialPaused,
            currentLife, currentPresence, currentSilence, fallback, darkInk,
            (radius / base).coerceIn(0f, 1f),
            covered, readoutBackground, if (fallback) readoutFallbackMin else readoutAgslMin,
            if (fallback) readoutFallbackMax else readoutAgslMax,
            layerOpacity = readoutLayerOpacity)
    }
    // Only the submitted signal supplies current suppression.
    private val targetSilence: Float
        get() = if (active && !saving && silenceSuppressed) 1f else 0f
    private var renderingVisible = true
    private var framePosted = false
    private var shaderTimeSeconds = 0f
    private var lastFrameNanos = 0L
    private var scaleReadDenied = false
    private var lastAudioSignalNanos = 0L
    private val choreographer = Choreographer.getInstance()

    private val renderer: Renderer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        runCatching<Renderer> { ShaderRenderer() }.getOrElse { FallbackRenderer() }
    } else {
        FallbackRenderer()
    }
    private var paletteMotion: ViewColorTransition? = null
    private fun publishPalette() {
        val colors = paletteMotion ?: return
        setMaterialPalette(colors.color(0), colors.color(1), colors.color(2))
        invalidate()
    }

    private fun setMaterialPalette(primary: Int, tertiary: Int, paused: Int) {
        materialPrimary = audioBlobMaterialColor(primary)
        materialTertiary = audioBlobMaterialColor(tertiary)
        materialPaused = paused
        renderer.setPalette(materialPrimary, materialTertiary, paused)
        publishReadoutAppearance()
    }

    fun readoutUsesDarkInk(): Boolean = resolveReadoutInkAndSupport() and 1 != 0

    private fun resolveReadoutInkAndSupport(): Int {
        val size = minOf(width, height).toFloat()
        if (readoutUsesBackdropInk) {
            val darkInk = readoutBackdropUsesDarkInk
            val supportNeeded = readoutNeedsSupport(darkInk, size, covered = false)
            return (if (darkInk) 1 else 0) or (if (supportNeeded) 4 else 0)
        }
        val covered = materialCoversReadout(size)
        var darkInk = audioBlobUsesDarkInk(materialPrimary, materialTertiary, materialPaused, currentLife,
            currentPresence, currentSilence, renderer is FallbackRenderer)
        var supportNeeded = readoutNeedsSupport(darkInk, size, covered)
        if (supportNeeded && !readoutNeedsSupport(!darkInk, size, covered)) {
            darkInk = !darkInk
            supportNeeded = false
        }
        return (if (darkInk) 1 else 0) or (if (supportNeeded) 4 else 0)
    }

    private fun publishReadoutAppearance() {
        val listener = onReadoutAppearanceChanged ?: return
        val resolvedInk = resolveReadoutInkAndSupport()
        val geometryTransition = currentPresence != targetPresence || currentLife != targetLife
        if (geometryTransition) materialTransitionOwnsPalette = true
        // A palette tail may extend an owned capture transition, but idle theme changes
        // must not apply a new readout correction to the small resting body.
        val transitioning = geometryTransition ||
            (materialTransitionOwnsPalette && paletteMotion?.isInterpolating == true)
        if (!transitioning) materialTransitionOwnsPalette = false
        val appearance = resolvedInk or (if (transitioning) 2 else 0)
        if (appearance == lastReadoutAppearance) return
        lastReadoutAppearance = appearance
        listener(appearance and 1 != 0, transitioning, appearance and 4 != 0)
    }

    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        framePosted = false
        val scale = motionDurationScale()
        if (scale == 0f) {
            stopFrames()
            snapPresentationToTargets()
            return@FrameCallback
        }
        if (!shouldAnimate()) {
            lastFrameNanos = 0L
            return@FrameCallback
        }
        val dtSeconds = if (lastFrameNanos == 0L) {
            initialFrameDeltaSeconds()
        } else {
            ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
        }
        lastFrameNanos = frameTimeNanos
        val motionSeconds = dtSeconds / scale
        shaderTimeSeconds += motionSeconds
        advance(motionSeconds)
        invalidate()
        postNextFrame()
    }

    init {
        setWillNotDraw(false)
        isFocusable = false
        isClickable = false
    }

    /** Activity supplies amplitude. Bins supply relative shape and are borrowed for this call. */
    fun submitFrame(activity: Float, bins: FloatArray, silenceSuppressed: Boolean) {
        val hadRecentSignal = hasRecentAudioSignal()
        this.silenceSuppressed = silenceSuppressed
        targetActivity = activity.coerceIn(0f, 1f)
        val source = bins
        if (source.isEmpty()) {
            targetBands.fill(0f)
        } else {
            for (band in 0 until BAND_COUNT) {
                val start = band * source.size / BAND_COUNT
                val end = max(start + 1, (band + 1) * source.size / BAND_COUNT).coerceAtMost(source.size)
                var peak = 0f
                for (index in start until end) peak = max(peak, source[index])
                targetBands[band] = peak.coerceIn(0f, 1f)
            }
        }
        if (targetActivity > SIGNAL_ACTIVITY_THRESHOLD) lastAudioSignalNanos = System.nanoTime()
        if (!hadRecentSignal && hasRecentAudioSignal() && framePosted) {
            // A quiet onset can be below the "hot" threshold while an idle 33 ms callback is
            // pending. Pull that first response forward to the very next vsync. Subsequent audio
            // stays at display cadence via postNextFrame(), so we do not continually re-arm it.
            choreographer.removeFrameCallback(frameCallback)
            framePosted = false
            postNextFrame(immediate = true)
        } else {
            ensureAnimationState(audioSubmission = true)
        }
    }

    fun clearFrame() {
        silenceSuppressed = false
        currentSilence = 0f
        targetActivity = 0f
        targetBands.fill(0f)
        currentActivity = 0f
        currentBands.fill(0f)
        lastAudioSignalNanos = 0L
        invalidate()
        publishReadoutAppearance()
    }

    fun currentBaseRadiusFraction(): Float = baseRadiusFraction(currentActivity.coerceIn(0f, 1f))

    private fun baseRadiusFraction(activity: Float): Float =
        0.095f + currentLife * (0.235f + activity * 0.018f)

    fun updateState(
        active: Boolean,
        enabled: Boolean,
        saving: Boolean,
        visible: Boolean,
        style: NativeAudioBlobStyle,
        filled: Boolean = false,
        skipSilenceEnabled: Boolean = false,
    ) {
        val primary = style.primary
        val tertiary = style.tertiary
        val paused = if (filled) style.filledResting else style.resting
        val stateChanged = this.active != active ||
            enabledState != enabled || this.saving != saving || renderingVisible != visible ||
            this.skipSilenceEnabled != skipSilenceEnabled
        this.active = active
        enabledState = enabled
        this.saving = saving
        this.skipSilenceEnabled = skipSilenceEnabled
        renderingVisible = visible
        targetLife = when {
            !enabled -> DISABLED_LIFE
            active && !saving -> 1f
            else -> COLLAPSED_LIFE
        }
        val colors = paletteMotion ?: ViewColorTransition(this, intArrayOf(primary, tertiary, paused),
            { publishPalette() }).also {
            paletteMotion = it
            setMaterialPalette(primary, tertiary, paused)
        }
        if (visible) colors.retarget(primary, tertiary, paused) else colors.snap(primary, tertiary, paused)
        if (!active || saving) {
            silenceSuppressed = false
            targetActivity = 0f
            targetBands.fill(0f)
            lastAudioSignalNanos = 0L
        }
        if (stateChanged) {
            scaleReadDenied = false
            // A silent/residual frame may already be queued on the 33 ms idle cadence.
            // Do not let a new expand/collapse request inherit that stale delay.
            if (framePosted) {
                choreographer.removeFrameCallback(frameCallback)
                framePosted = false
            }
            ensureAnimationState()
        }
        if (stateChanged) invalidate()
        publishReadoutAppearance()
    }

    private fun currentMotionEnergy(): Float =
        audioBlobMotionEnergy(currentActivity, currentSilence) * currentLife * currentPresence

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderer.draw(
            canvas = canvas,
            width = width,
            height = height,
            timeSeconds = shaderTimeSeconds,
            activity = currentActivity,
            energy = currentMotionEnergy(),
            bands = currentBands,
            life = currentLife,
            active = currentPresence,
            silence = currentSilence,
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        renderer.resize(w, h)
        publishReadoutAppearance()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ensureAnimationState()
    }

    override fun onDetachedFromWindow() {
        stopFrames()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        ensureAnimationState()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        ensureAnimationState()
    }

    private fun shouldAnimate(): Boolean =
        renderingVisible && isAttachedToWindow && isShown && hasWindowFocus() && enabledState &&
            (hasRecentAudioSignal() || hasResidualAudio() || silenceMoving() || currentPresence != targetPresence ||
                kotlin.math.abs(currentLife - targetLife) > LIFE_EPSILON)

    private fun silenceMoving(): Boolean = kotlin.math.abs(currentSilence - targetSilence) > SILENCE_EPSILON

    private fun ensureAnimationState(audioSubmission: Boolean = false) {
        if (motionDurationScale() == 0f) {
            stopFrames()
            snapPresentationToTargets()
            return
        }
        if (shouldAnimate()) {
            postNextFrame(immediate = true)
        } else {
            stopFrames()
            if (!renderingVisible) {
                currentSilence = targetSilence
                currentActivity = 0f
                currentBands.fill(0f)
                currentLife = COLLAPSED_LIFE
                currentPresence = targetPresence
            } else if (!active || saving) {
                currentSilence = 0f
                currentActivity = 0f
                currentBands.fill(0f)
                currentLife = targetLife
                currentPresence = targetPresence
            }
            if (!audioSubmission || !renderingVisible || !active || saving) {
                invalidate()
                publishReadoutAppearance()
            }
        }
    }

    private fun postNextFrame(immediate: Boolean = false) {
        if (motionDurationScale() == 0f) {
            stopFrames()
            snapPresentationToTargets()
            return
        }
        if (framePosted) return
        if (!shouldAnimate()) {
            lastFrameNanos = 0L
            return
        }
        framePosted = true
        val audioResponsive = hasHotAudio() || hasRecentAudioSignal()
        val lifeMoving = kotlin.math.abs(currentLife - targetLife) > LIFE_EPSILON
        if (immediate || lifeMoving || silenceMoving() || currentPresence != targetPresence || audioResponsive) {
            // Choreographer already limits us to the display cadence. Do not add a fixed 16 ms
            // timer on top of vsync: on high-refresh panels that halves the response rate, and on
            // 60 Hz panels it can miss the very next frame depending on phase.
            choreographer.postFrameCallback(frameCallback)
        } else {
            choreographer.postFrameCallbackDelayed(frameCallback, renderer.idleFrameDelayMillis)
        }
    }

    private fun motionDurationScale(): Float {
        if (!ValueAnimator.areAnimatorsEnabled()) return 0f
        if (scaleReadDenied) return 1f
        val scale = try { durationScale() } catch (_: SecurityException) {
            // Optional setting access must not interrupt live visualization. Retry on state change.
            scaleReadDenied = true
            1f
        }
        return if (scale.isFinite() && scale >= 0f) scale else 1f
    }

    /** Disabled decoration still presents each newly received audio frame without an animation loop. */
    private fun snapPresentationToTargets() {
        val activity = if (renderingVisible) targetActivity else 0f
        val life = if (renderingVisible) targetLife else COLLAPSED_LIFE
        val silence = targetSilence
        val presence = targetPresence
        val appearanceChanged = currentLife != life || currentSilence != silence || currentPresence != presence
        var changed = currentActivity != activity || appearanceChanged
        currentActivity = activity
        currentLife = life
        currentSilence = silence
        currentPresence = presence
        for (index in currentBands.indices) {
            val band = if (renderingVisible) targetBands[index] else 0f
            if (currentBands[index] != band) changed = true
            currentBands[index] = band
        }
        if (changed) invalidate()
        if (appearanceChanged) publishReadoutAppearance()
    }

    private fun hasHotAudio(): Boolean =
        currentActivity > ACTIVE_FRAME_THRESHOLD || targetActivity > ACTIVE_FRAME_THRESHOLD

    private fun hasResidualAudio(): Boolean =
        targetActivity > SIGNAL_ACTIVITY_THRESHOLD ||
            (currentActivity > RESIDUAL_FRAME_THRESHOLD &&
                kotlin.math.abs(currentActivity - targetActivity) > RESIDUAL_FRAME_THRESHOLD)

    private fun hasRecentAudioSignal(): Boolean {
        if (!active || lastAudioSignalNanos == 0L) return false
        return System.nanoTime() - lastAudioSignalNanos <= RECENT_AUDIO_HOLD_NANOS
    }

    private fun initialFrameDeltaSeconds(): Float {
        val refreshRate = display?.refreshRate
            ?.takeIf { it.isFinite() && it >= 24f }
            ?.coerceIn(24f, 240f)
            ?: 60f
        return 1f / refreshRate
    }

    private fun stopFrames() {
        if (framePosted) {
            choreographer.removeFrameCallback(frameCallback)
            framePosted = false
        }
        lastFrameNanos = 0L
    }

    private fun advance(dtSeconds: Float) {
        val normalized = (dtSeconds * 30f).coerceIn(0f, 3f)
        val activityRate = if (targetActivity > currentActivity) 0.72f else 0.16f
        val activityMix = 1f - (1f - activityRate).pow(normalized)
        currentActivity += (targetActivity - currentActivity) * activityMix
        val attackMix = 1f - (1f - 0.68f).pow(normalized)
        val releaseMix = 1f - (1f - 0.13f).pow(normalized)
        for (index in currentBands.indices) {
            val mix = if (targetBands[index] > currentBands[index]) attackMix else releaseMix
            currentBands[index] += (targetBands[index] - currentBands[index]) * mix
        }
        val previousLife = currentLife
        val previousSilence = currentSilence
        val previousPresence = currentPresence
        val lifeRate = if (targetLife > currentLife) 0.18f else 0.15f
        val lifeMix = 1f - (1f - lifeRate).pow(normalized)
        currentLife += (targetLife - currentLife) * lifeMix
        if (kotlin.math.abs(currentLife - targetLife) <= LIFE_EPSILON) currentLife = targetLife
        currentSilence = advanceSilenceBlob(currentSilence, targetSilence, dtSeconds)
        currentPresence = advanceBlobPresence(currentPresence, targetPresence, dtSeconds)
        // The readout uses a conservative envelope independent of raw activity and bands.
        if (currentLife != previousLife || currentSilence != previousSilence || currentPresence != previousPresence) {
            publishReadoutAppearance()
        }
    }

    internal interface Renderer {
        val idleFrameDelayMillis: Long
        fun resize(width: Int, height: Int)
        fun setPalette(primary: Int, tertiary: Int, paused: Int): Boolean
        fun setReadoutColorFilter(filter: LightingColorFilter?)
        fun draw(
            canvas: Canvas,
            width: Int,
            height: Int,
            timeSeconds: Float,
            activity: Float,
            energy: Float,
            bands: FloatArray,
            life: Float,
            active: Float,
            silence: Float,
        )
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    internal class ShaderRenderer : Renderer {
        override val idleFrameDelayMillis = 33L
        private val shader = RuntimeShader(SHADER_SOURCE)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.shader = shader }
        private var primary = 0
        private var tertiary = 0
        private var paused = 0
        private var lastActivity = Float.NaN
        private var lastLife = Float.NaN
        private var lastActive = Float.NaN
        private var lastSkipSilence = Float.NaN
        private val lastBands = FloatArray(BAND_COUNT) { Float.NaN }

        override fun resize(width: Int, height: Int) {
            shader.setFloatUniform("resolution", width.toFloat(), height.toFloat())
        }

        override fun setReadoutColorFilter(filter: LightingColorFilter?) {
            paint.colorFilter = filter
        }

        override fun setPalette(primary: Int, tertiary: Int, paused: Int): Boolean {
            if (this.primary == primary && this.tertiary == tertiary && this.paused == paused) return false
            this.primary = primary
            this.tertiary = tertiary
            this.paused = paused
            shader.setColorUniform("primaryColor", primary)
            shader.setColorUniform("tertiaryColor", tertiary)
            shader.setColorUniform("pausedColor", paused)
            return true
        }

        override fun draw(
            canvas: Canvas,
            width: Int,
            height: Int,
            timeSeconds: Float,
            activity: Float,
            energy: Float,
            bands: FloatArray,
            life: Float,
            active: Float,
            silence: Float,
        ) {
            if (width <= 0 || height <= 0) return
            shader.setFloatUniform("timeAndEnergy", timeSeconds, energy)
            if (activity != lastActivity) {
                shader.setFloatUniform("activity", activity)
                lastActivity = activity
            }
            if (life != lastLife) {
                shader.setFloatUniform("life", life)
                lastLife = life
            }
            val activeValue = active
            if (activeValue != lastActive) {
                shader.setFloatUniform("active", activeValue)
                lastActive = activeValue
            }
            val skipValue = silence
            if (skipValue != lastSkipSilence) {
                shader.setFloatUniform("skipSilence", skipValue)
                lastSkipSilence = skipValue
            }
            if (bands[0] != lastBands[0] || bands[1] != lastBands[1] ||
                bands[2] != lastBands[2] || bands[3] != lastBands[3]
            ) {
                shader.setFloatUniform("bands0", bands[0], bands[1], bands[2], bands[3])
                for (index in 0..3) lastBands[index] = bands[index]
            }
            if (bands[4] != lastBands[4] || bands[5] != lastBands[5] ||
                bands[6] != lastBands[6] || bands[7] != lastBands[7]
            ) {
                shader.setFloatUniform("bands1", bands[4], bands[5], bands[6], bands[7])
                for (index in 4..7) lastBands[index] = bands[index]
            }
            canvas.drawCircle(
                width * 0.5f,
                height * 0.5f,
                minOf(width, height) * 0.495f,
                paint,
            )
        }
    }

    internal class FallbackRenderer : Renderer {
        override val idleFrameDelayMillis = 33L
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pausedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val silencePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val x = FloatArray(FALLBACK_POINTS)
        private val y = FloatArray(FALLBACK_POINTS)
        private val angles = FloatArray(FALLBACK_POINTS) { index ->
            index.toFloat() / FALLBACK_POINTS * (PI.toFloat() * 2f) - PI.toFloat() / 2f
        }
        private val cosines = FloatArray(FALLBACK_POINTS) { cos(angles[it]) }
        private val sines = FloatArray(FALLBACK_POINTS) { sin(angles[it]) }
        private var primary = 0
        private var tertiary = 0
        private var paused = 0
        private var gradientState = -1

        override fun resize(width: Int, height: Int) {
            gradientState = -1
        }

        override fun setReadoutColorFilter(filter: LightingColorFilter?) {
            paint.colorFilter = filter
            pausedPaint.colorFilter = filter
            silencePaint.colorFilter = filter
        }

        override fun setPalette(primary: Int, tertiary: Int, paused: Int): Boolean {
            if (this.primary == primary && this.tertiary == tertiary && this.paused == paused) return false
            this.primary = primary
            this.tertiary = tertiary
            this.paused = paused
            gradientState = -1
            return true
        }

        override fun draw(
            canvas: Canvas,
            width: Int,
            height: Int,
            timeSeconds: Float,
            activity: Float,
            energy: Float,
            bands: FloatArray,
            life: Float,
            active: Float,
            silence: Float,
        ) {
            if (width <= 0 || height <= 0) return
            val minSize = minOf(width, height).toFloat()
            val cx = width * 0.5f
            val cy = height * 0.5f
            val base = minSize * (0.095f + life * (0.235f + activity * 0.018f))
            val low = bands[0] * .34f + bands[1] * .30f + bands[2] * .21f + bands[3] * .15f
            val high = bands[4] * .34f + bands[5] * .30f + bands[6] * .21f + bands[7] * .15f
            for (index in 0 until FALLBACK_POINTS) {
                val angle = angles[index]
                val h3 = sin(angle * 3f + timeSeconds * .78f)
                val h7 = sin(angle * 7f - timeSeconds * .39f + 1.8f)
                val fold = audioBlobFold(h3, h7, energy, low, high) * minSize
                val radius = base + fold
                x[index] = cx + cosines[index] * radius
                y[index] = cy + sines[index] * radius
            }
            path.reset()
            path.moveTo((x[0] + x[1]) * 0.5f, (y[0] + y[1]) * 0.5f)
            for (index in 1..FALLBACK_POINTS) {
                val current = index % FALLBACK_POINTS
                val next = (index + 1) % FALLBACK_POINTS
                path.quadTo(x[current], y[current], (x[current] + x[next]) * 0.5f, (y[current] + y[next]) * 0.5f)
            }
            path.close()

            val state = if (active == 0f) 0 else 1
            if (gradientState != state) {
                paint.shader = if (active > 0f) {
                    val baseGradient = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), primary, tertiary, Shader.TileMode.CLAMP)
                    val mutedGradient = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
                        silenceBlobMutedColor(primary), silenceBlobMutedColor(tertiary), Shader.TileMode.CLAMP)
                    val drainMask = RadialGradient(cx, cy, minSize * .33f,
                        intArrayOf(silenceBlobDrainMaskColor(0f), silenceBlobDrainMaskColor(.175f),
                            silenceBlobDrainMaskColor(.35f), silenceBlobDrainMaskColor(.525f),
                            silenceBlobDrainMaskColor(.7f), silenceBlobDrainMaskColor(1f)),
                        floatArrayOf(0f, .175f, .35f, .525f, .7f, 1f), Shader.TileMode.CLAMP)
                    silencePaint.shader = ComposeShader(mutedGradient, drainMask, PorterDuff.Mode.DST_IN)
                    baseGradient
                } else {
                    null
                }
                paint.color = if (active > 0f) primary else paused
                gradientState = state
            }
            pausedPaint.color = paused
            canvas.drawPath(path, pausedPaint)
            paint.alpha = (audioBlobColorLife(life, active) * 255f).toInt()
            canvas.drawPath(path, paint)
            if (active > 0f && silence > 0f) {
                // Drain the rim around a small color core using cached shaders and the same geometry.
                silencePaint.alpha = (silence.coerceIn(0f, 1f) * active * 255f).toInt()
                canvas.drawPath(path, silencePaint)
            }
        }
    }

    companion object {
        private const val BAND_COUNT = 8
        private const val FALLBACK_POINTS = 40
        private const val ACTIVE_FRAME_THRESHOLD = 0.018f
        private const val RESIDUAL_FRAME_THRESHOLD = 0.003f
        private const val SIGNAL_ACTIVITY_THRESHOLD = 0.006f
        private const val RECENT_AUDIO_HOLD_NANOS = 480_000_000L
        private const val COLLAPSED_LIFE = 0.38f
        private const val DISABLED_LIFE = 0.36f
        private const val LIFE_EPSILON = 0.006f
        private const val SILENCE_EPSILON = 0.002f

        private const val SHADER_SOURCE = """
            uniform float2 resolution;
            uniform float2 timeAndEnergy;
            uniform float activity;
            uniform float life;
            uniform float active;
            uniform float skipSilence;
            uniform float4 bands0;
            uniform float4 bands1;
            layout(color) uniform half4 primaryColor;
            layout(color) uniform half4 tertiaryColor;
            layout(color) uniform half4 pausedColor;

            half4 main(float2 fragCoord) {
                float minSize = min(resolution.x, resolution.y);
                float2 p = (fragCoord - resolution * 0.5) / minSize;
                float angle = atan(p.y, p.x);
                float radius = length(p);

                float low = dot(bands0, float4(0.34, 0.30, 0.21, 0.15));
                float high = dot(bands1, float4(0.34, 0.30, 0.21, 0.15));
                float h3 = sin(angle * 3.0 + timeAndEnergy.x * 0.78);
                float h7 = sin(angle * 7.0 - timeAndEnergy.x * 0.39 + 1.8);
                float fold = timeAndEnergy.y * (h3 * (0.035 + 0.035 * low) + h7 * (0.008 + 0.018 * high));

                float liveRadius = 0.330 + activity * 0.018;
                float baseRadius = mix(0.095, liveRadius, life);
                float blobRadius = baseRadius + fold;
                float distanceToEdge = radius - blobRadius;

                float body = smoothstep(0.012, -0.006, distanceToEdge);
                float glow = active * life * (1.0 - body) *
                    smoothstep(0.024, 0.0, max(distanceToEdge, 0.0)) *
                    (0.055 + activity * 0.12);

                float gradientMix = clamp(0.46 + p.x * 0.9 - p.y * 0.55, 0.0, 1.0);
                half4 activeColor = mix(primaryColor, tertiaryColor, half(gradientMix));
                float colorLife = smoothstep(0.36, 0.86, life) * active;
                half4 bodyColor = mix(pausedColor, activeColor, half(colorLife));
                float drain = skipSilence * active * colorLife *
                    (0.35 + 0.65 * smoothstep(0.0, 0.7, radius / baseRadius));
                half luminance = dot(bodyColor.rgb, half3(0.2126, 0.7152, 0.0722));
                half3 mutedColor = mix(bodyColor.rgb, half3(luminance), half(0.42)) * half(0.72);
                bodyColor.rgb = mix(bodyColor.rgb, mutedColor, half(drain));
                half4 glowColor = mix(primaryColor, tertiaryColor, half(0.58));

                float alpha = body + glow;
                half3 premultiplied = bodyColor.rgb * half(body) + glowColor.rgb * half(glow);
                return half4(premultiplied, half(alpha));
            }
        """
    }
}
