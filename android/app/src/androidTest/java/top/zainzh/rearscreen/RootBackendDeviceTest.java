package top.zainzh.rearscreen;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Requires an explicitly authorized, separate root validation package. Never grants root via ADB. */
@RunWith(AndroidJUnit4.class)
public class RootBackendDeviceTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }

    private String shell(String command) throws Exception {
        try (java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command));
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return bytes.toString("UTF-8");
        }
    }
    private void warmHost() throws Exception {
        shell("am start --display 0 -n " + context.getPackageName() + "/top.zainzh.rearscreen.RootProbeHostActivity");
        Thread.sleep(400);
    }

    @Test public void rootServiceUsesRealRemoteBinderAndScreenshotFd() throws Exception {
        warmHost();
        main(() -> {
            PrivilegeMode.ROOT.save(context);
            context.getSharedPreferences("mrss_settings", 0).edit().putBoolean("native_notification_prompted", true)
                    .putBoolean("charging_animation_enabled", false).putBoolean("notification_service_enabled", false).apply();
        });
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<ITaskService> remote = new AtomicReference<>();
        ServiceConnection owner = new ServiceConnection() {
            public void onServiceConnected(ComponentName name, IBinder binder) {
                remote.set(ITaskService.Stub.asInterface(binder)); ready.countDown();
            }
            public void onServiceDisconnected(ComponentName name) { remote.set(null); }
        };
        try {
            main(() -> { TaskServiceManager.acquire(context, owner); TaskServiceManager.requestConnection(context); });
            assertTrue("Authorize the installed validation app in the root manager. " + TaskServiceManager.diagnostics(),
                    ready.await(40, TimeUnit.SECONDS));
            ITaskService service = remote.get();
            assertNotNull(service);
            assertNull("The Binder must cross a process boundary", service.asBinder().queryLocalInterface("top.zainzh.rearscreen.ITaskService"));
            JSONObject report = new JSONObject(service.getCompatibilityDiagnostics());
            assertEquals(0, report.getInt("serviceUid"));
            assertEquals(context.getPackageName(), report.getString("package"));
            assertTrue("App must retain its ordinary UID", android.os.Process.myUid() >= 10000);
            assertTrue(new JSONObject(service.getRearDisplayState()).getInt("selectedId") > 0);
            int[] metadata = new int[3];
            try (ParcelFileDescriptor fd = service.captureRearScreenshot(metadata)) {
                assertNotNull(fd);
                Bitmap image = BitmapFactory.decodeFileDescriptor(fd.getFileDescriptor());
                assertNotNull(image);
                assertEquals(metadata[1], image.getWidth()); assertEquals(metadata[2], image.getHeight()); image.recycle();
            }
            String video = "/storage/emulated/0/Movies/ReMRSS_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ROOT).format(new java.util.Date())
                    + "_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8) + ".mp4";
            int recording = -1;
            java.io.File localVideo = new java.io.File(context.getCacheDir(), "root-recording-validation.mp4");
            try {
                int rear = new JSONObject(service.getRearDisplayState()).getInt("selectedId");
                assertTrue(service.executeShellCommand("input -d " + rear + " keyevent KEYCODE_WAKEUP"));
                Thread.sleep(300);
                assertTrue(service.executeShellCommand("mkdir -p /storage/emulated/0/Movies"));
                recording = Integer.parseInt(service.executeShellCommandWithResult("/system/bin/screenrecord --display-id "
                        + service.getRearPhysicalDisplayId() + " --time-limit 5 " + PrivilegedShellArguments.quote(video)
                        + " >/dev/null 2>&1 & echo $!").trim());
                Thread.sleep(1200);
                assertTrue("Owned screenrecord did not accept SIGINT", service.executeShellCommand(PrivilegedShellArguments.stopRecording(recording, video, 2)));
                long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    if (!service.executeShellCommandWithResult("tr '\\000' '\\n' < /proc/" + recording + "/cmdline 2>/dev/null | grep -Fqx -- "
                            + PrivilegedShellArguments.quote(video) + " && echo running || echo stopped").trim().equals("running")) break;
                    Thread.sleep(100);
                }
                RootRecordingValidation.copyVideo(service, video, localVideo);
                android.media.MediaExtractor extractor = new android.media.MediaExtractor();
                try {
                    extractor.setDataSource(localVideo.getPath());
                    assertTrue("Recording has no video track", extractor.getTrackCount() > 0);
                    assertTrue(extractor.getTrackFormat(0).getString(android.media.MediaFormat.KEY_MIME).startsWith("video/"));
                } finally { extractor.release(); }
            } finally {
                if (recording > 0) service.executeShellCommand(PrivilegedShellArguments.stopRecording(recording, video, 15));
                service.executeShellCommand("rm -f " + PrivilegedShellArguments.quote(video));
                localVideo.delete();
            }
            assertEquals("0", service.executeShellCommandWithResult("id -u").trim());
            // Explicit test setup starts the helper under its own UID, without changing MIUI auto-start policy.
            shell("am start-foreground-service -n " + context.getPackageName() + ".test/top.zainzh.rearscreen.RootForeignCallerService");
            CountDownLatch foreignConnected = new CountDownLatch(1);
            AtomicReference<IBinder> foreign = new AtomicReference<>();
            ServiceConnection foreignOwner = new ServiceConnection() {
                public void onServiceConnected(ComponentName name, IBinder binder) { foreign.set(binder); foreignConnected.countDown(); }
                public void onServiceDisconnected(ComponentName name) { foreign.set(null); }
            };
            main(() -> assertTrue(context.bindService(new android.content.Intent().setComponent(new ComponentName(context.getPackageName() + ".test",
                    "top.zainzh.rearscreen.RootForeignCallerService")), foreignOwner, Context.BIND_AUTO_CREATE)));
            try {
                assertTrue("Foreign service did not connect", foreignConnected.await(8, TimeUnit.SECONDS));
                android.os.Parcel data = android.os.Parcel.obtain(), reply = android.os.Parcel.obtain();
                try {
                    data.writeStrongBinder(service.asBinder());
                    assertTrue(foreign.get().transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0));
                    reply.readException();
                    assertEquals("Foreign UID was not rejected by the owner check", 1, reply.readInt());
                    assertNotEquals("Probe must run under another UID", android.os.Process.myUid(), reply.readInt());
                } finally { data.recycle(); reply.recycle(); }
            } finally { main(() -> context.unbindService(foreignOwner)); }
            assertTrue(TaskServiceManager.isRoot(context));
            System.out.println("ROOT_IPC_OK mode=ROOT uid=0 realBinder=true fd=true package=" + context.getPackageName());
        } finally {
            main(() -> TaskServiceManager.release(owner));
            shell("am stopservice -n " + context.getPackageName() + ".test/top.zainzh.rearscreen.RootForeignCallerService");
        }
    }

    @Test public void rootMovesFixtureCastsTouchesAndRestores() throws Exception {
        warmHost();
        main(() -> {
            PrivilegeMode.ROOT.save(context);
            context.getSharedPreferences("mrss_settings", 0).edit().putBoolean("native_notification_prompted", true)
                    .putBoolean("charging_animation_enabled", false).putBoolean("notification_service_enabled", false).apply();
        });
        {
            CountDownLatch connected = new CountDownLatch(1);
            AtomicReference<ITaskService> api = new AtomicReference<>();
            ServiceConnection owner = new ServiceConnection() {
                public void onServiceConnected(ComponentName name, IBinder binder) { api.set(ITaskService.Stub.asInterface(binder)); connected.countDown(); }
                public void onServiceDisconnected(ComponentName name) { api.set(null); }
            };
            try {
                main(() -> { TaskServiceManager.acquire(context, owner); TaskServiceManager.requestConnection(context); });
                assertTrue("Root connection timeout: " + TaskServiceManager.diagnostics(), connected.await(40, TimeUnit.SECONDS));
                ITaskService service = api.get();
                int rear = new JSONObject(service.getRearDisplayState()).getInt("selectedId");
                String occupied = service.getForegroundAppOnDisplay(rear);
                org.junit.Assume.assumeTrue("Another task owns the physical rear display: " + occupied,
                        occupied == null || occupied.startsWith("com.xiaomi.subscreencenter:") || occupied.startsWith("com.xiaomi.mirror:")
                                || occupied.startsWith(context.getPackageName() + ":"));
                shell("am start --display 0 -n " + context.getPackageName() + "/top.zainzh.rearscreen.FixtureInputActivity");
                java.util.concurrent.atomic.AtomicInteger task = new java.util.concurrent.atomic.AtomicInteger(-1);
                long fixtureDeadline = android.os.SystemClock.elapsedRealtime() + 8000;
                while (task.get() < 0 && android.os.SystemClock.elapsedRealtime() < fixtureDeadline) {
                    main(() -> { if (FixtureInputActivity.instance != null) task.set(FixtureInputActivity.instance.getTaskId()); });
                    Thread.sleep(100);
                }
                assertTrue("Fixture activity unavailable", task.get() >= 0);
                try {
                    try {
                        assertTrue(service.moveTaskToDisplay(task.get(), rear));
                        assertTrue(service.isTaskOnDisplay(task.get(), rear));
                    } finally { assertTrue(service.moveTaskToDisplay(task.get(), 0)); }
                    assertTrue(service.isTaskOnDisplay(task.get(), 0));
                    assertTrue(service.executeShellCommand("input -d " + rear + " keyevent KEYCODE_WAKEUP"));
                    String component = context.getPackageName() + "/top.zainzh.rearscreen.VirtualRearHostActivity";
                    assertTrue(service.executeShellCommand("am start --display " + rear + " -n " + component
                            + " --ei task " + task.get() + " --ei dpi 450"));
                    JSONObject running = null;
                    long deadline = android.os.SystemClock.elapsedRealtime() + 15000;
                    while (android.os.SystemClock.elapsedRealtime() < deadline) {
                        JSONObject status = new JSONObject(service.getRearVirtualCastStatus());
                        if ("running".equals(status.optString("state"))) { running = status; break; }
                        Thread.sleep(200);
                    }
                    assertNotNull("Virtual cast did not start: " + service.getCompatibilityDiagnostics(), running);
                    int virtual = running.getInt("display");
                    try {
                        assertTrue(virtual > 0 && virtual != rear);
                        assertTrue(service.isTaskOnDisplay(task.get(), virtual));
                        assertEquals("running", new JSONObject(service.getRearInputProxyStatus()).getString("state"));
                        CountDownLatch switched = new CountDownLatch(1);
                        AtomicReference<String> result = new AtomicReference<>();
                        main(() -> TaskServiceManager.switchMode(context, PrivilegeMode.SHIZUKU, error -> { result.set(error); switched.countDown(); }));
                        assertTrue(switched.await(5, TimeUnit.SECONDS));
                        assertEquals("busy", result.get());
                        assertTrue(TaskServiceManager.isRoot(context));
                        main(() -> FixtureInputActivity.instance.resetCounters());
                        JSONObject viewport = running.getJSONObject("viewport");
                        int x = viewport.optInt("bufferWidth", 500) / 2, y = viewport.optInt("bufferHeight", 700) / 2;
                        assertTrue(service.executeShellCommand("input -d " + virtual + " tap " + x + " " + y));
                        Thread.sleep(400);
                        java.util.concurrent.atomic.AtomicInteger ups = new java.util.concurrent.atomic.AtomicInteger();
                        main(() -> ups.set(FixtureInputActivity.instance.ups));
                        assertTrue("Fixture did not receive the injected touch", ups.get() > 0);
                    } finally {
                        service.stopRearVirtualCast(running.getString("id"));
                        main(() -> { if (VirtualRearHostActivity.instance != null) VirtualRearHostActivity.instance.finish(); });
                    }
                    assertTrue("Source task was not restored", service.isTaskOnDisplay(task.get(), 0));
                    assertEquals("stopped", new JSONObject(service.getRearVirtualCastStatus()).getString("state"));
                    assertEquals("stopped", new JSONObject(service.getRearInputProxyStatus()).getString("state"));
                    String directory = service.executeShellCommandWithResult("stat -c '%u %a' /data/local/tmp/remrss-freeze-guard-v1").trim();
                    assertEquals("2000 700", directory);
                    System.out.println("ROOT_CAST_OK physicalMove=true virtual=true input=true restored=true shellOwner=true");
                } finally { main(() -> { if (FixtureInputActivity.instance != null) FixtureInputActivity.instance.finish(); }); }
            } finally { main(() -> {
                if (VirtualRearHostActivity.instance != null) VirtualRearHostActivity.instance.finish();
                TaskServiceManager.release(owner);
            }); }
        }
    }
    @Test public void shizukuAndRootSwitchWithoutSharedRecoveryConflicts() throws Exception {
        warmHost(); main(() -> PrivilegeMode.ROOT.save(context));
        CountDownLatch rootReady = new CountDownLatch(1), shizukuReady = new CountDownLatch(1), restored = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger rootConnections = new java.util.concurrent.atomic.AtomicInteger();
        AtomicReference<ITaskService> api = new AtomicReference<>();
        ServiceConnection owner = new ServiceConnection() {
            public void onServiceConnected(ComponentName name, IBinder binder) {
                api.set(ITaskService.Stub.asInterface(binder));
                if (TaskServiceManager.isRoot(context)) { if (rootConnections.incrementAndGet() == 1) rootReady.countDown(); else restored.countDown(); }
                else shizukuReady.countDown();
            }
            public void onServiceDisconnected(ComponentName name) { api.set(null); }
        };
        try {
            main(() -> { TaskServiceManager.acquire(context, owner); TaskServiceManager.requestConnection(context); });
            assertTrue(rootReady.await(30, TimeUnit.SECONDS));
            assertTrue(rikka.shizuku.Shizuku.pingBinder());
            CountDownLatch switched = new CountDownLatch(1); AtomicReference<String> error = new AtomicReference<>();
            main(() -> TaskServiceManager.switchMode(context, PrivilegeMode.SHIZUKU, result -> { error.set(result); switched.countDown(); }));
            assertTrue(switched.await(10, TimeUnit.SECONDS)); assertNull(error.get());
            main(() -> { if (rikka.shizuku.Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) rikka.shizuku.Shizuku.requestPermission(992); });
            assertTrue("Allow the validation app in the Shizuku permission dialog", shizukuReady.await(50, TimeUnit.SECONDS));
            JSONObject report = new JSONObject(api.get().getCompatibilityDiagnostics());
            assertTrue(report.getInt("serviceUid") == 0 || report.getInt("serviceUid") == 2000);
            assertTrue(new JSONObject(api.get().getRearDisplayState()).getInt("selectedId") > 0);
            CountDownLatch back = new CountDownLatch(1);
            main(() -> TaskServiceManager.switchMode(context, PrivilegeMode.ROOT, result -> { error.set(result); back.countDown(); }));
            assertTrue(back.await(10, TimeUnit.SECONDS)); assertNull(error.get());
            assertTrue(restored.await(15, TimeUnit.SECONDS));
            assertEquals(0, new JSONObject(api.get().getCompatibilityDiagnostics()).getInt("serviceUid"));
            System.out.println("DUAL_BACKEND_OK Shizuku service UID=" + report.getInt("serviceUid") + " Root restored=true");
        } finally {
            if (!TaskServiceManager.isRoot(context)) {
                CountDownLatch back = new CountDownLatch(1);
                main(() -> TaskServiceManager.switchMode(context, PrivilegeMode.ROOT, result -> back.countDown()));
                back.await(10, TimeUnit.SECONDS);
            }
            main(() -> TaskServiceManager.release(owner));
        }
    }

}
