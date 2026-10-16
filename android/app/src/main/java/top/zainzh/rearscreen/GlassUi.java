package top.zainzh.rearscreen;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Native, resolution-independent soft glass. No image assets, live blur or rendering engine. */
final class GlassUi {
    final Context context;
    final boolean dark;
    final int text, secondary, accent, background, surface, divider, success;

    GlassUi(Context context) {
        this.context = context;
        dark = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        text = Color.parseColor(dark ? "#F1F5FF" : "#172238");
        secondary = Color.parseColor(dark ? "#B0BCD0" : "#526078");
        accent = Color.parseColor(dark ? "#9BBFFF" : "#245DE3");
        background = Color.parseColor(dark ? "#0C1322" : "#EEF2FA");
        surface = Color.parseColor(dark ? "#202C42" : "#FFFFFF");
        divider = Color.parseColor(dark ? "#34425A" : "#D5DEED");
        success = Color.parseColor(dark ? "#80E1BA" : "#087A55");
    }

    int dp(float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    LinearLayout column() {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setBackgroundColor(Color.TRANSPARENT);
        return view;
    }

    LinearLayout row() {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setBackgroundColor(Color.TRANSPARENT);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    TextView text(String value, float sp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setFontFeatureSettings("kern");
        view.setIncludeFontPadding(false);
        view.setLineSpacing(dp(3), 1f);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    TextView heading(String value) {
        TextView view = text(value, 13, secondary, true);
        view.setPadding(dp(4), dp(26), dp(4), dp(12));
        androidx.core.view.ViewCompat.setAccessibilityHeading(view, true);
        return view;
    }

    LinearLayout card() {
        LinearLayout card = column();
        card.setPadding(dp(16), dp(4), dp(16), dp(4));
        card.setBackground(glass(28, false));
        card.setElevation(dp(2));
        card.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        return card;
    }

    Drawable glass(float radius, boolean opaque) {
        return new GlassDrawable(radius, opaque);
    }

    Drawable pill(int color, float radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    void styleSwitch(android.widget.Switch control) {
        GradientDrawable track = new GradientDrawable();
        track.setColor(Color.WHITE);
        track.setCornerRadius(dp(14));
        track.setSize(dp(42), dp(24));
        control.setTrackDrawable(track);
        control.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{accent, dark ? 0xFF59657B : 0xFF7B879D}));
        control.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
    }

    Drawable ripple(Drawable content, float radius) {
        return new RippleDrawable(ColorStateList.valueOf(dark ? 0x33FFFFFF : 0x18245DE3),
                content, pill(Color.WHITE, radius));
    }

    void clickable(View view, Runnable action, float radius) {
        view.setBackground(ripple(view.getBackground(), radius));
        view.setFocusable(true);
        view.setOnClickListener(v -> action.run());
    }

    Button button(String label, boolean primary, Runnable action) {
        Button button = new Button(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(primary ? (dark ? background : Color.WHITE) : accent);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(16), dp(10), dp(16), dp(10));
        button.setBackground(ripple(pill(primary ? accent : (dark ? 0x33245DE3 : 0x0D245DE3), 18), 18));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    void divider(LinearLayout parent) {
        View line = new View(context);
        line.setBackgroundColor(divider);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(0.5f));
        params.leftMargin = dp(48);
        parent.addView(line, params);
    }

    Icon icon(String name, int color) { return new Icon(name, color); }

    final class Background extends FrameLayout {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Shader[] lighting = new Shader[3];
        Background() { super(context); setWillNotDraw(false); }
        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            float radius = Math.max(w, dp(320));
            lighting[0] = new RadialGradient(w * .92f, h * .1f, radius * .85f,
                    dark ? 0x663C5793 : 0x77A6C9FF, Color.TRANSPARENT, Shader.TileMode.CLAMP);
            lighting[1] = new RadialGradient(w * .03f, h * .36f, radius * .72f,
                    dark ? 0x443E3978 : 0x55CBBEF4, Color.TRANSPARENT, Shader.TileMode.CLAMP);
            lighting[2] = new RadialGradient(w * .85f, h * .85f, radius * .9f,
                    dark ? 0x33256568 : 0x448FE0DD, Color.TRANSPARENT, Shader.TileMode.CLAMP);
        }
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(background);
            for (Shader shader : lighting) {
                paint.setShader(shader);
                canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            }
            paint.setShader(null);
        }
    }

