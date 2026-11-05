package top.zainzh.rearscreen;

import android.app.ActivityOptions;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RearAnimationHandoffTest {
    private static final Instrumentation inst = InstrumentationRegistry.getInstrumentation();
    private static final Context context = inst.getTargetContext();
    private static ImageReader images;
    private static VirtualDisplay display;
    private static RearDisplayTestIdentity fixtureIdentity;
    private AttachedNotificationService service;
    private RearScreenNotificationActivity activity;
    private SharedPreferences prefs;
    private Map<String, ?> saved;
    private final AtomicInteger starts = new AtomicInteger(), restores = new AtomicInteger(), foregroundReads = new AtomicInteger();
    private final List<String> commands = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean covered, ipcOnMain;
    private Runnable beforeWake;
    private CountDownLatch resume;

    @BeforeClass public static void prepareDisplay() throws Exception {
        inst.getUiAutomation().adoptShellPermissionIdentity();
        if (RearDisplay.id() < 1) fixtureIdentity = new RearDisplayTestIdentity(1);
        images = ImageReader.newInstance(976, 596, PixelFormat.RGBA_8888, 3);
        images.setOnImageAvailableListener(reader -> { Image image = reader.acquireLatestImage(); if (image != null) image.close(); }, new Handler(Looper.getMainLooper()));
        display = context.getSystemService(DisplayManager.class).createVirtualDisplay("notification-regression", 976, 596, 450,
                images.getSurface(), DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
        assertNotNull(display);
    }
    @AfterClass public static void releaseDisplay() throws Exception {
        display.release(); images.close();
        if (fixtureIdentity != null) fixtureIdentity.close();
        inst.getUiAutomation().dropShellPermissionIdentity();
    }
    @Before public void prepare() throws Exception {
        RearAnimationManager.endAnimation(RearAnimationManager.getCurrentAnimation());
        RearAnimationManager.clearChargingAlwaysOnFlag();
        prefs = context.getSharedPreferences("mrss_settings", 0); saved = prefs.getAll();
        prefs.edit().putInt("notification_duration", 10).putBoolean("charging_always_on_enabled", false).commit();
        service = new AttachedNotificationService();
        set("prefs", prefs); set("taskService", fake());
    }
    @After public void cleanup() throws Exception {
        if (resume != null) resume.countDown();
        if (activity != null) { inst.runOnMainSync(activity::finish); await(activity::isDestroyed, 3000); }
        set("taskServiceReleased", true);
        ExecutorService worker = (ExecutorService) get("animationWorker"); worker.shutdown(); assertTrue(worker.awaitTermination(6, TimeUnit.SECONDS));
        RearAnimationManager.endAnimation(RearAnimationManager.getCurrentAnimation());
        RearAnimationManager.clearChargingAlwaysOnFlag();
        SharedPreferences.Editor edit = prefs.edit().clear();
        for (Map.Entry<String, ?> item : saved.entrySet()) {
            String key = item.getKey(); Object value = item.getValue();
            if (value instanceof Boolean) edit.putBoolean(key, (Boolean)value);
            else if (value instanceof Integer) edit.putInt(key, (Integer)value);
            else if (value instanceof Long) edit.putLong(key, (Long)value);
            else if (value instanceof Float) edit.putFloat(key, (Float)value);
            else if (value instanceof String) edit.putString(key, (String)value);
            else if (value instanceof Set) edit.putStringSet(key, (Set<String>)value);
        }
        edit.commit();
        if (getStatic("instance") == service) setStatic("instance", null);
    }
    @Test public void notificationWakesBeforeLaunchWithoutStoppingOfficialProcess() throws Exception {
        inst.runOnMainSync(() -> service.showNotificationOnRearScreen("android", "Title", "Content", 1));
        await(() -> foregroundReads.get() > 1, 3000);
        assertEquals(1, starts.get()); assertEquals(0, restores.get()); assertFalse(ipcOnMain);
        assertTrue(commands.get(0).contains("KEYCODE_WAKEUP"));
        assertTrue(commands.get(1).startsWith("am start --display 1"));
    }
    @Test public void taskLocatedOnRearButCoveredByHomeIsNotReportedSuccessful() throws Exception {
        covered = true;
        inst.runOnMainSync(() -> service.showNotificationOnRearScreen("android", "Title", "Content", 1));
        await(() -> restores.get() == 1, 4000);
        assertEquals(RearAnimationManager.AnimationType.NONE, RearAnimationManager.getCurrentAnimation());
        assertTrue(foregroundReads.get() > 2);
    }
    @Test public void chargingSupersedesPendingNotificationWithoutLateLaunchOrRestore() throws Exception {
        CountDownLatch entered = new CountDownLatch(1); resume = new CountDownLatch(1);
        beforeWake = () -> { entered.countDown(); try { assertTrue(resume.await(4, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new AssertionError(e); } };
        inst.runOnMainSync(() -> service.showNotificationOnRearScreen("android", "Old", "Content", 1));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        RearAnimationManager.Session next = RearAnimationManager.beginAnimation(RearAnimationManager.AnimationType.CHARGING);
        resume.countDown(); Thread.sleep(200);
        assertEquals(0, starts.get()); assertEquals(0, restores.get());
        assertTrue(RearAnimationManager.isCurrent(next.type, next.id)); assertFalse(ipcOnMain);
    }
    @Test public void delayedBinderStillStartsLatestNotification() throws Exception {
        set("taskService", null);
        inst.runOnMainSync(() -> service.showNotificationOnRearScreen("android", "Delayed", "Content", 1));
        Thread.sleep(200); assertEquals(0, starts.get()); set("taskService", fake());
        await(() -> starts.get() == 1, 3000);
    }
    @Test public void serviceOffActionEndsNotificationWaitingForBinderBeforeItCanLaunch() throws Exception {
        set("taskService", null);
        setStatic("instance", service);
        inst.runOnMainSync(() -> service.showNotificationOnRearScreen("android", "Pending", "Content", 1));
        await(() -> RearAnimationManager.getCurrentAnimation() == RearAnimationManager.AnimationType.NOTIFICATION, 2000);
        NotificationService.disableForUser(context);
        set("taskService", fake());
        await(() -> RearAnimationManager.getCurrentAnimation() == RearAnimationManager.AnimationType.NONE, 3000);
        assertEquals("disabled pending notification must not start later", 0, starts.get());
    }
    @Test public void serviceOffActionFinishesCurrentNotificationActivity() throws Exception {
        RearAnimationManager.Session current = RearAnimationManager.beginAnimation(RearAnimationManager.AnimationType.NOTIFICATION);
        setStatic("instance", service);
        activity = launch(current.id, "Visible");
        NotificationService.disableForUser(context);
        await(() -> activity.isDestroyed() && !RearAnimationManager.isCurrent(current.type, current.id), 3000);
        assertTrue("service off must end the displayed notification session", activity.isDestroyed());
    }
    @Test public void staleRestoreCannotRunAfterNewAnimationStartsAndEnds() throws Exception {
        RearAnimationManager.Session old = RearAnimationManager.beginAnimation(RearAnimationManager.AnimationType.NOTIFICATION);
        RearAnimationManager.endAnimation(old.type, old.id);
        RearAnimationManager.Session next = RearAnimationManager.beginAnimation(RearAnimationManager.AnimationType.CHARGING);
        RearAnimationManager.endAnimation(next.type, next.id);
        ChargingService.restoreRearScreen(fake(), -1, old.id);
        assertEquals(0, restores.get());
    }
    @Test public void olderNotificationCannotEndNewNotification() throws Exception {
        RearAnimationManager.Session first = RearAnimationManager.beginAnimation(RearAnimationManager.AnimationType.NOTIFICATION);
        activity = launch(first.id, "Old");
        RearAnimationManager.Session next = RearAnimationManager.beginAnimation(RearAnimationManager.AnimationType.NOTIFICATION);
        inst.runOnMainSync(activity::finish); await(activity::isDestroyed, 3000);
        assertTrue(RearAnimationManager.isCurrent(next.type, next.id));
    }
    @Test public void delayedServiceOffFinishUsesCapturedSessionAndLeavesReplacementAlive() throws Exception {
        RearAnimationManager.Session old = RearAnimationManager.beginAnimation(RearAnimationManager.AnimationType.NOTIFICATION);
        long capturedId = RearAnimationManager.captureSessionId(RearAnimationManager.AnimationType.NOTIFICATION);
        assertEquals(old.id, capturedId);
        RearAnimationManager.Session next = RearAnimationManager.beginAnimation(RearAnimationManager.AnimationType.NOTIFICATION);
        activity = launch(next.id, "Replacement");
        NotificationService.postNotificationFinishForSession(context, new Handler(Looper.getMainLooper()), capturedId);
        Thread.sleep(400);
        assertFalse(activity.isFinishing());
        assertTrue(RearAnimationManager.isCurrent(next.type, next.id));
        inst.runOnMainSync(() -> assertEquals("Replacement", ((TextView)activity.findViewById(R.id.notification_title)).getText().toString()));
    }
    @Test public void staleOffDuringAcceptedNewIntentDoesNotFinishRecreatingActivity() throws Exception {
        RearAnimationManager.Session old = RearAnimationManager.beginAnimation(RearAnimationManager.AnimationType.NOTIFICATION);
        activity = launch(old.id, "Old");
        RearAnimationManager.Session next = RearAnimationManager.beginAnimation(RearAnimationManager.AnimationType.NOTIFICATION);
        Instrumentation.ActivityMonitor monitor = inst.addMonitor(FixtureNotificationActivity.class.getName(), null, false);
        Intent nextIntent = new Intent(context, FixtureNotificationActivity.class)
                .putExtra(RearAnimationManager.EXTRA_SESSION_ID, next.id)
                .putExtra("packageName", "android").putExtra("title", "AcceptedNew")
                .putExtra("text", "Content").putExtra("when", 2L)
                .putExtra("fixtureRearDisplayId", display.getDisplay().getDisplayId());
        Intent staleFinish = new Intent(BuildConfig.APPLICATION_ID + ".FINISH_NOTIFICATION_ANIMATION")
                .putExtra(RearAnimationManager.EXTRA_SESSION_ID, old.id);
        Intent currentFinish = new Intent(BuildConfig.APPLICATION_ID + ".FINISH_NOTIFICATION_ANIMATION")
                .putExtra(RearAnimationManager.EXTRA_SESSION_ID, next.id);
        java.lang.reflect.Field receiverField = RearScreenNotificationActivity.class.getDeclaredField("interruptReceiver");
        receiverField.setAccessible(true);
        inst.runOnMainSync(() -> {
            activity.onNewIntent(nextIntent);
            try { ((android.content.BroadcastReceiver)receiverField.get(activity)).onReceive(context, staleFinish); }
            catch (IllegalAccessException error) { throw new AssertionError(error); }
            assertFalse("old close must not finish after the new intent was accepted", activity.isFinishing());
            try { ((android.content.BroadcastReceiver)receiverField.get(activity)).onReceive(context, currentFinish); }
            catch (IllegalAccessException error) { throw new AssertionError(error); }
            assertFalse("current close is deferred until the replacement instance exists", activity.isFinishing());
        });
        try {
            RearScreenNotificationActivity replacement = (RearScreenNotificationActivity) monitor.waitForActivityWithTimeout(4000);
            assertNotNull("accepted intent must recreate the activity", replacement);
            activity = replacement;
            await(() -> replacement.isDestroyed() && !RearAnimationManager.isCurrent(next.type, next.id), 3000);
            assertFalse("current close during recreation must end the new session", RearAnimationManager.isCurrent(next.type, next.id));
        } finally { inst.removeMonitor(monitor); }
    }
    @Test public void serviceOffClearsOnlyCapturedPassiveNotificationToken() {
        final RearCastNotification.Message[] visible = {null};
        RearCastNotification.Target target = new RearCastNotification.Target() {
            @Override public void show(RearCastNotification.Message message) { visible[0] = message; }
            @Override public void clearNotification(long token) {
                if (visible[0] != null && visible[0].token == token) visible[0] = null;
            }
        };
        RearCastNotification.attach(target);
        try {
            assertTrue(RearCastNotification.offer("android", "Old", "Body", 1, 10, false));
            long oldToken = RearCastNotification.captureNotificationToken();
            assertTrue(RearCastNotification.offer("android", "New", "Body", 2, 10, false));
            RearCastNotification.clearNotification(oldToken);
            assertNotNull("late close must not clear replacement notification", visible[0]);
            long currentToken = RearCastNotification.captureNotificationToken();
            RearCastNotification.offerCharging("android", "Charging", "", 3, false);
            RearCastNotification.clearNotification(currentToken);
            assertNotNull("notification off must not clear a charging capsule", visible[0]);
            assertTrue(visible[0].charging);
        } finally { RearCastNotification.detach(target); }
    }
    @Test public void destroyedOldServiceDoesNotCloseReplacementSessionOrCapsule() throws Exception {
        NotificationService replacementService = new AttachedNotificationService();
        RearAnimationManager.Session replacement = RearAnimationManager.beginAnimation(RearAnimationManager.AnimationType.NOTIFICATION);
        final RearCastNotification.Message[] visible = {null};
        RearCastNotification.Target target = new RearCastNotification.Target() {
            @Override public void show(RearCastNotification.Message message) { visible[0] = message; }
            @Override public void clearNotification(long token) {
                if (visible[0] != null && visible[0].token == token) visible[0] = null;
            }
        };
        RearCastNotification.attach(target);
        RearCastNotification.offer("android", "Replacement", "Body", 1, 10, false);
        setStatic("instance", replacementService);
        try {
            service.closeOwnedDisplaysOnDestroy();
            assertSame(replacementService, getStatic("instance"));
            assertTrue(RearAnimationManager.isCurrent(replacement.type, replacement.id));
            assertNotNull("old service destruction must not clear a newer capsule", visible[0]);
        } finally {
            RearCastNotification.detach(target);
            setStatic("instance", service);
        }
    }
    @Test public void repeatedNotificationRecreatesContentAndOldCloseDoesNotFinishNewWindow() throws Exception {
        prefs.edit().putInt("notification_duration", 1).commit();
        RearAnimationManager.Session first = RearAnimationManager.beginAnimation(RearAnimationManager.AnimationType.NOTIFICATION);
        activity = launch(first.id, "Old"); Thread.sleep(600);
        prefs.edit().putInt("notification_duration", 5).commit();
        RearAnimationManager.Session next = RearAnimationManager.beginAnimation(RearAnimationManager.AnimationType.NOTIFICATION);
        activity = launch(next.id, "New");
        context.sendBroadcast(new Intent(BuildConfig.APPLICATION_ID + ".FINISH_NOTIFICATION_ANIMATION").setPackage(context.getPackageName())
                .putExtra(RearAnimationManager.EXTRA_SESSION_ID, first.id));
        Thread.sleep(700);
        assertFalse(activity.isFinishing()); assertTrue(RearAnimationManager.isCurrent(next.type, next.id));
        inst.runOnMainSync(() -> assertEquals("New", ((TextView)activity.findViewById(R.id.notification_title)).getText().toString()));
    }
    @Test public void notificationPayloadUsesLiteralShellQuoting() throws Exception {
        inst.runOnMainSync(() -> service.showNotificationOnRearScreen("android", "A'$(echo unsafe)`x`", "line\n$HOME", 1));
        await(() -> starts.get() == 1, 3000);
        String command = commands.get(1);
        assertTrue(command, command.contains("'A'\"'\"'$(echo unsafe)`x`'"));
        assertTrue(command, command.contains("'line\n$HOME'"));
    }
    private RearScreenNotificationActivity launch(long id, String title) throws Exception {
        Instrumentation.ActivityMonitor monitor = inst.addMonitor(FixtureNotificationActivity.class.getName(), null, false);
        try {
            ActivityOptions options = ActivityOptions.makeBasic(); options.setLaunchDisplayId(display.getDisplay().getDisplayId());
            context.startActivity(new Intent(context, FixtureNotificationActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("fixtureRearDisplayId", display.getDisplay().getDisplayId()).putExtra("packageName", "android")
                    .putExtra("title", title).putExtra("text", "Content").putExtra(RearAnimationManager.EXTRA_SESSION_ID, id), options.toBundle());
            RearScreenNotificationActivity result = (RearScreenNotificationActivity) monitor.waitForActivityWithTimeout(4000);
            assertNotNull(result); inst.waitForIdleSync(); return result;
        } finally { inst.removeMonitor(monitor); }
    }
    private ITaskService fake() {
        return (ITaskService) Proxy.newProxyInstance(ITaskService.class.getClassLoader(), new Class<?>[]{ITaskService.class}, (proxy, method, args) -> {
            ipcOnMain |= Looper.myLooper() == Looper.getMainLooper();
            switch(method.getName()) {
                case "disableSubScreenLauncher": throw new AssertionError("Must not restart official package");
                case "enableSubScreenLauncher": restores.incrementAndGet(); return true;
                case "executeShellCommand":
                    String command=(String)args[0]; commands.add(command);
                    if (command.contains("KEYCODE_WAKEUP") && beforeWake != null) beforeWake.run();
                    if (command.startsWith("am start")) starts.incrementAndGet(); return true;
                case "executeShellCommandWithResult": return "  taskId=55: " + context.getPackageName() + "/" + RearScreenNotificationActivity.class.getName();
                case "getForegroundAppOnDisplay": foregroundReads.incrementAndGet(); return !covered && starts.get()>0 ? context.getPackageName()+":55" : "com.xiaomi.subscreencenter:2";
                case "isTaskOnDisplay": return true;
                case "moveTaskToDisplay": return true;
                case "asBinder": return new android.os.Binder();
                default: return null;
            }
        });
    }
    private static class AttachedNotificationService extends NotificationService { AttachedNotificationService() { attachBaseContext(context); } }
    private void set(String name,Object value) throws Exception { Field f=NotificationService.class.getDeclaredField(name);f.setAccessible(true);f.set(service,value); }
    private static void setStatic(String name,Object value) throws Exception { Field f=NotificationService.class.getDeclaredField(name);f.setAccessible(true);f.set(null,value); }
    private static Object getStatic(String name) throws Exception { Field f=NotificationService.class.getDeclaredField(name);f.setAccessible(true);return f.get(null); }
    private Object get(String name) throws Exception { Field f=NotificationService.class.getDeclaredField(name);f.setAccessible(true);return f.get(service); }
    private static void await(BooleanSupplier condition,long timeout) throws Exception {
        long end=android.os.SystemClock.elapsedRealtime()+timeout;
        while(android.os.SystemClock.elapsedRealtime()<end) { final boolean[] done={false};inst.runOnMainSync(()->done[0]=condition.getAsBoolean());if(done[0])return;Thread.sleep(30); }
        fail("Timed out waiting for animation handoff");
    }
}
