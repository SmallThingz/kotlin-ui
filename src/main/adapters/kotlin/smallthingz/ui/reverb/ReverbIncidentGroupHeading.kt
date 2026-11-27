// Reverb application presentation source, retained under the Reverb project GPL-3.0 license.

package smallthingz.ui.reverb

import android.content.Context
import android.graphics.Rect
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import smallthingz.ui.R
import smallthingz.ui.components.NativeGroupEdges
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.input.phase.NativeClickPhaseHost
import smallthingz.ui.input.phase.NativePointerLayerShape
import smallthingz.ui.input.phase.ViewClickPhaseEndpoint
import smallthingz.ui.interaction.view.NativeClickBinding
import smallthingz.ui.interaction.view.NativeClickEnvironment
import smallthingz.ui.interaction.view.RowIndication
import smallthingz.ui.interaction.view.RowIndicationSink
import smallthingz.ui.interaction.view.ViewClickController
import smallthingz.ui.interaction.view.isNativeClickActivationKey
import smallthingz.ui.interaction.view.readCachedAnimatorDurationScale
import smallthingz.ui.text.font.FontWeight
import smallthingz.ui.text.view.MergedRowAccessibility
import smallthingz.ui.text.view.NativeMergedRowAccessibilityHost
import smallthingz.ui.text.view.NativeMergedRowAccessibilityState
import smallthingz.ui.text.view.NativeMergedRowRole
import smallthingz.ui.text.view.ParagraphView
import kotlin.math.roundToInt

/** A compact joined heading, with one native phase/keyboard/semantic long-press owner. */
internal class ReverbIncidentGroupHeading(
    context: Context,
    initial: IncidentCardAppearance,
    inputRoot: View,
    private val selectError: (Long, String) -> Boolean,
) : FrameLayout(context), NativeClickPhaseHost, NativePointerLayerShape, NativeMergedRowAccessibilityHost {
    private var appearance = initial
    private data class Owner(val day: Long, val key: String)
    private var ownerKey: Owner? = null
    private var canMutate = false
    private val surface = initial.style.surface.create(context, ReverbTextSurface::invalidate)
    private val title = ParagraphView(context, initial.bodyMedium, initial.density,
        initial.fontResolver, initial.palette.ink)
    private val children = listOf<View>(title)
    private val indication = RowIndication.install(surface, initial.palette.ink) {
        readCachedAnimatorDurationScale(context)
    }
    private val input = ViewClickController(this, object : NativeClickEnvironment {
        override val nativeInputRoot = inputRoot
        override val minimumTouchWidthPx get() = 48f * appearance.density.density
        override val minimumTouchHeightPx get() = minimumTouchWidthPx
        override fun admitDown(event: MotionEvent) = surface.acceptsVisualPointer(event.x, event.y,
            event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER, minimumTouchWidthPx)
        override fun interestedParent(event: MotionEvent) = true
        override fun longPressOwnershipChanged(owns: Boolean) {
            parent?.requestDisallowInterceptTouchEvent(owns)
        }
    }, RowIndicationSink(indication) { x, y, out -> out.set(x, y) })
    override val nativeClickEndpoint = ViewClickPhaseEndpoint(input)
    override val mergedRowAccessibility = MergedRowAccessibility(this, NativeMergedRowRole.BUTTON, {
        NativeMergedRowAccessibilityState(canMutate, null, null, children,
            clickable = false, longClickable = canMutate,
            longClickLabel = context.getString(R.string.reverb_incident_select_error),
            rolePresent = false, readOnlyScreenReaderFocusable = true)
    }, { false }, ::select)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityDelegate = mergedRowAccessibility
        ViewCompat.setAccessibilityHeading(this, true)
        ViewCompat.setScreenReaderFocusable(this, true)
        surface.addRow(title, NativeGroupLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        setOnLongClickListener { select() }
    }

    private fun select(): Boolean = isAttachedToWindow && canMutate &&
        ownerKey?.let { selectError(it.day, it.key) } == true

    fun bind(value: IncidentCardAppearance, day: Long, key: String, message: String,
             edges: NativeGroupEdges, mutable: Boolean) {
        appearance = value
        ownerKey = Owner(day, key)
        canMutate = mutable
        isLongClickable = mutable
        isFocusable = mutable
        bindInput()
        indication.updateColor(value.palette.ink)
        val border = value.style.surface.borderColor(value.palette)
        surface.setJoinedEdges(edges)
        surface.setJoinedSeparatorColor(border)
        surface.setSurfacePreset(value.palette.surfacePreset)
        surface.setPaletteImmediately(value.style.surface.fillColor(value.palette), border)
        val horizontal = (12f * value.density.density).roundToInt()
        title.setPaddingRelative(horizontal, (8f * value.density.density).roundToInt(),
            horizontal, (5f * value.density.density).roundToInt())
        title.setTypography(value.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            value.density, value.fontResolver)
        title.setTextColor(value.palette.ink)
        title.setText(message)
    }

    private fun bindInput() {
        input.bind(ownerKey?.let { NativeClickBinding(it, canMutate, onClick = {}, onLongClick = { select() }) })
        input.focusChanged(isFocused)
    }

    fun unbind() {
        input.bind(null)
        ownerKey = null
        canMutate = false
        isLongClickable = false
        isFocusable = false
    }

    override fun containsPointerLayer(x: Float, y: Float) = surface.containsVisualPoint(x, y)
    override fun onTouchEvent(event: MotionEvent) = input.touch(event)
    override fun onKeyDown(keyCode: Int, event: KeyEvent) =
        if (isNativeClickActivationKey(keyCode)) input.key(event) else super.onKeyDown(keyCode, event)
    override fun onKeyUp(keyCode: Int, event: KeyEvent) =
        if (isNativeClickActivationKey(keyCode)) input.key(event) else super.onKeyUp(keyCode, event)
    override fun onHoverEvent(event: MotionEvent): Boolean {
        val handled = super.onHoverEvent(event)
        return input.hover(event) || handled
    }
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        input.focusChanged(gainFocus)
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        indication.onMeasured(w, h, appearance.density.density)
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        input.attached()
        bindInput()
    }
    override fun onDetachedFromWindow() {
        input.detached()
        super.onDetachedFromWindow()
    }
    override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo? =
        if (mergedRowAccessibility.isNodePresent()) super.createAccessibilityNodeInfo() else null
    override fun addChildrenForAccessibility(children: ArrayList<View>) =
        mergedRowAccessibility.addChildrenForAccessibility(children)
}
