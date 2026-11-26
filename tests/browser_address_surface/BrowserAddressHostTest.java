import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.text.Editable;
import android.text.InputType;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import androidx.core.view.ViewCompat;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import kotlin.Unit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runner.JUnitCore;
import org.junit.runner.Request;
import org.junit.runner.Result;
import org.junit.internal.TextListener;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import smallthingz.ui.components.NativeTextField;
import smallthingz.ui.graphics.NoiseDrawable;
import smallthingz.ui.graphics.NoiseTexture;
import smallthingz.ui.reverb.*;

/** Real Android Views/InputConnection with native Skia; no phone, WebView, app service or network. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class BrowserAddressHostTest {
    public static void main(String[] args) {
        JUnitCore core = new JUnitCore(); core.addListener(new TextListener(System.out));
        Result result = core.run(Request.method(BrowserAddressHostTest.class, args[0]));
        System.exit(result.wasSuccessful() ? 0 : 1);
    }
    @Before public void prepareGrain() throws Exception {
        NoiseTexture.INSTANCE.prepare();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (NoiseTexture.INSTANCE.shader(NoiseDrawable.SEED_TOP_BAR) == null && System.nanoTime() < deadline) Thread.sleep(10);
        assertNotNull("Use actual prepared grain", NoiseTexture.INSTANCE.shader(NoiseDrawable.SEED_TOP_BAR));
    }

    @Test public void roundAddressPixelsAndFullUtilityTargetsAcrossThemesSizesRtl() throws Exception {
        for (boolean dark : new boolean[] {false, true}) for (float font : new float[] {1f, 2f}) {
            Context context = context(font);
            ReverbBrowserChrome chrome = chrome(context, dark);
            try {
                NativeTextField editor = chrome.getAddressEditor();
                editor.setHint(""); editor.setCursorVisible(false);
                for (ReverbBrowserChromeActionView a : actions(chrome)) a.setIconPainter((canvas, bounds, tint) -> Unit.INSTANCE);
                for (int direction : new int[] {View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
                    chrome.setLayoutDirection(direction);
                    for (int width : new int[] {320, 224, 600}) {
                        layout(chrome, width, 400);
                        ViewGroup toolbar = toolbar(chrome);
                        Rect field = relative(toolbar, editor);
                        Bitmap actual = raster(toolbar), material = background(toolbar);
                        try {
                            if (width == 320 && font == 1f && direction == View.LAYOUT_DIRECTION_LTR)
                                save(actual, "address-surface-" + (dark ? "dark" : "light") + ".png");
                            int differences = 0;
                            for (int y = 0; y < actual.getHeight(); y++) for (int x = 0; x < actual.getWidth(); x++) {
                                if (actual.getPixel(x, y) != material.getPixel(x, y)) {
                                    assertTrue("only address may add an idle surface", field.contains(x, y)); differences++;
                                }
                            }
                            assertTrue("persistent address silhouette missing", differences > 0);
                            assertEquals("rounded corner exposes parent", material.getPixel(field.left, field.top), actual.getPixel(field.left, field.top));
                            assertEquals(0f, editor.getElevation(), 0f);
                            assertTrue(editor.getWidth() - editor.getCompoundPaddingLeft() - editor.getCompoundPaddingRight() >= 48);
                            for (ReverbBrowserChromeActionView a : actions(chrome)) {
                                Rect hit = relative(toolbar, a.getAccessibilityAction());
                                assertTrue("real hit owner remains48dp", hit.width() >= 48 && hit.height() >= 48);
                                assertFalse(Rect.intersects(hit, field));
                            }
                        } finally { actual.recycle(); material.recycle(); }
                    }
                }
            } finally { chrome.close(); }
        }
        for (boolean dark : new boolean[] {false, true}) {
            ReverbBrowserChrome chrome = chrome(context(1f), dark);
            try {
                chrome.getAddressEditor().setText("example.com");
                count(chrome, 12, "12 tabs"); layout(chrome, 320, 400);
                Bitmap image = raster(toolbar(chrome));
                try { save(image, "toolbar-idle-" + (dark ? "dark" : "light") + ".png"); }
                finally { image.recycle(); }
            } finally { chrome.close(); }
        }
    }

    @Test public void focusedOutlineRemainsAtViewportDuringScrollAndRetiresOnBlur() throws Exception {
        try (Mounted m = new Mounted()) {
            NativeTextField editor = m.chrome.getAddressEditor();
            editor.setText("https://example.test/" + "long-segment/".repeat(80)); editor.setCursorVisible(false);
            editor.setSelection(0); layout(m.chrome, 320, 400);
            ViewGroup owner = toolbar(m.chrome);
            Rect field = relative(owner, editor);
            assertFalse(editor.hasFocus());
            Bitmap beforeFocus = raster(owner);
            save(beforeFocus, "toolbar-before-focus.png");
            assertTrue(editor.requestFocus());
            int[][] samples = {{editor.getWidth() / 2, 1}, {1, editor.getHeight() / 2}, {editor.getWidth() - 2, editor.getHeight() / 2}};
            for (int scroll : new int[] {0, 120}) {
                editor.scrollTo(scroll, 0);
                // Keep the old observer as evidence. Direct child.draw lacks drawChild's scroll
                // translation and moves even the background. Parent rendering is the native oracle.
                Bitmap direct = raster(editor);
                try { save(direct, "direct-editor-scroll" + scroll + ".png"); } finally { direct.recycle(); }
                Bitmap image = raster(owner);
                try {
                    save(image, "toolbar-focused-scroll" + scroll + ".png");
                    assertEquals(scroll, editor.getScrollX());
                    for (int[] p : samples) assertEquals("native parent focus outline follows viewport at scroll=" + scroll,
                        m.theme.getPalette().getPageControls().getAccent(), image.getPixel(field.left + p[0], field.top + p[1]));
                } finally { image.recycle(); }
            }
            assertTrue(m.sink.requestFocus()); assertFalse(editor.hasFocus());
            Bitmap blurred = raster(owner);
            try {
                save(blurred, "toolbar-blurred-after-scroll120.png");
                // Compare the same parent's native grain phase, not a separately drawn background.
                for (int[] p : samples) assertEquals("blur restores exact before-focus parent pixels",
                    beforeFocus.getPixel(field.left + p[0], field.top + p[1]), blurred.getPixel(field.left + p[0], field.top + p[1]));
            }
            finally { blurred.recycle(); beforeFocus.recycle(); }
        }
    }

    @Test public void nativeCompositionSelectionAndGoRetainPlainEditorWithUriKeyboardMetadata() throws Exception {
        try (Mounted m = new Mounted()) {
            NativeTextField editor = m.chrome.getAddressEditor();
            int type = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
            editor.setInputType(type); editor.setSingleLine(true); editor.setSelectAllOnFocus(true);
            editor.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            editor.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
            int[] submits = {0};
            editor.setOnEditorActionListener((v, action, event) -> { if (action == EditorInfo.IME_ACTION_GO) { submits[0]++; return true; } return false; });
            assertTrue(editor.requestFocus());
            EditorInfo info = new EditorInfo(); InputConnection connection = editor.onCreateInputConnection(info); assertNotNull(connection);
            try {
                assertEquals(type, editor.getInputType());
                assertEquals("URI variation must exist only in keyboard metadata", type | InputType.TYPE_TEXT_VARIATION_URI, info.inputType);
                assertEquals(EditorInfo.IME_ACTION_GO, info.imeOptions & EditorInfo.IME_MASK_ACTION);
                assertTrue((info.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0);
                assertTrue(connection.setComposingText("alpha beta", 1)); assertTrue(connection.setSelection(2, 7));
                Editable text = editor.getText();
                int start = BaseInputConnection.getComposingSpanStart(text), end = BaseInputConnection.getComposingSpanEnd(text); assertTrue(start >= 0);
                m.chrome.bindTheme(ReverbDesign.INSTANCE.theme(editor.getContext(), true));
                assertSame(text, editor.getText()); assertEquals("alpha beta", text.toString());
                assertEquals(2, editor.getSelectionStart()); assertEquals(7, editor.getSelectionEnd());
                assertEquals(start, BaseInputConnection.getComposingSpanStart(text)); assertEquals(end, BaseInputConnection.getComposingSpanEnd(text));
                assertEquals(View.TEXT_DIRECTION_FIRST_STRONG, editor.getTextDirection());
                assertEquals(0, submits[0]); assertTrue(connection.performEditorAction(EditorInfo.IME_ACTION_GO)); assertEquals(1, submits[0]);
                assertTrue(connection.finishComposingText()); assertEquals(-1, BaseInputConnection.getComposingSpanStart(text));
            } finally { connection.closeConnection(); }
        }
    }

    @Test public void integerApiKeepsCountThroughLegacyBindingAndThemeRebind() throws Exception {
        Context context = context(1f);
        ReverbBrowserChrome chrome = chrome(context, false), reference = chrome(context, true);
        try {
            layout(chrome, 320, 400); layout(reference, 320, 400);
            count(chrome, 1, "1 tab"); Bitmap one = raster(chrome.getTabsAction());
            count(chrome, 2, "2 tabs"); Bitmap two = raster(chrome.getTabsAction());
            try {
                assertFalse("numeric binding must change glyph pixels", one.sameAs(two));
                chrome.bindTabCount("two open tabs"); Bitmap legacy = raster(chrome.getTabsAction());
                try { assertTrue(two.sameAs(legacy)); } finally { legacy.recycle(); }
            } finally { one.recycle(); two.recycle(); }
            chrome.bindTheme(ReverbDesign.INSTANCE.theme(context, true)); count(reference, 2, "2 tabs");
            Bitmap rebound = raster(chrome.getTabsAction()), expected = raster(reference.getTabsAction());
            try { assertTrue("theme rebind retains numeric count", rebound.sameAs(expected)); }
            finally { rebound.recycle(); expected.recycle(); }
            assertEquals("two open tabs", ViewCompat.getStateDescription(chrome.getTabsAction().getAccessibilityAction()).toString());
            chrome.close(); count(chrome, 99, "99 tabs");
            assertEquals("two open tabs", ViewCompat.getStateDescription(chrome.getTabsAction().getAccessibilityAction()).toString());
        } finally { chrome.close(); reference.close(); }
    }

    @Test public void retainedCountBoundariesAndContentLifecycle() throws Exception {
        Context context = context(1f);
        ReverbBrowserChrome chrome = chrome(context, false);
        try {
            layout(chrome, 320, 400);
            chrome.getAddressEditor().setText("example.com");
            int index = 0;
            for (int value : new int[] {0, 1, 99, 100, 99}) {
                count(chrome, value, value + " tabs");
                assertEquals(value + " tabs", ViewCompat.getStateDescription(chrome.getTabsAction().getAccessibilityAction()).toString());
                assertIntegratedGlyph(chrome, value, false, true);
                Bitmap frame = raster(toolbar(chrome));
                try { save(frame, "retained-count-" + index++ + "-" + value + ".png"); } finally { frame.recycle(); }
            }
            Bitmap beforeSpeech = raster(chrome.getTabsAction());
            try {
                count(chrome, 99, "99 pestañas abiertas");
                assertEquals("99 pestañas abiertas", ViewCompat.getStateDescription(chrome.getTabsAction().getAccessibilityAction()).toString());
                chrome.bindTabCount("ninety-nine open tabs");
                assertEquals("ninety-nine open tabs", ViewCompat.getStateDescription(chrome.getTabsAction().getAccessibilityAction()).toString());
                Bitmap afterSpeech = raster(chrome.getTabsAction());
                try { assertTrue("equal-count and legacy localization never replace glyph", beforeSpeech.sameAs(afterSpeech)); }
                finally { afterSpeech.recycle(); }
            } finally { beforeSpeech.recycle(); }
            ReverbBrowserChromeAction renamed = new ReverbBrowserChromeAction("tabs-renamed", "Tabs renamed",
                new ReverbBrowserChromeIcon.Shared(ReverbIcon.Close), true);
            chrome.getTabsAction().bindContent(renamed);
            // Detached fixtures have no published accessibility node. The description child is
            // the public semantic label owner, rather than the surrounding clickable leaf.
            assertTrue("content binding updates its actual description child",
                hasDescription(chrome.getTabsAction(), "Tabs renamed"));
            assertIntegratedGlyph(chrome, 99, false, true);
            chrome.bindTheme(ReverbDesign.INSTANCE.theme(context, true));
            assertIntegratedGlyph(chrome, 99, true, true);
            chrome.getTabsAction().bindContent(new ReverbBrowserChromeAction("tabs-renamed", "Tabs renamed",
                new ReverbBrowserChromeIcon.Shared(ReverbIcon.Close), false));
            assertFalse(chrome.getTabsAction().getAccessibilityAction().isEnabled());
            assertIntegratedGlyph(chrome, 99, true, false);
            chrome.getTabsAction().bindContent(renamed);
            assertTrue(chrome.getTabsAction().getAccessibilityAction().isEnabled());
            assertIntegratedGlyph(chrome, 99, true, true);
            assertEquals("ninety-nine open tabs", ViewCompat.getStateDescription(chrome.getTabsAction().getAccessibilityAction()).toString());
            int[] delivered = {0};
            chrome.setOnChromeAction(id -> { delivered[0]++; return Unit.INSTANCE; });
            chrome.close();
            Bitmap closed = raster(chrome.getTabsAction());
            try {
                count(chrome, 0, "closed mutation must be ignored");
                chrome.bindTabCount("legacy closed mutation must be ignored");
                chrome.getTabsAction().bindContent(action("replacement"));
                chrome.bindTheme(ReverbDesign.INSTANCE.theme(context, false));
                Bitmap after = raster(chrome.getTabsAction());
                try { assertTrue("closed bindings cannot change retired glyph pixels", closed.sameAs(after)); }
                finally { after.recycle(); }
                assertEquals("ninety-nine open tabs", ViewCompat.getStateDescription(chrome.getTabsAction().getAccessibilityAction()).toString());
                assertFalse(chrome.getTabsAction().getAccessibilityAction().isEnabled());
                assertNull("close retires host callback", chrome.getOnChromeAction());
                // Android performClick reports listener presence even for a disabled view;
                // rejection means no callback delivery, not a false framework return value.
                chrome.getTabsAction().getAccessibilityAction().performClick();
                assertEquals("retired action cannot deliver to the former host", 0, delivered[0]);
            } finally { closed.recycle(); }
        } finally { chrome.close(); }
    }

    private static void assertIntegratedGlyph(ReverbBrowserChrome actual, int value, boolean dark, boolean enabled) {
        // Independent expected path: install the count drawable directly into a fresh48dp action.
        // This never calls the numeric Chrome API, so stale retained count cannot match by copying itself.
        ReverbBrowserChrome expected = chrome(actual.getContext(), dark);
        try {
            expected.getTabsAction().bindContent(new ReverbBrowserChromeAction("reference", "reference",
                new ReverbBrowserChromeIcon.Shared(ReverbIcon.Browser), enabled));
            layout(expected, 320, 400);
            float density = actual.getResources().getDisplayMetrics().density;
            int tint = expected.getTabsAction().getSampledIconTint();
            expected.getTabsAction().setGlyph(new ReverbBrowserTabCountDrawable(value, density, tint), tint);
            Bitmap observed = raster(actual.getTabsAction()), oracle = raster(expected.getTabsAction());
            try { assertTrue("real22dp count matches independent direct drawable: " + value + ",dark=" + dark + ",enabled=" + enabled, observed.sameAs(oracle)); }
            finally { observed.recycle(); oracle.recycle(); }
            assertEquals(48, actual.getTabsAction().getAccessibilityAction().getWidth());
            assertEquals(48, actual.getTabsAction().getAccessibilityAction().getHeight());
        } finally { expected.close(); }
    }
    private static boolean hasDescription(View view, String label) {
        if (label.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (hasDescription(group.getChildAt(i), label)) return true;
        }
        return false;
    }

    private static void count(ReverbBrowserChrome chrome, int value, String description) throws Exception {
        // Exact parent compiles too, so its missing visual behavior fails assertions rather than linkage.
        try { Method method = chrome.getClass().getMethod("bindTabCount", int.class, String.class); method.invoke(chrome, value, description); }
        catch (NoSuchMethodException parent) { chrome.bindTabCount(description); }
    }
    private static Context context(float font) {
        Configuration config = new Configuration(RuntimeEnvironment.getApplication().getResources().getConfiguration()); config.fontScale = font;
        return new ContextThemeWrapper(RuntimeEnvironment.getApplication().createConfigurationContext(config), android.R.style.Theme_Material_Light_NoActionBar);
    }
    private static ReverbBrowserChrome chrome(Context context, boolean dark) {
        return new ReverbBrowserChrome(context, ReverbDesign.INSTANCE.theme(context, dark), "Search or type URL", action("site"), action("tabs"), action("menu"));
    }
    private static ReverbBrowserChromeAction action(String id) { return new ReverbBrowserChromeAction(id, id, new ReverbBrowserChromeIcon.Shared(ReverbIcon.Browser), true); }
    private static ReverbBrowserChromeActionView[] actions(ReverbBrowserChrome c) { return new ReverbBrowserChromeActionView[] {c.getSiteAction(), c.getTabsAction(), c.getMenuAction()}; }
    private static ViewGroup toolbar(ReverbBrowserChrome c) { return (ViewGroup) ((ViewGroup) c.getChromeToolbar()).getChildAt(0); }
    private static void layout(View v, int w, int h) { v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)); v.layout(0,0,w,h); }
    private static Rect relative(ViewGroup root, View child) { Rect r = new Rect(); child.getDrawingRect(r); root.offsetDescendantRectToMyCoords(child,r); return r; }
    private static Bitmap raster(View v) { Bitmap b = Bitmap.createBitmap(v.getWidth(),v.getHeight(),Bitmap.Config.ARGB_8888); v.draw(new Canvas(b)); return b; }
    private static Bitmap background(View v) { Bitmap b = Bitmap.createBitmap(v.getWidth(),v.getHeight(),Bitmap.Config.ARGB_8888); v.getBackground().draw(new Canvas(b)); return b; }
    private static void save(Bitmap b, String name) throws Exception { try (FileOutputStream out = new FileOutputStream(new File(System.getProperty("address.test.output"), name))) { b.compress(Bitmap.CompressFormat.PNG,100,out); } }
    private static class Mounted implements AutoCloseable {
        final ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible();
        final Activity activity = controller.get();
        final ReverbTheme theme = ReverbDesign.INSTANCE.theme(activity, false);
        final ReverbBrowserChrome chrome = chrome(activity, false);
        final View sink = new View(activity);
        Mounted() { sink.setFocusableInTouchMode(true); chrome.attachRenderer(sink); activity.setContentView(chrome); layout(chrome,320,400); assertTrue(sink.requestFocus()); }
        public void close() { chrome.close(); controller.pause().stop().destroy(); }
    }
}
