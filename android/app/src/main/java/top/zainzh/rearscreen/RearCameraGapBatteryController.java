package top.zainzh.rearscreen;

import android.content.*;
import android.hardware.display.DisplayManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.View;

/** An event-driven attachment to a casting host, with no service, timer or frame loop. */
@androidx.annotation.RequiresApi(33)
final class RearCameraGapBatteryController implements AutoCloseable {
    private final Context context;
    private final RearCameraGapBatteryView view;
    private final DisplayManager displays;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences preferences;
    private final RearCameraGapGeometry.Profile profile;
    private RearCastViewport safe;
    private boolean running, full, hostVisible, registered, closed, containsGap;

    private final BroadcastReceiver battery = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            view.setPercentage(RearCameraGapGeometry.percentage(
                    intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)));
        }
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener changed = (p, key) -> {
        if (RearCameraGapPreferences.ENABLED.equals(key)) main.post(this::update);
    };
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        public void onDisplayAdded(int id) { update(); }
        public void onDisplayChanged(int id) { if (context.getDisplay() != null && context.getDisplay().getDisplayId() == id) update(); }
        public void onDisplayRemoved(int id) { update(); }
    };

    RearCameraGapBatteryController(Context context, RearCameraGapBatteryView view) {
        this(context, view, RearCameraGapGeometry.supported(Build.DEVICE));
    }

    RearCameraGapBatteryController(Context context, RearCameraGapBatteryView view, RearCameraGapGeometry.Profile profile) {
        this.context = context; this.view = view;
        this.profile = profile;
        preferences = RearCameraGapPreferences.preferences(context);
        displays = context.getSystemService(DisplayManager.class);
        view.setVisibility(View.GONE);
        preferences.registerOnSharedPreferenceChangeListener(changed);
        if (displays != null) displays.registerDisplayListener(displayListener, main);
    }

    void session(RearCastViewport safe, boolean running, boolean full, int originX, int originY) {
        this.safe = safe; this.running = running; this.full = full;
        View parent = view.getParent() instanceof View ? (View) view.getParent() : null;
        containsGap = profile != null && parent != null && originX <= profile.left()
                && originY <= profile.upperY && originX + parent.getWidth() >= profile.right()
                && originY + parent.getHeight() >= profile.lowerY;
        view.configure(profile, originX, originY);
        update();
    }
    void visible(boolean visible) { hostVisible = visible; update(); }
    void themeChanged() { view.applyTheme(); update(); }

    private void update() {
        if (closed) return;
        Display display = context.getDisplay();
        boolean show = profile != null && profile.matches(safe) && containsGap && running && !full && hostVisible
                && preferences.getBoolean(RearCameraGapPreferences.ENABLED, false)
                && display != null && display.getState() == Display.STATE_ON;
        view.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show && !registered) {
            try {
                registered = true;
                Intent current = context.registerReceiver(battery, new IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED);
                if (current != null) battery.onReceive(context, current);
            } catch (RuntimeException e) {
                registered = false; view.setPercentage(-1); view.setVisibility(View.GONE);
                android.util.Log.w("RearCameraGap", "Battery listener unavailable", e);
            }
        } else if (!show && registered) {
            context.unregisterReceiver(battery); registered = false;
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true; main.removeCallbacksAndMessages(null);
        if (registered) { context.unregisterReceiver(battery); registered = false; }
        preferences.unregisterOnSharedPreferenceChangeListener(changed);
        if (displays != null) displays.unregisterDisplayListener(displayListener);
        view.setVisibility(View.GONE);
    }
}
