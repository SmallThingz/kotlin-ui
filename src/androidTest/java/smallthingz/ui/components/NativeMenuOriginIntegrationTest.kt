package smallthingz.ui.components

import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeMenuOriginIntegrationTest {
    @Test fun localPointFollowsNestedTransformsAndScrollWithoutSharingScratchAcrossWindows() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val root = FrameLayout(context)
            val row = FrameLayout(context)
            val child = View(context)
            root.addView(row)
            row.addView(child)
            root.layout(0, 0, 800, 1000)
            row.layout(40, 70, 440, 270)
            child.layout(10, 20, 210, 120)
            root.scrollTo(3, 5)
            row.scrollTo(7, 11)
            child.pivotX = 0f; child.pivotY = 0f
            child.scaleX = 2f; child.scaleY = 3f
            child.translationX = 4f; child.translationY = 6f
            val origin = requireNotNull(NativeMenuOrigin.fromDescendant(row, child, 12f, 8f))
            assertEquals(NativeMenuOrigin(31f, 39f), origin)
            row.pivotX = 0f; row.pivotY = 0f
            row.scaleX = 2f; row.scaleY = 2f
            row.translationX = 8f; row.translationY = 9f
            val mapper = NativeMenuOriginMapper()
            val rootLocation = IntArray(2)
            root.getLocationInWindow(rootLocation)
            mapper.map(row, origin)
            assertEquals(rootLocation[0] + 107, mapper.x)
            assertEquals(rootLocation[1] + 152, mapper.y)
            root.scrollTo(13, 25)
            row.rotation = 90f
            mapper.map(row, origin)
            assertEquals(rootLocation[0] - 43, mapper.x)
            assertEquals(rootLocation[1] + 116, mapper.y)
            assertNull(NativeMenuOrigin.fromDescendant(View(context), child, 1f, 2f))
            assertNull(NativeMenuOrigin.fromDescendant(row, child, Float.NaN, 2f))
        }
    }
}
