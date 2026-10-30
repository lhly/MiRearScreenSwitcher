package top.zainzh.rearscreen;

import android.os.Process;
import android.os.SystemClock;
import androidx.annotation.Keep;
import java.io.*;

/** Independent shell watchdog: a dead caller closes stdin, so restoration survives its death. */
@Keep
public final class RearFreezeGuardProcess {
    static final String KEY = "MILLET_NO_RESTRICT_APP";
    static final File DIRECTORY = new File("/data/local/tmp/remrss-freeze-guard-v1");
    private static final long LEASE_MS = 6000;
    private static volatile long heartbeat;
    private static volatile boolean ended;

    static String command(String... args) throws Exception {
        if (android.os.Build.VERSION.SDK_INT < 33) throw new UnsupportedOperationException("Virtual casting requires Android 13");
        java.lang.Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] b = new byte[2048]; int n;
                while ((n = in.read(b)) != -1) {
                    if (output.size() + n <= 131072) output.write(b, 0, n);
                }
            } catch (IOException ignored) { }
        }, "freeze-command-output");
        reader.setDaemon(true); reader.start();
        if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly(); throw new IOException("Freeze settings command timed out");
        }
        reader.join(1000);
        String result = output.toString("UTF-8");
        if (process.exitValue() != 0 || result.contains("Exception") || result.contains("Error"))
            throw new IOException("Freeze settings command failed: " + result.trim());
        return result.endsWith("\n") ? result.substring(0, result.length() - 1) : result;
    }
    static RearFreezeState.Store store() {
        return new RearFreezeState.Store() {
            public String read() throws Exception {
                String result = command("settings", "--user", "0", "get", "system", KEY);
                return "null".equals(result) ? null : result;
            }
            public void write(String value) throws Exception {
                if (value == null) command("settings", "--user", "0", "delete", "system", KEY);
                else command("settings", "--user", "0", "put", "system", KEY, value);
            }
        };
    }
    static void recover() throws Exception {
        try (RearFreezeState state = new RearFreezeState(store(), DIRECTORY)) { state.restore(); }
    }
    public static void main(String[] args) throws Exception {
        try { run(args); }
        catch (Exception e) {
            System.err.println("ERROR " + e); System.err.flush(); System.exit(1);
        }
    }
    private static void run(String[] args) throws Exception {
        if (android.os.Build.VERSION.SDK_INT < 33) throw new UnsupportedOperationException("Virtual casting requires Android 13");
        if (Process.myUid() != 2000 && Process.myUid() != 0) throw new SecurityException("Shell freeze guard only");
        if (args.length != 1) throw new IllegalArgumentException("A cast package is required");
        RearFreezeState.validatePackage(args[0]);
        heartbeat = SystemClock.elapsedRealtime();
        Thread reader = new Thread(() -> {
            try {
                while (System.in.read() != -1) heartbeat = SystemClock.elapsedRealtime();
            } catch (IOException ignored) { }
            finally { ended = true; }
        }, "freeze-owner-lease");
        reader.setDaemon(true); reader.start();
        try (RearFreezeState state = new RearFreezeState(store(), DIRECTORY)) {
            try {
                state.restore();
                if (ended) return;
                state.ensure(args[0]);
                System.out.println("READY"); System.out.flush();
                while (!ended && SystemClock.elapsedRealtime() - heartbeat < LEASE_MS) {
                    SystemClock.sleep(2000);
                    if (!ended) state.ensure(args[0]);
                }
            } finally {
                // Keep the independent process alive through transient settings/provider failures.
                Exception failure = null;
                for (int i = 0; i < 10; i++) {
                    try { state.restore(); failure = null; break; }
                    catch (Exception e) { failure = e; SystemClock.sleep(500); }
                }
                if (failure != null) throw failure; // Write-ahead journal remains for the next bind.
                System.out.println("RESTORED"); System.out.flush();
            }
        }
    }
}
