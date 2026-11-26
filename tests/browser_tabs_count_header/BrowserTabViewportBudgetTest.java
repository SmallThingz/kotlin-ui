import static org.junit.Assert.*;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import kotlin.Unit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import smallthingz.ui.components.NativeStandardIconButtonView;
import smallthingz.ui.reverb.*;

/** Exact short-host component budget. Synthetic preview is explicitly NOT real-page evidence. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class BrowserTabViewportBudgetTest {
    @Test public void exact1000x782At421dpiKeepsHeaderCloseAndPreviewBudget() throws Exception {
        for (float font : new float[] {.9f, 1f, 2f}) for (boolean dark : new boolean[] {false, true}) {
            int direction = font == 2f ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR;
            Configuration config = new Configuration(RuntimeEnvironment.getApplication().getResources().getConfiguration());
            config.densityDpi = 421;
            config.fontScale = font;
            config.screenWidthDp = 380;
            config.screenHeightDp = 297;
            Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication().createConfigurationContext(config), android.R.style.Theme_Material_Light_NoActionBar);
            assertEquals(421, context.getResources().getDisplayMetrics().densityDpi);
            assertEquals(font, context.getResources().getConfiguration().fontScale, 0f);
            float density = context.getResources().getDisplayMetrics().density;
            ReverbBrowserPanelTabs<String> panel = new ReverbBrowserPanelTabs<>(context, ReverbDesign.INSTANCE.theme(context, dark), "Tabs", "Back", "New tab");
            panel.setLayoutDirection(direction);
            panel.setOnTabAction((key, action) -> Unit.INSTANCE);
            panel.setOnTabMove((key, target) -> Unit.INSTANCE);
            Bitmap preview = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
            Canvas fixture = new Canvas(preview);
            fixture.drawColor(0xff214937);
            Paint text = new Paint(Paint.ANTI_ALIAS_FLAG); text.setColor(0xffeeeeee); text.setTextSize(18f);
            fixture.drawText("SYNTHETIC PREVIEW", 10f, 32f, text);
            fixture.drawLine(0f, 0f, 256f, 256f, text);
            String title = "A long recognizable page title with enough text to ellipsize";
            panel.bindTabs("2 tabs", Arrays.asList(
                new ReverbBrowserPanelTab<>("a", title, "https://example.com", preview, null, true, "Current tab", "Close tab 1: " + title, true),
                new ReverbBrowserPanelTab<>("b", "Second page", null, preview, null, false, "Current tab", "Close tab 2: Second page", true)));
            try {
                panel.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(782, View.MeasureSpec.EXACTLY));
                panel.layout(0, 0, 1000, 782);
                ReverbBrowserPanelSettingRow row = descendants(panel, ReverbBrowserPanelSettingRow.class).get(0);
                NativeStandardIconButtonView close = descendants(panel, NativeStandardIconButtonView.class).get(0);
                Rect rowBounds = relative(panel, row), closeBounds = relative(panel, close);
                Rect image = panel.tabPreviewBounds("a"); assertNotNull(image);
                int target = Math.round(48f * density);
                assertEquals(target, close.getVisual().getWidth()); assertEquals(target, close.getVisual().getHeight());
                assertEquals(target, close.getWidth()); assertEquals(target, close.getHeight());
                assertEquals(1, row.getTitleView().getMaxLines());
                assertTrue(row.getContentDescription().toString().contains(title));
                assertFalse(Rect.intersects(rowBounds, closeBounds));
                assertTrue(closeBounds.bottom <= image.top);
                assertTrue("this exact viewport retains a usable preview", image.height() > 0);
                assertTrue("preview fits above dock", image.bottom <= 782 - panel.getFooterHeight());
                assertTrue("semantic end close slot", direction == View.LAYOUT_DIRECTION_RTL ? closeBounds.right <= rowBounds.left : rowBounds.right <= closeBounds.left);
                String name = "tabs-1000x782-dpi421-font" + font + "-" + (direction == View.LAYOUT_DIRECTION_RTL ? "rtl" : "ltr") + "-" + (dark ? "dark" : "light");
                Bitmap rendered = Bitmap.createBitmap(1000, 782, Bitmap.Config.ARGB_8888);
                panel.draw(new Canvas(rendered));
                String output = System.getProperty("tabs.test.output");
                assertNotNull(output);
                try (FileOutputStream stream = new FileOutputStream(new File(output, name + ".png"))) { rendered.compress(Bitmap.CompressFormat.PNG, 100, stream); }
                System.out.println(name + " density=" + density + " targetPx=" + target + " titlePx=" + row.getTitleView().getTextSize()
                    + " headerRow=" + rowBounds + " close=" + closeBounds + " preview=" + image + " dockHeight=" + panel.getFooterHeight());
            } finally { panel.close(); }
        }
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
}
