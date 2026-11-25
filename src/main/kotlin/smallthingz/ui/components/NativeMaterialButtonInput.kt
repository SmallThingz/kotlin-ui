package smallthingz.ui.components

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import smallthingz.ui.geometry.roundedGroupGeometry
import smallthingz.ui.input.phase.NativeClickPhaseEndpoint
import smallthingz.ui.input.phase.ViewClickPhaseEndpoint
import smallthingz.ui.interaction.view.ButtonElevationInteraction
import smallthingz.ui.interaction.view.ButtonElevationToken
import smallthingz.ui.interaction.view.FilledButtonElevation
import smallthingz.ui.interaction.view.configureButtonShadowHost
import smallthingz.ui.interaction.view.NativeClickBinding
import smallthingz.ui.interaction.view.NativeClickEnvironment
import smallthingz.ui.interaction.view.NativeClickIndication
import smallthingz.ui.interaction.view.NativePressToken
import smallthingz.ui.interaction.view.NativeStateToken
import smallthingz.ui.interaction.view.RowIndication
import smallthingz.ui.interaction.view.RowIndicationSink
import smallthingz.ui.interaction.view.ViewClickController
import smallthingz.ui.interaction.view.readCachedAnimatorDurationScale
import java.util.IdentityHashMap
import kotlin.math.min

/** Optional native window and gesture ownership for a shared button. */
class NativeButtonInputPolicy(
    val inputRoot: (() -> View?)? = null,
    val revisionKey: (() -> Any?)? = null,
    val delayPressForParent: Boolean = false,
) {
    internal companion object { val Default = NativeButtonInputPolicy() }
}

internal class NativeMaterialButtonInput(
    private val inputHost: View,
    private val indicationHost: View,
    private var radiusDp: Float?,
    private val minimumTouchTargetDp: Int,
    filled: Boolean,
    private val policy: NativeButtonInputPolicy,
    setElevationPx: (Float) -> Unit,
) {
    private val indication = RowIndication.install(
        indicationHost, android.graphics.Color.BLACK, { readCachedAnimatorDurationScale(inputHost.context) },
    )
    private val shadow = when {
        radiusDp != null -> null
        filled -> FilledButtonElevation(inputHost, inputHost.isEnabled, setElevationPx)
        else -> {
            configureButtonShadowHost(inputHost)
            setElevationPx(0f)
            null
        }
    }
    private val inputLocation = IntArray(2)
    private val indicationLocation = IntArray(2)
    private val ripple = RowIndicationSink(indication) { x, y, output ->
        inputHost.getLocationInWindow(inputLocation)
        indicationHost.getLocationInWindow(indicationLocation)
        output.set(x + inputLocation[0] - indicationLocation[0], y + inputLocation[1] - indicationLocation[1])
    }
    private val elevationTokens = shadow?.let { IdentityHashMap<Any, ButtonElevationToken>() }
    private val sink = if (shadow == null || elevationTokens == null) ripple else object : NativeClickIndication {
        override fun press(token: NativePressToken) {
            ripple.press(token)
            shadow.begin(ButtonElevationInteraction.PRESS)?.let { elevationTokens[token] = it }
        }
        override fun release(token: NativePressToken) {
            ripple.release(token)
            shadow.end(elevationTokens.remove(token))
        }
        override fun cancel(token: NativePressToken) {
            ripple.cancel(token)
            shadow.end(elevationTokens.remove(token))
        }
        override fun stateEnter(token: NativeStateToken) {
            ripple.stateEnter(token)
            val kind = when (token.kind) {
                NativeStateToken.Kind.HOVER -> ButtonElevationInteraction.HOVER
                NativeStateToken.Kind.FOCUS -> ButtonElevationInteraction.FOCUS
            }
            shadow.begin(kind)?.let { elevationTokens[token] = it }
        }
        override fun stateExit(token: NativeStateToken) {
            ripple.stateExit(token)
            shadow.end(elevationTokens.remove(token))
        }
    }
    private val environment = object : NativeClickEnvironment {
        override val nativeInputRoot: View? get() = policy.inputRoot?.invoke()
        override val minimumTouchWidthPx: Float get() = minimumTouchTargetDp * inputHost.resources.displayMetrics.density
        override val minimumTouchHeightPx: Float get() = minimumTouchWidthPx
        override fun isWithinStandaloneFingerTarget(x: Float, y: Float): Boolean? =
            (inputHost.parent as? NativeButtonTouchTarget)?.containsFingerTarget(x, y)

        override fun admitDown(event: MotionEvent): Boolean {
            val density = inputHost.resources.displayMetrics.density
            val radius = radiusDp ?: min(inputHost.width, inputHost.height) / (2f * density)
            val geometry = roundedGroupGeometry(inputHost.width.toFloat(), inputHost.height.toFloat(), density, radius)
            val finger = event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
            val target = if (finger) inputHost.parent as? NativeButtonTouchTarget else null
            if (target == null) return geometry.acceptsPointer(event.x, event.y, finger, minimumTouchWidthPx)
            return target.containsFingerTarget(event.x, event.y) &&
                (target.expandsFingerTarget || geometry.contains(event.x, event.y))
        }
        // Standalone fixture fallback; native root phase delivery supplies per-change interest.
        override fun interestedParent(event: MotionEvent): Boolean = policy.delayPressForParent
        // A button owns ordinary activation only.
        override fun longPressOwnershipChanged(owns: Boolean) = Unit
    }
    private val input = ViewClickController(inputHost, environment, sink)
    val pointerEndpoint: NativeClickPhaseEndpoint = ViewClickPhaseEndpoint(input)
    private var enabled = inputHost.isEnabled

    fun bind(enabled: Boolean, contentColor: Int) {
        this.enabled = enabled
        // Preserve ButtonElevation's target-key behavior before disable emits cancellation ends.
        shadow?.bindEnabled(enabled)
        indication.updateColor(contentColor)
        bindInput()
    }
    fun useSquareSurface() {
        if (radiusDp == 0f) return
        input.cancel()
        shadow?.close()
        elevationTokens?.clear()
        radiusDp = 0f
    }
    fun measured() {
        indication.onMeasured(indicationHost.width, indicationHost.height, indicationHost.resources.displayMetrics.density)
        shadow?.measured()
    }
    fun touch(event: MotionEvent): Boolean = input.touch(event)
    fun key(event: KeyEvent): Boolean = input.key(event)
    fun hover(event: MotionEvent): Boolean = input.hover(event)
    fun focusChanged(focused: Boolean) = input.focusChanged(focused)
    fun cancel() = input.cancel()
    fun attached() {
        input.attached()
        bindInput()
        shadow?.attached()
    }
    private val click: () -> Unit = { inputHost.performClick() }
    private fun bindInput() {
        val owner = if (policy.revisionKey == null) inputHost else policy.revisionKey.invoke()
        input.bind(owner?.let { NativeClickBinding(it, enabled, onClick = click) })
    }
    fun detached() {
        input.detached()
        elevationTokens?.clear()
        shadow?.detached()
    }
}
