package smallthingz.ui.components

import android.graphics.drawable.ColorDrawable
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import smallthingz.ui.animation.core.Animatable
import smallthingz.ui.animation.core.tween
import smallthingz.ui.animation.view.ViewColorTransition
import smallthingz.ui.interaction.view.readCachedAnimatorDurationScale

/** Solid or textured retained toolbar fill. Only the fill color animates. */
class NativeToolbarBackground(
    view: View,
    style: NativeToolbarStyle,
    initialColor: Int,
    onPaintChanged: () -> Unit = {},
) : NativeBackdropBinding(view, style.grainSeed, initialColor, onPaintChanged) {
    fun bind(style: NativeToolbarStyle, color: Int, grainIntensity: Float = 1f) =
        bind(style.grainSeed, color, grainIntensity)
}

/** Pulse a set of toolbar actions for a new caller-owned hint token. */
class NativeToolbarHint(
    private val owner: View,
    private val scope: CoroutineScope,
    private var style: NativeToolbarStyle,
    initialColor: Int,
    vararg actions: View,
) : AutoCloseable {
    private val overlays = Array(actions.size) { index -> ColorDrawable().also { actions[index].background = it } }
    private var token: Any? = null
    private var job: Job? = null
    private var strength = 0f
    private val progress = Animatable(0f)
    private val color: ViewColorTransition = ViewColorTransition(owner, intArrayOf(initialColor), ::publish)

    fun bind(style: NativeToolbarStyle, tint: Int, hint: Any?) {
        this.style = style
        color.retarget(tint)
        publish()
        if (token === hint) return
        token = hint
        clearPulse()
        if (hint != null) job = scope.launch {
            try {
                progress.snapTo(1f)
                paint(1f)
                if (readCachedAnimatorDurationScale(owner.context) == 0f) delay(style.hintDurationMillis.toLong())
                else progress.animateTo(0f, tween(style.hintDurationMillis)) { paint(value) }
            } finally {
                if (token === hint) paint(0f)
            }
        }
    }

    fun clearPulse() {
        job?.cancel()
        job = null
        paint(0f)
    }

    private fun paint(value: Float) {
        strength = value
        publish()
    }

    private fun publish() {
        val tint = srgbColorWithAlpha(color.color(0), strength.coerceIn(0f, 1f) * style.hintAlpha)
        for (overlay in overlays) overlay.color = tint
    }

    override fun close() {
        token = null
        clearPulse()
    }
}
