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
    private float[] bubblePositions = new float[10]; // 气泡Y位置（受重力影响）
    private SensorManager sensorManager;
    private Sensor accelerometer;
    
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
        bubblePaint.setColor(0x50FFFFFF);
        bubblePaint.setMaskFilter(new BlurMaskFilter(3f, BlurMaskFilter.Blur.NORMAL)); // 模糊边缘
        
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
        
        // 初始化气泡位置
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
        
        // 更新液体渐变（从浅绿到深绿，增强立体深度感）
        liquidPaint.setShader(new LinearGradient(
            0, 0, 0, h,
            new int[]{
                0xFF00E676,  // 顶部：亮绿色（去掉过亮的青绿色）
                0xFF00D966,  // 上中：中亮绿色
                0xFF00C853,  // 中部：标准绿色
                0xFF00A83D,  // 下中：深绿色
                0xFF008A30   // 底部：最深绿色（阴影区）
            },
            new float[]{0f, 0.25f, 0.5f, 0.75f, 1f},
            Shader.TileMode.CLAMP
        ));
        
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
        super.onDraw(canvas);
        
        int width = getWidth();
        int height = getHeight();
        
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
            
            // 2.2 绘制液体底部的深色阴影（增强深度和厚度感）
            Paint bottomShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            bottomShadow.setShader(new LinearGradient(
                0, height - 30, 0, height,
                new int[]{0x00000000, 0x40000000, 0x50000000},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP
            ));
            canvas.drawRect(0, height - 30, width, height, bottomShadow);
            
            // 2.3 绘制液面波浪（动态效果 + 重力倾斜）
            if (fillHeight > 20) {
                float waveY = height - fillHeight;
                wavePath.reset();
                
                // 重力倾斜量（夸张效果）
                float leftTilt = tiltX * 20;  // 左侧倾斜量（4倍增强）
                float rightTilt = -tiltX * 20; // 右侧倾斜量
                
                wavePath.moveTo(0, waveY + leftTilt);
                
                // 创建正弦波浪 + 倾斜效果
                for (int x = 0; x <= width; x += 5) {
                    float wave = (float) Math.sin((x / (float)width * 4 * Math.PI) + waveOffset) * 5f;
                    float tilt = leftTilt + (rightTilt - leftTilt) * (x / (float)width);
                    wavePath.lineTo(x, waveY + wave + tilt);
                }
                wavePath.lineTo(width, height);
                wavePath.lineTo(0, height);
                wavePath.close();
                
                // 绘制波浪液体（稍微透明，显示下层渐变）
                Paint waveLiquidPaint = new Paint(liquidPaint);
                waveLiquidPaint.setAlpha(220);
                canvas.drawPath(wavePath, waveLiquidPaint);
            }
            
            // 2.4 液面光泽已移除（用户要求去掉液体顶部的白色）
            // 不再绘制白色高光，保持纯净的液体颜色
            
            // 2.5 绘制气泡（随机位置，根据填充高度显示）
            if (fillHeight > 30) {
                drawBubbles(canvas, width, height, fillHeight);
            }
            
            // 2.6 绘制液体左侧的明亮边缘（减弱亮度，避免过白）
            Paint leftEdgeShine = new Paint(Paint.ANTI_ALIAS_FLAG);
            leftEdgeShine.setStyle(Paint.Style.FILL);
            leftEdgeShine.setShader(new LinearGradient(
                width * 0.08f, 0, width * 0.22f, 0,
                new int[]{0x00FFFFFF, 0x30FFFFFF, 0x20FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.3f, 0.7f, 1f},
                Shader.TileMode.CLAMP
            ));
            canvas.drawRect(width * 0.08f, height - fillHeight, 
                           width * 0.22f, height, leftEdgeShine);
            
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
        // 绘制左上角的小面积高光反射
        Paint spotlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        spotlightPaint.setStyle(Paint.Style.FILL);
        spotlightPaint.setShader(new android.graphics.RadialGradient(
            width * 0.25f, height * 0.2f, width * 0.3f,
            new int[]{0x50FFFFFF, 0x20FFFFFF, 0x00FFFFFF},
            new float[]{0f, 0.5f, 1f},
            Shader.TileMode.CLAMP
        ));
        canvas.drawPath(highlightPath, spotlightPaint);
        canvas.restore();
        
        // 恢复重力倾斜的变换
        canvas.restore();
    }
    
    /**
     * 绘制液体中的气泡（受重力影响）
     */
    private void drawBubbles(Canvas canvas, int width, int height, float fillHeight) {
        float baseY = height - fillHeight;
        
        // 气泡受重力影响的横向偏移（夸张效果）
        float gravityOffsetX = -tiltX * 15; // 手机向左倾，气泡向右漂
        float gravityOffsetY = tiltY * 5;   // 前后倾斜的轻微影响
        
        // 气泡1（大）
        float bubble1X = width * 0.3f + gravityOffsetX;
        float bubble1Y = baseY + fillHeight * bubblePositions[0] + gravityOffsetY;
        canvas.drawCircle(bubble1X, bubble1Y, 7f, bubblePaint);
        
        // 气泡2（中）
        float bubble2X = width * 0.6f + gravityOffsetX * 0.8f;
        float bubble2Y = baseY + fillHeight * bubblePositions[1] + gravityOffsetY;
        canvas.drawCircle(bubble2X, bubble2Y, 5f, bubblePaint);
        
        // 气泡3（小）
        float bubble3X = width * 0.45f + gravityOffsetX * 0.6f;
        float bubble3Y = baseY + fillHeight * bubblePositions[2] + gravityOffsetY;
        canvas.drawCircle(bubble3X, bubble3Y, 4f, bubblePaint);
        
        // 气泡4（小）- 只在填充超过50%时显示
        if (fillLevel > 0.5f) {
            float bubble4X = width * 0.7f + gravityOffsetX * 0.9f;
            float bubble4Y = baseY + fillHeight * bubblePositions[3] + gravityOffsetY;
            canvas.drawCircle(bubble4X, bubble4Y, 4.5f, bubblePaint);
        }
        
        // 气泡5（大）- 只在填充超过70%时显示
        if (fillLevel > 0.7f) {
            float bubble5X = width * 0.35f + gravityOffsetX * 0.7f;
            float bubble5Y = baseY + fillHeight * bubblePositions[4] + gravityOffsetY;
            canvas.drawCircle(bubble5X, bubble5Y, 6f, bubblePaint);
        }
        
        // 每个气泡添加高光点（模拟透明感和光源）
        Paint bubbleHighlight = new Paint(Paint.ANTI_ALIAS_FLAG);
        bubbleHighlight.setColor(0xB0FFFFFF);
        canvas.drawCircle(bubble1X - 2.5f, bubble1Y - 2.5f, 2.5f, bubbleHighlight);
        canvas.drawCircle(bubble2X - 2f, bubble2Y - 2f, 2f, bubbleHighlight);
        canvas.drawCircle(bubble3X - 1.5f, bubble3Y - 1.5f, 1.5f, bubbleHighlight);
        
        // 气泡缓慢上升动画（模拟浮力）
        for (int i = 0; i < bubblePositions.length; i++) {
            bubblePositions[i] -= 0.002f; // 缓慢向上
            if (bubblePositions[i] < 0) {
                bubblePositions[i] = 1.0f; // 从底部重新出现
            }
        }
    }
    
    /**
     * 启动波浪动画
     */
    private void startWaveAnimation() {
        // 使用ValueAnimator创建循环波浪动画
        android.animation.ValueAnimator waveAnimator = android.animation.ValueAnimator.ofFloat(0f, (float)(2 * Math.PI));
        waveAnimator.setDuration(2000); // 2秒一个周期
        waveAnimator.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        waveAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
        waveAnimator.addUpdateListener(animation -> {
            waveOffset = (float) animation.getAnimatedValue();
            invalidate();
        });
        waveAnimator.start();
    }
    
    /**
     * 设置填充比例
     * @param level 0.0 - 1.0
     */
    public void setFillLevel(float level) {
        this.fillLevel = Math.max(0f, Math.min(1f, level));
        invalidate();
        
        // 如果开始填充，启动波浪动画
        if (level > 0.1f && waveOffset == 0f) {
            startWaveAnimation();
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
        // 注册重力传感器
        if (sensorManager != null && accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
            Log.d("LightningShapeView", "✅ 重力传感器已注册");
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
    }
    
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            // 获取重力加速度（X和Y轴）
            float x = event.values[0]; // 左右倾斜（-10 到 10）
            float y = event.values[1]; // 前后倾斜（-10 到 10）
            
            // 平滑过渡（避免抖动，但保持灵敏）
            float smoothFactor = 0.15f; // 降低以更灵敏
            tiltX = tiltX * (1 - smoothFactor) + x * smoothFactor;
            tiltY = tiltY * (1 - smoothFactor) + y * smoothFactor;
            
            // 限制倾斜范围（扩大到-8到8，更夸张）
            tiltX = Math.max(-8f, Math.min(8f, tiltX));
            tiltY = Math.max(-8f, Math.min(8f, tiltY));
            
            // 重绘
            invalidate();
        }
    }
    
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // 不需要处理精度变化
    }
}

