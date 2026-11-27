package smallthingz.ui.injector

import android.app.Application
import android.content.Context
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowViewGroup
import org.robolectric.util.ReflectionHelpers.ClassParameter
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.components.NativeMergedActionLayout

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], shadows = [InjectConfirmationFailureTest.JoinedPaletteShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class InjectConfirmationFailureTest {
    @Test fun joinedLayoutFailureClosesBothButtonsAndSuppressesCleanupFailuresInReverseOrder() {
        val context = RuntimeEnvironment.getApplication()
        val appearance = InjectAppearance(context, InjectDesign.theme(context, dark = false))
        val joinFailure = IllegalStateException("Could not finish the joined action layout")
        val cancelCloseFailure = IllegalStateException("Could not restore cancel content")
        val confirmCloseFailure = IllegalStateException("Could not restore confirm content")
        val buttons = mutableListOf<NativeMaterialButtonView>()
        val leadingViews = mutableListOf<FailingAccessibilityRestoreView>()
        var clicks = 0

        JoinedPaletteShadow.afterBind = { layout ->
            val content = layout.actionSurface.pointerContent
            for ((index, closeFailure) in listOf(cancelCloseFailure, confirmCloseFailure).withIndex()) {
                val button = content.getChildAt(index) as NativeMaterialButtonView
                buttons += button
                val leadingView = FailingAccessibilityRestoreView(context, closeFailure)
                leadingViews += leadingView
                button.setLeadingView(leadingView)
                leadingView.armed = true
            }
            throw joinFailure
        }
        try {
            val thrown = assertThrows(IllegalStateException::class.java) {
                appearance.confirmationActions("Cancel", "Continue", { clicks++ }) { clicks++ }
            }
            assertSame(joinFailure, thrown)
            assertEquals(listOf("Cancel", "Continue"), buttons.map { it.label.getText() })
            assertTrue(buttons.all { !it.isEnabled && !it.isFocusable })
            assertTrue(leadingViews.all { it.parent == null })
            assertEquals(listOf(confirmCloseFailure, cancelCloseFailure), thrown.suppressed.toList())
            assertEquals(0, clicks)
        } finally {
            JoinedPaletteShadow.afterBind = null
            leadingViews.forEach { it.armed = false }
            buttons.asReversed().forEach { it.close() }
        }
    }

    private class FailingAccessibilityRestoreView(context: Context, private val failure: Throwable) : View(context) {
        private val originalImportance = importantForAccessibility
        var armed = false

        override fun setImportantForAccessibility(mode: Int) {
            super.setImportantForAccessibility(mode)
            if (armed && mode == originalImportance) throw failure
        }
    }

    /** Fail during the existing palette bind, after real construction has installed both buttons. */
    @Implements(value = NativeMergedActionLayout::class, isInAndroidSdk = false)
    class JoinedPaletteShadow : ShadowViewGroup() {
        @RealObject
        private lateinit var layout: NativeMergedActionLayout

        @Implementation
        protected fun bindPalette(palette: NativeColorScheme) {
            Shadow.directlyOn<Any?, NativeMergedActionLayout>(
                layout, NativeMergedActionLayout::class.java, "bindPalette",
                ClassParameter.from(NativeColorScheme::class.java, palette),
            )
            afterBind?.invoke(layout)
        }

        companion object {
            var afterBind: ((NativeMergedActionLayout) -> Unit)? = null
        }
    }
}
