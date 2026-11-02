package top.zainzh.rearscreen;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import org.json.JSONObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Self instrumentation survives full R8 optimization without AndroidX Test class-loader rules. */
public final class RootIpcProbeInstrumentation extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { super.onCreate(args); arguments = args; start(); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private void progress(String value) { Bundle data = new Bundle(); data.putString("stream", value + "\n"); sendStatus(1, data); }
    private String shell(String command) throws Exception {
        try (java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(command));
             java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return bytes.toString("UTF-8");
        }
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        int resultCode = android.app.Activity.RESULT_CANCELED;
        Context context = getTargetContext();
        AtomicReference<ITaskService> api = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        ServiceConnection owner = new ServiceConnection() {
            public void onServiceConnected(ComponentName name, IBinder binder) { api.set(ITaskService.Stub.asInterface(binder)); ready.countDown(); }
            public void onServiceDisconnected(ComponentName name) { api.set(null); }
        };
        try {
            check(context.getPackageName().endsWith(".rootdebug"), "Only the isolated Root validation package is allowed");
            check(!context.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked(), "Unlock the phone before validation");
            shell("am start --display 0 -n " + context.getPackageName() + "/top.zainzh.rearscreen.RootProbeHostActivity");
            runOnMainSync(() -> {
                PrivilegeMode.ROOT.save(context);
                context.getSharedPreferences("mrss_settings", 0).edit().putBoolean("native_notification_prompted", true)
                        .putBoolean("charging_animation_enabled", false).putBoolean("notification_service_enabled", false).apply();
                TaskServiceManager.acquire(context, owner); TaskServiceManager.requestConnection(context);
            });
            check(ready.await(40, TimeUnit.SECONDS), "Root bind timeout: " + TaskServiceManager.diagnostics());
            ITaskService service = api.get();
            check(service != null && service.asBinder().queryLocalInterface("top.zainzh.rearscreen.ITaskService") == null, "Expected a real remote Binder");
            check(android.os.Process.myUid() >= 10000, "App lost its ordinary identity");
            check(new JSONObject(service.getCompatibilityDiagnostics()).getInt("serviceUid") == 0, "Remote UID is not root");
            int[] metadata = new int[3];
            try (ParcelFileDescriptor fd = service.captureRearScreenshot(metadata)) {
                check(fd != null, "Missing screenshot FD");
                Bitmap image = BitmapFactory.decodeFileDescriptor(fd.getFileDescriptor());
                check(image != null && image.getWidth() == metadata[1] && image.getHeight() == metadata[2], "Invalid screenshot PNG/metadata");
                image.recycle();
            }
            progress("ROOT_R8_IPC_OK uid=0 ordinaryApp=true remoteBinder=true screenshotFd=true");
            foreignCaller(context, service);
            recording(context, service, "ROOT_R8");
            casting(context, service, "ROOT_R8");
            if (arguments != null && "true".equals(arguments.getString("switch"))) switchBackends(context, api);
            result.putString("stream", "ROOT_R8_OK\n"); resultCode = android.app.Activity.RESULT_OK;
        } catch (Throwable failure) {
            result.putString("stream", android.util.Log.getStackTraceString(failure));
        } finally {
            runOnMainSync(() -> {
                if (VirtualRearHostActivity.instance != null) VirtualRearHostActivity.instance.finish();
                if (FixtureInputActivity.instance != null) FixtureInputActivity.instance.finish();
                TaskServiceManager.release(owner);
            });
        }
        finish(resultCode, result);
    }
    private void foreignCaller(Context context, ITaskService service) throws Exception {
        String helper = context.getPackageName() + ".test/top.zainzh.rearscreen.RootForeignCallerService";
        check(!shell("am start-foreground-service -n " + helper).contains("Error"), "Install the companion test APK first");
        CountDownLatch connected = new CountDownLatch(1); AtomicReference<IBinder> foreign = new AtomicReference<>();
        ServiceConnection owner = new ServiceConnection() {
            public void onServiceConnected(ComponentName name, IBinder binder) { foreign.set(binder); connected.countDown(); }
            public void onServiceDisconnected(ComponentName name) { foreign.set(null); }
        };
        boolean[] bound = {false};
        try {
            runOnMainSync(() -> bound[0] = context.bindService(new android.content.Intent().setComponent(new ComponentName(
                    context.getPackageName() + ".test", "top.zainzh.rearscreen.RootForeignCallerService")), owner, Context.BIND_AUTO_CREATE));
            check(bound[0] && connected.await(8, TimeUnit.SECONDS), "Foreign UID helper unavailable");
            android.os.Parcel data = android.os.Parcel.obtain(), reply = android.os.Parcel.obtain();
            try {
                data.writeStrongBinder(service.asBinder());
                check(foreign.get().transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0), "Foreign helper transaction failed");
                reply.readException();
                check(reply.readInt() == 1, "Foreign UID was not rejected by the Root owner check");
                check(reply.readInt() != android.os.Process.myUid(), "Helper must have another UID");
            } finally { data.recycle(); reply.recycle(); }
            progress("ROOT_R8_SECURITY_OK foreignUidRejected=true");
        } finally {
            if (bound[0]) runOnMainSync(() -> context.unbindService(owner));
            shell("am stopservice -n " + helper);
        }
    }
    private void recording(Context context, ITaskService service, String label) throws Exception {
        String video = "/storage/emulated/0/Movies/ReMRSS_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ROOT).format(new java.util.Date())
                + "_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8) + ".mp4";
        java.io.File local = new java.io.File(context.getCacheDir(), "root-r8-recording.mp4"); int pid = -1;
        String log = "/data/local/tmp/" + context.getPackageName() + "-record-probe-" + java.util.UUID.randomUUID() + ".log";
        try {
            int rear = new JSONObject(service.getRearDisplayState()).getInt("selectedId");
            check(service.executeShellCommand("input -d " + rear + " keyevent KEYCODE_WAKEUP"), "Rear wakeup failed");
            Thread.sleep(300);
            check(service.executeShellCommand("mkdir -p /storage/emulated/0/Movies"), "Movies directory unavailable");
            pid = Integer.parseInt(service.executeShellCommandWithResult("/system/bin/screenrecord --verbose --display-id " + service.getRearPhysicalDisplayId()
                    + " --time-limit 5 " + PrivilegedShellArguments.quote(video) + " >" + PrivilegedShellArguments.quote(log) + " 2>&1 & echo $!").trim());
            Thread.sleep(1200);
            check(service.executeShellCommand(PrivilegedShellArguments.stopRecording(pid, video, 2)), "Owned recording failed to stop");
            long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                if (!service.executeShellCommandWithResult("tr '\\000' '\\n' < /proc/" + pid + "/cmdline 2>/dev/null | grep -Fqx -- "
                        + PrivilegedShellArguments.quote(video) + " && echo running || echo stopped").trim().equals("running")) break;
                Thread.sleep(100);
            }
            RootRecordingValidation.copyVideo(service, video, local);
            android.media.MediaExtractor extractor = new android.media.MediaExtractor();
            try {
                try { extractor.setDataSource(local.getPath()); }
                catch (java.io.IOException invalid) {
                    throw new java.io.IOException("Recording invalid; copied=" + local.length() + "; file="
                            + service.executeShellCommandWithResult("stat -c '%s %u %a' " + PrivilegedShellArguments.quote(video)).trim()
                            + "; log=" + service.executeShellCommandWithResult("cat " + PrivilegedShellArguments.quote(log)), invalid);
                }
                check(extractor.getTrackCount() > 0 && extractor.getTrackFormat(0).getString(android.media.MediaFormat.KEY_MIME).startsWith("video/"), "No recorded video track");
            } finally { extractor.release(); }
            progress(label + "_RECORD_OK videoTrack=true guardedStop=true");
        } finally {
            if (pid > 0) service.executeShellCommand(PrivilegedShellArguments.stopRecording(pid, video, 15));
            service.executeShellCommand("rm -f " + PrivilegedShellArguments.quote(video) + " " + PrivilegedShellArguments.quote(log)); local.delete();
        }
    }
    private void casting(Context context, ITaskService service, String label) throws Exception {
        int rear = new JSONObject(service.getRearDisplayState()).getInt("selectedId");
        check(rear > 0, "Rear display unavailable");
        String occupied = service.getForegroundAppOnDisplay(rear);
        check(occupied == null || occupied.startsWith(context.getPackageName() + ":") || occupied.startsWith("com.xiaomi.subscreencenter:")
                || occupied.startsWith("com.xiaomi.mirror:"), "Another app owns the rear display: " + occupied);
        shell("am start --display 0 -n " + context.getPackageName() + "/top.zainzh.rearscreen.FixtureInputActivity");
        long deadline = android.os.SystemClock.elapsedRealtime() + 8000;
        java.util.concurrent.atomic.AtomicInteger fixtureTask = new java.util.concurrent.atomic.AtomicInteger(-1);
        while (fixtureTask.get() < 0 && android.os.SystemClock.elapsedRealtime() < deadline) {
            runOnMainSync(() -> { if (FixtureInputActivity.instance != null) fixtureTask.set(FixtureInputActivity.instance.getTaskId()); });
            Thread.sleep(100);
        }
        check(fixtureTask.get() >= 0, "Fixture unavailable");
        int task = fixtureTask.get(); String session = null;
        try {
            check(service.moveTaskToDisplay(task, rear) && service.isTaskOnDisplay(task, rear), "Physical cast failed");
            check(service.moveTaskToDisplay(task, 0), "Physical return failed");
            service.executeShellCommand("input -d " + rear + " keyevent KEYCODE_WAKEUP");
            check(service.executeShellCommand("am start --display " + rear + " -n " + context.getPackageName()
                    + "/top.zainzh.rearscreen.VirtualRearHostActivity --ei task " + task + " --ei dpi 450"), "Virtual host launch failed");
            JSONObject status = null; deadline = android.os.SystemClock.elapsedRealtime() + 15000;
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                JSONObject value = new JSONObject(service.getRearVirtualCastStatus());
                if ("running".equals(value.optString("state"))) { status = value; session = value.getString("id"); break; }
                Thread.sleep(200);
            }
            check(status != null, "Virtual cast failed: " + service.getRearVirtualCastStatus());
            int display = status.getInt("display"); check(service.isTaskOnDisplay(task, display), "Task missing from virtual display");
            check("running".equals(new JSONObject(service.getRearInputProxyStatus()).optString("state")), "Input proxy unavailable");
            runOnMainSync(() -> FixtureInputActivity.instance.resetCounters());
            JSONObject viewport = status.getJSONObject("viewport");
            check(service.executeShellCommand("input -d " + display + " tap " + viewport.optInt("bufferWidth", 500) / 2 + " "
                    + viewport.optInt("bufferHeight", 700) / 2), "Touch injection failed"); Thread.sleep(400);
            java.util.concurrent.atomic.AtomicInteger ups = new java.util.concurrent.atomic.AtomicInteger();
            runOnMainSync(() -> ups.set(FixtureInputActivity.instance.ups));
            check(ups.get() > 0, "Fixture received no touch");
        } finally {
            service.stopRearVirtualCast(session);
            if (!service.isTaskOnDisplay(task, 0)) service.moveTaskToDisplay(task, 0);
        }
        check(service.isTaskOnDisplay(task, 0), "Source task was not restored");
        check("stopped".equals(new JSONObject(service.getRearInputProxyStatus()).optString("state")), "Input remained active");
        check("2000 700".equals(service.executeShellCommandWithResult("stat -c '%u %a' /data/local/tmp/remrss-freeze-guard-v1").trim()), "Wrong recovery directory owner");
        runOnMainSync(() -> { if (VirtualRearHostActivity.instance != null) VirtualRearHostActivity.instance.finish(); FixtureInputActivity.instance.finish(); });
        Thread.sleep(500);
        progress(label + "_CAST_OK physical=true virtual=true touch=true restored=true shellOwner=true");
    }
    private void switchBackends(Context context, AtomicReference<ITaskService> api) throws Exception {
        check(rikka.shizuku.Shizuku.pingBinder() && rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED,
                "Authorize this validation app in Shizuku first");
        for (PrivilegeMode mode : new PrivilegeMode[]{PrivilegeMode.SHIZUKU, PrivilegeMode.ROOT}) {
            CountDownLatch switched = new CountDownLatch(1); AtomicReference<String> error = new AtomicReference<>();
            runOnMainSync(() -> TaskServiceManager.switchMode(context, mode, value -> { error.set(value); switched.countDown(); }));
            check(switched.await(10, TimeUnit.SECONDS) && error.get() == null, "Switch failed: " + error.get());
            long deadline = android.os.SystemClock.elapsedRealtime() + 15000;
            while (api.get() == null && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
            check(api.get() != null, "Selected backend unavailable");
            int uid = new JSONObject(api.get().getCompatibilityDiagnostics()).getInt("serviceUid");
            check(mode == PrivilegeMode.ROOT ? uid == 0 : uid == 0 || uid == 2000, "Unexpected backend identity");
            if (mode == PrivilegeMode.SHIZUKU) {
                recording(context, api.get(), "SHIZUKU_R8");
                casting(context, api.get(), "SHIZUKU_R8");
            }
        }
        progress("ROOT_R8_SWITCH_OK Shizuku=true RootRestored=true");
    }
}
