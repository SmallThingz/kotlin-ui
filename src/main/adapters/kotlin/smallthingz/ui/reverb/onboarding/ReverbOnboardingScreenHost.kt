package smallthingz.ui.reverb.onboarding

import android.content.Context
import android.graphics.Rect
import android.view.ViewGroup
import smallthingz.ui.components.NativeButtonInputPolicy
import smallthingz.ui.reverb.ReverbTheme
import smallthingz.ui.reverb.ReverbScreenTransition

/** Retained product body and the complete Reverb footer; the product owns all route decisions.
 * The surrounding window owns safe insets. Shared geometry owns the page/footer spacing.
 */
class ReverbOnboardingScreenHost(
    context: Context,
    private val theme: ReverbTheme,
    val pageArea: ViewGroup,
    pageCount: Int,
    onBack: () -> Unit,
    onContinue: () -> Unit,
) : ViewGroup(context), AutoCloseable {
    private val geometry = ReverbOnboardingScreenLayout(theme.density)
    private val transition = ReverbScreenTransition(pageArea)
    val isTransitionRunning: Boolean get() = transition.isRunning
    private var presentation: OnboardingFooterPresentation? = null
    private val footer = OnboardingFooterView(context, theme.controlTheme, pageCount,
        NativeButtonInputPolicy(inputRoot = { this }, revisionKey = { presentation?.page }))
    private val actions = footer.connect(onBack, onContinue)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(pageArea)
        footer.enableStandaloneAndroidAccessibility()
        footer.filterTouchesWhenObscured = true
        addView(footer)
    }

    fun bind(value: OnboardingFooterPresentation) {
        presentation = value
        footer.bind(value, theme.palette)
        transition.transitionTo(value.page)
        if (!hasWindowFocus()) transition.cancel()
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val height = MeasureSpec.getSize(h)
        geometry.measure(width, height, Rect(), pageArea, footer)
        setMeasuredDimension(resolveSize(width, w), resolveSize(height, h))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) =
        geometry.layout(pageArea, footer)

    fun cancelTransition() = transition.cancel()

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) transition.cancel()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        if (!hasWindowFocus) {
            transition.cancel()
            cancelPendingInputEvents()
        }
        super.onWindowFocusChanged(hasWindowFocus)
    }

    override fun close() {
        transition.close()
        actions.close()
        footer.close()
    }
}
