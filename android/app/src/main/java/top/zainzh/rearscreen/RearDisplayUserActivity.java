package top.zainzh.rearscreen;

import java.lang.reflect.Method;

/** Display-targeted user-activity bridge used only inside the Shizuku shell service. */
final class RearDisplayUserActivity {
    static final int EVENT_OTHER = 0; // PowerManager.USER_ACTIVITY_EVENT_OTHER
    static final int EVENT_TOUCH = 2; // PowerManager.USER_ACTIVITY_EVENT_TOUCH

    interface Sink {
        void report(int displayId, long eventTimeUptime, int eventType) throws Exception;
    }

    enum Result { REPORTED, INVALID_DISPLAY, INVALID_SESSION, EXPIRED, STALE_SESSION, WRONG_REAR, DISPLAY_OFF }

    interface SinkProvider { Sink prepare() throws Exception; }
    interface SnapshotProvider { DisplaySnapshot read(int displayId) throws Exception; }
    interface CurrentGate { boolean isCurrent() throws Exception; }
    interface ElapsedClock { long now(); }

    /** Fresh shell-side identity and power snapshot for one physical panel. */
    static final class DisplaySnapshot {
        final int selectedRearId;
        final String selectedRearUniqueId;
        final String actualDisplayUniqueId;
        final boolean displayOn;
        DisplaySnapshot(int selectedRearId, String selectedRearUniqueId, String actualDisplayUniqueId, boolean displayOn) {
            this.selectedRearId = selectedRearId;
            this.selectedRearUniqueId = selectedRearUniqueId;
            this.actualDisplayUniqueId = actualDisplayUniqueId;
            this.displayOn = displayOn;
        }
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
        return (displayId, eventTimeUptime, eventType) ->
                invokeDisplayTargeted(service, method, displayId, eventTimeUptime, eventType);
    }

    static Method displayTargetedMethod(Class<?> api) throws NoSuchMethodException {
        return api.getMethod("userActivity", int.class, long.class, int.class, int.class);
    }

    /** Kept package-visible so tests exercise the exact reflected call shape. */
    static void invokeDisplayTargeted(Object service, Method method, int displayId, long eventTimeUptime)
            throws Exception {
        invokeDisplayTargeted(service, method, displayId, eventTimeUptime, EVENT_TOUCH);
    }

    /** Only event classes used internally here are accepted; this path never fabricates TOUCH. */
    static void invokeDisplayTargeted(Object service, Method method, int displayId, long eventTimeUptime,
                                      int eventType) throws Exception {
        if (displayId < 1 || eventTimeUptime < 0) throw new IllegalArgumentException("Invalid rear activity");
        if (eventType != EVENT_TOUCH && eventType != EVENT_OTHER) throw new IllegalArgumentException("Invalid activity type");
        method.invoke(service, displayId, eventTimeUptime, eventType, 0);
    }

    /** Run slow setup/queries before a final exact-session gate and retain the original draw uptime. */
    static Result reportOwnedNotification(SinkProvider sinkProvider, SnapshotProvider snapshotProvider,
                                          CurrentGate currentGate, ElapsedClock elapsedClock, int displayId,
                                          long notificationSessionId, long expiresAtElapsedRealtimeMs,
                                          long drawEventUptime) throws Exception {
        if (displayId < 1) return Result.INVALID_DISPLAY;
        if (notificationSessionId <= 0 || expiresAtElapsedRealtimeMs <= 0 ||
                drawEventUptime < 0 || sinkProvider == null || snapshotProvider == null ||
                currentGate == null || elapsedClock == null) return Result.INVALID_SESSION;
        Sink sink = sinkProvider.prepare();
        DisplaySnapshot snapshot = snapshotProvider.read(displayId);
        if (snapshot == null || snapshot.selectedRearId != displayId ||
                snapshot.selectedRearUniqueId == null || snapshot.selectedRearUniqueId.isEmpty() ||
                !snapshot.selectedRearUniqueId.equals(snapshot.actualDisplayUniqueId)) return Result.WRONG_REAR;
        if (!snapshot.displayOn) return Result.DISPLAY_OFF;
        if (sink == null) throw new IllegalStateException("Rear activity backend unavailable");
        if (!currentGate.isCurrent()) return Result.STALE_SESSION;
        long nowElapsedRealtimeMs = elapsedClock.now();
        if (nowElapsedRealtimeMs < 0) return Result.INVALID_SESSION;
        if (nowElapsedRealtimeMs >= expiresAtElapsedRealtimeMs) return Result.EXPIRED;
        sink.report(displayId, drawEventUptime, EVENT_OTHER);
        return Result.REPORTED;
    }
}
