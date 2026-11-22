package smallthingz.ui.components

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.interaction.view.readCachedAnimatorDurationScale
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class NativeStandaloneSharedControlsIntegrationTest {
    @Test fun parentDispatchesFingerInEmptyButtonMarginWithoutStealingAdjacentAction() = withWindow { activity ->
        val appearance = NativeUiAppearance.Default
        val colors = NativeColorScheme.fallback(darkTheme = false)
        val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
            fontScale = 1f
            densityDpi = 500
        })
        val density = Density(context)
        val resolver = createFontFamilyResolver(context)
        var firstClicks = 0
        var adjacentClicks = 0
        val first = NativeMaterialButtonView(context, appearance.buttons.filled, density, resolver) { firstClicks++ }
        val adjacent = NativeMaterialButtonView(context, appearance.buttons.filled, density, resolver) { adjacentClicks++ }
        first.bind("New tab", true, colors.buttonPalette)
        adjacent.bind("Open", true, colors.buttonPalette)
        val target = NativeButtonTouchTarget(first)
        val adjacentTarget = NativeButtonTouchTarget(adjacent)
        fun dp(value: Int) = (value * density.density).roundToInt()
        val parent = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(target, LinearLayout.LayoutParams(-1, dp(48)))
            addView(adjacentTarget, LinearLayout.LayoutParams(-1, -2))
        }
        activity.setContentView(parent)
        return@withWindow {
            assertTrue(first.isAttachedToWindow && adjacent.isAttachedToWindow)
            assertEquals(150, target.height)
            assertEquals(125, first.height)
            assertEquals(13, first.top)
            val x = target.left + first.left + first.width / 2f
            val y = target.top + .25f
            val nextY = target.bottom + .25f
            assertTrue(gesture(parent, MotionEvent.TOOL_TYPE_FINGER,
                TouchStep(MotionEvent.ACTION_DOWN, x, y),
                TouchStep(MotionEvent.ACTION_MOVE, x, y),
                TouchStep(MotionEvent.ACTION_UP, x, y)))
            assertEquals(1, firstClicks)
            assertEquals(0, adjacentClicks)
            assertFalse(tap(parent, x, target.top - .25f, MotionEvent.TOOL_TYPE_FINGER))
            assertFalse(tap(parent, x, y, MotionEvent.TOOL_TYPE_STYLUS))
            assertEquals(1, firstClicks)
            assertTrue(tap(parent, adjacentTarget.left + adjacent.left + adjacent.width / 2f,
                nextY, MotionEvent.TOOL_TYPE_FINGER))
            assertEquals(1, firstClicks)
            assertEquals(1, adjacentClicks)

            gesture(parent, MotionEvent.TOOL_TYPE_FINGER,
                TouchStep(MotionEvent.ACTION_DOWN, x, y),
                TouchStep(MotionEvent.ACTION_MOVE, x, nextY),
                TouchStep(MotionEvent.ACTION_MOVE, x, y),
                TouchStep(MotionEvent.ACTION_UP, x, y))
            gesture(parent, MotionEvent.TOOL_TYPE_FINGER,
                TouchStep(MotionEvent.ACTION_DOWN, x, y),
                TouchStep(MotionEvent.ACTION_UP, x, nextY))
            gesture(parent, MotionEvent.TOOL_TYPE_FINGER,
                TouchStep(MotionEvent.ACTION_DOWN, x, y),
                TouchStep(MotionEvent.ACTION_CANCEL, x, y),
                TouchStep(MotionEvent.ACTION_UP, x, y))
            assertEquals(1, firstClicks)
            assertEquals(1, adjacentClicks)

            first.bind("New tab", false, colors.buttonPalette)
            tap(parent, x, y, MotionEvent.TOOL_TYPE_FINGER)
            assertEquals(1, firstClicks)
            assertEquals(1, adjacentClicks)
            first.close()
            adjacent.close()
        }
    }

    @Test fun edgeAlignedSurfacesKeepSeparateFingerTargetsInBothLayoutDirections() {
        for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
            withWindow { activity ->
                val density = Density(activity)
                val resolver = createFontFamilyResolver(activity)
                val colors = NativeColorScheme.fallback(darkTheme = false)
                val raised = NativeUiAppearance.Default.buttons.raisedIcon
                val recipe = raised.copy(surface = raised.surface.copy(minimumWidthDp = 40, minimumHeightDp = 40))
                var firstClicks = 0
                var secondClicks = 0
                val first = NativeMaterialButtonView(activity, recipe, density, resolver) { firstClicks++ }
                val second = NativeMaterialButtonView(activity, recipe, density, resolver) { secondClicks++ }
                first.bind("", true, colors.buttonPalette)
                second.bind("", true, colors.buttonPalette)
                val firstTarget = NativeButtonTouchTarget(first, NativeButtonTouchTarget.HorizontalAlignment.END)
                val secondTarget = NativeButtonTouchTarget(second, NativeButtonTouchTarget.HorizontalAlignment.START)
                fun dp(value: Int) = (value * density.density).roundToInt()
                val parent = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutDirection = direction
                    addView(firstTarget, LinearLayout.LayoutParams(-2, -2))
                    addView(secondTarget, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(2) })
                }
                activity.setContentView(parent)
                return@withWindow {
                    val rtl = direction == View.LAYOUT_DIRECTION_RTL
                    assertEquals(dp(48), firstTarget.width)
                    assertEquals(dp(48), secondTarget.width)
                    assertEquals(dp(48), firstTarget.height)
                    assertEquals(dp(48), secondTarget.height)
                    assertEquals(dp(40), first.width)
                    assertEquals(dp(40), second.width)
                    assertEquals(if (rtl) 0 else firstTarget.width - first.width, first.left)
                    assertEquals(if (rtl) secondTarget.width - second.width else 0, second.left)
                    val leftTarget = if (rtl) secondTarget else firstTarget
                    val rightTarget = if (rtl) firstTarget else secondTarget
                    assertEquals(dp(2), rightTarget.left - leftTarget.right)
                    val y = firstTarget.top + firstTarget.height / 2f
                    val firstOuterEdge = if (rtl) firstTarget.right - .25f else firstTarget.left + .25f
                    val secondOuterEdge = if (rtl) secondTarget.left + .25f else secondTarget.right - .25f
                    assertTrue(tap(parent, firstOuterEdge, y, MotionEvent.TOOL_TYPE_FINGER))
                    assertEquals(1, firstClicks)
                    assertEquals(0, secondClicks)
                    assertFalse(tap(parent, (leftTarget.right + rightTarget.left) / 2f, y, MotionEvent.TOOL_TYPE_FINGER))
                    assertTrue(tap(parent, secondOuterEdge, y, MotionEvent.TOOL_TYPE_FINGER))
                    assertEquals(1, firstClicks)
                    assertEquals(1, secondClicks)
                    first.close()
                    second.close()
                }
            }
        }
    }

    @Test fun joinedFieldPaletteAndValidationRetainFocusedPasswordEditorAndComposition() = withWindow { activity ->
        val appearance = NativeUiAppearance.Default
        val light = NativeColorScheme.fallback(darkTheme = false)
        val dark = NativeColorScheme.fallback(darkTheme = true)
        val type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val editor = NativeTextField(activity, light.settingsControls).apply {
            inputType = type
            imeOptions = EditorInfo.IME_ACTION_DONE
            setText("example-key")
            setSelection(4)
        }
        val tunnel = appearance.joinedFields.createField(activity, light, "Tunnel ID")
        val key = appearance.joinedFields.createField(activity, light, "Runtime key", editor)
        val group = appearance.joinedFields.createGroup(activity, light, tunnel, key)
        activity.setContentView(group.view)
        return@withWindow {
            assertTrue(editor.requestFocus())
            assertTrue(editor.isAttachedToWindow && editor.hasFocus())
            val text = editor.text
            val connection = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
            try {
                assertTrue(connection.setSelection(4, 4))
                assertTrue(connection.setComposingRegion(1, 4))
                assertEquals(1, BaseInputConnection.getComposingSpanStart(text))
                assertEquals(4, BaseInputConnection.getComposingSpanEnd(text))
                key.setSupportingText("Stored locally")
                key.setError("Invalid key")
                group.bindPalette(dark)
                assertSame(tunnel, group.view.pointerContent.getChildAt(0))
                assertSame(key, group.view.pointerContent.getChildAt(1))
                assertFalse((tunnel.layoutParams as NativeGroupLayout.LayoutParams).dividerTop)
                assertTrue((key.layoutParams as NativeGroupLayout.LayoutParams).dividerTop)
                assertSame(editor, key.editor)
                assertSame(text, editor.text)
                assertTrue(editor.hasFocus())
                assertEquals(type, editor.inputType)
                assertEquals(EditorInfo.IME_ACTION_DONE, editor.imeOptions)
                assertEquals(4, editor.selectionStart)
                assertEquals(1, BaseInputConnection.getComposingSpanStart(text))
                assertEquals(4, BaseInputConnection.getComposingSpanEnd(text))
                assertEquals("Invalid key", key.supportingView.text.toString())
                val node = checkNotNull(editor.createAccessibilityNodeInfo())
                try {
                    assertTrue(node.isContentInvalid)
                    assertEquals("Invalid key", node.error.toString())
                } finally {
                    node.recycle()
                }
                key.setError(null)
                assertEquals("Stored locally", key.supportingView.text.toString())
                assertTrue(connection.finishComposingText())
                assertEquals(-1, BaseInputConnection.getComposingSpanStart(text))
                assertTrue(editor.hasFocus())
            } finally {
                connection.closeConnection()
            }
        }
    }

    @Test fun joinedPaletteUpdatesOuterFillAndErrorForegroundOnTheSameViews() =
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val light = NativeColorScheme.fallback(darkTheme = false)
            val dark = NativeColorScheme.fallback(darkTheme = true)
            val recipe = NativeUiAppearance.Default.joinedFields
            val field = recipe.createField(context, light, "Tunnel ID")
            val group = recipe.createGroup(context, light, field)
            field.setError("Invalid ID")
            assertEquals(light.field, group.view.paintedFillColor)
            assertEquals(light.error, field.supportingView.currentTextColor)
            group.bindPalette(dark)
            assertSame(field, group.view.pointerContent.getChildAt(0))
            assertEquals(dark.field, group.view.paintedFillColor)
            assertEquals(dark.error, field.supportingView.currentTextColor)
        }

    @Test fun initiallyBlackGroupSeedsInkWithoutANewFillAndKeepsDisabledOpacity() =
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val palette = NativeColorScheme.fallback(darkTheme = true).copy(surfaceContainerLow = Color.BLACK,
                onSurface = Color.BLACK, error = Color.BLACK)
            val recipe = NativeUiAppearance.Default.joinedFields
            val field = recipe.createField(context, palette, "Key")
            field.editor.setText("Example key")
            field.setError("Invalid key")
            var fillChanges = 0
            val group = recipe.createGroup(context, palette, field, onPaintedSurfaceChanged = { fillChanges++ })
            assertEquals(Color.BLACK, group.view.paintedFillColor)
            assertEquals(0, fillChanges)
            assertEquals(Color.WHITE, field.editor.currentTextColor)
            assertEquals(Color.WHITE, field.supportingView.currentTextColor)
            group.bindPalette(palette)
            assertEquals(0, fillChanges)
            assertEquals(Color.WHITE, field.editor.currentTextColor)

            field.setEditorEnabled(false)
            assertEquals(0x61000000, field.editor.currentTextColor)
            assertEquals(0x61000000, field.supportingView.currentTextColor)
            assertSame(field, group.view.pointerContent.getChildAt(0))
        }

    @Test fun joinedInkRemainsReadableInActualIntermediateDraw() {
        assumeTrue("Requires Android animations enabled", ValueAnimator.areAnimatorsEnabled())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Keep every interior fill in the contrast-sensitive range. A fixed white-to-black
        // grayscale band can fit entirely between two frames at the device's motion scale.
        val rawInk = Color.rgb(128, 128, 128)
        val light = NativeColorScheme.fallback(darkTheme = false).copy(surfaceContainerLow = Color.rgb(160, 160, 160),
            onSurface = rawInk, onSurfaceVariant = rawInk, error = rawInk)
        val dark = NativeColorScheme.fallback(darkTheme = true).copy(surfaceContainerLow = Color.rgb(80, 80, 80),
            onSurface = rawInk, onSurfaceVariant = rawInk, error = rawInk)
        val finished = CountDownLatch(1)
        var bitmap: Bitmap? = null
        var canvas: Canvas? = null
        var fillSampleY = 0
        var durationScale = Float.NaN
        var intermediateFrames = 0
        var firstPaintMismatch: Pair<Int, Int>? = null
        var maximumRawContrast = 0.0
        var minimumContrast = Double.POSITIVE_INFINITY
        try {
            withWindow(observe = {
                assertTrue("Expected the attached group to finish its painted theme transition", finished.await(5, TimeUnit.SECONDS))
            }) { activity ->
                activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
                val recipe = NativeUiAppearance.Default.joinedFields
                val label = recipe.createField(activity, light, "\u2588\u2588\u2588\u2588\u2588\u2588")
                val body = recipe.createField(activity, light, "Key").apply {
                    editor.setText("Saved key")
                    setError("Invalid key")
                }
                val hint = recipe.createField(activity, light, "").apply { setPlaceholder("Enter value") }
                val group = recipe.createGroup(activity, light, label, body, hint, onPaintedSurfaceChanged = { view ->
                    val frame = bitmap ?: return@createGroup
                    val surface = view as NativeGroupLayout
                    val sampledFill = surface.paintedFillColor
                    if (sampledFill == dark.field) {
                        finished.countDown()
                        return@createGroup
                    }
                    if (sampledFill == light.field) return@createGroup
                    intermediateFrames++
                    frame.eraseColor(Color.TRANSPARENT)
                    surface.draw(checkNotNull(canvas))
                    val fill = frame.getPixel(frame.width / 2, fillSampleY)
                    if (fill != sampledFill) {
                        if (firstPaintMismatch == null) firstPaintMismatch = sampledFill to fill
                        return@createGroup
                    }
                    maximumRawContrast = maxOf(maximumRawContrast, ColorUtils.calculateContrast(rawInk, fill))
                    val placeholder = (0 until hint.childCount).asSequence().map(hint::getChildAt)
                        .filterIsInstance<NativeText>().single { it.text?.toString() == "Enter value" }
                    val inset = (28f * surface.resources.displayMetrics.density).roundToInt()
                    var labelContrast = 0.0
                    findLabel@ for (y in label.top + 3 until minOf(label.bottom - 3, frame.height) step 2) {
                        for (x in inset until minOf(surface.width, frame.width) - inset step 2) {
                            val ink = frame.getPixel(x, y)
                            if (Color.alpha(ink) == 255 && ink != fill) {
                                labelContrast = maxOf(labelContrast, ColorUtils.calculateContrast(ink, fill))
                                if (labelContrast >= 4.5) break@findLabel
                            }
                        }
                    }
                    minimumContrast = minOf(minimumContrast, labelContrast,
                        ColorUtils.calculateContrast(body.editor.currentTextColor, fill),
                        ColorUtils.calculateContrast(body.supportingView.currentTextColor, fill),
                        ColorUtils.calculateContrast(placeholder.currentTextColor, fill))
                })
                activity.setContentView(group.view)
                return@withWindow {
                    assertTrue(body.editor.requestFocus())
                    assertFalse(label.editor.hasFocus())
                    assertTrue(group.view.isAttachedToWindow && group.view.isShown)
                    val bottomInset = (4f * group.view.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
                    assertTrue("Expected a blank fill band below the last field",
                        hint.bottom < group.view.height - bottomInset)
                    fillSampleY = (hint.bottom + group.view.height - bottomInset) / 2
                    val frame = Bitmap.createBitmap(group.view.width, group.view.height, Bitmap.Config.ARGB_8888)
                    bitmap = frame
                    canvas = Canvas(frame)
                    durationScale = readCachedAnimatorDurationScale(activity)
                    assertEquals(light.field, group.view.paintedFillColor)
                    group.bindPalette(dark)
                    assertEquals("An attached shown group must start from its current paint at scale $durationScale",
                        light.field, group.view.paintedFillColor)
                }
            }
            assertTrue("Expected a nonendpoint paint sample at animator scale $durationScale", intermediateFrames > 0)
            assertEquals("Sampled fill and actual bitmap pixel must match", null, firstPaintMismatch)
            assertTrue("Every sampled frame must challenge raw ink ($maximumRawContrast)", maximumRawContrast in 1.0..<4.5)
            assertTrue("Label, body, placeholder, and error ink must reach 4.5:1 contrast ($minimumContrast)", minimumContrast >= 4.5)
        } finally {
            instrumentation.runOnMainSync {
                bitmap?.recycle()
                bitmap = null
                canvas = null
            }
        }
    }

    private data class TouchStep(val action: Int, val x: Float, val y: Float)

    private fun tap(parent: View, x: Float, y: Float, kind: Int): Boolean = gesture(parent, kind,
        TouchStep(MotionEvent.ACTION_DOWN, x, y), TouchStep(MotionEvent.ACTION_UP, x, y))

    private fun gesture(parent: View, kind: Int, vararg steps: TouchStep): Boolean {
        val start = SystemClock.uptimeMillis()
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = kind })
        val point = MotionEvent.PointerCoords().apply { pressure = 1f; size = 1f }
        val coordinates = arrayOf(point)
        val source = if (kind == MotionEvent.TOOL_TYPE_FINGER) InputDevice.SOURCE_TOUCHSCREEN else InputDevice.SOURCE_STYLUS
        var handled = true
        for ((index, step) in steps.withIndex()) {
            point.x = step.x
            point.y = step.y
            val event = MotionEvent.obtain(start, start + index, step.action, 1, properties, coordinates, 0, 0,
                1f, 1f, 0, 0, source, 0)
            try {
                if (!parent.dispatchTouchEvent(event)) handled = false
            } finally {
                event.recycle()
            }
        }
        return handled
    }

    private fun withWindow(observe: (() -> Unit)? = null, mount: (Activity) -> () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            lateinit var assertions: () -> Unit
            instrumentation.runOnMainSync { assertions = mount(activity) }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync(assertions)
            observe?.invoke()
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
