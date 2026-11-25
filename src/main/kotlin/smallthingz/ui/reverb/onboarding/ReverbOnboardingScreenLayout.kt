package smallthingz.ui.reverb.onboarding

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import kotlinx.coroutines.CoroutineScope
import smallthingz.ui.MotionDurationScale
import smallthingz.ui.components.NativeCircularProgress
import smallthingz.ui.geometry.Offset
import smallthingz.ui.geometry.Rect as ComposeRect
import smallthingz.ui.geometry.Size
import smallthingz.ui.geometry.roundedGroupContains
import smallthingz.ui.reverb.ReverbAppFeedbackHostView
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.reverb.ReverbPalette
import smallthingz.ui.reverb.ReverbTextRole
import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.text.view.NativePlainTextViewGeometry
import smallthingz.ui.text.view.NativeSourceAccessibilityNode
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.text.view.PlainTextAccessibilityGeometry
import smallthingz.ui.text.view.nativeMergedRoleBoundsInScreen
import smallthingz.ui.unit.Density
import smallthingz.ui.unit.dp
import kotlin.math.roundToInt

/** Reverb geometry for the page stack, stationary footer, loading indicator, and feedback. */
class ReverbOnboardingScreenLayout(private val density: Density) {
    private var left = 0
    private var top = 0
    private var pageHeight = 0

    fun measure(width: Int, height: Int, insets: Rect, pageArea: View, footer: View) {
        val horizontal = with(density) { 24.dp.roundToPx() }
        val vertical = with(density) { 20.dp.roundToPx() }
        left = insets.left + horizontal
        top = insets.top + vertical
        val contentWidth = (width - insets.left - insets.right - 2 * horizontal).coerceAtLeast(0)
        val contentHeight = (height - insets.top - insets.bottom - 2 * vertical).coerceAtLeast(0)
        footer.measure(exact(contentWidth), MeasureSpec.makeMeasureSpec(contentHeight, MeasureSpec.AT_MOST))
        pageHeight = (contentHeight - footer.measuredHeight).coerceAtLeast(0)
        pageArea.measure(exact(contentWidth), exact(pageHeight))
    }

    fun layout(pageArea: View, footer: View) {
        pageArea.layout(left, top, left + pageArea.measuredWidth, top + pageHeight)
        val footerTop = top + pageHeight
        footer.layout(left, footerTop, left + footer.measuredWidth, footerTop + footer.measuredHeight)
    }

    companion object {
        private const val FEEDBACK_HORIZONTAL_DP = 18
        private const val FEEDBACK_BOTTOM_DP = 84
        /** Legacy factory default retained for callers without a window palette. */
        val overscrollColor: Int = 0xff666666.toInt()

        fun overscrollColor(palette: ReverbPalette): Int = palette.muted

        fun createPageArea(context: Context): FrameLayout = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
        }

        fun typography(environment: ReverbEnvironment): OnboardingPageTypography = environment.appearance.typography.let {
            OnboardingPageTypography(it[ReverbTextRole.HEADLINE_MEDIUM], it[ReverbTextRole.TITLE_MEDIUM],
                it[ReverbTextRole.BODY_LARGE], it[ReverbTextRole.BODY_MEDIUM], it[ReverbTextRole.BODY_SMALL])
        }

        fun applyPredictiveBack(viewport: View, preview: View?, pageWidth: Int, direction: Float, progress: Float) {
            viewport.translationX = direction * pageWidth * progress
            viewport.alpha = 1f - .05f * progress
            preview?.let {
                it.translationX = -direction * pageWidth * .08f * (1f - progress)
                it.scaleX = .97f + .03f * progress
                it.scaleY = it.scaleX
                it.alpha = (progress * 1.45f).coerceIn(0f, 1f)
            }
        }

        fun createLoading(context: Context, environment: ReverbEnvironment, scope: CoroutineScope): NativeCircularProgress =
            NativeCircularProgress(context, environment.palette.primary,
                requireNotNull(scope.coroutineContext[MonotonicFrameClock]), scope,
                { scope.coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f }, strokeWidthDp = 4f).apply {
                bindSurfacePreset(environment.palette.surfacePreset)
            }

        fun bindLoading(indicator: NativeCircularProgress, environment: ReverbEnvironment) {
            indicator.bindSurfacePreset(environment.palette.surfacePreset)
            indicator.setColor(environment.palette.primary)
        }

        fun measureLoading(indicator: View, width: Int, height: Int, density: Float) {
            val size = (40 * density).roundToInt()
            indicator.measure(exact(minOf(width, size)), exact(minOf(height, size)))
        }

        fun layoutLoading(indicator: View, width: Int, height: Int) {
            val x = ((width - indicator.measuredWidth) * .5f).roundToInt()
            val y = ((height - indicator.measuredHeight) * .5f).roundToInt()
            indicator.layout(x, y, x + indicator.measuredWidth, y + indicator.measuredHeight)
        }

