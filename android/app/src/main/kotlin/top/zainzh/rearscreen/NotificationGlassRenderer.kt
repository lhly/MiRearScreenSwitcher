package top.zainzh.rearscreen

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi

/** 自有中性反射环境与边缘折射；整块屏幕只有一个光学表面。 */
@RequiresApi(33)
internal class NotificationGlassRenderer : RearNotificationGlassView.Renderer {
    private val shader = RuntimeShader(SOURCE)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = this@NotificationGlassRenderer.shader }

    override fun draw(canvas: Canvas, width: Int, height: Int, density: Float, dark: Boolean,
        background: Int, surface: Int) {
        shader.setFloatUniform("resolution", width.toFloat(), height.toFloat())
        shader.setFloatUniform("density", density)
        shader.setFloatUniform("dark", if (dark) 1f else 0f)
        shader.setColorUniform("background", background)
        shader.setColorUniform("surface", surface)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    companion object {
        private const val SOURCE = """
            uniform float2 resolution;
            uniform float density;
            uniform float dark;
            layout(color) uniform half4 background;
            layout(color) uniform half4 surface;

            float roundedDistance(float2 p, float2 halfSize, float radius) {
                float2 q = abs(p) - halfSize + radius;
                return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
            }

            half3 environment(float2 p) {
                float broad = exp(-pow((p.y + p.x * 0.3 - 0.2) / 0.45, 2.0));
                float reflected = exp(-pow((p.y - 0.11 * sin(p.x * 3.0) - 0.12) / 0.065, 2.0));
                float lower = exp(-pow((p.y + p.x * 0.2 - 1.15) / 0.24, 2.0));
                half3 neutral = mix(background.rgb, surface.rgb,
                    mix(0.42, 0.14, dark) + broad * mix(0.2, 0.15, dark));
                neutral = mix(neutral, half3(1.0),
                    reflected * mix(0.12, 0.022, dark) + lower * mix(0.055, 0.012, dark));
                return neutral;
            }

            half4 main(float2 coord) {
                float2 halfSize = resolution * 0.5 - density * 0.6;
                float radius = min(26.0 * density, min(halfSize.x, halfSize.y) * 0.5);
                float2 p = coord - resolution * 0.5;
                float distance = roundedDistance(p, halfSize, radius);
                if (distance > 1.0) return background;
                float depth = max(0.0, -distance);
                float2 q = abs(p) - halfSize + radius;
                float2 normal = q.x > 0.0 && q.y > 0.0
                    ? normalize(q) * sign(p)
                    : (q.x > q.y ? float2(sign(p.x), 0.0) : float2(0.0, sign(p.y)));
                float edgeZone = exp(-depth / max(1.0, density * 10.0));
                float2 bent = (coord - normal * edgeZone * density * 12.0) / resolution;
                half3 color = environment(bent);
                float light = 0.42 + 0.58 * max(dot(normal, normalize(float2(-0.55, -0.85))), 0.0);
                float rim = exp(-pow((depth - density * 0.4) / max(0.7, density * 0.38), 2.0));
                float inner = exp(-pow((depth - density * 2.0) / max(1.0, density * 1.1), 2.0));
                color *= 1.0 - inner * mix(0.032, 0.08, dark);
                color = mix(color, half3(1.0), rim * light * mix(0.72, 0.17, dark));
                float glow = edgeZone * edgeZone * light * mix(0.035, 0.014, dark);
                color += half3(glow);
                color = mix(color, background.rgb, smoothstep(-0.6, 1.0, distance));
                return half4(clamp(color, 0.0, 1.0), 1.0);
            }
        """
    }
}
