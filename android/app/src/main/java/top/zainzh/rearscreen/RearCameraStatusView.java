package top.zainzh.rearscreen;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.util.Objects;

/** Quiet, non-interactive labels; no animation and no background panel. */
public final class RearCameraStatusView extends View {
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG), icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect bounds = new Rect();
    private final Path path = new Path();
    private final Path textPath = new Path();
    private final PathMeasure measure = new PathMeasure();
    private final float[] position = new float[2], tangent = new float[2];
    private RearCameraStatusGeometry.Arc[] arcs = new RearCameraStatusGeometry.Arc[0];
    private String time = "", date = "", weekday = "", temperature = "";
    private RearWeatherSnapshot.Icon weatherIcon = RearWeatherSnapshot.Icon.UNKNOWN;
    private int originX, originY, draws;
    private RearCameraGapGeometry.Profile profile;

    public RearCameraStatusView(Context context) {
        super(context); setClickable(false); setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        icon.setStyle(Paint.Style.STROKE); icon.setStrokeWidth(1.7f);
        icon.setStrokeCap(Paint.Cap.ROUND); icon.setStrokeJoin(Paint.Join.ROUND);
        applyTheme();
    }
    public void applyTheme() {
        // This layer always sits on the host's black camera strip, even in a light App theme.
        text.setColor(getContext().getColor(R.color.remrss_camera_status_text));
        icon.setColor(text.getColor()); invalidate();
    }
    public void configure(RearCameraGapGeometry.Profile profile, int x, int y) {
        if (this.profile == profile && originX == x && originY == y) return;
        this.profile = profile;
        arcs = RearCameraStatusGeometry.arcs(profile); originX = x; originY = y; invalidate();
    }
    void setData(String time, String date, String weekday, RearWeatherSnapshot weather) {
        String temperature = weather == null ? "" : weather.temperature;
        RearWeatherSnapshot.Icon next = weather == null ? RearWeatherSnapshot.Icon.UNKNOWN : weather.icon;
        if (Objects.equals(this.time, time) && Objects.equals(this.date, date) && Objects.equals(this.weekday, weekday)
                && Objects.equals(this.temperature, temperature) && weatherIcon == next) return;
        this.time = time; this.date = date; this.weekday = weekday; this.temperature = temperature; weatherIcon = next;
        invalidate();
    }
    public int getDrawCount() { return draws; }
    public String getTimeLabel() { return time; }
    public String getWeatherLabel() { return temperature; }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); draws++;
        if (arcs.length != 4) return;
        int save = canvas.save(); canvas.translate(-originX, -originY);
        label(canvas, arcs[0], time, false);
        label(canvas, arcs[1], date, false);
        label(canvas, arcs[2], weekday, false);
        label(canvas, arcs[3], temperature, !temperature.isEmpty() && weatherIcon != RearWeatherSnapshot.Icon.UNKNOWN);
        canvas.restoreToCount(save);
    }
    private void label(Canvas canvas, RearCameraStatusGeometry.Arc arc, String value, boolean showIcon) {
        if (value.isEmpty()) return;
        textPath.reset();
        textPath.addArc(arc.x - arc.radius, arc.y - arc.radius, arc.x + arc.radius, arc.y + arc.radius,
                arc.startAngle(), arc.clockwise ? arc.sweep : -arc.sweep);
        measure.setPath(textPath, false);
        text.setTextSize(arc.textSize);
        float scale = Math.min(1f, (measure.getLength() - 4) / (text.measureText(value) + (showIcon ? 23f : 0)));
        text.setTextSize(arc.textSize * scale); text.getTextBounds(value, 0, value.length(), bounds);
        float iconSize = showIcon ? 17 * scale : 0, gap = showIcon ? 6 * scale : 0;
        float offset = (measure.getLength() - text.measureText(value) - iconSize - gap) / 2;
        canvas.drawTextOnPath(value, textPath, offset + iconSize + gap, 0, text);
        if (showIcon) {
            measure.getPosTan(offset + iconSize / 2, position, tangent);
            canvas.save(); canvas.translate(position[0], position[1]);
            canvas.rotate((float) Math.toDegrees(Math.atan2(tangent[1], tangent[0])));
            canvas.translate(0, (bounds.top + bounds.bottom) / 2f);
            canvas.scale(iconSize / 18, iconSize / 18);
            drawIcon(canvas); canvas.restore();
        }
    }
    private void drawIcon(Canvas c) {
        c.scale(.78f, .78f);
        if (weatherIcon == RearWeatherSnapshot.Icon.CLEAR) {
            c.drawCircle(0, 0, 3.7f, icon);
            for (int i = 0; i < 8; i++) { c.save(); c.rotate(i * 45); c.drawLine(0, -6.4f, 0, -8.2f, icon); c.restore(); }
        } else if (weatherIcon == RearWeatherSnapshot.Icon.HAZE) {
            c.drawLine(-7, -4, 7, -4, icon); c.drawLine(-8, 0, 8, 0, icon); c.drawLine(-5, 4, 5, 4, icon);
        } else {
            path.reset(); path.moveTo(-5, 2); path.cubicTo(-10, 2, -9, -5, -4, -4);
            path.cubicTo(-4, -10, 5, -10, 5, -4); path.cubicTo(10, -5, 11, 2, 5, 2); path.close(); c.drawPath(path, icon);
            if (weatherIcon == RearWeatherSnapshot.Icon.THUNDER) {
                path.reset(); path.moveTo(1, 3); path.lineTo(-2, 6); path.lineTo(2, 6); path.lineTo(-1, 9); c.drawPath(path, icon);
            } else if (weatherIcon == RearWeatherSnapshot.Icon.SNOW) {
                c.drawLine(0, 4, 0, 8, icon); c.drawLine(-2, 5, 2, 7, icon); c.drawLine(-2, 7, 2, 5, icon);
            } else if (weatherIcon == RearWeatherSnapshot.Icon.RAIN || weatherIcon == RearWeatherSnapshot.Icon.SLEET) {
                c.drawLine(-4, 4, -5, 7, icon); c.drawLine(2, 4, 1, 7, icon);
            }
        }
    }
}
