package smallthingz.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.RippleDrawable
import kotlin.math.pow
import android.text.Spanned
import android.text.style.LineHeightSpan
import smallthingz.ui.text.style.LineHeightStyle
import android.view.View
import android.widget.ImageView
import android.widget.Button
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.controls.ControlPalette
import smallthingz.ui.controls.ControlStyle
import kotlin.math.roundToInt

/** Real Android View tests on the physical device; no window/focus or rendered-pixel claim. */
@RunWith(AndroidJUnit4::class)
class NativeSegmentedIntegrationTest {
    private val palette = ControlPalette(-1, -1, 0xff202020.toInt(), 0xff606060.toInt(),
        0xff3458a0.toInt(), -1, 0xff3458a0.toInt(), 0xffaaaaaa.toInt())
    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    @Test fun compactRowKeepsExactHeightEqualWidthsRtlAndRetainedSelectionNodes() = main {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val group = NativeSegmentedChoice(context, palette, NativeChoiceStyle(outlined = false),
            gapDp = 0f, singleRow = true, activateOnFocusNavigation = false)
        val options = listOf(NativeChoice("time", "Time"), NativeChoice("size", "Size"))
        group.bind(options, "time")
        val time = group.choiceView("time")!!
        val size = group.choiceView("size")!!
        val height = (34 * context.resources.displayMetrics.density).roundToInt()
        group.measure(View.MeasureSpec.makeMeasureSpec(251, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        group.layout(0, 0, 251, height)
        assertEquals(height, time.height)
        assertEquals(height, size.height)
        assertEquals(251, time.width + size.width)
        group.layoutDirection = View.LAYOUT_DIRECTION_RTL
        // Unattached fixtures have no ViewRoot traversal to resolve direction and service requestLayout.
        group.measure(View.MeasureSpec.makeMeasureSpec(251, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        group.layout(0, 0, 251, height)
        assertTrue(time.left > size.left)
        group.bind(options, "size")
        assertSame(time, group.choiceView("time"))
        assertTrue(size.isChecked)
        assertFalse(time.isChecked)
        group.close()
    }

    @Test fun controlledActivationHonorsOwnerGateDisabledStateAndPermanentRetirement() = main {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val host = object : FrameLayout(context), NativeInputGate {
            var active = true
            override val acceptsNativeInput get() = active
        }
        val group = NativeSegmentedChoice(context, palette)
        host.addView(group)
        val options = listOf(NativeChoice("time", "Time"), NativeChoice("size", "Size"))
        group.bind(options, "time")
        var calls = 0
        group.onSelectionRequest = { id -> calls++; group.bind(options, id); true }
        val size = group.choiceView("size")!!
        assertTrue(size.performClick())
        assertEquals(1, calls)
        assertEquals("size", group.selectedId)
        host.active = false
        assertFalse(size.performClick())
        host.active = true
        group.isEnabled = false
        assertFalse(size.performClick())
        group.isEnabled = true
        group.close()
        group.isEnabled = true
        group.bind(options, "time")
        assertFalse(size.performClick())
        assertEquals(1, calls)
    }

    @Test fun unattachedAnimatedBindingSnapsAndPaletteRebindKeepsTheSameBackground() = main {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val chip = NativeChip(context, palette, NativeChoiceStyle(animateSelection = true,
            normalTextRole = NativeTextRole.MUTED, selectedTextRole = NativeTextRole.ON_ACCENT))
        chip.bind("Choice", true)
        assertEquals(palette.onAccent, chip.currentTextColor)
        val background = chip.background
        val dark = palette.copy(surface = 0xff181818.toInt(), selection = 0xffa9c0ff.toInt(),
            onAccent = 0xff182950.toInt(), muted = 0xffaaaaaa.toInt())
        chip.applyPalette(dark)
        assertSame(background, chip.background)
        assertEquals(dark.onAccent, chip.currentTextColor)
        chip.bindChecked(false)
        assertEquals(dark.muted, chip.currentTextColor)
        chip.close()
    }

    @Test fun focusedUnselectedButtonsAndIconsKeepNormalFillAndFocusOutline() = main {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val paint = ControlStyle(context, palette)
        val button = Button(context)
        val icon = ImageView(context)
        paint.applyButton(button)
        paint.applyIcon(icon)
        for (background in listOf(button.background, icon.background)) {
            background.state = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_focused)
            background.setBounds(0, 0, 120, 60)
            val bitmap = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888)
            background.draw(Canvas(bitmap))
            assertEquals(0, bitmap.getPixel(60, 30))
            assertTrue((0 until 10).any { y -> bitmap.getPixel(60, y) != 0 })
            bitmap.recycle()
        }
    }

    @Test fun animatedDisabledOverridesMatchStaticChoicesAcrossPaletteAndSelectionRebinding() = main {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val style = NativeChoiceStyle(disabledContent = NativeChoiceColor(NativeTextRole.ACCENT, .4f),
            disabledSelectedContent = NativeChoiceColor(NativeTextRole.ON_ACCENT, .6f),
            disabledSelectedFill = NativeChoiceColor(NativeTextRole.MUTED, .3f),
            disabledOutline = NativeChoiceColor(NativeTextRole.CONTENT, .7f),
            disabledSelectedOutline = NativeChoiceColor(NativeTextRole.ACCENT, .8f))
        val animated = NativeChip(context, palette, style.copy(animateSelection = true))
        val plain = NativeChip(context, palette, style)
        for (colors in listOf(palette, palette.copy(accent = 0xffa9c0ff.toInt(), muted = 0xffaaaaaa.toInt()))) {
            animated.applyPalette(colors); plain.applyPalette(colors)
            for (selected in listOf(false, true)) {
                animated.bind("Choice", selected, enabled = false)
                plain.bind("Choice", selected, enabled = false)
                assertEquals(plain.currentTextColor, animated.currentTextColor)
                assertEquals(plain.compoundDrawableTintList!!.getColorForState(plain.drawableState, 0),
                    animated.compoundDrawableTintList!!.getColorForState(animated.drawableState, 0))
                fun pixels(view: View): Bitmap {
                    view.background.setBounds(0, 0, 120, 60)
                    return Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888).also { view.background.draw(Canvas(it)) }
                }
                val expected = pixels(plain); val actual = pixels(animated)
                try { assertTrue(expected.sameAs(actual)) } finally { expected.recycle(); actual.recycle() }
                val textColors = animated.textColors
                val iconColors = animated.compoundDrawableTintList
                repeat(8) { frame ->
                    animated.isPressed = frame % 2 == 0
                    animated.refreshDrawableState()
                    assertSame(textColors, animated.textColors)
                    assertSame(iconColors, animated.compoundDrawableTintList)
                }
            }
        }
        animated.close(); plain.close()
    }

    @Test fun animatedDefaultDisabledInkUsesCurrentPaletteAfterRetainedRebind() = main {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val animated = NativeChip(context, palette, NativeChoiceStyle(animateSelection = true))
        val plain = NativeChip(context, palette)
        val dark = palette.copy(surface = 0xff181818.toInt(), content = 0xffeeeeee.toInt(),
            muted = 0xffaaaaaa.toInt(), selection = 0xffa9c0ff.toInt(), onAccent = 0xff182950.toInt())
        var oldDisabled: Int? = null
        for (colors in listOf(palette, dark, palette)) {
            animated.applyPalette(colors); plain.applyPalette(colors)
            for (selected in listOf(false, true)) {
                animated.bind("Choice", selected, enabled = false)
                plain.bind("Choice", selected, enabled = false)
                assertEquals(plain.currentTextColor, animated.currentTextColor)
                assertEquals(plain.compoundDrawableTintList!!.getColorForState(plain.drawableState, 0),
                    animated.compoundDrawableTintList!!.getColorForState(animated.drawableState, 0))
            }
            oldDisabled?.let { assertNotEquals(it, animated.currentTextColor) }
            oldDisabled = animated.currentTextColor
        }
        animated.close(); plain.close()
    }

    @Test fun componentButtonsAndIconsUseReadableDefaultSelectedInkForEveryTone() = main {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (selection in listOf(palette.accent, 0xffdbe5ff.toInt())) {
            val colors = palette.copy(selection = selection)
            for (tone in NativeButtonTone.entries) {
                val button = NativeButton(context, colors, NativeButtonStyle(tone = tone))
                val icon = NativeIconButton(context, colors, NativeIconButtonStyle(tone = tone))
                val expected = if (tone == NativeButtonTone.FILLED || selection == palette.accent)
                    palette.onAccent else palette.content
                for (focused in listOf(false, true)) {
                    val state = if (focused) intArrayOf(android.R.attr.state_enabled,
                        android.R.attr.state_selected, android.R.attr.state_focused)
                        else intArrayOf(android.R.attr.state_enabled, android.R.attr.state_selected)
                    assertEquals(expected, button.textColors.getColorForState(state, 0))
                    assertEquals(expected, button.compoundDrawableTintList!!.getColorForState(state, 0))
                    assertEquals(expected, icon.imageTintList!!.getColorForState(state, 0))
                }
            }
        }
    }

    @Test fun animatedIconDefaultsRemainIndependentOfTextOverridesAtBothEndpoints() = main {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val chip = NativeChip(context, palette, NativeChoiceStyle(animateSelection = true,
            normalTextRole = NativeTextRole.MUTED, selectedTextRole = NativeTextRole.MUTED))
        val dark = palette.copy(surface = 0xff181818.toInt(), content = 0xffeeeeee.toInt(),
            muted = 0xffaaaaaa.toInt(), selection = 0xffa9c0ff.toInt(), onAccent = 0xff182950.toInt())
        for (colors in listOf(palette, dark)) {
            chip.applyPalette(colors)
            chip.bind("Choice", false)
            assertEquals(colors.muted, chip.currentTextColor)
            assertEquals(colors.content, chip.compoundDrawableTintList!!.defaultColor)
            chip.bindChecked(true)
            assertEquals(colors.muted, chip.currentTextColor)
            assertEquals(colors.onAccent, chip.compoundDrawableTintList!!.defaultColor)
        }
        chip.close()
    }

    @Test fun selectedStaticRipplePaintingPreservesContrast() {
        lateinit var chip: NativeChip
        lateinit var ripples: List<RippleDrawable>
        main {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val colors = palette.copy(surface = -0x1000000, selection = 0xff747474.toInt(),
                content = -1, accent = -1, onAccent = -0x1000000)
            val style = ControlStyle(context, colors)
            val button = NativeButton(context, colors)
            val icon = NativeIconButton(context, colors)
            chip = NativeChip(context, colors)
            button.isSelected = true; icon.isSelected = true; chip.bind("Selected", true)
            assertEquals(-1, button.currentTextColor)
            assertEquals(-1, icon.imageTintList!!.getColorForState(icon.drawableState, 0))
            assertEquals(-1, chip.currentTextColor)
            ripples = listOf(style.background(), button.background as RippleDrawable,
                icon.background as RippleDrawable, chip.background as RippleDrawable)
        }
        try {
            for (ripple in ripples) {
                var pixel = 0xff747474.toInt()
                main {
                    ripple.setBounds(0, 0, 120, 60)
                    ripple.setHotspot(60f, 30f)
                    ripple.state = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_selected,
                        android.R.attr.state_focused, android.R.attr.state_window_focused)
                    ripple.jumpToCurrentState()
                }
                // Solid ripples jump immediately; newer patterned focus layers start on first draw.
                val deadline = android.os.SystemClock.uptimeMillis() + 2000
                do {
                    main {
                        val bitmap = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888)
                        try { ripple.draw(Canvas(bitmap)); pixel = bitmap.getPixel(60, 30) }
                        finally { bitmap.recycle() }
                    }
                    if (pixel != 0xff747474.toInt()) break
                    android.os.SystemClock.sleep(16)
                } while (android.os.SystemClock.uptimeMillis() < deadline)
                assertEquals(255, pixel ushr 24)
                val red = pixel ushr 16 and 255
                assertEquals(red, pixel ushr 8 and 255)
                assertEquals(red, pixel and 255)
                assertTrue("Selected ripple must visibly darken #747474; actual=${pixel.toUInt().toString(16)}", red < 116)
                val gray = red / 255.0
                val luminance = if (gray <= .04045) gray / 12.92 else ((gray + .055) / 1.055).pow(2.4)
                assertTrue(1.05 / (luminance + .05) >= 4.5)
            }
        } finally {
            main {
                ripples.forEach { it.state = intArrayOf(); it.jumpToCurrentState(); it.setVisible(false, false) }
                chip.close()
            }
        }
    }

    @Test fun transparentSelectedFillKeepsSelectedTextAndIconRolesLikeStaticChoices() = main {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val style = NativeChoiceStyle(normalTextRole = NativeTextRole.MUTED,
            selectedTextRole = NativeTextRole.ACCENT, normalIconRole = NativeTextRole.MUTED,
            selectedIconRole = NativeTextRole.CONTENT)
        val plain = NativeChip(context, palette, style)
        val animated = NativeChip(context, palette, style.copy(animateSelection = true))
        for (colors in listOf(palette, palette.copy(selection = 0))) {
            plain.applyPalette(colors); animated.applyPalette(colors)
            for (selected in listOf(false, true)) {
                plain.bind("Choice", selected); animated.bind("Choice", selected)
                assertEquals(plain.currentTextColor, animated.currentTextColor)
                assertEquals(plain.compoundDrawableTintList!!.getColorForState(plain.drawableState, 0),
                    animated.compoundDrawableTintList!!.getColorForState(animated.drawableState, 0))
            }
        }
        plain.close(); animated.close()
    }

    @Test fun transparentSelectedDefaultInkAndFocusedPressedLayersAgree() = main {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (transparent in listOf(0, 0x00123456)) {
            val colors = palette.copy(surface = -1, content = -0x1000000,
                accent = 0xffdbe5ff.toInt(), onAccent = 0xff3458a0.toInt(), selection = transparent)
            val chip = NativeChip(context, colors, NativeChoiceStyle(animateSelection = true))
            chip.bind("Selected", true)
            assertEquals(colors.onAccent, chip.currentTextColor)
            for (state in listOf(
                intArrayOf(android.R.attr.state_enabled, android.R.attr.state_selected, android.R.attr.state_focused),
                intArrayOf(android.R.attr.state_enabled, android.R.attr.state_selected,
                    android.R.attr.state_focused, android.R.attr.state_pressed))) {
                chip.background.state = state
                chip.background.setBounds(0, 0, 120, 60)
                val bitmap = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(colors.surface)
                chip.background.draw(Canvas(bitmap))
                try {
                    val background = bitmap.getPixel(60, 30)
                    assertEquals(255, background ushr 24)
                    assertEquals(colors.onAccent, chip.currentTextColor)
                    fun luminance(color: Int): Double {
                        fun channel(shift: Int): Double {
                            val value = (color ushr shift and 255) / 255.0
                            return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
                        }
                        return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
                    }
                    val foregroundLuminance = luminance(chip.currentTextColor)
                    val backgroundLuminance = luminance(background)
                    val ratio = (maxOf(foregroundLuminance, backgroundLuminance) + .05) /
                        (minOf(foregroundLuminance, backgroundLuminance) + .05)
                    assertTrue("Rendered selected text contrast=$ratio background=${background.toUInt().toString(16)}", ratio >= 4.5)
                } finally { bitmap.recycle() }
            }
            chip.bindChecked(false)
            assertEquals(colors.content, chip.currentTextColor)
            chip.close()
        }
    }

    @Test fun compactSingleRowBoundsDefaultGapsAtZeroNarrowAndNormalWidths() = main {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val group = NativeSegmentedChoice(context, palette, singleRow = true)
        group.bind(listOf(NativeChoice("a", "A"), NativeChoice("b", "B"), NativeChoice("c", "C")), "a")
        val gap = (4 * context.resources.displayMetrics.density).roundToInt()
        val height = (34 * context.resources.displayMetrics.density).roundToInt()
        for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
            group.layoutDirection = direction
            for (width in listOf(0, 1, gap - 1, 2 * gap - 1, 2 * gap, 2 * gap + 1, 300)) {
                group.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                group.layout(0, 0, width, height)
                val children = (0 until group.childCount).map { group.getChildAt(it) }.sortedWith(compareBy<View> { it.left }.thenBy { it.right })
                children.forEach {
                    assertTrue(it.left >= 0 && it.right >= it.left && it.right <= width)
                    assertEquals(height, it.height)
                }
                val firstGap = children[1].left - children[0].right
                val secondGap = children[2].left - children[1].right
                assertEquals(firstGap, secondGap)
                assertEquals(width, children.sumOf { it.width } + firstGap + secondGap)
                if (width >= 2 * gap) assertEquals(gap, firstGap)
            }
        }
        group.close()
    }

    @Test fun selectedOnlyLineHeightRestoresOriginalMetricsAndRetiresItsSpan() = main {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val chip = NativeChip(context, palette, NativeChoiceStyle(selectedTextStyle = NativeTextStyle(
            sizeSp = 14f, lineHeightSp = 80f,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None))))
        fun measure() {
            chip.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            chip.layout(0, 0, 300, chip.measuredHeight)
        }
        fun lineSpans(): Int = (chip.text as? Spanned)?.let {
            it.getSpans(0, it.length, LineHeightSpan::class.java).size
        } ?: 0
        chip.bind("Choice", false); measure()
        val height = chip.measuredHeight; val baseline = chip.baseline
        val lineHeight = checkNotNull(chip.layout).height
        assertEquals(0, lineSpans())
        repeat(2) {
            chip.bindChecked(true); measure()
            assertTrue(lineSpans() > 0)
            assertTrue(checkNotNull(chip.layout).height > lineHeight)
            chip.bindChecked(false); measure()
            assertEquals(0, lineSpans())
            assertEquals(height, chip.measuredHeight)
            assertEquals(baseline, chip.baseline)
            assertEquals(lineHeight, checkNotNull(chip.layout).height)
        }
        chip.close()
    }

    @Test fun defaultSelectedPaintContrastsAndExplicitOverrideAndUnchangedDrawableSurvive() = main {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (selection in listOf(palette.accent, 0xffdbe5ff.toInt())) {
            val colors = palette.copy(selection = selection)
            val button = Button(context)
            val paint = ControlStyle(context, colors)
            paint.applyButton(button)
            val background = button.background
            paint.applyButton(button)
            assertSame(background, button.background)
            button.isSelected = true
            assertNotEquals(selection, button.currentTextColor)
            assertEquals(colors.accent, paint.foregroundStates(selected = colors.accent)
                .getColorForState(intArrayOf(android.R.attr.state_enabled, android.R.attr.state_selected), 0))
            val chip = NativeChip(context, colors)
            chip.bind("Choice", true)
            assertNotEquals(selection, chip.currentTextColor)
            chip.close()
        }
    }
}
