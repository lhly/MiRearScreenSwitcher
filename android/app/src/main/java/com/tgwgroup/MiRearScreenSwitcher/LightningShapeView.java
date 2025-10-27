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

package com.tgwgroup.MiRearScreenSwitcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Color;
import android.graphics.BlurMaskFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import androidx.core.graphics.PathParser;

/**
 * 闪电形状的液体填充视图
 * 支持从底部向上填充绿色液体，带重力感应
 */
public class LightningShapeView extends View implements SensorEventListener {
    private Paint liquidPaint;      // 液体画笔
    private Paint liquidShinePaint; // 液体光泽画笔
    private Paint bubblePaint;      // 气泡画笔
    private Paint outlinePaint;     // 边框画笔
    private Paint glassHighlightPaint;  // 玻璃高光画笔
    private Paint glassReflectionPaint; // 玻璃反射光画笔
    private Paint innerGlowPaint;   // 内部发光画笔
    private Paint glassDepthPaint;  // 玻璃深度画笔
    private Path lightningPath;     // 闪电形状路径
    private Path highlightPath;     // 高光路径（左上角）
    private Path wavePath;          // 液面波浪路径
    private float fillLevel = 0f;   // 填充比例 0.0 - 1.0
    private float waveOffset = 0f;  // 波浪动画偏移
    private float tiltX = 0f;       // X轴倾斜角度（重力感应）
    private float tiltY = 0f;       // Y轴倾斜角度（重力感应）
    private float[] bubblePositions = new float[6]; // 气泡Y位置（受重力影响）
    private SensorManager sensorManager;
    private Sensor accelerometer;
    
    // V3.5: 全屏液体模式（不绘制闪电边框）
    private boolean fullScreenMode = false;
    
