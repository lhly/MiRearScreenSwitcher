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
}
