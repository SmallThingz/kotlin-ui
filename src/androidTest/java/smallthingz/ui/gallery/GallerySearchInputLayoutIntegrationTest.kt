package smallthingz.ui.gallery

import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.View.MeasureSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GallerySearchInputLayoutIntegrationTest {
    @Test fun boundedPortraitMeasuresEachChildOnlyAtFullWidth() = onMain { context ->
        val fixture = Fixture(context, widthDp = 420, heightDp = 460)
        val fullWidth = exact(fixture.width)
        fixture.measureAndLayout(fullWidth)

        assertEquals(listOf(fullWidth), fixture.actions.widthSpecs)
        assertEquals(listOf(fullWidth), fixture.query.widthSpecs)
        fixture.assertStacked(fixture.width)
    }

    @Test fun shortLandscapeRemeasuresFullWidthOnlyAfterATallerNarrowQuery() = onMain { context ->
        val tallActions = Fixture(context, widthDp = 640, heightDp = 360, actionHeightDp = 72)
        val fullWidth = exact(tallActions.width)
        val naturalProbe = MeasureSpec.makeMeasureSpec(tallActions.width, MeasureSpec.AT_MOST)
        tallActions.measureAndLayout(fullWidth)
        assertEquals(listOf(naturalProbe, fullWidth), tallActions.actions.widthSpecs)
        assertEquals(listOf(fullWidth), tallActions.query.widthSpecs)
        tallActions.assertStacked(tallActions.width)

        val wraps = Fixture(context, widthDp = 640, heightDp = 360, queryWrapsBelowDp = 560)
        wraps.measureAndLayout(fullWidth)
        assertEquals(listOf(naturalProbe, fullWidth), wraps.actions.widthSpecs)
        assertEquals(listOf(fullWidth, exact(wraps.inlineQueryWidth), fullWidth), wraps.query.widthSpecs)
        wraps.assertStacked(wraps.width)
    }

    @Test fun compactLandscapeKeepsNaturalActionsAndInlineRtlPlacement() = onMain { context ->
        val fixture = Fixture(context, widthDp = 640, heightDp = 360)
        fixture.layout.layoutDirection = View.LAYOUT_DIRECTION_RTL
        fixture.measureAndLayout(exact(fixture.width))

        assertEquals(listOf(MeasureSpec.makeMeasureSpec(fixture.width, MeasureSpec.AT_MOST)),
            fixture.actions.widthSpecs)
        assertEquals(listOf(exact(fixture.width), exact(fixture.inlineQueryWidth)), fixture.query.widthSpecs)
        assertEquals(fixture.width, fixture.query.right)
        assertEquals(fixture.query.left - fixture.gap, fixture.actions.right)
        assertEquals(0, fixture.actions.left)
        assertEquals(fixture.queryHeight, fixture.layout.height)
    }

    @Test fun unboundedWidthKeepsTheNaturalActionWidthProbe() = onMain { context ->
        val fixture = Fixture(context, widthDp = 640, heightDp = 360, actionWidthDp = 360)
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val fullWidth = exact(fixture.actionWidth)
        fixture.measureAndLayout(unspecified)

        assertEquals(listOf(unspecified, fullWidth), fixture.actions.widthSpecs)
        assertEquals(listOf(fullWidth), fixture.query.widthSpecs)
        fixture.assertStacked(fixture.actionWidth)
    }

    private class Fixture(
        base: Context,
        widthDp: Int,
        heightDp: Int,
        actionWidthDp: Int = 120,
        actionHeightDp: Int = 32,
        queryWrapsBelowDp: Int = 0,
    ) {
        private val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            screenWidthDp = widthDp
            screenHeightDp = heightDp
            orientation = if (widthDp > heightDp) Configuration.ORIENTATION_LANDSCAPE
                else Configuration.ORIENTATION_PORTRAIT
        })
        private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()

        val width = dp(widthDp)
        val gap = dp(10)
        val actionWidth = dp(actionWidthDp)
        private val actionHeight = dp(actionHeightDp)
        val queryHeight = dp(48)
        val inlineQueryWidth = width - actionWidth - gap
        val query = MeasuringChild(context, dp(280)) { width ->
            if (width < dp(queryWrapsBelowDp)) dp(80) else queryHeight
        }
        val actions = MeasuringChild(context, actionWidth) { actionHeight }
        val layout = GallerySearchInputLayout(context).apply { setSearchContent(query, actions) }

        fun measureAndLayout(widthSpec: Int) {
            // Force cold child measurement so cache hits cannot hide redundant calls.
            actions.forceLayout()
            query.forceLayout()
            layout.measure(widthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            layout.layout(0, 0, layout.measuredWidth, layout.measuredHeight)
        }

        fun assertStacked(expectedWidth: Int) {
            assertEquals(expectedWidth, layout.width)
            assertEquals(expectedWidth, query.width)
            assertEquals(expectedWidth, actions.width)
            assertEquals(queryHeight, query.height)
            assertEquals(queryHeight + gap, actions.top)
            assertEquals(actionHeight, actions.height)
            assertEquals(actions.bottom, layout.height)
        }
    }

    private class MeasuringChild(context: Context, private val naturalWidth: Int, private val heightAtWidth: (Int) -> Int) :
        View(context) {
        val widthSpecs = mutableListOf<Int>()
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            widthSpecs.add(widthMeasureSpec)
            val width = resolveSize(naturalWidth, widthMeasureSpec)
            setMeasuredDimension(width, resolveSize(heightAtWidth(width), heightMeasureSpec))
        }
    }

    private fun exact(width: Int) = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)

    private fun onMain(block: (Context) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching { block(instrumentation.context) } }
        checkNotNull(result).getOrThrow()
    }
}
