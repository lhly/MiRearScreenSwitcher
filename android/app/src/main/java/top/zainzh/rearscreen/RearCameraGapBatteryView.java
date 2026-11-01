package top.zainzh.rearscreen;

import android.content.Context;
import android.graphics.*;
import android.os.Build;
import android.view.View;

/** Static optical material in the physical camera gap; never captures another App. */
public final class RearCameraGapBatteryView extends View {
    private static final int COUNT = 14;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect textBounds = new Rect();
    private final RectF bar = new RectF();
    private final Path clip = new Path();
    private final float[][] segments = new float[COUNT][];
    private final Shader[] fallbackGradients = new Shader[COUNT];
    private RearCameraGapGeometry.Profile profile;
    private int percentage = -1, green, originX, originY, draws;
    private String label = "";
    private float baseline;
    private Glass glass;
    private boolean materialPrepared;

    public RearCameraGapBatteryView(Context context) {
        super(context);
        setClickable(false); setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setTextAlign(Paint.Align.CENTER);
        applyTheme();
    }

    private void prepareMaterial() {
        if (materialPrepared || profile == null || percentage < 0) return;
        materialPrepared = true;
        if (Build.VERSION.SDK_INT >= 33) {
            try { glass = new Glass(); }
            catch (IllegalArgumentException e) { android.util.Log.w("RearCameraGap", "Glass material unavailable", e); }
        }
    }

    public void applyTheme() {
        green = getContext().getColor(R.color.remrss_charging_liquid);
        prepareFallback(); invalidate();
    }
    private void prepareFallback() {
        int[] colors = {Color.rgb(121, 229, 150), green, Color.rgb(14, 75, 35)};
        for (int i = 0; i < COUNT; i++) {
            float[] s = segments[i];
            if (s != null) fallbackGradients[i] = new LinearGradient(s[0], s[1], s[2], s[3], colors, null, Shader.TileMode.CLAMP);
        }
    }

    public void configure(RearCameraGapGeometry.Profile next, int x, int y) {
        if (profile == next && originX == x && originY == y) return;
        profile = next; originX = x; originY = y;
        if (next != null) {
            float pitch = (next.right() - next.left()) / COUNT;
            for (int i = 0; i < COUNT; i++) {
                float left = next.left() + i * pitch + 2.7f, right = next.left() + (i + 1) * pitch - 2.7f;
                float[] interval = next.interval(left, right);
                segments[i] = new float[]{left, interval[0], right, interval[1]};
            }
            // Use actual glyph bounds, with a stable center even when 99% becomes 100%.
            float[] room = next.interval(next.x - 42, next.x + 42);
            float maxInk = Math.max(0, room[1] - room[0] - 4f);
            text.setTextSize(27f);
            text.getTextBounds("100%", 0, 4, textBounds);
            if (textBounds.height() > maxInk) text.setTextSize(27f * maxInk / textBounds.height());
            text.getTextBounds("100%", 0, 4, textBounds);
            baseline = next.centerY() - (textBounds.top + textBounds.bottom) / 2f;
            prepareFallback();
        }
        invalidate();
    }

    public void setPercentage(int value) {
        if (value < -1 || value > 100) value = -1;
        if (percentage == value) return;
        percentage = value; label = value < 0 ? "" : value + "%";
        prepareMaterial();
        invalidate();
    }

