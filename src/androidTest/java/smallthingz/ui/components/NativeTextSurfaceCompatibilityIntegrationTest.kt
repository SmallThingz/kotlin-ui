package smallthingz.ui.components

import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.controls.textSurfaceRange
import smallthingz.ui.reverb.ReverbGlyphView
import smallthingz.ui.reverb.ReverbIcon
import smallthingz.ui.reverb.ReverbTextSurface
import smallthingz.ui.reverb.ReverbTextSurfaceScope
import smallthingz.ui.reverb.ReverbTextSurfaceTarget

/** Old names remain actual JVM types; new common code recognizes old compositions. */
@RunWith(AndroidJUnit4::class)
class NativeTextSurfaceCompatibilityIntegrationTest {
    @Test fun oldScopeAndTargetKeepTheirJvmContractsAndUseTheCommonSampler() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val scope = object : FrameLayout(context), ReverbTextSurfaceScope {}
            var calls = 0
            val target = object : View(context), ReverbTextSurfaceTarget {
                override fun invalidateTextSurfaceInk() { calls++ }
            }
            assertTrue(NativeTextSurfaceScope::class.java.isAssignableFrom(ReverbTextSurfaceScope::class.java))
            assertTrue(NativeTextSurfaceTarget::class.java.isAssignableFrom(ReverbTextSurfaceTarget::class.java))
            assertNotNull(ReverbTextSurfaceTarget::class.java.getDeclaredMethod("invalidateTextSurfaceInk"))
            val commonTarget: NativeTextSurfaceTarget = target
            commonTarget.invalidateTextSurfaceInk()
            assertEquals(1, calls)
            val group = NativeGroupLayout(context)
            assertSame(group, ReverbTextSurface.ownGroupInk(group))
            assertTrue(group.hasOwnTextSurfaceInkInvalidator)
            group.setPaletteImmediately(Color.WHITE, Color.TRANSPARENT)
            group.addRow(target, NativeGroupLayout.LayoutParams(100, 40))
            scope.addView(group)
            assertEquals(textSurfaceRange(Color.WHITE), NativeTextSurface.sample(target))
            assertEquals(textSurfaceRange(Color.WHITE), ReverbTextSurface.sample(target))
            ReverbTextSurface.invalidate(scope)
            assertEquals("Detached compatibility owner stays inactive", 1, calls)
        }
    }

    @Test fun reverbGlyphIsACommonTargetWithoutChangingItsContrastOptIn() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val glyph = ReverbGlyphView(instrumentation.targetContext, ReverbIcon.Save, Color.BLACK)
            assertFalse(glyph.useTextSurfaceContrast)
            val target: NativeTextSurfaceTarget = glyph
            target.invalidateTextSurfaceInk()
            assertFalse(glyph.useTextSurfaceContrast)
            glyph.useTextSurfaceContrast = true
            target.invalidateTextSurfaceInk()
            assertTrue(glyph.useTextSurfaceContrast)
        }
    }
}
