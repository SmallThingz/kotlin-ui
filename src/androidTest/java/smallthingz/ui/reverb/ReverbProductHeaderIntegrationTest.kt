package smallthingz.ui.reverb

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import smallthingz.ui.components.NativeMenuAccessibilityTestActivity
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class ReverbProductHeaderIntegrationTest {
    @Test fun countsExtendHeaderWithoutResizingActionRowAndIdentityRetainsAnchor() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val theme = ReverbDesign.theme(context, dark = false)
            val header = ReverbProductTopBar(context, theme)
            val row = header.getChildAt(0) as ReverbTopBar
            val strip = header.getChildAt(1) as ViewGroup
            val settings = ReverbHeaderAction("Settings", {})
            fun bind(label: String, browser: ReverbHeaderAction?, counts: List<ReverbHeaderCount>) {
                header.bind(theme, ReverbProductIdentity.Monochrome(android.R.drawable.ic_menu_info_details),
                    label, {}, browser, settings, counts = counts)
                val width = (320 * theme.density.density).roundToInt()
                header.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                header.layout(0, 0, width, header.measuredHeight)
            }
            bind("About", ReverbHeaderAction("Browser", {}), emptyList())
            val anchor = checkNotNull(row.centerAnchor)
            val actionHeight = (58 * theme.density.density).roundToInt()
            assertEquals(actionHeight, row.measuredHeight)
            assertEquals(actionHeight, header.measuredHeight)
            assertEquals(View.GONE, strip.visibility)

            bind("About Control", null, listOf(ReverbHeaderCount("Completed", "1.2K", "Completed: 1,234")))
            assertSame(anchor, row.centerAnchor)
            assertEquals("About Control", anchor.contentDescription)
            assertEquals(actionHeight, row.measuredHeight)
            assertTrue(header.measuredHeight > actionHeight)
            assertEquals("Completed: 1,234", strip.getChildAt(0).contentDescription)
        }
    }


    @Test fun attachedIdentityDistinguishesUserDismissalAnchorDetachAndOwnerClose() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            NativeMenuAccessibilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var root: FrameLayout
        lateinit var header: ReverbProductTopBar
        lateinit var anchor: View
        var opens = 0
        var userDismissals = 0
        var windowDismissals = 0

        fun await(message: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 5_000L
            var ready = false
            do {
                instrumentation.runOnMainSync { ready = condition() }
                if (ready) return
                SystemClock.sleep(16L)
            } while (SystemClock.uptimeMillis() < deadline)
            assertTrue(message, ready)
        }

        fun openAbout(expectedOpens: Int) {
            await("About anchor should be attached and focused", anchor::hasWindowFocus)
            instrumentation.runOnMainSync {
                assertTrue(anchor.performClick())
                assertEquals(expectedOpens, opens)
            }
            await("About window should take focus") { !anchor.hasWindowFocus() }
            instrumentation.waitForIdleSync()
        }

        try {
            instrumentation.runOnMainSync {
                val theme = ReverbDesign.theme(activity, dark = false)
                val details = ReverbAboutDetails("Control", "1.0", "Source", "Repository", "Unavailable")
                root = FrameLayout(activity)
                header = ReverbProductTopBar(activity, theme)
                header.bind(theme,
                    identity = ReverbProductIdentity.Monochrome(android.R.drawable.ic_menu_info_details),
                    aboutLabel = "About Control",
                    onAbout = {
                        if (header.openAbout(theme, details, "About Control", openRepository = { true },
                                onUserDismiss = { userDismissals++ }, onWindowDismissed = { windowDismissals++ })) {
                            opens++
                        }
                    },
                    browser = null,
                    settings = ReverbHeaderAction("Settings", {}),
                )
                anchor = checkNotNull((header.getChildAt(0) as ReverbTopBar).centerAnchor)
                root.addView(header, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                activity.setContentView(root)
            }

            await("Header should be attached and focused", anchor::hasWindowFocus)
            var wasInTouchMode = false
            instrumentation.runOnMainSync { wasInTouchMode = anchor.isInTouchMode }
            if (wasInTouchMode) instrumentation.setInTouchMode(false)
            try {
                await("Header should accept keyboard focus") { !anchor.isInTouchMode }
                instrumentation.runOnMainSync {
                    assertTrue(header.requestSettingsFocus())
                    val focused = root.findFocus()?.createAccessibilityNodeInfo()
                    assertEquals("Settings", focused?.contentDescription?.toString())
                }
            } finally {
                if (wasInTouchMode) instrumentation.setInTouchMode(true)
            }

            openAbout(1)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("Back should complete the user-dismiss callback") { userDismissals == 1 }
            await("Focus should return to the host window", anchor::hasWindowFocus)
            instrumentation.runOnMainSync { assertEquals(0, windowDismissals) }

            openAbout(2)
            instrumentation.runOnMainSync { root.removeView(header) }
            await("Anchor detach should retire the window") { windowDismissals == 1 }
            await("Host should regain window focus", root::hasWindowFocus)
            instrumentation.runOnMainSync {
                assertEquals(1, userDismissals)
                root.addView(header, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }

            openAbout(3)
            instrumentation.runOnMainSync {
                header.closeAbout()
                header.closeAbout()
            }
            await("Owner close should retire the window", anchor::hasWindowFocus)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(1, userDismissals)
                assertEquals(1, windowDismissals)
            }
        } finally {
            instrumentation.runOnMainSync {
                activity.finish()
            }
        }
    }
}
