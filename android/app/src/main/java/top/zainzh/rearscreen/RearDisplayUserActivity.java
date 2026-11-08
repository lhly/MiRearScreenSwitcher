package top.zainzh.rearscreen;

import java.lang.reflect.Method;

/** Display-targeted user-activity bridge used only inside the Shizuku shell service. */
final class RearDisplayUserActivity {
    static final int EVENT_TOUCH = 2; // PowerManager.USER_ACTIVITY_EVENT_TOUCH

    interface Sink {
        void report(int displayId, long eventTimeUptime) throws Exception;
    }

    private RearDisplayUserActivity() {}

    /**
     * The displayId overload is essential: legacy userActivity(long,int,int) targets the
     * default display and must never be used as a fallback for the physical rear panel.
     */
    static Sink shell() throws Exception {
        Object service = RearSystemBridge.service("power", "android.os.IPowerManager$Stub");
        Class<?> api = Class.forName("android.os.IPowerManager");
        Method method = displayTargetedMethod(api);
        return (displayId, eventTimeUptime) -> invokeDisplayTargeted(service, method, displayId, eventTimeUptime);
    }

    static Method displayTargetedMethod(Class<?> api) throws NoSuchMethodException {
        return api.getMethod("userActivity", int.class, long.class, int.class, int.class);
    }

    /** Kept package-visible so tests exercise the exact reflected call shape. */
    static void invokeDisplayTargeted(Object service, Method method, int displayId, long eventTimeUptime)
            throws Exception {
        if (displayId < 1 || eventTimeUptime < 0) throw new IllegalArgumentException("Invalid rear activity");
        method.invoke(service, displayId, eventTimeUptime, EVENT_TOUCH, 0);
    }
}
