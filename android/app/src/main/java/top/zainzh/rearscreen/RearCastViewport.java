package top.zainzh.rearscreen;

/** Immutable physical rectangle and its render buffer, shared by output and input. */
public final class RearCastViewport {
    public final int physicalWidth, physicalHeight, rotation, density, left, top, right, bottom;
    public final int bufferWidth, bufferHeight;

    public RearCastViewport(int width, int height, int rotation, int density,
                            int left, int top, int right, int bottom) {
        this(width, height, rotation, density, left, top, right, bottom, right - left, bottom - top);
    }
    private RearCastViewport(int width, int height, int rotation, int density,
                            int left, int top, int right, int bottom, int bufferWidth, int bufferHeight) {
        if (width < 2 || height < 2 || rotation != 0 || density < 1 || left < 0 || top < 0
                || right > width || bottom > height || right - left < 2 || bottom - top < 2)
            throw new IllegalArgumentException("Invalid rear viewport; physical display rotation must be 0");
        if (bufferWidth < 2 || bufferHeight < 2 || bufferWidth > 8192 || bufferHeight > 8192
                || (long) bufferWidth * bufferHeight > 32_000_000L)
            throw new IllegalArgumentException("Rear render buffer too large; increase casting DPI");
        physicalWidth = width; physicalHeight = height; this.rotation = rotation; this.density = density;
        this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        this.bufferWidth = bufferWidth; this.bufferHeight = bufferHeight;
    }
    public static RearCastViewport safe(int width, int height, int rotation, int density,
                                         int left, int top, int right, int bottom, int[] radii) {
        if (radii == null || radii.length != 4 || left < 0 || top < 0 || right < 0 || bottom < 0)
            throw new IllegalArgumentException("Invalid rear cutout/corners");
        int[] margins = new int[4];
        for (int i = 0; i < 4; i++) {
            if (radii[i] < 0) throw new IllegalArgumentException("Invalid corner radius");
            margins[i] = radii[i] == 0 ? 0 : (int) Math.ceil(radii[i] * (1 - 1 / Math.sqrt(2))) + 2;
        }
        return new RearCastViewport(width, height, rotation, density,
                Math.max(left, Math.max(margins[0], margins[3])),
                Math.max(top, Math.max(margins[0], margins[1])),
                width - Math.max(right, Math.max(margins[1], margins[2])),
                height - Math.max(bottom, Math.max(margins[2], margins[3])));
    }
    public static void validateContentRotation(int rotation) {
        if (rotation < 0 || rotation > 3) throw new IllegalArgumentException("Invalid cast content rotation");
    }
    public int contentWidth(int rotation) { validateContentRotation(rotation); return (rotation & 1) == 0 ? bufferWidth : bufferHeight; }
    public int contentHeight(int rotation) { validateContentRotation(rotation); return (rotation & 1) == 0 ? bufferHeight : bufferWidth; }
    /** Physical output coordinates -> logical pixels of the rendered, rotated virtual display. */
    public float[] contentPoint(float x, float y, int rotation) {
        validateContentRotation(rotation);
        x = Math.max(0, Math.min(bufferWidth - 1, (x - left) * bufferWidth / width()));
        y = Math.max(0, Math.min(bufferHeight - 1, (y - top) * bufferHeight / height()));
        switch (rotation) {
            case 1: return new float[]{y, bufferWidth - 1 - x};
            case 2: return new float[]{bufferWidth - 1 - x, bufferHeight - 1 - y};
            case 3: return new float[]{bufferHeight - 1 - y, x};
            default: return new float[]{x, y};
        }
    }
    public int width() { return right - left; }
    public int height() { return bottom - top; }
    public RearCastViewport buffered(int width, int height) {
        return new RearCastViewport(physicalWidth, physicalHeight, rotation, density, left, top, right, bottom, width, height);
    }
    public String renderKey() { return key() + ":" + bufferWidth + "," + bufferHeight; }
    public RearCastViewport full() {
        return new RearCastViewport(physicalWidth, physicalHeight, rotation, density, 0, 0, physicalWidth, physicalHeight);
    }
    public boolean isFull() { return left == 0 && top == 0 && right == physicalWidth && bottom == physicalHeight; }
    public boolean contains(float x, float y) {
        return x >= left && x < right && y >= top && y < bottom;
    }
    public String key() {
        return physicalWidth + "," + physicalHeight + "," + rotation + "," + density + ":"
                + left + "," + top + "," + right + "," + bottom;
    }
}
