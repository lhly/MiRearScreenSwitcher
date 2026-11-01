package top.zainzh.rearscreen;

import android.os.Build;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayDeque;

/** Bounded, content-free compatibility events; no notification text or general logcat is exported. */
final class CompatibilityDiagnostics {
    private static final ArrayDeque<String> events = new ArrayDeque<>();
    private CompatibilityDiagnostics() {}
    static synchronized void record(String stage, String detail) {
        String value = System.currentTimeMillis() + " " + stage + " " + detail;
        if (value.length() > 2000) value = value.substring(0, 2000);
        if (events.size() >= 60) events.removeFirst();
        events.addLast(value); Log.i("RearCompatibility", value);
    }
    static String report() {
        JSONObject report = new JSONObject();
        try {
            report.put("schema", 1).put("serviceUid", android.os.Process.myUid()).put("version", BuildConfig.VERSION_NAME).put("package", BuildConfig.APPLICATION_ID)
                    .put("device", Build.DEVICE).put("model", Build.MODEL).put("manufacturer", Build.MANUFACTURER)
                    .put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT).put("incremental", Build.VERSION.INCREMENTAL)
                    .put("display", new JSONObject(RearDisplay.state())).put("launchers", new JSONArray(RearSystemBridge.launcherCandidates()));
            try {
                java.lang.reflect.Field field = Class.forName("android.app.IActivityTaskManager$Stub").getDeclaredField("TRANSACTION_moveRootTaskToDisplay");
                field.setAccessible(true); report.put("moveRootTaskTransaction", field.getInt(null));
            } catch (Exception e) { report.put("moveRootTaskTransactionError", e.toString()); }
            try { report.put("physicalDisplayId", RearDisplay.physicalId()); } catch (Exception e) { report.put("physicalDisplayError", e.toString()); }
            try { report.put("input", RearInputDevices.diagnostics()); } catch (Exception e) { report.put("inputError", e.toString()); }
            synchronized (CompatibilityDiagnostics.class) { report.put("events", new JSONArray(events)); }
        } catch (Exception e) { try { report.put("error", e.toString()); } catch (Exception ignored) { } }
        return report.toString();
    }
}
