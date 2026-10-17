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

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.BlurMaskFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

/**
 * 背屏充电液体视图
 * 支持从底部向上填充绿色液体，带重力感应
 */
public class ChargingLiquidView extends View implements SensorEventListener {
    private Paint liquidPaint;      // 液体画笔
    private Paint bubblePaint;      // 气泡画笔
    private float fillLevel = 0f;   // 填充比例 0.0 - 1.0
    private float waveOffset = 0f;  // 波浪动画偏移
    private float tiltX = 0f;       // X轴倾斜角度（重力感应）
    private float tiltY = 0f;       // Y轴倾斜角度（重力感应）
    private float[] bubblePositions = new float[6]; // 气泡Y位置（受重力影响）
    private SensorManager sensorManager;
    private Sensor accelerometer;

    // V3.5: 复用对象避免GC（性能优化）
    private Path fullScreenLiquidPath = new Path();
    private Paint fullScreenShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int lastShadowHeight = -1;  // 缓存上次的高度，避免重复创建shader

    // V3.5: 波浪计算优化（预计算，避免每帧sin计算）
    private float[] wavePoints = new float[200];  // 预计算波浪点
    private int lastWaveWidth = -1;  // 缓存波浪宽度

    // V3.14: 恢复波浪计算频率，保证流畅度
    private static final float WAVE_UPDATE_THRESHOLD = 0.01f;  // 波浪更新阈值（拉满）
    private float lastProcessedWaveOffset = -1f;  // 上次处理的波浪偏移

    public ChargingLiquidView(Context context) {
        super(context);
        init();
    }

    public ChargingLiquidView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        // 初始化重力传感器
        try {
            sensorManager = (SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager != null) {
                accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            }
        } catch (Exception e) {
            Log.w("ChargingLiquidView", "重力传感器初始化失败", e);
        }

        // 启用硬件加速的图层类型
        setLayerType(View.LAYER_TYPE_HARDWARE, null);

        // 绿色表示充电状态，与主题强调色独立。
        liquidPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        liquidPaint.setStyle(Paint.Style.FILL);
        liquidPaint.setColor(getContext().getColor(R.color.remrss_charging_liquid));

        // 气泡画笔（液体中的气泡）
        bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bubblePaint.setStyle(Paint.Style.FILL);
        bubblePaint.setColor(0x80FFFFFF);  // 增加透明度，让气泡更明显
        bubblePaint.setMaskFilter(new BlurMaskFilter(2f, BlurMaskFilter.Blur.NORMAL)); // 减少模糊，让气泡更清晰

        // 初始化气泡位置（简单方案）
        for (int i = 0; i < bubblePositions.length; i++) {
            bubblePositions[i] = (float) Math.random();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long drawStartTime = System.nanoTime();  // 性能追踪开始

        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();

        drawFullScreenLiquid(canvas, width, height);

        // V3.5: 性能追踪（修复bug + 增加帧间隔追踪）
        long drawEndTime = System.nanoTime();
        long drawTimeNanos = drawEndTime - drawStartTime;
        totalDrawTime += drawTimeNanos;

        // 计算帧间隔
        if (lastFrameTimeNanos > 0) {
            long frameInterval = drawStartTime - lastFrameTimeNanos;
            totalFrameInterval += frameInterval;
        }
        lastFrameTimeNanos = drawStartTime;
        frameCount++;

        // 每60帧输出一次统计
        if (frameCount % 60 == 0) {
            float avgDrawTimeMs = (totalDrawTime / (float)frameCount) / 1_000_000f;  // 纳秒→毫秒
            float avgFrameIntervalMs = (totalFrameInterval / (float)(frameCount - 1)) / 1_000_000f;  // 平均帧间隔

            long currentTime = System.currentTimeMillis();
            long timeSinceLastLog = currentTime - lastFrameTime;
            float actualFps = (timeSinceLastLog > 0) ? (60000f / timeSinceLastLog) : 0;

            float vsyncFps = (avgFrameIntervalMs > 0) ? (1000f / avgFrameIntervalMs) : 0;

            Log.d("ChargingLiquidPerf", String.format("📊 性能: FPS=%.1f, VSync=%.1fHz (间隔%.2fms), 平均绘制=%.2fms",
                actualFps, vsyncFps, avgFrameIntervalMs, avgDrawTimeMs));

            lastFrameTime = currentTime;
            totalDrawTime = 0;
            totalFrameInterval = 0;
            frameCount = 0;
            lastFrameTimeNanos = 0;
        }

    }

