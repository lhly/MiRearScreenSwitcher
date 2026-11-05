package top.zainzh.rearscreen;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;

public class RearCastModeTest {
    @Test public void defaultsVirtualCastingOnButHonorsSavedChoiceAndPlatformGates() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = RearCastMode.preferences(context);
        boolean hadValue = preferences.contains(RearCastMode.VIRTUAL);
        boolean savedValue = preferences.getBoolean(RearCastMode.VIRTUAL, true);
        try {
            preferences.edit().remove(RearCastMode.VIRTUAL).commit();
            boolean supported = BuildConfig.VIRTUAL_CAST_ENABLED && Build.VERSION.SDK_INT >= 33;
            assertEquals("first use should opt into virtual casting on supported builds", supported, RearCastMode.useVirtual(context));
            assertFalse("reading the default must not write a per-user choice", preferences.contains(RearCastMode.VIRTUAL));

            RearCastMode.setVirtual(context, false);
            assertTrue("an explicit opt-out is persisted", preferences.contains(RearCastMode.VIRTUAL));
            assertFalse("a saved false must override the new default", RearCastMode.useVirtual(context));

            RearCastMode.setVirtual(context, true);
            assertEquals("an explicit true remains enabled where supported", supported, RearCastMode.useVirtual(context));

            preferences.edit().putBoolean(RearCastMode.VIRTUAL, true).commit();
            assertEquals("API and feature gates still prevent unsupported virtual casting", supported, RearCastMode.useVirtual(context));
        } finally {
            SharedPreferences.Editor edit = preferences.edit();
            if (hadValue) edit.putBoolean(RearCastMode.VIRTUAL, savedValue); else edit.remove(RearCastMode.VIRTUAL);
            edit.commit();
        }
    }
}
