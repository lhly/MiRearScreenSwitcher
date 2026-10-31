package top.zainzh.rearscreen;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;

/** 整块背屏共用的单层玻璃，通知文字不参与折射。材质稳定后无需持续刷新。 */
public final class RearNotificationGlassView extends View {
    interface Renderer {
        void draw(Canvas canvas, int width, int height, float density, boolean dark,
                  int background, int surface);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private Renderer renderer;
    private boolean rendererUnavailable;
    private boolean oled;
    private boolean dark;
    private int background;
    private int surface;
    private long renderedFrames;

    public RearNotificationGlassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setClickable(false);
    }

    public void configure(boolean oledMode) {
        oled = oledMode;
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        background = getContext().getColor(R.color.remrss_background);
        surface = getContext().getColor(R.color.remrss_surface);
        setVisibility(oled ? INVISIBLE : VISIBLE);
        if (!oled && renderer == null && !rendererUnavailable && Build.VERSION.SDK_INT >= 33) {
            try { renderer = new NotificationGlassRenderer(); }
            catch (RuntimeException failure) { useCompatibleMaterial(failure); }
        }
        updateCompatibleMaterial();
        invalidate();
    }

    private void useCompatibleMaterial(RuntimeException failure) {
        renderer = null;
        rendererUnavailable = true;
        android.util.Log.w("RearNotificationGlass", "Using compatible notification material", failure);
    }

    private void updateCompatibleMaterial() {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        int middle = mix(background, surface, dark ? .22f : .5f);
        paint.setShader(new LinearGradient(0, 0, getWidth(), getHeight(),
                new int[]{mix(middle, Color.WHITE, dark ? .025f : .10f), middle,
                        mix(middle, background, .65f)}, new float[]{0f, .48f, 1f}, Shader.TileMode.CLAMP));
        rim.setShader(new LinearGradient(0, 0, getWidth(), getHeight(),
                new int[]{Color.argb(dark ? 64 : 225, 255, 255, 255), Color.TRANSPARENT,
                        Color.argb(dark ? 28 : 100, 255, 255, 255)},
                new float[]{0f, .6f, 1f}, Shader.TileMode.CLAMP));
    }

    private static int mix(int from, int to, float amount) {
        return Color.rgb(Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * amount),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * amount),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount));
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateCompatibleMaterial();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (oled || getWidth() == 0 || getHeight() == 0) return;
        renderedFrames++;
        float density = getResources().getDisplayMetrics().density;
        if (renderer != null && canvas.isHardwareAccelerated()) {
            try {
                renderer.draw(canvas, getWidth(), getHeight(), density, dark, background, surface);
                return;
            } catch (RuntimeException failure) { useCompatibleMaterial(failure); }
        }
        float inset = Math.max(1f, density * .6f);
        bounds.set(inset, inset, getWidth() - inset, getHeight() - inset);
        float radius = Math.min(26f * density, Math.min(bounds.width(), bounds.height()) * .25f);
        canvas.drawRoundRect(bounds, radius, radius, paint);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1f, density * .65f));
        canvas.drawRoundRect(bounds, radius, radius, rim);
    }

    long getRenderedFrameCount() { return renderedFrames; }
}
