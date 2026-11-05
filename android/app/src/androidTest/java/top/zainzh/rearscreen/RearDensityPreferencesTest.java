package top.zainzh.rearscreen;

import android.content.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class RearDensityPreferencesTest {
    @Test public void numericSettingsPreserveExistingSwitchAndOtherApps() {
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences p=RearDensityPreferences.preferences(c);
        String app="test.layout.target",other="test.layout.other";
        Map<String,?> before=new HashMap<>(p.getAll());
        try {
            p.edit().remove("enabled:"+app).remove("dpi:"+app).commit();
            assertFalse(RearDensityPreferences.enabled(c,app));assertEquals(0,RearDensityPreferences.layoutDensity(c,app));
            RearDensityPreferences.setEnabled(c,app,true);RearDensityPreferences.setLayoutDensity(c,app,200);
            RearDensityPreferences.setLayoutDensity(c,other,320);
            assertTrue(RearDensityPreferences.enabled(c,app));assertEquals(200,RearDensityPreferences.layoutDensity(c,app));
            RearDensityPreferences.setEnabled(c,app,false);assertEquals(200,RearDensityPreferences.layoutDensity(c,app));
            RearDensityPreferences.setEnabled(c,app,true);RearDensityPreferences.setLayoutDensity(c,app,0);
            assertTrue(RearDensityPreferences.enabled(c,app));assertEquals(0,RearDensityPreferences.layoutDensity(c,app));
            assertEquals(320,RearDensityPreferences.layoutDensity(c,other));
            p.edit().putInt("dpi:"+app,999).commit();assertEquals(0,RearDensityPreferences.layoutDensity(c,app));
            p.edit().putString("dpi:"+app,"corrupt").commit();assertEquals(0,RearDensityPreferences.layoutDensity(c,app));
            try{RearDensityPreferences.setLayoutDensity(c,app,71);fail("invalid DPI accepted");}catch(IllegalArgumentException expected){}
        } finally {
            SharedPreferences.Editor e=p.edit().clear();for(Map.Entry<String,?> v:before.entrySet()){
                if(v.getValue() instanceof Boolean)e.putBoolean(v.getKey(),(Boolean)v.getValue());
                else if(v.getValue() instanceof Integer)e.putInt(v.getKey(),(Integer)v.getValue());
                else if(v.getValue() instanceof String)e.putString(v.getKey(),(String)v.getValue());
            }e.commit();
        }
    }

    @Test public void bulkSwitchUpdatesOnlyDeduplicatedAppsAndKeepsDpiAndDefaults() {
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences p=RearDensityPreferences.preferences(c);
        Map<String,?> before=new HashMap<>(p.getAll());
        String first="test.bulk.first",second="test.bulk.second",outside="test.bulk.outside",newApp="test.bulk.new";
        try {
            p.edit().clear().commit();
            assertFalse("unconfigured apps remain off by default",RearDensityPreferences.enabled(c,newApp));
            RearDensityPreferences.setEnabled(c,first,true);
            RearDensityPreferences.setEnabled(c,second,false);
            RearDensityPreferences.setEnabled(c,outside,true);
            RearDensityPreferences.setLayoutDensity(c,first,240);
            RearDensityPreferences.setLayoutDensity(c,second,320);

            RearDensityPreferences.setAllEnabled(c,Arrays.asList(first,first,second),true);
            assertTrue(RearDensityPreferences.enabled(c,first));
            assertTrue(RearDensityPreferences.enabled(c,second));
            assertTrue("apps outside the input collection must remain unchanged",RearDensityPreferences.enabled(c,outside));
            assertEquals("batch changes do not alter custom DPI",240,RearDensityPreferences.layoutDensity(c,first));
            assertEquals(320,RearDensityPreferences.layoutDensity(c,second));
            assertFalse("new apps still default to off without writing an entry",RearDensityPreferences.enabled(c,newApp));
            assertFalse(p.contains("enabled:"+newApp));

            // A deliberate per-App choice made before a bulk action is updated only if included.
            RearDensityPreferences.setEnabled(c,second,true);
            RearDensityPreferences.setAllEnabled(c,Arrays.asList(first,second),false);
            assertFalse(RearDensityPreferences.enabled(c,first));
            assertFalse(RearDensityPreferences.enabled(c,second));
            assertTrue(RearDensityPreferences.enabled(c,outside));
            assertEquals(240,RearDensityPreferences.layoutDensity(c,first));
            assertEquals(320,RearDensityPreferences.layoutDensity(c,second));
        } finally {
            restore(p,before);
        }
    }

    @Test public void invalidBulkInputDoesNotPartiallyWriteAndDisabledFeatureIsNoOp() {
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences p=RearDensityPreferences.preferences(c);
        Map<String,?> before=new HashMap<>(p.getAll());
        try {
            p.edit().clear().putBoolean("enabled:test.bulk.existing",false).putInt("dpi:test.bulk.existing",280).commit();
            Map<String,?> stable=new HashMap<>(p.getAll());
            try {
                RearDensityPreferences.setAllEnabled(c,Arrays.asList("test.bulk.valid","invalid"),true);
                fail("invalid package should reject the entire operation");
            } catch (IllegalArgumentException expected) { }
            assertEquals("validation must finish before the single preferences write",stable,p.getAll());
            try {
                RearDensityPreferences.setAllEnabled(c,null,true);
                fail("null package collection should be rejected");
            } catch (IllegalArgumentException expected) { }

            if (!BuildConfig.VIRTUAL_CAST_ENABLED) {
                RearDensityPreferences.setAllEnabled(c,Arrays.asList("test.bulk.existing","test.bulk.valid"),true);
                assertEquals("disabled feature must not write any key",stable,p.getAll());
            }
        } finally {
            restore(p,before);
        }
    }

    private static void restore(SharedPreferences preferences,Map<String,?> values) {
        SharedPreferences.Editor e=preferences.edit().clear();
        for(Map.Entry<String,?> v:values.entrySet()){
            if(v.getValue() instanceof Boolean)e.putBoolean(v.getKey(),(Boolean)v.getValue());
            else if(v.getValue() instanceof Integer)e.putInt(v.getKey(),(Integer)v.getValue());
            else if(v.getValue() instanceof String)e.putString(v.getKey(),(String)v.getValue());
        }
        e.commit();
    }
}
