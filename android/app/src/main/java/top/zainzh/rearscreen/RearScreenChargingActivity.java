/*
 * Author: AntiOblivionis
 * QQ: 319641317
 * Github: https://github.com/GoldenglowSusie/
 * Bilibili: 罗德岛T0驭械术师澄闪
 * 
 * Chief Tester: 汐木泽
 * 
 * Co-developed with AI assistants:
 * - Cursor
 * - Claude-4.5-Sonnet
 * - GPT-5
 * - Gemini-2.5-Pro
 */

package top.zainzh.rearscreen;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.util.TypedValue;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowCompat;
import androidx.core.widget.TextViewCompat;

/** 背屏液体充电动画，普通模式显示8秒，常亮模式在拔线时退出。 */
public class RearScreenChargingActivity extends Activity {
    private static final String TAG = "RearScreenChargingActivity";
    private static final long DISPLAY_DURATION_MS = 8000;
    private static final long EXIT_DURATION_MS = 250;
    static final String EXTRA_REQUEST_ID = "chargingRequestId";
    private static volatile RearScreenChargingActivity currentInstance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autoFinish = this::finish;
    private android.animation.ValueAnimator liquidAnimator;
    private boolean contentInitialized;
    private boolean resumed;
    private boolean interrupted;
    private boolean exitInProgress;
    private int rearTaskId = -1;
    private long requestId;
    private long animationId;
    private int displayedLevel = -1;
    private ITaskService restoreService;

    public static void updateBatteryLevelStatic(int newLevel) {
        RearScreenChargingActivity activity = currentInstance;
        if (activity != null && activity.contentInitialized && !activity.isFinishing()) {
            activity.updateBatteryLevel(newLevel);
        }
    }

    private final BroadcastReceiver finishReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            // 旧启动请求的异步清理不能关闭已经复用此窗口的新动画。
            long target = intent.getLongExtra(EXTRA_REQUEST_ID, 0);
            if (target != 0 && target != requestId) return;
            String action = intent.getAction();
            if ((BuildConfig.APPLICATION_ID + ".INTERRUPT_CHARGING_ANIMATION").equals(action)) {
                if (RearAnimationManager.isCurrent(RearAnimationManager.AnimationType.CHARGING, animationId)) return;
                interrupted = true;
                ChargingService.cancelForNotification();
                finish();
            } else if ((BuildConfig.APPLICATION_ID + ".FINISH_CHARGING_ANIMATION").equals(action)) {
                finish();
            } else if ((BuildConfig.APPLICATION_ID + ".UPDATE_CHARGING_BATTERY").equals(action)) {
                int level = intent.getIntExtra("batteryLevel", -1);
                if (contentInitialized && level >= 0) updateBatteryLevel(level);
            } else if ((BuildConfig.APPLICATION_ID + ".RELOAD_CHARGING_SETTINGS").equals(action)) {
                if (contentInitialized) scheduleAutoFinish();
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        restoreService = ChargingService.getTaskService();
        readIntent();
        currentInstance = this;
        // 即使先在主屏占位，也必须能接收拔线/打断事件。
        IntentFilter filter = new IntentFilter();
        for (String action : new String[]{"FINISH_CHARGING_ANIMATION", "INTERRUPT_CHARGING_ANIMATION",
                "UPDATE_CHARGING_BATTERY", "RELOAD_CHARGING_SETTINGS"}) {
            filter.addAction(BuildConfig.APPLICATION_ID + "." + action);
        }
        androidx.core.content.ContextCompat.registerReceiver(this, finishReceiver, filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        ensureRearContent();
    }

    private void readIntent() {
        rearTaskId = getIntent().getIntExtra("rearTaskId", -1);
        requestId = getIntent().getLongExtra(EXTRA_REQUEST_ID, 0);
        animationId = requestId == 0 ? RearAnimationManager.getGeneration() : requestId;
        RearAnimationManager.registerAnimationActivityTask(animationId, getTaskId());
    }

    protected int currentDisplayId() {
        return getWindowManager().getDefaultDisplay().getDisplayId();
    }

    private void ensureRearContent() {
        if (isFinishing()) return;
        if (requestId != 0 && !ChargingService.isRequestActive(requestId)) {
            interrupted = true;
            finish();
            return;
        }
        if (!RearDisplay.isRear(currentDisplayId()) || contentInitialized) return;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        if (android.os.Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowManager.LayoutParams windowParams = getWindow().getAttributes();
        if (android.os.Build.VERSION.SDK_INT >= 30) windowParams.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        else if (android.os.Build.VERSION.SDK_INT >= 28) windowParams.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(windowParams);
        setContentView(R.layout.activity_rear_screen_charging);
        contentInitialized = true;
        TextView batteryText = findViewById(R.id.battery_text);
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(batteryText, 24, 60, 1,
                TypedValue.COMPLEX_UNIT_SP);
        TextView status = findViewById(R.id.charging_status);
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(status, 10, 15, 1, TypedValue.COMPLEX_UNIT_SP);
        batteryText.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> alignTextInk());
        status.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> alignTextInk());
        View container = findViewById(R.id.charging_container);
        WindowCompat.getInsetsController(getWindow(), container).hide(WindowInsetsCompat.Type.systemBars());
        ViewCompat.setOnApplyWindowInsetsListener(container, (view, insets) -> {
            applySafeAreaToText(findViewById(R.id.charging_text_group),
                    insets.getInsets(WindowInsetsCompat.Type.displayCutout()));
            return insets;
        });
        ViewCompat.requestApplyInsets(container);
        playAnimation();
        Log.i(TAG, "content_ready display_id=" + currentDisplayId() + " request=" + requestId);
    }

