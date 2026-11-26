package smallthingz.ui.gallery

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeButtonColorRole
import smallthingz.ui.components.NativeButtonRecipes
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.controls.ControlStyle

@RunWith(AndroidJUnit4::class)
class GalleryHealControlsStyleIntegrationTest {
    @Test fun selectionActionsRetainGeometryAndUseDisabledNativeInk() = main {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (dark in listOf(false, true)) {
            val colors = NativeColorScheme.fallback(dark).copy(surfacePreset = null)
            var clicks = 0
            val button = Button(context).apply {
                text = "Clear selection"
                contentDescription = text
                maxLines = 2
                minimumHeight = 52
                setPaddingRelative(7, 4, 9, 6)
                setOnClickListener { clicks++ }
            }
            GalleryHealControlsStyle.paintSelectionAction(button, colors.pageControls)
            val background = layoutBackground(button, 60)
            val enabledInk = button.currentTextColor
            repeat(2) {
                button.isEnabled = false
                assertTrue("Disabled command must have reduced native ink opacity",
                    Color.alpha(button.currentTextColor) < Color.alpha(enabledInk))
                assertSame(background, button.background)
                button.isEnabled = true
                assertEquals(enabledInk, button.currentTextColor)
            }
            assertEquals("Clear selection", button.text.toString())
            assertEquals("Clear selection", button.contentDescription.toString())
            assertEquals(2, button.maxLines)
            assertEquals(52, button.minimumHeight)
            assertEquals(listOf(7, 4, 9, 6),
                listOf(button.paddingStart, button.paddingTop, button.paddingEnd, button.paddingBottom))
            button.performClick()
            assertEquals(1, clicks)
        }
    }

    @Test fun previewUsesWindowRecipeWhileRetainingPlatformButton() = main {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val colors = NativeColorScheme.fallback(false).copy(surfacePreset = null)
        val base = GalleryDesign.appearance
        val recipe = base.buttons.filled
        val custom = base.copy(buttons = NativeButtonRecipes(filled = recipe.copy(
            surface = recipe.surface.copy(radiusDp = 5f),
            enabledPaint = recipe.enabledPaint.copy(container = NativeButtonColorRole.ERROR_SURFACE),
            disabledPaint = recipe.disabledPaint.copy(container = NativeButtonColorRole.QUIET_SURFACE,
                containerOpacity = null),
        )))
        val label = "Preview cropped selection"
        var clicks = 0
        val button = Button(context).apply {
            text = label
            maxLines = 2
            minimumHeight = 52
            setPaddingRelative(7, 4, 9, 6)
            setOnClickListener { clicks++ }
        }
        val density = context.resources.displayMetrics.density

        GalleryHealControlsStyle.emphasizePreview(button, colors, custom)
        val background = layoutBackground(button, 40)
        assertRadius(background, minOf(5f * density, 20f))
        assertEquals(colors.errorContainer, centerPixel(background))
        assertEquals(recipe.contentColor(colors.buttonPalette, enabled = true), button.currentTextColor)

        button.isEnabled = false
        assertSame(background, button.background)
        assertEquals(colors.surfaceContainerLow, centerPixel(background))
        assertEquals(recipe.contentColor(colors.buttonPalette, enabled = false), button.currentTextColor)

        button.isEnabled = true
        GalleryHealControlsStyle.emphasizePreview(button, colors.pageControls)
        assertSame(background, button.background)
        assertRadius(background, 20f)
        assertSame(background, background.mutate())
        assertSame(background, layoutBackground(button, 60))
        assertRadius(background, 30f)
        assertEquals(Color.TRANSPARENT, pixel(background, 0, 0))
        assertEquals(colors.primary, centerPixel(background))
        assertEquals(label, button.text.toString())
        assertEquals(2, button.maxLines)
        assertEquals(52, button.minimumHeight)
        assertEquals(listOf(7, 4, 9, 6),
            listOf(button.paddingStart, button.paddingTop, button.paddingEnd, button.paddingBottom))
        button.performClick()
        assertEquals(1, clicks)

        val legacy = ControlStyle(context, colors.pageControls)
        legacy.applyButton(button, filled = true)
        assertRadius(layoutBackground(button, 80), legacy.dp(12).toFloat(), maskRadius = false)
    }

    private fun layoutBackground(button: Button, height: Int): RippleDrawable {
        button.layout(0, 0, 120, height)
        return (button.background as RippleDrawable).apply { setBounds(0, 0, 120, height) }
    }

    private fun assertRadius(background: RippleDrawable, expected: Float, maskRadius: Boolean = true) {
        val outline = Outline()
        val content = background.getDrawable(0)
        content.getOutline(outline)
        assertEquals("Content outline radius", expected, outline.radius, .01f)
        if (maskRadius) {
            val mask = background.findDrawableByLayerId(android.R.id.mask) as GradientDrawable
            assertEquals("Installed mask radius", expected, mask.cornerRadius, .01f)
            assertEquals("Installed mask bounds", content.bounds, mask.bounds)
            mask.getOutline(outline)
            assertEquals("Mask outline radius", expected, outline.radius, .01f)
            assertEquals("Mask center", Color.WHITE, pixel(mask, 60, 20))
            assertEquals("Mask clipped corner", Color.TRANSPARENT, pixel(mask, 0, 0))
        }
    }

    private fun centerPixel(background: RippleDrawable): Int = pixel(background, 60, 20)

    private fun pixel(drawable: Drawable, x: Int, y: Int): Int {
        val bitmap = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888)
        return try {
            drawable.draw(Canvas(bitmap))
            bitmap.getPixel(x, y)
        } finally {
            bitmap.recycle()
        }
    }

    private fun main(block: () -> Unit) {
        var result: Result<Unit>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        checkNotNull(result).getOrThrow()
    }
}
