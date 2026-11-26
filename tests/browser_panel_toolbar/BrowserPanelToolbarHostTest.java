import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.SystemClock;
import android.os.Looper;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import java.time.Duration;
import org.robolectric.Shadows;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import kotlin.Unit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import smallthingz.ui.graphics.NoiseDrawable;
import smallthingz.ui.graphics.NoiseTexture;
import smallthingz.ui.reverb.*;

/** Only the browser panel toolbar boundary; no Chrome/address/count/global header cases. */
@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 36, qualifiers = "w380dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class BrowserPanelToolbarHostTest {
    @Before public void prepareGrain() throws Exception {
        NoiseTexture.INSTANCE.prepare();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (NoiseTexture.INSTANCE.shader(NoiseDrawable.SEED_TOP_BAR) == null && System.nanoTime() < deadline) Thread.sleep(10);
        assertNotNull(NoiseTexture.INSTANCE.shader(NoiseDrawable.SEED_TOP_BAR));
    }
    @Test public void browserPanelsUseUnboxedUtilitiesWithRetainedTargetsAndCallbacks() throws Exception {
        List<String> surfaceFailures = new ArrayList<>();
        List<String> geometryFailures = new ArrayList<>();
        List<String> inputFailures = new ArrayList<>();
        new Instrumentation().setInTouchMode(true);
        for (boolean settings : new boolean[] {false, true}) {
            ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible();
            try {
                Activity activity = controller.get();
                FrameLayout root = new FrameLayout(activity);
                root.setPadding(7, 11, 9, 13);
                ReverbTheme initial = ReverbDesign.INSTANCE.theme(activity, false);
                ReverbBrowserPanelLayout panel;
                List<String> delivered = new ArrayList<>();
                if (settings) {
                    ReverbBrowserPanelSettings value = new ReverbBrowserPanelSettings(activity, initial, "Browser settings", "Back", "Undo", "Apply");
                    value.setOnPanelAction(action -> { delivered.add(action.name()); return Unit.INSTANCE; });
                    value.bindPendingActions(true);
                    panel = value;
                } else {
                    ReverbBrowserPanelTabs<String> value = new ReverbBrowserPanelTabs<>(activity, initial, "Tabs", "Back", "New tab");
                    value.setOnTabAction((key, action) -> { delivered.add(action.name()); return Unit.INSTANCE; });
                    panel = value;
                }
                root.addView(panel, new FrameLayout.LayoutParams(-1, -1));
                activity.setContentView(root);
                controller.windowFocusChanged(true);
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
                List<ReverbBrowserPanelToolbarAction> actions = new ArrayList<>();
                collect(panel, actions);
                assertEquals(settings ? 3 : 1, actions.size());
                try {
                    for (boolean dark : new boolean[] {false, true}) {
                        ReverbTheme theme = ReverbDesign.INSTANCE.theme(activity, dark);
                        if (settings) ((ReverbBrowserPanelSettings) panel).bindTheme(theme);
                        else ((ReverbBrowserPanelTabs<?>) panel).bindTheme(theme);
                        for (int direction : new int[] {View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
                            panel.setLayoutDirection(direction); layout(root, 380, 900);
                            frames(); layout(root, 380, 900);
                            panel.requestFocus();
                            String tag = (settings ? "settings" : "tabs") + "-dark" + dark + "-rtl" + (direction == View.LAYOUT_DIRECTION_RTL);
                            Bitmap visible = raster(panel);
                            try { save(visible, tag + ".png"); } finally { visible.recycle(); }
                            for (ReverbBrowserPanelToolbarAction action : actions) {
                                View owner = action.getAccessibilityAction();
                                if (owner.getWidth() != 48 || owner.getHeight() != 48) geometryFailures.add(tag + ":target=" + owner.getWidth() + "x" + owner.getHeight());
                                assertTrue(owner.isEnabled()); assertTrue(owner.isClickable());
                                assertTrue(owner.getFilterTouchesWhenObscured());
                                assertTrue(hasDescription(action, owner.getTooltipText().toString()));
                                action.setVisibility(View.INVISIBLE);
                            }
                            Bitmap withoutActions = raster(panel);
                            final List<Rect> glyphs = new ArrayList<>();
                            for (ReverbBrowserPanelToolbarAction action : actions) {
                                action.setVisibility(View.VISIBLE);
                                action.setIconPainter((canvas, bounds, tint) -> { glyphs.add(new Rect(bounds)); return Unit.INSTANCE; });
                            }
                            Bitmap withoutGlyphs = raster(panel);
                            try {
                                save(withoutGlyphs, tag + "-utilities-no-glyph.png");
                                if (!withoutActions.sameAs(withoutGlyphs)) surfaceFailures.add(tag);
                                for (Rect bounds : glyphs) {
                                    if (bounds.width() != 22 || bounds.height() != 22) geometryFailures.add(tag + ":glyph=" + bounds);
                                }
                                assertFalse("actual icon draw path reached", glyphs.isEmpty());
                            } finally {
                                withoutActions.recycle(); withoutGlyphs.recycle();
                                for (ReverbBrowserPanelToolbarAction action : actions) action.setIconPainter(null);
                            }
                            ReverbTitledTopBarLayout toolbar = (ReverbTitledTopBarLayout) panel.getChildAt(0);
                            Rect titleBounds = relative(root, toolbar.getContentClipView().getTitleView());
                            if (settings && toolbar.getContentClipView().getActionView().getWidth() != 96)
                                geometryFailures.add(tag + ":paired-action-slot=" + toolbar.getContentClipView().getActionView().getWidth());
                            List<Rect> owners = new ArrayList<>();
                            for (ReverbBrowserPanelToolbarAction action : actions) {
                                View owner = action.getAccessibilityAction();
                                Rect bounds = relative(root, owner), visibleBounds = new Rect();
                                if (!owner.getGlobalVisibleRect(visibleBounds) || visibleBounds.width() != 48 || visibleBounds.height() != 48)
                                    geometryFailures.add(tag + ":visible-owner=" + visibleBounds);
                                if (Rect.intersects(bounds, titleBounds)) geometryFailures.add(tag + ":owner overlaps title");
                                for (Rect other : owners) if (Rect.intersects(bounds, other)) geometryFailures.add(tag + ":owners overlap");
                                owners.add(bounds);
                                assertTrue("Real attached, shown, focused input hierarchy", action.isAttachedToWindow() && action.isShown() && action.hasWindowFocus());
                                String expected = owner.getTooltipText().toString().toUpperCase(java.util.Locale.ROOT);
                                for (int point = 0; point < 5; point++) {
                                    // Pending Android traversal may restore window geometry between events.
                                    layout(root, 380, 900); bounds = relative(root, owner);
                                    float[][] points = {{bounds.left + 24f, bounds.top + 24f},
                                        {bounds.left + .5f, bounds.top + .5f}, {bounds.left + 47.5f, bounds.top + .5f},
                                        {bounds.left + .5f, bounds.top + 47.5f}, {bounds.left + 47.5f, bounds.top + 47.5f}};
                                    int before = delivered.size();
                                    boolean consumed = tap(root, points[point][0], points[point][1]);
                                    if (!consumed || delivered.size() != before + 1 || !expected.equals(delivered.get(before)))
                                        inputFailures.add(tag + ":" + expected + ":point" + point + ":" + delivered.subList(before, delivered.size()));
                                }
                                layout(root, 380, 900); bounds = relative(root, owner);
                                owner.setEnabled(false); int beforeDisabled = delivered.size();
                                tap(root, bounds.left + 24f, bounds.top + 24f);
                                if (delivered.size() != beforeDisabled) inputFailures.add(tag + ":disabled " + expected + " delivered");
                                owner.setEnabled(true);
                            }
                        }
                    }
                    // Closed-owner rejection also starts at the mounted root, never performClick.
                    int beforeClose = delivered.size(); panel.close();
                    for (ReverbBrowserPanelToolbarAction action : actions) {
                        layout(root,380,900); Rect bounds = relative(root,action.getAccessibilityAction());
                        tap(root,bounds.left+24f,bounds.top+24f);
                    }
                    assertEquals("closed panel cannot deliver to former callback", beforeClose, delivered.size());
                } finally { panel.close(); }
            } finally { controller.pause().stop().destroy(); }
        }
        assertTrue("Browser utilities add a separate idle surface/border: " + surfaceFailures, surfaceFailures.isEmpty());
        assertTrue("Browser utilities must retain48dp owners and22dp glyphs: " + geometryFailures, geometryFailures.isEmpty());
        assertTrue("Root-dispatched finger routing: " + inputFailures, inputFailures.isEmpty());
    }
    private static void frames() {
        for (int i=0; i<32; i++) Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16));
    }
    private static Rect relative(ViewGroup root, View view) {
        Rect bounds = new Rect(0,0,view.getWidth(),view.getHeight()); root.offsetDescendantRectToMyCoords(view,bounds); return bounds;
    }
    private static boolean tap(View root, float x, float y) {
        long down=SystemClock.uptimeMillis(); boolean first=send(root,down,MotionEvent.ACTION_DOWN,x,y);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16));
        boolean last=send(root,down,MotionEvent.ACTION_UP,x,y);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80)); return first && last;
    }
    private static boolean send(View root,long down,int action,float x,float y) {
        MotionEvent.PointerProperties p=new MotionEvent.PointerProperties();p.id=0;p.toolType=MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords c=new MotionEvent.PointerCoords();c.x=x;c.y=y;c.pressure=1f;c.size=1f;
        MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,1,
            new MotionEvent.PointerProperties[]{p},new MotionEvent.PointerCoords[]{c},0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        try{return root.dispatchTouchEvent(event);}finally{event.recycle();}
    }
    private static void collect(View view, List<ReverbBrowserPanelToolbarAction> out) {
        if (view instanceof ReverbBrowserPanelToolbarAction) out.add((ReverbBrowserPanelToolbarAction) view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) collect(((ViewGroup) view).getChildAt(i), out);
    }
    private static boolean hasDescription(View view, String text) {
        if (text.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return true;
        if (view instanceof ViewGroup) for (int i=0; i<((ViewGroup)view).getChildCount(); i++) if (hasDescription(((ViewGroup)view).getChildAt(i),text)) return true;
        return false;
    }
    private static void layout(View view, int w, int h) {
        view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY)); view.layout(0,0,w,h);
    }
    private static Bitmap raster(View view) {
        Bitmap image = Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888); view.draw(new Canvas(image)); return image;
    }
    private static void save(Bitmap image, String name) throws Exception {
        try (FileOutputStream out = new FileOutputStream(new File(System.getProperty("address.test.output"),name))) { image.compress(Bitmap.CompressFormat.PNG,100,out); }
    }
}
