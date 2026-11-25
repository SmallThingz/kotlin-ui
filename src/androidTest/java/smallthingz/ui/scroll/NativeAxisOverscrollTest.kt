package smallthingz.ui.scroll

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RenderNode
import android.os.Build
import android.view.MotionEvent
import android.widget.EdgeEffect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.unit.Density

/** Contract probes use recording platform edges, not a duplicate overscroll implementation. */
@RunWith(AndroidJUnit4::class)
class NativeAxisOverscrollTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private class Edge(context: Context) : EdgeEffect(context) {
        val pulls = mutableListOf<Pair<Float, Float>>()
        val absorbs = mutableListOf<Int>()
        val drawnOrigins = mutableListOf<List<Float>>()
        var stretch = 0f
        var finishes = 0
        var releases = 0
        var dimensions = 0 to 0
        var colorWrites = 0
        override fun setColor(color: Int) { colorWrites++; super.setColor(color) }
        override fun getDistance() = stretch
        override fun isFinished() = stretch == 0f && pulls.isEmpty()
        override fun setSize(width: Int, height: Int) { dimensions = width to height }
        override fun onPull(deltaDistance: Float, displacement: Float) {
            pulls += deltaDistance to displacement
        }
        override fun onPullDistance(deltaDistance: Float, displacement: Float): Float {
            pulls += deltaDistance to displacement
            if (deltaDistance < 0f) stretch = 0f
            return deltaDistance / 4f
        }
        override fun onAbsorb(velocity: Int) { absorbs += velocity }
        override fun onRelease() { releases++ }
        override fun finish() { finishes++; stretch = 0f }
        @Suppress("DEPRECATION")
        override fun draw(canvas: Canvas): Boolean {
            val points = floatArrayOf(0f, 0f, 1f, 0f)
            val matrix = Matrix()
            canvas.getMatrix(matrix)
            matrix.mapPoints(points)
            drawnOrigins += points.toList()
            return false
        }
    }

    private fun effect(axis: NativeOverscrollAxis) =
        NativeAxisOverscroll(context, Density(1f), 0xff666666.toInt(), axis) {}

    private fun install(owner: NativeAxisOverscroll, name: String, edge: Edge) {
        NativeAxisOverscroll::class.java.getDeclaredField(name).apply { isAccessible = true }.set(owner, edge)
    }

    private fun installedEdge(owner: NativeAxisOverscroll, name: String): EdgeEffect? =
        NativeAxisOverscroll::class.java.getDeclaredField(name).apply { isAccessible = true }.get(owner) as EdgeEffect?

    @Test fun recolorRetainsEdgesAndPhysicsAndDuplicateColorIsNoOp() = main {
        val initialColor = 0xff666666.toInt()
        for (axis in NativeOverscrollAxis.entries) {
            var invalidations = 0
            val owner = NativeAxisOverscroll(context, Density(1f), initialColor, axis) { invalidations++ }
            val edges = listOf("near", "far", "nearNegation", "farNegation").associateWith {
                Edge(context).apply { color = initialColor; stretch = .125f }
            }
            try {
                edges.forEach { (name, edge) -> install(owner, name, edge) }
                owner.measured(200, 100)
                owner.scroll(0f, NativeOverscrollSource.SIDE_EFFECT) { 0f }
                edges.values.forEach { it.onAbsorb(73) }
                fun physics(edge: Edge) = listOf(edge.pulls.toList(), edge.absorbs.toList(),
                    edge.finishes, edge.releases, edge.stretch, edge.dimensions, edge.drawnOrigins.toList())
                val snapshots = edges.mapValues { physics(it.value) }
                val writes = edges.mapValues { it.value.colorWrites }
                val inProgress = owner.isInProgress
                val invalidationsBefore = invalidations
                owner.setGlowColor(initialColor)
                assertEquals(invalidationsBefore, invalidations)
                edges.forEach { (name, edge) -> assertEquals(writes.getValue(name), edge.colorWrites) }
                for ((index, color) in listOf(0xff49454f.toInt(), 0xffcac4d0.toInt(), 0xff49454f.toInt()).withIndex()) {
                    owner.setGlowColor(color)
                    assertEquals(invalidationsBefore + index + 1, invalidations)
                    edges.forEach { (name, edge) ->
                        assertSame(edge, installedEdge(owner, name))
                        assertEquals(color, edge.color)
                        assertEquals(writes.getValue(name) + index + 1, edge.colorWrites)
                        assertEquals(snapshots.getValue(name), physics(edge))
                    }
                    assertEquals(inProgress, owner.isInProgress)
                    owner.setGlowColor(color)
                    assertEquals("Identical tint must not schedule another draw", invalidationsBefore + index + 1, invalidations)
                    edges.forEach { (name, edge) ->
                        assertSame(edge, installedEdge(owner, name))
                        assertEquals(writes.getValue(name) + index + 1, edge.colorWrites)
                        assertEquals(snapshots.getValue(name), physics(edge))
                    }
                }
            } finally { owner.dispose() }
        }
    }

    @Test fun recolorBeforeAndBetweenLazyEdgesUsesLatestColorWithoutCreatingEdges() = main {
        val light = 0xff49454f.toInt()
        val dark = 0xffcac4d0.toInt()
        for (axis in NativeOverscrollAxis.entries) {
            val owner = effect(axis)
            try {
                owner.measured(200, 100)
                owner.setGlowColor(light)
                for (name in listOf("near", "far", "nearNegation", "farNegation")) {
                    assertNull("Recolor must not allocate $name", installedEdge(owner, name))
                }
                owner.scroll(10f, NativeOverscrollSource.USER_INPUT) { 0f }
                val near = checkNotNull(installedEdge(owner, "near"))
                assertEquals(light, near.color)
                assertNull(installedEdge(owner, "far"))
                owner.setGlowColor(dark)
                assertSame(near, installedEdge(owner, "near"))
                assertEquals(dark, near.color)
                assertNull(installedEdge(owner, "far"))
                // Finish only in the test so opposite input creates far instead of destretching near.
                near.finish()
                owner.scroll(-10f, NativeOverscrollSource.USER_INPUT) { 0f }
                assertEquals(dark, checkNotNull(installedEdge(owner, "far")).color)
                assertSame(near, installedEdge(owner, "near"))
                assertNull(installedEdge(owner, "nearNegation"))
                assertNull(installedEdge(owner, "farNegation"))
            } finally { owner.dispose() }
        }
    }

    @Test fun zeroBoundsThresholdAndSourceAdmissionKeepConsumptionIndependentOfPulls() = main {
        for (axis in NativeOverscrollAxis.entries) {
            val owner = effect(axis)
            val near = Edge(context)
            install(owner, "near", near)
            val inactive = owner.beginFling(75f)
            assertEquals(75f, inactive.remainingVelocity, 0f)
            assertEquals(3f, owner.scroll(9f, NativeOverscrollSource.USER_INPUT) { 3f }, 0f)
            assertTrue(near.pulls.isEmpty())
            owner.measured(200, 100)
            assertEquals(if (axis == NativeOverscrollAxis.HORIZONTAL) 100 to 200 else 200 to 100, near.dimensions)
            owner.finishFling(inactive, 100f)
            assertTrue(near.absorbs.isEmpty())
            for (delta in listOf(.5f, -.5f, 0f)) {
                assertEquals(0f, owner.scroll(delta, NativeOverscrollSource.USER_INPUT) { 0f }, 0f)
            }
            owner.scroll(10f, NativeOverscrollSource.SIDE_EFFECT) { 0f }
            assertTrue(near.pulls.isEmpty())
            assertEquals(2f, owner.scroll(10f, NativeOverscrollSource.USER_INPUT) { 2f }, 0f)
            assertEquals(8f / if (axis == NativeOverscrollAxis.HORIZONTAL) 200f else 100f, near.pulls.single().first, 0f)
            assertEquals(.5f, near.pulls.single().second, 0f)
            owner.dispose()
        }
    }

    @Test fun pointerReplacementAndUpRetainPhysicalCrossPositionForBothEdges() = main {
        for (axis in NativeOverscrollAxis.entries) {
            val owner = effect(axis)
            val near = Edge(context)
            val far = Edge(context)
            install(owner, "near", near)
            install(owner, "far", far)
            owner.measured(200, 100)
            fun pointer(action: Int, ids: IntArray, xs: FloatArray, ys: FloatArray) {
                val properties = Array(ids.size) { i -> MotionEvent.PointerProperties().apply { id = ids[i] } }
                val positions = Array(ids.size) { i -> MotionEvent.PointerCoords().apply { x = xs[i]; y = ys[i] } }
                val event = MotionEvent.obtain(0, 1, action, ids.size, properties, positions, 0, 0, 1f, 1f, 0, 0, 0, 0)
                try { owner.pointer(event) } finally { event.recycle() }
            }
            pointer(MotionEvent.ACTION_DOWN, intArrayOf(7), floatArrayOf(20f), floatArrayOf(10f))
            pointer(MotionEvent.ACTION_POINTER_UP, intArrayOf(7, 9), floatArrayOf(20f, 50f), floatArrayOf(10f, 75f))
            // Up's coordinates must not replace the final retained position from pointer replacement.
            pointer(MotionEvent.ACTION_UP, intArrayOf(9), floatArrayOf(0f), floatArrayOf(0f))
            owner.scroll(10f, NativeOverscrollSource.USER_INPUT) { 0f }
            owner.scroll(-10f, NativeOverscrollSource.USER_INPUT) { 0f }
            assertEquals(.25f, near.pulls.single().second, 0f)
            assertEquals(.75f, far.pulls.single().second, 0f)
            assertEquals(if (axis == NativeOverscrollAxis.HORIZONTAL) .05f else .1f, near.pulls.single().first, 0f)
            assertEquals(if (axis == NativeOverscrollAxis.HORIZONTAL) .05f else .1f, far.pulls.single().first, 0f)
            owner.dispose()
        }
    }

    @Test fun stretchDestretchUsesFourTimesSideEffectAndSoftwareDrawFinishesEveryEdge() = main {
        if (Build.VERSION.SDK_INT < 31) return@main
        for (axis in NativeOverscrollAxis.entries) {
            val owner = effect(axis)
            val edges = listOf("near", "far", "nearNegation", "farNegation").associateWith { Edge(context) }
            edges.forEach { (name, edge) -> install(owner, name, edge) }
            owner.measured(200, 100)
            val near = edges.getValue("near")
            near.stretch = .1f
            var delegated = Float.NaN
            val consumed = owner.scroll(-4f, NativeOverscrollSource.SIDE_EFFECT) { delegated = it; 0f }
            assertEquals(-1f, consumed, 0f)
            assertEquals(-3f, delegated, 0f)
            assertEquals(listOf(0f, if (axis == NativeOverscrollAxis.HORIZONTAL) -.08f else -.16f), near.pulls.map { it.first })
            edges.values.forEach { it.stretch = .1f }
            val bitmap = Bitmap.createBitmap(240, 140, Bitmap.Config.ARGB_8888)
            try {
                var draws = 0
                owner.draw(Canvas(bitmap)) { draws++ }
                assertEquals(1, draws)
                assertFalse(owner.isInProgress)
                assertTrue(edges.values.all { it.finishes > 0 })
            } finally { bitmap.recycle(); owner.dispose() }
        }
    }

    @Test fun rendererKeepsIndependentAxisTransformsAndCrossAxisClipExpansion() = main {
        for (axis in NativeOverscrollAxis.entries) {
            val owner = effect(axis)
            val near = Edge(context).apply { stretch = .1f }
            val far = Edge(context).apply { stretch = .1f }
            install(owner, "near", near)
            install(owner, "far", far)
            owner.measured(200, 100)
            val bitmap = Bitmap.createBitmap(240, 140, Bitmap.Config.ARGB_8888)
            var contentCalls = 0
            try {
                if (Build.VERSION.SDK_INT >= 31) {
                    val node = RenderNode("overscroll-contract-test").apply { setPosition(0, 0, 240, 140) }
                    val canvas = node.beginRecording()
                    try {
                        owner.draw(canvas) { content ->
                            contentCalls++
                            val expected = if (axis == NativeOverscrollAxis.HORIZONTAL) 240 to 200 else 300 to 140
                            assertEquals(expected, content.width to content.height)
                        }
                    } finally { node.endRecording(); node.discardDisplayList() }
                    if (axis == NativeOverscrollAxis.HORIZONTAL) {
                        assertPoints(listOf(0f, 0f, 0f, -1f), near.drawnOrigins.single())
                        assertPoints(listOf(0f, 0f, 0f, 1f), far.drawnOrigins.single())
                    } else {
                        assertPoints(listOf(0f, 0f, 1f, 0f), near.drawnOrigins.single())
                        assertPoints(listOf(0f, 0f, -1f, 0f), far.drawnOrigins.single())
                    }
                } else {
                    owner.draw(Canvas(bitmap)) { contentCalls++ }
                    if (axis == NativeOverscrollAxis.HORIZONTAL) {
                        assertPoints(listOf(0f, 100f, 0f, 99f), near.drawnOrigins.single())
                        assertPoints(listOf(200f, 0f, 200f, 1f), far.drawnOrigins.single())
                    } else {
                        assertPoints(listOf(0f, 0f, 1f, 0f), near.drawnOrigins.single())
                        assertPoints(listOf(200f, 100f, 199f, 100f), far.drawnOrigins.single())
                    }
                }
                assertEquals(1, contentCalls)
            } finally { bitmap.recycle(); owner.dispose() }
        }
    }

    private fun assertPoints(expected: List<Float>, actual: List<Float>) {
        expected.zip(actual).forEach { (a, b) -> assertEquals(a, b, .001f) }
    }

}
