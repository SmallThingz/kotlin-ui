package smallthingz.ui.components

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.MotionDurationScale
import smallthingz.ui.animation.AnimatedValue
import smallthingz.ui.R
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.reverb.library.LibraryRecordingRow
import smallthingz.ui.reverb.library.LibraryRecordingRowActions
import smallthingz.ui.reverb.library.ReverbLibraryRecordingRow
import kotlin.math.roundToInt

/** Real shared row/Paragraph measurement; no screenshot-derived dimensions or system-setting writes. */
@RunWith(AndroidJUnit4::class)
class ReverbLibraryResponsiveRowIntegrationTest {
    @Test fun narrowTimestampReclaimsPrimaryWidthAcrossDensityFontScaleAndRtl() = main {
        for (density in listOf(160, 464)) for (fontScale in listOf(1f, 2f)) {
            Fixture(density, fontScale).use { f ->
                for (rtl in listOf(false, true)) {
                    f.row.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                    f.bind(playing = true)
                    f.layout(f.dp(167)) // Synthetic 199dp window minus the shared 16dp list gutters.
                    assertEquals(if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR,
                        f.body.layoutDirection)
                    f.assertCompact()
                    assertEquals(f.dp(72), f.leading.width)
                    assertEquals(f.context.getString(R.string.recording_playing_details, f.model.subtitle),
                        f.subtitle.getText())
                    assertEquals(f.model.timestamp, f.timestamp.getText())
                    assertTrue("Long primary text still uses honest ellipsis", f.title.currentParagraph!!.isLineEllipsized(0))
                    assertEquals("Paragraph itself uses the reclaimed budget", f.innerWidth.toFloat(),
                        f.title.currentParagraph!!.width, 0f)
                }
            }
        }
    }

    @Test fun halfBudgetBoundaryIsStrictAndOneHolderReflowsBothWays() = main {
        Fixture().use { f ->
            f.bind()
            f.layout(f.dp(640))
            val stamp = f.timestamp.maxIntrinsicTextWidth()
            val fixed = f.dp(72) + f.dp(14)
            val equalityWidth = fixed + 2 * (stamp + f.dp(12))
            for (width in listOf(equalityWidth - 1, equalityWidth, equalityWidth + 1,
                f.dp(640), equalityWidth - 1)) {
                f.layout(width)
                assertEquals("No hidden row inset in this fixture", width, f.body.width)
                if (width < equalityWidth) f.assertCompact() else f.assertHorizontal()
            }
            // Same holder/source identity: playing and selection may not change the chosen geometry.
            f.bind(playing = true, selecting = true)
            f.layout(equalityWidth - 1)
            f.assertCompact()
            f.bind()
            f.layout(f.dp(640))
            f.assertHorizontal()
            f.bind(playing = true)
            f.layout(equalityWidth - 1)
            f.assertCompact()
        }
    }

    @Test fun boundedHeightKeepsCompactTimestampWithinRemainingSpace() = main {
        for (scale in listOf(1f, 2f)) Fixture(fontScale = scale).use { f ->
            f.bind()
            for (height in listOf(f.dp(32), f.dp(68), f.dp(160))) {
                f.layout(f.dp(167), height)
                assertTrue(f.row.measuredHeight <= height)
                assertTrue(f.body.height <= height)
                assertTrue(f.title.top >= 0)
                assertTrue(f.subtitle.top >= f.title.bottom)
                assertTrue(f.timestamp.top >= f.subtitle.bottom)
                assertTrue(f.timestamp.bottom <= f.body.height)
                assertEquals(f.dp(72), f.leading.width)
            }
            f.layout(f.dp(167))
            f.assertCompact()
            assertTrue("Unbounded measurement restores real timestamp height", f.timestamp.height > 0)
        }
    }

