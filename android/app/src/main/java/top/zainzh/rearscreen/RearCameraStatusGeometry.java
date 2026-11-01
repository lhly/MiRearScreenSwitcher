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
        return profile != null && profile.verified && "popsicle".equals(profile.device);
    }
    public static Arc[] arcs(RearCameraGapGeometry.Profile profile) {
        if (!supported(profile)) return new Arc[0];
        // Top glyphs sit outside a clockwise baseline; bottom glyphs sit inside a
        // reversed baseline so they remain upright. The left opening is narrower.
        return new Arc[]{new Arc(150, 150, 148, 314, 26, 30, true),
                new Arc(150, 450, 169, 45, 24, 28, false),
                new Arc(150, 150, 146, 225, 22, 19, true),
                new Arc(150, 450, 163, 137, 18, 23, false)};
    }
}
