package top.zainzh.rearscreen;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** An expiring capsule above the cast Surface; its tap is consumed by the existing input relay. */
@androidx.annotation.RequiresApi(33)
final class RearCastNotificationOverlay extends FrameLayout implements AutoCloseable {
    interface TouchRegion { void update(long token, int left, int top, int right, int bottom); }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ImageView icon;
    private final TextView title, summary;
    private final DisplayManager displays;
    private RearCastNotification.Message message;
    private long expiresAt;
    private boolean available, closed, exiting;
    private final TouchRegion touchRegion;
    private String pendingRegion, readyRegion;
    private int originX, originY;
    private final Runnable render = this::update;
    private final Runnable expire = this::update;
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        public void onDisplayAdded(int id) { update(); }
        public void onDisplayChanged(int id) { if (getContext().getDisplay() != null && id == getContext().getDisplay().getDisplayId()) update(); }
        public void onDisplayRemoved(int id) { update(); }
    };

    RearCastNotificationOverlay(Context context) {
        this(context, null);
    }
    RearCastNotificationOverlay(Context context, TouchRegion touchRegion) {
        super(context);
        this.touchRegion = touchRegion;
        setVisibility(GONE);
        setOnClickListener(view -> { if (message != null) dismiss(message.token); });
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        // Describe the capsule once; its icon and text are not separate controls.
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(8), dp(12), dp(8));
        row.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        row.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        title = label(14); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        summary = label(12);
        labels.addView(title); labels.addView(summary);
        LinearLayout.LayoutParams text = new LinearLayout.LayoutParams(0, -2, 1f);
        text.setMarginStart(dp(8)); row.addView(labels, text);
        addView(row, new FrameLayout.LayoutParams(-1, -2));
        setElevation(dp(4));
        displays = context.getSystemService(DisplayManager.class);
        if (displays != null) displays.registerDisplayListener(displayListener, main);
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView label(int sp) {
        TextView label = new TextView(getContext());
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        label.setMaxLines(1); label.setEllipsize(TextUtils.TruncateAt.END);
        label.setIncludeFontPadding(false);
        return label;
    }

    void offer(RearCastNotification.Message next) {
        if (closed || next.remaining(SystemClock.elapsedRealtime()) == 0) return;
        message = next;
        expiresAt = next.expiresAt;
        AccessibilityManager accessibility = getContext().getSystemService(AccessibilityManager.class);
        if (accessibility != null) {
            int remaining = (int) next.remaining(SystemClock.elapsedRealtime());
            expiresAt = SystemClock.elapsedRealtime() + accessibility.getRecommendedTimeoutMillis(remaining,
                    AccessibilityManager.FLAG_CONTENT_TEXT | AccessibilityManager.FLAG_CONTENT_ICONS);
        }
        String app = next.packageName;
        if (next.charging) {
            app = getContext().getString(R.string.cast_charging_title);
            icon.setImageDrawable(getContext().getDrawable(R.drawable.ic_cast_charging));
        } else try {
            android.content.pm.PackageManager pm = getContext().getPackageManager();
            app = pm.getApplicationLabel(pm.getApplicationInfo(next.packageName, 0)).toString();
            icon.setImageDrawable(pm.getApplicationIcon(next.packageName));
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            icon.setImageDrawable(getContext().getDrawable(android.R.drawable.sym_def_app_icon));
        }
        title.setText(next.title.isEmpty() ? app : next.title);
        summary.setText(next.text);
        summary.setVisibility(next.text.isEmpty() ? GONE : VISIBLE);
        setContentDescription((next.charging ? "" : app + ": ") + title.getText() + (next.text.isEmpty() ? "" : ". " + next.text)
                + ". " + getContext().getString(R.string.cast_notification_dismiss_hint));
        applyTheme();
        main.removeCallbacks(expire);
        update();
    }

    void hostState(RearCastViewport safe, int originX, int originY, boolean ready) {
        if (closed) return;
        this.originX = originX; this.originY = originY;
        available = ready && safe != null;
        if (safe != null) {
            int gutter = Math.min(dp(8), safe.width() / 16);
            int width = Math.min(dp(280), safe.width() - gutter * 2);
            FrameLayout.LayoutParams bounds = new FrameLayout.LayoutParams(width, -2);
            bounds.leftMargin = safe.left - originX + (safe.width() - width) / 2;
            bounds.topMargin = safe.top - originY + gutter;
            FrameLayout.LayoutParams old = (FrameLayout.LayoutParams) getLayoutParams();
            if (old == null || old.width != width || old.leftMargin != bounds.leftMargin || old.topMargin != bounds.topMargin)
                setLayoutParams(bounds);
        }
        update();
    }

    void applyTheme() {
        boolean oled = message != null && message.oled;
        int foreground = getContext().getColor(oled ? R.color.remrss_on_oled : R.color.remrss_foreground);
        int secondary = getContext().getColor(oled ? R.color.remrss_on_oled_secondary : R.color.remrss_secondary);
        title.setTextColor(foreground); summary.setTextColor(secondary);
        GradientDrawable panel = new GradientDrawable();
        panel.setColor(getContext().getColor(oled ? R.color.remrss_oled_background : R.color.remrss_surface));
        panel.setCornerRadius(dp(28));
        panel.setStroke(Math.max(1, dp(.5f)), getContext().getColor(R.color.remrss_outline));
        setBackground(panel);
    }

    private void update() {
        if (closed) return;
        long remaining = message == null ? 0 : Math.max(0, expiresAt - SystemClock.elapsedRealtime());
        Display display = getContext().getDisplay();
        boolean visible = available && remaining > 0 && display != null && display.getState() == Display.STATE_ON;
        main.removeCallbacks(expire);
        if (remaining == 0) { message = null; setContentDescription(null); }
        else main.postDelayed(expire, remaining);
        if (!visible) {
            if (remaining > 0 || !available || getVisibility() != VISIBLE || !ValueAnimator.areAnimatorsEnabled()) {
                hideImmediately(); return;
            }
            if (exiting) return;
            exiting = true;
            animate().alpha(0f).setDuration(120).withEndAction(this::hideImmediately).start();
            return;
        }
        // Register the exact physical hit rectangle before exposing a tappable capsule.
        if (isLayoutRequested() || getWidth() <= 1 || getHeight() == 0) {
            animate().cancel(); exiting = false; setVisibility(INVISIBLE); return;
        }
        int left = getLeft() + originX, top = getTop() + originY;
        int right = getRight() + originX, bottom = getBottom() + originY;
        String region = key(message.token, left, top, right, bottom);
        if (!region.equals(pendingRegion)) {
            pendingRegion = region; readyRegion = null;
            if (touchRegion == null) readyRegion = region;
            else touchRegion.update(message.token, left, top, right, bottom);
        }
        if (!region.equals(readyRegion)) {
            animate().cancel(); exiting = false; setVisibility(INVISIBLE); return;
        }
        boolean entering = getVisibility() != VISIBLE;
        animate().cancel(); exiting = false;
        if (entering) {
            setVisibility(VISIBLE);
            setAlpha(ValueAnimator.areAnimatorsEnabled() ? 0f : 1f);
            setTranslationY(0f);
        }
        if (ValueAnimator.areAnimatorsEnabled())
            animate().alpha(1f).translationY(0f).setDuration(180)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        else { setAlpha(1f); setTranslationY(0f); }
    }

    private void hideImmediately() {
        animate().cancel(); exiting = false; setVisibility(GONE); setAlpha(1f); setTranslationY(0f);
        if (pendingRegion != null) {
            pendingRegion = readyRegion = null;
            if (touchRegion != null) touchRegion.update(0, 0, 0, 0, 0);
        }
        if (message == null && (title.length() > 0 || summary.length() > 0)) {
            title.setText(""); summary.setText(""); icon.setImageDrawable(null); setContentDescription(null);
        }
    }
    private static String key(long token, int left, int top, int right, int bottom) {
        return token + ":" + left + "," + top + "," + right + "," + bottom;
    }
    void regionRegistered(long token, int left, int top, int right, int bottom, boolean success) {
        if (closed || message == null || message.token != token) return;
        String region = key(token, left, top, right, bottom);
        if (!region.equals(pendingRegion)) return;
        if (!success) { dismiss(token); return; }
        readyRegion = region; update();
    }
    void dismiss(long token) {
        if (closed || message == null || message.token != token) return;
        message = null; main.removeCallbacks(expire); hideImmediately();
    }
    void dismissCharging() { if (message != null && message.charging) dismiss(message.token); }
    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        main.removeCallbacks(render); main.post(render);
    }
    boolean hasMessage() { return message != null; }
    @Override protected void onConfigurationChanged(Configuration config) { super.onConfigurationChanged(config); applyTheme(); }
    @Override public void close() {
        closed = true; message = null; main.removeCallbacksAndMessages(null); hideImmediately();
        if (displays != null) displays.unregisterDisplayListener(displayListener);
    }
}
