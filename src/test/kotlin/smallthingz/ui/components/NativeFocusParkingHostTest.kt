package smallthingz.ui.components

import android.app.Activity
import android.app.Application
import android.view.ViewGroup
import android.widget.LinearLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NativeFocusParkingHostTest {
    @Test fun structuralParkingPreservesDescendantTraversalAndSliderHighlights() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        try {
            val activity = controller.get()
            val palette = NativeColorScheme.fallback(true).settingsControls
            val slider = NativeSlider(activity, palette)
            val range = NativeRangeSlider(activity, palette, 0, 100, 1, 20, 80, "Low", "High")
            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                addView(slider, LinearLayout.LayoutParams(320, 56))
                addView(range, LinearLayout.LayoutParams(320, 56))
            }
            val targets = listOf(slider) + (0 until range.childCount)
                .map(range::getChildAt).filter { it.isFocusable }
            assertEquals("Slider and both native range thumbs", 3, targets.size)
            targets.forEach { assertTrue(it.defaultFocusHighlightEnabled) }

            root.configureNativeFocusParking()
            assertTrue(root.isFocusable)
            assertTrue(root.isFocusableInTouchMode)
            assertFalse(root.defaultFocusHighlightEnabled)
            assertEquals(ViewGroup.FOCUS_AFTER_DESCENDANTS, root.descendantFocusability)
            targets.forEach { assertTrue(it.defaultFocusHighlightEnabled) }

            activity.setContentView(root)
            controller.start().resume().visible().windowFocusChanged(true)
            for (target in targets) {
                assertTrue("Native child focus remains available", target.requestFocusFromTouch())
                assertTrue(target.isFocused)
                assertTrue(target.defaultFocusHighlightEnabled)
            }
        } finally { controller.close() }
    }
}
