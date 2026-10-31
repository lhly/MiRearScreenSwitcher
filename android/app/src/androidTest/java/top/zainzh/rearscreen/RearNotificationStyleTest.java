package top.zainzh.rearscreen;

import android.app.ActivityOptions;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.PixelCopy;
import android.view.View;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Actual rear windows: theme changes, OLED/privacy compatibility and small-screen typography. */
public class RearNotificationStyleTest {
    final Instrumentation inst = InstrumentationRegistry.getInstrumentation();
    final Context context = inst.getTargetContext();
    RearScreenNotificationActivity activity;
    VirtualDisplay display;
    int displayId;
    ImageReader reader;
    String night, font;
    SharedPreferences prefs;
    int duration;
    boolean hadDuration, initialized;
    Object cachedInfo;
    static final String LONG_TEXT = "这是用于检查背屏通知布局的较长正文。The notification must remain inside the safe area at large font sizes. ";

    String shell(String command) throws Exception {
        try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(inst.getUiAutomation().executeShellCommand(command))) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString("UTF-8").trim();
        }
    }
    static Field cacheField(String name) throws Exception {
        Field field = DisplayInfoCache.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
    @Before public void prepare() throws Exception {
        if (android.os.Build.VERSION.SDK_INT >= 29) inst.getUiAutomation().adoptShellPermissionIdentity();
        String mode = shell("cmd uimode night"); night = mode.contains("auto") ? "auto" : mode.contains("yes") ? "yes" : "no";
        font = shell("settings get system font_scale");
        DisplayInfoCache cache = DisplayInfoCache.getInstance();
        initialized = cache.isInitialized(); cachedInfo = cacheField("cachedInfo").get(cache);
        prefs = context.getSharedPreferences("mrss_settings", 0); hadDuration = prefs.contains("notification_duration"); duration = prefs.getInt("notification_duration", 10);
        prefs.edit().putInt("notification_duration", 120).commit();
        RearAnimationManager.endAnimation(RearAnimationManager.getCurrentAnimation()); RearAnimationManager.clearChargingAlwaysOnFlag();
    }
    @After public void cleanup() throws Exception {
        closeActivity();
        if (display != null) display.release(); if (reader != null) reader.close();
        cacheField("cachedInfo").set(DisplayInfoCache.getInstance(), cachedInfo); cacheField("initialized").set(DisplayInfoCache.getInstance(), initialized);
        if (hadDuration) prefs.edit().putInt("notification_duration", duration).commit(); else prefs.edit().remove("notification_duration").commit();
        shell("cmd uimode night " + night);
        shell(font.equals("null") ? "settings delete system font_scale" : "settings put system font_scale " + font);
        RearAnimationManager.endAnimation(RearAnimationManager.getCurrentAnimation());
        if (android.os.Build.VERSION.SDK_INT >= 29) inst.getUiAutomation().dropShellPermissionIdentity();
    }
    void createDisplay(boolean portrait) throws Exception {
        int width = portrait ? 596 : 976, height = portrait ? 976 : 596;
        boolean primary = "true".equals(InstrumentationRegistry.getArguments().getString("notificationUsePrimary"));
        if (!primary) {
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(source -> { Image image = source.acquireLatestImage(); if (image != null) image.close(); }, new Handler(Looper.getMainLooper()));
        display = context.getSystemService(DisplayManager.class).createVirtualDisplay("notification-style", width, height, 450,
            reader.getSurface(), DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
        assertNotNull(display);
        displayId = display.getDisplay().getDisplayId();
        } else displayId = 0;
        RearDisplayHelper.RearDisplayInfo info = new RearDisplayHelper.RearDisplayInfo();
        info.width = width; info.height = height; info.densityDpi = 450;
        info.cutout = portrait ? new Rect(0, 296, 0, 0) : new Rect(296, 0, 0, 0);
        cacheField("cachedInfo").set(DisplayInfoCache.getInstance(), info); cacheField("initialized").set(DisplayInfoCache.getInstance(), true);
    }
    RearScreenNotificationActivity launch(boolean oled, String text) throws Exception {
        long id = RearAnimationManager.beginAnimation(RearAnimationManager.AnimationType.NOTIFICATION).id;
        Instrumentation.ActivityMonitor monitor = inst.addMonitor(FixtureNotificationActivity.class.getName(), null, false);
        try {
            ActivityOptions options = ActivityOptions.makeBasic(); options.setLaunchDisplayId(displayId);
            context.startActivity(new Intent(context, FixtureNotificationActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("fixtureRearDisplayId", displayId).putExtra("packageName", context.getPackageName())
                .putExtra("title", "ReMRSS 通知样式检查 / Long notification title").putExtra("text", text).putExtra("darkMode", oled)
                .putExtra(RearAnimationManager.EXTRA_SESSION_ID, id), options.toBundle());
            RearScreenNotificationActivity result = (RearScreenNotificationActivity) monitor.waitForActivityWithTimeout(5000);
            assertNotNull(result); inst.waitForIdleSync();
            long end = android.os.SystemClock.uptimeMillis() + 5000;
            while (android.os.SystemClock.uptimeMillis() < end) {
                final boolean[] settled = {false};
                inst.runOnMainSync(() -> settled[0] = result.findViewById(R.id.notification_content_container).getAlpha() == 1f
                    && result.findViewById(R.id.notification_content_container).getTranslationY() == 0f);
                if (settled[0]) { Thread.sleep(100); return result; }
                Thread.sleep(30);
            }
            throw new AssertionError("Notification animation did not settle");
        } finally { inst.removeMonitor(monitor); }
    }
    void await(BooleanSupplier condition) throws Exception {
        long end = android.os.SystemClock.uptimeMillis() + 5000;
        while (android.os.SystemClock.uptimeMillis() < end) {
            final boolean[] done = {false}; inst.runOnMainSync(() -> done[0] = condition.getAsBoolean()); if (done[0]) return; Thread.sleep(30);
        }
        fail("Notification state timed out");
    }
    void mode(String mode) throws Exception {
        shell("cmd uimode night " + mode);
        await(() -> ((context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) == mode.equals("yes"));
    }
    void assertFits() {
        View container = activity.findViewById(R.id.notification_container), wrapper = activity.findViewById(R.id.notification_wrapper);
        TextView title = activity.findViewById(R.id.notification_title), content = activity.findViewById(R.id.notification_content);
        assertTrue("Card must fit safe-area height", wrapper.getTop() >= 0 && wrapper.getBottom() <= container.getHeight());
        assertTrue("Card must fit safe-area width", wrapper.getLeft() >= 0 && wrapper.getRight() <= container.getWidth());
        assertTrue("Header must fit card", title.getWidth() <= wrapper.getWidth());
        if (content.getVisibility() == View.VISIBLE) {
            Rect bounds = new Rect(); content.getDrawingRect(bounds);
            ((android.view.ViewGroup) wrapper).offsetDescendantRectToMyCoords(content, bounds);
            assertTrue("Body must fit card", bounds.bottom <= wrapper.getHeight() - wrapper.getPaddingBottom());
            assertTrue(content.getMaxLines() >= 1 && content.getMaxLines() <= 6);
        }
    }
    Bitmap copyWindow() throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(activity.getWindow().getDecorView().getWidth(), activity.getWindow().getDecorView().getHeight(), Bitmap.Config.ARGB_8888);
        CountDownLatch copied = new CountDownLatch(1); int[] result = {-1};
        inst.runOnMainSync(() -> PixelCopy.request(activity.getWindow(), bitmap, code -> { result[0] = code; copied.countDown(); }, new Handler(Looper.getMainLooper())));
        assertTrue(copied.await(3, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, result[0]);
        return bitmap;
    }
    void capture(String name) throws Exception {
        Bitmap bitmap = copyWindow();
        try (FileOutputStream output = new FileOutputStream(new File(context.getCacheDir(), "notification-" + name + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); }
        bitmap.recycle();
    }
    void closeActivity() throws Exception {
        if (activity != null) { inst.runOnMainSync(activity::finish); await(activity::isDestroyed); activity = null; }
    }
    @Test public void themeChangeKeepsWindowPayloadAndSafeArea() throws Exception {
        mode("no"); createDisplay(false); activity = launch(false, LONG_TEXT.repeat(8));
        View root = activity.findViewById(R.id.notification_root); int light = ((ColorDrawable)root.getBackground()).getColor();
        assertNull(activity.findViewById(R.id.notification_wrapper).getBackground());
        inst.runOnMainSync(this::assertFits); capture("light"); mode("yes");
        await(() -> ((ColorDrawable)root.getBackground()).getColor() != light);
        inst.runOnMainSync(() -> {
            assertSame(root, activity.findViewById(R.id.notification_root)); assertFalse(activity.isFinishing());
            assertNull("Content must not acquire a second card", activity.findViewById(R.id.notification_wrapper).getBackground());
            assertEquals(LONG_TEXT.repeat(8), ((TextView)activity.findViewById(R.id.notification_content)).getText().toString());
            assertEquals(((ColorDrawable)root.getBackground()).getColor(), ((ColorDrawable)activity.getWindow().getDecorView().getBackground()).getColor());
            assertFits();
        });
        capture("dark");
    }
    @Test public void oledModeStaysBlackAndNextNotificationRestoresNormalContent() throws Exception {
        mode("no"); createDisplay(false); activity = launch(true, "");
        inst.runOnMainSync(() -> {
            assertEquals(android.graphics.Color.BLACK, ((ColorDrawable)activity.findViewById(R.id.notification_root).getBackground()).getColor());
            assertEquals(View.GONE, activity.findViewById(R.id.notification_content).getVisibility()); assertFits();
        });
        capture("oled-private"); mode("yes");
        inst.runOnMainSync(() -> assertEquals(android.graphics.Color.BLACK, ((ColorDrawable)activity.findViewById(R.id.notification_root).getBackground()).getColor()));
        activity = launch(false, LONG_TEXT);
        inst.runOnMainSync(() -> {
            assertEquals(View.VISIBLE, activity.findViewById(R.id.notification_content).getVisibility());
            assertNotEquals(android.graphics.Color.BLACK, ((ColorDrawable)activity.findViewById(R.id.notification_root).getBackground()).getColor()); assertFits();
        });
    }
    @Test public void largeTextFitsLandscapeAndPortraitSafeAreas() throws Exception {
        shell("settings put system font_scale 2.0"); await(() -> context.getResources().getConfiguration().fontScale >= 1.99f); mode("no");
        for (boolean portrait : new boolean[]{false, true}) {
            createDisplay(portrait); activity = launch(false, LONG_TEXT.repeat(8));
            inst.runOnMainSync(() -> { assertFits(); assertTrue(activity.getResources().getConfiguration().fontScale >= 1.99f); });
            capture(portrait ? "portrait-large" : "landscape-large"); closeActivity(); display.release(); reader.close(); display = null; reader = null;
        }
    }
    @Test public void unavailableDisplayInfoKeepsSystemWindowMetrics() throws Exception {
        createDisplay(false);
        DisplayInfoCache.getInstance().clear();
        android.util.DisplayMetrics expected = new android.util.DisplayMetrics();
        display.getDisplay().getRealMetrics(expected);
        activity = launch(false, LONG_TEXT);
        inst.runOnMainSync(() -> {
            assertFalse(DisplayInfoCache.getInstance().isInitialized());
            assertNull(DisplayInfoCache.getInstance().getCachedInfo());
            assertEquals(expected.densityDpi, activity.getResources().getDisplayMetrics().densityDpi);
            assertTrue(activity.getResources().getDisplayMetrics().density > 0);
            assertFits();
        });
        capture("unavailable-display-info");
    }
    @Test public void confirmedNoCutoutClearsPreviousSafeArea() throws Exception {
        createDisplay(false); activity = launch(false, LONG_TEXT);
        java.lang.reflect.Method apply = RearScreenNotificationActivity.class.getDeclaredMethod("applySafeAreaPadding");
        apply.setAccessible(true);
        inst.runOnMainSync(() -> {
            View container = activity.findViewById(R.id.notification_container);
            assertEquals(296, ((android.view.ViewGroup.MarginLayoutParams)container.getLayoutParams()).leftMargin);
            DisplayInfoCache.getInstance().getCachedInfo().cutout = new Rect(0, 0, 0, 0);
            try { apply.invoke(activity); } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            android.view.ViewGroup.MarginLayoutParams margins = (android.view.ViewGroup.MarginLayoutParams)container.getLayoutParams();
            assertEquals(0, margins.leftMargin); assertEquals(0, margins.topMargin);
            assertEquals(0, margins.rightMargin); assertEquals(0, margins.bottomMargin);
        });
    }

    @Test public void singleGlassPlaneKeepsTextReadableInBothThemes() throws Exception {
        mode("no"); createDisplay(false); activity = launch(false, LONG_TEXT);
        for (String theme : new String[]{"no", "yes"}) {
            mode(theme); Thread.sleep(250);
            inst.runOnMainSync(() -> {
                View root = activity.findViewById(R.id.notification_root);
                View glass = activity.findViewById(R.id.notification_glass);
                assertEquals(root.getWidth(), glass.getWidth()); assertEquals(root.getHeight(), glass.getHeight());
                assertNull(activity.findViewById(R.id.notification_wrapper).getBackground());
                assertEquals(0f, activity.findViewById(R.id.notification_wrapper).getElevation(), 0f);
                assertFits();
            });
            assertTextContrast();
            capture(theme.equals("yes") ? "glass-dark" : "glass-light");
        }
    }

    @Test public void settledMaterialDoesNotContinuouslyRedraw() throws Exception {
        mode("yes"); createDisplay(false); activity = launch(false, LONG_TEXT);
        RearNotificationGlassView glass = activity.findViewById(R.id.notification_glass);
        Thread.sleep(250); final long[] frames = {0};
        inst.runOnMainSync(() -> frames[0] = glass.getRenderedFrameCount());
        Thread.sleep(600);
        inst.runOnMainSync(() -> assertEquals("A stable notification must not run a material frame loop",
                frames[0], glass.getRenderedFrameCount()));
    }

    void assertTextContrast() throws Exception {
        Bitmap foreground = copyWindow();
        int[][] regions = new int[3][5];
        inst.runOnMainSync(() -> {
            int n = 0;
            for (int id : new int[]{R.id.app_name, R.id.notification_title, R.id.notification_content}) {
                TextView view = activity.findViewById(id);
                int[] location = new int[2]; view.getLocationInWindow(location);
                regions[n++] = new int[]{location[0], location[1], location[0] + view.getWidth(),
                        location[1] + view.getHeight(), view.getCurrentTextColor()};
            }
            activity.findViewById(R.id.notification_wrapper).setVisibility(View.INVISIBLE);
        });
        Thread.sleep(120); Bitmap background = copyWindow();
        inst.runOnMainSync(() -> activity.findViewById(R.id.notification_wrapper).setVisibility(View.VISIBLE));
        try {
            for (int[] region : regions) {
                double minimum = 21; int samples = 0;
                for (int y = Math.max(0, region[1]); y < Math.min(foreground.getHeight(), region[3]); y++) {
                    for (int x = Math.max(0, region[0]); x < Math.min(foreground.getWidth(), region[2]); x++) {
                        int pixel = foreground.getPixel(x, y), color = region[4];
                        if (Math.abs(android.graphics.Color.red(pixel) - android.graphics.Color.red(color)) <= 2
                                && Math.abs(android.graphics.Color.green(pixel) - android.graphics.Color.green(color)) <= 2
                                && Math.abs(android.graphics.Color.blue(pixel) - android.graphics.Color.blue(color)) <= 2) {
                            double a = luminance(color), b = luminance(background.getPixel(x, y));
                            minimum = Math.min(minimum, (Math.max(a,b) + .05) / (Math.min(a,b) + .05)); samples++;
                        }
                    }
                }
                assertTrue("Text must have visible glyph samples", samples > 20);
                assertTrue("Notification text contrast must be >= 4.5:1; actual=" + minimum, minimum >= 4.5);
            }
        } finally { foreground.recycle(); background.recycle(); }
    }

    static double luminance(int color) {
        double[] value = {android.graphics.Color.red(color)/255.0, android.graphics.Color.green(color)/255.0, android.graphics.Color.blue(color)/255.0};
        for (int i = 0; i < 3; i++) value[i] = value[i] <= .04045 ? value[i]/12.92 : Math.pow((value[i]+.055)/1.055,2.4);
        return .2126*value[0] + .7152*value[1] + .0722*value[2];
    }
}