    public int getDrawCount() { return draws; }
    public int getPercentage() { return percentage; }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); draws++;
        if (profile == null || percentage < 0) return;
        int saved = canvas.save(); canvas.translate(-originX, -originY);
        for (int i = 0; i < COUNT; i++) {
            float[] s = segments[i]; bar.set(s[0], s[1], s[2], s[3]);
            if (bar.height() < 3) continue;
            float radius = Math.min(bar.width() * .5f, 7f);
            float fill = RearCameraGapGeometry.segmentFill(percentage, i, COUNT);
            if (glass != null && Build.VERSION.SDK_INT >= 33) glass.draw(canvas, bar, radius, fill, green, profile.x, profile.centerY());
            else {
                paint.setShader(null); paint.setStyle(Paint.Style.FILL); paint.setColor(Color.argb(90, 42, 61, 47));
                canvas.drawRoundRect(bar, radius, radius, paint);
                int part = canvas.save(); clip.reset(); clip.addRoundRect(bar, radius, radius, Path.Direction.CW); canvas.clipPath(clip);
                canvas.clipRect(bar.left, bar.top, bar.left + fill * bar.width(), bar.bottom);
                paint.setShader(fallbackGradients[i]);
                canvas.drawRoundRect(bar, radius, radius, paint); canvas.restoreToCount(part);
                paint.setShader(null); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(.7f);
                paint.setColor(Color.argb(105, 193, 249, 208)); canvas.drawRoundRect(bar, radius, radius, paint);
            }
        }
        text.setStyle(Paint.Style.STROKE); text.setStrokeWidth(3f); text.setColor(Color.rgb(6, 20, 10));
        canvas.drawText(label, profile.x, baseline, text);
        text.setStyle(Paint.Style.FILL); text.setColor(Color.rgb(234, 255, 239));
        canvas.drawText(label, profile.x, baseline, text);
        canvas.restoreToCount(saved);
    }

    @androidx.annotation.RequiresApi(33)
    private static final class Glass {
        private final RuntimeShader shader = new RuntimeShader(SOURCE);
        private final Paint material = new Paint(Paint.ANTI_ALIAS_FLAG);
        Glass() { material.setShader(shader); }
        void draw(Canvas canvas, RectF r, float radius, float fill, int color, float centerX, float centerY) {
            shader.setFloatUniform("bounds", r.left, r.top, r.right, r.bottom);
            shader.setFloatUniform("radius", radius); shader.setFloatUniform("fill", fill);
            shader.setFloatUniform("textCenter", centerX, centerY); shader.setColorUniform("tint", color);
            canvas.drawRoundRect(r, radius, radius, material);
        }
        private static final String SOURCE =
                "uniform float4 bounds; uniform float radius; uniform float fill; uniform float2 textCenter; layout(color) uniform half4 tint;" +
                "half4 main(float2 p) {" +
                " float2 size=bounds.zw-bounds.xy; float2 q=p-(bounds.xy+bounds.zw)*0.5;" +
                " float2 d=abs(q)-(size*0.5-radius); float edge=-(length(max(d,0.0))+min(max(d.x,d.y),0.0)-radius);" +
                " float x=(p.x-bounds.x)/size.x; float y=(p.y-bounds.y)/size.y;" +
                " float active=1.0-smoothstep(fill-0.03,fill+0.03,x); if(fill<=0.0)active=0.0; if(fill>=1.0)active=1.0;" +
                " float rim=exp(-max(edge,0.0)*1.8); float inner=exp(-pow((edge-1.8)*1.5,2.0));" +
                " float lens=sqrt(max(0.0,1.0-pow((x-0.5)*2.0,2.0)));" +
                " float light=0.32+0.40*lens+0.14*cos(y*5.0+x*2.0);" +
                " half3 green=tint.rgb*light+half3(0.32,0.65,0.39)*pow(max(0.0,cos(x*3.8+y*4.0)),10.0);" +
                " half3 c=mix(half3(0.045,0.085,0.055),green,active);" +
                " c*=1.0-inner*0.37; c+=half3(0.66,0.95,0.73)*rim*(0.32+0.35*active)*(0.4+0.6*(1.0-x));" +
                " c+=half3(0.24,0.59,0.34)*inner*0.16*active;" +
                " float2 t=(p-textCenter)/float2(47.0,15.0); c*=1.0-0.46*exp(-dot(t,t)*1.5);" +
                " return half4(c,1.0); }";
    }
}