    private void playAnimation() {
        handler.removeCallbacks(autoFinish);
        if (liquidAnimator != null) liquidAnimator.cancel();
        int level = Math.max(0, Math.min(100, getIntent().getIntExtra("batteryLevel", 0)));
        displayedLevel = level;
        TextView text = findViewById(R.id.battery_text);
        text.animate().cancel();
        text.setText(String.format(java.util.Locale.getDefault(), "%d%%", level));
        ChargingLiquidView liquid = findViewById(R.id.full_screen_liquid);
        liquid.setAnimationActive(resumed);
        liquid.restartMotion();
        updateStatus(level);
        startFullScreenLiquidAnimation(liquid, level);
        startCenterTextAnimation(findViewById(R.id.charging_text_group));
        scheduleAutoFinish();
    }

    private void scheduleAutoFinish() {
        handler.removeCallbacks(autoFinish);
        boolean alwaysOn = getSharedPreferences("mrss_settings", MODE_PRIVATE)
                .getBoolean("charging_always_on_enabled", false);
        ChargingLiquidView liquid = findViewById(R.id.full_screen_liquid);
        if (liquid != null) liquid.setAlwaysOn(alwaysOn);
        if (!alwaysOn) {
            handler.postDelayed(autoFinish, DISPLAY_DURATION_MS);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        cancelExit(true);
        setIntent(intent);
        readIntent();
        interrupted = false;
        if (requestId != 0 && !ChargingService.isRequestActive(requestId)) {
            interrupted = true;
            finish();
        } else if (contentInitialized && RearDisplay.isRear(currentDisplayId())) {
            playAnimation();
        } else {
            ensureRearContent();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        ensureRearContent();
        ChargingLiquidView liquid = findViewById(R.id.full_screen_liquid);
        if (liquid != null) liquid.setAnimationActive(true);
        if (liquidAnimator != null && liquidAnimator.isPaused()) liquidAnimator.resume();
    }

    @Override protected void onPause() {
        resumed = false;
        ChargingLiquidView liquid = findViewById(R.id.full_screen_liquid);
        if (liquid != null) liquid.setAnimationActive(false);
        if (liquidAnimator != null && liquidAnimator.isStarted()) liquidAnimator.pause();
        super.onPause();
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        ensureRearContent();
        if (contentInitialized) {
            ViewCompat.requestApplyInsets(findViewById(R.id.charging_container));
            updateStatus(displayedLevel);
        }
    }

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        ensureRearContent();
    }

    @Override public void finish() {
        if (isFinishing()) return;
        View container = findViewById(R.id.charging_container);
        ChargingLiquidView liquid = findViewById(R.id.full_screen_liquid);
        if (liquid != null) liquid.setAnimationActive(false);
        boolean animationsEnabled = android.os.Build.VERSION.SDK_INT < 26
                || android.animation.ValueAnimator.areAnimatorsEnabled();
        if (interrupted || !contentInitialized || container == null || !animationsEnabled) {
            cancelExit(false);
            finishWindow();
            return;
        }
        if (exitInProgress) return;
        exitInProgress = true;
        handler.removeCallbacks(autoFinish);
        if (liquidAnimator != null) liquidAnimator.cancel();
        View text = findViewById(R.id.charging_text_group);
        if (text != null) text.animate().cancel();
        Log.i(TAG, "exit_started request=" + requestId);
        container.animate().alpha(0f).setDuration(EXIT_DURATION_MS)
                .setInterpolator(new android.view.animation.PathInterpolator(0.23f, 1f, 0.32f, 1f))
                .withEndAction(this::finishWindow).start();
    }

    private void cancelExit(boolean resetOpacity) {
        View container = findViewById(R.id.charging_container);
        if (container != null) {
            container.animate().cancel();
            if (resetOpacity) container.setAlpha(1f);
        }
        exitInProgress = false;
    }

    private void finishWindow() {
        Log.i(TAG, "exit_finished request=" + requestId);
        super.finish();
        overridePendingTransition(0, 0);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        cancelExit(false);
        if (liquidAnimator != null) liquidAnimator.cancel();
        View text = findViewById(R.id.charging_text_group);
        if (text != null) text.animate().cancel();
        unregisterReceiver(finishReceiver);
        super.onDestroy();
        if (currentInstance != this) return;
        currentInstance = null;
        if (isChangingConfigurations()) return;
        boolean ownsAnimation = ChargingService.onAnimationFinished(requestId);
        if (!ownsAnimation || interrupted
                || !RearAnimationManager.endAnimation(RearAnimationManager.AnimationType.CHARGING, animationId)) return;
        ITaskService service = ChargingService.getTaskService();
        if (service == null) service = NotificationService.getTaskService();
        if (service == null) service = restoreService;
        if (service != null) ChargingService.restoreAfterAnimation(service, rearTaskId, animationId);
        top.zainzh.rearscreen.platform.RearCapabilityCore.get(this).onNotificationPresentationAvailable();
    }

    /**
     * V3.5: 全屏液体填充动画（非线性，从0到目标电量）
     */
    private void startFullScreenLiquidAnimation(ChargingLiquidView liquidView, int targetLevel) {
        // 目标填充比例
        float targetFillLevel = targetLevel / 100f;
        
        // 创建非线性填充动画（DecelerateInterpolator - 减速效果）
        android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofFloat(0f, targetFillLevel);
        liquidAnimator = animator;
        animator.setDuration(2000); // 2秒填充动画
        animator.setInterpolator(new android.view.animation.DecelerateInterpolator(2.5f));
        
        animator.addUpdateListener(animation -> {
            float animatedValue = (float) animation.getAnimatedValue();
            liquidView.setFillLevel(animatedValue);
        });
        
        animator.start();
        if (!resumed) animator.pause();
        Log.d(TAG, String.format("🌊 全屏液体填充动画已启动: 0%% → %d%%", targetLevel));
    }
    
    /**
     * V3.5: 中央电量数字淡入动画
     */
    private void startCenterTextAnimation(View textView) {
        textView.animate().cancel();
        textView.setAlpha(0f);
        textView.setScaleX(0.8f);
        textView.setScaleY(0.8f);
        
        textView.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(800)
            .setStartDelay(600) // 液体填充开始后显示
            .setInterpolator(new android.view.animation.DecelerateInterpolator(2.0f))
            .start();
    }
    
    /**
     * V3.5: 更新电量显示（充电常亮模式下实时更新）
     */
    private void updateBatteryLevel(int newLevel) {
        if (newLevel < 0 || newLevel > 100 || newLevel == displayedLevel) return;
        displayedLevel = newLevel;
        if (liquidAnimator != null) liquidAnimator.cancel();
        try {
            Log.d(TAG, "🔋 开始更新电量: " + newLevel + "%");
            ChargingLiquidView liquidView = findViewById(R.id.full_screen_liquid);
            TextView batteryText = findViewById(R.id.battery_text);
            
            if (liquidView != null && batteryText != null) {
                liquidAnimator = android.animation.ValueAnimator.ofFloat(liquidView.getFillLevel(), newLevel / 100f);
                liquidAnimator.setDuration(600);
                liquidAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator());
                liquidAnimator.addUpdateListener(animation -> liquidView.setFillLevel((float) animation.getAnimatedValue()));
                liquidAnimator.start();
                if (!resumed) liquidAnimator.pause();
                updateStatus(newLevel);
                // 更新数字
                batteryText.setText(String.format(java.util.Locale.getDefault(), "%d%%", newLevel));
                Log.d(TAG, "🔋 电量已更新: " + newLevel + "%");
            } else {
                Log.w(TAG, "⚠️ 视图未找到，无法更新电量 - liquidView=" + (liquidView != null) + ", batteryText=" + (batteryText != null));
            }
        } catch (Exception e) {
            Log.w(TAG, "更新电量失败: " + e.getMessage());
        }
    }
    
