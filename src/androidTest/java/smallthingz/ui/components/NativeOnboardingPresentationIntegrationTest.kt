package smallthingz.ui.components

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.unit.Density

@RunWith(AndroidJUnit4::class)
class NativeOnboardingPresentationIntegrationTest {
    @Test fun pageIndicatorKeepsOddWidthCentersAndMirrorsTheSelectedDot() = main {
        val context = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            densityDpi = 160
        })
        val owner = object : View(context) {
            var direction = LAYOUT_DIRECTION_LTR
            override fun getLayoutDirection() = direction
        }
        owner.layout(0, 0, 101, 76)
        val recipe = NativeUiAppearance.Default.onboardingFooter
        val indicator = recipe.createIndicator(owner, 3)
        val palette = NativeColorScheme.fallback(darkTheme = false).copy(primary = Color.RED, outlineVariant = Color.BLUE)
        val bitmap = Bitmap.createBitmap(owner.width, owner.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            assertEquals(38, recipe.actionsTopPx(1f))
            indicator.bind(0, palette)
            indicator.draw(canvas)
            assertEquals(Color.RED, bitmap.getPixel(36, 20))
            assertEquals(Color.BLUE, bitmap.getPixel(57, 20))
            assertEquals(Color.BLUE, bitmap.getPixel(71, 20))
            assertEquals(Color.TRANSPARENT, bitmap.getPixel(49, 20))

            bitmap.eraseColor(Color.TRANSPARENT)
            owner.direction = View.LAYOUT_DIRECTION_RTL
            indicator.draw(canvas)
            assertEquals(Color.BLUE, bitmap.getPixel(31, 20))
            assertEquals(Color.BLUE, bitmap.getPixel(45, 20))
            assertEquals(Color.RED, bitmap.getPixel(65, 20))

            bitmap.eraseColor(Color.TRANSPARENT)
            indicator.bind(2, palette)
            indicator.draw(canvas)
            assertEquals(Color.RED, bitmap.getPixel(36, 20))
            assertEquals(Color.BLUE, bitmap.getPixel(71, 20))
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun pagerChildClipKeepsVerticalShadowMarginAndExactHorizontalBounds() = main {
        val viewport = object : NativeHorizontalPagerViewport(context, Density(1f)) {
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = layOutOverflowChild(this)
        }
        paintViewport(viewport) { bitmap ->
            assertEquals(Color.RED, bitmap.getPixel(40, 10))
            assertEquals(Color.RED, bitmap.getPixel(79, 109))
            assertEquals(Color.TRANSPARENT, bitmap.getPixel(39, 60))
            assertEquals(Color.TRANSPARENT, bitmap.getPixel(80, 60))
            assertEquals(Color.TRANSPARENT, bitmap.getPixel(60, 9))
            assertEquals(Color.TRANSPARENT, bitmap.getPixel(60, 110))
        }
    }

    @Test fun permissionViewUsesTheSameSoftwareOutlineAndHitRectangle() = main {
        val viewport = object : NativeRectangularViewport(context) {
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = layOutOverflowChild(this)
            fun contains(x: Float, y: Float) = containsViewportPoint(x, y)
        }
        paintViewport(viewport) { bitmap ->
            assertEquals(Color.RED, bitmap.getPixel(40, 40))
            assertEquals(Color.RED, bitmap.getPixel(79, 79))
            assertEquals(Color.TRANSPARENT, bitmap.getPixel(39, 60))
            assertEquals(Color.TRANSPARENT, bitmap.getPixel(60, 39))
            assertEquals(Color.TRANSPARENT, bitmap.getPixel(60, 80))
            assertTrue(viewport.contains(0f, 0f))
            assertFalse(viewport.contains(40f, 20f))
            assertFalse(viewport.contains(20f, 40f))
            assertTrue(viewport.clipToOutline)
            val outline = Outline()
            viewport.outlineProvider.getOutline(viewport, outline)
            val rectangle = Rect()
            assertTrue(outline.getRect(rectangle))
            assertEquals(Rect(0, 0, 40, 40), rectangle)
        }
    }

    private fun layOutOverflowChild(viewport: ViewGroup) {
        viewport.getChildAt(0).apply {
            measure(exact(120), exact(120))
            layout(-40, -40, 80, 80)
        }
    }

    private fun paintViewport(viewport: ViewGroup, verify: (Bitmap) -> Unit) {
        viewport.addView(object : View(context) {
            override fun onDraw(canvas: Canvas) = canvas.drawColor(Color.RED)
        }, ViewGroup.LayoutParams(120, 120))
        viewport.measure(exact(40), exact(40))
        viewport.layout(0, 0, 40, 40)
        val bitmap = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        try {
            viewport.draw(Canvas(bitmap).apply { translate(40f, 40f) })
            verify(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().context
    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
}
