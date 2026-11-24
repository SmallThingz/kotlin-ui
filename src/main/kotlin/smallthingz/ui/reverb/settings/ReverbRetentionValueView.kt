package smallthingz.ui.reverb.settings

import android.content.Context
import android.view.View
import android.view.ViewGroup
import smallthingz.ui.components.atMost
import smallthingz.ui.components.exact
import smallthingz.ui.components.unspecified
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.reverb.ReverbGlyphView
import smallthingz.ui.reverb.ReverbIcon
import smallthingz.ui.reverb.ReverbTextRole
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** RetentionValue2258–2352; editor remains the actual native raw-text/IME owner supplied by host. */
open class ReverbRetentionValueView(
    context: Context,
    private val glyph: ReverbIcon,
    label: String,
    unitText: String,
    private val editor: View,
    private var environment: ReverbEnvironment,
) : ViewGroup(context), smallthingz.ui.input.NativeAndroidInteropHost {
    override val nativeInteropRequests = smallthingz.ui.input.NativeAndroidInteropRequests()
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        nativeInteropRequests.request(disallowIntercept)
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    private val icon = ReverbGlyphView(context, glyph, environment.palette.muted)
    private val heading = environment.paragraph(context, label, ReverbTextRole.BODY_LARGE, environment.palette.muted)
    private val unit = environment.paragraph(
        context, unitText, ReverbTextRole.BODY_LARGE, environment.palette.muted
    ).apply {
        visibility = GONE
    }
    private val estimate = environment.paragraph(context, "", ReverbTextRole.BODY_LARGE, environment.palette.muted)
    private var time = true
    private var headerHeight = 0
    private var headerGap = 0
    private var valueHeight = 0
    private var valueGap = 0
    private var editorWidth = 0

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        icon.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        icon.useTextSurfaceContrast = true
        if (editor.id == View.NO_ID) editor.id = View.generateViewId()
        heading.labelFor = editor.id
        addView(icon)
        addView(heading)
        addView(editor)
        addView(unit)
        addView(estimate)
        bindTypography()
    }

    fun bind(mode: ReverbRetentionMode, estimateText: String, next: ReverbEnvironment) {
        val typographyChanged = environment !== next
        val modeChanged = time != (mode == ReverbRetentionMode.TIME)
        environment = next
        time = mode == ReverbRetentionMode.TIME
        unit.visibility = if (time) GONE else VISIBLE
        estimate.setText(estimateText)
        if (typographyChanged) bindTypography()
        if (typographyChanged || modeChanged) requestLayout()
    }

    internal fun setNumericOffset(offset: Float) {
        editor.translationY = offset
        unit.translationY = offset
        estimate.translationY = offset
    }

    private fun bindTypography() {
        val typography = environment.appearance.readoutTypography
        heading.setTypography(typography.retentionLabel, environment.textDensity, environment.sharedFontResolver)
        unit.setTypography(typography.retentionLabel, environment.textDensity, environment.sharedFontResolver)
        estimate.setTypography(typography.retentionEstimate, environment.textDensity, environment.sharedFontResolver)
        heading.animateTextColor(environment.palette.muted)
        unit.animateTextColor(environment.palette.muted)
        estimate.animateTextColor(environment.palette.muted)
        icon.bind(glyph, environment.palette.muted)
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        icon.measure(exact(min(dp(17), width)), exact(dp(17)))
        headerGap = min(dp(8), (width - icon.measuredWidth).coerceAtLeast(0))
        heading.measure(atMost((width - icon.measuredWidth - headerGap).coerceAtLeast(0)), unspecified())
        headerHeight = max(icon.measuredHeight, heading.measuredHeight)
        if (!time) unit.measure(atMost(width), unspecified())
        valueGap = if (time) 0 else min(dp(7), (width - unit.measuredWidth).coerceAtLeast(0))
        editorWidth = (width - (if (time) 0 else unit.measuredWidth) - valueGap).coerceAtLeast(0)
        editor.measure(exact(editorWidth), unspecified())
        valueHeight = max(editor.measuredHeight, if (time) 0 else unit.measuredHeight + dp(6))
        estimate.measure(atMost(width), unspecified())
        setMeasuredDimension(
            resolveSize(width, w),
            resolveSize(headerHeight + dp(10) + valueHeight + dp(3) + estimate.measuredHeight, h)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        place(icon, 0, ((headerHeight - icon.measuredHeight) / 2f).roundToInt())
        place(heading, icon.measuredWidth + headerGap, ((headerHeight - heading.measuredHeight) / 2f).roundToInt())
        val valueTop = headerHeight + dp(10)
        place(editor, 0, valueTop + valueHeight - editor.measuredHeight)
        if (!time) place(unit, editorWidth + valueGap, valueTop + valueHeight - unit.measuredHeight - dp(6))
        place(estimate, 0, valueTop + valueHeight + dp(3))
    }

    private fun place(view: View, start: Int, y: Int) {
        val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - start - view.measuredWidth else start
        view.layout(x, y, x + view.measuredWidth, y + view.measuredHeight)
    }

    private fun dp(v: Int) = (v * environment.textDensity.density).roundToInt()
}