    /**
     * V3.6: 统一波浪计算（避免重复计算）
     */
    private void updateWavePoints(int width, float waveOffset) {
        if (lastWaveWidth != width || Math.abs(lastProcessedWaveOffset - waveOffset) > WAVE_UPDATE_THRESHOLD) {
            // V3.14: 恢复波浪点数，保证流畅度
            int pointCount = Math.min(width / 6, wavePoints.length);
            for (int i = 0; i < pointCount; i++) {
                float x = (float) i / (pointCount - 1) * width;
                wavePoints[i] = (float) Math.sin((x / (float)width * 4 * Math.PI) + waveOffset) * 8f;
            }
            lastWaveWidth = width;
            lastProcessedWaveOffset = waveOffset;
        }
    }

    /**
     * V3.7: 绘制液体中的气泡（恢复重力效果，但保持性能优化）
     */
    private void drawBubbles(Canvas canvas, int width, int height, float fillHeight) {
        float baseY = height - fillHeight;

        // V3.17: 微小气泡重力响应强度，细腻效果
        float gravityOffsetX = -tiltX * 5; // 手机向左倾，气泡向右漂（微小强度）
        float gravityOffsetY = tiltY * 2;   // 前后倾斜的影响（微小强度）

        // V3.15: 增加气泡数量，修复闪烁问题
        // 绘制气泡（简单方案）
        // 气泡1（大）
        float bubble1X = width * 0.2f + gravityOffsetX;
        float bubble1Y = baseY + fillHeight * bubblePositions[0] + gravityOffsetY;
        canvas.drawCircle(bubble1X, bubble1Y, 6f, bubblePaint);

        // 气泡2（中）
        float bubble2X = width * 0.4f + gravityOffsetX * 0.8f;
        float bubble2Y = baseY + fillHeight * bubblePositions[1] + gravityOffsetY;
        canvas.drawCircle(bubble2X, bubble2Y, 4f, bubblePaint);

        // 气泡3（小）
        float bubble3X = width * 0.6f + gravityOffsetX * 0.6f;
        float bubble3Y = baseY + fillHeight * bubblePositions[2] + gravityOffsetY;
        canvas.drawCircle(bubble3X, bubble3Y, 3f, bubblePaint);

        // 气泡4（小）
        float bubble4X = width * 0.8f + gravityOffsetX * 0.9f;
        float bubble4Y = baseY + fillHeight * bubblePositions[3] + gravityOffsetY;
        canvas.drawCircle(bubble4X, bubble4Y, 3.5f, bubblePaint);

        // 气泡5（中）
        float bubble5X = width * 0.3f + gravityOffsetX * 0.7f;
        float bubble5Y = baseY + fillHeight * bubblePositions[4] + gravityOffsetY;
        canvas.drawCircle(bubble5X, bubble5Y, 4.5f, bubblePaint);

        // 气泡6（小）
        float bubble6X = width * 0.7f + gravityOffsetX * 0.5f;
        float bubble6Y = baseY + fillHeight * bubblePositions[5] + gravityOffsetY;
        canvas.drawCircle(bubble6X, bubble6Y, 2.5f, bubblePaint);

        // 简单气泡上升逻辑
        for (int i = 0; i < bubblePositions.length; i++) {
            bubblePositions[i] -= 0.002f; // 固定上升速度
            if (bubblePositions[i] < 0) {
                bubblePositions[i] = 1.0f; // 从底部重新开始
            }
        }
    }

    private long waveAnimationStartTime = 0;
    private android.view.Choreographer.FrameCallback frameCallback;

    // V3.5: 性能追踪
    private long lastFrameTime = 0;
    private long frameCount = 0;
    private long totalDrawTime = 0;
    private long lastFrameTimeNanos = 0;  // 上一帧的纳秒时间
    private long totalFrameInterval = 0;  // 帧间隔总和

