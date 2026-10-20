package top.zainzh.rearscreen;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;

/** Non-interactive feedback inside the rear Surface host, never a separate system overlay. */
final class RearBackGestureView extends ImageView {
    private boolean tracking, menuArmed;
    private int generation;

    RearBackGestureView(Context context) {
        super(context);
        setScaleType(ScaleType.FIT_CENTER);
        setPadding(dp(7), dp(10), dp(7), dp(10));
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        setClickable(false); setFocusable(false);
        setElevation(dp(3));
        applyTheme();
        clear();
    }
    int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    void applyTheme() {
        GradientDrawable capsule = new GradientDrawable();
        capsule.setColor(getContext().getColor(R.color.remrss_surface));
        capsule.setCornerRadius(dp(20));
        capsule.setStroke(Math.max(1, dp(.5f)), getContext().getColor(R.color.remrss_outline));
        setBackground(capsule);
        setImageResource(menuArmed ? R.drawable.ic_rear_menu_gesture : R.drawable.ic_rear_back_gesture);
        setImageTintList(android.content.res.ColorStateList.valueOf(getContext().getColor(R.color.remrss_foreground)));
    }
    private boolean motionEnabled() { return Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled(); }
    private void interrupt() { generation++; animate().cancel(); }
    void progress(float inward, float y, boolean ready, boolean armed) {
        boolean entering = !tracking;
        boolean changed = menuArmed != armed;
        if (entering || changed) interrupt();
        tracking = true; menuArmed = armed;
        if (entering || changed)
            setImageResource(armed ? R.drawable.ic_rear_menu_gesture : R.drawable.ic_rear_back_gesture);
        // Back stays attached to the edge. Only the explicit menu-ready state leaves it.
        float target = armed ? -dp(18) : dp(2);
        if (changed && !entering && motionEnabled()) {
            animate().translationX(target).setDuration(160)
                    .setInterpolator(new PathInterpolator(.23f, 1f, .32f, 1f)).start();
        } else if (entering || !motionEnabled()) {
            if (!motionEnabled()) interrupt();
            setTranslationX(target);
        }
        int parentHeight = ((View) getParent()).getHeight();
        float center = Math.max(dp(24), Math.min(parentHeight - dp(24), y));
        setTranslationY(center - dp(20));
        setScaleX(1f); setScaleY(1f);
        setAlpha(ready || armed ? 1f : .75f);
        setVisibility(VISIBLE);
        if (entering || changed) {
            android.util.Log.i("RearBackGesture", "mode=" + (armed ? "menu_ready" : "back")
                    + " targetX=" + target + " inward=" + inward + " y=" + y);
        }
    }
    void finish(boolean committed) {
        if (!tracking) return;
        tracking = false; interrupt();
        android.util.Log.i("RearBackGesture", "retreat committed=" + committed);
        if (!motionEnabled()) { clear(); return; }
        int expected = generation;
        animate().translationX(dp(34)).alpha(0f).scaleX(.94f).scaleY(.94f)
                .setDuration(committed ? 125 : 160)
                .setInterpolator(new PathInterpolator(.23f, 1f, .32f, 1f))
                .withEndAction(() -> { if (generation == expected && !tracking) setVisibility(INVISIBLE); }).start();
    }
    void clear() {
        // ImageView.setAlpha(int) controls the drawable; use View alpha so the next gesture restores it.
        tracking = menuArmed = false; interrupt(); setAlpha(0f); setVisibility(INVISIBLE);
    }
    @Override protected void onDetachedFromWindow() { clear(); super.onDetachedFromWindow(); }
}
