package smallthingz.ui.components

import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.Button
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.controls.ControlPalette
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class NativeSettingsRowIntegrationTest {
    @Test fun newRowLabelsUseTheConfiguredDensityAndTypography() = main {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        fun configured(fontScale: Float, dpi: Int) =
            base.createConfigurationContext(Configuration(base.resources.configuration).also {
                it.fontScale = fontScale
                it.densityDpi = dpi
            })
        val palette = ControlPalette(Color.WHITE, Color.WHITE, Color.BLACK, Color.DKGRAY,
            Color.BLACK, Color.WHITE, Color.BLACK, Color.GRAY)
        val title = NativeTextStyle(19f, lineHeightSp = 27f, weight = 600)
        val detail = NativeTextStyle(13f, letterSpacingSp = 0.3f)
        val style = NativeSettingsRowStyle(titleTextStyle = title, detailTextStyle = detail, valueSizeSp = 15f)
        for (context in listOf(configured(1f, 160), configured(1.6f, 240))) {
            val row = NativeSettingsRow(context, palette, style)
            row.bind("Mail", "Settings", value = "On")
            val density = context.resources.displayMetrics.density
            assertEquals((style.horizontalPaddingDp * density).roundToInt(), row.paddingStart)
            assertEquals((style.minHeightDp * density).roundToInt(), row.minimumHeight)
            for ((label, textStyle) in listOf(row.titleView to title, row.detailView to detail,
                    row.valueView to NativeTextStyle(style.valueSizeSp))) {
                val reference = NativeText(context, palette, textStyle)
                assertEquals(reference.textSize, label.textSize, 0.01f)
                assertEquals(reference.letterSpacing, label.letterSpacing, 0.0001f)
                assertEquals(reference.lineSpacingExtra, label.lineSpacingExtra, 0.01f)
                assertEquals(reference.typeface, label.typeface)
            }
            assertEquals(palette.content, NativeText(context, palette).currentTextColor)
            assertEquals(palette.content, row.titleView.currentTextColor)
            assertEquals(palette.muted, row.detailView.currentTextColor)
            assertEquals(palette.muted, row.valueView.currentTextColor)
        }
    }

    @Test fun selectedSurfaceQualifiesOwnedInkWithoutChangingAccessoryStateOrAction() = main {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val palette = ControlPalette(Color.WHITE, Color.WHITE, 0xff151515.toInt(), 0xff555555.toInt(),
            Color.BLACK, Color.WHITE, Color.BLACK, 0xffaaaaaa.toInt())
        val row = NativeSettingsRow(context, palette, NativeSettingsRowStyle(containerFill = true))
        var selections = 0
        var closes = 0
        var closeInk = 0
        val close = Button(context).apply {
            contentDescription = "Close tab"
            isSelected = true
            setOnClickListener { closes++ }
        }
        row.bind("Tab", "Site", ColorDrawable(Color.WHITE), "1") { selections++ }
        row.setAccessory(close) { closeInk = it }
        val icon = row.getChildAt(0) as ImageView
        val background = row.background as RippleDrawable

        fun assertPaint(title: Int, detail: Int, container: Int, pressed: Boolean = false) {
            assertEquals(title, row.titleView.currentTextColor)
            assertEquals(detail, row.detailView.currentTextColor)
            assertEquals(detail, row.valueView.currentTextColor)
            assertEquals(detail, icon.imageTintList!!.defaultColor)
            assertEquals(title, row.accessoryContentColor)
            assertEquals(title, closeInk)
            if (!pressed) {
                val bitmap = Bitmap.createBitmap(80, 56, Bitmap.Config.ARGB_8888)
                try {
                    background.jumpToCurrentState()
                    background.setBounds(0, 0, 80, 56)
                    background.draw(Canvas(bitmap))
                    assertEquals(container, bitmap.getPixel(40, 28))
                } finally {
                    bitmap.recycle()
                }
            }
        }

        assertPaint(palette.content, palette.muted, palette.surface)
        row.isSelected = true
        assertPaint(Color.WHITE, Color.WHITE, Color.BLACK)
        row.isPressed = true
        assertPaint(Color.WHITE, Color.WHITE, Color.BLACK, pressed = true)
        row.isPressed = false
        assertTrue(close.performClick())
        assertEquals(1, closes)
        assertEquals(0, selections)
        assertTrue(row.performClick())
        assertEquals(1, selections)
        assertEquals(1, closes)

        row.isSelected = false
        assertPaint(palette.content, palette.muted, palette.surface)
        assertTrue(close.isSelected)
        row.isEnabled = false
        assertTrue(close.isEnabled)
        assertEquals(palette.content, closeInk)
        assertTrue(close.performClick())
        assertEquals(2, closes)
        row.performClick()
        assertEquals(1, selections)
        assertSame(background, row.background)
    }

    @Test fun closeInkFollowsPaintedIntermediateFillAndReplacementRetiresOldPainter() {
        assumeTrue("Requires Android animations enabled", ValueAnimator.areAnimatorsEnabled())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val selectedFrame = CountDownLatch(1)
        val replacementPainted = CountDownLatch(1)
        val palette = ControlPalette(Color.WHITE, Color.WHITE, Color.BLACK, Color.BLACK,
            Color.BLACK, Color.WHITE, Color.BLACK, Color.BLACK)
        val bitmap = Bitmap.createBitmap(80, 56, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        var mountedRow: NativeSettingsRow? = null
        var oldPaints = 0
        var oldPaintsAtReplacement = 0
        var startingInk = 0
        var minimumContrast = Double.POSITIVE_INFINITY

        fun sample(row: NativeSettingsRow, color: Int): Int {
            row.background.draw(canvas)
            val fill = bitmap.getPixel(40, 28)
            minimumContrast = minOf(minimumContrast, ColorUtils.calculateContrast(color, fill))
            return fill
        }

        try {
            main {
                val row = NativeSettingsRow(activity, palette, NativeSettingsRowStyle(containerFill = true))
                mountedRow = row
                row.bind("Tab")
                activity.setContentView(row)
            }
            instrumentation.waitForIdleSync()
            main {
                val row = checkNotNull(mountedRow)
                assertTrue(row.isAttachedToWindow && row.isShown)
                row.background.setBounds(0, 0, bitmap.width, bitmap.height)
                row.setAccessory(Button(activity)) { ink ->
                    oldPaints++
                    startingInk = ink
                    val fill = sample(row, ink)
                    if (fill != Color.WHITE && fill != Color.BLACK) selectedFrame.countDown()
                }
                row.isSelected = true
                assertEquals(Color.BLACK, startingInk) // Retarget does not change the currently painted fill.
            }
            assertTrue("Expected an accessory update on an intermediate selected fill",
                selectedFrame.await(5, TimeUnit.SECONDS))
            main {
                val row = checkNotNull(mountedRow)
                oldPaintsAtReplacement = oldPaints
                row.setAccessory(Button(activity)) { ink ->
                    sample(row, ink)
                    if (!row.isSelected && ink == Color.BLACK) replacementPainted.countDown()
                }
                row.isSelected = false
            }
            assertTrue("Expected the replacement accessory to follow the reverse transition",
                replacementPainted.await(5, TimeUnit.SECONDS))
            main {
                assertEquals(oldPaintsAtReplacement, oldPaints)
                assertTrue("Accessory paint must meet 4.5:1 contrast", minimumContrast >= 4.5)
            }
        } finally {
            main {
                mountedRow?.setAccessory(null)
                activity.finish()
                bitmap.recycle()
            }
        }
    }

    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
}