        fun measureFeedback(feedback: ReverbAppFeedbackHostView, width: Int, height: Int, density: Float, safe: Insets) {
            val horizontal = (FEEDBACK_HORIZONTAL_DP * density).roundToInt()
            val bottom = (FEEDBACK_BOTTOM_DP * density).roundToInt()
            val contentWidth = (width - safe.left - safe.right - horizontal * 2).coerceAtLeast(0)
            val contentHeight = (height - safe.top - safe.bottom - bottom).coerceAtLeast(0)
            feedback.measure(atMost(contentWidth), atMost(contentHeight))
        }

        fun layoutFeedback(feedback: ReverbAppFeedbackHostView, width: Int, height: Int, density: Float, safe: Insets) {
            val horizontal = (FEEDBACK_HORIZONTAL_DP * density).roundToInt()
            val bottom = height - safe.bottom - (FEEDBACK_BOTTOM_DP * density).roundToInt()
            val contentWidth = (width - safe.left - safe.right - horizontal * 2).coerceAtLeast(0)
            val x = safe.left + horizontal + ((contentWidth - feedback.measuredWidth) * .5f).roundToInt()
            feedback.layout(x, bottom - feedback.measuredHeight, x + feedback.measuredWidth, bottom)
        }

        /** The passive card's one important text leaf provides onboarding's feedback occlusion. */
        fun feedbackOcclusion(
            root: View,
            geometry: NativePlainTextViewGeometry,
            feedback: ReverbAppFeedbackHostView?,
        ): List<Rect> {
            val text = feedback?.feedbackText ?: return emptyList()
            var ancestor: View? = text
            while (ancestor != null && ancestor !== root) {
                if (ancestor.visibility != View.VISIBLE || ancestor.alpha <= 0f) return emptyList()
                ancestor = ancestor.parent as? View
            }
            if (ancestor !== root || root.alpha <= 0f) return emptyList()
            val bounds = captureFeedback(root, geometry, text)?.visibleBoundsInRoot ?: return emptyList()
            return listOf(Rect(bounds.left.roundToInt(), bounds.top.roundToInt(),
                bounds.right.roundToInt(), bounds.bottom.roundToInt()))
        }

        fun feedbackSourceNode(root: View, geometry: NativePlainTextViewGeometry, view: View): NativeSourceAccessibilityNode {
            val bounds = captureFeedback(root, geometry, view) ?: return NativeSourceAccessibilityNode.absent()
            var hidden = false
            var ancestor: View? = view
            while (ancestor != null) {
                if (ancestor.alpha <= 0f) {
                    hidden = true
                    break
                }
                if (ancestor === root) break
                ancestor = ancestor.parent as? View
            }
            return NativeSourceAccessibilityNode(true, hidden, !hidden, nativeMergedRoleBoundsInScreen(bounds, true))
        }

        private fun captureFeedback(root: View, geometry: NativePlainTextViewGeometry, view: View): PlainTextAccessibilityGeometry? {
            if (!view.isAttachedToWindow || !view.isLaidOut || view.visibility != View.VISIBLE) return null
            val local = if (view is ParagraphView) view.originalAccessibilityBoundsInView()
                else ComposeRect(0f, 0f, view.width.toFloat(), view.height.toFloat())
            val mapped = geometry.capture(view, Offset.Zero, local) ?: return null
            val visible = mapped.visibleBoundsInRoot.intersect(ComposeRect(0f, 0f, root.width.toFloat(), root.height.toFloat()))
            if (visible.isEmpty) return null
            return if (visible == mapped.visibleBoundsInRoot) mapped
                else PlainTextAccessibilityGeometry(mapped.paragraphPositionInRoot, visible, mapped.rootToScreen)
        }

        fun minimumTouchTarget(density: Density): Size = Size(48f * density.density, 48f * density.density)

        fun pagerHitContains(width: Float, height: Float, density: Density, point: Offset): Boolean {
            val margin = with(density) { 30.dp.roundToPx() }.toFloat()
            return point.x >= 0f && point.x < width && point.y >= -margin && point.y < height + margin
        }

        fun pageScrollHitContains(width: Float, height: Float, density: Density, point: Offset): Boolean {
            val margin = with(density) { 30.dp.roundToPx() }.toFloat()
            return point.y >= 0f && point.y < height && point.x >= -margin && point.x < width + margin
        }

        fun groupRowHitContains(width: Float, height: Float, density: Density, point: Offset): Boolean =
            roundedGroupContains(width, height, density.density, 20f, point.x, point.y)

        fun footerButtonHitContains(width: Float, height: Float, density: Density, point: Offset): Boolean =
            roundedGroupContains(width, height, density.density, minOf(width, height) / (2f * density.density), point.x, point.y)
    }
}
