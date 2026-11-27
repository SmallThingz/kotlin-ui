import static org.junit.Assert.*;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Paint;
import androidx.core.view.ViewCompat;
import smallthingz.ui.components.NativeGroupLayout;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import kotlin.Unit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import smallthingz.ui.components.CachedPathIconDrawable;
import smallthingz.ui.components.NativeStandardIconButtonView;
import smallthingz.ui.reverb.*;

/** Focused offline rendering/geometry tests. No app launch, device, network or broad suite. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class BrowserTabCountHeaderTest {
    @Test public void eachCountHasDistinctInkAndOneUnchangingOutline() {
        HashSet<String> interiors = new HashSet<>();
        int[] reference = pixels(render(new ReverbBrowserTabCountDrawable(0, 3f, Color.WHITE), 72, 72));
        for (int count = 0; count <= 99; count++) {
            ReverbBrowserTabCountDrawable icon = new ReverbBrowserTabCountDrawable(count, 3f, Color.WHITE);
            assertTrue("uses shared mask/tint contract", icon instanceof CachedPathIconDrawable);
            assertEquals(count, icon.getCount());
            int[] actual = pixels(render(icon, 72, 72));
            ArrayList<Integer> ink = new ArrayList<>();
            for (int y = 0; y < 72; y++) for (int x = 0; x < 72; x++) {
                if (x < 14 || x >= 58 || y < 14 || y >= 58)
                    assertEquals("count must not add a second box", reference[y * 72 + x], actual[y * 72 + x]);
                else ink.add(actual[y * 72 + x]);
            }
            assertTrue("distinct visible count " + count, interiors.add(ink.toString()));
        }
        // A cross section away from the centered label has exactly the two sides of ONE box.
        int runs = 0; boolean inside = false;
        for (int x = 0; x < 72; x++) {
            boolean next = Color.alpha(reference[15 * 72 + x]) > 127;
            if (next && !inside) runs++;
            inside = next;
        }
        assertEquals("one outline, not stacked tabs", 2, runs);
    }

    @Test public void negativesClampAndOverflowIsStableDistinctAndNotMirrored() throws Exception {
        Bitmap zero = render(new ReverbBrowserTabCountDrawable(0, 3f, Color.WHITE), 72, 72);
        ReverbBrowserTabCountDrawable negative = new ReverbBrowserTabCountDrawable(Integer.MIN_VALUE, 3f, Color.WHITE);
        assertEquals(0, negative.getCount());
        assertArrayEquals(pixels(zero), pixels(render(negative, 72, 72)));
        ReverbBrowserTabCountDrawable overflow = new ReverbBrowserTabCountDrawable(100, 3f, Color.WHITE);
        int[] visible = pixels(render(overflow, 72, 72));
        assertArrayEquals(visible, pixels(render(new ReverbBrowserTabCountDrawable(Integer.MAX_VALUE, 3f, Color.WHITE), 72, 72)));
        assertFalse(Arrays.equals(visible, pixels(render(new ReverbBrowserTabCountDrawable(99, 3f, Color.WHITE), 72, 72))));
        overflow.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        assertArrayEquals("numbers and + remain legible in RTL", visible, pixels(render(overflow, 72, 72)));
        Bitmap strip = Bitmap.createBitmap(6 * 84, 84, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(strip);
        int[] counts = {0, 1, 9, 10, 99, 100};
        for (int i = 0; i < counts.length; i++) {
            ReverbBrowserTabCountDrawable icon = new ReverbBrowserTabCountDrawable(counts[i], 3f, 0xff45d79b);
            icon.setBounds(i * 84 + 6, 6, i * 84 + 78, 78); icon.draw(canvas);
        }
        save(strip, "counter-0-1-9-10-99-overflow.png");
    }

    @Test public void tintAlphaStateAndNonSquareBoundsUseSharedGlyphRendering() {
        ReverbBrowserTabCountDrawable icon = new ReverbBrowserTabCountDrawable(12, 2f, Color.RED);
        assertEquals(48, icon.getIntrinsicWidth());
        int[] red = pixels(render(icon, 96, 48));
        icon.setTintList(new ColorStateList(new int[][] {{android.R.attr.state_selected}, {}}, new int[] {Color.GREEN, Color.BLUE}));
        icon.setState(new int[] {android.R.attr.state_selected});
        int[] green = pixels(render(icon, 96, 48));
        for (int i = 0; i < red.length; i++) {
            assertEquals(Color.alpha(red[i]), Color.alpha(green[i]));
            if (Color.alpha(green[i]) > 200) assertEquals(255, Color.green(green[i]));
        }
        icon.setAlpha(0);
        for (int pixel : pixels(render(icon, 96, 48))) assertEquals(0, Color.alpha(pixel));
        icon.setAlpha(255); icon.setVisible(false, false); icon.setVisible(true, false);
        assertArrayEquals("visibility cache eviction preserves geometry", green, pixels(render(icon, 96, 48)));
        for (int y = 0; y < 48; y++) for (int x = 0; x < 24; x++)
            assertEquals("centered square glyph in wide host", 0, Color.alpha(green[y * 96 + x]));
    }

    @Test public void compactHeaderRetainsFullCloseOwnerAndPreviewBoundsAcrossRtlFontAndWidth() throws Exception {
        for (float font : new float[] {1f, 2f}) for (int direction : new int[] {View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
            Configuration config = new Configuration(RuntimeEnvironment.getApplication().getResources().getConfiguration());
            config.fontScale = font;
            Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication().createConfigurationContext(config), android.R.style.Theme_Material_Light_NoActionBar);
            ReverbTheme theme = ReverbDesign.INSTANCE.theme(context, true);
            ReverbBrowserPanelTabs<String> panel = new ReverbBrowserPanelTabs<>(context, theme, "Tabs", "Back", "New tab");
            panel.setLayoutDirection(direction);
            panel.setOnTabAction((key, action) -> Unit.INSTANCE);
            panel.setOnTabMove((key, target) -> Unit.INSTANCE);
            Bitmap preview = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
            preview.eraseColor(0xff214937);
            String title = "A long recognizable page title with enough text to ellipsize";
            panel.bindTabs("2 tabs", Arrays.asList(
                new ReverbBrowserPanelTab<>("a", title, "https://example.com", preview, null, true, "Current tab", "Close tab 1: " + title, true),
                new ReverbBrowserPanelTab<>("b", "Second", null, null, null, false, "Current tab", "Close tab 2: Second", true)));
            try {
                for (int width : new int[] {295, 296, 320, 719, 720}) {
                    layout(panel, width, 360);
                    ReverbBrowserPanelSettingRow row = descendants(panel, ReverbBrowserPanelSettingRow.class).get(0);
                    NativeStandardIconButtonView close = descendants(panel, NativeStandardIconButtonView.class).get(0);
                    float expected = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 14f, context.getResources().getDisplayMetrics());
                    assertEquals("compact title uses 14sp, including larger accessibility fonts", expected, row.getTitleView().getTextSize(), .05f);
                    assertEquals(1, row.getTitleView().getMaxLines());
                    assertTrue(row.getContentDescription().toString().contains(title));
                    assertTrue(row.isLongClickable());
                    assertEquals(48, close.getWidth()); assertEquals(48, close.getHeight());
                    assertEquals(48, close.getVisual().getWidth()); assertEquals(48, close.getVisual().getHeight());
                    assertEquals("only glyph drawing is compact", 20f, close.getVisual().getGlyph().getWidth() * close.getVisual().getGlyph().getScaleX(), .05f);
                    assertNotNull("shared noisy foreground remains on actual action", close.getVisual().getBackground());
                    Rect rowBounds = relative(panel, row), closeBounds = relative(panel, close);
                    assertFalse("separate select and close slots", Rect.intersects(rowBounds, closeBounds));
                    Rect image = panel.tabPreviewBounds("a"); assertNotNull(image);
                    assertTrue(closeBounds.bottom <= image.top);
                    assertTrue(image.bottom <= panel.getHeight() - panel.getFooterHeight());
                    assertTrue("RTL moves close to semantic end", direction == View.LAYOUT_DIRECTION_RTL ? closeBounds.right <= rowBounds.left : rowBounds.right <= closeBounds.left);
                }
                panel.bindTheme(ReverbDesign.INSTANCE.theme(context, false));
                layout(panel, 320, 360);
                ReverbBrowserPanelSettingRow row = descendants(panel, ReverbBrowserPanelSettingRow.class).get(0);
                assertEquals(2, row.getPaddingTop());
                assertEquals(2, row.getPaddingBottom());
                if (font == 1f && direction == View.LAYOUT_DIRECTION_LTR) {
                    Bitmap image = Bitmap.createBitmap(320, 360, Bitmap.Config.ARGB_8888);
                    panel.draw(new Canvas(image)); save(image, "compact-header-320-light.png");
                }
            } finally { panel.close(); }
        }
    }

    @Test public void retainedTabCloseSlotTracksThemeDensityWithoutShrinkingOrOverlappingSelect() {
        Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), android.R.style.Theme_Material_Light_NoActionBar);
        for (int direction : new int[] {View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
            ReverbBrowserPanelTabs<String> panel = new ReverbBrowserPanelTabs<>(context, ReverbDesign.INSTANCE.theme(context, true), "Tabs", "Back", "New tab");
            panel.setLayoutDirection(direction);
            panel.setOnTabAction((key, action) -> Unit.INSTANCE);
            panel.bindTabs("1 tab", java.util.Collections.singletonList(
                new ReverbBrowserPanelTab<>("a", "Retained title", null, null, null, true, "Current", "Close retained", true)));
            try {
                NativeStandardIconButtonView retainedClose = descendants(panel, NativeStandardIconButtonView.class).get(0);
                for (int density : new int[] {160, 320, 120, 421, 160}) {
                    Configuration config = new Configuration(context.getResources().getConfiguration());
                    config.densityDpi = density;
                    ReverbTheme next = ReverbDesign.INSTANCE.theme(context.createConfigurationContext(config), true);
                    panel.bindTheme(next); layout(panel, 900, 900);
                    NativeStandardIconButtonView close = descendants(panel, NativeStandardIconButtonView.class).get(0);
                    ReverbBrowserPanelSettingRow row = descendants(panel, ReverbBrowserPanelSettingRow.class).get(0);
                    assertSame("Keyed close is retained on theme rebind", retainedClose, close);
                    int target = Math.round(48f*next.getDensity().getDensity());
                    assertEquals("Actual close width refreshes, not only icon density", target, close.getWidth());
                    assertEquals(target, close.getHeight()); assertEquals(target, close.getVisual().getWidth());
                    assertEquals(target, close.getVisual().getHeight());
                    Rect closeBounds = relative(panel, close), rowBounds = relative(panel, row);
                    assertFalse("Close target never overlaps SELECT", Rect.intersects(closeBounds, rowBounds));
                    assertEquals("Title reservation follows actual close slot", direction == View.LAYOUT_DIRECTION_RTL ? closeBounds.right : rowBounds.right,
                        direction == View.LAYOUT_DIRECTION_RTL ? rowBounds.left : closeBounds.left);
                    Rect preview = panel.tabPreviewBounds("a"); assertNotNull(preview);
                    assertTrue("Target remains above preview", closeBounds.bottom <= preview.top);
                }
            } finally { panel.close(); }
        }
    }

    @Test public void selectedTabPaintsUsualGroupBorderAfterSelectionAndThemeRebind() {
        Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), android.R.style.Theme_Material_Light_NoActionBar);
        ReverbTheme first = ReverbDesign.INSTANCE.theme(context, true);
        ReverbBrowserPanelTabs<String> panel = new ReverbBrowserPanelTabs<>(context, first, "Tabs", "Back", "New tab");
        panel.setOnTabAction((key, action) -> Unit.INSTANCE);
        try {
            for (boolean dark : new boolean[] {true, false}) {
                ReverbTheme theme = ReverbDesign.INSTANCE.theme(context, dark);
                panel.bindTheme(theme);
                for (boolean selected : new boolean[] {false, true, false, true}) {
                    panel.bindTabs("1 tab", java.util.Collections.singletonList(
                        new ReverbBrowserPanelTab<>("a", "Same tab", null, null, null, selected, "Current tab", "Close tab", true)));
                    layout(panel, 320, 360);
                    assertUsualBorder(panel, theme);
                    ReverbBrowserPanelSettingRow row = descendants(panel, ReverbBrowserPanelSettingRow.class).get(0);
                    assertEquals(selected ? "Current tab" : null, ViewCompat.getStateDescription(row));
                }
                // Selection remains true: exercise bindTheme's paint route independently of bindTabs.
                ReverbTheme rebound = ReverbDesign.INSTANCE.theme(context, !dark);
                panel.bindTheme(rebound); layout(panel, 320, 360); assertUsualBorder(panel, rebound);
            }
        } finally { panel.close(); }
    }

    private static void assertUsualBorder(ReverbBrowserPanelTabs<String> panel, ReverbTheme theme) {
        ReverbBrowserPanelSettingRow row = descendants(panel, ReverbBrowserPanelSettingRow.class).get(0);
        android.view.ViewParent parent = row.getParent();
        while (!(parent instanceof NativeGroupLayout)) { assertNotNull(parent); parent = parent.getParent(); }
        NativeGroupLayout surface = (NativeGroupLayout) parent;
        Bitmap bitmap = Bitmap.createBitmap(surface.getWidth(), surface.getHeight(), Bitmap.Config.ARGB_8888);
        try {
            BorderCanvas canvas = new BorderCanvas(bitmap); surface.draw(canvas);
            assertNotNull("Observe actual outer border draw, not a duplicated color helper", canvas.lastStroke);
            int expected = ReverbDesign.INSTANCE.getAppearance().getGroupSurfaces().getSettingsChoices().borderColor(theme.getPalette());
            assertEquals("Current and inactive tabs use ordinary dim group outline", expected, canvas.lastStroke.intValue());
        } finally { bitmap.recycle(); }
    }

    private static final class BorderCanvas extends Canvas {
        Integer lastStroke;
        BorderCanvas(Bitmap bitmap) { super(bitmap); }
        @Override public void drawRoundRect(RectF rect, float rx, float ry, Paint paint) {
            if (paint.getStyle() == Paint.Style.STROKE) lastStroke = paint.getColor();
            super.drawRoundRect(rect, rx, ry, paint);
        }
    }

    private static Bitmap render(ReverbBrowserTabCountDrawable icon, int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        icon.setBounds(0, 0, width, height); icon.draw(new Canvas(bitmap)); return bitmap;
    }
    private static int[] pixels(Bitmap bitmap) {
        int[] result = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(result, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight()); return result;
    }
    private static void layout(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }
    private static Rect relative(ViewGroup root, View view) {
        Rect result = new Rect(0, 0, view.getWidth(), view.getHeight()); root.offsetDescendantRectToMyCoords(view, result); return result;
    }
    private static <T> List<T> descendants(View root, Class<T> type) {
        List<T> result = new ArrayList<>();
        if (type.isInstance(root)) result.add(type.cast(root));
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) result.addAll(descendants(((ViewGroup) root).getChildAt(i), type));
        return result;
    }
    private static void save(Bitmap bitmap, String name) throws Exception {
        String output = System.getProperty("tabs.test.output");
        if (output == null) return;
        try (FileOutputStream stream = new FileOutputStream(new File(output, name))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
    }
}
