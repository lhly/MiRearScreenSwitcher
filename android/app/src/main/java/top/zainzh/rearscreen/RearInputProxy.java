package top.zainzh.rearscreen;

import android.os.IBinder;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Session-scoped rear input relay running as the Shizuku shell user. */
final class RearInputProxy {
    interface Guard { boolean allowed(int task, int display) throws Exception; }
    interface AcceptedInteraction { void afterRouterFrameAccepted(long eventTimeUptime); }
    private final Guard guard;
    private volatile Session current;
    private volatile String lastStatus = "{\"state\":\"stopped\"}";
    RearInputProxy(Guard guard) { this.guard = guard; }
    private static boolean waitForProcess(Process process, long milliseconds) throws InterruptedException {
        long deadline = SystemClock.uptimeMillis() + milliseconds;
        do {
            try { process.exitValue(); return true; }
            catch (IllegalThreadStateException running) { }
            Thread.sleep(Math.min(20, Math.max(1, deadline - SystemClock.uptimeMillis())));
        } while (SystemClock.uptimeMillis() < deadline);
        return false;
    }
    synchronized String start(String path, int task, IBinder owner) throws Exception {
        return start(path, task, owner, RearDisplay.requireId());
    }
    synchronized String start(String path, int task, IBinder owner, int display) throws Exception {
        return start(path, task, owner, display, null);
    }
    synchronized String start(String path, int task, IBinder owner, int display, RearTouchRouter.Feedback feedback) throws Exception {
        return start(path, task, owner, display, feedback, null);
    }
    synchronized String start(String path, int task, IBinder owner, int display, RearTouchRouter.Feedback feedback, RearCastViewport viewport) throws Exception {
        return start(path, task, owner, display, feedback, viewport, null);
    }
    synchronized String start(String path, int task, IBinder owner, int display, RearTouchRouter.Feedback feedback,
                              RearCastViewport viewport, RearTouchRouter.Menu menu) throws Exception {
        return start(path, task, owner, display, feedback, viewport, menu, 0);
    }
    synchronized String start(String path, int task, IBinder owner, int display, RearTouchRouter.Feedback feedback,
                              RearCastViewport viewport, RearTouchRouter.Menu menu, int contentRotation) throws Exception {
        return start(path, task, owner, display, feedback, viewport, menu, contentRotation, null);
    }
    synchronized String start(String path, int task, IBinder owner, int display, RearTouchRouter.Feedback feedback,
                              RearCastViewport viewport, RearTouchRouter.Menu menu, int contentRotation,
                              AcceptedInteraction acceptedInteraction) throws Exception {
        if (!BuildConfig.VIRTUAL_CAST_ENABLED && !BuildConfig.INPUT_PROXY_PROTOTYPE) throw new IllegalStateException("Rear input relay is unavailable");
        RearCastViewport.validateContentRotation(contentRotation);
        if (display < 1 || owner == null || !owner.isBinderAlive() || task < 0 || !guard.allowed(task, display)) throw new IllegalStateException("Rear task/owner unavailable");
        File file = new File(path).getCanonicalFile();
        if (!file.getPath().startsWith("/data/app/") || !file.getPath().contains("/" + BuildConfig.APPLICATION_ID + "-")
                || !file.getName().equals("libremrss_input_proxy.so") || !file.canExecute()) throw new SecurityException("Reader must belong to this installed APK");
        stop(null);
        Session next = new Session(file.getPath(), task, owner, display, feedback, viewport, menu, contentRotation, acceptedInteraction);
        current = next;
        try { next.begin(); return next.status(); }
        catch (Exception e) { CompatibilityDiagnostics.record("input_start_failed", e.toString()); next.close(e.toString()); throw e; }
    }
    synchronized void stop(String id) {
        Session s = current;
        if (s != null && (id == null || s.id.equals(id))) s.close("stopped");
    }
    void taskLeaving(int task) { Session s = current; if (s != null && s.task == task) stop(s.id); }
    String status() { Session s = current; return s == null ? lastStatus : s.status(); }
    boolean notification(String id, long token, int left, int top, int right, int bottom, RearTouchRouter.NotificationDismiss dismiss) {
        Session s = current;
        if (s == null || !s.id.equals(id)) return false;
        synchronized (s) {
            if (s.closed) return false;
            s.router.notification(token, left, top, right, bottom, dismiss); return true;
        }
    }
    boolean renew(String id) {
        Session s = current;
        if (s == null || !s.id.equals(id) || s.closed) return false;
        s.lease = SystemClock.uptimeMillis(); return true;
    }
    boolean outputAvailable(String id, boolean available) throws Exception {
        return outputAvailable(id, available, null);
    }
    boolean outputAvailable(String id, boolean available, BooleanSupplier actionCurrent) throws Exception {
        Session s = current;
        if (s == null || !s.id.equals(id)) return false;
        synchronized (s) {
            if (s.closed) return false;
            if (actionCurrent != null && !actionCurrent.getAsBoolean()) return false;
            s.setOutputAvailableLocked(available);
            return true;
        }
    }
    void dismissMenu(String id) throws Exception {
        Session s = current;
        if (s != null && s.id.equals(id)) synchronized (s) { if (!s.closed) s.router.dismissMenu(SystemClock.uptimeMillis()); }
    }
    boolean beginRotation(String id) throws Exception {
        Session s = current;
        if (s == null || !s.id.equals(id)) return false;
        synchronized (s) {
            if (s.closed) return false;
            s.configuring = true; s.setOutputAvailableLocked(false); return true;
        }
    }
    boolean finishRotation(String id, int rotation, boolean available) throws Exception {
        Session s = current;
        return s != null && finishConfiguration(id, s.viewport, rotation, available);
    }
    boolean finishConfiguration(String id, RearCastViewport viewport, int rotation, boolean available) throws Exception {
        Session s = current;
        if (s == null || !s.id.equals(id)) return false;
        synchronized (s) {
            if (s.closed) return false;
            s.viewport = viewport; s.contentRotation = rotation; s.configuring = false; s.geometry();
            s.state.configure(viewport, rotation, SystemClock.uptimeMillis());
            s.setOutputAvailableLocked(available); return true;
        }
    }
    private final class Session {
        final String id = UUID.randomUUID().toString(), path;
        final int task, display;
        final String physicalIdentity = RearDisplay.identity();
        final IBinder owner;
        final RearTouchRouter.Feedback feedback;
        final RearTouchRouter.Menu menu;
        final AcceptedInteraction acceptedInteraction;
        volatile boolean configuring;
        volatile int contentRotation;
        final RearCastViewport safeViewport;
        volatile RearCastViewport viewport;
        final IBinder.DeathRecipient death = () -> close("owner_died");
        final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        // Task validation can execute shell commands; it must not delay gesture readiness.
        final ScheduledExecutorService gestureTimer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "rear-gesture-clock"); thread.setDaemon(true); return thread;
        });
        final PausableGestureClock gestureClock;
        volatile long lease = SystemClock.uptimeMillis(), frames, events, backs;
        volatile boolean closed, ready;
        volatile boolean outputAvailable = true;
        volatile boolean resumeRequested;
        long outputEpoch;
        long resumeRequestedEpoch = -1;
        boolean readerReadySeen;
        volatile String reason = "starting", node = "";
        Process process;
        BufferedWriter commands;
        RearTouchState state;
        RearTouchRouter router;
        int xmin, xmax, ymin, ymax, slots, width, height, rotation, density;
        Session(String path, int task, IBinder owner, int display, RearTouchRouter.Feedback feedback,
                RearCastViewport viewport, RearTouchRouter.Menu menu, int contentRotation,
                AcceptedInteraction acceptedInteraction) throws Exception {
            this.path = path; this.task = task; this.owner = owner; this.display = display; this.feedback = feedback;
            this.viewport = viewport; this.safeViewport = viewport == null ? null : RearCastGeometry.read(); this.menu = menu;
            this.contentRotation = contentRotation; this.acceptedInteraction = acceptedInteraction;
            gestureClock = new PausableGestureClock(gestureTimer, () -> {
                try { if (!closed && outputAvailable && router != null) router.tick(SystemClock.uptimeMillis()); }
                catch (Exception e) { close(e.toString()); }
            }, 20);
        }
        void begin() throws Exception {
            owner.linkToDeath(death, 0);
            RearInputInjector injector = RearInputInjector.shell(display);
            state = new RearTouchState(new RearTouchState.Output() {
                public void event(int action, int[] ids, float[] xs, float[] ys, long down, long time) throws Exception {
                    injector.touch(action, ids, xs, ys, down, time);
                    events++;
                }
                public void ended(float x, float y) throws Exception {
                    if (RearDisplay.isRear(display)) injector.resetSystemGesture("scroll", x, y);
                }
            }, viewport);
            state.configure(viewport, contentRotation, SystemClock.uptimeMillis());
            RearInputPolicy.Device device = RearInputDevices.require();
            node = device.path;
            geometry();
            router = new RearTouchRouter(state, width, height, density, !RearDisplay.isRear(display), time -> {
                if (!outputAvailable || !guard.allowed(task, display)) throw new IOException("Virtual Back target left");
                injector.back(time); backs++;
                android.util.Log.i("RearInputProxy", "back_sent id=" + id + " display=" + display + " task=" + task);
            }, feedback);
            router.setMenu(menu);
            gestureClock.resume();
            process = new ProcessBuilder(path, "--stream", node, device.name, Integer.toString(device.xmin), Integer.toString(device.xmax),
                    Integer.toString(device.ymin), Integer.toString(device.ymax), Integer.toString(device.slots)).redirectErrorStream(true).start();
            commands = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            CountDownLatch started = new CountDownLatch(1);
            new Thread(() -> read(started), "rear-input-reader").start();
            // Both the native watchdog and this lease are independent of Activity destruction.
            timer.scheduleAtFixedRate(() -> {
                try {
                    if (SystemClock.uptimeMillis() - lease > 4000) { close("lease_expired"); return; }
                    if (!owner.isBinderAlive() || !guard.allowed(task, display)) { close("task_or_owner_left"); return; }
                    synchronized (this) { if (!closed) { commands.write("P\n"); commands.flush(); } }
                } catch (Exception e) { close(e.toString()); }
            }, 500, 500, TimeUnit.MILLISECONDS);
            if (!started.await(3500, TimeUnit.MILLISECONDS) || !ready || closed) throw new IOException("Rear grab failed: " + reason);
        }
        synchronized void setOutputAvailableLocked(boolean available) throws Exception {
            if (closed) return;
            if (!available) {
                boolean cancelResume = resumeRequested;
                resumeRequested = false;
                resumeRequestedEpoch = -1;
                if (!outputAvailable) {
                    if (cancelResume && commands != null) {
                        outputEpoch++; commands.write("S " + outputEpoch + "\n"); commands.flush();
                    }
                    return;
                }
                outputEpoch++;
                router.outputAvailable(false, SystemClock.uptimeMillis());
                outputAvailable = false;
                gestureClock.pause();
                router.cancel(SystemClock.uptimeMillis()); router.frame(Collections.emptyList(), SystemClock.uptimeMillis());
                if (commands != null) { commands.write("S " + outputEpoch + "\n"); commands.flush(); }
                return;
            }
            if (outputAvailable || resumeRequested) return;
            resumeRequested = true;
            resumeRequestedEpoch = ++outputEpoch;
            // Input and the 20 ms gesture clock remain paused until native confirms the
            // EVIOCGRAB was safely reacquired after all contacts ended.
            if (commands != null) { commands.write("R " + resumeRequestedEpoch + "\n"); commands.flush(); }
        }
        synchronized void geometry() throws Exception {
            if (!physicalIdentity.equals(RearDisplay.identity())) throw new IOException("Rear identity changed; restart casting");
            Object global = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null);
            Object info = global.getClass().getMethod("getDisplayInfo", int.class).invoke(global, RearDisplay.requireId());
            if (info == null) throw new IOException("Rear display missing");
            width = info.getClass().getField("logicalWidth").getInt(info);
            height = info.getClass().getField("logicalHeight").getInt(info);
            rotation = info.getClass().getField("rotation").getInt(info);
            density = info.getClass().getField("logicalDensityDpi").getInt(info);
            if (width < 2 || height < 2) throw new IOException("Invalid rear dimensions");
            if (!RearDisplay.isRear(display)) {
                Object target = global.getClass().getMethod("getDisplayInfo", int.class).invoke(global, display);
                if (viewport == null || !safeViewport.key().equals(RearCastGeometry.read().key()) || target == null
                        || (!configuring && (target.getClass().getField("rotation").getInt(target) != contentRotation
                        || viewport.contentWidth(contentRotation) != target.getClass().getField("logicalWidth").getInt(target)
                        || viewport.contentHeight(contentRotation) != target.getClass().getField("logicalHeight").getInt(target))))
                    throw new IOException("Virtual/rear geometry changed; restart casting");
            }
        }
        void read(CountDownLatch started) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while (!closed && (line = r.readLine()) != null) {
                    String[] parts = line.split(" ");
                    if (RearInputReaderLineProtocol.discardFrameIfNotReady(parts, ready && outputAvailable && !closed, slots)) continue;
                    if (parts[0].equals("READY2")) {
                        if (parts.length != 8 || !parts[2].equals(node)) throw new IOException("Invalid reader handshake");
                        long ackEpoch = Long.parseLong(parts[1]);
                        slots = Integer.parseInt(parts[3]); xmin = Integer.parseInt(parts[4]); xmax = Integer.parseInt(parts[5]);
                        ymin = Integer.parseInt(parts[6]); ymax = Integer.parseInt(parts[7]);
                        if (slots < 1 || slots > 16 || xmax <= xmin || ymax <= ymin) throw new IOException("Invalid reader ranges");
                        synchronized (this) {
                            boolean accept = RearInputReaderAckGate.acceptReady(ackEpoch, outputEpoch,
                                    resumeRequestedEpoch, outputAvailable, closed);
                            if (accept) {
                                boolean resumed = resumeRequestedEpoch >= 0 && ackEpoch == resumeRequestedEpoch;
                                readerReadySeen = true;
                                if (resumed) {
                                    resumeRequested = false; resumeRequestedEpoch = -1; outputAvailable = true;
                                    router.outputAvailable(true, SystemClock.uptimeMillis()); gestureClock.resume();
                                }
                                ready = true; reason = "running"; started.countDown();
                            }
                        }
                        android.util.Log.i("RearInputProxy", "session_ready id=" + id + " task=" + task + " available=" + outputAvailable);
                    } else if (parts[0].equals("PAUSED")) {
                        synchronized (this) {
                            if (parts.length != 2) throw new IOException("Invalid reader pause ACK");
                            long ackEpoch = Long.parseLong(parts[1]);
                            if (RearInputReaderAckGate.acceptPaused(ackEpoch, outputEpoch, outputAvailable, closed)) ready = false;
                        }
                        android.util.Log.i("RearInputProxy", "session_input_released id=" + id);
                    } else if (parts[0].equals("RESET")) { synchronized (this) { router.cancel(SystemClock.uptimeMillis()); router.frame(Collections.emptyList(), SystemClock.uptimeMillis()); } }
                    else if (parts[0].equals("F")) {
                        int count = Integer.parseInt(parts[2]);
                        if (count < 0 || count > slots || parts.length != count + 3) throw new IOException("Invalid rear frame");
                        int oldRotation = rotation, oldWidth = width, oldHeight = height, oldDensity = density;
                        geometry();
                        boolean geometryChanged = rotation != oldRotation || width != oldWidth || height != oldHeight || density != oldDensity;
                        List<RearTouchState.Contact> contacts = new ArrayList<>();
                        for (int i = 0; i < count; i++) {
                            String[] c = parts[i + 3].split(":");
                            if (c.length != 4) throw new IOException("Invalid rear contact");
                            float[] xy = RearTouchState.coordinate((Float.parseFloat(c[2]) - xmin) / (xmax - xmin),
                                    (Float.parseFloat(c[3]) - ymin) / (ymax - ymin), width, height, rotation);
                            contacts.add(new RearTouchState.Contact(Integer.parseInt(c[0]), Integer.parseInt(c[1]), xy[0], xy[1]));
                        }
                        long eventTime = Math.min(SystemClock.uptimeMillis(), Long.parseLong(parts[1]));
                        boolean interactionAccepted;
                        synchronized (this) {
                            if (closed) break;
                            // A frame buffered just before the native S/PAUSED handshake is not
                            // active user input once the owning output has been paused.
                            if (!ready || !outputAvailable) continue;
                            if (geometryChanged) {
                                router.cancel(SystemClock.uptimeMillis()); router.geometry(width, height, density);
                            }
                            frames++;
                            interactionAccepted = router.frame(contacts, eventTime);
                        }
                        // Task/power inspection can block. Keep it outside the InputSession monitor so
                        // pause/close can invalidate the report while its producer query is in flight.
                        try { dispatchAcceptedRouterInteraction(interactionAccepted, eventTime, acceptedInteraction); }
                        catch (RuntimeException ignored) { /* Activity reporting must not interrupt accepted input. */ }
                    } else if (!parts[0].equals("RELEASED")) throw new IOException(line);
                }
                close("reader_exited");
            } catch (Exception e) { close(e.toString()); }
            finally { started.countDown(); }
        }
        synchronized void close(String why) {
            if (closed) return;
            closed = true; ready = false; reason = why; timer.shutdownNow(); gestureClock.close();
            try { if (router != null) router.cancel(SystemClock.uptimeMillis()); else if (state != null) state.cancel(SystemClock.uptimeMillis()); } catch (Exception ignored) { }
            try { if (commands != null) { commands.write("Q\n"); commands.flush(); commands.close(); } } catch (Exception ignored) { }
            if (process != null) {
                try { if (!waitForProcess(process, 700)) process.destroy(); }
                catch (InterruptedException e) { process.destroy(); Thread.currentThread().interrupt(); }
            }
            owner.unlinkToDeath(death, 0);
            lastStatus = status();
            android.util.Log.i("RearInputProxy", "session_stopped id=" + id + " task=" + task
                    + " reason=" + reason + " frames=" + frames + " events=" + events);
            if (current == this) current = null;
        }
        String status() {
            try { return new JSONObject().put("id", id).put("state", closed ? "stopped" : reason)
                    .put("reason", reason).put("node", node).put("task", task).put("display", display).put("outputAvailable", outputAvailable).put("frames", frames).put("events", events).put("backs", backs).put("contentRotation", contentRotation).put("menuOpen", router != null && router.isMenuOpen()).toString(); }
            catch (Exception e) { return "{}"; }
        }
    }

    /** Called only for an accepted contact or matched final-UP frame, never RESET/CANCEL or a clock tick. */
    static boolean dispatchAcceptedRouterInteraction(boolean accepted, long eventTimeUptime, AcceptedInteraction callback) {
        if (!accepted || callback == null) return false;
        callback.afterRouterFrameAccepted(eventTimeUptime);
        return true;
    }

}