    // V3.5: 复用对象避免GC（性能优化）
    private Path fullScreenLiquidPath = new Path();
    private Path fullScreenWavePath = new Path();  // 复用波浪路径
    private Paint fullScreenShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint fullScreenBottomShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);  // 复用底部阴影画笔
    private Paint fullScreenWavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);  // 复用波浪画笔
    private Paint fullScreenEdgeShinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);  // 复用边缘光泽画笔
    private int lastShadowHeight = -1;  // 缓存上次的高度，避免重复创建shader
    private int lastBottomShadowHeight = -1;  // 缓存底部阴影高度
    private int lastEdgeShineWidth = -1;  // 缓存边缘光泽宽度
    private Paint bubbleHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);  // 复用气泡高光画笔
    
    // V3.5: 波浪计算优化（预计算，避免每帧sin计算）
    private float[] wavePoints = new float[200];  // 预计算波浪点
    private int lastWaveWidth = -1;  // 缓存波浪宽度
    private float lastWaveOffset = -1f;  // 缓存波浪偏移
    
    // V3.14: 恢复波浪计算频率，保证流畅度
    private static final float WAVE_UPDATE_THRESHOLD = 0.01f;  // 波浪更新阈值（拉满）
    private float lastProcessedWaveOffset = -1f;  // 上次处理的波浪偏移
    
    public LightningShapeView(Context context) {
        super(context);
        init();
    }
    
    public LightningShapeView(Context context, AttributeSet attrs) {
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
            Log.w("LightningShapeView", "重力传感器初始化失败", e);
        }
        
        // 启用硬件加速的图层类型
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        
        // 液体画笔（绿色渐变）
        liquidPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        liquidPaint.setStyle(Paint.Style.FILL);
        liquidPaint.setDither(true); // 抖动，更平滑的渐变
        
        // 液体光泽画笔（液体表面的反光）
        liquidShinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        liquidShinePaint.setStyle(Paint.Style.FILL);
        
        // 气泡画笔（液体中的气泡）
        bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bubblePaint.setStyle(Paint.Style.FILL);
        bubblePaint.setColor(0x80FFFFFF);  // 增加透明度，让气泡更明显
        bubblePaint.setMaskFilter(new BlurMaskFilter(2f, BlurMaskFilter.Blur.NORMAL)); // 减少模糊，让气泡更清晰
        
        // V3.5: 气泡高光画笔（预先初始化，避免每帧创建）
        bubbleHighlightPaint.setColor(0xB0FFFFFF);
        
        // 主边框画笔（半透明白色）
        outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStrokeWidth(6f);
        outlinePaint.setColor(0x80FFFFFF);
        
        // 玻璃高光画笔（左上角明亮边缘）
        glassHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glassHighlightPaint.setStyle(Paint.Style.STROKE);
        glassHighlightPaint.setStrokeWidth(4f);
        glassHighlightPaint.setColor(0xF0FFFFFF); // 非常亮
        glassHighlightPaint.setMaskFilter(new BlurMaskFilter(2f, BlurMaskFilter.Blur.OUTER));
        
        // 玻璃反射光画笔（外部光晕）
        glassReflectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glassReflectionPaint.setStyle(Paint.Style.STROKE);
        glassReflectionPaint.setStrokeWidth(12f);
        glassReflectionPaint.setColor(0x50FFFFFF);
        glassReflectionPaint.setMaskFilter(new BlurMaskFilter(6f, BlurMaskFilter.Blur.OUTER));
        
        // 玻璃深度画笔（内部阴影，增强立体感）
        glassDepthPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glassDepthPaint.setStyle(Paint.Style.STROKE);
        glassDepthPaint.setStrokeWidth(8f);
        glassDepthPaint.setColor(0x40000000);
        glassDepthPaint.setMaskFilter(new BlurMaskFilter(4f, BlurMaskFilter.Blur.INNER));
        
        // 内部发光画笔（液体周围的光晕）
        innerGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        innerGlowPaint.setStyle(Paint.Style.STROKE);
        innerGlowPaint.setStrokeWidth(2f);
        innerGlowPaint.setColor(0x60FFFFFF);
        
        // 创建路径
        lightningPath = new Path();
        highlightPath = new Path();
        wavePath = new Path();
        
        // 初始化气泡位置（简单方案）
        for (int i = 0; i < bubblePositions.length; i++) {
            bubblePositions[i] = (float) Math.random();
        }
    }
    
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        
        // 使用Android的PathParser解析SVG路径
        // 原始SVG path data（从lightening.xml）
        String pathData = "M511.616,85.333 c-27.947,0 -54.059,14.08 -69.717,37.547 l-256.811,385.707 " +
                         "a86.187,86.187 0,0,0 22.613,118.571 l6.101,3.84 " +
                         "c12.501,7.04 26.624,10.795 41.003,10.795 h172.544 " +
                         "v211.499 c0,47.147 37.675,85.376 84.139,85.376 " +
                         "c27.861,0 53.888,-13.952 69.547,-37.291 l257.707,-383.829 " +
                         "a86.187,86.187 0,0,0 -22.187,-118.613 l-6.144,-3.883 " +
                         "a83.2,83.2 0,0,0 -41.216,-10.965 h-173.44 " +
                         "v-213.333 C595.755,123.52 558.08,85.333 511.616,85.333 z";
        
        try {
            // 使用AndroidX的PathParser解析SVG路径
            lightningPath = PathParser.createPathFromPathData(pathData);
            
            // 缩放路径以适应视图大小（原始viewBox是1024x1024）
            android.graphics.Matrix matrix = new android.graphics.Matrix();
            matrix.setScale(w / 1024f, h / 1024f);
            lightningPath.transform(matrix);
            
        } catch (Exception e) {
            Log.e("LightningShapeView", "解析SVG路径失败，使用简化闪电形状", e);
            
            // 回退：使用简化的闪电形状
            lightningPath.reset();
            float centerX = w / 2f;
            
            lightningPath.moveTo(centerX, h * 0.08f);
            lightningPath.lineTo(centerX - w * 0.18f, h * 0.5f);
            lightningPath.lineTo(centerX + w * 0.05f, h * 0.52f);
            lightningPath.lineTo(centerX - w * 0.08f, h * 0.92f);
            lightningPath.lineTo(centerX + w * 0.12f, h * 0.58f);
            lightningPath.lineTo(centerX + w * 0.18f, h * 0.56f);
            lightningPath.close();
        }
        
        // 使用系统电池绿色（#34C759），去掉渐变，使用纯色
        liquidPaint.setShader(null);  // 移除渐变
        liquidPaint.setColor(0xFF34C759);  // 系统电池绿色
        
        // 创建左上角高光路径（模拟玻璃反射）
        highlightPath.reset();
        highlightPath.moveTo(w * 0.2f, h * 0.1f);
        highlightPath.lineTo(w * 0.35f, h * 0.15f);
        highlightPath.lineTo(w * 0.3f, h * 0.35f);
        highlightPath.lineTo(w * 0.15f, h * 0.3f);
        highlightPath.close();
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        long drawStartTime = System.nanoTime();  // 性能追踪开始
        
        super.onDraw(canvas);
        
        int width = getWidth();
        int height = getHeight();
        
        // V3.5: 全屏液体模式 - 直接绘制液体，不裁剪为闪电形状
        if (fullScreenMode) {
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
                float currentDrawMs = drawTimeNanos / 1_000_000f;
                float avgFrameIntervalMs = (totalFrameInterval / (float)(frameCount - 1)) / 1_000_000f;  // 平均帧间隔
                
                long currentTime = System.currentTimeMillis();
                long timeSinceLastLog = currentTime - lastFrameTime;
                float actualFps = (timeSinceLastLog > 0) ? (60000f / timeSinceLastLog) : 0;
                
                // 计算理论最大帧间隔（考虑绘制时间）
                float drawTimeMs = currentDrawMs;
                float maxTheoreticalFps = (drawTimeMs > 0) ? (1000f / drawTimeMs) : 999;
                float vsyncFps = (avgFrameIntervalMs > 0) ? (1000f / avgFrameIntervalMs) : 0;
                
                Log.d("LightningPerf", String.format("📊 性能: FPS=%.1f, VSync=%.1fHz (间隔%.2fms), 平均绘制=%.2fms", 
                    actualFps, vsyncFps, avgFrameIntervalMs, avgDrawTimeMs));
                
                lastFrameTime = currentTime;
                totalDrawTime = 0;
                totalFrameInterval = 0;
                frameCount = 0;
                lastFrameTimeNanos = 0;
            }
            
            return;
        }
        
        // 原有的闪电容器模式
        // 应用重力倾斜（夸张效果，模拟真实液体）
        canvas.save();
        canvas.translate(tiltX * 5, tiltY * 3);
        
        // 第0层：绘制玻璃深度阴影（内部凹陷感）
        canvas.save();
        canvas.translate(2, 2);
        canvas.drawPath(lightningPath, glassDepthPaint);
        canvas.restore();
        
        // 第1层：绘制外部柔和反射光（最外层光晕）
        canvas.save();
        canvas.translate(4, 4);
        canvas.drawPath(lightningPath, glassReflectionPaint);
        canvas.restore();
        
        // 第2层：保存画布并裁剪为闪电形状
        canvas.save();
        canvas.clipPath(lightningPath);
        
        // 绘制液体填充（从底部向上）
        if (fillLevel > 0) {
            float fillHeight = height * fillLevel;
            
            // 2.1 绘制主液体（绿色渐变）
            canvas.drawRect(0, height - fillHeight, width, height, liquidPaint);
            
            // 2.2 绘制液体底部的深色阴影（复用Paint，仅高度变化时重建shader）
            if (lastBottomShadowHeight != height) {
                fullScreenBottomShadowPaint.setShader(new LinearGradient(
                    0, height - 30, 0, height,
                    new int[]{0x00000000, 0x40000000, 0x50000000},
                    new float[]{0f, 0.5f, 1f},
                    Shader.TileMode.CLAMP
                ));
                lastBottomShadowHeight = height;
            }
            canvas.drawRect(0, height - 30, width, height, fullScreenBottomShadowPaint);
            
            // 2.3 绘制液面波浪（优化：减少计算频率）
            if (fillHeight > 20) {
                float waveY = height - fillHeight;
                fullScreenWavePath.reset();
                
                // V3.17: 微小闪电模式重力倾斜强度，细腻效果
                float leftTilt = tiltX * 6;  // 左侧倾斜量（微小强度）
                float rightTilt = -tiltX * 6; // 右侧倾斜量（微小强度）
                
                fullScreenWavePath.moveTo(0, waveY + leftTilt);
                
                // V3.6: 统一波浪计算（避免重复计算）
                updateWavePoints(width, waveOffset);
                
                // 使用预计算的波浪点
                int pointCount = Math.min(width / 8, wavePoints.length);  // 减少绘制点数
                for (int i = 0; i < pointCount; i++) {
                    float x = (float) i / (pointCount - 1) * width;
                    float wave = wavePoints[i];
                    float tilt = leftTilt + (rightTilt - leftTilt) * (x / (float)width);
                    fullScreenWavePath.lineTo(x, waveY + wave + tilt);
                }
                fullScreenWavePath.lineTo(width, height);
                fullScreenWavePath.lineTo(0, height);
                fullScreenWavePath.close();
                
                // 绘制波浪液体（复用Paint，只设置alpha）
                fullScreenWavePaint.set(liquidPaint);
                fullScreenWavePaint.setAlpha(220);
                canvas.drawPath(fullScreenWavePath, fullScreenWavePaint);
            }
            
            // 2.4 液面光泽已移除（用户要求去掉液体顶部的白色）
            // 不再绘制白色高光，保持纯净的液体颜色
            
            // V3.15: 修复气泡闪烁，每帧都绘制
            if (fillHeight > 10) {  // 降低条件，让气泡在更低的液体高度时也能显示
                drawBubbles(canvas, width, height, fillHeight);
            }
            
            // 2.6 绘制液体左侧的明亮边缘（复用Paint，仅宽度变化时重建shader）
            if (lastEdgeShineWidth != width) {
                fullScreenEdgeShinePaint.setStyle(Paint.Style.FILL);
                fullScreenEdgeShinePaint.setShader(new LinearGradient(
                    width * 0.08f, 0, width * 0.22f, 0,
                    new int[]{0x00FFFFFF, 0x30FFFFFF, 0x20FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.3f, 0.7f, 1f},
                    Shader.TileMode.CLAMP
                ));
                lastEdgeShineWidth = width;
            }
            canvas.drawRect(width * 0.08f, height - fillHeight, 
                           width * 0.22f, height, fullScreenEdgeShinePaint);
            
            // 2.8 液体内部光线散射效果已移除
            // 保持纯净的液体颜色，不添加白色散射
            
            // 2.9 液体与玻璃壁交界处反光已移除
            // 保持纯净的液体颜色
        }
        
        // 恢复画布（取消裁剪）
        canvas.restore();
        
        // 第3层：绘制主边框
       canvas.drawPath(lightningPath, outlinePaint);
        
        // 第4层：左上角强烈高光（模拟光源反射）
        //canvas.save();
        //canvas.clipPath(lightningPath);
        //canvas.translate(-width * 0.05f, -height * 0.05f);
        //canvas.drawPath(lightningPath, glassHighlightPaint);
        //canvas.restore();
        
        // 第5层：右下角柔和阴影（增强3D效果）
        //canvas.save();
        //canvas.translate(width * 0.02f, height * 0.02f);
        //Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        //shadowPaint.setStyle(Paint.Style.STROKE);
        //shadowPaint.setStrokeWidth(3f);
        //shadowPaint.setColor(0x30000000); // 19% 透明黑色
        //canvas.drawPath(lightningPath, shadowPaint);
        //canvas.restore();
        
        // 第6层：内部高光（沿着左上边缘的光带）
        canvas.save();
        canvas.clipPath(lightningPath);
        // 绘制左上角的小面积高光反射（复用Paint，避免每帧创建）
        if (lastEdgeShineWidth != width) {
            fullScreenEdgeShinePaint.setStyle(Paint.Style.FILL);
            fullScreenEdgeShinePaint.setShader(new android.graphics.RadialGradient(
                width * 0.25f, height * 0.2f, width * 0.3f,
                new int[]{0x50FFFFFF, 0x20FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP
            ));
            lastEdgeShineWidth = width;
        }
        canvas.drawPath(highlightPath, fullScreenEdgeShinePaint);
        canvas.restore();
        
        // 恢复重力倾斜的变换
        canvas.restore();
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
        Log.d("LightningShapeView", "✓ 波浪动画已启动（Choreographer.FrameCallback，跟随屏幕刷新率）");
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
        Log.d("LightningShapeView", "🔋 填充比例已更新: " + (level * 100) + "%");
    }
    
    /**
     * V3.5: 设置全屏液体模式
     */
    public void setFullScreenMode(boolean enabled) {
        this.fullScreenMode = enabled;
        
        // 全屏模式下立即启动波浪动画
        if (enabled && fillLevel > 0) {
            startWaveAnimation();
        }
        
        invalidate();
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
            Log.d("LightningShapeView", "✅ 重力传感器已注册（UI延迟）");
        }
    }
    
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // 注销重力传感器
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
            Log.d("LightningShapeView", "❌ 重力传感器已注销");
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

