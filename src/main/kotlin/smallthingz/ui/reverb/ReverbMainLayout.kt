package smallthingz.ui.reverb

import android.view.View
import kotlin.math.roundToInt

enum class ReverbMainFeedbackLayer { SCREEN, LIBRARY, INCIDENTS }

/** Feedback geometry and stacking policy for the retained Main surfaces. */
data class ReverbMainFeedbackPlacement(
    val deliveryAllowed: Boolean,
    val layer: ReverbMainFeedbackLayer,
    val horizontalPaddingDp: Int,
    val bottomPaddingDp: Int,
    val addNavigationBarBottom: Boolean,
)

object ReverbMainLayout {
    fun feedbackPlacement(libraryVisible: Boolean, incidentsVisible: Boolean, settingsVisible: Boolean,
                          deliveryAllowed: Boolean): ReverbMainFeedbackPlacement {
        val abovePanel = incidentsVisible || libraryVisible
        return ReverbMainFeedbackPlacement(
            deliveryAllowed = deliveryAllowed,
            layer = when {
                incidentsVisible -> ReverbMainFeedbackLayer.INCIDENTS
                libraryVisible -> ReverbMainFeedbackLayer.LIBRARY
                else -> ReverbMainFeedbackLayer.SCREEN
            },
            horizontalPaddingDp = 18,
            bottomPaddingDp = if (abovePanel || settingsVisible) 20 else 104,
            addNavigationBarBottom = abovePanel,
        )
    }

    fun feedbackBottomPx(placement: ReverbMainFeedbackPlacement, navigationBottom: Int, density: Float): Int =
        (placement.bottomPaddingDp * density).roundToInt() + if (placement.addNavigationBarBottom) navigationBottom else 0

    fun measureFeedback(view: View, width: Int, height: Int, placement: ReverbMainFeedbackPlacement,
                        navigationBottom: Int, density: Float) {
        val horizontal = (placement.horizontalPaddingDp * density).roundToInt()
        val bottom = feedbackBottomPx(placement, navigationBottom, density)
        view.measure(atMost(width - 2 * horizontal), atMost(height - bottom))
    }

    fun layoutFeedback(view: View, width: Int, height: Int, placement: ReverbMainFeedbackPlacement,
                       navigationBottom: Int, density: Float) {
        val bottom = height - feedbackBottomPx(placement, navigationBottom, density)
        val left = ((width - view.measuredWidth) * .5f).roundToInt()
        view.layout(left, bottom - view.measuredHeight, left + view.measuredWidth, bottom)
    }

    fun applySettingsMotion(home: View, settings: View?, height: Int, progress: Float, visible: Boolean) {
        home.translationY = progress * height
        settings?.alpha = if (progress > 0f || visible) 1f else .01f
    }

    /** Back-to-front order of the same direct children; no intermediate pairs or sorting. */
    fun <T : Any> drawOrder(settings: T?, home: T, feedback: T, library: T?, libraryToolbar: T?,
                          incidents: T, libraryAboveHome: Boolean, feedbackLayer: ReverbMainFeedbackLayer): List<T> =
        ArrayList<T>(6).apply {
        if (!libraryAboveHome && library != null) add(library)
        if (settings != null) add(settings)
        add(home)
        if (feedbackLayer == ReverbMainFeedbackLayer.SCREEN) add(feedback)
        if (libraryAboveHome && library != null) add(library)
        if (libraryToolbar != null) add(libraryToolbar)
        if (feedbackLayer == ReverbMainFeedbackLayer.LIBRARY) add(feedback)
        add(incidents)
        if (feedbackLayer == ReverbMainFeedbackLayer.INCIDENTS) add(feedback)
    }

    private fun atMost(value: Int) = View.MeasureSpec.makeMeasureSpec(value.coerceAtLeast(0), View.MeasureSpec.AT_MOST)
}
