package smallthingz.ui.components

import android.content.Context
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import smallthingz.ui.input.DEFAULT_MINIMUM_TOUCH_TARGET_DP
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density
import kotlin.math.abs
import kotlin.math.roundToInt

/** The shared sheet knob for a dialog that owns its cancellation and window policy. */
fun createNativeDialogDragHandle(
    context: Context,
    colors: NativeColorScheme,
    appearance: NativeUiAppearance,
    runtime: NativeModalWindowRuntime,
    onDismiss: () -> Unit,
): View {
    val density = Density(context)
    val style = appearance.actionSheetStyle(density, createFontFamilyResolver(context), colors)
    val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    val dismissDistance = maxOf(touchSlop * 2f, 24f * density.density)
    return object : FrameLayout(context) {
        private val drag = NativeDialogDragDismiss(dismissDistance, onDismiss)

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            minimumWidth = (DEFAULT_MINIMUM_TOUCH_TARGET_DP * density.density).roundToInt()
            minimumHeight = minimumWidth
            addView(
                NativeSheetDragHandle(context, style, { this }, runtime, onDismiss, onDismiss),
                LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER),
            )
        }

        // The knob owns its full touch target. Keyboard and accessibility reach the child directly.
        override fun onInterceptTouchEvent(event: MotionEvent): Boolean = true

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val obscured = event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED != 0 ||
                (Build.VERSION.SDK_INT >= 29 && event.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED != 0)
            drag.handle(event.actionMasked, obscured, event.rawX, event.rawY)
            return true
        }
    }
}

/** The knob only commits an unobscured, single-pointer downward release. */
internal class NativeDialogDragDismiss(
    private val dismissDistance: Float,
    private val onDismiss: () -> Unit,
) {
    private var started = false
    private var downX = 0f
    private var downY = 0f

    fun handle(action: Int, obscured: Boolean, x: Float, y: Float) {
        if (obscured) {
            started = false
            return
        }
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                started = true
                downX = x
                downY = y
            }
            MotionEvent.ACTION_UP -> {
                val distance = y - downY
                val dismiss = started && distance >= dismissDistance && distance > abs(x - downX)
                started = false
                if (dismiss) onDismiss()
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> started = false
        }
    }
}
