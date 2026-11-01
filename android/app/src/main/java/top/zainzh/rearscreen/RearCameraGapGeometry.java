package top.zainzh.rearscreen;

/** Physical pixels, independent of the virtual App's rotation and density. */
public final class RearCameraGapGeometry {
    private RearCameraGapGeometry() {}

    public static final class Profile {
        public final String device;
        public final int width, height;
        public final float x, upperY, lowerY, radius, margin;
        public final boolean verified;

        private Profile(String device, int width, int height, float x, float upperY,
                        float lowerY, float radius, boolean verified) {
            this.device = device; this.width = width; this.height = height;
            this.x = x; this.upperY = upperY; this.lowerY = lowerY;
            this.radius = radius; this.margin = 4f; this.verified = verified;
        }

        public float centerY() { return (upperY + lowerY) / 2f; }
        public float left() { return 24f; }
        public float right() { return x + radius - 8f; }

        /** Conservative bounds across the entire bar width, not just its center. */
        public float[] interval(float left, float right) {
            if (!Float.isFinite(left) || !Float.isFinite(right) || left < left() || right > right() || left >= right)
                throw new IllegalArgumentException("Bar outside the camera gap");
            float nearest = Math.max(left, Math.min(right, x));
            double r = radius + margin;
            float arc = (float) Math.sqrt(Math.max(0, r * r - (nearest - x) * (nearest - x)));
            return new float[]{Math.max(upperY + arc, centerY() - 58f),
                    Math.min(lowerY - arc, centerY() + 58f)};
        }

        public boolean matches(RearCastViewport safe) {
            return safe != null && safe.rotation == 0 && safe.physicalWidth == width && safe.physicalHeight == height
                    && safe.top == 0 && safe.right == width && safe.bottom == height
                    && safe.left >= x + radius + margin && safe.left <= x + radius + 64f;
        }
    }

    // Official per-device screenshot shells provide candidate coordinates. The popsicle
    // exclusion radius also covers the larger 266px circle in the official contest template.
    // Candidate profiles must not become production support based on resolution alone.
    public static Profile candidate(String device) {
        // Conservative template contour + 4px clearance, visually confirmed on a 17 Pro Max.
        if ("popsicle".equals(device)) return new Profile(device, 976, 596, 150, 150, 450, 133, true);
        if ("pandora".equals(device)) return new Profile(device, 904, 572, 152, 150, 420, 124, false);
        if ("hongkong".equals(device)) return new Profile(device, 912, 596, 150, 150, 447, 118, false);
        if ("madrid".equals(device)) return new Profile(device, 976, 596, 152, 152, 444, 128, false);
        return null;
    }

    public static Profile supported(String device) {
        Profile p = candidate(device);
        return p != null && p.verified ? p : null;
    }

    /** -1 is unknown; it must never turn into a false 0% reading. */
    public static int percentage(int level, int scale) {
        if (scale <= 0 || level < 0 || level > scale) return -1;
        return (int) Math.round(level * 100.0 / scale);
    }

    public static float segmentFill(float percentage, int index, int count) {
        if (!Float.isFinite(percentage) || percentage < 0 || percentage > 100 || count < 1 || index < 0 || index >= count)
            throw new IllegalArgumentException("Invalid battery segment");
        return Math.max(0f, Math.min(1f, percentage * count / 100f - index));
    }
}
