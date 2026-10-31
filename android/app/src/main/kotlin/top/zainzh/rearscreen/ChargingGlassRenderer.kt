package top.zainzh.rearscreen

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi

/** One GPU pass: procedural environment, refracted light ribbons, meniscus and absorption. */
@RequiresApi(33)
internal class ChargingGlassRenderer : ChargingLiquidView.GlassRenderer {
    private val shader = RuntimeShader(SOURCE)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = this@ChargingGlassRenderer.shader }

    override fun draw(canvas: Canvas, width: Int, height: Int, fill: Float, seconds: Float,
        tilt: Float, textArea: RectF, color: Int) {
        shader.setFloatUniform("resolution", width.toFloat(), height.toFloat())
        shader.setFloatUniform("level", fill)
        shader.setFloatUniform("clock", seconds)
        shader.setFloatUniform("gravityTilt", tilt)
        shader.setFloatUniform("safeArea", textArea.left, textArea.top, textArea.right, textArea.bottom)
        shader.setColorUniform("chargeColor", color)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    companion object {
        private const val SOURCE = """
            uniform float2 resolution;
            uniform float level;
            uniform float clock;
            uniform float gravityTilt;
            uniform float4 safeArea;
            layout(color) uniform half4 chargeColor;

            float bell(float value, float width) {
                float z = value / width;
                return exp(-z * z);
            }

            // The scene is generated inside the animation, never captured from another window.
            half3 environment(float2 p, float time) {
                float a = p.x * 2.8 + p.y * 1.5 + time * 0.17;
                float b = p.x * 1.5 - p.y * 3.5 - time * 0.13;
                float broad = 0.5 + 0.5 * sin(a) * cos(b);
                half3 green = chargeColor.rgb;
                half3 c = green * (0.13 + broad * 0.32);
                for (int i = 0; i < 4; i++) {
                    float k = float(i);
                    float center = 0.08 + k * 0.31
                        + 0.13 * sin(p.x * (2.35 + k * 0.25) + time * 0.21 + k * 2.0)
                        + 0.045 * sin(p.x * 5.1 - time * 0.16 + k);
                    float distance = p.y - center;
                    float soft = bell(distance, 0.07 + k * 0.007);
                    float ribbon = bell(distance, 0.007 + k * 0.0015);
                    float filament = bell(distance + 0.008, 0.0017);
                    float modulation = 0.55 + 0.45 * sin(p.x * 2.2 + k + time * 0.11);
                    c += green * soft * 0.28;
                    c += mix(green, half3(0.7, 1.0, 0.81), 0.48) * ribbon * 0.35 * modulation;
                    c += half3(0.78, 1.0, 0.86) * filament * 0.34 * modulation;
                }
                // Curved, intersecting optical folds give the volume depth between the broad ribbons.
                float foldA = p.y * 4.5 + 0.95 * sin(p.x * 2.0 + time * 0.16)
                    + 0.3 * sin(p.x * 5.4 - p.y * 1.3 - time * 0.13);
                float foldB = p.y * 5.8 - p.x * 0.8
                    + 0.75 * cos(p.x * 2.8 + p.y * 1.7 - time * 0.12);
                float causticA = pow(0.5 + 0.5 * cos(foldA * 2.2), 16.0);
                float causticB = pow(0.5 + 0.5 * sin(foldB * 2.1), 24.0);
                float filament = 1.0 / (1.0 + abs(sin(foldA * 1.8) * cos(foldB * 1.4)) * 160.0);
                c += green * (causticA * 0.4 + causticB * 0.28);
                c += half3(0.59, 1.0, 0.72) * (causticA * causticA * 0.4
                    + causticB * causticB * 0.25 + filament * 0.16);
                return c;
            }

            half4 main(float2 coord) {
                float width = resolution.x;
                float height = resolution.y;
                float fill = clamp(level, 0.0, 1.0);
                if (fill <= 0.0) return half4(0.0, 0.0, 0.0, 1.0);
                float x = coord.x / width;
                float time = clock;
                float amplitude = min(height * 0.018,
                    min(height * fill * 0.16, height * (1.0 - fill) * 0.22));
                float phaseA = x * 6.283185 * 1.1 + time * 0.85;
                float phaseB = x * 6.283185 * 2.4 - time * 0.6;
                float wave = sin(phaseA) + 0.32 * sin(phaseB) + gravityTilt * (x - 0.5);
                float surface = height * (1.0 - fill) + amplitude * wave;
                float slope = amplitude / width * (6.9115 * cos(phaseA)
                    + 4.8255 * cos(phaseB) + gravityTilt);
                // Full charge has no free surface. Tilt and waves cannot uncover the top pixels.
                bool full = fill >= 1.0;
                if (full) { surface = -2.0; slope = 0.0; }
                float depth = coord.y - surface;
                float coverage = full ? 1.0 : smoothstep(-0.8, 0.8, depth);
                if (coverage <= 0.0) return half4(0.0, 0.0, 0.0, 1.0);

                float2 p = coord / height;
                float u = p.x * 3.6 + p.y * 2.7 + time * 0.27;
                float v = p.x * 2.1 - p.y * 4.3 - time * 0.19;
                float2 opticalGradient = float2(3.6 * cos(u) + 1.05 * cos(v),
                    2.7 * cos(u) - 2.15 * cos(v));
                float2 normal = normalize(float2(-slope, 1.0));
                float nearRim = full ? 0.0 : exp(-max(depth, 0.0) / (height * 0.045));
                // Sample the internal environment at bent coordinates to create moving refraction.
                float2 refracted = p + opticalGradient * 0.018 + normal * nearRim * 0.028;
                half3 c = environment(refracted, time);
                float absorption = 0.8 + 0.2 * exp(-max(depth, 0.0) / height);
                c *= absorption;
                float3 volumeNormal = normalize(float3(opticalGradient * 0.22, 1.0));
                float3 light = normalize(float3(-0.4, -0.55, 1.0));
                float specular = pow(max(dot(reflect(-light, volumeNormal), float3(0.0, 0.0, 1.0)), 0.0), 42.0);
                c += half3(0.7, 1.0, 0.81) * specular * 0.28;
                float rimWidth = max(1.2, height * 0.0033);
                if (!full) {
                    float rim = bell(depth - 1.0, rimWidth);
                    float inner = bell(depth - rimWidth * 3.0, rimWidth * 0.8);
                    c *= 1.0 - 0.24 * bell(depth - rimWidth * 1.8, rimWidth * 0.7);
                    c += half3(0.74, 1.0, 0.84) * (rim * 0.82 + inner * 0.24);
                    float rimLight = 0.45 + 0.55 * pow(0.5 + 0.5 * cos(x * 8.0 - time * 0.25), 3.0);
                    c += half3(0.39, 0.95, 0.58) * nearRim * 0.3 * rimLight;
                    float bevel = max(6.0, height * 0.022);
                    c *= 1.0 - 0.38 * bell(depth - bevel * 0.6, bevel * 0.2);
                    c += half3(0.67, 1.0, 0.78) * bell(depth - bevel * 0.25, bevel * 0.15) * 0.46 * rimLight;
                    c += half3(0.36, 0.84, 0.5) * bell(depth - bevel, bevel * 0.17) * 0.25;
                }

                // Sparse, time-based bubbles; full charge settles into internal light flow only.
                if (!full) {
                    for (int i = 0; i < 4; i++) {
                        float k = float(i);
                        float bx = 0.15 + k * 0.21 + 0.025 * sin(time * 0.22 + k * 3.0);
                        float by = fract(0.19 + k * 0.253 - time * (0.035 + k * 0.003));
                        float bubbleY = surface + max(0.0, height - surface) * by;
                        float radius = height * (0.006 + mod(k, 3.0) * 0.0025);
                        float2 bq = coord - float2(bx * width, bubbleY);
                        float d = length(bq);
                        float ring = bell(d - radius, max(0.7, height * 0.0015));
                        float inside = 1.0 - smoothstep(radius * 0.5, radius, d);
                        float glint = bell(length(bq - float2(-radius * 0.38, -radius * 0.42)), radius * 0.23);
                        float fade = smoothstep(radius * 2.0, radius * 4.0, bubbleY - surface);
                        c *= 1.0 - 0.2 * inside * fade;
                        c += half3(0.61, 0.96, 0.73) * (ring * 0.38 + glint * 0.6) * fade;
                    }
                }

                float edge = min(min(coord.x, width - coord.x), min(coord.y, height - coord.y));
                float edgeLight = exp(-edge / max(1.0, height * 0.0025));
                c += half3(0.37, 0.83, 0.5) * edgeLight * 0.26;
                float2 safeCenter = (safeArea.xy + safeArea.zw) * 0.5;
                float2 textRadius = float2(max(1.0, (safeArea.z - safeArea.x) * 0.48), height * 0.31);
                float2 textDelta = (coord - safeCenter) / textRadius;
                float2 squared = textDelta * textDelta;
                float textShade = exp(-dot(squared, squared) * 0.8);
                c = mix(c, min(c, half3(0.035, 0.19, 0.075)), textShade * 0.95);
                return half4(clamp(c, 0.0, 1.0) * coverage, 1.0);
            }
        """
    }
}
