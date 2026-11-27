import static org.junit.Assert.*;
import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import kotlin.Unit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.*;
import org.junit.internal.TextListener;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import smallthingz.ui.components.NativeTextField;
import smallthingz.ui.reverb.*;

/** Narrow Browser05 child: no tab-switch/submit replay; no physical IME or network claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=36, qualifiers="mdpi", application=Application.class,
    instrumentedPackages={"smallthingz.ui.animation.view"})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class BrowserPillRegressionTest {
    public static void main(String[] args) {
        if(args.length!=1 || !Arrays.asList("actualControlPillRetainsCompositionAcrossDirectionAndWidth",
            "attachedPillTargetsAndTouchVersusKeyboardFocus").contains(args[0])) throw new IllegalArgumentException("One exact case required");
        JUnitCore core=new JUnitCore();core.addListener(new TextListener(System.out));
        Result r=core.run(Request.method(BrowserPillRegressionTest.class,args[0]));System.exit(r.wasSuccessful()?0:1);
    }
    // Root still binds the complete compiled-input freeze and BOTH serial/resource guards.
    // Do not call historical VerifyHome15Freeze: its literal shared201 cannot certify a candidate jar.
    static void admitted() throws Exception {
        assertEquals("true",System.getProperty("control.actualActivity.rootAdmitted"));
        org.json.JSONArray bindings=new org.json.JSONArray();
        // Observed Robolectric instrumentation leaves CodeSource.location null. Verify the
        // exact class resource URL/entry bytes under root's sealed classpath instead.
        for(Class<?> c:new Class<?>[]{ReverbBrowserChrome.class,dev.aicore.app.phone.browser.PhoneBrowserView.class}) {
            String prefix=c==ReverbBrowserChrome.class?"browser.regression.shared":"browser.regression.app";
            String expectedSha=System.getProperty(prefix+"Sha256"), expectedJar=System.getProperty(prefix+"Jar");
            String expectedClass=System.getProperty(prefix+"ClassSha256");
            assertNotNull(expectedSha);assertNotNull(expectedJar);assertNotNull(expectedClass);
            String entry=c.getName().replace('.','/')+".class";
            java.net.URL url=c.getResource("/"+entry);assertNotNull("Exact class resource required",url);
            assertEquals("Sealed JAR resource only","jar",url.getProtocol());
            java.net.JarURLConnection connection=(java.net.JarURLConnection)url.openConnection();connection.setUseCaches(false);
            assertEquals(entry,connection.getEntryName());assertEquals("file",connection.getJarFileURL().getProtocol());
            Path actual=Path.of(connection.getJarFileURL().toURI()).toRealPath(), expected=Path.of(expectedJar).toRealPath();
            assertEquals("Class resource resolves exact root-sealed artifact",expected,actual);
            try(java.io.InputStream in=Files.newInputStream(actual)){assertEquals(expectedSha,digest(in));}
            String resourceSha;
            try(java.io.InputStream in=connection.getInputStream()){resourceSha=digest(in);}
            assertEquals("Resource bytes match root-frozen class entry",expectedClass,resourceSha);
            try(java.util.zip.ZipFile jar=new java.util.zip.ZipFile(expected.toFile())) {
                java.util.zip.ZipEntry item=jar.getEntry(entry);assertNotNull(item);
                try(java.io.InputStream in=jar.getInputStream(item)){assertEquals(expectedClass,digest(in));}
            }
            bindings.put(new JSONObject().put("class",c.getName()).put("resourceUrl",url.toExternalForm())
                .put("jar",actual.toString()).put("jarSha256",expectedSha).put("entrySha256",resourceSha));
        }
        Files.writeString(Path.of(System.getProperty("address.test.output"),"class-resource-binding.json"),
            new JSONObject().put("status","PASS").put("binding","sealed class resources; instrumented CodeSource location unavailable")
                .put("classes",bindings).toString(2)+"\n",StandardOpenOption.CREATE_NEW);
    }
    static String digest(java.io.InputStream in) throws Exception {
        MessageDigest md=MessageDigest.getInstance("SHA-256");byte[] b=new byte[65536];int n;
        while((n=in.read(b))!=-1)md.update(b,0,n);
        return HexFormat.of().formatHex(md.digest());
    }
    @Test public void actualControlPillRetainsCompositionAcrossDirectionAndWidth() throws Exception {
        admitted(); new Instrumentation().setInTouchMode(true);
        try(PhoneBrowserSnapshotTest.Fixture f=new PhoneBrowserSnapshotTest.Fixture()) {
            assertTrue(f.browser.goBack());settle();
            NativeTextField editor=f.browser.getAddressEditor();
            assertTrue(editor.requestFocus());settle();
            int originalType=editor.getInputType(), originalOptions=editor.getImeOptions();
            assertEquals(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,originalType);
            EditorInfo info=new EditorInfo();InputConnection input=editor.onCreateInputConnection(info);assertNotNull(input);
            try {
                assertEquals(originalType|InputType.TYPE_TEXT_VARIATION_URI,info.inputType);
                assertEquals(EditorInfo.IME_ACTION_GO,info.imeOptions&EditorInfo.IME_MASK_ACTION);
                assertTrue((info.imeOptions&EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)!=0);
                assertTrue(input.setSelection(0,editor.length()));assertTrue(input.setComposingText("draft segment",1));
                assertTrue(input.setSelection(2,7));
                Editable editable=editor.getText();int a=BaseInputConnection.getComposingSpanStart(editable),b=BaseInputConnection.getComposingSpanEnd(editable);
                assertTrue(a>=0 && b>a);
                for(int direction:new int[]{View.LAYOUT_DIRECTION_LTR,View.LAYOUT_DIRECTION_RTL}) {
                    f.browser.setLayoutDirection(direction);
                    for(int width:new int[]{320,380}) {
                        size(f.browser,width,480);settle();size(f.browser,width,480);
                        save(f.browser.getChromeToolbar(),"control-"+direction+"-"+width);
                        geometry(f.browser);
                        assertSame(editor,f.browser.getAddressEditor());assertSame(editable,editor.getText());
                        assertEquals("draft segment",editable.toString());assertTrue(editor.hasFocus());
                        assertEquals(2,editor.getSelectionStart());assertEquals(7,editor.getSelectionEnd());
                        assertEquals(a,BaseInputConnection.getComposingSpanStart(editable));assertEquals(b,BaseInputConnection.getComposingSpanEnd(editable));
                        assertEquals(originalType,editor.getInputType());assertEquals(originalOptions,editor.getImeOptions());
                    }
                }
                assertTrue(input.finishComposingText());assertEquals(-1,BaseInputConnection.getComposingSpanStart(editable));
            } finally {input.closeConnection();}
        }
        receipt("control-composition",true);
    }
    @Test public void attachedPillTargetsAndTouchVersusKeyboardFocus() throws Exception {
        admitted(); new Instrumentation().setInTouchMode(true);
        ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup().visible();
        Activity activity=controller.get();controller.windowFocusChanged(true);
        try {
            for(int direction:new int[]{View.LAYOUT_DIRECTION_LTR,View.LAYOUT_DIRECTION_RTL}) {
                Configuration config=new Configuration(activity.getResources().getConfiguration());
                config.fontScale=direction==View.LAYOUT_DIRECTION_RTL?2f:1f;
                Context context=activity.createConfigurationContext(config);
                boolean dark=direction==View.LAYOUT_DIRECTION_RTL;
                ReverbTheme theme=ReverbDesign.INSTANCE.theme(context,dark);
                ReverbBrowserChrome c=new ReverbBrowserChrome(context,theme,"Search or type URL",action("site"),action("tabs"),action("menu"));
                FrameLayout root=new FrameLayout(activity);root.addView(c,new FrameLayout.LayoutParams(-1,-1));
                c.setLayoutDirection(direction);c.setChromeInputAllowed(()->true);
                List<String> events=new ArrayList<>();c.setOnChromeAction(id->{events.add(id);return Unit.INSTANCE;});
                activity.setContentView(root);size(root,320,480);settle();size(root,320,480);
                try {
                    assertTrue(c.isAttachedToWindow()&&c.isShown()&&c.hasWindowFocus());
                    frameworkTouchEditor(activity.getWindow().getDecorView(),c.getAddressEditor());
                    save(c.getChromeToolbar(),"shared-idle-"+direction);geometry(c);opticalCenters(c);
                    ReverbBrowserChromeActionView[] actions={c.getSiteAction(),c.getTabsAction(),c.getMenuAction()};
                    String[] ids={"site","tabs","menu"};
                    new Instrumentation().setInTouchMode(true);
                    for(int i=0;i<actions.length;i++) for(float fraction:new float[]{.04f,.5f,.96f}) {
                        Rect r=relative(root,actions[i].getAccessibilityAction());events.clear();
                        assertTrue(tap(root,r.left+r.width()*fraction,r.exactCenterY()));
                        assertEquals(Collections.singletonList(ids[i]),events);
                    }
                    NativeTextField editor=c.getAddressEditor();editor.setText("example.test/long-address-segment-long-address");
                    editor.setCursorVisible(false);assertTrue(editor.requestFocus());settle();
                    ViewGroup toolbar=(ViewGroup)c.getChromeToolbar();Rect e=relative(toolbar,editor);
                    assertTrue(editor.isInTouchMode());
                    Bitmap touch=raster(toolbar);
                    try {save(touch,"touch-focus-"+direction);assertNotEquals("Touch editing stays borderless",theme.getPalette().getPageControls().getAccent(),touch.getPixel(e.centerX(),e.top+1));}
                    finally {touch.recycle();}
                    frameworkKeyboardRight(activity.getWindow().getDecorView());assertTrue(editor.requestFocus());settle();
                    assertFalse(editor.isInTouchMode());assertTrue(editor.hasFocus());
                    for(int scroll:new int[]{0,40}) {
                        editor.scrollTo(scroll,0);Bitmap key=raster(toolbar);
                        try {save(key,"keyboard-focus-"+direction+"-"+scroll);assertEquals("Keyboard cue follows viewport",theme.getPalette().getPageControls().getAccent(),key.getPixel(e.centerX(),e.top+1));}
                        finally {key.recycle();}
                    }
                } finally {c.close();root.removeAllViews();}
            }
        } finally {controller.pause().stop().destroy();}
        receipt("shared-targets-focus",false);
    }
    // Retained HomeKeyboardFocusProbe route: SDK-hidden PUBLIC framework entry only.
    // No product reflection, AttachInfo writes, forced focus flags or direct touch-mode override.
    static Object frameworkRoot(View decor) {
        assertTrue(decor.isAttachedToWindow());assertTrue(decor.hasWindowFocus());
        Object root=org.robolectric.util.ReflectionHelpers.callInstanceMethod(decor,"getViewRootImpl");
        assertNotNull(root);assertEquals("android.view.ViewRootImpl",root.getClass().getName());return root;
    }
    static void deliverFramework(Object root,android.view.InputEvent event) {
        org.robolectric.util.ReflectionHelpers.callInstanceMethod(root,"dispatchInputEvent",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(android.view.InputEvent.class,event));
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        // Framework owns and recycles delivered events.
    }
    static void frameworkKeyboardRight(View decor) {
        Object root=frameworkRoot(decor);long down=SystemClock.uptimeMillis();
        for(int phase:new int[]{android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.ACTION_UP}) {
            deliverFramework(root,new android.view.KeyEvent(down,SystemClock.uptimeMillis(),phase,
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT,0,0,android.view.KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,0,InputDevice.SOURCE_KEYBOARD));
        }
        settle();
    }
    static void frameworkTouchEditor(View decor,NativeTextField editor) {
        // Every configuration starts through real pointer input, including RTL after the prior
        // keyboard pass. Changing the global default alone does not update an attached window.
        Object root=frameworkRoot(decor);int[] at=new int[2];editor.getLocationInWindow(at);
        float x=at[0]+editor.getCompoundPaddingLeft()+(editor.getWidth()-editor.getCompoundPaddingLeft()-editor.getCompoundPaddingRight())/2f;
        float y=at[1]+editor.getHeight()/2f;long down=SystemClock.uptimeMillis();
        for(int phase:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP}) {
            MotionEvent.PointerProperties p=new MotionEvent.PointerProperties();p.id=0;p.toolType=MotionEvent.TOOL_TYPE_FINGER;
            MotionEvent.PointerCoords c=new MotionEvent.PointerCoords();c.x=x;c.y=y;c.pressure=1;c.size=1;
            deliverFramework(root,MotionEvent.obtain(down,SystemClock.uptimeMillis(),phase,1,
                new MotionEvent.PointerProperties[]{p},new MotionEvent.PointerCoords[]{c},0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0));
            org.robolectric.shadows.ShadowLooper.getShadowMainLooper().idleFor(java.time.Duration.ofMillis(16));
        }
        settle();assertTrue("Actual pointer delivery enters attached-window touch mode",editor.isInTouchMode());
    }
    static void geometry(ReverbBrowserChrome c) {
        NativeTextField e=c.getAddressEditor();assertTrue("Editor parent must be the shared pill FrameLayout",e.getParent() instanceof FrameLayout);
        ViewGroup pill=(ViewGroup)e.getParent();
        assertSame("Site action is inside the same filled pill",pill,c.getSiteAction().getParent());
        assertNotSame(pill,c.getTabsAction().getParent());assertNotSame(pill,c.getMenuAction().getParent());
        float density=e.getResources().getDisplayMetrics().density;int target=Math.round(48*density);
        assertEquals(target,e.getPaddingStart());assertEquals(Math.round(12*density),e.getPaddingEnd());
        Rect er=relative(pill,e),site=relative(pill,c.getSiteAction().getAccessibilityAction());
        assertTrue("Site owner contained by pill",new Rect(0,0,pill.getWidth(),pill.getHeight()).contains(site));
        Rect text=new Rect(er.left+e.getCompoundPaddingLeft(),er.top,er.right-e.getCompoundPaddingRight(),er.bottom);
        assertTrue("Editable interior",text.width()>=target);assertFalse("Text cannot overlap site target",Rect.intersects(text,site));
        ViewGroup toolbar=(ViewGroup)c.getChromeToolbar();Rect pr=relative(toolbar,pill);
        Rect tabs=relative(toolbar,c.getTabsAction().getAccessibilityAction()),menu=relative(toolbar,c.getMenuAction().getAccessibilityAction());
        for(Rect r:new Rect[]{site,tabs,menu}) {assertTrue(r.width()>=target);assertTrue(r.height()>=target);}
        assertFalse(Rect.intersects(pr,tabs));assertFalse(Rect.intersects(pr,menu));assertFalse(Rect.intersects(tabs,menu));
        assertEquals("Touch centers remain48dp apart despite optical glyph shifts",target,Math.abs(tabs.centerX()-menu.centerX()));
    }
    // Identical public fixture glyph content isolates optical displacement from icon shape.
    static void opticalCenters(ReverbBrowserChrome c) {
        ViewGroup toolbar=(ViewGroup)c.getChromeToolbar();double[] centers=new double[2];int i=0;
        for(ReverbBrowserChromeActionView a:new ReverbBrowserChromeActionView[]{c.getTabsAction(),c.getMenuAction()}) {
            View visual=a.getVisual();assertFalse(visual.hasFocus());Bitmap b=raster(visual);
            try {int left=b.getWidth(),right=-1;
                for(int y=0;y<b.getHeight();y++)for(int x=0;x<b.getWidth();x++)if(android.graphics.Color.alpha(b.getPixel(x,y))>0){left=Math.min(left,x);right=Math.max(right,x);}
                assertTrue("Actual glyph ink",right>=left);assertTrue("Unboxed idle glyph only",right-left+1<=24);
                centers[i++]=relative(toolbar,visual).left+(left+right+1)/2.0;
            } finally {b.recycle();}
        }
        assertEquals("Glyph centers36px apart at mdpi; owners remain48px",36.0,Math.abs(centers[0]-centers[1]),1.0);
    }
    static ReverbBrowserChromeAction action(String id){return new ReverbBrowserChromeAction(id,id,new ReverbBrowserChromeIcon.Shared(ReverbIcon.Browser),true);}
    static Rect relative(ViewGroup root,View child){Rect r=new Rect(0,0,child.getWidth(),child.getHeight());root.offsetDescendantRectToMyCoords(child,r);return r;}
    static void size(View v,int w,int h){v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h);}
    static void settle(){PhoneBrowserSnapshotTest.settle();}
    static boolean tap(View root,float x,float y){long down=SystemClock.uptimeMillis();boolean a=send(root,down,MotionEvent.ACTION_DOWN,x,y);org.robolectric.shadows.ShadowLooper.getShadowMainLooper().idleFor(java.time.Duration.ofMillis(16));boolean b=send(root,down,MotionEvent.ACTION_UP,x,y);org.robolectric.shadows.ShadowLooper.getShadowMainLooper().idleFor(java.time.Duration.ofMillis(80));return a&&b;}
    static boolean send(View root,long down,int action,float x,float y){MotionEvent.PointerProperties p=new MotionEvent.PointerProperties();p.id=0;p.toolType=MotionEvent.TOOL_TYPE_FINGER;MotionEvent.PointerCoords c=new MotionEvent.PointerCoords();c.x=x;c.y=y;c.pressure=1;c.size=1;MotionEvent e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,1,new MotionEvent.PointerProperties[]{p},new MotionEvent.PointerCoords[]{c},0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);try{return root.dispatchTouchEvent(e);}finally{e.recycle();}}
    static Bitmap raster(View v){Bitmap b=Bitmap.createBitmap(v.getWidth(),v.getHeight(),Bitmap.Config.ARGB_8888);v.draw(new Canvas(b));return b;}
    static void save(View v,String name)throws Exception{Bitmap b=raster(v);try{save(b,name);}finally{b.recycle();}}
    static void save(Bitmap b,String name)throws Exception{try(java.io.OutputStream out=Files.newOutputStream(Path.of(System.getProperty("address.test.output"),name+".png"),StandardOpenOption.CREATE_NEW)){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,out));}}
    static void receipt(String name,boolean control)throws Exception{Files.writeString(Path.of(System.getProperty("address.test.output"),name+".json"),new JSONObject().put("status","PASS").put("actualControlBrowser",control).put("nativeSoftwareCanvas",true).put("physicalIME",false).put("network",false).put("phone",false).toString(2)+"\n",StandardOpenOption.CREATE_NEW);}
}
