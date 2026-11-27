package smallthingz.ui.components

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import smallthingz.ui.MotionDurationScale
import smallthingz.ui.animation.AnimatedValue
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.reverb.library.LibraryRecordingRow
import smallthingz.ui.reverb.library.LibraryRecordingRowActions
import smallthingz.ui.reverb.library.ReverbLibraryRecordingRow
import kotlin.math.roundToInt

/** Native-graphics production View/Paragraph measurement, not a copy of the row algorithm. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReverbLibraryResponsiveRowHostTest {
    @Test fun timestampReflowsBelowPrimaryAndReturnsWideInBothDirections() {
        for (scale in listOf(1f, 2f)) withFixture(scale) { f ->
            for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                f.row.layoutDirection = direction
                for (width in listOf(f.dp(167), f.dp(640), f.dp(167))) {
                    f.place(width)
                    assertEquals(direction, f.row.layoutDirection)
                    assertEquals(direction, f.body.layoutDirection)
                    assertEquals(f.dp(72), f.leading.width)
                    if (width == f.dp(167)) f.assertCompact() else f.assertWide()
                }
                // Same actual paragraph supplies the independent measured timestamp extent.
                f.place(f.dp(640))
                val boundary = f.dp(72) + f.dp(14) + 2 * (f.timestamp.maxIntrinsicTextWidth() + f.dp(12))
                for (width in listOf(boundary - 1, boundary, boundary + 1)) {
                    f.place(width)
                    if (width < boundary) f.assertCompact() else f.assertWide()
                }
                f.place(f.dp(167))
                assertTrue(f.body.performClick())
                assertTrue(f.leading.performClick())
            }
            assertEquals(listOf("one", "one", "one", "one"), f.activated)
        }
    }

    @Test fun compactRowHonorsBoundedHeightAndCustomTrailingSlot() {
        withFixture { f ->
            for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                f.row.layoutDirection = direction
                for (height in listOf(f.dp(32), f.dp(68), f.dp(160))) {
                    f.place(f.dp(167), height)
                    assertEquals(direction, f.body.layoutDirection)
                    assertTrue(f.row.height <= height)
                    assertTrue(f.title.top >= 0)
                    assertTrue(f.subtitle.top >= f.title.bottom)
                    assertTrue(f.timestamp.top >= f.subtitle.bottom)
                    assertTrue(f.timestamp.bottom <= f.body.height)
                }
                f.place(f.dp(167))
                f.assertCompact()
                assertTrue(f.timestamp.height > 0)
                val custom = object : View(f.context) {
                    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                        setMeasuredDimension(resolveSize(f.dp(50), widthSpec), resolveSize(f.dp(18), heightSpec))
                    }
                }
                f.row.setSummarySlots(null, custom)
                f.place(f.dp(167))
                assertEquals(View.GONE, f.timestamp.visibility)
                assertEquals(f.innerWidth - f.dp(8) - custom.width, f.title.width)
                assertEquals(f.body.width - f.dp(14) - custom.width, f.start(custom))
                assertEquals(f.dp(72), f.leading.width)
                f.row.setSummarySlots(null, null)
                f.place(f.dp(167))
                f.assertCompact()
                assertEquals(View.VISIBLE, f.timestamp.visibility)
            }
        }
    }

    private fun withFixture(fontScale: Float = 1f, block: (Fixture) -> Unit) {
        val info = RuntimeEnvironment.getApplication().applicationInfo
        val flags = info.flags
        info.flags = flags or ApplicationInfo.FLAG_SUPPORTS_RTL
        try { Fixture(fontScale).use(block) } finally { info.flags = flags }
    }

    private class Fixture(fontScale: Float = 1f) : AutoCloseable {
        val context = RuntimeEnvironment.getApplication().let { app ->
            app.createConfigurationContext(Configuration(app.resources.configuration).apply {
                this.fontScale = fontScale
            })
        }
        private val runtime = NativeModalWindowRuntime(context)
        private val environment = ReverbEnvironment(context, false)
        private val scope = CoroutineScope(runtime.scope.coroutineContext + object : MotionDurationScale {
            override val scaleFactor = 0f
        })
        val row = ReverbLibraryRecordingRow(context, environment, scope, View(context))
        private val outerSize = row.javaClass.getDeclaredField("outerSizeMotion").apply { isAccessible = true }
        val activated = mutableListOf<String>()
        init {
            row.bind(LibraryRecordingRow("one", "Captured recording with a long descriptive filename.wav",
                "20 seconds • 2.4 MB", "10:42 PM", false, false, true, null, isPlaying = true),
                LibraryRecordingRowActions({ activated += it }, null, null))
        }
        val title get() = row.paragraphLeaves[0]
        val subtitle get() = row.paragraphLeaves[1]
        val timestamp get() = row.paragraphLeaves[2]
        val body get() = title.parent as ViewGroup
        val leading get() = descendants(body).filterIsInstance<NativeLeadingDisc>().single().parent as View
        val innerWidth get() = body.width - leading.width - dp(14)
        fun dp(value: Int) = (value * environment.textDensity.density).roundToInt()
        fun place(width: Int, maxHeight: Int? = null) {
            val w = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
            val h = if (maxHeight == null) View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                else View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST)
            row.measure(w, h)
            // Deterministically settle the existing outer size owner without requesting frame work.
            (outerSize.get(row) as AnimatedValue<*, *>).frame(0L, 0f)
            row.requestLayout()
            row.measure(w, h)
            row.layout(0, 0, row.measuredWidth, row.measuredHeight)
        }
        fun start(view: View) = if (body.layoutDirection == View.LAYOUT_DIRECTION_RTL)
            body.width - view.right else view.left
        fun assertCompact() {
            assertEquals(innerWidth.coerceAtMost(title.maxIntrinsicTextWidth()), title.width)
            assertEquals(title.width.toFloat(), title.currentParagraph!!.width, 0f)
            assertEquals(leading.width, start(title))
            assertEquals(leading.width, start(subtitle))
            assertEquals(leading.width, start(timestamp))
            assertTrue(timestamp.top >= subtitle.bottom + dp(4))
            assertTrue(timestamp.bottom <= body.height - dp(12))
            assertTrue(row.height >= body.height)
        }
        fun assertWide() {
            assertEquals((innerWidth - dp(12) - timestamp.width).coerceAtMost(title.maxIntrinsicTextWidth()), title.width)
            assertEquals(leading.width, start(title))
            assertEquals(body.width - dp(14) - timestamp.width, start(timestamp))
            assertTrue(timestamp.top < subtitle.bottom)
        }
        private fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) repeat(view.childCount) { yieldAll(descendants(view.getChildAt(it))) }
        }
        override fun close() {
            try { row.unbind() } finally { runtime.close() }
        }
    }
}
