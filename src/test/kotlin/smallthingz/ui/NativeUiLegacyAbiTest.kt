package smallthingz.ui

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import smallthingz.ui.components.NativeBottomActionDock
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.reverb.ReverbTransferProgress
import smallthingz.ui.reverb.ReverbTransferSettings
import smallthingz.ui.unit.Density
import java.lang.reflect.Modifier

class NativeUiLegacyAbiTest {
    @Test fun transferBinderRetainsLegacyAndTypedJvmDescriptors() {
        val settings = ReverbTransferSettings::class.java
        val values = List::class.java
        val progress = ReverbTransferProgress::class.java
        assertEquals(Void.TYPE, settings.getMethod("bind", values, String::class.java).returnType)
        assertEquals(Void.TYPE, settings.getMethod("bind", values, progress).returnType)
        val bridge = settings.getMethod(
            "bind\$default", settings, values, progress, Int::class.javaPrimitiveType!!, Any::class.java,
        )
        assertEquals(Void.TYPE, bridge.returnType)
        assertTrue(bridge.isSynthetic)
        assertTrue(Modifier.isStatic(bridge.modifiers))
    }

    @Test fun dockRetainsJavaOverloadsAndBothKotlinDefaultBridges() {
        val dock = NativeBottomActionDock::class.java
        val prefix = arrayOf(Context::class.java, Density::class.java, NativeMaterialButtonView::class.java)
        val int = Int::class.javaPrimitiveType!!
        val marker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
        assertFalse(dock.getConstructor(*prefix).isSynthetic)
        assertFalse(dock.getConstructor(*prefix, int).isSynthetic)
        assertFalse(dock.getConstructor(*prefix, int, int).isSynthetic)
        assertTrue(dock.getConstructor(*prefix, int, int, marker).isSynthetic)
        assertTrue(dock.getConstructor(*prefix, int, int, int, marker).isSynthetic)
    }
}
