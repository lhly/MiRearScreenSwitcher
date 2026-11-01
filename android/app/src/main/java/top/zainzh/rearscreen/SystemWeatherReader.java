package top.zainzh.rearscreen;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Fixed, read-only weather query under the existing shell service; no city data crosses IPC. */
@androidx.annotation.RequiresApi(26)
final class SystemWeatherReader {
    static String read() {
        Process process = null;
        try {
            // On this ROM the provider returns null for root as well as ordinary Apps.
            // A root-backed Shizuku service drops only this child query to shell; the
            // shared service keeps its identity and casting/input capabilities.
            ProcessBuilder command = android.os.Process.myUid() == 0
                    ? new ProcessBuilder("/system/bin/su", "2000", "-c",
                        "/system/bin/content query --uri content://weather/actualWeatherData/1 --projection temperature:weather_type:publish_time:temperature_unit:day")
                    : new ProcessBuilder("/system/bin/content", "query", "--uri", "content://weather/actualWeatherData/1",
                        "--projection", "temperature:weather_type:publish_time:temperature_unit:day");
            process = command.redirectErrorStream(true).start();
            if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) return "{}";
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[2048]; int count;
            while ((count = process.getInputStream().read(buffer)) != -1) {
                if (bytes.size() + count > 32768) return "{}";
                bytes.write(buffer, 0, count);
            }
            for (String line : bytes.toString(StandardCharsets.UTF_8.name()).split("\n")) {
                if (!line.startsWith("Row: ")) continue;
                JSONObject filtered = new JSONObject();
                for (String field : line.replaceFirst("^Row: \\d+\\s+", "").split(", ")) {
                    int split = field.indexOf('=');
                    if (split < 1) continue;
                    String key = field.substring(0, split);
                    if (key.equals("temperature") || key.equals("weather_type") || key.equals("publish_time")
                            || key.equals("temperature_unit") || key.equals("day")) filtered.put(key, field.substring(split + 1).trim());
                }
                if ("1".equals(filtered.optString("day"))) { filtered.remove("day"); return filtered.toString(); }
            }
        } catch (Exception ignored) { /* Unavailable weather is not an error in the casting session. */ }
        finally { if (process != null) process.destroy(); }
        return "{}";
    }
}
