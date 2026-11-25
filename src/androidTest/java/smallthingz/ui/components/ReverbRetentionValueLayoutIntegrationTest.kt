package smallthingz.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.reverb.ReverbIcon
import smallthingz.ui.reverb.settings.ReverbRetentionMode
import smallthingz.ui.reverb.settings.ReverbRetentionValueView
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density
import smallthingz.ui.unit.sp

/** Real retained-view binds; unattached paint settles synchronously, with no window/device changes. */
@RunWith(AndroidJUnit4::class)
class ReverbRetentionValueLayoutIntegrationTest {
    @Test fun paletteOnlyRebindPreservesGeometryAndRepaintsEveryReadout() = main { context ->
        val base = environment(context)
        val editor = View(context).apply { minimumHeight = 24 }
        val row = CountingReadout(context, editor, base)
        row.bind(ReverbRetentionMode.SIZE, "123 minutes", base)
        layout(row)
        val children = (0 until row.childCount).map(row::getChildAt)
        val original = geometry(row)
        val blue = base.withPalette(base.palette.copy(onSurfaceVariant = Color.BLUE))
        val typography = base.appearance.readoutTypography
        val equalStyles = ReverbEnvironment.withTestAppearance(base.textDensity, base.sharedFontResolver,
            blue.palette, base.appearance.copy(readoutTypography = typography.copy(
                retentionLabel = typography.retentionLabel.copy(), retentionEstimate = typography.retentionEstimate.copy())))
        for (next in listOf(blue, equalStyles, base)) {
            row.requests = 0
            row.bind(ReverbRetentionMode.SIZE, "123 minutes", next)
            assertEquals("Palette/equal-style bind must not request layout", 0, row.requests)
            assertFalse(row.isLayoutRequested)
            layout(row)
            assertEquals(original, geometry(row))
            children.indices.forEach { assertSame(children[it], row.getChildAt(it)) }
            // Glyph, heading, unit and estimate all retain their actual painted tint.
            for (index in listOf(0, 1, 3, 4)) assertInk(row.getChildAt(index), next.palette.muted)
        }
    }

    @Test fun typographyDensityAndResolverChangesStillRequestLayout() = main { context ->
        val base = environment(context)
        val typography = base.appearance.readoutTypography
        val resolver = object : FontFamily.Resolver by base.sharedFontResolver {}
        val changes = listOf(
            "label metrics" to ReverbEnvironment.withTestAppearance(base.textDensity, base.sharedFontResolver,
                base.palette, base.appearance.copy(readoutTypography = typography.copy(
                    retentionLabel = typography.retentionLabel.copy(fontSize = 28.sp, lineHeight = 40.sp)))),
            "estimate metrics" to ReverbEnvironment.withTestAppearance(base.textDensity, base.sharedFontResolver,
                base.palette, base.appearance.copy(readoutTypography = typography.copy(
                    retentionEstimate = typography.retentionEstimate.copy(fontSize = 26.sp, lineHeight = 40.sp)))),
            "density" to ReverbEnvironment(Density(1.5f), base.sharedFontResolver, base.palette),
            "font scale" to ReverbEnvironment(Density(1f, 1.5f), base.sharedFontResolver, base.palette),
            // Equal numeric density still changes ParagraphView's cache owner; do not suppress it.
            "density identity" to ReverbEnvironment(Density(1f), base.sharedFontResolver, base.palette),
            "resolver identity" to ReverbEnvironment(base.textDensity, resolver, base.palette),
        )
        for ((name, next) in changes) {
            val row = CountingReadout(context, View(context).apply { minimumHeight = 24 }, base)
            row.bind(ReverbRetentionMode.SIZE, "123 minutes", base)
            layout(row)
            val before = geometry(row)
            row.requests = 0
            row.bind(ReverbRetentionMode.SIZE, "123 minutes", next)
            assertTrue("$name must request layout", row.requests > 0)
            assertTrue(row.isLayoutRequested)
            layout(row)
            if (!name.endsWith("identity")) assertNotEquals("$name must affect measured geometry", before, geometry(row))
        }
    }

    @Test fun modeAndEstimateChangesStillRequestLayout() = main { context ->
        val base = environment(context)
        val row = CountingReadout(context, View(context).apply { minimumHeight = 24 }, base)
        row.bind(ReverbRetentionMode.TIME, "1 MiB", base)
        layout(row)
        row.requests = 0
        row.bind(ReverbRetentionMode.SIZE, "1 MiB", base)
        assertTrue(row.requests > 0)
        assertEquals(View.VISIBLE, row.getChildAt(3).visibility)
        layout(row)
        row.requests = 0
        row.bind(ReverbRetentionMode.SIZE, "A longer estimate that wraps onto more than one line at this width", base)
        assertTrue(row.requests > 0)
    }

    private class CountingReadout(context: Context, editor: View, environment: ReverbEnvironment) :
        ReverbRetentionValueView(context, ReverbIcon.OneShot, "One-shot", "MiB", editor, environment) {
        var requests = 0
        override fun requestLayout() { requests++; super.requestLayout() }
    }

    private fun environment(context: Context) = ReverbEnvironment(Density(1f), createFontFamilyResolver(context),
        NativeColorScheme.fallback(false).copy(onSurfaceVariant = Color.RED))

    private fun layout(row: View) {
        row.measure(View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        row.layout(0, 0, row.measuredWidth, row.measuredHeight)
    }

    private fun geometry(row: CountingReadout): List<Rect> = listOf(Rect(0, 0, row.width, row.height)) +
        (0 until row.childCount).map { row.getChildAt(it).let { child -> Rect(child.left, child.top, child.right, child.bottom) } }

    private fun assertInk(view: View, color: Int) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            val pixels = IntArray(view.width * view.height)
            bitmap.getPixels(pixels, 0, view.width, 0, 0, view.width, view.height)
            assertTrue("Missing rebound ink in ${view.javaClass.simpleName}", pixels.any {
                Color.alpha(it) > 0 && (it and 0xffffff) == (color and 0xffffff)
            })
        } finally { bitmap.recycle() }
    }

    private fun main(block: (Context) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { block(instrumentation.targetContext) }
    }
}
