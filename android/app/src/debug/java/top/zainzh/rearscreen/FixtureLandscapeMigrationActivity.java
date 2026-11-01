package top.zainzh.rearscreen;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.widget.TextView;

/** Fixed landscape/non-resizable target for task migration checks; never included in release APKs. */
public final class FixtureLandscapeMigrationActivity extends Activity {
    public static volatile FixtureLandscapeMigrationActivity instance;
    public volatile int ups;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        instance = this;
        TextView content = new TextView(this);
        content.setGravity(android.view.Gravity.CENTER);
        content.setText("Landscape task migration fixture");
        setContentView(content);
        Log.i("LandscapeMigrationFixture", "created task=" + getTaskId() + " display=" + getDisplay().getDisplayId()
                + " orientation=" + getResources().getConfiguration().orientation);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP) ups++;
        return true;
    }
    @Override public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
