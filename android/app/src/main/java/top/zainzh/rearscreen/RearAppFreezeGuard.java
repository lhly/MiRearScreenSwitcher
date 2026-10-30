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
        try { if (required()) RearFreezeGuardProcess.recover(); }
        catch (Exception e) { Log.w("RearAppFreezeGuard", "Recovery deferred to the active guard or next cast", e); }
    }
    static RearAppFreezeGuard start(String pkg) throws Exception {
        RearFreezeState.validatePackage(pkg);
        if (!required()) return new RearAppFreezeGuard(null, "not_needed");
        String paths = RearFreezeGuardProcess.command("pm", "path", BuildConfig.APPLICATION_ID);
        String apk = null;
        for (String line : paths.split("\n")) if (line.startsWith("package:") && line.endsWith("/base.apk")) apk = line.substring(8);
        if (apk == null || !new File(apk).isFile()) throw new IOException("Installed cast APK unavailable for freeze recovery");
        ProcessBuilder builder = new ProcessBuilder("/system/bin/app_process", "/system/bin", "--nice-name=remrss-freeze-guard",
                RearFreezeGuardProcess.class.getName(), pkg).redirectErrorStream(true);
        builder.environment().put("CLASSPATH", apk);
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
            else if (watchdog.exitValue() != 0) RearFreezeGuardProcess.recover();
        } catch (Exception e) { Log.e("RearAppFreezeGuard", "Freeze restoration pending", e); }
    }
}
