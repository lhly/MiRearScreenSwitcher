package top.zainzh.rearscreen;

/** Physical pixels, independent of the virtual App's rotation and density. */
public final class RearCameraGapGeometry {
    private RearCameraGapGeometry() {}

    public static final class Profile {
        public final String device;
        public final int width, height;
        public final float x, upperY, lowerY, radius, margin;
        /** Physical-device validation only; template-based support does not imply this is true. */
        public final boolean verified;

        private Profile(String device, int width, int height, float x, float upperY,
                        float lowerY, float radius, boolean verified) {
            this.device = device; this.width = width; this.height = height;
            this.x = x; this.upperY = upperY; this.lowerY = lowerY;
            this.radius = radius; this.margin = 4f; this.verified = verified;
        }

        public float centerY() { return (upperY + lowerY) / 2f; }
        // Keep the sampling grid centered on the lens axis. Unequal distances to
        // its two ends produce unequal heights even with a symmetric circle.
        private float halfWidth() { return Math.min(x - 24f, radius - 8f); }
        public float left() { return x - halfWidth(); }
        public float right() { return x + halfWidth(); }

        /** Conservative bounds across the entire bar width, not just its center. */
        public float[] interval(float left, float right) {
            if (!Float.isFinite(left) || !Float.isFinite(right) || left < left() || right > right() || left >= right)
                throw new IllegalArgumentException("Bar outside the camera gap");
            float nearest = Math.max(left, Math.min(right, x));
            double r = radius + margin;
            float arc = (float) Math.sqrt(Math.max(0, r * r - (nearest - x) * (nearest - x)));
            // A fixed height cap creates flat shelves on wider gaps. Follow the
            // complete circle envelope so every bar changes height with the lenses.
            return new float[]{upperY + arc, lowerY - arc};
        }

        public boolean matches(RearCastViewport safe) {
            return safe != null && safe.rotation == 0 && safe.physicalWidth == width && safe.physicalHeight == height
                    && safe.top == 0 && safe.right == width && safe.bottom == height
                    && safe.left >= x + radius + margin && safe.left <= x + radius + 64f;
        }
    }

    // Official per-device screenshot shells provide candidate coordinates. The popsicle
    // exclusion radius also covers the larger 266px circle in the official contest template.
    // Select only by device identity. Equal resolutions do not imply equal lens geometry.
    public static Profile candidate(String device) {
        // Conservative template contour + 4px clearance, visually confirmed on a 17 Pro Max.
        if ("popsicle".equals(device)) return new Profile(device, 976, 596, 150, 150, 450, 133, true);
        if ("pandora".equals(device)) return new Profile(device, 904, 572, 152, 150, 420, 124, false);
        if ("hongkong".equals(device)) return new Profile(device, 912, 596, 150, 150, 447, 118, false);
        if ("madrid".equals(device)) return new Profile(device, 976, 596, 152, 152, 444, 128, false);
        return null;
    }

    public static Profile supported(String device) {
        // All four official templates are supported; verified is retained as UI metadata.
        return candidate(device);
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
