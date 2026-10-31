/*
 * Original charging view: AntiOblivionis.
 * Dynamic glass rendering and lifecycle updates: ReMRSS contributors, 2026.
 */
package top.zainzh.rearscreen;

import android.animation.ValueAnimator;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.Display;
import android.view.Surface;
import android.view.View;

/** Full-screen charging glass. Text is rendered by a separate, cutout-safe foreground. */
public class ChargingLiquidView extends View implements SensorEventListener {
    interface GlassRenderer {
        void draw(Canvas canvas, int width, int height, float fill, float seconds, float tilt,
                  RectF textArea, int color);
    }

    private static final float AMBIENT_AFTER_SECONDS = 8f;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Rect cutout = new Rect();
    private final RectF textArea = new RectF();
    private final Path liquidPath = new Path();
    private final Path lightPath = new Path();
    private final Paint liquidPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private SensorManager sensors;
    private Sensor gravity;
    private DisplayManager displays;
    private GlassRenderer glass;
    private int chargingColor;
    private int gradientHeight;
    private float fillLevel;
    private float seconds;
    private float visibleSeconds;
    private float tilt;
    private float durationScale = 1f;
    private long lastTick;
    private long lastDrawTick;
    private boolean attached;
    private boolean active = true;
    private boolean alwaysOn;
    private boolean framePending;
    private boolean sensorRegistered;
    private long renderedFrames;

