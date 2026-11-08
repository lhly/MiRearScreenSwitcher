package top.zainzh.rearscreen;

import org.junit.Test;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public final class RearDisplayUserActivityTest {
    public static final class DisplayTargetedPower {
        final List<String> calls = new ArrayList<>();
        public void userActivity(int displayId, long time, int event, int flags) {
            calls.add(displayId + ":" + time + ":" + event + ":" + flags);
        }
        public void userActivity(long time, int event, int flags) {
            calls.add("default:" + time + ":" + event + ":" + flags);
        }
    }
    public static final class LegacyDefaultOnlyPower {
        public void userActivity(long time, int event, int flags) {}
    }

    @Test public void resolvesAndInvokesExactDisplayTargetedSignature() throws Exception {
        DisplayTargetedPower service = new DisplayTargetedPower();
        Method method = RearDisplayUserActivity.displayTargetedMethod(DisplayTargetedPower.class);
        RearDisplayUserActivity.invokeDisplayTargeted(service, method, 4, 3210);
        assertEquals(java.util.Collections.singletonList("4:3210:2:0"), service.calls);
    }

    @Test public void legacyDefaultDisplaySignatureIsNotAcceptedAsFallback() {
        try {
            RearDisplayUserActivity.displayTargetedMethod(LegacyDefaultOnlyPower.class);
            fail("default-display activity must not be used for a physical rear panel");
        } catch (NoSuchMethodException expected) { }
    }

    @Test public void invalidDisplayAndTimeAreRejectedBeforeInvocation() throws Exception {
        DisplayTargetedPower service = new DisplayTargetedPower();
        Method method = RearDisplayUserActivity.displayTargetedMethod(DisplayTargetedPower.class);
        try { RearDisplayUserActivity.invokeDisplayTargeted(service, method, 0, 3210); fail(); }
        catch (IllegalArgumentException expected) { }
        try { RearDisplayUserActivity.invokeDisplayTargeted(service, method, 4, -1); fail(); }
        catch (IllegalArgumentException expected) { }
        assertTrue(service.calls.isEmpty());
    }
}
