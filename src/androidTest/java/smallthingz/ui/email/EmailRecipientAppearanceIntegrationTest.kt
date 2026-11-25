package smallthingz.ui.email

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.LayerDrawable
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.common.ControlTheme
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeDarkSurfacePreset
import smallthingz.ui.components.NativeDarkSurfaces
import smallthingz.ui.components.NativeGrainPreset
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.components.withLaidOutPaintActivity
import smallthingz.ui.controls.ControlStyle
import smallthingz.ui.controls.textSurfaceRange
import smallthingz.ui.graphics.NoiseDrawable
import smallthingz.ui.graphics.NoiseTexture
import smallthingz.ui.graphics.noiseSurfaceRange
import smallthingz.ui.graphics.noiseSurfaceRangeOver

/** Proposal-only regressions. The queued callback test does not require a cold texture cache. */
@RunWith(AndroidJUnit4::class)
class EmailRecipientAppearanceIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun manuallyPaintedSelectionTracksGrainOffOnAndPressedStateWithoutEscapingShape() {
        awaitShader()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val theme = ControlTheme.resolve(context, false).withColorScheme(colors(0f))
            val row = EmailRecipientRow(context, theme).apply {
                measure(View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(96, View.MeasureSpec.EXACTLY))
                layout(0, 0, 240, 96)
                isSelected = true
            }
            fun render() = pixels(240, 96) { row.draw(it) }
            val flat = render()
            row.bindTheme(theme.withColorScheme(colors(.5f)))
            val noisy = render()
            assertTrue("Selected row must actually paint grain", (32 until 64).any { y ->
                (96 until 144).any { x -> flat[y * 240 + x] != noisy[y * 240 + x] }
            })
            assertTrue("Grain must remain inside the existing shape", flat.indices.all {
                flat[it] != 0 || noisy[it] == 0
            })
            assertArrayEquals("Repeated draws must not accumulate grain", noisy, render())
            row.bindTheme(theme)
            assertArrayEquals("Disabling grain restores flat paint", flat, render())
            row.bindTheme(theme.withColorScheme(colors(.5f)))
            assertArrayEquals("Re-enabling grain restores its retained paint", noisy, render())
            row.isSelected = false
            assertTrue("Inactive rows must not add grain to their popup", render().all { it == 0 })
            row.isPressed = true
            assertArrayEquals("Pressed and selected use the same paint", noisy, render())
            row.isPressed = false
            assertTrue(render().all { it == 0 })
        }
    }

    @Test fun presetOnlyRebindsRetainDetachedShapeChildrenFocusAndActions() {
        lateinit var row: EmailRecipientRow
        lateinit var theme: ControlTheme
        lateinit var label: TextView
        lateinit var action: ImageView
        var clicks = 0
        withLaidOutPaintActivity(mount = { activity ->
            theme = ControlTheme.resolve(activity, false).withColorScheme(colors(0f))
            row = EmailRecipientRow(activity, theme)
            label = TextView(activity).apply { text = "retained@example.org" }
            action = ImageView(activity).apply {
                isFocusableInTouchMode = true
                setOnClickListener { clicks++ }
            }
            row.addView(label, LinearLayout.LayoutParams(180, 64))
            row.addView(action, LinearLayout.LayoutParams(64, 64))
            listOf(row, label, action) // The paint-only selection must NEVER be an admission view.
        }) {
            instrumentation.runOnMainSync {
                val group = selection(row)
                val actionParent = action.parent
                val labelParent = label.parent
                assertTrue(action.requestFocus())
                row.isSelected = true
                var retainedGrain: Any? = null
                for ((strength, brightest) in listOf(.25f to 5, .5f to 9, 1f to 17,
                    0f to 0, .5f to 9)) {
                    row.bindTheme(theme.withColorScheme(colors(strength)))
                    assertEquals(textSurfaceRange(Color.BLACK, Color.rgb(brightest, brightest, brightest)),
                        group.paintedTextSurface)
                    val grain = field(group, "grain")
                    if (retainedGrain == null) retainedGrain = grain else assertSame(retainedGrain, grain)
                    assertSame(group, selection(row))
                    assertNull(group.parent)
                    assertFalse(group.isAttachedToWindow)
                    assertNull(row.background)
                    assertEquals(2, row.childCount)
                    assertSame(label, row.getChildAt(0))
                    assertSame(action, row.getChildAt(1))
                    assertSame(labelParent, label.parent)
                    assertSame(actionParent, action.parent)
                    assertEquals("retained@example.org", label.text.toString())
                    assertTrue(action.hasFocus())
                    row.isSelected = false
                    assertEquals(Color.TRANSPARENT, group.paintedFillColor)
                    assertEquals(0L, group.paintedTextSurface)
                    row.isPressed = true
                    assertEquals(Color.BLACK, group.paintedFillColor)
                    assertEquals(textSurfaceRange(Color.BLACK, Color.rgb(brightest, brightest, brightest)),
                        group.paintedTextSurface)
                    row.isSelected = true
                    row.isPressed = false
                    assertEquals(Color.BLACK, group.paintedFillColor)
                }
                row.bindTheme(theme.withColorScheme(colors(0f).copy(surfacePreset = null)))
                assertEquals(textSurfaceRange(Color.BLACK), group.paintedTextSurface)
                assertEquals(0, clicks)
                assertTrue(action.performClick())
                assertEquals(1, clicks)
                val initiallyNoisy = EmailRecipientRow(row.context, theme.withColorScheme(colors(.5f)))
                initiallyNoisy.isSelected = true
                assertEquals(textSurfaceRange(Color.BLACK, 0xff090909.toInt()), selection(initiallyNoisy).paintedTextSurface)
            }
        }
    }

    @Test fun queuedTextureReadyInvalidationReachesAttachedRowAndItsActualParent() {
        lateinit var row: TrackingRow
        lateinit var parent: TrackingParent
        withLaidOutPaintActivity(mount = { activity ->
            val theme = ControlTheme.resolve(activity, false).withColorScheme(colors(.5f))
            row = TrackingRow(activity, theme).apply { isSelected = true }
            parent = TrackingParent(activity).apply { addView(row, FrameLayout.LayoutParams(240, 96)) }
            listOf(parent, row)
        }) {
            val done = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            instrumentation.runOnMainSync {
                val base = ControlTheme.resolve(row.context, false)
                for (strength in listOf(0f, .25f, .5f, 1f, 0f, .5f))
                    row.bindTheme(base.withColorScheme(colors(strength)))
                val group = selection(row)
                val noise = field(checkNotNull(field(group, "grain")), "grain") as NoiseDrawable
                assertNull(group.parent)
                assertTrue(row.isAttachedToWindow)
                assertTrue(row.post {
                    try {
                        // Clear the row's dirty drawing state before measuring this exact event.
                        pixels(row.width, row.height) { row.draw(it) }
                        row.invalidations = 0
                        parent.descendantInvalidations = 0
                        noise.invalidateSelf() // Exact event dispatched by NoiseTexture's main-thread completion.
                        assertTrue("Texture completion must invalidate the real row", row.invalidations > 0)
                        if (row.isHardwareAccelerated) assertTrue("The attached parent must receive invalidation",
                            parent.descendantInvalidations > 0)
                    } catch (error: Throwable) { failure.set(error) }
                    finally { done.countDown() }
                })
            }
            assertTrue("Posted texture-ready callback was not delivered", done.await(5, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Texture-ready ownership failed", it) }
        }
    }

    @Test fun popupRetainsPaddingClippingBorderAndIndependentDrawableCallbacks() {
        awaitShader()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val base = ControlTheme.resolve(context, false)
            for (strength in listOf(0f, .25f, .5f, 1f)) {
                // Opaque border isolates draw order; the default 14%-alpha border shows grain through it.
                val preset = NativeDarkSurfacePreset.Amoled.copy(style = NativeDarkSurfaces.GRAY,
                    foregroundGrain = strength, borderLightness = 1f)
                val theme = base.withColorScheme(colors(strength).copy(surfacePreset = preset))
                val first = emailRecipientPopupBackground(context, theme) as LayerDrawable
                val second = emailRecipientPopupBackground(context, theme) as LayerDrawable
                val controls = ControlStyle(context, theme.colors.pageControls)
                val recipe = theme.appearance.modals.anchoredPanel
                val expectedPad = controls.dp(recipe.verticalPaddingDp.roundToInt())
                val padding = Rect()
                first.getPadding(padding)
                assertEquals(Rect(expectedPad, expectedPad, expectedPad, expectedPad), padding)
                assertEquals(if (strength == 0f) 1 else 2, first.numberOfLayers)
                val side = maxOf(160, controls.dp(120))
                val end = side - 8
                first.setBounds(8, 8, end, end)
                second.setBounds(8, 8, end, end)
                val firstCallback = TrackingCallback()
                val secondCallback = TrackingCallback()
                first.callback = firstCallback
                second.callback = secondCallback
                try {
                    if (strength > 0f) {
                        val noise = (first.getDrawable(0) as DrawableWrapper).drawable as NoiseDrawable
                        noise.invalidateSelf()
                        assertEquals(1, firstCallback.invalidations)
                        assertEquals(0, secondCallback.invalidations)
                    }
                    val rendered = pixels(side, side) { first.draw(it) }
                    for ((x, y) in listOf(0 to 0, 8 to 8, end - 1 to 8, 8 to end - 1, end - 1 to end - 1))
                        assertEquals("Rounded popup corner at $x,$y", 0, rendered[y * side + x])
                    val center = side / 2
                    val interior = (center - 20..center + 20).flatMap { y ->
                        (center - 20..center + 20).map { x -> rendered[y * side + x] }
                    }.toSet()
                    if (strength == 0f) assertEquals(setOf(Color.BLACK), interior)
                    else assertTrue("Enabled grain must change interior pixels", interior.size > 1)
                    val original = controls.shape(Color.BLACK, recipe.cornerRadiusDp.roundToInt(), theme.colors.border)
                    original.setBounds(8, 8, end, end)
                    val reference = pixels(side, side) { original.draw(it) }
                    assertEquals("Grain must not overpaint the border", reference[8 * side + center], rendered[8 * side + center])
                    val secondBefore = pixels(side, side) { second.draw(it) }
                    first.alpha = 0
                    assertTrue(pixels(side, side) { first.draw(it) }.all { it == 0 })
                    assertArrayEquals(secondBefore, pixels(side, side) { second.draw(it) })
                } finally { first.callback = null; second.callback = null }
            }
        }
    }

    @Test fun matchedInkQualifiesBothNoisySurfacesAndTranslucentSelection() {
        instrumentation.runOnMainSync {
            val base = ControlTheme.resolve(instrumentation.targetContext, false)
            val nearThreshold = 0xff767676.toInt()
            val flat = colors(0f).copy(surfaceContainerHigh = Color.WHITE, surfaceContainerHighest = Color.WHITE,
                primary = nearThreshold, onSurface = Color.BLACK)
            assertTrue(ColorUtils.calculateContrast(nearThreshold, Color.WHITE) >= 4.5)
            assertTrue(ColorUtils.calculateContrast(nearThreshold, 0xffeeeeee.toInt()) < 4.5)
            assertEquals(nearThreshold, emailRecipientMatchColor(base.withColorScheme(flat)))
            assertEquals(Color.BLACK, emailRecipientMatchColor(base.withColorScheme(flat.copy(surfacePreset = NativeGrainPreset(0f, 1f)))))
            // With full grain, #696969 qualifies on noisy white but fails on noisy #eeeeee.
            for ((popup, selected) in listOf(Color.WHITE to 0xffeeeeee.toInt(),
                0xffeeeeee.toInt() to Color.WHITE, Color.WHITE to 0x80000000.toInt())) {
                val palette = flat.copy(surfacePreset = NativeGrainPreset(0f, 1f), primary = 0xff696969.toInt(),
                    surfaceContainerHigh = popup, surfaceContainerHighest = selected)
                val popupRange = noiseSurfaceRange(popup, 255)
                val selectedRange = noiseSurfaceRangeOver(popupRange, selected, selected ushr 24)
                val result = emailRecipientMatchColor(base.withColorScheme(palette))
                assertEquals(Color.BLACK, result)
                if (selected ushr 24 == 255) {
                    val safeRange = if (popup == Color.WHITE) popupRange else selectedRange
                    assertTrue("Only one surface should reject this primary", minContrast(palette.primary, safeRange) >= 4.5)
                    assertTrue(minContrast(result, popupRange, selectedRange) >= 4.5)
                }
                assertTrue(minContrast(palette.primary, popupRange, selectedRange) < 4.5)
                assertTrue(minContrast(result, popupRange, selectedRange) > minContrast(palette.primary, popupRange, selectedRange))
            }
            val midpoint = flat.copy(surfacePreset = NativeGrainPreset(0f, 1f), primary = 0xff757575.toInt(),
                onSurface = 0xff757575.toInt(), surfaceContainerHigh = 0xff757575.toInt(), surfaceContainerHighest = 0xff757575.toInt())
            val range = noiseSurfaceRange(0xff757575.toInt(), 255)
            val chosen = emailRecipientMatchColor(base.withColorScheme(midpoint))
            assertEquals(Color.BLACK, chosen)
            assertTrue(minContrast(chosen, range) < 4.5) // Best achievable, not an impossible AA guarantee.
            assertTrue(minContrast(chosen, range) > minContrast(Color.WHITE, range))
        }
    }

    private fun colors(strength: Float) = NativeColorScheme.fallback(false).copy(
        surfaceContainerHighest = Color.BLACK, surfaceContainerHigh = Color.BLACK,
        primary = Color.WHITE, onSurface = Color.WHITE, surfacePreset = NativeGrainPreset(0f, strength))
    private fun selection(row: EmailRecipientRow) = field(row, "selection", EmailRecipientRow::class.java) as NativeGroupLayout
    private fun field(owner: Any, name: String, type: Class<*> = owner.javaClass): Any? =
        type.getDeclaredField(name).let { it.isAccessible = true; it.get(owner) }
    private fun minContrast(ink: Int, vararg ranges: Long) = ranges.flatMap {
        listOf((it ushr 32).toInt(), it.toInt())
    }.minOf { ColorUtils.calculateContrast(ink, it) }
    private fun pixels(width: Int, height: Int, draw: (Canvas) -> Unit): IntArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            draw(Canvas(bitmap))
            return IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }
        } finally { bitmap.recycle() }
    }
    private fun awaitShader() {
        val ready = CountDownLatch(1)
        lateinit var observer: NoiseDrawable
        val callback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) { if (NoiseTexture.shader(0) != null) ready.countDown() }
            override fun scheduleDrawable(who: Drawable, what: Runnable, at: Long) = Unit
            override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
        }
        instrumentation.runOnMainSync {
            observer = NoiseDrawable().apply { this.callback = callback }
            if (NoiseTexture.shader(0) != null) ready.countDown()
        }
        try { assertTrue("Noise shader did not become ready", ready.await(5, TimeUnit.SECONDS)) }
        finally { instrumentation.runOnMainSync { observer.callback = null } }
    }
    private class TrackingCallback : Drawable.Callback {
        var invalidations = 0
        override fun invalidateDrawable(who: Drawable) { invalidations++ }
        override fun scheduleDrawable(who: Drawable, what: Runnable, at: Long) = Unit
        override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
    }
    private class TrackingRow(context: Context, theme: ControlTheme) : EmailRecipientRow(context, theme) {
        var invalidations = 0
        override fun invalidate() { invalidations++; super.invalidate() }
    }
    private class TrackingParent(context: Context) : FrameLayout(context) {
        var descendantInvalidations = 0
        override fun onDescendantInvalidated(child: View, target: View) {
            descendantInvalidations++
            super.onDescendantInvalidated(child, target)
        }
    }
}
