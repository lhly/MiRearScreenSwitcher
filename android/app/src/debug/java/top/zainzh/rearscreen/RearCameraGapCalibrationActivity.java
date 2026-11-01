package top.zainzh.rearscreen;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.FrameLayout;

/** Shell-only optical calibration. This Activity is never packaged in a release. */
@androidx.annotation.RequiresApi(33)
public final class RearCameraGapCalibrationActivity extends Activity {
    private RearCameraGapBatteryView battery;
    private Grid grid;
    private RearCameraGapGeometry.Profile profile;
    private RearCameraStatusView status;
    private RearCameraStatusSource statusSource;
    private boolean resumed, ready;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        profile = RearCameraGapGeometry.candidate(getIntent().getStringExtra("device") == null
                ? android.os.Build.DEVICE : getIntent().getStringExtra("device"));
        if (profile == null) { finish(); return; }
        getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        grid = new Grid(); root.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        battery = new RearCameraGapBatteryView(this);
        root.addView(battery, new FrameLayout.LayoutParams(-1, -1));
        status = new RearCameraStatusView(this);
        root.addView(status, new FrameLayout.LayoutParams(-1, -1));
        statusSource = new RearCameraStatusSource(this, (time, date, weekday, weather) -> {
            status.setData(time, date, weekday, weather);
            android.util.Log.i("RearCameraStatusProbe", "ordinary_app_uid=" + android.os.Process.myUid()
                    + " time=" + time + " date=" + date + " weather=" + (weather == null ? "unavailable" : weather.temperature));
        });
        setContentView(root);
        getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());
        root.post(() -> {
            int[] xy = new int[2]; root.getLocationOnScreen(xy);
            battery.configure(profile, xy[0], xy[1]);
            status.configure(profile, xy[0], xy[1]);
            ready = true; apply();
            android.util.Log.i("RearCameraGapProbe", "device=" + profile.device + " display=" + getDisplay().getDisplayId()
                    + " root=" + xy[0] + "," + xy[1] + "/" + root.getWidth() + "x" + root.getHeight());
            main.postDelayed(() -> android.util.Log.i("RearCameraGapProbe", "settled percentage="
                    + battery.getPercentage() + " draws=" + battery.getDrawCount()), 5000);
        });
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); apply(); }
    private void apply() {
        if (getIntent().getBooleanExtra("finish", false)) { finish(); return; }
        if (!ready) return;
        int level = getIntent().getIntExtra("percentage", -1);
        if (level < 0) {
            Intent current = registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (current != null) level = RearCameraGapGeometry.percentage(current.getIntExtra("level", -1), current.getIntExtra("scale", -1));
        }
        battery.setPercentage(level);
        grid.setVisibility(getIntent().getBooleanExtra("grid", false) ? View.VISIBLE : View.GONE);
        boolean show = getIntent().getBooleanExtra("status", true);
        status.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show && resumed) statusSource.start(); else statusSource.stop();
    }
    @Override protected void onResume() { super.onResume(); resumed = true; apply(); }
    @Override protected void onPause() { resumed = false; statusSource.stop(); super.onPause(); }
    @Override protected void onDestroy() { main.removeCallbacksAndMessages(null); if (statusSource != null) statusSource.close(); super.onDestroy(); }

    private final class Grid extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Grid() { super(RearCameraGapCalibrationActivity.this); }
        @Override protected void onDraw(Canvas canvas) {
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(.65f); paint.setColor(Color.rgb(22, 54, 46));
            for (int x = 0; x < 296; x += 20) canvas.drawLine(x, 0, x, profile.height, paint);
            for (int y = 0; y < profile.height; y += 20) canvas.drawLine(0, y, 296, y, paint);
            paint.setColor(Color.rgb(170, 100, 40)); paint.setStrokeWidth(1);
            canvas.drawCircle(profile.x, profile.upperY, profile.radius + profile.margin, paint);
            canvas.drawCircle(profile.x, profile.lowerY, profile.radius + profile.margin, paint);
            paint.setStyle(Paint.Style.FILL); paint.setTextSize(22); paint.setColor(Color.LTGRAY);
            canvas.drawText("摄像头间电量 · 边界标定", 330, 190, paint);
            canvas.drawText(profile.device + "  " + profile.width + " × " + profile.height, 330, 228, paint);
            canvas.drawText("请确认竖条及百分比完整可见", 330, 290, paint);
            canvas.drawText("镜头周围应保留少量黑色间距", 330, 325, paint);
            canvas.drawText("本页仅用于标定，可直接返回退出", 330, 410, paint);
        }
    }
}