    private final ContentObserver motionObserver = new ContentObserver(handler) {
        @Override public void onChange(boolean selfChange) { readMotionScale(); updateRunning(); invalidate(); }
    };
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) { updateRunning(); }
        @Override public void onDisplayRemoved(int id) { updateRunning(); }
        @Override public void onDisplayChanged(int id) {
            if (getDisplay() != null && getDisplay().getDisplayId() == id) updateRunning();
        }
    };
    private final Choreographer.FrameCallback frameCallback = this::tick;

    public ChargingLiquidView(Context context) { this(context, null); }
    public ChargingLiquidView(Context context, AttributeSet attrs) {
        super(context, attrs);
        chargingColor = context.getColor(R.color.remrss_charging_liquid);
        sensors = context.getSystemService(SensorManager.class);
        displays = context.getSystemService(DisplayManager.class);
        if (sensors != null) {
            gravity = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY);
            if (gravity == null) gravity = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            try { glass = new ChargingGlassRenderer(); }
            catch (RuntimeException failure) { android.util.Log.w("ChargingLiquidView", "Glass material unavailable; using compatible renderer", failure); }
        }
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        readMotionScale();
    }

    public void setFillLevel(float level) {
        fillLevel = Float.isNaN(level) ? 0f : Math.max(0f, Math.min(1f, level));
        updateRunning();
        if (!framePending && canDraw()) invalidate();
    }
    public float getFillLevel() { return fillLevel; }

    public void restartMotion() {
        seconds = 0f;
        visibleSeconds = 0f;
        lastTick = lastDrawTick = 0;
        tilt = 0f;
        updateRunning();
        invalidate();
    }
    public void setAlwaysOn(boolean value) { alwaysOn = value; updateRunning(); }
    public void setAnimationActive(boolean value) { active = value; updateRunning(); if (value && canDraw()) invalidate(); }

    public void setTextInsets(int left, int top, int right, int bottom) {
        cutout.set(Math.max(0, left), Math.max(0, top), Math.max(0, right), Math.max(0, bottom));
        updateTextArea();
        invalidate();
    }
    private void updateTextArea() {
        int w = getWidth(), h = getHeight();
        if (cutout.left + cutout.right < w && cutout.top + cutout.bottom < h)
            textArea.set(cutout.left, cutout.top, w - cutout.right, h - cutout.bottom);
        else textArea.set(0, 0, w, h);
        if (w > 0 && h > 0) {
            android.graphics.RadialGradient shade = new android.graphics.RadialGradient(0f, 0f, 1f,
                    new int[]{0xD9000000, 0xD9000000, Color.TRANSPARENT},
                    new float[]{0f, .65f, 1f}, Shader.TileMode.CLAMP);
            android.graphics.Matrix transform = new android.graphics.Matrix();
            transform.setScale(Math.max(1f, textArea.width() * .7f), Math.max(1f, textArea.height() * .58f));
            transform.postTranslate(textArea.centerX(), textArea.centerY());
            shade.setLocalMatrix(transform);
            shadePaint.setShader(shade);
        }
    }

    private void readMotionScale() {
        durationScale = Settings.Global.getFloat(getContext().getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        if (!Float.isFinite(durationScale) || durationScale < 0f) durationScale = 1f;
    }
    private boolean canDraw() {
        Display display = getDisplay();
        int state = display == null ? Display.STATE_UNKNOWN : display.getState();
        return attached && active && isShown() && getWindowVisibility() == VISIBLE && state != Display.STATE_OFF
                && state != Display.STATE_DOZE && state != Display.STATE_DOZE_SUSPEND;
    }
    private boolean canAnimate() { return canDraw() && fillLevel > 0f && durationScale > 0f; }
    private void updateRunning() {
        // View visibility callbacks can arrive before the subclass fields are initialized.
        if (handler == null) return;
        boolean running = canAnimate();
        if (running && !framePending) {
            lastTick = lastDrawTick = 0;
            framePending = true;
            Choreographer.getInstance().postFrameCallback(frameCallback);
        } else if (!running && framePending) {
            Choreographer.getInstance().removeFrameCallback(frameCallback);
            framePending = false;
            lastTick = lastDrawTick = 0;
        }
        boolean needsSensor = running && sensors != null && gravity != null;
        if (needsSensor && !sensorRegistered)
            sensorRegistered = sensors.registerListener(this, gravity, SensorManager.SENSOR_DELAY_UI);
        else if (!needsSensor && sensorRegistered) {
            sensors.unregisterListener(this);
            sensorRegistered = false;
        }
    }
    private void tick(long frameTime) {
        if (!canAnimate()) { framePending = false; updateRunning(); return; }
        if (lastTick != 0) {
            float delta = Math.min(.1f, (frameTime - lastTick) / 1_000_000_000f);
            visibleSeconds += delta;
            float rate = alwaysOn && visibleSeconds >= AMBIENT_AFTER_SECONDS ? .35f : 1f;
            seconds += delta * rate / Math.max(.1f, durationScale);
        }
        lastTick = frameTime;
        int fps = alwaysOn && visibleSeconds >= AMBIENT_AFTER_SECONDS ? 30 : 60;
        if (lastDrawTick == 0 || frameTime - lastDrawTick >= 1_000_000_000L / fps - 500_000L) {
            lastDrawTick = frameTime;
            invalidate();
        }
        Choreographer.getInstance().postFrameCallbackDelayed(frameCallback, fps == 30 ? 24 : 0);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        renderedFrames++;
        if (getWidth() == 0 || getHeight() == 0 || fillLevel <= 0f) {
            canvas.drawColor(Color.BLACK);
            return;
        }
        if (glass != null && canvas.isHardwareAccelerated())
        {
            try { glass.draw(canvas, getWidth(), getHeight(), fillLevel, seconds, tilt, textArea, chargingColor); }
            catch (RuntimeException failure) {
                android.util.Log.w("ChargingLiquidView", "Glass draw failed; using compatible renderer", failure);
                glass = null;
                drawCompatibleGlass(canvas);
            }
        } else drawCompatibleGlass(canvas);
    }

    /** The compatibility renderer shares the full-charge and time-based wave rules. */
    private void drawCompatibleGlass(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        canvas.drawColor(Color.BLACK);
        float amplitude = waveAmplitude(h, fillLevel);
        liquidPath.reset();
        liquidPath.moveTo(0, surfaceAt(0, w, h, amplitude));
        for (int x = 0; x <= w + 8; x += 8) liquidPath.lineTo(Math.min(x, w), surfaceAt(Math.min(x, w), w, h, amplitude));
        liquidPath.lineTo(w, h); liquidPath.lineTo(0, h); liquidPath.close();
        if (gradientHeight != h) {
            liquidPaint.setShader(new LinearGradient(0, 0, w, h,
                    new int[]{Color.rgb(14, 111, 46), chargingColor, Color.rgb(3, 57, 24)},
                    new float[]{0f, .55f, 1f}, Shader.TileMode.CLAMP));
            gradientHeight = h;
        }
        canvas.save();
        canvas.clipPath(liquidPath);
        canvas.drawRect(0, 0, w, h, liquidPaint);
        lightPaint.setStyle(Paint.Style.STROKE);
        lightPaint.setStrokeCap(Paint.Cap.ROUND);
        for (int band = 0; band < 4; band++) {
            lightPath.reset();
            for (int x = 0; x <= w + 8; x += 8) {
                float y = h * (.16f + band * .24f) + (float)Math.sin(x / (float)w * 4f + seconds * .22f + band) * h * .12f;
                if (x == 0) lightPath.moveTo(x, y); else lightPath.lineTo(x, y);
            }
            lightPaint.setColor(Color.argb(28, 151, 255, 184));
            lightPaint.setStrokeWidth(h * .035f);
            canvas.drawPath(lightPath, lightPaint);
            lightPaint.setColor(Color.argb(105, 184, 255, 210));
            lightPaint.setStrokeWidth(Math.max(1f, h * .002f));
            canvas.drawPath(lightPath, lightPaint);
        }
        canvas.drawRect(0, 0, w, h, shadePaint);
        lightPaint.setColor(Color.argb(210, 209, 255, 222));
        lightPaint.setStrokeWidth(Math.max(1f, h * .004f));
        lightPath.reset();
        for (int x = 0; x <= w + 8; x += 8) {
            float y = Math.max(0f, surfaceAt(Math.min(x, w), w, h, amplitude));
            if (x == 0) lightPath.moveTo(x, y); else lightPath.lineTo(x, y);
        }
        canvas.drawPath(lightPath, lightPaint);
        canvas.restore();
    }
    private float surfaceAt(float x, int w, int h, float amplitude) {
        if (fillLevel >= 1f) return -1f;
        float nx = x / Math.max(1f, w);
        return h * (1f - fillLevel) + amplitude * ((float)Math.sin(nx * 6.283185f * 1.1f + seconds * .85f)
                + .32f * (float)Math.sin(nx * 6.283185f * 2.4f - seconds * .6f)
                + tilt * (nx - .5f));
    }
    private static float waveAmplitude(int height, float fill) {
        return Math.min(height * .018f, Math.min(height * fill * .16f, height * (1f - fill) * .22f));
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        gradientHeight = 0;
        updateTextArea();
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        readMotionScale();
        getContext().getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, motionObserver);
        if (displays != null) displays.registerDisplayListener(displayListener, handler);
        updateRunning();
    }
    @Override protected void onDetachedFromWindow() {
        attached = false;
        updateRunning();
        getContext().getContentResolver().unregisterContentObserver(motionObserver);
        if (displays != null) displays.unregisterDisplayListener(displayListener);
        super.onDetachedFromWindow();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); updateRunning(); }
    @Override protected void onVisibilityChanged(View view, int visibility) { super.onVisibilityChanged(view, visibility); updateRunning(); }
    @Override public void onSensorChanged(SensorEvent event) {
        float x = event.values[0], y = event.values[1];
        int rotation = getDisplay() == null ? Surface.ROTATION_0 : getDisplay().getRotation();
        float horizontal = rotation == Surface.ROTATION_90 ? -y : rotation == Surface.ROTATION_180 ? -x
                : rotation == Surface.ROTATION_270 ? y : x;
        float target = Math.max(-1f, Math.min(1f, horizontal / 6f));
        tilt += (target - tilt) * .12f;
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    // Diagnostics for lifecycle/frame pacing acceptance; no synthetic FPS estimates.
    long getRenderedFrameCount() { return renderedFrames; }
    boolean isFrameLoopRunning() { return framePending; }
    boolean isAmbientMotion() { return alwaysOn && visibleSeconds >= AMBIENT_AFTER_SECONDS; }
}
