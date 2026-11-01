package top.zainzh.rearscreen;

import android.app.Activity;
import android.os.Bundle;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Local visual fixture, without a cast service or a device input reader. */
@androidx.annotation.RequiresApi(33)
public final class FixtureCastNotificationActivity extends Activity {
    FrameLayout root;
    RearCastNotificationOverlay capsule;
    android.view.SurfaceView output;
    int surfaceChanges;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setDecorFitsSystemWindows(false);
        root = new RearCastFrameLayout(this);
        root.setBackgroundColor(getColor(R.color.remrss_background));
        output = new android.view.SurfaceView(this);
        output.getHolder().addCallback(new android.view.SurfaceHolder.Callback() {
            public void surfaceCreated(android.view.SurfaceHolder holder) { surfaceChanges++; }
            public void surfaceChanged(android.view.SurfaceHolder holder, int format, int width, int height) {
                surfaceChanges++;
                android.graphics.Canvas canvas = holder.lockCanvas();
                if (canvas != null) {
                    canvas.drawColor(getColor(R.color.remrss_background)); holder.unlockCanvasAndPost(canvas);
                }
            }
            public void surfaceDestroyed(android.view.SurfaceHolder holder) { surfaceChanges++; }
        });
        root.addView(output, new FrameLayout.LayoutParams(-1, -1));
        TextView content = new TextView(this);
        content.setText("投放中的 App\n\n通知在上方短暂显示，画面与触摸保持原状");
        content.setTextColor(getColor(R.color.remrss_foreground)); content.setTextSize(20);
        content.setGravity(android.view.Gravity.CENTER);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        capsule = new RearCastNotificationOverlay(this);
        root.addView(capsule, new FrameLayout.LayoutParams(1, -2));
        setContentView(root);
        getWindow().getInsetsController().hide(android.view.WindowInsets.Type.systemBars());
    }
    @Override protected void onDestroy() { capsule.close(); super.onDestroy(); }
}
