package top.zainzh.rearscreen;

import android.os.*;
import android.hardware.display.DisplayManager;
import android.view.Surface;
import org.json.JSONObject;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns a virtual display and presents its Surface on the physical rear display. */
final class RearVirtualCast {
    static final int RECOVERY_WAKE_REAR = 1;
    static final int RECOVERY_PRESENT_HOST = 2;
    // Framework flags hidden from the public SDK. Preserve these values for the shell-owned display.
    private static final int VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 1 << 6;
    private static final int VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 << 10;
    private static final int VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP = 1 << 11;
    private static final int VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED = 1 << 12;
    private static final int VIRTUAL_DISPLAY_FLAG_OWN_FOCUS = 1 << 14;
    private static final int CAST_DISPLAY_FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
            | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH
            | VIRTUAL_DISPLAY_FLAG_TRUSTED | VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP
            | VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED | VIRTUAL_DISPLAY_FLAG_OWN_FOCUS;
    private final TaskService service;
    private final RearInputProxy input;
    private volatile Session current;
    private volatile String last = "{\"state\":\"stopped\"}";
    RearVirtualCast(TaskService service, RearInputProxy input) { this.service = service; this.input = input; }

    String renderPlan(int task, String component, int density, boolean compatibility, boolean full) throws Exception {
        if (!BuildConfig.VIRTUAL_CAST_ENABLED) throw new SecurityException("Virtual casting is unavailable");
        VirtualCastRequest.density(Integer.toString(density));
        if (component != null) VirtualCastRequest.component(component);
        if (task >= 0 ? !service.isTaskOnDisplay(task, 0) : component == null)
            throw new IllegalStateException("Source task unavailable");
        String pkg = component == null ? service.getPackageNameFromTaskId(task) : component.substring(0, component.indexOf('/'));
        VirtualCastRequest.packageName(pkg);
        int source = RearCastGeometry.displayDensity(0);
        int actual = RearCastCompatibility.density(compatibility, density, source);
        RearCastViewport safe = RearCastGeometry.read();
        RearCastViewport render = RearCastCompatibility.viewport(full ? safe.full() : safe, density, actual);
        return new JSONObject(RearCastGeometry.encode(render)).put("package", pkg).put("renderDensity", actual)
                .put("requestedDensity", density).put("sourceDensity", source)
                .put("compatibility", compatibility ? "source_density" : "none").toString();
    }
    synchronized String start(Surface surface, String reader, int task, int host, int density, String component, IBinder owner, IRearGestureListener gestures, String viewport, boolean compatibility, int rotation, boolean full) throws Exception {
        if (!BuildConfig.VIRTUAL_CAST_ENABLED) throw new SecurityException("Virtual casting is unavailable");
        RearCastViewport.validateContentRotation(rotation);
        VirtualCastRequest.density(Integer.toString(density));
        if (component != null) VirtualCastRequest.component(component);
        RearCastViewport safe = RearCastGeometry.read();
        if (!(full ? safe.full() : safe).key().equals(RearCastGeometry.decode(viewport).key())) throw new IllegalStateException("Rear output area changed before start");
        JSONObject plan = new JSONObject(renderPlan(task, component, density, compatibility, full));
        if (!RearCastGeometry.decode(plan.toString()).renderKey().equals(RearCastGeometry.decode(viewport).renderKey()))
            throw new IllegalStateException("Rear render plan changed before start");
        if (surface == null || !surface.isValid() || owner == null || !owner.isBinderAlive()
                || (task < 0 ? component == null : !service.isTaskOnDisplay(task, 0)) || !service.isTaskOnDisplay(host, RearDisplay.requireId()))
            throw new IllegalStateException("Main task / rear host unavailable");
        stop(null);
        input.stop(null);
        // Closing the previous session may have released its host Surface while we waited.
        if (!surface.isValid() || !owner.isBinderAlive() || !service.isTaskOnDisplay(host, RearDisplay.requireId()))
            throw new IllegalStateException("Rear host changed during previous session cleanup");
        Session s = new Session(surface, reader, task, host, density, component, owner, gestures, safe, plan.getInt("renderDensity"), compatibility, rotation, full);
        current = s;
        try { s.begin(); return s.status(); }
        catch (Exception e) { CompatibilityDiagnostics.record("virtual_start_failed", root(e).toString()); s.close("start_failed: " + root(e)); throw e; }
    }
    synchronized void stop(String id) { Session s = current; if (s != null && (id == null || s.id.equals(id))) s.close("stopped"); }
    String status() { Session s = current; return s == null ? last : s.status(); }
    boolean notification(String id, long token, int left, int top, int right, int bottom) {
        Session s = current;
        if (s == null || s.closed || !s.id.equals(id)) return false;
        return input.notification(s.inputId, token, left, top, right, bottom, tapped -> {
            if (s.closed || s.gestures == null) return;
            try { s.gestures.onNotificationDismiss(s.id, tapped); }
            catch (RemoteException | RuntimeException e) { android.util.Log.w("RearVirtualCast", "Notification dismiss unavailable", e); }
        });
    }
    boolean updateSurface(String id, Surface surface) throws Exception {
        Session s = current;
        if (s == null || !s.id.equals(id)) { if (surface != null) surface.release(); return false; }
        return s.updateSurface(surface);
    }
    String recoverHost(String id, int hostTask, int rearDisplayId, int action,
                       ISceneTaskActionGate actionGate) throws Exception {
        Session s = current;
        if (s == null || id == null || !s.id.equals(id)) return "stale_session";
        if (action != RECOVERY_WAKE_REAR && action != RECOVERY_PRESENT_HOST) return "invalid_action";
        return s.recoverHost(hostTask, rearDisplayId, action, actionGate);
    }
    void dismissMenu(String id) throws Exception {
        Session s = current;
        if (s != null && !s.closed && s.id.equals(id)) input.dismissMenu(s.inputId);
    }
    String rotate(String id, int direction) throws Exception {
        if (direction != -1 && direction != 1) throw new IllegalArgumentException("Rotation direction must be -1 or 1");
        Session s = current;
        if (s == null || s.closed || !s.id.equals(id)) throw new IllegalStateException("Cast session ended");
        return s.rotate(direction);
    }
    String beginViewport(String id, boolean full) throws Exception {
        Session s = current;
        if (s == null || s.closed || !s.id.equals(id)) throw new IllegalStateException("Cast session ended");
        return s.beginViewport(full);
    }
    String finishViewport(String id, Surface surface) throws Exception {
        Session s = current;
        if (s == null || s.closed || !s.id.equals(id)) {
            if (surface != null) surface.release();
            throw new IllegalStateException("Cast session ended");
        }
        return s.finishViewport(surface);
    }
    boolean renew(String id) {
        Session s = current;
        if (s == null || s.closed || !s.id.equals(id)) return false;
        synchronized (s) { if (s.closed || !s.id.equals(id)) return false; s.touchLease(); return true; }
    }
    String setOutputAvailable(String id, boolean available, Surface replacement,
                              ISceneTaskActionGate actionGate) throws Exception {
        Session s = current;
        if (s == null || s.closed || !s.id.equals(id)) {
            if (replacement != null) replacement.release();
            return "stale_session";
        }
        return s.setOutputAvailable(available, replacement, actionGate);
    }
    private static Throwable root(Throwable e) { while (e.getCause() != null) e = e.getCause(); return e; }

