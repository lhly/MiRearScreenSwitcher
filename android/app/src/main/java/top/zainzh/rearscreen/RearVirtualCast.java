package top.zainzh.rearscreen;

import android.os.*;
import android.hardware.display.DisplayManager;
import android.view.Surface;
import org.json.JSONObject;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;

/** Owns a virtual display and presents its Surface on the physical rear display. */
final class RearVirtualCast {
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
                || (task < 0 ? component == null : !service.isTaskOnDisplay(task, 0)) || !service.isTaskOnDisplay(host, 1))
            throw new IllegalStateException("Main task / rear host unavailable");
        stop(null);
        input.stop(null);
        Session s = new Session(surface, reader, task, host, density, component, owner, gestures, safe, plan.getInt("renderDensity"), compatibility, rotation, full);
        current = s;
        try { s.begin(); return s.status(); }
        catch (Exception e) { s.close("start_failed: " + root(e)); throw e; }
    }
    synchronized void stop(String id) { Session s = current; if (s != null && (id == null || s.id.equals(id))) s.close("stopped"); }
    String status() { Session s = current; return s == null ? last : s.status(); }
    boolean updateSurface(String id, Surface surface) throws Exception {
        Session s = current;
        if (s == null || !s.id.equals(id)) { if (surface != null) surface.release(); return false; }
        return s.updateSurface(surface);
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
        s.lease = SystemClock.uptimeMillis(); return true;
    }
    private static Throwable root(Throwable e) { while (e.getCause() != null) e = e.getCause(); return e; }

    private final class Session {
        final String id = UUID.randomUUID().toString(), reader;
        volatile int task;
        final int host, density, requestedDensity;
        final String packageName, component;
        final boolean densityCompatibility;
        volatile Surface surface;
        volatile boolean outputAvailable = true;
        volatile long outputDeadline;
        final IBinder owner;
        final IRearGestureListener gestures;
        final RearCastViewport safeViewport;
        volatile RearCastViewport viewport;
        volatile RearCastViewport pendingViewport;
        final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        final IBinder.DeathRecipient death = () -> close("owner_died");
        volatile boolean closed;
        volatile long lease = SystemClock.uptimeMillis();
        volatile String phase = "starting", inputId = "";
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
            Object config = builderType.getMethod("build").invoke(builder);
            display = (Integer) iface.getMethod("createVirtualDisplay", config.getClass(), callbackType,
                    Class.forName("android.media.projection.IMediaProjection"), String.class)
                    .invoke(manager, config, callback, null, "com.android.shell");
            if (display < 2) throw new IOException("Virtual display creation failed: " + display);
            // Apply the saved orientation before moving the task or enabling hardware input.
            command("wm", "fixed-to-user-rotation", "-d", Integer.toString(display), "enabled");
            command("wm", "user-rotation", "-d", Integer.toString(display), "lock", Integer.toString(contentRotation));
            awaitRotation(contentRotation);
            if (task >= 0 && !service.moveTaskToDisplay(task, display)) throw new IOException("Task move failed");
            if (component != null) {
                if (!service.executeShellCommand("am start --display " + display + " -n '" + component + "'"))
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
            }, contentRotation));
            inputId = started.getString("id");
            lease = SystemClock.uptimeMillis();
            phase = "running";
            timer.scheduleAtFixedRate(() -> {
                try {
                    if (SystemClock.uptimeMillis() - lease > 4000) { close("lease_expired"); return; }
                    if (!outputAvailable && SystemClock.uptimeMillis() > outputDeadline) { close("output_reconnect_timeout"); return; }
                    if (!owner.isBinderAlive() || (outputAvailable && !surface.isValid()) || !service.isTaskOnDisplay(host, 1)
                            || !service.isTaskOnDisplay(task, display) || !input.renew(inputId)) close("host_task_or_input_left");
                } catch (Exception e) { close(root(e).toString()); }
            }, 500, 500, TimeUnit.MILLISECONDS);
            android.util.Log.i("RearVirtualCast", status());
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
            lease = SystemClock.uptimeMillis();
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
            } finally { lease = SystemClock.uptimeMillis(); }
        }
        synchronized String beginViewport(boolean full) throws Exception {
            if (closed || pendingViewport != null || !outputAvailable || !surface.isValid() || !owner.isBinderAlive())
                throw new IllegalStateException("Rear output unavailable for viewport change");
            if (!safeViewport.key().equals(RearCastGeometry.read().key())) throw new IOException("Rear geometry changed");
            RearCastViewport next = RearCastCompatibility.viewport(full ? safeViewport.full() : safeViewport, requestedDensity, density);
            if (!input.beginRotation(inputId)) throw new IOException("Input session ended");
            pendingViewport = next;
            outputAvailable = false; outputDeadline = SystemClock.uptimeMillis() + 5000;
            lease = SystemClock.uptimeMillis();
            android.util.Log.i("RearVirtualCast", "viewport_begin id=" + id + " target=" + next.key());
            return RearCastGeometry.encode(next);
        }
        synchronized String finishViewport(Surface next) throws Exception {
            boolean adopted = false;
            try {
                if (closed || pendingViewport == null || next == null || !next.isValid() || !owner.isBinderAlive())
                    throw new IllegalStateException("Viewport transaction unavailable");
                lease = SystemClock.uptimeMillis();
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
                pendingViewport = null; outputAvailable = true;
                android.util.Log.i("RearVirtualCast", "viewport_changed id=" + id + " full=" + viewport.isFull() + " rotation=" + contentRotation);
                return status();
            } catch (Exception e) {
                // Never leave a stretched image with a different touch mapping, or a reader grabbed after a failed resize.
                close("viewport_failed: " + root(e)); throw e;
            } finally {
                if (!adopted && next != null) next.release();
                lease = SystemClock.uptimeMillis();
            }
        }
        synchronized boolean updateSurface(Surface next) throws Exception {
            if (closed) { if (next != null) next.release(); return false; }
            if (pendingViewport != null) {
                if (next != null) next.release();
                close("viewport_output_interrupted"); return false;
            }
            if (next == null) {
                if (outputAvailable) {
                    outputDeadline = SystemClock.uptimeMillis() + 2500;
                    outputAvailable = false;
                    input.outputAvailable(inputId, false);
                    android.util.Log.i("RearVirtualCast", "output_reconnecting id=" + id);
                }
                return true;
            }
            boolean adopted = false;
            try {
                if (!next.isValid()) throw new IOException("Replacement Surface invalid");
                iface.getMethod("setVirtualDisplaySurface", callbackType, Surface.class).invoke(manager, callback, next);
                Surface previous = surface;
                surface = next; adopted = true;
                previous.release();
                if (!input.outputAvailable(inputId, true)) throw new IOException("Input session ended during reconnect");
                outputAvailable = true;
                android.util.Log.i("RearVirtualCast", "output_reconnected id=" + id);
                return true;
            } finally { if (!adopted) next.release(); }
        }
        synchronized void close(String reason) {
            if (closed) return;
            closed = true; phase = reason; timer.shutdown();
            if (!inputId.isEmpty()) input.stop(inputId);
            // Also restore tasks newly opened by the app on our display. Never inspect/move other displays.
            if (display >= 2) {
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
            surface.release();
            // Restore the physical rear only for this closing session, before a new start can proceed.
            try { service.executeShellCommand("am start --display 1 -n com.xiaomi.subscreencenter/.SubScreenLauncher"); }
            catch (Exception e) { android.util.Log.w("RearVirtualCast", "Rear launcher restoration", e); }
            last = status();
            android.util.Log.i("RearVirtualCast", last);
            if (current == this) current = null;
        }
        String status() {
            try { return new JSONObject().put("id", id).put("state", closed ? "stopped" : phase)
                    .put("reason", phase).put("display", display).put("task", task).put("host", host)
                    .put("package", packageName).put("density", density).put("requestedDensity", requestedDensity)
                    .put("compatibility", densityCompatibility ? "source_density" : "none").put("outputAvailable", outputAvailable)
                    .put("contentRotation", contentRotation).put("viewport", new JSONObject(RearCastGeometry.encode(viewport)))
                    .put("fullViewport", viewport.isFull()).put("viewportChanging", pendingViewport != null)
                    .put("input", new JSONObject(input.status())).toString(); }
            catch (Exception e) { return "{}"; }
        }
    }
}
