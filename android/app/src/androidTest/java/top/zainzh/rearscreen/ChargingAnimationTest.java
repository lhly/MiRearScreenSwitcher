package top.zainzh.rearscreen;

import android.app.Instrumentation;
import android.app.ActivityOptions;
import android.graphics.PixelFormat;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/** 真实副屏上的生命周期回归，以及可控Binder延迟/取消回归。 */
@RunWith(AndroidJUnit4.class)
public class ChargingAnimationTest {
    private static final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private static final Context context = instrumentation.getTargetContext();
    private static int rearDisplayId;
    private static ImageReader reader;
    private static VirtualDisplay virtualDisplay;
    private SharedPreferences prefs;
    private Map<String, ?> originalPrefs;
    private RearScreenChargingActivity activity;
    private AttachedService service;
    private CountDownLatch release;
    private final AtomicInteger starts = new AtomicInteger();
    private final AtomicInteger restores = new AtomicInteger();
    private final AtomicInteger moves = new AtomicInteger();
    private final AtomicInteger wakeups = new AtomicInteger();
    private volatile String launchCommand;
    private volatile boolean shellOnMain;

    @BeforeClass public static void prepareShell() {
        instrumentation.getUiAutomation().adoptShellPermissionIdentity();
        DisplayManager displays = context.getSystemService(DisplayManager.class);
        if (displays.getDisplay(1) != null) {
            rearDisplayId = 1;
        } else {
            reader = ImageReader.newInstance(976, 596, PixelFormat.RGBA_8888, 3);
            reader.setOnImageAvailableListener(source -> {
                Image image = source.acquireLatestImage();
                if (image != null) image.close();
            }, new Handler(Looper.getMainLooper()));
            virtualDisplay = displays.createVirtualDisplay("charging-regression", 976, 596, 450,
                    reader.getSurface(), DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                            | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
            assertNotNull(virtualDisplay);
            rearDisplayId = virtualDisplay.getDisplay().getDisplayId();
        }
    }

    @AfterClass public static void releaseShell() {
        if (virtualDisplay != null) virtualDisplay.release();
        if (reader != null) reader.close();
        instrumentation.getUiAutomation().dropShellPermissionIdentity();
    }

    @Before public void prepare() {
        prefs = context.getSharedPreferences("mrss_settings", Context.MODE_PRIVATE);
        originalPrefs = prefs.getAll();
        prefs.edit().putBoolean("charging_animation_enabled", true)
                .putBoolean("charging_always_on_enabled", true).commit();
        RearAnimationManager.endAnimation(RearAnimationManager.getCurrentAnimation());
        RearAnimationManager.clearChargingAlwaysOnFlag();
    }

    @After public void cleanup() throws Exception {
        if (release != null) release.countDown();
        if (activity != null) instrumentation.runOnMainSync(activity::finish);
        instrumentation.waitForIdleSync();
        if (activity != null) await(activity::isDestroyed, 2000);
        if (service != null) {
            Object request = get(service, "activeRequest");
            if (request != null) set(request, "cancelled", true);
            Handler handler = (Handler) get(service, "handler");
            instrumentation.runOnMainSync(() -> handler.removeCallbacksAndMessages(null));
            ExecutorService worker = (ExecutorService) get(service, "worker");
            worker.shutdown();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            set(null, ChargingService.class, "instance", null);
        }
        RearAnimationManager.endAnimation(RearAnimationManager.getCurrentAnimation());
        SharedPreferences.Editor edit = prefs.edit().clear();
        for (Map.Entry<String, ?> item : originalPrefs.entrySet()) {
            Object value = item.getValue(); String key = item.getKey();
            if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) edit.putInt(key, (Integer) value);
            else if (value instanceof Long) edit.putLong(key, (Long) value);
            else if (value instanceof Float) edit.putFloat(key, (Float) value);
            else if (value instanceof String) edit.putString(key, (String) value);
            else if (value instanceof java.util.Set) edit.putStringSet(key, (java.util.Set<String>) value);
        }
        edit.commit();
    }

