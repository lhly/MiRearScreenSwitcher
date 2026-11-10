package top.zainzh.rearscreen;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.FrameLayout;
import java.io.File;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Hosts the rear output Surface and controls the owned virtual casting session. */
@androidx.annotation.RequiresApi(33)
public final class VirtualRearHostActivity extends androidx.activity.ComponentActivity implements SurfaceHolder.Callback {
    public static volatile VirtualRearHostActivity instance;
    public volatile String status = "{}";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final IBinder owner = new Binder();
    private volatile ITaskService service;
    private volatile SurfaceHolder holder;
    private SurfaceView output;
    private FrameLayout root;
    private RearCastViewport viewport;
    private RearCastViewport safeViewport;
    private boolean densityCompatibility;
    private int requestedLayoutDensity = VirtualCastRequest.DEFAULT_DENSITY_DPI;
    private int initialRotation;
    private boolean initialFull;
    private volatile String sessionPackage;
    private boolean resizing, resizeSubmitted;
    private boolean viewportPlaced;
    private String lastViewportLayout;
    private volatile String session;
    private volatile boolean starting;
    private volatile boolean reconnecting;
    private volatile boolean powerSuspendedOutput;
    private volatile boolean surfaceConfigured;
    private final VirtualCastOutputRecoveryPolicy.MissingSurfacePresentationGate missingSurfacePresentationGate =
            new VirtualCastOutputRecoveryPolicy.MissingSurfacePresentationGate();
    private int missingSurfaceLossEpoch;
    private int outputTimeoutLossEpoch;
    private int outputTimeoutId;
    private volatile int outputGeneration;
    private volatile boolean ending;
    private volatile int recoveryActionEpoch;
    private volatile int screenOffCycleEpoch;
    private volatile int outputPowerEpoch;
    private volatile int gesturePreviewGeneration;
    private RearBackGestureView backIndicator;
    private RearCastMenuUi castMenu;
    private RearCameraGapBatteryController cameraGapBattery;
    private RearCameraStatusController cameraStatus;
    private RearCastNotificationOverlay castNotification;
    private boolean hostResumed;
    private boolean primaryInteractive;
    private boolean primaryKeyguardLocked;
    private int observedRearDisplayState = android.view.Display.STATE_UNKNOWN;
    private final android.hardware.display.DisplayManager.DisplayListener rearDisplayListener = new android.hardware.display.DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) { }
        @Override public void onDisplayRemoved(int displayId) { }
        @Override public void onDisplayChanged(int displayId) {
            if (displayId != RearDisplay.id()) return;
            android.view.Display display = getSystemService(android.hardware.display.DisplayManager.class).getDisplay(displayId);
            int state = display == null ? android.view.Display.STATE_UNKNOWN : display.getState();
            int previous = observedRearDisplayState;
            observedRearDisplayState = state;
            if (VirtualCastOutputRecoveryPolicy.isRearDisplaySuspendEdge(starting && session != null, ending, previous, state))
                main.post(VirtualRearHostActivity.this::onRearDisplaySuspended);
            else if (VirtualCastOutputRecoveryPolicy.isRearDisplayResumeEdge(starting && session != null, ending, previous, state))
                main.post(() -> VirtualRearHostActivity.this.onRearDisplayResumed(true));
        }
    };
    private final RearCastNotification.Target notificationTarget = new RearCastNotification.Target() {
        public void show(RearCastNotification.Message message) {
            main.post(() -> { if (!ending && instance == VirtualRearHostActivity.this && castNotification != null) castNotification.offer(message); });
        }
        public void clearNotification(long token) {
            clearNotification(token, "CANCELLED");
        }
        public void clearNotification(long token, String reason) {
            main.post(() -> { if (!ending && instance == VirtualRearHostActivity.this && castNotification != null) castNotification.dismiss(token, reason); });
        }
        public void clearCharging() {
            main.post(() -> { if (!ending && instance == VirtualRearHostActivity.this && castNotification != null) castNotification.dismissCharging(); });
        }
        public void presentationAvailable() {
            main.post(() -> {
                if (!ending && instance == VirtualRearHostActivity.this && session != null)
                    top.zainzh.rearscreen.platform.RearCapabilityCore.get(VirtualRearHostActivity.this)
                            .onNotificationPresentationAvailable();
            });
        }
    };
    private boolean menuVisible, menuBusy;
    private final Runnable viewportTimeout = () -> { if (resizing) fail(new IllegalStateException("Rear viewport change timed out")); };
    private void toggleViewport() {
        if (ending || menuBusy || session == null || reconnecting || resizing) return;
        menuBusy = true; castMenu.setBusy(true); menuVisible = false; castMenu.show(false); clearFeedback();
        resizing = true; resizeSubmitted = false; outputGeneration++;
        updateCameraGapBattery();
        main.postDelayed(viewportTimeout, 4500);
        String id = session; boolean full = !viewport.isFull();
        worker.execute(() -> {
            try {
                if (service == null) throw new IllegalStateException("Control service disconnected");
                RearCastViewport next = RearCastGeometry.decode(service.beginRearCastViewport(id, full));
                main.post(() -> {
                    if (ending || !id.equals(session)) return;
                    viewport = next; viewportPlaced = false; layoutViewport();
                });
            } catch (Exception e) { fail(e); }
        });
    }
    private void maybeFinishViewport() {
        if (!resizing || resizeSubmitted || ending || !viewportPlaced || holder == null
                || width != viewport.bufferWidth || height != viewport.bufferHeight || !holder.getSurface().isValid()) return;
        resizeSubmitted = true;
        String id = session; Surface surface = holder.getSurface();
        worker.execute(() -> {
            try {
                if (service == null) throw new IllegalStateException("Control service disconnected");
                String confirmed = service.finishRearCastViewport(id, surface);
                status = confirmed;
                rememberPresentation(confirmed, false);
                main.post(() -> {
                    if (ending || !id.equals(session)) return;
                    resizing = false; resizeSubmitted = false; menuBusy = false;
                    updateCameraGapBattery();
                    castMenu.updateViewportMode(viewport.isFull()); castMenu.setBusy(false);
                    main.removeCallbacks(viewportTimeout);
                    android.util.Log.i("VirtualRearHost", status);
                });
            } catch (Exception e) { fail(e); }
        });
    }
    private void dismissMenu() {
        if (ending || menuBusy || !menuVisible) return;
        castMenu.show(false); menuVisible = false; updateCastNotification();
        String id = session;
        worker.execute(() -> { try { if (service != null && id != null) service.dismissRearCastMenu(id); } catch (Exception e) { fail(e); } });
    }
    private void rotateContent(int direction) {
        if (ending || menuBusy || session == null || reconnecting) return;
        menuBusy = true; castMenu.setBusy(true); menuVisible = false; castMenu.show(false); String id = session;
        updateCastNotification();
        worker.execute(() -> {
            try {
                if (service == null) throw new IllegalStateException("Control service disconnected");
                String confirmed = service.rotateRearVirtualCast(id, direction);
                status = confirmed;
                rememberPresentation(confirmed, true);
                main.post(() -> { menuBusy = false; castMenu.setBusy(false); updateCastNotification(); });
            } catch (Exception e) {
                android.util.Log.w("VirtualRearHost", "Content rotation failed", e);
                main.post(() -> {
                    if (ending) return;
                    menuBusy = false; castMenu.setBusy(false); updateCastNotification();
                    android.widget.Toast.makeText(this, R.string.rear_cast_menu_failed, android.widget.Toast.LENGTH_SHORT).show();
                });
            }
        });
    }
    private void rememberPresentation(String confirmed, boolean rotationChanged) throws Exception {
        JSONObject state = new JSONObject(confirmed);
        if (ending || !"running".equals(state.optString("state")) || !state.optString("id").equals(session)
                || sessionPackage == null || !sessionPackage.equals(state.getString("package"))) return;
        if (rotationChanged) RearPresentationPreferences.saveRotation(this, sessionPackage, state.getInt("contentRotation"));
        else RearPresentationPreferences.saveViewport(this, sessionPackage, state.getBoolean("fullViewport"));
    }
    private void renderMenu(String id, boolean open) {
        if (ending || id == null || !id.equals(session) || (open && (reconnecting || menuBusy))) return;
        if (open) {
            float anchor = Float.NaN;
            synchronized (feedbackLock) { if (id.equals(feedbackSession) && safeViewport != null) anchor = feedbackY - safeViewport.top; }
            castMenu.anchor(anchor);
        }
        clearFeedback(); menuVisible = open; castMenu.show(open);
        updateCastNotification();
        if (open) root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    }
    private final Object feedbackLock = new Object();
    private String feedbackSession;
    private float feedbackInward, feedbackY;
    private boolean feedbackActive, feedbackReady, feedbackMenuArmed, feedbackPosted;
    private int feedbackGeneration;
    private final Runnable renderFeedback = () -> {
        String id; float inward, y; boolean active, ready, menuArmed; int generation;
        synchronized (feedbackLock) {
            feedbackPosted = false; id = feedbackSession; inward = feedbackInward; y = feedbackY;
            active = feedbackActive; ready = feedbackReady; menuArmed = feedbackMenuArmed; generation = feedbackGeneration;
        }
        if (ending || reconnecting || resizing || menuVisible || id == null || !id.equals(session) || generation != outputGeneration) return;
        if (active) backIndicator.progress(inward, y, ready, menuArmed); else backIndicator.finish(ready);
    };
    private final Runnable scheduleFeedback = () -> {
        if (!ending && backIndicator != null) backIndicator.postOnAnimation(renderFeedback);
    };
    private void queueFeedback(String id, float inward, float y, boolean active, boolean ready, boolean menuArmed) {
        if (ending || id == null || !id.equals(session)) return;
        synchronized (feedbackLock) {
            feedbackSession = id; feedbackInward = inward; feedbackY = y;
            feedbackActive = active; feedbackReady = ready; feedbackMenuArmed = menuArmed; feedbackGeneration = outputGeneration;
            if (feedbackPosted) return;
            feedbackPosted = true;
        }
        main.post(scheduleFeedback);
    }
    private void queueMenuTouch(String id, int action, float x, float y, long down, long time) {
            int generation = outputGeneration;
            main.post(() -> {
                if (ending || reconnecting || menuBusy || !menuVisible || !id.equals(session) || generation != outputGeneration) return;
                int[] xy = new int[2]; castMenu.getView().getLocationOnScreen(xy);
                if (action == MotionEvent.ACTION_DOWN && (x < xy[0] || y < xy[1]
                        || x >= xy[0] + castMenu.getView().getWidth() || y >= xy[1] + castMenu.getView().getHeight())) {
                    dismissMenu(); return;
                }
                MotionEvent event = MotionEvent.obtain(down, time, action, x - xy[0], y - xy[1], 0);
                event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                try { castMenu.getView().dispatchTouchEvent(event); } finally { event.recycle(); }
            });
    }
    private final IRearGestureListener gestures = new IRearGestureListener.Stub() {
        @Override public void onNotificationDismiss(String id, long token) {
            main.post(() -> { if (!ending && id.equals(session) && castNotification != null) castNotification.dismiss(token); });
        }
        @Override public void onCastMenu(String id, boolean open) {
            int generation = outputGeneration;
            main.post(() -> { if (generation == outputGeneration) renderMenu(id, open); });
        }
        @Override public void onCastMenuTouch(String id, int action, float x, float y, long down, long time) {
            queueMenuTouch(id, action, x, y, down, time);
        }
        @Override public void onBackGesture(String id, float inward, float y, boolean active, boolean ready, boolean menuArmed) {
            gesturePreviewGeneration++; // A real gesture always supersedes a local visual preview.
            queueFeedback(id, inward, y, active, ready, menuArmed);
        }
    };
    private void clearFeedback() {
        gesturePreviewGeneration++;
        main.removeCallbacks(scheduleFeedback);
        if (backIndicator != null) { backIndicator.removeCallbacks(renderFeedback); backIndicator.clear(); }
        synchronized (feedbackLock) { feedbackPosted = false; feedbackSession = null; }
    }
    private boolean screenReceiverRegistered;
    private volatile boolean lockRecoveryRequested, wakeAttempted;
    private int invalidateRecoveryActions() { return ++recoveryActionEpoch; }
    private ISceneTaskActionGate recoveryGate(String id, int epoch, boolean waking) {
        return new VirtualCastRecoveryActionGate(() -> {
            if (!VirtualCastOutputRecoveryPolicy.allowRecoveryAction(starting && session != null, ending,
                    !isFinishing() && instance == this, id != null && id.equals(session),
                    epoch == (waking ? screenOffCycleEpoch : recoveryActionEpoch),
                    waking, lockRecoveryRequested)) return false;
            android.view.Display display = getDisplay();
            if (display == null) return false;
            int state = display.getState();
            return waking ? state != android.view.Display.STATE_ON : state == android.view.Display.STATE_ON;
        });
    }
    private ISceneTaskActionGate outputPowerGate(String id, int epoch, boolean available) {
        return new VirtualCastRecoveryActionGate(() -> {
            if (ending || !starting || instance != this || id == null || !id.equals(session)
                    || isFinishing()
                    || epoch != outputPowerEpoch) return false;
            android.view.Display display = getDisplay();
            if (display == null) return false;
            return available ? display.getState() == android.view.Display.STATE_ON
                    : VirtualCastOutputPowerGate.isSleeping(display.getState());
        });
    }
    private void onPrimaryDisplayAvailable(boolean actualResumeEdge) {
        if (ending || isFinishing() || !starting || session == null || !reconnecting) return;
        armOutputTimeout(actualResumeEdge);
        int rearState = getDisplay() == null ? android.view.Display.STATE_UNKNOWN : getDisplay().getState();
        if (rearState != android.view.Display.STATE_ON) {
            if (!lockRecoveryRequested) requestLockRecovery(150);
        } else if (reconnecting) {
            onRearDisplayResumed(actualResumeEdge);
        } else presentExistingHost();
    }
    private void onRearDisplaySuspended() {
        if (ending || isFinishing() || !starting || session == null || resizing) return;
        reconnecting = true;
        powerSuspendedOutput = true;
        missingSurfaceLossEpoch = missingSurfacePresentationGate.beginPowerCycleLoss();
        missingSurfacePresentationGate.pauseDeadlineForPowerSuspension(missingSurfaceLossEpoch);
        outputGeneration++;
        outputPowerEpoch++;
        invalidateRecoveryActions();
        main.removeCallbacks(outputTimeout);
        clearFeedback();
        menuVisible = false; if (castMenu != null) castMenu.show(false);
        updateCameraGapBattery();
        String id = session;
        int epoch = outputPowerEpoch;
        int generation = outputGeneration;
        worker.execute(() -> {
            try {
                if (isCurrentOutputPowerRequest(id, generation, epoch) && service != null) {
                    String result = service.setRearVirtualCastOutputAvailable(id, false, null,
                            outputPowerGate(id, epoch, false));
                    android.util.Log.i("VirtualRearHost", "Rear sleep output pause session=" + id + " result=" + result);
                }
            } catch (Exception e) {
                main.post(() -> { if (isCurrentOutputPowerRequest(id, generation, epoch)) fail(e); });
            }
        });
    }
    private void onRearDisplayResumed(boolean actualResumeEdge) {
        if (!reconnecting || ending || isFinishing()) return;
        armOutputTimeout(actualResumeEdge);
        outputPowerEpoch++;
        String id = session;
        Surface surface = holder == null || !surfaceConfigured ? null : holder.getSurface();
        boolean surfaceValid = surface != null && surface.isValid();
        int rearState = getDisplay() == null ? android.view.Display.STATE_UNKNOWN : getDisplay().getState();
        final int generation = outputGeneration;
        final int epoch = outputPowerEpoch;
        final int lossEpoch = missingSurfaceLossEpoch;
        VirtualCastOutputRecoveryPolicy.dispatchRearResume(starting && id != null, ending, reconnecting,
                powerSuspendedOutput, surfaceValid, rearState,
                new VirtualCastOutputRecoveryPolicy.RearResumeEffects() {
                    @Override public void reattachOutputSurface() {
                        worker.execute(() -> {
                            try {
                                if (!isCurrentSurfaceRecovery(id, generation, lossEpoch, surface)) return;
                                ITaskService remote = service;
                                if (remote == null) return;
                                String result = remote.setRearVirtualCastOutputAvailable(id, true, surface,
                                        outputPowerGate(id, epoch, true));
                                if (!VirtualCastOutputRecoveryPolicy.isOutputAvailableResult(result)) {
                                    android.util.Log.i("VirtualRearHost", "Rear resume remains pending session=" + id + " result=" + result);
                                    return;
                                }
                                main.post(() -> commitCurrentSurfaceRecovery(id, generation, lossEpoch, surface));
                            } catch (Exception e) {
                                main.post(() -> { if (isCurrentSurfaceRecovery(id, generation, lossEpoch, surface)) fail(e); });
                            }
                        });
                    }

                    @Override public void waitForReplacementSurface() {
                        armOutputTimeout(actualResumeEdge);
                        android.util.Log.i("VirtualRearHost", "Rear is ON; waiting for owned Surface session=" + id);
                    }

                    @Override public void presentOwnedHost() {
                        if (surfaceValid) {
                            submitSurfaceRecovery(id, surface, generation, lossEpoch);
                        } else {
                            int attempt = requestOwnedHostForMissingSurface();
                            if (attempt != 0) presentExistingHost(missingSurfaceLossEpoch, attempt);
                        }
                    }
                });
    }
    private void armOutputTimeout(boolean actualResumeEdge) {
        VirtualCastOutputRecoveryPolicy.scheduleReconnectDeadline(missingSurfacePresentationGate,
                missingSurfaceLossEpoch, actualResumeEdge,
                (lossEpoch, deadlineId, timeoutMillis) -> {
                    outputTimeoutLossEpoch = lossEpoch;
                    outputTimeoutId = deadlineId;
                    main.postDelayed(outputTimeout, timeoutMillis);
                });
    }
    private int requestOwnedHostForMissingSurface() {
        if (ending || isFinishing() || !reconnecting || session == null) return 0;
        int attempt = missingSurfacePresentationGate.beginAttempt(missingSurfaceLossEpoch);
        if (attempt == 0) return 0;
        android.util.Log.i("VirtualRearHost", "Presenting owned host to restore missing Surface session=" + session);
        return attempt;
    }
    private void presentExistingHost() {
        presentExistingHost(0, 0);
    }
    private void presentExistingHost(int lossEpoch, int attempt) {
        String id = session;
        int epoch = recoveryActionEpoch;
        if (id == null || ending || isFinishing()) return;
        int host = getTaskId();
        worker.execute(() -> {
            try {
                ITaskService remote = service;
                if (remote == null || ending || !id.equals(session)) {
                    if (attempt != 0) main.post(() -> missingSurfacePresentationGate.complete(lossEpoch, attempt));
                    return;
                }
                ISceneTaskActionGate actionGate = attempt == 0
                        ? recoveryGate(id, epoch, false)
                        : new VirtualCastRecoveryActionGate(() -> {
                            android.view.Display display = getDisplay();
                            return missingSurfacePresentationGate.isCurrent(lossEpoch, attempt,
                                    starting && session != null && !isFinishing(), ending, instance == this,
                                    id.equals(session), reconnecting,
                                    display != null && display.getState() == android.view.Display.STATE_ON);
                        });
                String result = remote.recoverRearVirtualCastHost(id, host, RearDisplay.requireId(),
                        RearVirtualCast.RECOVERY_PRESENT_HOST, actionGate);
                android.util.Log.i("VirtualRearHost", "Owned host present session=" + id + " result=" + result);
                if (attempt != 0) main.post(() -> missingSurfacePresentationGate.complete(lossEpoch, attempt));
            } catch (Exception e) {
                if (attempt != 0) main.post(() -> missingSurfacePresentationGate.complete(lossEpoch, attempt));
                android.util.Log.w("VirtualRearHost", "Owned host presentation deferred", e);
            }
        });
    }
    private void requestLockRecovery(long delay) {
        if (ending || isFinishing() || !VirtualCastOutputRecoveryPolicy.shouldScheduleWakeForPowerCycle(lockRecoveryRequested)) return;
        lockRecoveryRequested = true;
        wakeAttempted = false;
        invalidateRecoveryActions();
        main.postDelayed(wakeRearAfterMainLock, delay);
    }
    private final Runnable wakeRearAfterMainLock = this::wakeRearOnce;
    private void wakeRearOnce() {
        if (ending || isFinishing() || !starting || session == null || !lockRecoveryRequested || wakeAttempted) return;
        wakeAttempted = true;
        String id = session;
        int host = getTaskId();
        int epoch = screenOffCycleEpoch;
        worker.execute(() -> {
            try {
                ITaskService remote = service;
                if (remote == null || ending || !id.equals(session)) return;
                String result = remote.recoverRearVirtualCastHost(id, host, RearDisplay.requireId(),
                        RearVirtualCast.RECOVERY_WAKE_REAR, recoveryGate(id, epoch, true));
                android.util.Log.i("VirtualRearHost", "Rear one-shot wake session=" + id + " result=" + result);
                if (VirtualCastOutputRecoveryPolicy.shouldReconcileAlreadyOnWake(result,
                        starting && session != null, ending, id.equals(session), reconnecting)) {
                    main.post(() -> {
                        android.view.Display display = getDisplay();
                        if (!ending && starting && id.equals(session) && reconnecting && display != null
                                && display.getState() == android.view.Display.STATE_ON)
                            onRearDisplayResumed(false);
                    });
                }
            } catch (Exception e) { fail(e); }
        });
    }
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                boolean wasPrimaryInteractive = primaryInteractive;
                primaryInteractive = false;
                VirtualCastOutputRecoveryPolicy.PrimaryScreenOffDecision decision =
                        VirtualCastOutputRecoveryPolicy.onPrimaryScreenOff(missingSurfacePresentationGate,
                                lockRecoveryRequested, wasPrimaryInteractive, reconnecting);
                missingSurfaceLossEpoch = decision.lossEpoch;
                missingSurfacePresentationGate.pauseDeadlineForPowerSuspension(missingSurfaceLossEpoch);
                main.removeCallbacks(outputTimeout);
                invalidateRecoveryActions();
                if (decision.newPrimaryCycle) screenOffCycleEpoch++;
                android.view.Display rear = getDisplay();
                boolean rearIsOn = rear != null && rear.getState() == android.view.Display.STATE_ON;
                VirtualCastOutputRecoveryPolicy.dispatchPrimaryScreenOff(decision, rearIsOn,
                        new VirtualCastOutputRecoveryPolicy.PrimaryScreenOffEffects() {
                            @Override public void scheduleWake() { requestLockRecovery(350); }
                            @Override public void reconcileAlreadyOnRear() { onPrimaryDisplayAvailable(false); }
                        });
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                boolean resumeEdge = !primaryInteractive;
                primaryInteractive = true;
                if (resumeEdge) {
                    screenOffCycleEpoch++;
                    lockRecoveryRequested = false;
                    wakeAttempted = false;
                }
                primaryKeyguardLocked = getSystemService(android.app.KeyguardManager.class).isKeyguardLocked();
                onPrimaryDisplayAvailable(resumeEdge);
            } else if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                boolean resumeEdge = primaryKeyguardLocked;
                primaryKeyguardLocked = false;
                onPrimaryDisplayAvailable(resumeEdge);
            }
        }
    };
    private final Runnable outputTimeout = this::handleOutputTimeout;
    private void handleOutputTimeout() {
        int lossEpoch = outputTimeoutLossEpoch;
        int deadlineId = outputTimeoutId;
        if (!missingSurfacePresentationGate.isCurrentDeadline(lossEpoch, deadlineId) || !reconnecting) return;
        PowerManager power = getSystemService(PowerManager.class);
        boolean interactive = power != null && power.isInteractive();
        boolean mainLocked = getSystemService(android.app.KeyguardManager.class).isKeyguardLocked() || !interactive;
        int rearState = getDisplay() == null ? android.view.Display.STATE_UNKNOWN : getDisplay().getState();
        boolean stillWaiting = VirtualCastOutputRecoveryPolicy.waitForDisplayResume(mainLocked, interactive, rearState);
        if (!missingSurfacePresentationGate.finishDeadline(lossEpoch, deadlineId, stillWaiting)) return;
        if (stillWaiting) {
            android.util.Log.i("VirtualRearHost", "Output remains suspended for display resume; session=" + session
                    + " rearState=" + rearState + " mainLocked=" + mainLocked);
            return;
        }
        fail(new IllegalStateException("Rear output reconnect timeout"));
    }
    private int width, height;
    private final Runnable startAfterLayout = this::startSession;
    private final Runnable heartbeat = new Runnable() {
        public void run() {
            if (ending) return;
            worker.execute(() -> {
                try {
                    ITaskService remote = service; String id = session;
                    if (remote != null && id != null) {
                        if (!remote.renewRearVirtualCastLease(id)) { main.post(VirtualRearHostActivity.this::finish); return; }
                        status = remote.getRearVirtualCastStatus();
                    }
                } catch (Exception e) { fail(e); }
            });
            main.postDelayed(this, 1000);
        }
    };
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ITaskService.Stub.asInterface(binder);
            worker.execute(() -> {
                try {
                    android.util.Log.i("VirtualRearHost", "Query safe viewport");
                    RearCastViewport safe = RearCastGeometry.decode(service.getRearCastViewport());
                    int task = getIntent().getIntExtra("task", -1), dpi = getIntent().getIntExtra("dpi", VirtualCastRequest.DEFAULT_DENSITY_DPI);
                    String component = getIntent().getStringExtra("component");
                    JSONObject plan = new JSONObject(service.getRearCastRenderPlan(task, component, dpi, false, false));
                    String pkg = plan.getString("package");
                    boolean compatibility = RearDensityPreferences.enabled(VirtualRearHostActivity.this, pkg);
                    int layoutDpi = RearCastCompatibility.layoutDensity(compatibility, dpi,
                            RearDensityPreferences.layoutDensity(VirtualRearHostActivity.this, pkg),
                            getIntent().getBooleanExtra("explicitDpi", false));
                    RearPresentationPreferences.Selection presentation = RearPresentationPreferences.read(VirtualRearHostActivity.this, pkg);
                    if (compatibility || presentation.full || layoutDpi != dpi)
                        plan = new JSONObject(service.getRearCastRenderPlan(task, component, layoutDpi, compatibility, presentation.full));
                    RearCastViewport render = RearCastGeometry.decode(plan.toString());
                    android.util.Log.i("VirtualRearHost", "Received safe viewport=" + safe.key());
                    main.post(() -> {
                        if (ending) return;
                        if (safeViewport != null && !safeViewport.key().equals(safe.key())) {
                            fail(new IllegalStateException("Rear safe area changed; restart casting")); return;
                        }
                        safeViewport = safe;
                        densityCompatibility = compatibility;
                        requestedLayoutDensity = layoutDpi;
                        initialRotation = presentation.rotation;
                        initialFull = presentation.full;
                        if (viewport == null) viewport = render;
                        castMenu.updateViewportMode(initialFull);
                        root.post(VirtualRearHostActivity.this::layoutViewport);
                    });
                } catch (Exception e) { fail(e); }
            });
        }
        public void onServiceDisconnected(ComponentName name) { service = null; fail(new IllegalStateException("Control service disconnected")); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        PowerManager initialPower = getSystemService(PowerManager.class);
        primaryInteractive = initialPower != null && initialPower.isInteractive();
        primaryKeyguardLocked = getSystemService(android.app.KeyguardManager.class).isKeyguardLocked();
        if (Build.VERSION.SDK_INT < 33) { finish(); return; }
        android.util.Log.i("VirtualRearHost", "Launch display=" + getDisplay().getDisplayId() + " rear=" + RearDisplay.id()
                + " task=" + getIntent().getIntExtra("task", -1) + " stop=" + getIntent().getBooleanExtra("stop", false));
        if (!BuildConfig.VIRTUAL_CAST_ENABLED || !RearDisplay.isRear(getDisplay().getDisplayId()) || getIntent().getBooleanExtra("stop", false)
                || (getIntent().getIntExtra("task", -1) < 0 && getIntent().getStringExtra("component") == null)) { finish(); return; }
        instance = this;
        setShowWhenLocked(true);
        getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        getWindow().setDecorFitsSystemWindows(false);
        if (getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE).getBoolean("flutter.keep_screen_on_enabled", true))
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        output = new SurfaceView(this);
        // Keep the producer alive while HyperOS temporarily puts its launcher in front on power-off.
        if (Build.VERSION.SDK_INT >= 34) output.setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT);
        output.getHolder().addCallback(this);
        root = new RearCastFrameLayout(this);
        root.setBackgroundColor(android.graphics.Color.BLACK);
        root.addView(output, new FrameLayout.LayoutParams(-1, -1));
        RearCameraGapBatteryView batteryLayer = new RearCameraGapBatteryView(this);
        root.addView(batteryLayer, new FrameLayout.LayoutParams(-1, -1));
        cameraGapBattery = new RearCameraGapBatteryController(this, batteryLayer);
        RearCameraStatusView statusLayer = new RearCameraStatusView(this);
        root.addView(statusLayer, new FrameLayout.LayoutParams(-1, -1));
        cameraStatus = new RearCameraStatusController(this, statusLayer);
        castNotification = new RearCastNotificationOverlay(this, (token, left, top, right, bottom) -> {
            String id = session;
            if (id == null) return;
            worker.execute(() -> {
                boolean success = false;
                try {
                    ITaskService remote = service;
                    if (!ending && remote != null && id.equals(session))
                        success = remote.setRearCastNotificationBounds(id, token, left, top, right, bottom);
                } catch (Exception error) { android.util.Log.w("VirtualRearHost", "Notification touch region unavailable", error); }
                boolean registered = success;
                main.post(() -> {
                    if (!ending && id.equals(session)) castNotification.regionRegistered(token, left, top, right, bottom, registered);
                });
            });
        });
        root.addView(castNotification, new FrameLayout.LayoutParams(1, -2));
        backIndicator = new RearBackGestureView(this);
        root.addView(backIndicator, new FrameLayout.LayoutParams(backIndicator.dp(32), backIndicator.dp(40), Gravity.RIGHT | Gravity.TOP));
        castMenu = new RearCastMenuUi(this, this::finish, () -> rotateContent(-1), () -> rotateContent(1), this::toggleViewport, this::dismissMenu);
        root.addView(castMenu.getView(), new FrameLayout.LayoutParams(1, 1));
        root.getViewTreeObserver().addOnGlobalLayoutListener(this::layoutViewport);
        setContentView(root);
        RearCastNotification.attach(notificationTarget);
        getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());
        IntentFilter screenEvents = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        screenEvents.addAction(Intent.ACTION_SCREEN_ON);
        screenEvents.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenReceiver, screenEvents, Context.RECEIVER_NOT_EXPORTED);
        screenReceiverRegistered = true;
        android.hardware.display.DisplayManager displayManager = getSystemService(android.hardware.display.DisplayManager.class);
        android.view.Display rearDisplay = displayManager == null ? null : displayManager.getDisplay(RearDisplay.requireId());
        observedRearDisplayState = rearDisplay == null ? android.view.Display.STATE_UNKNOWN : rearDisplay.getState();
        if (displayManager != null) displayManager.registerDisplayListener(rearDisplayListener, main);
        TaskServiceManager.acquire(this, connection);
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { finish(); }
        });
        main.post(heartbeat);
    }
    private void layoutViewport() {
        if (ending || viewport == null || root.getWidth() == 0 || root.getHeight() == 0) return;
        android.graphics.Rect buffer = output.getHolder().getSurfaceFrame();
        if (buffer.width() != viewport.bufferWidth || buffer.height() != viewport.bufferHeight)
            output.getHolder().setFixedSize(viewport.bufferWidth, viewport.bufferHeight);
        int[] origin = new int[2]; root.getLocationOnScreen(origin);
        String layoutState = "root=" + origin[0] + "," + origin[1] + " " + root.getWidth() + "x" + root.getHeight()
                + " output=" + output.getLeft() + "," + output.getTop() + " " + output.getWidth() + "x" + output.getHeight();
        if (!layoutState.equals(lastViewportLayout)) { lastViewportLayout = layoutState; android.util.Log.i("VirtualRearHost", layoutState); }
        if (origin[0] > viewport.left || origin[1] > viewport.top
                || origin[0] + root.getWidth() < viewport.right || origin[1] + root.getHeight() < viewport.bottom) {
            fail(new IllegalStateException("Rear host does not contain the safe viewport")); return;
        }
        FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(viewport.width(), viewport.height());
        layout.leftMargin = viewport.left - origin[0]; layout.topMargin = viewport.top - origin[1];
        FrameLayout.LayoutParams menuLayout = (FrameLayout.LayoutParams) castMenu.getView().getLayoutParams();
        if (menuLayout.width != safeViewport.width() || menuLayout.height != safeViewport.height()
                || menuLayout.leftMargin != safeViewport.left - origin[0] || menuLayout.topMargin != safeViewport.top - origin[1]) {
            FrameLayout.LayoutParams safeLayout = new FrameLayout.LayoutParams(safeViewport.width(), safeViewport.height());
            safeLayout.leftMargin = safeViewport.left - origin[0]; safeLayout.topMargin = safeViewport.top - origin[1];
            castMenu.getView().setLayoutParams(safeLayout);
        }
        FrameLayout.LayoutParams old = (FrameLayout.LayoutParams) output.getLayoutParams();
        if (old.width != layout.width || old.height != layout.height || old.leftMargin != layout.leftMargin || old.topMargin != layout.topMargin) {
            viewportPlaced = false; output.setLayoutParams(layout); return;
        }
        int[] xy = new int[2]; output.getLocationOnScreen(xy);
        if (xy[0] != viewport.left || xy[1] != viewport.top
                || output.getWidth() != viewport.width() || output.getHeight() != viewport.height()) return;
        if (!viewportPlaced) android.util.Log.i("VirtualRearHost", "Safe viewport=" + viewport.key() + " surface=" + xy[0] + "," + xy[1]);
        viewportPlaced = true;
        updateCameraGapBattery();
        if (resizing) maybeFinishViewport(); else maybeStart();
    }
    private boolean isCurrentRecoveryEpoch(String id, int generation, int lossEpoch) {
        return VirtualCastOutputRecoveryPolicy.isCurrentSurfaceRecovery(
                starting && session != null && !ending && !isFinishing(), instance == this,
                id != null && id.equals(session),
                reconnecting, generation, outputGeneration, true,
                missingSurfacePresentationGate.isEpisodeCurrent(lossEpoch));
    }
    private boolean isCurrentOutputPowerRequest(String id, int generation, int powerEpoch) {
        return !ending && !isFinishing() && starting && instance == this && id != null && id.equals(session)
                && reconnecting && generation == outputGeneration && powerEpoch == outputPowerEpoch;
    }
    private boolean isCurrentSurfaceRecovery(String id, int generation, int lossEpoch, Surface surface) {
        SurfaceHolder currentHolder = holder;
        Surface currentSurface = currentHolder == null ? null : currentHolder.getSurface();
        return VirtualCastOutputRecoveryPolicy.isCurrentSurfaceRecovery(
                starting && session != null, !ending && !isFinishing() && instance == this,
                id != null && id.equals(session), reconnecting, generation, outputGeneration,
                surfaceConfigured && surface != null && currentSurface == surface,
                missingSurfacePresentationGate.isEpisodeCurrent(lossEpoch));
    }
    private void commitCurrentSurfaceRecovery(String id, int generation, int lossEpoch, Surface surface) {
        if (!VirtualCastOutputRecoveryPolicy.canCommitSurfaceRecovery(true,
                starting && session != null, !ending && !isFinishing() && instance == this,
                id != null && id.equals(session), reconnecting, generation, outputGeneration,
                surfaceConfigured && surface != null && surface.isValid()
                        && holder != null && holder.getSurface() == surface,
                missingSurfacePresentationGate.isEpisodeCurrent(lossEpoch))
                || !missingSurfacePresentationGate.commitRecovery(lossEpoch)) return;
        reconnecting = false;
        powerSuspendedOutput = false;
        main.removeCallbacks(outputTimeout);
        invalidateRecoveryActions();
        updateCameraGapBattery();
        android.util.Log.i("VirtualRearHost", "Rear output recovery backend commit accepted session=" + id);
        presentExistingHost();
    }
    private void submitSurfaceRecovery(String id, Surface surface, int generation, int lossEpoch) {
        worker.execute(() -> {
            try {
                if (!isCurrentSurfaceRecovery(id, generation, lossEpoch, surface)) return;
                ITaskService remote = service;
                if (remote == null) throw new IllegalStateException("Control service disconnected");
                boolean accepted = remote.updateRearVirtualCastSurface(id, surface);
                main.post(() -> {
                    if (!isCurrentSurfaceRecovery(id, generation, lossEpoch, surface)) return;
                    if (!accepted) {
                        fail(new IllegalStateException("Rear output session ended"));
                        return;
                    }
                    commitCurrentSurfaceRecovery(id, generation, lossEpoch, surface);
                });
            } catch (Exception e) {
                main.post(() -> { if (isCurrentSurfaceRecovery(id, generation, lossEpoch, surface)) fail(e); });
            }
        });
    }
    public void surfaceCreated(SurfaceHolder value) {
        if (starting && holder != value) outputGeneration++;
        surfaceConfigured = false;
        holder = value;
    }
    public void surfaceChanged(SurfaceHolder value, int format, int w, int h) {
        android.util.Log.i("VirtualRearHost", "Surface " + w + "x" + h + " starting=" + starting);
        if (starting && !resizing && (width != w || height != h)) { fail(new IllegalStateException("Rear Surface resized; restart casting")); return; }
        if (starting) outputGeneration++;
        Surface surface = value.getSurface();
        holder = value; surfaceConfigured = true; width = w; height = h;
        if (resizing) { layoutViewport(); return; }
        if (starting && reconnecting && !ending && !isFinishing()) {
            if (VirtualCastOutputPowerGate.deferSurfaceChangeWhilePowerSuspended(powerSuspendedOutput)) {
                android.view.Display rear = getDisplay();
                if (rear != null && rear.getState() == android.view.Display.STATE_ON)
                    main.post(() -> VirtualRearHostActivity.this.onRearDisplayResumed(false));
                return; // The gated ON transaction uses this latest Surface, never stale Backend state.
            }
            String id = session;
            int generation = outputGeneration;
            int lossEpoch = missingSurfaceLossEpoch;
            submitSurfaceRecovery(id, surface, generation, lossEpoch);
        } else maybeStart();
    }
    public void surfaceDestroyed(SurfaceHolder value) {
        holder = null; surfaceConfigured = false; outputGeneration++; main.removeCallbacks(startAfterLayout);
        missingSurfaceLossEpoch = missingSurfacePresentationGate.beginLoss();
        updateCameraGapBattery();
        clearFeedback();
        menuVisible = false; if (castMenu != null) castMenu.show(false);
        if (!starting || ending || isFinishing()) return;
        if (resizing) { fail(new IllegalStateException("Rear output lost during viewport change")); return; }
        boolean mainLocked = getSystemService(android.app.KeyguardManager.class).isKeyguardLocked()
                || !getSystemService(PowerManager.class).isInteractive();
        int rearState = getDisplay() == null ? android.view.Display.STATE_UNKNOWN : getDisplay().getState();
        android.util.Log.i("VirtualRearHost", "Surface lost; mainLocked=" + mainLocked + " rearState=" + rearState
                + " session=" + session);
        PowerManager power = getSystemService(PowerManager.class);
        boolean interactive = power != null && power.isInteractive();
        boolean waitingForDisplay = VirtualCastOutputRecoveryPolicy.waitForDisplayResume(mainLocked, interactive, rearState);
        reconnecting = true;
        String lostSession = session;
        int lostGeneration = outputGeneration;
        int lossEpoch = missingSurfaceLossEpoch;
        invalidateRecoveryActions();
        if (rearState == android.view.Display.STATE_ON) armOutputTimeout(false);
        worker.execute(() -> {
            try {
                if (!ending && isCurrentRecoveryEpoch(lostSession, lostGeneration, lossEpoch)
                        && service != null && !service.updateRearVirtualCastSurface(lostSession, null))
                    throw new IllegalStateException("Rear output session ended");
            } catch (Exception e) {
                main.post(() -> { if (isCurrentRecoveryEpoch(lostSession, lostGeneration, lossEpoch)) fail(e); });
            }
        });
        if (!waitingForDisplay) {
            main.post(() -> {
                if (!ending && starting && instance == this && reconnecting
                        && lostSession != null && lostSession.equals(session)
                        && lossEpoch == missingSurfaceLossEpoch) onRearDisplayResumed(false);
            });
        } else {
            requestLockRecovery(150);
        }
    }
    private void maybeStart() {
        main.removeCallbacks(startAfterLayout);
        if (!ending && !starting) main.postDelayed(startAfterLayout, 300);
    }
    private void startSession() {
        ITaskService remote = service;
        if (ending || starting || remote == null || holder == null || viewport == null || !viewportPlaced
                || width != viewport.bufferWidth || height != viewport.bufferHeight) return;
        starting = true;
        Surface surface = holder.getSurface();
        int target = getIntent().getIntExtra("task", -1), host = getTaskId();
        worker.execute(() -> {
            try {
                status = remote.startRearVirtualCast(surface,
                        new File(getApplicationInfo().nativeLibraryDir, "libremrss_input_proxy.so").getPath(), target, host,
                        requestedLayoutDensity, getIntent().getStringExtra("component"), owner, gestures,
                        RearCastGeometry.encode(viewport), densityCompatibility, initialRotation, initialFull);
                JSONObject state = new JSONObject(status);
                session = state.getString("id");
                sessionPackage = state.getString("package");
                main.post(this::updateCameraGapBattery);
                if (densityCompatibility && !"unrestricted".equals(state.optString("sizePolicy")))
                    main.post(() -> { if (!ending) android.widget.Toast.makeText(this, R.string.rear_density_partial_support,
                            android.widget.Toast.LENGTH_LONG).show(); });
                String monitored = state.optString("package") + ":" + state.optInt("task");
                android.util.Log.i("VirtualRearHost", status);
                if (getIntent().getBooleanExtra("managed", false)) main.post(() -> {
                    if (ending) return;
                    Intent keeper = new Intent(this, RearScreenKeeperService.class)
                            .putExtra(VirtualCastUriFlow.EXTRA_SESSION, session)
                            .putExtra("lastMovedTask", monitored)
                            .putExtra("keepScreenOnEnabled", false);
                    startForegroundService(keeper);
                });
            } catch (Exception e) { fail(e); }
        });
    }
    private void fail(Exception e) {
        if (ending || isFinishing()) return;
        ending = true;
        android.util.Log.e("VirtualRearHost", "Virtual casting stopped", e);
        main.post(this::finish);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) { return true; /* hardware is exclusively read by evdev */ }
    private void updateCameraGapBattery() {
        updateCastNotification();
        if (cameraGapBattery == null || root == null) return;
        int[] origin = new int[2]; root.getLocationOnScreen(origin);
        cameraGapBattery.session(safeViewport, !ending && session != null && viewportPlaced && holder != null
                && !resizing && !reconnecting, viewport == null || viewport.isFull(), origin[0], origin[1]);
        if (cameraStatus != null) cameraStatus.session(safeViewport, !ending && session != null && viewportPlaced && holder != null
                && !resizing && !reconnecting, viewport == null || viewport.isFull(), origin[0], origin[1]);
    }
    private void updateCastNotification() {
        if (castNotification == null || root == null) return;
        int[] origin = new int[2]; root.getLocationOnScreen(origin);
        castNotification.hostState(safeViewport, origin[0], origin[1], hostResumed && !ending && session != null
                && viewportPlaced && holder != null && !resizing && !reconnecting && !menuVisible && !menuBusy,
                31 * java.util.Objects.hashCode(session) + outputGeneration + (viewport == null ? 0 : viewport.key().hashCode()));
    }
    @Override protected void onResume() {
        super.onResume();
        hostResumed = true; updateCastNotification();
        if (cameraGapBattery != null) { cameraGapBattery.visible(true); updateCameraGapBattery(); }
        if (cameraStatus != null) cameraStatus.visible(true);
    }
    @Override protected void onPause() {
        hostResumed = false; updateCastNotification();
        if (cameraGapBattery != null) cameraGapBattery.visible(false);
        if (cameraStatus != null) cameraStatus.visible(false);
        super.onPause();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra("stop", false)) { finish(); return; }
        // DUMP-protected software visual/button QA only; does not simulate the native hold/grab chain.
        if (BuildConfig.DEBUG && session != null && !ending && !reconnecting) {
            if (intent.getBooleanExtra("preview_menu", false)) {
                android.util.Log.i("VirtualRearHost", "menu_visual_probe session=" + session);
                renderMenu(session, true);
            }
            if (intent.hasExtra("preview_menu_x") && menuVisible) {
                float x = intent.getFloatExtra("preview_menu_x", 0), y = intent.getFloatExtra("preview_menu_y", 0);
                long down = SystemClock.uptimeMillis(); String id = session;
                queueMenuTouch(id, MotionEvent.ACTION_DOWN, x, y, down, down);
                main.postDelayed(() -> queueMenuTouch(id, MotionEvent.ACTION_UP, x, y, down, SystemClock.uptimeMillis()), 70);
            }
        }
        if (BuildConfig.DEBUG) {
        String preview = intent.getStringExtra("preview_back");
        if (preview != null && session != null && !reconnecting && !ending) {
            clearFeedback();
            int generation = gesturePreviewGeneration; String id = session;
            try {
                Class<?> previewType = Class.forName("top.zainzh.rearscreen.RearBackGesturePreview");
                java.lang.reflect.Method play = previewType.getDeclaredMethod("play", Handler.class, String.class, int.class, RearTouchRouter.Feedback.class, java.util.function.BooleanSupplier.class, Runnable.class);
                play.setAccessible(true);
                play.invoke(null, main, preview, getResources().getDisplayMetrics().densityDpi,
                    (RearTouchRouter.Feedback) (distance, y, active, ready, menuArmed) -> queueFeedback(id, distance, y, active, ready, menuArmed),
                    (java.util.function.BooleanSupplier) () -> !ending && !reconnecting && generation == gesturePreviewGeneration && id.equals(session), (Runnable) () -> renderMenu(id, true));
            } catch (ReflectiveOperationException e) { android.util.Log.e("VirtualRearHost", "Debug preview unavailable", e); }
        }
        }
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        if (backIndicator != null) backIndicator.applyTheme();
        if (cameraGapBattery != null) cameraGapBattery.themeChanged();
        if (cameraStatus != null) cameraStatus.themeChanged();
        if (castNotification != null) castNotification.applyTheme();
        if (safeViewport != null) worker.execute(() -> {
            try {
                if (!safeViewport.key().equals(RearCastGeometry.decode(service.getRearCastViewport()).key()))
                    throw new IllegalStateException("Rear display geometry changed; restart casting");
            } catch (Exception e) { fail(e); }
        });
    }
    @Override protected void onDestroy() {
        ending = true; outputPowerEpoch++; screenOffCycleEpoch++; invalidateRecoveryActions();
        missingSurfacePresentationGate.invalidate(); main.removeCallbacksAndMessages(null);
        RearCastNotification.detach(notificationTarget);
        if (castNotification != null) castNotification.close();
        if (cameraGapBattery != null) cameraGapBattery.close();
        if (cameraStatus != null) cameraStatus.close();
        clearFeedback();
        menuVisible = false; if (castMenu != null) castMenu.show(false);
        if (screenReceiverRegistered) { unregisterReceiver(screenReceiver); screenReceiverRegistered = false; }
        android.hardware.display.DisplayManager displayManager = getSystemService(android.hardware.display.DisplayManager.class);
        if (displayManager != null) displayManager.unregisterDisplayListener(rearDisplayListener);
        worker.execute(() -> {
            try {
                if (service != null && session != null) service.stopRearVirtualCast(session);
            }
            catch (Exception e) { android.util.Log.e("VirtualRearHost", "Stop", e); }
            finally { TaskServiceManager.release(connection); }
        });
        if (session != null) RearScreenKeeperService.stopVirtualSession(session);
        worker.shutdown();
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
