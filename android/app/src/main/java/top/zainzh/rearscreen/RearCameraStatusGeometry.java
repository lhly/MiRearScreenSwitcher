package top.zainzh.rearscreen;

/** Physical-pixel text baselines following the lens arcs, independent of App rotation. */
public final class RearCameraStatusGeometry {
    private RearCameraStatusGeometry() {}
    public static final float FILM_CLEARANCE = 7f;
    public static final class Arc {
        public final float x, y, radius, angle, sweep, textSize;
        public final boolean clockwise;
        Arc(float x, float y, float radius, float angle, float sweep, float textSize, boolean clockwise) {
            this.x = x; this.y = y; this.radius = radius; this.angle = angle;
            this.sweep = sweep; this.textSize = textSize; this.clockwise = clockwise;
        }
        public float length() { return (float) Math.toRadians(sweep) * radius; }
        public float startAngle() { return angle + (clockwise ? -sweep / 2 : sweep / 2); }
    }
    public static boolean supported(RearCameraGapGeometry.Profile profile) {
        return profile != null;
    }
    public static Arc[] arcs(RearCameraGapGeometry.Profile profile) {
        if (!supported(profile)) return new Arc[0];
        // Top glyphs sit outside a clockwise baseline; bottom glyphs sit inside a
        // reversed baseline so they remain upright. Derive each baseline from that
        // device's official lens centers/radius, with the same extra film clearance
        // as the visually verified popsicle layout. Its upper-left opening needs
        // one less pixel of nominal font size to clear the official outer corner.
        float weekdaySize = "popsicle".equals(profile.device) ? 18 : 19;
        float weekdaySweep = "popsicle".equals(profile.device) ? 20 : 22;
        return new Arc[]{new Arc(profile.x, profile.upperY, profile.radius + 15, 314, 26, 30, true),
                new Arc(profile.x, profile.lowerY, profile.radius + 36, 45, 24, 28, false),
                new Arc(profile.x, profile.upperY, profile.radius + 13, 225, weekdaySweep, weekdaySize, true),
                new Arc(profile.x, profile.lowerY, profile.radius + 30, 137, 18, 23, false)};
    }
}
