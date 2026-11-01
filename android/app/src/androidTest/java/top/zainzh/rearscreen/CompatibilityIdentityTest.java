package top.zainzh.rearscreen;

import android.os.Binder;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.lang.reflect.Proxy;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class CompatibilityIdentityTest {
    private static ITaskService service(int id) {
        Binder binder = new Binder();
        return (ITaskService) Proxy.newProxyInstance(ITaskService.class.getClassLoader(), new Class<?>[]{ITaskService.class}, (proxy, method, args) -> {
            if (method.getName().equals("asBinder")) return binder;
            if (method.getName().equals("getRearDisplayState"))
                return "{\"selectedId\":" + id + ",\"selectedUnique\":\"local:private-panel\",\"reason\":\"secondary_internal_panel\"}";
            throw new UnsupportedOperationException(method.getName());
        });
    }
    @Test public void privatePanelDoesNotRequirePublicAppVisibility() {
        try {
            RearDisplay.attach(service(71));
            assertEquals(71, RearDisplay.requireId());
            assertTrue(RearDisplay.isRear(71));
            assertFalse(RearDisplay.isRear(0));
        } finally { RearDisplay.attach(null); }
    }
    @Test public void serviceNeverPromotesMainOrMissingPanel() {
        try {
            RearDisplay.attach(service(0));
            assertEquals(-1, RearDisplay.id());
            RearDisplay.attach(service(-1));
            assertEquals(-1, RearDisplay.id());
        } finally { RearDisplay.attach(null); }
    }
}
