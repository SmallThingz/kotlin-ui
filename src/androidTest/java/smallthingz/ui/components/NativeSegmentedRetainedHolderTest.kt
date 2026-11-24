package smallthingz.ui.components

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.reverb.settings.ReverbRetentionMode
import smallthingz.ui.reverb.settings.ReverbRetentionSection
import smallthingz.ui.reverb.settings.ReverbSettingsSection
import smallthingz.ui.reverb.settings.ReverbSettingsSectionFactory
import smallthingz.ui.reverb.settings.ReverbSettingsSectionKey
import smallthingz.ui.reverb.settings.ReverbSettingsSectionListView
import smallthingz.ui.reverb.settings.ReverbThemeSelector
import kotlin.math.roundToInt

/** Real attached RV cache/reattachment; no fabricated attach flags or manual child layout.
 * This verifies the layout prerequisite of Main's strict hit path, not physical input delivery. */
@RunWith(AndroidJUnit4::class)
class NativeSegmentedRetainedHolderTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
    private fun rect(view: View) = Rect(view.left, view.top, view.right, view.bottom)

    @Test fun themeCachedHolderRestoresDescendantLayoutAtUnchangedBounds() =
        checkRetained(ReverbSettingsSectionKey.THEME)

    @Test fun retentionCachedHolderRestoresDescendantLayoutAtUnchangedBounds() =
        checkRetained(ReverbSettingsSectionKey.RETENTION)

    private fun checkRetained(targetKey: ReverbSettingsSectionKey) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var sections: ReverbSettingsSectionListView? = null
        var callbacks = 0
        try {
            lateinit var host: FrameLayout
            lateinit var viewport: RecyclerView
            var targetPosition = -1
            main {
                val environment = ReverbEnvironment(activity, false).forSettings()
                val factory = ReverbSettingsSectionFactory { key ->
                    if (key != targetKey) object : ReverbSettingsSection {
                        override val view = View(activity).apply {
                            minimumHeight = (800 * environment.textDensity.density).roundToInt()
                        }
                        override fun bind() = Unit
                        override fun close() = Unit
                    } else if (key == ReverbSettingsSectionKey.THEME) {
                        val selector = ReverbThemeSelector(activity, environment.theme, NativeThemeMode.SYSTEM,
                            { true }, { callbacks++; true })
                        object : ReverbSettingsSection {
                            override val view = selector.view
                            override fun bind() = selector.bind(NativeThemeMode.SYSTEM, environment.theme)
                            override fun close() = selector.close()
                        }
                    } else {
                        val selector = ReverbRetentionSection(activity, ReverbRetentionMode.TIME, environment,
                            { "Retention" }, { true }, { callbacks++; true },
                            TextView(activity).apply { text = "One shot" },
                            TextView(activity).apply { text = "Looping" })
                        object : ReverbSettingsSection {
                            override val view = selector
                            override fun bind() = selector.bind(ReverbRetentionMode.TIME, null, environment)
                            override fun close() = selector.close()
                        }
                    }
                }
                val list = ReverbSettingsSectionListView(activity, factory, environment, restricted = false)
                sections = list
                viewport = list.viewport
                // Retain real holders in RV's cache; never disable caching to hide the defect.
                viewport.setItemViewCacheSize(8)
                checkNotNull(viewport.layoutManager).isItemPrefetchEnabled = false
                val adapter = checkNotNull(viewport.adapter)
                targetPosition = (0 until adapter.itemCount).single { adapter.getItemViewType(it) == targetKey.ordinal }
                host = FrameLayout(activity).apply {
                    addView(list, FrameLayout.LayoutParams(-1, (240 * environment.textDensity.density).roundToInt()))
                }
                activity.setContentView(host)
            }
            fun await(label: String, predicate: () -> Boolean) {
                val deadline = SystemClock.uptimeMillis() + 5_000L
                while (true) {
                    instrumentation.waitForIdleSync()
                    var ready = false
                    main { ready = predicate() }
                    if (ready) return
                    check(SystemClock.uptimeMillis() < deadline) { "Timed out: $label" }
                    Thread.sleep(16)
                }
            }
            fun settleAt(position: Int) {
                main { viewport.scrollToPosition(position) }
                await("real RV placement $position") {
                    host.hasWindowFocus() && viewport.isLaidOut && !viewport.isLayoutRequested &&
                        !viewport.isComputingLayout && !viewport.hasPendingAdapterUpdates() &&
                        viewport.findViewHolderForAdapterPosition(position)?.itemView?.isAttachedToWindow == true
                }
            }
            await("initial window layout") { host.hasWindowFocus() && viewport.isLaidOut && !viewport.isLayoutRequested }
            settleAt(targetPosition)
            lateinit var retained: RecyclerView.ViewHolder
            lateinit var capsule: NativeSegmentedControl
            lateinit var choice: NativeSegmentedChoice
            lateinit var children: List<View>
            lateinit var originalBounds: List<Rect>
            main {
                retained = checkNotNull(viewport.findViewHolderForAdapterPosition(targetPosition))
                capsule = descendants(retained.itemView).filterIsInstance<NativeSegmentedControl>().single()
                choice = descendants(capsule).filterIsInstance<NativeSegmentedChoice>().single()
                children = descendants(capsule).toList()
                assertTrue(children.all { it.isAttachedToWindow && it.isLaidOut })
                assertEquals(if (targetKey == ReverbSettingsSectionKey.THEME) 3 else 2, choice.childCount)
                originalBounds = children.map(::rect)
                assertEquals(0, callbacks)
            }
            repeat(2) {
                settleAt(checkNotNull(viewport.adapter).itemCount - 1)
                await("target actually detached into cache") { !retained.itemView.isAttachedToWindow }
                main {
                    assertFalse(choice.isAttachedToWindow)
                    assertFalse(capsule.acceptsNativeInput)
                }
                settleAt(targetPosition)
                main {
                    // Do not await or force descendant layout: assert the result of the real
                    // completed RV placement, which is where the original source fails.
                    assertSame(retained, viewport.findViewHolderForAdapterPosition(targetPosition))
                    assertEquals(children, descendants(capsule).toList())
                    assertEquals("No size/relative-position change should repair this accidentally",
                        originalBounds, children.map(::rect))
                    for (child in children) {
                        assertTrue("${child.javaClass.name} attached", child.isAttachedToWindow)
                        assertTrue("${child.javaClass.name} laid out after cached reattach", child.isLaidOut)
                    }
                    assertTrue(choice.isShown)
                    assertTrue(choice.getGlobalVisibleRect(Rect()))
                    assertTrue(capsule.acceptsNativeInput)
                    assertEquals("Reattachment must not invoke selection", 0, callbacks)
                }
            }
        } finally {
            main { try { sections?.close() } finally { activity.finish() } }
        }
    }
}
