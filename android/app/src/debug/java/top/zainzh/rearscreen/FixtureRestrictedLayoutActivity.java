package top.zainzh.rearscreen;

import android.app.Activity;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.TextView;

/** Deliberately portrait/non-resizable in the debug manifest; real framework policy probe. */
@android.annotation.TargetApi(33)
public final class FixtureRestrictedLayoutActivity extends Activity {
    public static volatile FixtureRestrictedLayoutActivity instance;
    public volatile int ups;
    public volatile float lastX, lastY;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); instance = this;
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        TextView view = new TextView(this);
        view.setBackgroundColor(0xff183a62); view.setTextColor(0xffffffff); view.setTextSize(24);
        android.content.res.Configuration c = getResources().getConfiguration();
        view.setText("Restricted layout probe\n" + c.screenWidthDp + " × " + c.screenHeightDp + " dp\n" + c.densityDpi + " DPI");
        view.setGravity(android.view.Gravity.CENTER); setContentView(view);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP) { ups++; lastX = event.getX(); lastY = event.getY(); }
        return true;
    }
    @Override public void onDestroy() { if (instance == this) instance = null; super.onDestroy(); }
}
