package top.zainzh.rearscreen;

/** Render policy; the App snapshots the per-package preference before starting a session. */
final class RearCastCompatibility {
    static int density(boolean enabled, int requested, int source) {
        if (requested < 72 || requested > 640 || source < 72 || source > 1280)
            throw new IllegalArgumentException("Invalid casting/source density");
        return enabled ? source : requested;
    }
    static RearCastViewport viewport(RearCastViewport physical, int requested, int renderDensity) {
        if (requested < 72 || requested > 640 || renderDensity < 72 || renderDensity > 1280)
            throw new IllegalArgumentException("Invalid render density");
        // Keep the effective dp layout unchanged; SurfaceView scales this buffer into the physical rectangle.
        return physical.buffered((int) Math.round((double) physical.width() * renderDensity / requested),
                (int) Math.round((double) physical.height() * renderDensity / requested));
    }
}
