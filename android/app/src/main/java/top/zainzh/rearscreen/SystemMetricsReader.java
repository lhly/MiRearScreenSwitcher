package top.zainzh.rearscreen;

import android.os.SystemClock;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Shell-side bounded reads of fixed counters. No commands, caller paths, threads or timers. */
final class SystemMetricsReader {
    private String cached;
    private long sampledAt = -1000;

    synchronized String read() {
        long now = SystemClock.elapsedRealtime();
        if (cached != null && now - sampledAt < 1000) return cached;
        try {
            JSONObject result = new JSONObject().put("sampledAt", now);
            put(result, "cpu", "/proc/stat", 1024);
            put(result, "gpu", "/sys/class/kgsl/kgsl-3d0/gpubusy", 128);
            cached = result.toString(); sampledAt = now;
            return cached;
        } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void put(JSONObject target, String key, String path, int limit) throws org.json.JSONException {
        try { target.put(key, firstLine(path, limit)).put(key + "Status", "available"); }
        catch (IOException | SecurityException unavailable) { target.put(key + "Status", "unavailable"); }
    }

    private static String firstLine(String path, int limit) throws IOException {
        byte[] buffer = new byte[limit];
        try (FileInputStream input = new FileInputStream(path)) {
            int count = input.read(buffer);
            if (count <= 0) throw new IOException("Empty metric");
            for (int i = 0; i < count; i++) if (buffer[i] == '\n') return new String(buffer, 0, i, StandardCharsets.US_ASCII);
            if (count == limit) throw new IOException("Oversized metric");
            return new String(buffer, 0, count, StandardCharsets.US_ASCII);
        }
    }
}
