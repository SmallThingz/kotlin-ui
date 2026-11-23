package smallthingz.ui.reverb

import android.content.Context
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import smallthingz.ui.text.view.NativeSourceAccessibilityNode
import smallthingz.ui.text.view.PlainTextAccessibility
import smallthingz.ui.text.view.PlainTextAccessibilitySnapshot
import smallthingz.ui.text.view.PlainTextAccessibilityWindowScope
import smallthingz.ui.animation.view.ViewColorTransition

class ReverbGlyphView(
    context: Context,
    icon: ReverbIcon,
    color: Int,
) : View(context) {
    private var descriptionWindow: PlainTextAccessibilityWindowScope? = null
    private var descriptionAccessibility: PlainTextAccessibility? = null
    private var descriptionExposure: (() -> Boolean)? = null
    private var descriptionToken: Any? = null
    private var sourceDescriptionNode: (() -> NativeSourceAccessibilityNode)? = null
    private var currentIcon = icon
    private var glyph = ReverbIconDrawable(icon, resources.displayMetrics.density, color)
    private var displayedColor=color
    private var publishedTint=color
    var useTextSurfaceContrast=false
        set(value) { if(field!=value){field=value;invalidate()} }
    private var colorTransition:ViewColorTransition?=null
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; isFocusable = false }
    fun bind(icon: ReverbIcon, color: Int) {
        if (currentIcon != icon) {
            currentIcon = icon
            glyph = ReverbIconDrawable(icon, resources.displayMetrics.density, color)
            publishedTint=color
            glyph.setBounds(0, 0, width, height)
        }
        glyph.layoutDirection = layoutDirection
        val motion=colorTransition ?: ViewColorTransition(this,intArrayOf(displayedColor),{applyTint()})
            .also {colorTransition=it}
        motion.retarget(color)
        applyTint()
        invalidate()
    }
    private fun publishTint() {
        val ink = if (!useTextSurfaceContrast || colorTransition == null) displayedColor else
            smallthingz.ui.controls.textSurfaceForeground(ReverbTextSurface.sample(this),displayedColor)
        if (publishedTint != ink) { publishedTint=ink;glyph.setTint(ink) }
    }
    private fun applyTint() {colorTransition?.let {displayedColor=it.color(0)};publishTint();invalidate()}
    fun setTintImmediately(color:Int) {
        colorTransition?.close();colorTransition=null;displayedColor=color;applyTint()
    }
    fun bindImmediately(icon:ReverbIcon,color:Int) {
        if(currentIcon!=icon) {
            currentIcon=icon;glyph=ReverbIconDrawable(icon,resources.displayMetrics.density,color);publishedTint=color
            glyph.setBounds(0,0,width,height)
        }
        glyph.layoutDirection=layoutDirection
        setTintImmediately(color)
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { glyph.setBounds(0, 0, w, h) }
    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        glyph.layoutDirection = layoutDirection
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        publishTint()
        glyph.draw(canvas)
    }

    fun bindDescriptionAccessibility(window: PlainTextAccessibilityWindowScope, exposed: () -> Boolean): AutoCloseable =
        bindDescriptionAccessibility(window, null, exposed)

    fun bindDescriptionAccessibility(window: PlainTextAccessibilityWindowScope,
                                     sourceNode: (() -> NativeSourceAccessibilityNode)?,
                                     exposed: () -> Boolean): AutoCloseable {
        val token = Any()
        descriptionToken = token
        descriptionExposure = exposed
        sourceDescriptionNode = sourceNode
        if (descriptionWindow !== window || descriptionAccessibility == null) {
            descriptionWindow = window
            descriptionAccessibility = PlainTextAccessibility(this, window,
                snapshot = { PlainTextAccessibilitySnapshot(contentDescription?.toString().orEmpty(), { null }, { null }) },
                exposed = { descriptionExposure?.invoke() == true }, descriptionOnly = true)
        }
        return AutoCloseable {
            if (descriptionToken === token) {
                descriptionToken = null
                descriptionWindow = null
                descriptionAccessibility = null
                descriptionExposure = null
                sourceDescriptionNode = null
            }
        }
    }
    override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo? =
        if (sourceDescriptionNode?.invoke()?.present == false) null else super.createAccessibilityNodeInfo()

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (contentDescription.isNullOrEmpty()) return
        // Source Image role remains generic under a merging row; its description is separate.
        info.className = "android.view.View"
        info.text = null
        info.isScreenReaderFocusable = false
        info.isFocusable = false
        info.isClickable = false
        info.isEnabled = true
        val actions = info.actionList
        for (index in actions.size - 1 downTo 0) info.removeAction(actions[index])
        info.addAction(if (isAccessibilityFocused) AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
            else AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
        descriptionAccessibility?.populate(info)
        sourceDescriptionNode?.invoke()?.populate(info)
    }
    override fun performAccessibilityAction(action: Int, args: Bundle?): Boolean =
        descriptionAccessibility?.performAction(action, args) ?: super.performAccessibilityAction(action, args)
    override fun onInitializeAccessibilityEvent(event: AccessibilityEvent) {
        super.onInitializeAccessibilityEvent(event)
        if (!contentDescription.isNullOrEmpty()) {
            event.className = "android.view.View"
            event.isEnabled = true
            event.text.clear()
            event.contentDescription = null
        }
    }
}
