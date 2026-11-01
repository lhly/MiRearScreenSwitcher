package top.zainzh.rearscreen;

import android.content.*;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.view.*;

@androidx.annotation.RequiresApi(33)
final class RearCameraStatusController implements AutoCloseable {
    private final Context context;
    private final RearCameraStatusView view;
    private final RearCameraStatusSource source;
    private final RearCameraGapGeometry.Profile profile = RearCameraGapGeometry.supported(Build.DEVICE);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences preferences;
    private final DisplayManager displays;
    private boolean running, full, hostVisible, contained, closed;
    private RearCastViewport safe;
    private final SharedPreferences.OnSharedPreferenceChangeListener changed = (p, key) -> {
        if (RearCameraGapPreferences.STATUS_ENABLED.equals(key)) main.post(this::update);
    };
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        public void onDisplayAdded(int id) { update(); }
        public void onDisplayRemoved(int id) { update(); }
        public void onDisplayChanged(int id) { if (context.getDisplay() != null && context.getDisplay().getDisplayId() == id) update(); }
    };
    RearCameraStatusController(Context context, RearCameraStatusView view) {
        this.context = context; this.view = view; source = new RearCameraStatusSource(context, view::setData);
        preferences = RearCameraGapPreferences.preferences(context); displays = context.getSystemService(DisplayManager.class);
        view.setVisibility(View.GONE); preferences.registerOnSharedPreferenceChangeListener(changed);
        if (displays != null) displays.registerDisplayListener(displayListener, main);
    }
    void session(RearCastViewport safe, boolean running, boolean full, int x, int y) {
        this.safe = safe; this.running = running; this.full = full;
        View parent = view.getParent() instanceof View ? (View) view.getParent() : null;
        contained = profile != null && parent != null && x <= 0 && y <= 0 && x + parent.getWidth() >= 296 && y + parent.getHeight() >= profile.height;
        view.configure(profile, x, y); update();
    }
    void visible(boolean value) { hostVisible = value; update(); }
    void themeChanged() { view.applyTheme(); }
    private void update() {
        if (closed) return;
        Display display = context.getDisplay();
        boolean show = RearCameraStatusGeometry.supported(profile) && profile.matches(safe) && contained && running && !full && hostVisible
                && RearCameraGapPreferences.statusEnabled(context) && display != null && display.getState() == Display.STATE_ON;
        view.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) source.start(); else source.stop();
    }
    boolean isObserving() { return source.isActive(); }
    @Override public void close() {
        if (closed) return;
        closed = true; main.removeCallbacksAndMessages(null); source.close();
        preferences.unregisterOnSharedPreferenceChangeListener(changed);
        if (displays != null) displays.unregisterDisplayListener(displayListener);
        view.setVisibility(View.GONE);
    }
}