    /** 按实际字形收紧两行间距，并将可见文字整体对齐到安全区中心。 */
    private void alignTextInk() {
        TextView battery = findViewById(R.id.battery_text);
        TextView status = findViewById(R.id.charging_status);
        if (battery.getHeight() == 0 || status.getHeight() == 0 || battery.length() == 0 || status.length() == 0) return;
        android.graphics.Rect numberInk = new android.graphics.Rect();
        android.graphics.Rect statusInk = new android.graphics.Rect();
        String number = battery.getText().toString(), label = status.getText().toString();
        battery.getPaint().getTextBounds(number, 0, number.length(), numberInk);
        status.getPaint().getTextBounds(label, 0, label.length(), statusInk);
        int belowNumber = battery.getHeight() - battery.getBaseline() - numberInk.bottom;
        int aboveLabel = status.getBaseline() + statusInk.top;
        int gap = Math.round(8 * getResources().getDisplayMetrics().density);
        android.widget.LinearLayout.LayoutParams params = (android.widget.LinearLayout.LayoutParams) status.getLayoutParams();
        int margin = gap - belowNumber - aboveLabel;
        if (params.topMargin != margin) { params.topMargin = margin; status.setLayoutParams(params); }
        float leading = battery.getBaseline() + numberInk.top;
        float trailing = status.getHeight() - status.getBaseline() - statusInk.bottom;
        float correction = (leading - trailing) / 2f;
        battery.setTranslationY(-correction); status.setTranslationY(-correction);
    }

    private void updateStatus(int level) {
        TextView status = findViewById(R.id.charging_status);
        if (status != null) status.setText(level >= 100 ? R.string.charging_glass_complete : R.string.charging_glass_status);
    }

    /** 使用当前窗口的安全区域，随背屏旋转更新。 */
    private void applySafeAreaToText(View textView, Insets cutout) {
        android.widget.FrameLayout.LayoutParams params =
                (android.widget.FrameLayout.LayoutParams) textView.getLayoutParams();
        if (params.leftMargin != cutout.left || params.topMargin != cutout.top
                || params.rightMargin != cutout.right || params.bottomMargin != cutout.bottom) {
            params.leftMargin = cutout.left;
            params.topMargin = cutout.top;
            params.rightMargin = cutout.right;
            params.bottomMargin = cutout.bottom;
            textView.setLayoutParams(params);
        }
        ChargingLiquidView liquid = findViewById(R.id.full_screen_liquid);
        if (liquid != null) liquid.setTextInsets(cutout.left, cutout.top, cutout.right, cutout.bottom);
    }
}