    @Test public void mainPlaceholderInitializesAfterMovingToRearAndUpdatesBattery() throws Exception {
        activity = launch(0, 42);
        instrumentation.runOnMainSync(() -> assertNull(activity.findViewById(R.id.full_screen_liquid)));
        shell("am display move-stack " + activity.getTaskId() + " " + rearDisplayId);
        await(() -> activity.findViewById(R.id.full_screen_liquid) != null, 4000);
        instrumentation.runOnMainSync(() -> {
            assertEquals(rearDisplayId, activity.getDisplay().getDisplayId());
            assertEquals("42%", ((TextView) activity.findViewById(R.id.battery_text)).getText().toString());
            RearScreenChargingActivity.updateBatteryLevelStatic(61);
            assertEquals("61%", ((TextView) activity.findViewById(R.id.battery_text)).getText().toString());
        });
    }

    @Test public void unplugClosesEvenAnUninitializedPlaceholder() throws Exception {
        activity = launch(0, 42);
        send("FINISH_CHARGING_ANIMATION", 0);
        await(activity::isDestroyed, 3000);
    }

    @Test public void rotatedChargingInsetsReplaceOldMarginsAndKeepPercentageOnOneLine() throws Exception {
        org.junit.Assume.assumeNotNull(virtualDisplay);
        activity = launch(1, 100);
        try {
            virtualDisplay.resize(596, 976, 450);
            await(() -> activity.findViewById(R.id.charging_container).getWidth() == 596, 3000);
            instrumentation.runOnMainSync(() -> {
                android.view.View container = activity.findViewById(R.id.charging_container);
                TextView text = activity.findViewById(R.id.battery_text);
                for (android.graphics.Rect edge : new android.graphics.Rect[]{
                        new android.graphics.Rect(296, 0, 0, 0), new android.graphics.Rect(0, 0, 0, 296),
                        new android.graphics.Rect(0, 0, 296, 0), new android.graphics.Rect(0, 296, 0, 0),
                        new android.graphics.Rect()}) {
                    androidx.core.view.WindowInsetsCompat insets = new androidx.core.view.WindowInsetsCompat.Builder()
                            .setInsets(androidx.core.view.WindowInsetsCompat.Type.displayCutout(),
                                    androidx.core.graphics.Insets.of(edge.left, edge.top, edge.right, edge.bottom))
                            .build();
                    androidx.core.view.ViewCompat.dispatchApplyWindowInsets(container, insets);
                    android.widget.FrameLayout.LayoutParams params = (android.widget.FrameLayout.LayoutParams) text.getLayoutParams();
                    assertEquals(edge.left, params.leftMargin); assertEquals(edge.top, params.topMargin);
                    assertEquals(edge.right, params.rightMargin); assertEquals(edge.bottom, params.bottomMargin);
                }
            });
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                TextView text = activity.findViewById(R.id.battery_text);
                assertEquals("100%", text.getText().toString());
                assertEquals(1, text.getLineCount());
                assertTrue("Percentage must fit the portrait width", text.getPaint().measureText("100%")
                        <= text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight());
            });
        } finally { virtualDisplay.resize(976, 596, 450); }
    }

    @Test public void repeatedStartReusesWindowButReloadsBatteryAndTimer() throws Exception {
        prefs.edit().putBoolean("charging_always_on_enabled", false).commit();
        activity = launch(1, 42);
        Thread.sleep(4500);
        instrumentation.runOnMainSync(() -> assertNotNull(activity.findViewById(R.id.full_screen_liquid)));
        startActivity(1, 77);
        await(() -> "77%".contentEquals(((TextView) activity.findViewById(R.id.battery_text)).getText()), 3000);
        instrumentation.runOnMainSync(() -> assertEquals(rearDisplayId, activity.getDisplay().getDisplayId()));
        send("FINISH_CHARGING_ANIMATION", 123);
        Thread.sleep(4000);
        assertFalse("The old 8-second timer must be cancelled on reuse", activity.isFinishing());
        send("FINISH_CHARGING_ANIMATION", 0);
        await(activity::isDestroyed, 3000);
    }

    @Test public void normalModeFinishesAfterEightSeconds() throws Exception {
        prefs.edit().putBoolean("charging_always_on_enabled", false).commit();
        activity = launch(1, 42);
        Thread.sleep(1000);
        assertFalse(activity.isFinishing());
        await(activity::isDestroyed, 10000);
    }

    @Test public void alwaysOnSurvivesEightSecondsAndInterruptKeepsNotificationOwner() throws Exception {
        activity = launch(1, 42);
        Thread.sleep(8500);
        assertFalse(activity.isFinishing());
        RearAnimationManager.startAnimation(RearAnimationManager.AnimationType.NOTIFICATION);
        send("INTERRUPT_CHARGING_ANIMATION", 0);
        await(activity::isDestroyed, 3000);
        assertEquals(RearAnimationManager.AnimationType.NOTIFICATION, RearAnimationManager.getCurrentAnimation());
    }

    @Test public void waitingForShizukuThenUnplugNeverLaunchesLate() throws Exception {
        prepareService(null);
        power(Intent.ACTION_POWER_CONNECTED);
        power(Intent.ACTION_POWER_DISCONNECTED);
        set(service, "taskService", fakeService(null, true));
        invokePending();
        Thread.sleep(300);
        assertEquals(0, starts.get());
        assertEquals(RearAnimationManager.AnimationType.NONE, RearAnimationManager.getCurrentAnimation());
    }

    @Test public void disconnectDuringShellCancelsLaunchAndRestoresLauncher() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        prepareService(fakeService(() -> {
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new AssertionError(e); }
        }, true));
        power(Intent.ACTION_POWER_CONNECTED);
        assertTrue("Launch must execute off the main thread", entered.await(3, TimeUnit.SECONDS));
        power(Intent.ACTION_POWER_DISCONNECTED); // 主线程仍能立即处理拔线
        release.countDown();
        await(() -> restores.get() == 1, 3000);
        assertEquals(0, starts.get());
        assertFalse(shellOnMain);
    }

    @Test public void directLaunchVerifiesDisplayAndFailedLaunchRestoresLauncher() throws Exception {
        prepareService(fakeService(null, false));
        power(Intent.ACTION_POWER_CONNECTED);
        await(() -> restores.get() == 1, 3000);
        assertEquals(1, starts.get());
        assertTrue(launchCommand, launchCommand.contains("am start --display 1"));
        assertTrue(launchCommand, launchCommand.contains("--el chargingRequestId"));
        assertEquals(RearAnimationManager.AnimationType.NONE, RearAnimationManager.getCurrentAnimation());
        assertFalse(shellOnMain);
    }

    @Test public void oldActivityDestroyCannotEndNewChargingRequest() throws Exception {
        prepareService(null);
        power(Intent.ACTION_POWER_CONNECTED);
        Object oldRequest = get(service, "activeRequest");
        long oldId = (Long) get(oldRequest, "id");
        power(Intent.ACTION_POWER_DISCONNECTED);
        power(Intent.ACTION_POWER_CONNECTED);
        Object newRequest = get(service, "activeRequest");
        long newId = (Long) get(newRequest, "id");
        assertNotEquals(oldId, newId);
        instrumentation.runOnMainSync(() -> assertFalse(ChargingService.onAnimationFinished(oldId)));
        assertTrue(ChargingService.isRequestActive(newId));
        assertEquals(RearAnimationManager.AnimationType.CHARGING, RearAnimationManager.getCurrentAnimation());
    }

    @Test public void quickReplugIsAllowedWithoutSixSecondCooldown() throws Exception {
        prepareService(fakeService(null, false));
        power(Intent.ACTION_POWER_CONNECTED);
        await(() -> restores.get() == 1, 3000);
        power(Intent.ACTION_POWER_DISCONNECTED);
        power(Intent.ACTION_POWER_CONNECTED);
        await(() -> restores.get() == 2, 3000);
        assertEquals(2, starts.get());
    }

    @Test public void successfulLaunchAndDuplicateBroadcastOnlyStartOnce() throws Exception {
        prefs.edit().putBoolean("charging_always_on_enabled", false).commit();
        prepareService(fakeService(null, true));
        power(Intent.ACTION_POWER_CONNECTED);
        await(() -> starts.get() == 1, 3000);
        Thread.sleep(200);
        power(Intent.ACTION_POWER_CONNECTED);
        Thread.sleep(200);
        assertEquals(1, starts.get());
        assertEquals(0, restores.get());
        assertTrue(ChargingService.isRequestActive((Long) get(get(service, "activeRequest"), "id")));
        assertFalse(shellOnMain);
    }

    @Test public void delayedShizukuConnectionStartsPendingRequest() throws Exception {
        prepareService(null);
        power(Intent.ACTION_POWER_CONNECTED);
        Thread.sleep(200);
        assertEquals(0, starts.get());
        set(service, "taskService", fakeService(null, true));
        invokePending();
        await(() -> starts.get() == 1, 3000);
        assertFalse(shellOnMain);
    }

    @Test public void finishingAnimationRestoresProjectedTaskWithoutOpeningLauncher() throws Exception {
        ITaskService fake = fakeService(null, true);
        prepareService(fake);
        ((ExecutorService) get(service, "worker")).execute(() -> ChargingService.restoreRearScreen(fake, 77));
        await(() -> moves.get() == 1, 3000);
        assertEquals(0, restores.get());
        assertFalse(shellOnMain);
    }

    @Test public void normalModeKeepsDisplayAwakeUntilAnimationEnds() throws Exception {
        prefs.edit().putBoolean("charging_always_on_enabled", false).commit();
        prepareService(fakeService(null, true));
        power(Intent.ACTION_POWER_CONNECTED);
        await(() -> wakeups.get() >= 2, 3000);
        assertTrue((Boolean) get(service, "wakeupRunning"));
        long id = (Long) get(get(service, "activeRequest"), "id");
        instrumentation.runOnMainSync(() -> ChargingService.onAnimationFinished(id));
        Thread.sleep(200);
        int afterFinish = wakeups.get();
        Thread.sleep(250);
        assertEquals("Display hold must stop when the animation ends", afterFinish, wakeups.get());
    }

    @Test public void disablingAlwaysOnKeepsDisplayHoldForRemainingPlayback() throws Exception {
        prepareService(fakeService(null, true));
        power(Intent.ACTION_POWER_CONNECTED);
        await(() -> wakeups.get() >= 2, 3000);
        prefs.edit().putBoolean("charging_always_on_enabled", false).commit();
        BroadcastReceiver receiver = (BroadcastReceiver) get(service, "settingsReceiver");
        int before = wakeups.get();
        instrumentation.runOnMainSync(() -> receiver.onReceive(context,
                new Intent(BuildConfig.APPLICATION_ID + ".RELOAD_CHARGING_SETTINGS")));
        await(() -> wakeups.get() > before, 1000);
        assertTrue((Boolean) get(service, "wakeupRunning"));
    }

    @Test public void normalExitFadesBeforeDestroyingWindow() throws Exception {
        org.junit.Assume.assumeTrue(android.animation.ValueAnimator.areAnimatorsEnabled());
        activity = launch(1, 42);
        instrumentation.runOnMainSync(activity::finish);
        await(() -> activity.findViewById(R.id.charging_container).getAlpha() < 0.99f, 1000);
        instrumentation.runOnMainSync(() -> {
            assertFalse("The window must remain while its content fades", activity.isDestroyed());
            float alpha = activity.findViewById(R.id.charging_container).getAlpha();
            assertTrue("Expected an intermediate opacity", alpha > 0f && alpha < 1f);
        });
        await(activity::isDestroyed, 2000);
    }

    @Test public void newIntentDuringExitCancelsOldFinishCallback() throws Exception {
        org.junit.Assume.assumeTrue(android.animation.ValueAnimator.areAnimatorsEnabled());
        activity = launch(1, 42);
        instrumentation.runOnMainSync(() -> {
            activity.finish();
            activity.onNewIntent(new Intent(activity.getIntent()).putExtra("batteryLevel", 63));
            assertEquals("63%", ((TextView) activity.findViewById(R.id.battery_text)).getText().toString());
            assertEquals(1f, activity.findViewById(R.id.charging_container).getAlpha(), 0.001f);
        });
        Thread.sleep(400);
        assertFalse("Cancelled exit must not finish the reused activity", activity.isFinishing());
    }

    @Test public void disabledSystemAnimationsSkipExitDelay() throws Exception {
        String key = android.provider.Settings.Global.ANIMATOR_DURATION_SCALE;
        String original = android.provider.Settings.Global.getString(context.getContentResolver(), key);
        try {
            shell("settings put global animator_duration_scale 0");
            await(() -> !android.animation.ValueAnimator.areAnimatorsEnabled(), 3000);
            activity = launch(1, 42);
            instrumentation.runOnMainSync(() -> {
                activity.finish();
                assertTrue("Reduced motion must not retain a transparent window", activity.isFinishing());
            });
            await(activity::isDestroyed, 2000);
        } finally {
            shell(original == null ? "settings delete global animator_duration_scale"
                    : "settings put global animator_duration_scale " + original);
            if (original == null || Float.parseFloat(original) > 0f) {
                await(android.animation.ValueAnimator::areAnimatorsEnabled, 3000);
            }
        }
    }

    private RearScreenChargingActivity launch(int display, int level) throws Exception {
        RearAnimationManager.startAnimation(RearAnimationManager.AnimationType.CHARGING);
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(FixtureChargingActivity.class.getName(), null, false);
        try {
            startActivity(display, level);
            RearScreenChargingActivity result = (RearScreenChargingActivity) monitor.waitForActivityWithTimeout(5000);
            assertNotNull(result);
            instrumentation.waitForIdleSync();
            return result;
        } finally { instrumentation.removeMonitor(monitor); }
    }

    private void startActivity(int display, int level) {
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(display == 1 ? rearDisplayId : 0);
        context.startActivity(new Intent(context, FixtureChargingActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("batteryLevel", level)
                .putExtra("fixtureRearDisplayId", rearDisplayId), options.toBundle());
    }

    private void send(String action, long id) {
        context.sendBroadcast(new Intent(BuildConfig.APPLICATION_ID + "." + action)
                .setPackage(context.getPackageName()).putExtra(RearScreenChargingActivity.EXTRA_REQUEST_ID, id));
    }

    private static class AttachedService extends ChargingService {
        AttachedService() { attachBaseContext(context); }
    }

    private void prepareService(ITaskService fake) throws Exception {
        service = new AttachedService();
        set(service, "prefs", prefs);
        set(service, "taskService", fake);
        set(null, ChargingService.class, "instance", service);
    }

    private ITaskService fakeService(Runnable beforeStop, boolean success) {
        return (ITaskService) Proxy.newProxyInstance(ITaskService.class.getClassLoader(),
                new Class<?>[]{ITaskService.class}, (proxy, method, args) -> {
                    shellOnMain |= Looper.myLooper() == Looper.getMainLooper();
                    switch (method.getName()) {
                        case "disableSubScreenLauncher": if (beforeStop != null) beforeStop.run(); return true;
                        case "enableSubScreenLauncher": restores.incrementAndGet(); return true;
                        case "executeShellCommand":
                            String command = (String) args[0];
                            if (command.contains("KEYCODE_WAKEUP")) wakeups.incrementAndGet();
                            if (command.startsWith("am start")) { launchCommand = command; starts.incrementAndGet(); }
                            return success;
                        case "executeShellCommandWithResult":
                            if ("am stack list".equals(args[0])) return "  taskId=99: " + context.getPackageName()
                                    + "/" + RearScreenChargingActivity.class.getName();
                            return "";
                        case "isTaskOnDisplay": return success;
                        case "moveTaskToDisplay": moves.incrementAndGet(); return success;
                        case "getForegroundAppOnDisplay": return null;
                        case "asBinder": return new android.os.Binder();
                        default: return null;
                    }
                });
    }

    private void power(String action) throws Exception {
        BroadcastReceiver receiver = (BroadcastReceiver) get(service, "batteryReceiver");
        instrumentation.runOnMainSync(() -> receiver.onReceive(context, new Intent(action)));
    }

    private void invokePending() {
        instrumentation.runOnMainSync(() -> {
            try {
                java.lang.reflect.Method method = ChargingService.class.getDeclaredMethod("executePending");
                method.setAccessible(true); method.invoke(service);
            } catch (Exception e) { throw new AssertionError(e); }
        });
    }

    private void await(BooleanSupplier check, long timeout) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + timeout;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            final boolean[] done = {false};
            instrumentation.runOnMainSync(() -> done[0] = check.getAsBoolean());
            if (done[0]) return;
            Thread.sleep(30);
        }
        fail("Timed out waiting for charging lifecycle");
    }

    private static Object get(Object target, String name) throws Exception {
        Class<?> owner = target instanceof ChargingService ? ChargingService.class : target.getClass();
        Field field = owner.getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        set(target, target instanceof ChargingService ? ChargingService.class : target.getClass(), name, value);
    }
    private static void set(Object target, Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static void shell(String command) throws Exception {
        try (ParcelFileDescriptor descriptor = instrumentation.getUiAutomation().executeShellCommand(command);
             FileInputStream input = new FileInputStream(descriptor.getFileDescriptor())) {
            byte[] buffer = new byte[4096]; while (input.read(buffer) != -1) { /* drain */ }
        }
    }
}
