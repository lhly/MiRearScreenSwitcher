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
        inst.getUiAutomation().adoptShellPermissionIdentity();
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
        inst.getUiAutomation().dropShellPermissionIdentity();
    }
    void createDisplay(boolean portrait) throws Exception {
        int width = portrait ? 596 : 976, height = portrait ? 976 : 596;
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(source -> { Image image = source.acquireLatestImage(); if (image != null) image.close(); }, new Handler(Looper.getMainLooper()));
        display = context.getSystemService(DisplayManager.class).createVirtualDisplay("notification-style", width, height, 450,
            reader.getSurface(), DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
        assertNotNull(display);
        RearDisplayHelper.RearDisplayInfo info = new RearDisplayHelper.RearDisplayInfo();
        info.width = width; info.height = height; info.densityDpi = 450;
        info.cutout = portrait ? new Rect(0, 296, 0, 0) : new Rect(296, 0, 0, 0);
        cacheField("cachedInfo").set(DisplayInfoCache.getInstance(), info); cacheField("initialized").set(DisplayInfoCache.getInstance(), true);
    }
    RearScreenNotificationActivity launch(boolean oled, String text) throws Exception {
        long id = RearAnimationManager.beginAnimation(RearAnimationManager.AnimationType.NOTIFICATION).id;
        Instrumentation.ActivityMonitor monitor = inst.addMonitor(FixtureNotificationActivity.class.getName(), null, false);
        try {
            ActivityOptions options = ActivityOptions.makeBasic(); options.setLaunchDisplayId(display.getDisplay().getDisplayId());
            context.startActivity(new Intent(context, FixtureNotificationActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("fixtureRearDisplayId", display.getDisplay().getDisplayId()).putExtra("packageName", "android")
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
    void capture(String name) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(activity.getWindow().getDecorView().getWidth(), activity.getWindow().getDecorView().getHeight(), Bitmap.Config.ARGB_8888);
        CountDownLatch copied = new CountDownLatch(1); int[] result = {-1};
        inst.runOnMainSync(() -> PixelCopy.request(activity.getWindow(), bitmap, code -> { result[0] = code; copied.countDown(); }, new Handler(Looper.getMainLooper())));
        assertTrue(copied.await(3, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, result[0]);
        try (FileOutputStream output = new FileOutputStream(new File(context.getCacheDir(), "notification-" + name + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); }
        bitmap.recycle();
    }
    void closeActivity() throws Exception {
        if (activity != null) { inst.runOnMainSync(activity::finish); await(activity::isDestroyed); activity = null; }
    }
    @Test public void themeChangeKeepsWindowPayloadAndSafeArea() throws Exception {
        mode("no"); createDisplay(false); activity = launch(false, LONG_TEXT.repeat(8));
        View root = activity.findViewById(R.id.notification_root); int light = ((ColorDrawable)root.getBackground()).getColor();
        int lightCard = ((android.graphics.drawable.GradientDrawable)activity.findViewById(R.id.notification_wrapper).getBackground()).getColor().getDefaultColor();
        inst.runOnMainSync(this::assertFits); capture("light"); mode("yes");
        await(() -> ((ColorDrawable)root.getBackground()).getColor() != light);
        inst.runOnMainSync(() -> {
            assertSame(root, activity.findViewById(R.id.notification_root)); assertFalse(activity.isFinishing());
            assertNotEquals("Card must refresh with the background and foreground", lightCard,
                ((android.graphics.drawable.GradientDrawable)activity.findViewById(R.id.notification_wrapper).getBackground()).getColor().getDefaultColor());
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
}