    /**
     * 启动波浪动画（优化为120fps）
     */
    private void startWaveAnimation() {
        // 避免重复启动
        if (frameCallback != null) {
            return;
        }

        // 记录起始时间（使用实际帧时间）
        waveAnimationStartTime = 0;

        // 创建FrameCallback
        frameCallback = new android.view.Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                if (fillLevel > 0) {
                    // 初始化起始时间
                    if (waveAnimationStartTime == 0) {
                        waveAnimationStartTime = frameTimeNanos;
                    }

                    // V3.14: 恢复波浪速度，保证流畅度
                    long elapsedNanos = frameTimeNanos - waveAnimationStartTime;
                    waveOffset = (float)((elapsedNanos / 1_000_000_000.0) * Math.PI * 1.5); // 0.67秒一个周期

                    // 请求重绘（使用postInvalidateOnAnimation确保与vsync同步）
                    postInvalidateOnAnimation();

                    // V3.6: 修复 - 只在需要时继续下一帧，避免无限递归
                    if (fillLevel > 0) {
                        android.view.Choreographer.getInstance().postFrameCallback(this);
                    }
                }
            }
        };

        // 开始帧回调
        android.view.Choreographer.getInstance().postFrameCallback(frameCallback);
        Log.d("ChargingLiquidView", "✓ 波浪动画已启动（Choreographer.FrameCallback，跟随屏幕刷新率）");
    }

    /**
     * 设置填充比例
     * @param level 0.0 - 1.0
     */
    public void setFillLevel(float level) {
        this.fillLevel = Math.max(0f, Math.min(1f, level));

        // 如果开始填充，启动波浪动画（仅启动一次）
        if (level > 0.01f && frameCallback == null) {
            startWaveAnimation();
        }

        // 如果填充为0，停止动画
        if (level <= 0 && frameCallback != null) {
            android.view.Choreographer.getInstance().removeFrameCallback(frameCallback);
            frameCallback = null;
        }

        // 强制重绘
        invalidate();
        Log.d("ChargingLiquidView", "🔋 填充比例已更新: " + (level * 100) + "%");
    }

    /**
     * V3.7: 绘制全屏液体（恢复波浪效果，但保持性能优化）
     */
    private void drawFullScreenLiquid(Canvas canvas, int width, int height) {
        if (fillLevel <= 0) return;

        float fillHeight = height * fillLevel;

        // V3.17: 微小重力倾斜强度，细腻效果
        float leftTilt = tiltX * 8;  // 左侧倾斜量（微小强度）
        float rightTilt = -tiltX * 8; // 右侧倾斜量（微小强度）

        // 1. 复用Path对象，避免每帧创建新对象
        fullScreenLiquidPath.reset();

        // 液面波浪 + 重力倾斜
        float waveY = height - fillHeight;
        fullScreenLiquidPath.moveTo(0, waveY + leftTilt);

        // V3.7: 统一波浪计算（避免重复计算）
        updateWavePoints(width, waveOffset);

        // V3.14: 恢复波浪点数，保证流畅度
        int pointCount = Math.min(width / 6, wavePoints.length);  // 恢复密集波浪点
        for (int i = 0; i < pointCount; i++) {
            float x = (float) i / (pointCount - 1) * width;
            float wave = wavePoints[i];
            float tilt = leftTilt + (rightTilt - leftTilt) * (x / (float)width);
            fullScreenLiquidPath.lineTo(x, waveY + wave + tilt);
        }

        // 连接到右下角，再到左下角，形成封闭路径
        fullScreenLiquidPath.lineTo(width, height);
        fullScreenLiquidPath.lineTo(0, height);
        fullScreenLiquidPath.close();

        // 2. 绘制整体液体
        canvas.drawPath(fullScreenLiquidPath, liquidPaint);

        // 3. 绘制底部阴影（仅在高度变化时重新创建shader）
        if (lastShadowHeight != height) {
            fullScreenShadowPaint.setShader(new LinearGradient(
                0, height - 40, 0, height,
                new int[]{0x00000000, 0x20000000, 0x40000000},
                new float[]{0f, 0.7f, 1f},
                Shader.TileMode.CLAMP
            ));
            lastShadowHeight = height;
        }
        canvas.drawPath(fullScreenLiquidPath, fullScreenShadowPaint);

        // V3.15: 修复气泡闪烁，每帧都绘制
        if (fillHeight > 10) {  // 降低条件，让气泡在任何液体高度都能显示
            drawBubbles(canvas, width, height, fillHeight);
        }
    }

    /**
     * 获取当前填充比例
     */
    public float getFillLevel() {
        return fillLevel;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // V3.5: 注册重力传感器（使用UI延迟，降低回调频率）
        if (sensorManager != null && accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
            Log.d("ChargingLiquidView", "✅ 重力传感器已注册（UI延迟）");
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // 注销重力传感器
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
            Log.d("ChargingLiquidView", "❌ 重力传感器已注销");
        }

        // V3.5: 停止Choreographer回调
        if (frameCallback != null) {
            android.view.Choreographer.getInstance().removeFrameCallback(frameCallback);
            frameCallback = null;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            // 获取重力加速度（X和Y轴）
            float x = event.values[0]; // 左右倾斜（-10 到 10）
            float y = event.values[1]; // 前后倾斜（-10 到 10）

            // V3.17: 微小重力感应，更细腻的效果
            float smoothFactor = 0.05f; // 微小灵敏度
            tiltX = tiltX * (1 - smoothFactor) + x * smoothFactor;
            tiltY = tiltY * (1 - smoothFactor) + y * smoothFactor;

            // 限制倾斜范围（微小范围）
            tiltX = Math.max(-2f, Math.min(2f, tiltX));
            tiltY = Math.max(-2f, Math.min(2f, tiltY));

            // V3.5: 不在这里invalidate()，由Choreographer统一驱动刷新，避免过度绘制
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // 不需要处理精度变化
    }
}
