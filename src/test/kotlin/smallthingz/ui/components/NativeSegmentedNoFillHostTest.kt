package smallthingz.ui.components

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.graphics.ColorUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeSegmentedNoFillHostTest {
    @Test fun noFillCapsuleKeepsChoiceInkAndAcceptsControlledSelection() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val root = FrameLayout(activity)
            activity.setContentView(root)
            val palette = NativeColorScheme.fallback(false).settingsControls.copy(
                surface = Color.WHITE,
                accent = 0xff005caa.toInt(),
                muted = Color.DKGRAY,
            )
            val options = listOf(NativeChoice("first", "First"), NativeChoice("second", "Second"))
            val defaults = NativeSegmentedStyle.Default
            val outlineOnly = defaults.copy(choiceStyle = defaults.choiceStyle.copy(
                selectedFill = false,
                selectedOutlined = true,
                normalTextRole = NativeTextRole.MUTED,
                selectedTextRole = NativeTextRole.ACCENT,
                animateSelection = false,
            ))

            NativeSegmentedControl(activity, palette, outlineOnly).use { capsule ->
                val requests = mutableListOf<String>()
                capsule.onSelectionRequest = { requests.add(it) }
                capsule.bind(options, "first", palette)
                root.addView(capsule)
                try {
                    controller.windowFocusChanged(true)
                    assertTrue("The attached capsule must admit the click", capsule.acceptsNativeInput)
                    drawCapsule(capsule)
                    val choices = descendants(capsule).filterIsInstance<NativeSegmentedChoice>().single()
                    val first = requireNotNull(choices.choiceView("first"))
                    val second = requireNotNull(choices.choiceView("second"))
                    assertTrue(first.isChecked)
                    assertFalse(second.isChecked)
                    assertFalse(first.parentDrawsSelectedFill)
                    assertFalse(second.parentDrawsSelectedFill)
                    assertNull(first.uniformSelectionForeground)
                    assertNull(second.uniformSelectionForeground)
                    assertEquals(palette.accent, first.currentTextColor)
                    assertEquals(palette.muted, second.currentTextColor)
                    assertTrue(requests.isEmpty())

                    assertTrue(second.performClick())
                    assertEquals(listOf("second"), requests)
                    assertEquals("first", choices.selectedId)

                    capsule.bind(options, "second", palette)
                    assertEquals("second", choices.selectedId)
                    assertFalse(first.isChecked)
                    assertTrue(second.isChecked)
                    assertEquals(palette.muted, first.currentTextColor)
                    assertEquals(palette.accent, second.currentTextColor)
                    assertEquals(listOf("second"), requests)
                } finally {
                    root.removeView(capsule)
                }
            }

            NativeSegmentedControl(activity, palette).use { capsule ->
                capsule.bind(options, "first", palette)
                drawCapsule(capsule)
                val choices = descendants(capsule).filterIsInstance<NativeSegmentedChoice>().single()
                val selected = requireNotNull(choices.choiceView("first"))
                assertTrue(selected.parentDrawsSelectedFill)
                assertNotNull("Default capsules still prepare uniform ink over the moving fill",
                    selected.uniformSelectionForeground)
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun noFillDefaultUsesReadableTextAndTintedIconInLightAndDark() {
        val context = RuntimeEnvironment.getApplication()
        val defaults = NativeSegmentedStyle.Default
        val outlineOnly = defaults.copy(choiceStyle = defaults.choiceStyle.copy(
            selectedFill = false,
            selectedOutlined = true,
        ))
        val explicitRoles = outlineOnly.copy(choiceStyle = outlineOnly.choiceStyle.copy(
            selectedTextRole = NativeTextRole.MUTED,
            selectedIconRole = NativeTextRole.CONTENT,
        ))

        for (dark in listOf(false, true)) {
            val palette = NativeColorScheme.fallback(dark).settingsControls
            val mode = if (dark) "Dark" else "Light"
            fun verifyInk(style: NativeSegmentedStyle, textRoleColor: Int, iconRoleColor: Int) {
                NativeSegmentedControl(context, palette, style).use { capsule ->
                    val options = listOf(
                        NativeChoice("first", "First", ColorDrawable(Color.WHITE)),
                        NativeChoice("second", "Second"),
                    )
                    // Bind while detached so the unchanged default selection motion settles.
                    capsule.bind(options, "first", palette)
                    drawCapsule(capsule)
                    val choices = descendants(capsule).filterIsInstance<NativeSegmentedChoice>().single()
                    val selected = requireNotNull(choices.choiceView("first"))
                    assertTrue(selected.isChecked)
                    assertFalse(selected.parentDrawsSelectedFill)
                    val fill = (capsule.getChildAt(0) as NativeSurface).paintedFillColor
                    assertEquals(palette.surface, fill)
                    val text = selected.currentTextColor
                    val icon = paintedLeadingIconColor(selected)
                    assertEquals("$mode selected text", textRoleColor, text)
                    assertEquals("$mode selected icon", iconRoleColor, icon)
                    if (style === outlineOnly) {
                        assertTrue("$mode text contrast", ColorUtils.calculateContrast(text, fill) >= 4.5)
                        assertTrue("$mode icon contrast", ColorUtils.calculateContrast(icon, fill) >= 3.0)
                    }
                }
            }

            verifyInk(outlineOnly, palette.accent, palette.accent)
            verifyInk(explicitRoles, palette.muted, palette.content)
        }
    }

    private fun paintedLeadingIconColor(chip: NativeChip): Int {
        val icon = requireNotNull(chip.compoundDrawablesRelative[0])
        val bounds = icon.bounds
        val bitmap = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(bitmap)
            canvas.translate(-bounds.left.toFloat(), -bounds.top.toFloat())
            icon.draw(canvas)
            bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        } finally {
            bitmap.recycle()
        }
    }

    private fun drawCapsule(capsule: NativeSegmentedControl) {
        capsule.measure(View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        capsule.layout(0, 0, capsule.measuredWidth, capsule.measuredHeight)
        val bitmap = Bitmap.createBitmap(capsule.width, capsule.height, Bitmap.Config.ARGB_8888)
        try {
            capsule.draw(Canvas(bitmap))
        } finally {
            bitmap.recycle()
        }
    }

    private fun descendants(parent: ViewGroup): Sequence<View> = sequence {
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            yield(child)
            if (child is ViewGroup) yieldAll(descendants(child))
        }
    }
}
