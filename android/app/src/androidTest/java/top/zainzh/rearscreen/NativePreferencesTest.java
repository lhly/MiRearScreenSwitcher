package top.zainzh.rearscreen;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.*;
public class NativePreferencesTest {
private final android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private Context isolatedContext(String suffix) {
        Context base = instrumentation.getTargetContext();
        return new ContextWrapper(base) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return base.getSharedPreferences("native_test_" + suffix + "_" + name, mode);
            }
        };
    }

    @Test public void migratesFlutterLongAndKeepsSelectedApps() {
        Context context = isolatedContext("upgrade");
        SharedPreferences legacy = context.getSharedPreferences("FlutterSharedPreferences", 0);
        SharedPreferences nativePrefs = context.getSharedPreferences("mrss_settings", 0);
        legacy.edit().clear().putBoolean("flutter.charging_animation_enabled", false)
                .putBoolean("flutter.always_wakeup_enabled", true).putBoolean("flutter.keep_screen_on_enabled", false)
                .putLong("flutter.notification_duration", 7200).commit();
        nativePrefs.edit().clear().putStringSet("notification_selected_apps", Collections.singleton("example.app")).commit();
        NativePreferences prefs = new NativePreferences(context);
        assertFalse(prefs.getBoolean("charging_animation_enabled"));
        assertTrue(prefs.getBoolean("always_wakeup_enabled"));
        assertFalse(prefs.getBoolean("keep_screen_on_enabled"));
        assertEquals(7200, prefs.getDuration());
        assertEquals(Collections.singleton("example.app"), nativePrefs.getStringSet("notification_selected_apps", null));
        // Reopening must honor new native changes instead of importing a stale Flutter value again.
        nativePrefs.edit().putBoolean("charging_animation_enabled", true).commit();
        assertTrue(new NativePreferences(context).getBoolean("charging_animation_enabled"));
    }

    @Test public void settingsStillReachLegacyTileAndNativeServiceReaders() {
        Context context = isolatedContext("contracts");
        context.getSharedPreferences("mrss_settings", 0).edit().clear().commit();
        context.getSharedPreferences("FlutterSharedPreferences", 0).edit().clear().commit();
        NativePreferences prefs = new NativePreferences(context);
        prefs.putBoolean("proximity_sensor_enabled", false);
        prefs.putBoolean("notification_service_enabled", true);
        prefs.putDuration(25);
        SharedPreferences legacy = context.getSharedPreferences("FlutterSharedPreferences", 0);
        SharedPreferences services = context.getSharedPreferences("mrss_settings", 0);
        assertFalse(legacy.getBoolean("flutter.proximity_sensor_enabled", true));
        assertTrue(legacy.getBoolean("flutter.notification_service_enabled", false));
        assertTrue(services.getBoolean("notification_service_enabled", false));
        assertEquals(25L, legacy.getLong("flutter.notification_duration", 0));
        assertEquals(25, services.getInt("notification_duration", 0));
    }


}