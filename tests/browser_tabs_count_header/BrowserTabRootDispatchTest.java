import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import kotlin.Unit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import smallthingz.ui.components.NativeInputGate;
import smallthingz.ui.components.NativeStandardIconButtonView;
import smallthingz.ui.reverb.*;

/** Mounted root-dispatched finger input. No direct leaf dispatch, performClick or listener calls. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, application = Application.class, qualifiers = "en-rUS-w380dp-h297dp-421dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class BrowserTabRootDispatchTest {
    @Test public void fingerCornersRouteToCloseAndAdjacentTitleAndPreviewSelect() {
        new Instrumentation().setInTouchMode(true); // This synthetic VM only; never a device operation.
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible();
        Activity activity = controller.get();
        controller.windowFocusChanged(true);
        List<String> mismatches = new ArrayList<>();
        try {
            for (int direction : new int[] {View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
                Configuration config = new Configuration(activity.getResources().getConfiguration());
                config.fontScale = direction == View.LAYOUT_DIRECTION_RTL ? 2f : 1f;
                Context context = activity.createConfigurationContext(config);
                assertEquals(421, context.getResources().getDisplayMetrics().densityDpi);
                Gate root = new Gate(activity);
                // Nonzero root padding catches tests that accidentally use leaf-local coordinates.
                root.setPadding(13, 17, 11, 19);
                ReverbBrowserPanelTabs<String> panel = new ReverbBrowserPanelTabs<>(context, ReverbDesign.INSTANCE.theme(context, true), "Tabs", "Back", "New tab");
                panel.setLayoutDirection(direction);
                List<String> events = new ArrayList<>();
                panel.setOnTabAction((key, action) -> { events.add(key + ":" + action.name()); return Unit.INSTANCE; });
                panel.setOnTabMove((key, target) -> { events.add("MOVE:" + key + ":" + target); return Unit.INSTANCE; });
                panel.bindTabs("2 tabs", Arrays.asList(
                    new ReverbBrowserPanelTab<>("a", "Alpha", null, null, null, true, "Current", "Close Alpha", true),
                    new ReverbBrowserPanelTab<>("b", "Beta", null, null, null, false, "Current", "Close Beta", true)));
                root.addView(panel, new FrameLayout.LayoutParams(-1, -1));
                activity.setContentView(root);
                try {
                    root.measure(exact(1000), exact(782)); root.layout(0, 0, 1000, 782);
                    assertTrue("Real attached window required", panel.isAttachedToWindow() && panel.isShown() && panel.hasWindowFocus());
                    ReverbBrowserPanelSettingRow row = descendants(panel, ReverbBrowserPanelSettingRow.class).stream()
                        .filter(v -> v.getTitleView().getText().toString().equals("Alpha")).findFirst().orElseThrow();
                    NativeStandardIconButtonView close = descendants(panel, NativeStandardIconButtonView.class).stream()
                        .filter(v -> "Close Alpha".contentEquals(v.getVisual().getGlyph().getContentDescription())).findFirst().orElseThrow();
                    int minimum = Math.round(48f * context.getResources().getDisplayMetrics().density);
                    assertEquals(minimum, close.getVisual().getWidth()); assertEquals(minimum, close.getVisual().getHeight());
                    smallthingz.ui.components.NativeBottomActionDock dock = descendants(panel, smallthingz.ui.components.NativeBottomActionDock.class).get(0);
                    System.out.println("runtime-binding direction=" + direction + " footerHeight=" + panel.getFooterHeight()
                        + " dockMinimum=" + dock.getMinimumActionHeightPx() + " dockActionHeight=" + dock.getAction().getHeight()
                        + " nativeDock=" + smallthingz.ui.components.NativeBottomActionDock.class.getProtectionDomain().getCodeSource().getLocation()
                        + " reverbDock=" + ReverbBottomActionDock.class.getProtectionDomain().getCodeSource().getLocation()
                        + " panel=" + ReverbBrowserPanelTabs.class.getProtectionDomain().getCodeSource().getLocation());
                    assertEquals("Current Reverb dock floor must be48dp", minimum, dock.getMinimumActionHeightPx());
                    assertTrue("Dock action retains48dp actual height", dock.getAction().getHeight() >= minimum);
                    Rect bounds = relative(root, close.getVisual());
                    // The native semantic owner must describe the same full target we tap.
                    AccessibilityNodeInfo node = close.getVisual().createAccessibilityNodeInfo();
                    assertNotNull(node);
                    try {
                        Rect semantic = new Rect(); node.getBoundsInScreen(semantic);
                        int[] rootScreen = new int[2]; root.getLocationOnScreen(rootScreen);
                        Rect expectedScreen = new Rect(bounds); expectedScreen.offset(rootScreen[0], rootScreen[1]);
                        assertEquals("Close semantic bounds match the actual 48dp owner", expectedScreen, semantic);
                        assertTrue(node.isEnabled() && node.isVisibleToUser());
                        assertEquals("Close Alpha", String.valueOf(node.getContentDescription()));
                        assertEquals(1, node.getActionList().stream().filter(a -> a.getId() == AccessibilityNodeInfo.ACTION_CLICK).count());
                    } finally { node.recycle(); }
                    assertEquals("Compact glyph remains centered in full touch owner", close.getVisual().getWidth()/2f,
                        close.getVisual().getGlyph().getLeft()+close.getVisual().getGlyph().getWidth()/2f, .5f);
                    assertEquals(20f*context.getResources().getDisplayMetrics().density,
                        close.getVisual().getGlyph().getWidth()*close.getVisual().getGlyph().getScaleX(), 1f);
                    // Close stays outside the rounded surface's pointer-content clipping chain.
                    for (android.view.ViewParent parent = close.getParent(); parent instanceof View; parent=parent.getParent())
                        assertFalse("Group clipping must not reject close's outer corners", parent instanceof smallthingz.ui.components.NativeGroupLayout);
                    Rect title = relative(root, row);
                    Rect preview = panel.tabPreviewBounds("a"); assertNotNull(preview);
                    Rect panelInRoot = relative(root, panel); preview.offset(panelInRoot.left, panelInRoot.top);
                    float inset = .5f; // Deliberately inside the actual owner's edge, outside its circular glyph region.
                    float[][] points = {
                        {bounds.exactCenterX(), bounds.exactCenterY()},
                        {bounds.left + inset, bounds.top + inset},
                        {bounds.right - inset, bounds.top + inset},
                        {bounds.left + inset, bounds.bottom - inset},
                        {bounds.right - inset, bounds.bottom - inset},
                        {bounds.exactCenterX(), bounds.top + inset},
                        {bounds.right - inset, bounds.exactCenterY()},
                        {bounds.exactCenterX(), bounds.bottom - inset},
                        {bounds.left + inset, bounds.exactCenterY()},
                        {direction == View.LAYOUT_DIRECTION_LTR ? bounds.left - inset : bounds.right + inset, bounds.exactCenterY()},
                        {title.exactCenterX(), title.exactCenterY()},
                        {preview.exactCenterX(), preview.exactCenterY()}
                    };
                    String[] labels = {"close-center", "close-top-left", "close-top-right", "close-bottom-left", "close-bottom-right", "close-top-edge", "close-right-edge", "close-bottom-edge", "close-left-edge", "adjacent-title", "title-center", "preview-center"};
                    for (int i = 0; i < points.length; i++) {
                        // A mounted Activity may consume its queued traversal after the prior tap.
                        // Sample the live owners for every probe, never stale pre-traversal rectangles.
                        Rect liveClose = relative(root, close.getVisual());
                        Rect liveTitle = relative(root, row);
                        Rect livePreview = panel.tabPreviewBounds("a");
                        Rect livePanel = relative(root, panel); livePreview.offset(livePanel.left, livePanel.top);
                        float[][] livePoints = {
                            {liveClose.exactCenterX(), liveClose.exactCenterY()},
                            {liveClose.left + inset, liveClose.top + inset},
                            {liveClose.right - inset, liveClose.top + inset},
                            {liveClose.left + inset, liveClose.bottom - inset},
                            {liveClose.right - inset, liveClose.bottom - inset},
                            {liveClose.exactCenterX(), liveClose.top + inset},
                            {liveClose.right - inset, liveClose.exactCenterY()},
                            {liveClose.exactCenterX(), liveClose.bottom - inset},
                            {liveClose.left + inset, liveClose.exactCenterY()},
                            {direction == View.LAYOUT_DIRECTION_LTR ? liveClose.left - inset : liveClose.right + inset, liveClose.exactCenterY()},
                            {liveTitle.exactCenterX(), liveTitle.exactCenterY()},
                            {livePreview.exactCenterX(), livePreview.exactCenterY()}
                        };
                        System.out.println("live-geometry point=" + labels[i] + " root=" + root.getWidth() + "x" + root.getHeight()
                            + " before=" + bounds + " current=" + liveClose);
                        points[i] = livePoints[i];
                        events.clear();
                        boolean handled = tap(root, points[i][0], points[i][1]);
                        List<String> expected = Collections.singletonList(i < 9 ? "a:CLOSE" : "a:SELECT");
                        String observation = "direction=" + direction + " point=" + labels[i] + " xy=" + Arrays.toString(points[i])
                            + " close=" + liveClose + " handled=" + handled + " events=" + events + " expected=" + expected;
                        System.out.println(observation);
                        if (!handled || !events.equals(expected)) mismatches.add(observation);
                    }
                    // Admission failure must suppress callbacks even when rectangular hit testing succeeds.
                    root.admitted = false; events.clear();
                    tap(root, bounds.exactCenterX(), bounds.exactCenterY());
                    if (!events.isEmpty()) mismatches.add("denied root delivered " + events);
                    root.admitted = true;
                    // A canceled real swipe must move the close together with its preview,
                    // cancel the child tap, and restore the same retained card without callbacks.
                    Rect swipePreview = panel.tabPreviewBounds("a");
                    Rect swipePanel = relative(root, panel); swipePreview.offset(swipePanel.left, swipePanel.top);
                    float sx = swipePreview.exactCenterX(), sy = swipePreview.exactCenterY();
                    long down = SystemClock.uptimeMillis(); events.clear();
                    int[] closeBefore = new int[2], previewBefore = new int[2], closeDuring = new int[2], previewDuring = new int[2];
                    close.getLocationOnScreen(closeBefore); row.getLocationOnScreen(previewBefore);
                    send(root, down, MotionEvent.ACTION_DOWN, sx, sy);
                    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16));
                    send(root, down, MotionEvent.ACTION_MOVE, sx + 100f, sy);
                    close.getLocationOnScreen(closeDuring); row.getLocationOnScreen(previewDuring);
                    assertTrue("Root swipe moves close", closeDuring[0] > closeBefore[0]);
                    assertEquals("Close and card move together", closeDuring[0] - closeBefore[0], previewDuring[0] - previewBefore[0]);
                    send(root, down, MotionEvent.ACTION_CANCEL, sx + 100f, sy);
                    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80));
                    close.getLocationOnScreen(closeDuring);
                    assertEquals("Canceled swipe restores close", closeBefore[0], closeDuring[0]);
                    assertTrue("Canceled swipe emits no close/select/move", events.isEmpty());
                    System.out.println("swipe-cancel direction=" + direction + " shared translation and restoration PASS; no callbacks");
                } finally { panel.close(); root.removeAllViews(); }
            }
            assertTrue("Root routing failures (do not replace these with direct leaf clicks):\n" + String.join("\n", mismatches), mismatches.isEmpty());
        } finally { controller.pause().stop().destroy(); }
    }

    private static boolean tap(View root, float x, float y) {
        long down = SystemClock.uptimeMillis();
        boolean first = send(root, down, MotionEvent.ACTION_DOWN, x, y);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16));
        boolean last = send(root, down, MotionEvent.ACTION_UP, x, y);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80));
        return first && last;
    }
    private static boolean send(View root, long down, int action, float x, float y) {
        MotionEvent.PointerProperties property = new MotionEvent.PointerProperties(); property.id = 0; property.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords coordinates = new MotionEvent.PointerCoords(); coordinates.x = x; coordinates.y = y; coordinates.pressure = 1f; coordinates.size = 1f;
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1,
            new MotionEvent.PointerProperties[] {property}, new MotionEvent.PointerCoords[] {coordinates},
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try { return root.dispatchTouchEvent(event); } finally { event.recycle(); }
    }
    private static int exact(int px) { return View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY); }
    private static Rect relative(ViewGroup root, View view) {
        Rect result = new Rect(0, 0, view.getWidth(), view.getHeight()); root.offsetDescendantRectToMyCoords(view, result); return result;
    }
    private static <T> List<T> descendants(View root, Class<T> type) {
        List<T> result = new ArrayList<>();
        if (type.isInstance(root)) result.add(type.cast(root));
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) result.addAll(descendants(((ViewGroup) root).getChildAt(i), type));
        return result;
    }
    private static final class Gate extends FrameLayout implements NativeInputGate {
        boolean admitted = true;
        Gate(Context context) { super(context); }
        @Override public boolean getAcceptsNativeInput() { return admitted; }
    }
}