    private final class GlassDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float radius;
        private final boolean opaque;
        private Shader fill, rim;
        GlassDrawable(float radius, boolean opaque) { this.radius = dp(radius); this.opaque = opaque; }
        @Override protected void onBoundsChange(android.graphics.Rect bounds) {
            rect.set(bounds); rect.inset(dp(.75f), dp(.75f));
            fill = new LinearGradient(0, rect.top, rect.width() * .7f, rect.bottom,
                    dark ? new int[]{opaque ? 0xFF28354D : 0xEB28354D, opaque ? 0xFF182336 : 0xE0182336}
                         : new int[]{opaque ? 0xFFFFFFFF : 0xEFFFFFFF, opaque ? 0xFFF6F9FF : 0xCFF6F9FF},
                    null, Shader.TileMode.CLAMP);
            rim = new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                    dark ? new int[]{0x668999C0, 0x14334455, 0x40404D6B}
                         : new int[]{Color.WHITE, 0xAADBE3F3, 0xDDFFFFFF}, null, Shader.TileMode.CLAMP);
        }
        @Override public void draw(Canvas canvas) {
            paint.setStyle(Paint.Style.FILL); paint.setShader(fill);
            canvas.drawRoundRect(rect, radius, radius, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1)); paint.setShader(rim);
            canvas.drawRoundRect(rect, radius, radius, paint);
            paint.setShader(null);
        }
        @Override public void getOutline(android.graphics.Outline outline) {
            outline.setRoundRect(getBounds(), radius);
            // A translucent panel must not occlude the background behind its outline.
            outline.setAlpha(opaque ? 1f : .15f);
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    final class Icon extends View {
        private final String name;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        Icon(String name, int color) {
            super(context); this.name = name;
            paint.setColor(color); paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.7f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        @Override protected void onDraw(Canvas canvas) {
            canvas.save();
            float scale = Math.min(getWidth(), getHeight()) / 24f;
            canvas.translate((getWidth() - scale * 24) / 2, (getHeight() - scale * 24) / 2);
            canvas.scale(scale, scale); path.reset();
            switch (name) {
                case "screen":
                case "density":
                    canvas.drawRoundRect(5, 2, 19, 22, 4, 4, paint);
                    canvas.drawLine(10, 18, 14, 18, paint); break;
                case "bell":
                    path.moveTo(5, 17); path.lineTo(7, 14); path.lineTo(7, 9);
                    path.cubicTo(7, 2, 17, 2, 17, 9); path.lineTo(17, 14); path.lineTo(19, 17); path.close();
                    canvas.drawPath(path, paint); canvas.drawArc(10, 18, 14, 22, 0, 180, false, paint); break;
                case "info":
                    canvas.drawCircle(12, 12, 9, paint); canvas.drawLine(12, 11, 12, 17, paint);
                    canvas.drawPoint(12, 7, paint); break;
                case "shield":
                    path.moveTo(12, 2); path.lineTo(20, 6); path.lineTo(19, 14);
                    path.quadTo(17, 20, 12, 22); path.quadTo(7, 20, 5, 14); path.lineTo(4, 6); path.close();
                    canvas.drawPath(path, paint); canvas.drawLine(8, 12, 11, 15, paint);
                    canvas.drawLine(11, 15, 16, 9, paint); break;
                case "eye":
                    path.moveTo(2, 12); path.cubicTo(7, 3, 17, 3, 22, 12);
                    path.cubicTo(17, 21, 7, 21, 2, 12); canvas.drawPath(path, paint);
                    canvas.drawCircle(12, 12, 3, paint); break;
                case "sun":
                    canvas.drawCircle(12, 12, 4, paint);
                    for (int i = 0; i < 8; i++) {
                        double a = i * Math.PI / 4;
                        canvas.drawLine(12 + (float)Math.cos(a) * 7, 12 + (float)Math.sin(a) * 7,
                                12 + (float)Math.cos(a) * 10, 12 + (float)Math.sin(a) * 10, paint);
                    } break;
                case "charge":
                    path.moveTo(14, 2); path.lineTo(5, 14); path.lineTo(11, 14);
                    path.lineTo(10, 22); path.lineTo(19, 10); path.lineTo(13, 10); path.close();
                    canvas.drawPath(path, paint); break;
                case "rotate":
                    canvas.drawArc(4, 4, 20, 20, 20, 290, false, paint);
                    path.moveTo(18, 2); path.lineTo(18, 8); path.lineTo(12, 8); canvas.drawPath(path, paint); break;
                case "search":
                    canvas.drawCircle(10, 10, 6, paint); canvas.drawLine(15, 15, 21, 21, paint); break;
                case "back":
                    path.moveTo(15, 5); path.lineTo(8, 12); path.lineTo(15, 19); canvas.drawPath(path, paint); break;
                case "chevron":
                    path.moveTo(9, 6); path.lineTo(15, 12); path.lineTo(9, 18); canvas.drawPath(path, paint); break;
                case "check":
                    path.moveTo(5, 12); path.lineTo(10, 17); path.lineTo(20, 6); canvas.drawPath(path, paint); break;
                case "apps":
                    for (int x = 4; x <= 14; x += 10) for (int y = 4; y <= 14; y += 10)
                        canvas.drawRoundRect(x, y, x + 6, y + 6, 1.5f, 1.5f, paint);
                    break;
                default:
                    canvas.drawRoundRect(4, 4, 20, 20, 5, 5, paint);
                    canvas.drawLine(8, 12, 16, 12, paint); canvas.drawLine(12, 8, 12, 16, paint);
            }
            canvas.restore();
        }
    }

}
