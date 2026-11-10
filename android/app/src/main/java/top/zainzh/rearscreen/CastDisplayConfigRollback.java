package top.zainzh.rearscreen;

/** Request-scoped display settings snapshot; restores only fields for the same rear display. */
final class CastDisplayConfigRollback {
    interface Port {
        int displayId() throws Exception;
        String displayIdentity() throws Exception;
        Integer dpi() throws Exception;
        Integer rotation(int displayId) throws Exception;
        void setDpi(int value) throws Exception;
        void setRotation(int displayId, int value) throws Exception;
    }

    private final Integer dpi;
    private final Integer rotation;
    private final int displayId;
    private final String identity;

    private CastDisplayConfigRollback(Integer dpi, Integer rotation, int displayId, String identity) {
        this.dpi = dpi;
        this.rotation = rotation;
        this.displayId = displayId;
        this.identity = identity;
    }

    static CastDisplayConfigRollback capture(Port port, boolean captureDpi, boolean captureRotation)
            throws Exception {
        if (!captureDpi && !captureRotation) return new CastDisplayConfigRollback(null, null, -1, null);
        int id = port.displayId();
        String identity = port.displayIdentity();
        if (identity == null || identity.isBlank()) throw new IllegalStateException("Rear display identity unavailable");
        Integer dpi = captureDpi ? port.dpi() : null;
        Integer rotation = captureRotation ? port.rotation(id) : null;
        if (captureDpi && (dpi == null || dpi <= 0)) throw new IllegalStateException("Cannot snapshot current rear DPI");
        if (captureRotation && (rotation == null || rotation < 0 || rotation > 3))
            throw new IllegalStateException("Cannot snapshot current rear rotation");
        return new CastDisplayConfigRollback(dpi, rotation, id, identity);
    }

    void restore(Port port) throws Exception {
        if (dpi == null && rotation == null) return;
        if (port.displayId() != displayId || !identity.equals(port.displayIdentity())) return;
        Exception failure = null;
        if (dpi != null) {
            try { port.setDpi(dpi); }
            catch (Exception error) { failure = error; }
        }
        if (rotation != null) {
            try { port.setRotation(displayId, rotation); }
            catch (Exception error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }
}
