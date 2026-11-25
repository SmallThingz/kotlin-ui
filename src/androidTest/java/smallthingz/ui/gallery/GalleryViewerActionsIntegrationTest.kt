package smallthingz.ui.gallery

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/** Geometry only; no media, activity launch or global configuration changes. */
@RunWith(AndroidJUnit4::class)
class GalleryViewerActionsIntegrationTest {
    @Test fun equalSlotsRetainActionsAcrossFontDirectionCompactAndNarrowViewport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (font in listOf(1f, 2f)) {
                val base = instrumentation.targetContext
                val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply { fontScale = font })
                fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
                val row = GalleryViewerActions(context, GalleryDesign.palette(context, true).pageControls)
                val names = listOf("Share", "Edit", "Unavailable", "More")
                val actions = names.mapIndexed { index, label ->
                    row.addAction(ColorDrawable(Color.WHITE), label, selected = index == 2,
                        enabled = index != 2) { true }
                }
                val parents = actions.map { it.parent }
                fun layout(width: Int) {
                    row.measure(View.MeasureSpec.makeMeasureSpec(dp(width), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    row.layout(0, 0, row.measuredWidth, row.measuredHeight)
                }
                for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                    row.layoutDirection = direction
                    for (compact in listOf(false, true, false)) {
                        row.setCompact(compact)
                        for (width in listOf(320, 480, 144, 320)) {
                            layout(width)
                            assertEquals(dp(width), row.width)
                            assertEquals(maxOf(dp(width), 4 * dp(48)), row.getChildAt(0).width)
                            assertTrue(actions.maxOf { it.width } - actions.minOf { it.width } <= 1)
                            assertTrue(actions.all { it.width >= dp(48) && it.height >= dp(48) })
                            assertEquals(1, actions.map { it.top }.distinct().size)
                            assertEquals(parents, actions.map { it.parent })
                            assertEquals(names, actions.map { it.contentDescription.toString() })
                            assertEquals(names.map { if (compact) "" else it }, actions.map { it.text.toString() })
                            if (!compact) actions.forEach { action ->
                                val textLayout = checkNotNull(action.layout)
                                assertEquals(action.text.length, textLayout.getLineEnd(textLayout.lineCount - 1))
                                for (line in 0 until textLayout.lineCount) assertEquals(0, textLayout.getEllipsisCount(line))
                                assertTrue(textLayout.height <= action.height - action.compoundPaddingTop - action.compoundPaddingBottom)
                            }
                            assertFalse(actions[2].isEnabled)
                            assertTrue(actions[2].isSelected)
                            if (width == 144) {
                                row.scrollTo(Int.MAX_VALUE, 0)
                                assertTrue(row.scrollX > 0)
                            }
                        }
                    }
                }
            }
        }
    }
}
