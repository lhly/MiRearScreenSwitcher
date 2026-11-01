package top.zainzh.rearscreen;

import android.app.ActivityOptions;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.View;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Real Android overlay lifecycle plus the production notification service's cast diversion. */
@androidx.annotation.RequiresApi(33)
public class RearCastNotificationTest {
    final Instrumentation inst = InstrumentationRegistry.getInstrumentation();
    final Context context = inst.getTargetContext();
    FixtureCastNotificationActivity activity;
    ImageReader images;
    VirtualDisplay display;
    RearCastNotification.Target target;

    @Before public void setup() {
        inst.getUiAutomation().adoptShellPermissionIdentity();
        RearAnimationManager.endAnimation(RearAnimationManager.getCurrentAnimation());
    }
    @After public void cleanup() throws Exception {
        if (target != null) RearCastNotification.detach(target);
        if (activity != null && !activity.isDestroyed()) {
            inst.runOnMainSync(activity::finish); await(activity::isDestroyed, 4000);
        }
        if (display != null) display.release();
        if (images != null) images.close();
        RearAnimationManager.endAnimation(RearAnimationManager.getCurrentAnimation());
        inst.getUiAutomation().dropShellPermissionIdentity();
    }
    void launch(int width, int height, int density, float font, boolean dark) throws Exception {
        images = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        images.setOnImageAvailableListener(reader -> { Image image = reader.acquireLatestImage(); if (image != null) image.close(); }, new Handler(Looper.getMainLooper()));
        display = context.getSystemService(DisplayManager.class).createVirtualDisplay("cast-capsule-test", width, height, density,
                images.getSurface(), DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
        Instrumentation.ActivityMonitor monitor = inst.addMonitor(FixtureCastNotificationActivity.class.getName(), null, false);
        try {
            ActivityOptions options = ActivityOptions.makeBasic(); options.setLaunchDisplayId(display.getDisplay().getDisplayId());
            context.startActivity(new Intent(context, FixtureCastNotificationActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options.toBundle());
            activity = (FixtureCastNotificationActivity) monitor.waitForActivityWithTimeout(5000);
            assertNotNull(activity);
            await(() -> activity.root.getWidth() > 0 && activity.output.getHolder().getSurface().isValid() && activity.surfaceChanges >= 2, 3000);
            inst.runOnMainSync(() -> {
                Configuration config = new Configuration(activity.getResources().getConfiguration());
                config.fontScale = font;
                config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                        | (dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
                // Rebuild only the fixture view with a configuration context; no system settings are changed.
                activity.capsule.close(); activity.root.removeView(activity.capsule);
                activity.capsule = new RearCastNotificationOverlay(activity.createConfigurationContext(config));
                activity.root.addView(activity.capsule, new android.widget.FrameLayout.LayoutParams(1, -2));
                activity.capsule.hostState(new RearCastViewport(width, height, 0, density,
                        width == 976 ? 296 : 24, 32, width - 24, height - 32), 0, 0, true);
            });
        } finally { inst.removeMonitor(monitor); }
    }
    void offer(String title, int seconds, boolean oled) {
        inst.runOnMainSync(() -> activity.capsule.offer(new RearCastNotification.Message(context.getPackageName(), title,
                "这是一条通知摘要，长文字会在行尾截断。Long notification content.", SystemClock.elapsedRealtime(), seconds, oled)));
    }
    void await(BooleanSupplier condition, long timeout) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < deadline) {
            boolean[] done = {false}; inst.runOnMainSync(() -> done[0] = condition.getAsBoolean());
            if (done[0]) return; Thread.sleep(30);
        }
        fail("Capsule state timed out");
    }
    @Test public void repeatedNotificationReplacesAndExtendsWithoutOldExpiryClosingIt() throws Exception {
        launch(976, 596, 450, 1f, false);
        int[] surface = {0, 0, 0};
        inst.runOnMainSync(() -> { surface[0] = activity.surfaceChanges; surface[1] = activity.output.getWidth(); surface[2] = activity.output.getHeight(); });
        offer("第一条", 1, false);
        await(() -> activity.capsule.isShown(), 2000);
        Thread.sleep(600); offer("新的通知", 2, false); Thread.sleep(650);
        inst.runOnMainSync(() -> { assertTrue(activity.capsule.isShown()); assertTrue(activity.capsule.getContentDescription().toString().contains("新的通知")); });
        await(() -> activity.capsule.getVisibility() == View.GONE && !activity.capsule.hasMessage(), 3000);
        inst.runOnMainSync(() -> {
            assertEquals(surface[0], activity.surfaceChanges);
            assertEquals(surface[1], activity.output.getWidth()); assertEquals(surface[2], activity.output.getHeight());
            assertTrue(activity.output.getHolder().getSurface().isValid());
        });
        assertEquals(RearAnimationManager.AnimationType.NONE, RearAnimationManager.getCurrentAnimation());
    }
    @Test public void hiddenHostDoesNotReplayExpiredMessagesAndCloseDropsPendingWork() throws Exception {
        launch(976, 596, 450, 1f, true); offer("即将过期", 1, false);
        RearCastViewport safe = new RearCastViewport(976, 596, 0, 450, 296, 32, 952, 564);
        inst.runOnMainSync(() -> activity.capsule.hostState(safe, 0, 0, false));
        Thread.sleep(1200);
        inst.runOnMainSync(() -> { activity.capsule.hostState(safe, 0, 0, true); assertFalse(activity.capsule.isShown()); });
        offer("新的通知", 10, false);
        inst.runOnMainSync(activity.capsule::close);
        offer("关闭后的旧投递", 10, false);
        inst.runOnMainSync(() -> { assertFalse(activity.capsule.hasMessage()); assertEquals(View.GONE, activity.capsule.getVisibility()); });
    }
    @Test public void capsuleWaitsForHitRegionAndOldTapCannotDismissReplacement() throws Exception {
        launch(976, 596, 450, 1f, false);
        long[] registered = {0}; int[] bounds = new int[4];
        inst.runOnMainSync(() -> {
            activity.capsule.close(); activity.root.removeView(activity.capsule);
            activity.capsule = new RearCastNotificationOverlay(activity, (token, left, top, right, bottom) -> {
                registered[0] = token; bounds[0] = left; bounds[1] = top; bounds[2] = right; bounds[3] = bottom;
            });
            activity.root.addView(activity.capsule, new android.widget.FrameLayout.LayoutParams(1, -2));
            activity.capsule.hostState(new RearCastViewport(976, 596, 0, 450, 296, 32, 952, 564), 0, 0, true);
        });
        offer("等待触摸区域", 10, false);
        await(() -> registered[0] > 0, 3000);
        long first = registered[0];
        inst.runOnMainSync(() -> {
            assertNotEquals(View.VISIBLE, activity.capsule.getVisibility());
            activity.capsule.regionRegistered(first, bounds[0], bounds[1], bounds[2], bounds[3], true);
        });
        await(() -> activity.capsule.isShown(), 2000);
        offer("新的通知", 10, false);
        await(() -> registered[0] > first, 2000);
        inst.runOnMainSync(() -> {
            activity.capsule.dismiss(first); assertTrue(activity.capsule.hasMessage());
            activity.capsule.regionRegistered(registered[0], bounds[0], bounds[1], bounds[2], bounds[3], true);
            activity.capsule.performClick();
            assertFalse(activity.capsule.hasMessage()); assertEquals(View.GONE, activity.capsule.getVisibility());
            assertEquals(0, registered[0]);
        });
    }
    @Test public void serviceRoutesIntoHostWithoutClaimingAnAnimationOrCallingTaskService() throws Exception {
        launch(976, 596, 450, 1f, false);
        AtomicInteger calls = new AtomicInteger();
        NotificationService service = new AttachedService();
        Field prefs = NotificationService.class.getDeclaredField("prefs"); prefs.setAccessible(true);
        prefs.set(service, context.getSharedPreferences("mrss_settings", 0));
        Field binder = NotificationService.class.getDeclaredField("taskService"); binder.setAccessible(true);
        binder.set(service, Proxy.newProxyInstance(ITaskService.class.getClassLoader(), new Class<?>[]{ITaskService.class},
                (proxy, method, args) -> { calls.incrementAndGet(); throw new AssertionError("Cast capsule must not use TaskService"); }));
        target = message -> activity.capsule.offer(message);
        RearCastNotification.attach(target);
        long generation = RearAnimationManager.getGeneration();
        inst.runOnMainSync(() -> service.showNotificationOnRearScreen(context.getPackageName(), "已隐藏标题", "已隐藏内容", 1));
        await(() -> activity.capsule.isShown(), 2000);
        assertEquals(0, calls.get()); assertEquals(generation, RearAnimationManager.getGeneration());
        assertEquals(RearAnimationManager.AnimationType.NONE, RearAnimationManager.getCurrentAnimation());
        inst.runOnMainSync(() -> {
            assertTrue(activity.capsule.isClickable()); assertFalse(activity.capsule.isFocusable());
            assertTrue(activity.capsule.getContentDescription().toString().contains("已隐藏内容"));
            android.view.MotionEvent down = android.view.MotionEvent.obtain(1, 1, 0, 20, 20, 0);
            try { assertTrue("Capsule must consume a tap", activity.capsule.dispatchTouchEvent(down)); }
            finally { down.recycle(); }
            android.view.MotionEvent up = android.view.MotionEvent.obtain(1, 2, 1, 20, 20, 0);
            try { activity.capsule.dispatchTouchEvent(up); } finally { up.recycle(); }
        });
        await(() -> !activity.capsule.hasMessage(), 1000);
        Field worker = NotificationService.class.getDeclaredField("animationWorker"); worker.setAccessible(true);
        ((java.util.concurrent.ExecutorService) worker.get(service)).shutdownNow();
    }
    @Test public void lightRearLayoutFitsAndHasReadableTheme() throws Exception { layout(976, 596, 450, 1f, false, false, "light"); }
    @Test public void chargingUsesCapsuleEvenWithAlwaysOnAndUnplugDoesNotCloseNewMessage() throws Exception {
        chargingCapsule(false);
    }
    @Test public void disabledChargingDoesNotShowCapsuleOrStartAnimation() throws Exception {
        chargingCapsule(true);
    }
    void chargingCapsule(boolean disabled) throws Exception {
        launch(976, 596, 450, 1f, true);
        android.content.SharedPreferences prefs = context.getSharedPreferences("mrss_settings", 0);
        boolean hadEnabled = prefs.contains("charging_animation_enabled"), enabled = prefs.getBoolean("charging_animation_enabled", true);
        boolean hadAlways = prefs.contains("charging_always_on_enabled"), always = prefs.getBoolean("charging_always_on_enabled", false);
        ChargingService service = new AttachedChargingService();
        AtomicInteger calls = new AtomicInteger(), offers = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<RearCastNotification.Message> latest = new java.util.concurrent.atomic.AtomicReference<>();
        try {
            prefs.edit().putBoolean("charging_animation_enabled", !disabled).putBoolean("charging_always_on_enabled", true).commit();
            Field field = ChargingService.class.getDeclaredField("prefs"); field.setAccessible(true); field.set(service, prefs);
            Field remote = ChargingService.class.getDeclaredField("taskService"); remote.setAccessible(true);
            remote.set(service, Proxy.newProxyInstance(ITaskService.class.getClassLoader(), new Class<?>[]{ITaskService.class},
                    (proxy, method, args) -> { calls.incrementAndGet(); throw new AssertionError("Cast charging must not call TaskService"); }));
            target = new RearCastNotification.Target() {
                public void show(RearCastNotification.Message message) { offers.incrementAndGet(); latest.set(message); activity.capsule.offer(message); }
                public void clearCharging() { activity.capsule.dismissCharging(); }
            };
            RearCastNotification.attach(target);
            Field receiver = ChargingService.class.getDeclaredField("batteryReceiver"); receiver.setAccessible(true);
            android.content.BroadcastReceiver power = (android.content.BroadcastReceiver) receiver.get(service);
            long generation = RearAnimationManager.getGeneration();
            inst.runOnMainSync(() -> {
                power.onReceive(context, new Intent(Intent.ACTION_POWER_CONNECTED));
                power.onReceive(context, new Intent(Intent.ACTION_POWER_CONNECTED));
            });
            assertEquals(disabled ? 0 : 1, offers.get());
            if (!disabled) {
                await(() -> activity.capsule.isShown(), 3000);
                assertTrue(latest.get().charging);
                assertTrue(latest.get().remaining(SystemClock.elapsedRealtime()) <= 8000);
                inst.runOnMainSync(() -> power.onReceive(context, new Intent(Intent.ACTION_POWER_DISCONNECTED)));
                assertFalse(activity.capsule.hasMessage());
                inst.runOnMainSync(() -> {
                    power.onReceive(context, new Intent(Intent.ACTION_POWER_CONNECTED));
                    activity.capsule.performClick(); assertFalse(activity.capsule.hasMessage());
                    power.onReceive(context, new Intent(Intent.ACTION_POWER_CONNECTED)); // no re-show after dismissal
                    assertFalse(activity.capsule.hasMessage());
                    RearCastNotification.offer(context.getPackageName(), "消息优先显示", "新消息", SystemClock.elapsedRealtime(), 10, false);
                    power.onReceive(context, new Intent(Intent.ACTION_POWER_DISCONNECTED));
                    assertTrue(activity.capsule.hasMessage());
                    assertTrue(activity.capsule.getContentDescription().toString().contains("消息优先显示"));
                });
                assertEquals(3, offers.get()); // two true connections and the notification
            }
            assertEquals(0, calls.get()); assertEquals(generation, RearAnimationManager.getGeneration());
            assertEquals(RearAnimationManager.AnimationType.NONE, RearAnimationManager.getCurrentAnimation());
        } finally {
            android.content.SharedPreferences.Editor edit = prefs.edit();
            if (hadEnabled) edit.putBoolean("charging_animation_enabled", enabled); else edit.remove("charging_animation_enabled");
            if (hadAlways) edit.putBoolean("charging_always_on_enabled", always); else edit.remove("charging_always_on_enabled");
            edit.commit();
            Field worker = ChargingService.class.getDeclaredField("worker"); worker.setAccessible(true);
            ((java.util.concurrent.ExecutorService) worker.get(service)).shutdownNow();
        }
    }
    @Test public void darkLargeFontLayoutFits() throws Exception { layout(976, 596, 450, 1.8f, true, false, "dark-large"); }
    @Test public void narrowPortraitLayoutFitsWithSystemAnimationsOff() throws Exception {
        String old = shell("settings get global animator_duration_scale");
        try {
            shell("settings put global animator_duration_scale 0");
            layout(596, 976, 320, 1.8f, false, true, "portrait-oled");
        } finally { shell(old.equals("null") ? "settings delete global animator_duration_scale" : "settings put global animator_duration_scale " + old); }
    }
    void layout(int width, int height, int density, float font, boolean dark, boolean oled, String name) throws Exception {
        launch(width, height, density, font, dark); offer("应用通知：一条较长的通知标题", 10, oled);
        await(() -> activity.capsule.isShown() && activity.capsule.getWidth() > 1 && activity.capsule.getAlpha() == 1f, 3000);
        inst.runOnMainSync(() -> {
            assertTrue(activity.capsule.getLeft() >= (width == 976 ? 296 : 24));
            assertTrue(activity.capsule.getTop() >= 32);
            assertTrue(activity.capsule.getRight() <= width - 24);
            assertTrue(activity.capsule.getBottom() <= height - 32);
            assertTrue(activity.capsule.getHeight() < height / 2);
            assertLabels(activity.capsule, oled ? Color.BLACK : activity.capsule.getContext().getColor(R.color.remrss_surface));
        });
        Bitmap bitmap = Bitmap.createBitmap(activity.root.getWidth(), activity.root.getHeight(), Bitmap.Config.ARGB_8888);
        CountDownLatch copied = new CountDownLatch(1); int[] status = {-1};
        inst.runOnMainSync(() -> PixelCopy.request(activity.getWindow(), bitmap, result -> { status[0] = result; copied.countDown(); }, new Handler(Looper.getMainLooper())));
        assertTrue(copied.await(3, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, status[0]);
        File out = new File(context.getExternalFilesDir(null), "cast-capsule-" + name + ".png");
        try (FileOutputStream stream = new FileOutputStream(out)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
        bitmap.recycle();
    }
    void assertLabels(View view, int background) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            assertEquals(1, text.getMaxLines());
            double a = luminance(text.getCurrentTextColor()), b = luminance(background);
            assertTrue("Text contrast must be >= 4.5", (Math.max(a, b) + .05) / (Math.min(a, b) + .05) >= 4.5);
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup parent = (android.view.ViewGroup) view;
            for (int i = 0; i < parent.getChildCount(); i++) assertLabels(parent.getChildAt(i), background);
        }
    }
    double luminance(int color) {
        double sum = 0; double[] weights = {.2126, .7152, .0722}; int[] channels = {Color.red(color), Color.green(color), Color.blue(color)};
        for (int i = 0; i < 3; i++) { double c = channels[i] / 255d; sum += weights[i] * (c <= .04045 ? c / 12.92 : Math.pow((c + .055) / 1.055, 2.4)); }
        return sum;
    }
    String shell(String command) throws Exception {
        try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(inst.getUiAutomation().executeShellCommand(command))) {
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        }
    }
    static class AttachedService extends NotificationService { AttachedService() { attachBaseContext(contextStatic()); } }
    static class AttachedChargingService extends ChargingService { AttachedChargingService() { attachBaseContext(contextStatic()); } }
    static Context contextStatic() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
}