    @Test fun absentTimestampAndCustomTrailingContentKeepExistingAllocation() = main {
        Fixture().use { f ->
            f.bind(timestamp = null)
            f.layout(f.dp(167))
            assertEquals(View.GONE, f.timestamp.visibility)
            assertEquals(f.innerWidth, f.title.width)
            val custom = object : View(f.context) {
                override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                    setMeasuredDimension(resolveSize(f.dp(50), widthSpec), resolveSize(f.dp(18), heightSpec))
                }
            }
            f.row.setSummarySlots(null, custom)
            f.bind()
            for (rtl in listOf(false, true)) {
                f.row.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                f.layout(f.dp(167))
                assertEquals(if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR,
                    f.body.layoutDirection)
                assertEquals(View.GONE, f.timestamp.visibility)
                assertSame(f.body, custom.parent)
                assertEquals(f.innerWidth - f.dp(8) - custom.width, f.title.width)
                assertEquals(f.body.width - f.dp(14) - custom.width, f.start(custom))
            }
            f.row.setSummarySlots(null, null)
            f.layout(f.dp(167))
            assertEquals(View.VISIBLE, f.timestamp.visibility)
            f.assertCompact()
        }
    }

    @Test fun compactRowPreservesBodyLeadingActionsAndPlayingOnReuse() = main {
        Fixture().use { f ->
            f.bind(playing = true, selecting = true)
            f.layout(f.dp(167))
            f.assertCompact()
            assertTrue(f.body.performClick())
            assertTrue(f.leading.performClick())
            assertTrue(f.body.performLongClick())
            assertTrue(f.leading.performLongClick())
            assertEquals(listOf("activate:one", "activate:one", "body:one", "leading:one"), f.calls)
            f.row.unbind()
            assertTrue(f.row.paragraphLeaves.all { it.getText().isEmpty() })
            f.model = f.model.copy(recordingId = "two", title = "Second recording with a long name.wav")
            f.bind()
            f.layout(f.dp(167))
            f.assertCompact()
            assertEquals(f.model.subtitle, f.subtitle.getText())
            assertTrue(f.leading.performClick())
            assertEquals("activate:two", f.calls.last())
            f.bind(enabled = false)
            f.layout(f.dp(167))
            val before = f.calls.toList()
            f.body.performClick()
            f.leading.performClick()
            assertFalse(f.body.performLongClick())
            assertFalse(f.leading.performLongClick())
            assertEquals(before, f.calls)
        }
    }

    private class Fixture(densityDpi: Int = 160, fontScale: Float = 1f) : AutoCloseable {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.let { base ->
            base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                this.densityDpi = densityDpi
                this.fontScale = fontScale
            })
        }
        private val runtime = NativeModalWindowRuntime(context)
        private val environment = ReverbEnvironment(context, false)
        // Settle only this fixture's presentation clock; no global animator/setting mutation.
        private val scope = CoroutineScope(runtime.scope.coroutineContext + object : MotionDurationScale {
            override val scaleFactor = 0f
        })
        val row = ReverbLibraryRecordingRow(context, environment, scope, View(context))
        private val outerSize = row.javaClass.getDeclaredField("outerSizeMotion").apply { isAccessible = true }
        var model = LibraryRecordingRow("one", "Captured recording with a long descriptive filename.wav",
            "20 seconds • 2.4 MB", "10:42 PM", false, false, true, null)
        val calls = mutableListOf<String>()
        private val actions = LibraryRecordingRowActions({ calls += "activate:$it" },
            { calls += "body:$it" }, { calls += "leading:$it" })
        val title get() = row.paragraphLeaves[0]
        val subtitle get() = row.paragraphLeaves[1]
        val timestamp get() = row.paragraphLeaves[2]
        val body get() = title.parent as ViewGroup
        val leading get() = descendants(body).filterIsInstance<NativeLeadingDisc>().single().parent as View
        val innerWidth get() = (body.width - leading.width - dp(14)).coerceAtLeast(0)
        fun dp(value: Int) = (value * environment.textDensity.density).roundToInt()
        fun bind(playing: Boolean = false, selecting: Boolean = false, enabled: Boolean = true,
            timestamp: String? = model.timestamp) {
            row.bind(model.copy(timestamp = timestamp, isPlaying = playing, selected = selecting,
                selectionActive = selecting, interactionsEnabled = enabled), actions)
        }
        fun layout(width: Int, height: Int? = null) {
            val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
            val heightSpec = if (height == null) View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                else View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST)
            row.measure(widthSpec, heightSpec)
            // Detached measurement does not receive vsync. Advance the existing size owner to
            // its zero-duration endpoint; this fixture does not claim animation/frame parity.
            (outerSize.get(row) as AnimatedValue<*, *>).frame(0L, 0f)
            row.requestLayout()
            row.measure(widthSpec, heightSpec)
            row.layout(0, 0, row.measuredWidth, row.measuredHeight)
        }
        fun start(view: View) = if (body.layoutDirection == View.LAYOUT_DIRECTION_RTL)
            body.width - view.right else view.left
        fun assertCompact() {
            assertEquals(innerWidth.coerceAtMost(title.maxIntrinsicTextWidth()), title.width)
            assertEquals(leading.width, start(title))
            assertEquals(leading.width, start(subtitle))
            assertEquals(leading.width, start(timestamp))
            assertTrue(timestamp.top >= subtitle.bottom + dp(4))
            assertTrue(timestamp.bottom <= body.height - dp(12))
            assertTrue(timestamp.width <= innerWidth)
            assertTrue(row.height >= body.height)
        }
        fun assertHorizontal() {
            val budget = innerWidth - dp(12) - timestamp.width
            assertEquals(budget.coerceAtMost(title.maxIntrinsicTextWidth()), title.width)
            assertEquals(leading.width, start(title))
            assertEquals(body.width - dp(14) - timestamp.width, start(timestamp))
            assertTrue(timestamp.top < subtitle.bottom)
            assertEquals(dp(72), leading.width)
        }
        private fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) repeat(view.childCount) { yieldAll(descendants(view.getChildAt(it))) }
        }
        override fun close() {
            try { row.unbind() } finally { runtime.close() }
        }
    }

    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
}
