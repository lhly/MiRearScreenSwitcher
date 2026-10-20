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
    private SurfaceHolder holder;
    private SurfaceView output;
    private FrameLayout root;
    private RearCastViewport viewport;
    private RearCastViewport safeViewport;
    private boolean densityCompatibility;
    private int initialRotation;
    private boolean initialFull;
    private volatile String sessionPackage;
    private boolean resizing, resizeSubmitted;
    private boolean viewportPlaced;
    private String lastViewportLayout;
    private volatile String session;
    private boolean starting, reconnecting;
    private volatile int outputGeneration;
    private volatile boolean ending;
    private volatile int gesturePreviewGeneration;
    private RearBackGestureView backIndicator;
    private RearCastMenuUi castMenu;
    private boolean menuVisible, menuBusy;
    private final Runnable viewportTimeout = () -> { if (resizing) fail(new IllegalStateException("Rear viewport change timed out")); };
    private void toggleViewport() {
        if (ending || menuBusy || session == null || reconnecting || resizing) return;
        menuBusy = true; castMenu.setBusy(true); menuVisible = false; castMenu.show(false); clearFeedback();
        resizing = true; resizeSubmitted = false; outputGeneration++;
        main.postDelayed(viewportTimeout, 4500);
        String id = session; boolean full = !viewport.isFull();
        worker.execute(() -> {
            try {
                if (service == null) throw new IllegalStateException("Shizuku disconnected");
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
                if (service == null) throw new IllegalStateException("Shizuku disconnected");
                String confirmed = service.finishRearCastViewport(id, surface);
                status = confirmed;
                rememberPresentation(confirmed, false);
                main.post(() -> {
                    if (ending || !id.equals(session)) return;
                    resizing = false; resizeSubmitted = false; menuBusy = false;
                    castMenu.updateViewportMode(viewport.isFull()); castMenu.setBusy(false);
                    main.removeCallbacks(viewportTimeout);
                    android.util.Log.i("VirtualRearHost", status);
                });
            } catch (Exception e) { fail(e); }
        });
    }
    private void dismissMenu() {
        if (ending || menuBusy || !menuVisible) return;
        castMenu.show(false); menuVisible = false;
        String id = session;
        worker.execute(() -> { try { if (service != null && id != null) service.dismissRearCastMenu(id); } catch (Exception e) { fail(e); } });
    }
    private void rotateContent(int direction) {
        if (ending || menuBusy || session == null || reconnecting) return;
        menuBusy = true; castMenu.setBusy(true); menuVisible = false; castMenu.show(false); String id = session;
        worker.execute(() -> {
            try {
                if (service == null) throw new IllegalStateException("Shizuku disconnected");
                String confirmed = service.rotateRearVirtualCast(id, direction);
                status = confirmed;
                rememberPresentation(confirmed, true);
                main.post(() -> { menuBusy = false; castMenu.setBusy(false); });
            } catch (Exception e) {
                android.util.Log.w("VirtualRearHost", "Content rotation failed", e);
                main.post(() -> {
                    if (ending) return;
                    menuBusy = false; castMenu.setBusy(false);
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
    private boolean screenReceiverRegistered, lockRecoveryRequested;
    private void requestLockRecovery(long delay) {
        if (ending || lockRecoveryRequested) return;
        lockRecoveryRequested = true;
        main.postDelayed(wakeRearAfterMainLock, delay);
    }
    private final Runnable wakeRearAfterMainLock = this::wakeRearOnce;
    private void wakeRearOnce() {
        if (ending || session == null) return;
        String id = session;
        int host = getTaskId();
        worker.execute(() -> {
            try {
                ITaskService remote = service;
                if (remote == null || ending || !id.equals(session)) return;
                JSONObject current = new JSONObject(remote.getRearVirtualCastStatus());
                if (id.equals(current.optString("id")) && "running".equals(current.optString("state"))) {
                    // HyperOS sleeps physical display 1 as part of the main power-button action.
                    // Wake that display once after the transition, never dismiss/wake the main keyguard.
                    remote.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                    // Refocus the existing rear task. Starting a new Activity while locked is denied by HyperOS.
                    if (!ending && id.equals(session) && remote.renewRearVirtualCastLease(id) && remote.isTaskOnDisplay(host, 1))
                        remote.executeShellCommand("am task focus " + host);
                    android.util.Log.i("VirtualRearHost", "Main locked; rear-only wake/refocus session=" + id);
                }
            } catch (Exception e) { fail(e); }
        });
    }
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                requestLockRecovery(350);
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                lockRecoveryRequested = false;
            }
        }
    };
    private final Runnable outputTimeout = () -> { if (reconnecting) fail(new IllegalStateException("Rear output reconnect timeout")); };
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
                    RearPresentationPreferences.Selection presentation = RearPresentationPreferences.read(VirtualRearHostActivity.this, pkg);
                    if (compatibility || presentation.full)
                        plan = new JSONObject(service.getRearCastRenderPlan(task, component, dpi, compatibility, presentation.full));
                    RearCastViewport render = RearCastGeometry.decode(plan.toString());
                    android.util.Log.i("VirtualRearHost", "Received safe viewport=" + safe.key());
                    main.post(() -> {
                        if (ending) return;
                        if (safeViewport != null && !safeViewport.key().equals(safe.key())) {
                            fail(new IllegalStateException("Rear safe area changed; restart casting")); return;
                        }
                        safeViewport = safe;
                        densityCompatibility = compatibility;
                        initialRotation = presentation.rotation;
                        initialFull = presentation.full;
                        if (viewport == null) viewport = render;
                        castMenu.updateViewportMode(initialFull);
                        root.post(VirtualRearHostActivity.this::layoutViewport);
                    });
                } catch (Exception e) { fail(e); }
            });
        }
        public void onServiceDisconnected(ComponentName name) { service = null; fail(new IllegalStateException("Shizuku disconnected")); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT < 33) { finish(); return; }
        if (!BuildConfig.VIRTUAL_CAST_ENABLED || getDisplay().getDisplayId() != 1 || getIntent().getBooleanExtra("stop", false)
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
        backIndicator = new RearBackGestureView(this);
        root.addView(backIndicator, new FrameLayout.LayoutParams(backIndicator.dp(32), backIndicator.dp(40), Gravity.RIGHT | Gravity.TOP));
        castMenu = new RearCastMenuUi(this, this::finish, () -> rotateContent(-1), () -> rotateContent(1), this::toggleViewport, this::dismissMenu);
        root.addView(castMenu.getView(), new FrameLayout.LayoutParams(1, 1));
        root.getViewTreeObserver().addOnGlobalLayoutListener(this::layoutViewport);
        setContentView(root);
        getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());
        IntentFilter screenEvents = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        screenEvents.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenReceiver, screenEvents, Context.RECEIVER_NOT_EXPORTED);
        screenReceiverRegistered = true;
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
        if (resizing) maybeFinishViewport(); else maybeStart();
    }
    public void surfaceCreated(SurfaceHolder value) { holder = value; }
    public void surfaceChanged(SurfaceHolder value, int format, int w, int h) {
        android.util.Log.i("VirtualRearHost", "Surface " + w + "x" + h + " starting=" + starting);
        if (starting && !resizing && (width != w || height != h)) { fail(new IllegalStateException("Rear Surface resized; restart casting")); return; }
        holder = value; width = w; height = h;
        if (resizing) { layoutViewport(); return; }
        if (starting && reconnecting) {
            Surface surface = value.getSurface(); int generation = outputGeneration;
            worker.execute(() -> {
                try {
                    if (ending || generation != outputGeneration) return;
                    if (service == null || session == null || !service.updateRearVirtualCastSurface(session, surface))
                        throw new IllegalStateException("Rear output session ended");
                    main.post(() -> {
                        if (generation == outputGeneration && !ending) {
                            reconnecting = false; main.removeCallbacks(outputTimeout);
                        }
                    });
                } catch (Exception e) { fail(e); }
            });
        } else maybeStart();
    }
    public void surfaceDestroyed(SurfaceHolder value) {
        holder = null; outputGeneration++; main.removeCallbacks(startAfterLayout);
        clearFeedback();
        menuVisible = false; if (castMenu != null) castMenu.show(false);
        if (!starting || ending) return;
        if (resizing) { fail(new IllegalStateException("Rear output lost during viewport change")); return; }
        boolean mainLocked = getSystemService(android.app.KeyguardManager.class).isKeyguardLocked()
                || !getSystemService(PowerManager.class).isInteractive();
        android.util.Log.i("VirtualRearHost", "Surface lost; mainLocked=" + mainLocked);
        if (!mainLocked) { finish(); return; }
        reconnecting = true;
        main.removeCallbacks(outputTimeout); main.postDelayed(outputTimeout, 2300);
        worker.execute(() -> {
            try {
                if (!ending && service != null && session != null && !service.updateRearVirtualCastSurface(session, null))
                    throw new IllegalStateException("Rear output session ended");
            } catch (Exception e) { fail(e); }
        });
        requestLockRecovery(150);
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
                        getIntent().getIntExtra("dpi", VirtualCastRequest.DEFAULT_DENSITY_DPI), getIntent().getStringExtra("component"), owner, gestures,
                        RearCastGeometry.encode(viewport), densityCompatibility, initialRotation, initialFull);
                JSONObject state = new JSONObject(status);
                session = state.getString("id");
                sessionPackage = state.getString("package");
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
        android.util.Log.e("VirtualRearHost", "Virtual casting stopped", e);
        main.post(this::finish);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) { return true; /* hardware is exclusively read by evdev */ }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra("stop", false)) finish();
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
        if (safeViewport != null) worker.execute(() -> {
            try {
                if (!safeViewport.key().equals(RearCastGeometry.decode(service.getRearCastViewport()).key()))
                    throw new IllegalStateException("Rear display geometry changed; restart casting");
            } catch (Exception e) { fail(e); }
        });
    }
    @Override protected void onDestroy() {
        ending = true; main.removeCallbacksAndMessages(null);
        clearFeedback();
        menuVisible = false; if (castMenu != null) castMenu.show(false);
        if (screenReceiverRegistered) { unregisterReceiver(screenReceiver); screenReceiverRegistered = false; }
        worker.execute(() -> {
            try { if (service != null && session != null) service.stopRearVirtualCast(session); }
            catch (Exception e) { android.util.Log.e("VirtualRearHost", "Stop", e); }
            finally { TaskServiceManager.release(connection); }
        });
        if (session != null) RearScreenKeeperService.stopVirtualSession(session);
        worker.shutdown();
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
