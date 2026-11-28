package smallthingz.ui.components

import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeMaterialSwitchGeometryTest {
    @Test fun nominalAndConstrainedBoundsInBothDirections() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val base = instrumentation.context
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                densityDpi = 160
            })
            assertEquals(1f, context.resources.displayMetrics.density, 0f)
            for (dark in listOf(false, true)) {
                val palette = NativeColorScheme.fallback(darkTheme = dark).switchPalette
                for (rtl in listOf(false, true)) {
                    for (checked in listOf(false, true)) {
                        for (width in listOf(36, 52)) {
                            val mark = NativeMaterialSwitchView(context, palette, initialChecked = checked)
                            mark.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                            mark.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                            assertEquals(width, mark.measuredWidth)
                            assertEquals(32, mark.measuredHeight)
                            mark.layout(0, 0, mark.measuredWidth, mark.measuredHeight)
                            val canvas = RecordingCanvas()
                            mark.draw(canvas)

                            val end = if (width == 36) 8f else 24f
                            val x = if (checked xor rtl) end else 4f
                            assertEquals(
                                listOf(
                                    RoundRect(RectF(0f, 0f, width.toFloat(), 32f), 16f, 0f, 255),
                                    RoundRect(RectF(x, 4f, x + 24f, 28f), 12f, 0f, 255),
                                    RoundRect(RectF(.5f, .5f, width - .5f, 31.5f), 15.5f, 1f,
                                        if (checked) 0 else 255),
                                ),
                                canvas.rects,
                            )
                        }
                    }
                }
            }
        }
    }

    private data class RoundRect(
        val bounds: RectF,
        val radius: Float,
        val stroke: Float,
        val alpha: Int,
    )

    private class RecordingCanvas : Canvas() {
        val rects = mutableListOf<RoundRect>()

        override fun drawRoundRect(
            left: Float, top: Float, right: Float, bottom: Float, rx: Float, ry: Float, paint: Paint,
        ) {
            assertEquals(rx, ry, 0f)
            val stroke = if (paint.style == Paint.Style.STROKE) paint.strokeWidth else 0f
            rects += RoundRect(RectF(left, top, right, bottom), rx, stroke, paint.alpha)
        }
    }
}
