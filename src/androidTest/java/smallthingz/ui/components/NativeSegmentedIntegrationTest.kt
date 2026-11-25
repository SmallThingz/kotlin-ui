package smallthingz.ui.components

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.RippleDrawable
import kotlin.math.pow
import android.text.Spanned
import android.text.style.LineHeightSpan
import smallthingz.ui.text.style.LineHeightStyle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
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

    @Test fun providerAccessoryMeasuresIntrinsicWidthAndReflows() = main {
        val host = InstrumentationRegistry.getInstrumentation().targetContext
        val context = host.createConfigurationContext(Configuration(host.resources.configuration).apply { fontScale = 1f })
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        val selector = NativeUiAppearance.Default.settingsSelectors.createSegmentedControl(context, palette, density)
        val provider = NativeSettingsRow(context, palette)
        val options = listOf(NativeChoice("chatgpt", "ChatGPT"), NativeChoice("cloudflare", "Cloudflare"))
        try {
            selector.bind(options, "cloudflare", palette, density)
            selector.onSelectionRequest = { error("Measuring or rebinding must not request selection") }
            provider.bind("Provider")
            provider.setAccessory(selector)
            val unconstrained = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            fun layout(widthDp: Int) {
                provider.measure(View.MeasureSpec.makeMeasureSpec(dp(widthDp), View.MeasureSpec.EXACTLY), unconstrained)
                provider.layout(0, 0, provider.measuredWidth, provider.measuredHeight)
            }
            fun bounds(child: View) = Rect(0, 0, child.width, child.height).also {
                provider.offsetDescendantRectToMyCoords(child, it)
            }
            selector.measure(unconstrained, unconstrained)
            val preferred = selector.measuredWidth
            assertTrue(preferred > 0)
            layout(384)
            assertEquals(preferred, selector.width)
            val titleBounds = bounds(provider.titleView)
            val accessoryBounds = bounds(selector)
            assertTrue(titleBounds.right <= accessoryBounds.left)
            assertTrue(titleBounds.top < accessoryBounds.bottom && accessoryBounds.top < titleBounds.bottom)
            val surface = selector.getChildAt(0) as NativeGroupLayout
            val hostView = surface.pointerContent.getChildAt(0) as ViewGroup
            val choices = hostView.getChildAt(0) as NativeSegmentedChoice
            val cloudflare = requireNotNull(choices.choiceView("cloudflare"))
            val chatgpt = requireNotNull(choices.choiceView("chatgpt"))
            layout(132)
            assertTrue(bounds(selector).top >= bounds(provider.titleView).bottom)
            assertTrue(chatgpt.top < cloudflare.top)
            assertSame(cloudflare, choices.choiceView("cloudflare"))
            val layout = requireNotNull(cloudflare.layout)
            assertEquals("Cloudflare".length, layout.getLineEnd(layout.lineCount - 1))
            assertTrue(layout.height <= cloudflare.height - cloudflare.compoundPaddingTop - cloudflare.compoundPaddingBottom)
            assertTrue(cloudflare.isChecked)
            layout(384)
            assertSame(chatgpt, choices.choiceView("chatgpt"))
            assertEquals(preferred, selector.width)
        } finally { selector.close() }
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
            chip = NativeChip(context, colors)
            chip.bind("Selected", true)
            assertEquals(-1, chip.currentTextColor)
            ripples = listOf(style.background(), chip.background as RippleDrawable)
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

    @Test fun retainedTextReplacementReflowsOwnedLineHeightAtUnchangedWidth() = main {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply { fontScale = 1f })
        val density = context.resources.displayMetrics.density
        val width = (240 * density).roundToInt()
        val expectedLineHeight = kotlin.math.ceil(80f * density).toInt()
        val style = NativeChoiceStyle(textStyle = NativeTextStyle(
            sizeSp = 14f, lineHeightSp = 80f, includeFontPadding = false,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)))
        fun chip() = NativeChip(context, palette, style).apply {
            layoutParams = FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            maxLines = Int.MAX_VALUE
            ellipsize = null
            bind("Choice", false)
        }
        fun measure(chip: NativeChip) {
            chip.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            chip.layout(0, 0, chip.measuredWidth, chip.measuredHeight)
        }
        fun diagnostic(chip: NativeChip): String {
            val buffer = chip.text as? Spanned
            val spans = buffer?.getSpans(0, buffer.length, LineHeightSpan::class.java)?.size ?: 0
            return "requested=${chip.isLayoutRequested} spans=$spans textHeight=${chip.layout?.height} viewHeight=${chip.height}"
        }
        var transition = "initial"
        fun assertLabel(chip: NativeChip) {
            assertEquals(width, chip.width)
            assertEquals("Choice", chip.text.toString())
            val text = checkNotNull(chip.layout)
            assertTrue("Exercise retained spannable layout", text is android.text.DynamicLayout)
            assertEquals(1, text.lineCount)
            assertEquals(chip.text.length, text.getLineEnd(0))
            assertEquals(0, text.getEllipsisCount(0))
            assertEquals("Requested leading must survive text replacement: $transition afterMeasure=${diagnostic(chip)}",
                expectedLineHeight, text.height)
            assertTrue(text.height > chip.paint.fontMetricsInt.descent - chip.paint.fontMetricsInt.ascent)
            assertTrue(text.height <= chip.height - chip.compoundPaddingTop - chip.compoundPaddingBottom)
            assertTrue(text.getLineMax(0) <= chip.width - chip.compoundPaddingLeft - chip.compoundPaddingRight + .5f)
            val buffer = chip.text as Spanned
            val spans = buffer.getSpans(0, buffer.length, LineHeightSpan::class.java)
            assertEquals("Exactly one owned line-height span", 1, spans.size)
            assertEquals(0, buffer.getSpanStart(spans.single()))
            assertEquals(buffer.length, buffer.getSpanEnd(spans.single()))
        }
        val retained = chip()
        val fresh = chip()
        try {
            measure(fresh); measure(retained)
            assertLabel(fresh); assertLabel(retained)
            repeat(2) {
                // Keep width and typography unchanged: setText may construct a layout
                // before measurement installs the successor buffer's owned span.
                retained.text = ""
                measure(retained)
                val before = diagnostic(retained)
                retained.text = "Choice"
                transition = "beforeAssign=[$before] afterAssign=[${diagnostic(retained)}]"
                measure(retained)
                assertLabel(retained)
                val actual = checkNotNull(retained.layout)
                val expected = checkNotNull(fresh.layout)
                assertEquals(expected.height, actual.height)
                assertEquals(expected.getLineAscent(0), actual.getLineAscent(0))
                assertEquals(expected.getLineDescent(0), actual.getLineDescent(0))
                assertEquals(fresh.baseline, retained.baseline)
                assertEquals(fresh.height, retained.height)
            }
        } finally { retained.close(); fresh.close() }
    }

    @Test fun nativeTextReplacementReflowsOwnedLineHeightAtUnchangedWidth() = main {
        assertRetainedOwnerLineHeight { context, style -> NativeText(context, palette, style) }
    }

    @Test fun nativeTextFieldReplacementReflowsOwnedLineHeightAtUnchangedWidth() = main {
        assertRetainedOwnerLineHeight { context, style ->
            NativeTextField(context, palette, NativeTextFieldStyle(textStyle = style)).apply { setSingleLine(false) }
        }
    }

    private fun assertRetainedOwnerLineHeight(factory: (android.content.Context, NativeTextStyle) -> TextView) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply { fontScale = 1f })
        val density = context.resources.displayMetrics.density
        val width = (240 * density).roundToInt()
        val expectedHeight = kotlin.math.ceil(80f * density).toInt()
        val style = NativeTextStyle(sizeSp = 14f, lineHeightSp = 80f, includeFontPadding = false,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None))
        fun create() = factory(context, style).apply {
            layoutParams = FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            maxLines = Int.MAX_VALUE
            ellipsize = null
            setText("Choice")
        }
        fun measure(view: TextView) {
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        }
        fun diagnostic(view: TextView): String {
            val buffer = view.text as? Spanned
            val spans = buffer?.getSpans(0, buffer.length, LineHeightSpan::class.java)?.size ?: 0
            return "requested=${view.isLayoutRequested} spans=$spans textHeight=${view.layout?.height} viewHeight=${view.height}"
        }
        fun assertLabel(view: TextView, detail: String) {
            assertEquals(width, view.width)
            assertEquals("Choice", view.text.toString())
            val layout = checkNotNull(view.layout)
            assertTrue(layout is android.text.DynamicLayout)
            assertEquals(1, layout.lineCount)
            assertEquals(view.text.length, layout.getLineEnd(0))
            assertEquals(0, layout.getEllipsisCount(0))
            assertEquals("${view.javaClass.simpleName}: $detail afterMeasure=[${diagnostic(view)}]", expectedHeight, layout.height)
            assertTrue(layout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
            assertTrue(layout.getLineMax(0) <= view.width - view.compoundPaddingLeft - view.compoundPaddingRight + .5f)
            val buffer = view.text as Spanned
            val span = buffer.getSpans(0, buffer.length, LineHeightSpan::class.java)
            assertEquals(1, span.size)
            assertEquals(0, buffer.getSpanStart(span.single()))
            assertEquals(buffer.length, buffer.getSpanEnd(span.single()))
        }
        val fresh = create()
        val retained = create()
        measure(fresh); measure(retained)
        assertLabel(fresh, "fresh"); assertLabel(retained, "initial")
        repeat(2) {
            retained.setText("")
            measure(retained)
            val before = diagnostic(retained)
            retained.setText("Choice")
            val detail = "beforeAssign=[$before] afterAssign=[${diagnostic(retained)}]"
            assertTrue("Styled text replacement must schedule measurement: $detail", retained.isLayoutRequested)
            val editable = (retained as? NativeTextField)?.text
            (retained as? NativeTextField)?.setSelection(2)
            measure(retained)
            assertLabel(retained, detail)
            val expected = checkNotNull(fresh.layout)
            val actual = checkNotNull(retained.layout)
            assertEquals(expected.getLineAscent(0), actual.getLineAscent(0))
            assertEquals(expected.getLineDescent(0), actual.getLineDescent(0))
            assertEquals(fresh.baseline, retained.baseline)
            assertEquals(fresh.height, retained.height)
            if (retained is NativeTextField) {
                assertSame("Measurement must preserve the current Editable", editable, retained.text)
                assertEquals(2, retained.selectionStart)
                assertEquals(2, retained.selectionEnd)
            }
        }
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
