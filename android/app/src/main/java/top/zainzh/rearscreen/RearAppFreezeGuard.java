package top.zainzh.rearscreen;

import android.util.Log;
import java.io.*;
import java.util.concurrent.*;

/** Session-scoped HyperOS protection, owned by a separate process rather than an App setting. */
final class RearAppFreezeGuard implements AutoCloseable {
    private final java.lang.Process watchdog;
    private final OutputStream lease;
    private boolean closed;
    final String mode;
    private RearAppFreezeGuard(java.lang.Process process, String mode) {
        watchdog = process; lease = process == null ? null : process.getOutputStream(); this.mode = mode;
    }
    static boolean required() throws Exception {
        if (android.os.Build.VERSION.SDK_INT < 33) return false;
        return Class.forName("android.os.ServiceManager").getMethod("checkService", String.class).invoke(null, "greezer") != null;
    }
    static void recoverStale() {
        try {
            if (!required()) return;
            if (android.os.Process.myUid() == 0) {
                RearFreezeDirectory.prepareShellOwner(RearFreezeGuardProcess.DIRECTORY);
                java.lang.Process recovery = shellProcess("--recover").start();
                if (!recovery.waitFor(5, TimeUnit.SECONDS)) { recovery.destroy(); throw new IOException("Recovery timed out"); }
                if (recovery.exitValue() != 0) throw new IOException("Shell recovery failed");
            } else RearFreezeGuardProcess.recover();
        }
        catch (Exception e) { Log.w("RearAppFreezeGuard", "Recovery deferred to the active guard or next cast", e); }
    }
    static RearAppFreezeGuard start(String pkg) throws Exception {
        return startAll(new String[]{pkg});
    }
    static RearAppFreezeGuard startAll(String[] packages) throws Exception {
        if (packages.length < 1 || packages.length > 2) throw new IllegalArgumentException("Invalid freeze package count");
        for (String pkg : packages) RearFreezeState.validatePackage(pkg);
        if (!required()) return new RearAppFreezeGuard(null, "not_needed");
        RearFreezeDirectory.prepareShellOwner(RearFreezeGuardProcess.DIRECTORY);
        ProcessBuilder builder = shellProcess(String.join(",", packages));
        java.lang.Process process = builder.start();
        RearAppFreezeGuard guard = new RearAppFreezeGuard(process, "temporary_whitelist");
        CompletableFuture<Boolean> ready = new CompletableFuture<>();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if ("READY".equals(line)) ready.complete(true);
                    else if (!"RESTORED".equals(line)) Log.w("RearAppFreezeGuard", line);
                }
            } catch (IOException e) { ready.completeExceptionally(e); }
            finally { ready.complete(false); }
        }, "freeze-guard-status");
        reader.setDaemon(true); reader.start();
        try {
            if (!ready.get(10, TimeUnit.SECONDS)) throw new IOException("Freeze guard did not start");
            guard.renew(); return guard;
        } catch (Exception e) { guard.close(); throw new IOException("Lock-screen App protection unavailable", e); }
    }
    private static String quote(String value) { return "'" + value.replace("'", "'\"'\"'") + "'"; }
    private static ProcessBuilder shellProcess(String argument) throws Exception {
        String paths = RearFreezeGuardProcess.command("pm", "path", BuildConfig.APPLICATION_ID);
        String apk = null;
        for (String line : paths.split("\n")) if (line.startsWith("package:") && line.endsWith("/base.apk")) apk = line.substring(8);
        if (apk == null || !new File(apk).isFile()) throw new IOException("Installed cast APK unavailable for freeze recovery");
        ProcessBuilder builder;
        if (android.os.Process.myUid() == 0) {
            String command = "CLASSPATH=" + quote(apk) + " /system/bin/app_process /system/bin --nice-name=remrss-freeze-guard "
                    + RearFreezeGuardProcess.class.getName() + " " + quote(argument);
            builder = new ProcessBuilder("/system/bin/su", "2000", "-c", command);
        } else {
            builder = new ProcessBuilder("/system/bin/app_process", "/system/bin", "--nice-name=remrss-freeze-guard",
                    RearFreezeGuardProcess.class.getName(), argument);
            builder.environment().put("CLASSPATH", apk);
        }
        return builder.redirectErrorStream(true);
    }
    synchronized void renew() throws Exception {
        if (closed) throw new IOException("Freeze protection ended");
        if (watchdog == null) return;
        if (android.os.Build.VERSION.SDK_INT < 33) throw new IOException("Virtual casting requires Android 13");
        if (!watchdog.isAlive()) throw new IOException("Freeze protection process exited");
        lease.write('\n'); lease.flush();
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (watchdog == null || android.os.Build.VERSION.SDK_INT < 33) return;
        try { lease.close(); } catch (IOException ignored) { }
        try {
            if (!watchdog.waitFor(8, TimeUnit.SECONDS))
                Log.w("RearAppFreezeGuard", "Independent restoration is still running");
            else if (watchdog.exitValue() != 0) recoverStale();
        } catch (Exception e) { Log.e("RearAppFreezeGuard", "Freeze restoration pending", e); }
    }
}