    private final class Session {
        final String id = UUID.randomUUID().toString(), reader;
        volatile int task;
        final int host, density, requestedDensity;
        final String packageName, component;
        final boolean densityCompatibility;
        final String physicalIdentity = RearDisplay.identity();
        final int physicalRearId = RearDisplay.requireId();
        volatile Surface surface;
        volatile boolean outputAvailable = true;
        volatile long viewportDeadline;
        final IBinder owner;
        final IRearGestureListener gestures;
        final RearCastViewport safeViewport;
        volatile RearCastViewport viewport;
        volatile RearCastViewport pendingViewport;
        final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        final IBinder.DeathRecipient death = () -> close("owner_died");
        volatile boolean closed;
        // Every state transition which can invalidate an in-flight health probe advances this
        // generation while holding the Session monitor. The 500 ms probe may do slow checks
        // outside the lock, but it may only close the exact state it inspected.
        final VirtualCastHealthGeneration healthGeneration = new VirtualCastHealthGeneration();
        final RearTouchActivityReporter touchActivityReporter;
        final AtomicBoolean touchActivityUnavailableLogged = new AtomicBoolean();
        final AtomicBoolean firstTouchActivityLogged = new AtomicBoolean();
        final AtomicBoolean touchActivityReportPending = new AtomicBoolean();
        final java.util.concurrent.ExecutorService touchActivityExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "rear-touch-activity"); thread.setDaemon(true); return thread;
        });
        volatile long lastTouchActivityQueuedUptime = Long.MIN_VALUE;
        volatile long lease = SystemClock.uptimeMillis();
        volatile String phase = "starting", inputId = "";
        String sizePolicy = "disabled";
        RearAppFreezeGuard freezeGuard;
        boolean launcherEntry;
        int display = -1;
        volatile int contentRotation;
        Object manager, callback;
        Class<?> iface, callbackType;
        Session(Surface surface, String reader, int task, int host, int density, String component, IBinder owner, IRearGestureListener gestures, RearCastViewport safe, int renderDensity, boolean compatibility, int rotation, boolean full) {
            this.surface = surface; this.reader = reader; this.task = task; this.host = host; this.owner = owner;
            this.gestures = gestures;
            this.viewport = RearCastCompatibility.viewport(full ? safe.full() : safe, density, renderDensity); this.safeViewport = safe;
            this.contentRotation = rotation;
            this.requestedDensity = density; this.density = renderDensity; this.component = component;
            this.densityCompatibility = compatibility;
            this.packageName = component == null ? service.getPackageNameFromTaskId(task) : component.substring(0, component.indexOf('/'));
            RearDisplayUserActivity.Sink activitySink = null;
            try { activitySink = RearDisplayUserActivity.shell(); }
            catch (Exception unavailable) {
                android.util.Log.i("RearVirtualCast", "touch_activity_api=unavailable reason=" + unavailable.getClass().getSimpleName());
            }
            touchActivityReporter = new RearTouchActivityReporter(activitySink, (displayId, hostTaskId, hostPackage) -> {
                if (!physicalIdentity.equals(RearDisplay.identity()) || !RearDisplay.isRear(displayId))
                    return RearTouchPresentationGate.Decision.SNAPSHOT_UNAVAILABLE;
                RearDisplayCastOccupancy.Snapshot snapshot = RearDisplayCastOccupancyReader.readTypedOnly(displayId);
                return RearTouchPresentationGate.inspect(snapshot, displayId, hostTaskId, hostPackage,
                        ignored -> physicalRearStillOn());
            });
        }
        synchronized void begin() throws Exception {
            owner.linkToDeath(death, 0);
            int width = viewport.bufferWidth, height = viewport.bufferHeight;
            iface = Class.forName("android.hardware.display.IDisplayManager");
            callbackType = Class.forName("android.hardware.display.IVirtualDisplayCallback");
            Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "display");
            manager = Class.forName("android.hardware.display.IDisplayManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
            Binder token = new Binder() {
                @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                    if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString("android.hardware.display.IVirtualDisplayCallback"); return true; }
                    if (code >= 1 && code <= 3) { data.enforceInterface("android.hardware.display.IVirtualDisplayCallback"); return true; }
                    return super.onTransact(code, data, reply, flags);
                }
            };
            callback = Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[]{callbackType}, (p, m, a) -> {
                if (m.getName().equals("asBinder")) return token;
                if (m.getName().equals("hashCode")) return System.identityHashCode(p);
                if (m.getName().equals("equals")) return p == a[0];
                if (m.getName().equals("toString")) return "ReMRSS display callback";
                return null;
            });
            Class<?> builderType = Class.forName("android.hardware.display.VirtualDisplayConfig$Builder");
            Object builder = builderType.getConstructor(String.class, int.class, int.class, int.class)
                    .newInstance("ReMRSS-virtual-" + id, width, height, density);
            // PUBLIC | OWN_CONTENT_ONLY | SUPPORTS_TOUCH | TRUSTED | OWN_DISPLAY_GROUP | ALWAYS_UNLOCKED | OWN_FOCUS.
            // A separate display group is required for ALWAYS_UNLOCKED to survive system validation.
            // Keep the cast interactive when the main display locks; never dismiss the main keyguard.
            builderType.getMethod("setFlags", int.class).invoke(builder, CAST_DISPLAY_FLAGS);
            builderType.getMethod("setSurface", Surface.class).invoke(builder, surface);
            RearCastResizePolicy.Result resizePolicy = RearCastResizePolicy.build(builder, densityCompatibility);
            Object config = resizePolicy.config;
            sizePolicy = resizePolicy.mode;
            android.util.Log.i("RearVirtualCast", "size_policy=" + sizePolicy + " requested=" + densityCompatibility);
            display = (Integer) iface.getMethod("createVirtualDisplay", config.getClass(), callbackType,
                    Class.forName("android.media.projection.IMediaProjection"), String.class)
                    .invoke(manager, config, callback, null, "com.android.shell");
            if (display <= 0 || RearDisplay.isRear(display)) throw new IOException("Virtual display creation failed: " + display);
            // Apply the saved orientation before moving the task or enabling hardware input.
            command("wm", "fixed-to-user-rotation", "-d", Integer.toString(display), "enabled");
            command("wm", "user-rotation", "-d", Integer.toString(display), "lock", Integer.toString(contentRotation));
            awaitRotation(contentRotation);
            // Acquire before the target enters the virtual display. The independent process
            // restores its mirror entry even if this Shizuku service is killed abruptly.
            if (freezeGuard == null) freezeGuard = RearAppFreezeGuard.start(packageName);
            if (task >= 0 && !service.moveTaskToDisplay(task, display)) throw new IOException("Task move failed");
            if (component != null) {
                if (!service.executeShellCommand("am start --display " + display + (launcherEntry ? " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER" : "") + " -n '" + component + "'"))
                    throw new IOException("Activity launch failed");
                long end = SystemClock.uptimeMillis() + 4000;
                boolean found = false;
                do {
                    String foreground = service.getForegroundAppOnDisplay(display);
                    if (foreground != null && foreground.startsWith(packageName + ":")) {
                        task = Integer.parseInt(foreground.substring(foreground.lastIndexOf(':') + 1));
                        found = service.isTaskOnDisplay(task, display);
                        if (found) break;
                    }
                    Thread.sleep(100);
                } while (SystemClock.uptimeMillis() < end);
                if (!found) throw new IOException("Target activity did not enter the virtual display");
            }
            JSONObject started = new JSONObject(input.start(reader, task, owner, display, (inward, y, active, ready, menuArmed) -> {
                if (gestures == null) return;
                try { gestures.onBackGesture(id, inward, y, active && !closed, ready && !closed, menuArmed && !closed); }
                // A missing visual consumer must never interrupt the App's input or delay a Back action.
                catch (RemoteException | RuntimeException e) { android.util.Log.w("RearVirtualCast", "Gesture feedback unavailable", e); }
            }, viewport, new RearTouchRouter.Menu() {
                public void visible(boolean open) throws Exception {
                    if (gestures == null) throw new IOException("Menu consumer unavailable");
                    gestures.onCastMenu(id, open && !closed);
                    android.util.Log.i("RearVirtualCast", "menu_open=" + open + " id=" + id);
                }
                public void touch(int action, float x, float y, long down, long time) throws Exception {
                    if (gestures == null || closed) throw new IOException("Menu consumer unavailable");
                    gestures.onCastMenuTouch(id, action, x, y, down, time);
                }
            }, contentRotation, this::reportAcceptedInteraction));
            inputId = started.getString("id");
            freezeGuard.renew();
            touchLease();
            phase = "running";
            timer.scheduleAtFixedRate(() -> {
                HealthSnapshot checked = healthSnapshot();
                if (checked.closed) return;
                String failure = null;
                try {
                    long now = SystemClock.uptimeMillis();
                    if (now - checked.lease > 4000) {
                        closeIfLeaseSnapshotCurrent(checked, "lease_expired");
                        return;
                    }
                    else if (VirtualCastOutputRecoveryPolicy.expireViewportChange(
                            checked.viewportPending, checked.viewportDeadline, now)) failure = "viewport_reconnect_timeout";
                    else {
                        // Keep the captured Surface local; never dereference mutable Session.surface
                        // outside the Session monitor while updateSurface may be releasing it.
                        boolean invalidOutput = checked.outputAvailable
                                && (checked.surface == null || !checked.surface.isValid());
                        if (!physicalIdentity.equals(RearDisplay.identity()) || !owner.isBinderAlive() || invalidOutput
                                || !service.isTaskOnDisplay(host, RearDisplay.requireId())
                                || !service.isTaskOnDisplay(task, display) || !input.renew(inputId))
                            failure = "host_task_or_input_left";
                        else if (freezeGuard != null) freezeGuard.renew();
                    }
                } catch (Exception e) { failure = root(e).toString(); }
                if (failure != null) closeIfHealthSnapshotCurrent(checked, failure);
            }, 500, 500, TimeUnit.MILLISECONDS);
            android.util.Log.i("RearVirtualCast", status());
        }
        private void reportAcceptedInteraction(long eventTimeUptime) {
            if (current != this || closed || !owner.isBinderAlive() || host < 0
                    || !outputAvailable || pendingViewport != null || surface == null || !surface.isValid()) return;
            long queuedAt = SystemClock.uptimeMillis();
            long previous = lastTouchActivityQueuedUptime;
            if (previous != Long.MIN_VALUE && queuedAt >= previous
                    && queuedAt - previous < RearTouchActivityReporter.MIN_REPORT_INTERVAL_MS) return;
            if (!touchActivityReportPending.compareAndSet(false, true)) return;
            lastTouchActivityQueuedUptime = queuedAt;
            try {
                touchActivityExecutor.execute(() -> {
                    try { reportAcceptedTouchNow(eventTimeUptime); }
                    finally { touchActivityReportPending.set(false); }
                });
            } catch (java.util.concurrent.RejectedExecutionException closedExecutor) {
                touchActivityReportPending.set(false);
            }
        }
        private void reportAcceptedTouchNow(long eventTimeUptime) {
            RearTouchActivityReporter.Result result = touchActivityReporter.acceptedInteraction(eventTimeUptime,
                    display, physicalRearId, host, BuildConfig.APPLICATION_ID,
                    () -> current == this && !closed && owner.isBinderAlive(),
                    () -> current == this && !closed && owner.isBinderAlive()
                            && host >= 0
                            && outputAvailable && pendingViewport == null && surface != null && surface.isValid());
            if (result == RearTouchActivityReporter.Result.REPORTED
                    && firstTouchActivityLogged.compareAndSet(false, true)) {
                android.util.Log.i("RearVirtualCast", "touch_activity session=" + id + " display=" + physicalRearId
                        + " uptime=" + eventTimeUptime + " result=submitted");
            } else if (result == RearTouchActivityReporter.Result.UNAVAILABLE
                    && touchActivityUnavailableLogged.compareAndSet(false, true)) {
                android.util.Log.w("RearVirtualCast", "touch_activity session=" + id + " display=" + physicalRearId
                        + " uptime=" + eventTimeUptime + " result=unavailable");
            } else if (result == RearTouchActivityReporter.Result.HOST_NOT_PRESENTED) {
                android.util.Log.i("RearVirtualCast", "touch_activity session=" + id + " display=" + physicalRearId
                        + " uptime=" + eventTimeUptime + " result=not_presented gate=" + touchActivityReporter.lastDecision());
            }
        }
        private boolean physicalRearStillOn() {
            try {
                if (!physicalIdentity.equals(RearDisplay.identity()) || !RearDisplay.isRear(physicalRearId)) return false;
                Object info = RearDisplay.info(physicalRearId);
                return info.getClass().getField("state").getInt(info) == android.view.Display.STATE_ON;
            } catch (Exception unavailable) { return false; }
        }
        private final class HealthSnapshot {
            final long generation, leaseGeneration, lease, viewportDeadline;
            final Surface surface;
            final boolean closed, outputAvailable, viewportPending;
            HealthSnapshot(long generation, long leaseGeneration, long lease, long viewportDeadline, Surface surface,
                           boolean closed, boolean outputAvailable, boolean viewportPending) {
                this.generation = generation; this.leaseGeneration = leaseGeneration; this.lease = lease; this.viewportDeadline = viewportDeadline;
                this.surface = surface; this.closed = closed; this.outputAvailable = outputAvailable;
                this.viewportPending = viewportPending;
            }
        }
        private synchronized HealthSnapshot healthSnapshot() {
            return new HealthSnapshot(healthGeneration.capture(), healthGeneration.captureLease(), lease, viewportDeadline, surface, closed,
                    outputAvailable, pendingViewport != null);
        }
        private synchronized void touchLease() {
            lease = SystemClock.uptimeMillis();
            healthGeneration.renewLease();
        }
        private void closeIfHealthSnapshotCurrent(HealthSnapshot checked, String reason) {
            synchronized (this) {
                if (closed) return;
                healthGeneration.runIfCurrent(checked.generation, () -> close(reason));
            }
        }
        private void closeIfLeaseSnapshotCurrent(HealthSnapshot checked, String reason) {
            synchronized (this) {
                if (closed) return;
                healthGeneration.runIfLeaseCurrent(checked.leaseGeneration, () -> close(reason));
            }
        }
        private void command(String... args) throws Exception {
            DisplayRotationController.Result result = DisplayRotationController.runCommand(args);
            if (result.exitCode != 0) throw new IOException("Virtual display rotation: " + result.output);
        }
        private void awaitRotation(int rotation) throws Exception {
            long deadline = SystemClock.uptimeMillis() + 1800;
            Object global = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null);
            do {
                Object info = global.getClass().getMethod("getDisplayInfo", int.class).invoke(global, display);
                if (info != null && info.getClass().getField("rotation").getInt(info) == rotation
                        && info.getClass().getField("logicalWidth").getInt(info) == viewport.contentWidth(rotation)
                        && info.getClass().getField("logicalHeight").getInt(info) == viewport.contentHeight(rotation)) return;
                Thread.sleep(50);
            } while (SystemClock.uptimeMillis() < deadline);
            throw new IOException("Virtual display did not apply rotation " + rotation);
        }
        synchronized String rotate(int direction) throws Exception {
            if (closed || pendingViewport != null || !outputAvailable || !surface.isValid() || !owner.isBinderAlive())
                throw new IllegalStateException("Rear output unavailable for rotation");
            int previous = contentRotation, next = (previous + direction + 4) % 4;
            touchLease();
            if (!input.beginRotation(inputId)) throw new IOException("Input session ended");
            try {
                // Only our disposable display is changed. Surface pixels remain in the physical safe rectangle.
                command("wm", "fixed-to-user-rotation", "-d", Integer.toString(display), "enabled");
                command("wm", "user-rotation", "-d", Integer.toString(display), "lock", Integer.toString(next));
                awaitRotation(next);
                if (!input.finishRotation(inputId, next, outputAvailable)) throw new IOException("Input session ended");
                contentRotation = next;
                android.util.Log.i("RearVirtualCast", "content_rotated id=" + id + " rotation=" + next);
                return status();
            } catch (Exception failure) {
                try {
                    command("wm", "user-rotation", "-d", Integer.toString(display), "lock", Integer.toString(previous));
                    awaitRotation(previous);
                    if (!input.finishRotation(inputId, previous, outputAvailable)) throw new IOException("Input rollback failed");
                } catch (Exception rollback) { close("rotation_rollback_failed: " + root(rollback)); }
                throw failure;
            } finally { touchLease(); }
        }
        synchronized String beginViewport(boolean full) throws Exception {
            if (closed || pendingViewport != null || !outputAvailable || !surface.isValid() || !owner.isBinderAlive())
                throw new IllegalStateException("Rear output unavailable for viewport change");
            if (!safeViewport.key().equals(RearCastGeometry.read().key())) throw new IOException("Rear geometry changed");
            RearCastViewport next = RearCastCompatibility.viewport(full ? safeViewport.full() : safeViewport, requestedDensity, density);
            if (!input.beginRotation(inputId)) throw new IOException("Input session ended");
            pendingViewport = next;
            outputAvailable = false; viewportDeadline = SystemClock.uptimeMillis() + 5000; healthGeneration.advance();
            touchLease();
            android.util.Log.i("RearVirtualCast", "viewport_begin id=" + id + " target=" + next.key());
            return RearCastGeometry.encode(next);
        }
        synchronized String finishViewport(Surface next) throws Exception {
            boolean adopted = false;
            try {
                if (closed || pendingViewport == null || next == null || !next.isValid() || !owner.isBinderAlive())
                    throw new IllegalStateException("Viewport transaction unavailable");
                touchLease();
                // Resize the same display/task; fix its current rotation before applying the new native dimensions.
                command("wm", "fixed-to-user-rotation", "-d", Integer.toString(display), "enabled");
                command("wm", "user-rotation", "-d", Integer.toString(display), "lock", Integer.toString(contentRotation));
                iface.getMethod("resizeVirtualDisplay", callbackType, int.class, int.class, int.class)
                        .invoke(manager, callback, pendingViewport.bufferWidth, pendingViewport.bufferHeight, density);
                iface.getMethod("setVirtualDisplaySurface", callbackType, Surface.class).invoke(manager, callback, next);
                Surface previous = surface; surface = next; adopted = true; previous.release();
                viewport = pendingViewport;
                awaitRotation(contentRotation);
                if (!input.finishConfiguration(inputId, viewport, contentRotation, true)) throw new IOException("Input session ended");
                pendingViewport = null; outputAvailable = true; healthGeneration.advance();
                android.util.Log.i("RearVirtualCast", "viewport_changed id=" + id + " full=" + viewport.isFull() + " rotation=" + contentRotation);
                return status();
            } catch (Exception e) {
                // Never leave a stretched image with a different touch mapping, or a reader grabbed after a failed resize.
                close("viewport_failed: " + root(e)); throw e;
            } finally {
                if (!adopted && next != null) next.release();
                touchLease();
            }
        }
        synchronized boolean updateSurface(Surface next) throws Exception {
            VirtualCastOutputRecoveryPolicy.SurfaceUpdate decision = VirtualCastOutputRecoveryPolicy.surfaceUpdate(
                    closed, pendingViewport != null, next != null, next == null || next.isValid());
            if (decision == VirtualCastOutputRecoveryPolicy.SurfaceUpdate.SESSION_CLOSED) {
                if (next != null) next.release(); return false;
            }
            if (decision == VirtualCastOutputRecoveryPolicy.SurfaceUpdate.VIEWPORT_INTERRUPTED) {
                if (next != null) next.release();
                close("viewport_output_interrupted"); return false;
            }
            if (decision == VirtualCastOutputRecoveryPolicy.SurfaceUpdate.LOST_TEMPORARILY) {
                if (outputAvailable || surface != null) {
                    if (outputAvailable)
                        iface.getMethod("setVirtualDisplaySurface", callbackType, Surface.class).invoke(manager, callback, (Surface) null);
                    Surface previous = surface;
                    surface = null;
                    outputAvailable = false;
                    healthGeneration.advance();
                    if (previous != null) previous.release();
                    input.outputAvailable(inputId, false);
                    android.util.Log.i("RearVirtualCast", "output_reconnecting id=" + id);
                }
                return true;
            }
            if (decision == VirtualCastOutputRecoveryPolicy.SurfaceUpdate.INVALID_REPLACEMENT) {
                next.release();
                throw new IOException("Replacement Surface invalid");
            }
            boolean adopted = false;
            try {
                iface.getMethod("setVirtualDisplaySurface", callbackType, Surface.class).invoke(manager, callback, next);
                Surface previous = surface;
                surface = next; adopted = true;
                outputAvailable = true; healthGeneration.advance();
                if (previous != null) previous.release();
                if (!input.outputAvailable(inputId, true)) throw new IOException("Input session ended during reconnect");
                android.util.Log.i("RearVirtualCast", "output_reconnected id=" + id);
                return true;
            } finally { if (!adopted) next.release(); }
        }
        synchronized String setOutputAvailable(boolean available, Surface replacement,
                                               ISceneTaskActionGate actionGate) throws Exception {
            if (closed || current != this || !owner.isBinderAlive()) {
                if (replacement != null && replacement != surface) replacement.release();
                return "stale_session";
            }
            if (pendingViewport != null || actionGate == null) {
                if (replacement != null && replacement != surface) replacement.release();
                return "cancelled";
            }
            int rearId = RearDisplay.requireId();
            int stateNow = rearDisplayState(rearId);
            if (!powerTransitionCurrent(available, stateNow, actionGate)) {
                if (replacement != null && replacement != surface) replacement.release();
                return "cancelled";
            }
            if (!available) {
                // Even if the VD flag already says paused, reconcile the reader too. A prior
                // ON attach may have been cancelled after its reader resumed but before the
                // output transition committed.
                // Release the rear input grab before the remote display update so the system
                // can receive its wake gesture even if setVirtualDisplaySurface is slow.
                if (!input.outputAvailable(inputId, false, () -> currentGate(actionGate))) return "cancelled";
                if (!outputAvailable) return "already_paused";
                outputAvailable = false; healthGeneration.advance();
                if (!sessionStillOwnsSleepingOutput(rearId)) return "cancelled";
                iface.getMethod("setVirtualDisplaySurface", callbackType, Surface.class)
                        .invoke(manager, callback, (Surface) null);
                android.util.Log.i("RearVirtualCast", "power_output_paused id=" + id + " surface_retained=" + (surface != null));
                return "paused";
            }
            VirtualCastOutputPowerGate.SurfaceChoice choice = VirtualCastOutputPowerGate.chooseRestoreSurface(
                    surface != null && surface.isValid(), replacement != null, replacement != null && replacement.isValid());
            if (choice == VirtualCastOutputPowerGate.SurfaceChoice.INVALID_REPLACEMENT) {
                replacement.release(); return "surface_unavailable";
            }
            Surface candidate = choice == VirtualCastOutputPowerGate.SurfaceChoice.REPLACEMENT ? replacement
                    : choice == VirtualCastOutputPowerGate.SurfaceChoice.RETAINED ? surface : null;
            if (candidate == null) return "surface_unavailable";
            if (outputAvailable && candidate == surface) {
                if (replacement != null && replacement != surface) replacement.release();
                return "already_available";
            }
            if (!powerTransitionCurrent(true, rearDisplayState(rearId), actionGate)) {
                if (replacement != null && replacement != surface) replacement.release();
                return "cancelled";
            }
            iface.getMethod("setVirtualDisplaySurface", callbackType, Surface.class)
                    .invoke(manager, callback, candidate);
            Surface previous = surface;
            surface = candidate;
            if (previous != null && previous != candidate) previous.release();
            healthGeneration.advance();
            if (!powerTransitionCurrent(true, rearDisplayState(rearId), actionGate)
                    || !input.outputAvailable(inputId, true, () -> currentGate(actionGate))) {
                boolean cleanupOk = VirtualCastOutputPowerGate.rollbackCancelledRestore(
                        () -> {
                            if (!input.outputAvailable(inputId, false))
                                throw new IOException("Input session unavailable during cancelled restore");
                        },
                        () -> iface.getMethod("setVirtualDisplaySurface", callbackType, Surface.class)
                                .invoke(manager, callback, (Surface) null));
                outputAvailable = false; healthGeneration.advance();
                if (!cleanupOk) {
                    close("cancelled_restore_cleanup_failed");
                    return "cleanup_failed";
                }
                return "cancelled";
            }
            outputAvailable = true; healthGeneration.advance();
            android.util.Log.i("RearVirtualCast", "power_output_resumed id=" + id + " same_session=true replacement=" + (candidate != previous));
            return "resumed";
        }
        private boolean powerTransitionCurrent(boolean available, int rearState,
                                               ISceneTaskActionGate actionGate) throws Exception {
            int rearId = RearDisplay.requireId();
            boolean taskStillOwned = service.isTaskOnDisplay(host, rearId);
            return VirtualCastOutputPowerGate.mayCommit(!closed && current == this, owner.isBinderAlive(),
                    taskStillOwned, rearState, available, () -> currentGate(actionGate));
        }
        private boolean sessionStillOwnsSleepingOutput(int rearId) throws Exception {
            return !closed && current == this && owner.isBinderAlive()
                    && service.isTaskOnDisplay(host, rearId)
                    && VirtualCastOutputPowerGate.isSleeping(rearDisplayState(rearId));
        }
        private boolean currentGate(ISceneTaskActionGate actionGate) {
            try { return actionGate != null && actionGate.isCurrent(); }
            catch (RemoteException denied) { return false; }
        }
        private String recordPresentationResult(VirtualCastRecoveryForeground.Decision decision,
                                               String result, int finalPower) {
            if (decision != null && decision.diagnostic != null) {
                android.util.Log.i("RearVirtualCast", "recovery_present "
                        + decision.diagnostic.format(decision.refusal, result, finalPower));
            }
            return result;
        }
        String recoverHost(int requestedHost, int rearDisplayId, int action,
                           ISceneTaskActionGate actionGate) throws Exception {
            if (action != RECOVERY_WAKE_REAR && action != RECOVERY_PRESENT_HOST) return "invalid_action";
            final long generation;
            synchronized (this) {
                if (closed || current != this || requestedHost != host || actionGate == null
                        || rearDisplayId != RearDisplay.requireId() || !owner.isBinderAlive()) return "stale_owner";
                generation = healthGeneration.capture();
            }
            if (!service.isTaskOnDisplay(host, rearDisplayId)) return "host_not_on_rear";
            VirtualCastRecoveryForeground.Decision presentation = null;
            RearDisplayCastOccupancy.Snapshot snapshot = RearDisplayCastOccupancy.Snapshot.parse(
                    service.getDisplayCastOccupancy(rearDisplayId), rearDisplayId);
            if (action == RECOVERY_PRESENT_HOST) {
                presentation = VirtualCastRecoveryForeground.resolve(snapshot, rearDisplayId, host,
                        BuildConfig.APPLICATION_ID);
                if (presentation.disposition == VirtualCastRecoveryForeground.Disposition.HOST_ALREADY_FRONT)
                    return recordPresentationResult(presentation, "already_front", snapshot.displayState);
                if (presentation.disposition == VirtualCastRecoveryForeground.Disposition.OTHER_TASK)
                    return recordPresentationResult(presentation, "preserved_other_task", snapshot.displayState);
                if (presentation.disposition != VirtualCastRecoveryForeground.Disposition.FOCUS_HOST)
                    return recordPresentationResult(presentation, "foreground_unverified", snapshot.displayState);
            } else {
                // A sleeping rear has no meaningful visible top, so require the exact owned host
                // plus known rear launcher(s) and reject every unknown/third-party task.
                presentation = VirtualCastRecoveryForeground.resolveWake(snapshot, rearDisplayId, host,
                        BuildConfig.APPLICATION_ID);
                if (presentation.disposition == VirtualCastRecoveryForeground.Disposition.OTHER_TASK)
                    return "preserved_other_task";
                if (presentation.disposition != VirtualCastRecoveryForeground.Disposition.WAKE_ALLOWED)
                    return "foreground_unverified";
            }

            int powerState = rearDisplayState(rearDisplayId);
            if (action == RECOVERY_PRESENT_HOST) {
                if (powerState != android.view.Display.STATE_ON)
                    return recordPresentationResult(presentation, "rear_not_on", powerState);
            } else {
                if (!isSleepingDisplayState(powerState)) return powerState == android.view.Display.STATE_ON
                        ? "already_on" : "rear_state_unknown";
            }

            // The expensive task/foreground queries are complete. Keep the final state check,
            // caller gate and side effect adjacent; close/updateSurface cannot invalidate the
            // Session between this check and its own shell action.
            synchronized (this) {
                if (closed || current != this || generation != healthGeneration.capture()
                        || !owner.isBinderAlive() || requestedHost != host || rearDisplayId != RearDisplay.requireId())
                    return action == RECOVERY_PRESENT_HOST
                            ? recordPresentationResult(presentation, "stale_session", powerState) : "stale_session";
                if (!actionGate.isCurrent())
                    return action == RECOVERY_PRESENT_HOST
                            ? recordPresentationResult(presentation, "cancelled", powerState) : "cancelled";
                int now = rearDisplayState(rearDisplayId);
                if (action == RECOVERY_WAKE_REAR) {
                    if (!isSleepingDisplayState(now)) return now == android.view.Display.STATE_ON ? "already_on" : "rear_state_unknown";
                    return service.executeShellCommand("input -d " + rearDisplayId + " keyevent KEYCODE_WAKEUP")
                            ? "wake_requested" : "wake_rejected";
                }
                if (now != android.view.Display.STATE_ON)
                    return recordPresentationResult(presentation, "rear_not_on", now);
                String result = VirtualCastRecoveryForeground.perform(presentation, host, rearDisplayId,
                        taskId -> service.executeShellCommand("am task focus " + taskId));
                return recordPresentationResult(presentation, result, now);
            }
        }
        private int rearDisplayState(int displayId) throws Exception {
            JSONObject state = new JSONObject(RearDisplay.state());
            org.json.JSONArray displays = state.optJSONArray("displays");
            if (displays == null) return android.view.Display.STATE_UNKNOWN;
            for (int i = 0; i < displays.length(); i++) {
                JSONObject item = displays.optJSONObject(i);
                if (item != null && item.optInt("id", -1) == displayId)
                    return item.optInt("state", android.view.Display.STATE_UNKNOWN);
            }
            return android.view.Display.STATE_UNKNOWN;
        }
        private boolean isSleepingDisplayState(int state) {
            return state == android.view.Display.STATE_OFF || state == android.view.Display.STATE_DOZE
                    || state == android.view.Display.STATE_DOZE_SUSPEND || state == android.view.Display.STATE_ON_SUSPEND;
        }
        synchronized void close(String reason) {
            if (closed) return;
            closed = true; healthGeneration.advance(); phase = reason; timer.shutdown();
            touchActivityExecutor.shutdownNow();
            if (!inputId.isEmpty()) input.stop(inputId);
            // Also restore tasks newly opened by the app on our display. Never inspect/move other displays.
            if (display > 0 && !RearDisplay.isRear(display)) {
                try {
                    List<Integer> tasks = new ArrayList<>();
                    java.lang.Process p = new ProcessBuilder("am", "stack", "list").redirectErrorStream(true).start();
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                        boolean ours = false; String line;
                        while ((line = r.readLine()) != null) {
                            if (line.startsWith("RootTask ")) ours = line.matches(".*\\bdisplayId=" + display + "\\b.*");
                            if (ours && line.trim().startsWith("taskId=")) {
                                String value = line.trim().substring(7).split(":", 2)[0];
                                tasks.add(Integer.parseInt(value));
                            }
                        }
                    }
                    p.waitFor();
                    for (int t : tasks) service.moveTaskToDisplay(t, 0);
                } catch (Exception e) { android.util.Log.e("RearVirtualCast", "Task restoration", e); }
                try { iface.getMethod("releaseVirtualDisplay", callbackType).invoke(manager, callback); }
                catch (Exception e) { android.util.Log.e("RearVirtualCast", "Display release", e); }
            }
            owner.unlinkToDeath(death, 0);
            if (surface != null) surface.release();
            if (freezeGuard != null) freezeGuard.close();
            // Restore the physical rear only for this closing session, before a new start can proceed.
            try {
                if (physicalIdentity.equals(RearDisplay.identity())) {
                    String foreground = service.getForegroundAppOnDisplay(RearDisplay.requireId());
                    if (foreground == null || foreground.endsWith(":" + host)
                            || foreground.startsWith("com.xiaomi.subscreencenter:") || foreground.startsWith("com.xiaomi.mirror:"))
                        RearSystemBridge.restoreLauncher();
                    else CompatibilityDiagnostics.record("launcher_restore_skipped", "A newer rear task owns the display: " + foreground);
                }
            }
            catch (Exception e) { android.util.Log.w("RearVirtualCast", "Rear launcher restoration", e); }
            CompatibilityDiagnostics.record("virtual_closed", "reason=" + reason);
            if (firstTouchActivityLogged.get() || touchActivityUnavailableLogged.get())
                android.util.Log.i("RearVirtualCast", "touch_activity_summary session=" + id + " display="
                        + physicalRearId + " " + touchActivityReporter.summary());
            last = status();
            android.util.Log.i("RearVirtualCast", last);
            if (current == this) current = null;
        }
        // A stopped result means resource restoration has finished, including asynchronous watchdog closes.
        synchronized String status() {
            try { return new JSONObject().put("id", id).put("state", closed ? "stopped" : phase)
                    .put("reason", phase).put("display", display).put("task", task).put("host", host)
                    .put("package", packageName).put("density", density).put("requestedDensity", requestedDensity)
                    .put("compatibility", densityCompatibility ? "source_density" : "none").put("outputAvailable", outputAvailable)
                    .put("sizePolicy", sizePolicy)
                    .put("freezeProtection", freezeGuard == null ? "unavailable" : freezeGuard.mode)
                    .put("contentRotation", contentRotation).put("viewport", new JSONObject(RearCastGeometry.encode(viewport)))
                    .put("fullViewport", viewport.isFull()).put("viewportChanging", pendingViewport != null)
                    .put("input", new JSONObject(input.status())).toString(); }
            catch (Exception e) { return "{}"; }
        }
    }
}
