package smallthingz.ui.zfm

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeChip
import smallthingz.ui.components.NativeInputGate

/** Detached native measurement and direct accessibility nodes; no window/focus claim. */
@RunWith(AndroidJUnit4::class)
class ZfmBrowserControlsIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun context(fontScale: Float): Context {
        val base = instrumentation.targetContext
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            densityDpi = 160
            this.fontScale = fontScale
        })
    }

    private class InputOwner(context: Context) : FrameLayout(context), NativeInputGate {
        var active = true
        override val acceptsNativeInput get() = active
    }

    @Test fun iconOnlyKeepsNamedButtonAndCompactGeometryAtLargeFontScale() = main {
        for (font in listOf(1f, 2f)) for (selected in listOf(false, true)) {
            val context = context(font)
            val theme = ZfmTheme.resolve(context, dark = false)
            val icon = ColorDrawable(Color.BLACK)
            val owner = InputOwner(context)
            var clicks = 0
            val chip = theme.navigationChip(context, "Search files", icon,
                selected = selected, iconOnly = true) { clicks++ }
            owner.addView(chip)
            try {
                chip.measure(View.MeasureSpec.makeMeasureSpec(4096, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                chip.layout(0, 0, chip.measuredWidth, chip.measuredHeight)
                assertTrue(chip.text.isNullOrEmpty())
                assertEquals("Search files", chip.contentDescription.toString())
                assertEquals(48, chip.width)
                assertEquals(48, chip.height)
                assertSame(icon, chip.compoundDrawablesRelative[1])
                assertButtonNode(chip, selected, iconOnly = true)
                assertInputBoundary(chip, owner) { clicks }
                assertButtonNode(chip, selected, iconOnly = true)
            } finally {
                chip.close()
                owner.removeView(chip)
            }
        }
    }

    @Test fun ordinaryLabelsKeepVisibleTextAndNativeButtonSemantics() = main {
        val context = context(2f)
        val theme = ZfmTheme.resolve(context, dark = false)
        for (withIcon in listOf(false, true)) for (selected in listOf(false, true)) {
            val icon = if (withIcon) ColorDrawable(Color.BLACK) else null
            val owner = InputOwner(context)
            var clicks = 0
            // Omit iconOnly to exercise the ordinary factory default.
            val chip = theme.navigationChip(context, "Search files", icon, selected) { clicks++ }
            owner.addView(chip)
            try {
                assertEquals("Search files", chip.text.toString())
                assertNull(chip.contentDescription)
                assertSame(icon, chip.compoundDrawablesRelative[0])
                assertNull(chip.compoundDrawablesRelative[1])
                assertButtonNode(chip, selected, iconOnly = false)
                assertInputBoundary(chip, owner) { clicks }
                assertButtonNode(chip, selected, iconOnly = false)
            } finally {
                chip.close()
                owner.removeView(chip)
            }
        }
    }

    private fun assertButtonNode(chip: NativeChip, selected: Boolean, iconOnly: Boolean) {
        val node = checkNotNull(chip.createAccessibilityNodeInfo())
        try {
            assertEquals("android.widget.Button", node.className.toString())
            assertFalse(node.isCheckable)
            assertFalse(node.isChecked)
            assertEquals(selected, node.isSelected)
            assertTrue(node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
            if (iconOnly) {
                assertTrue(node.text.isNullOrEmpty())
                assertEquals("Search files", node.contentDescription.toString())
            } else {
                assertEquals("Search files", node.text.toString())
                assertNull(node.contentDescription)
            }
        } finally { node.recycle() }
    }

    private fun assertInputBoundary(chip: NativeChip, owner: InputOwner, clicks: () -> Int) {
        // NativeChip admits direct input through enabled state and ancestor NativeInputGate.
        // It does not require an attached/focused window, unlike some composite controls.
        assertTrue(chip.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals(1, clicks())
        owner.active = false
        assertFalse(chip.performClick())
        assertFalse(chip.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        owner.active = true
        chip.isEnabled = false
        assertFalse(chip.performClick())
        assertFalse(chip.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals(1, clicks())
        chip.isEnabled = true
        assertTrue(chip.performClick())
        assertEquals(2, clicks())
    }
}
