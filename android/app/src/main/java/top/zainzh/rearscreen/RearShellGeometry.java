package top.zainzh.rearscreen;

/** Template coordinates, independent from display density and PNG raster dimensions. */
final class RearShellGeometry {
    final float width, height, left, top, right, bottom;
    final int outputWidth, outputHeight;
    RearShellGeometry(double width, double height, double left, double top, double right, double bottom) {
        double[] values = {width, height, left, top, right, bottom};
        for (double value : values) {
            if (!Double.isFinite(value) || value < 0 || value > 8192) throw new IllegalArgumentException("Invalid shell geometry");
        }
        if (width <= 0 || height <= 0 || (width + left + right) * (height + top + bottom) > 24000000)
            throw new IllegalArgumentException("Invalid shell canvas");
        this.width = (float) width; this.height = (float) height;
        this.left = (float) left; this.top = (float) top; this.right = (float) right; this.bottom = (float) bottom;
        outputWidth = (int) Math.round(width + left + right);
        outputHeight = (int) Math.round(height + top + bottom);
    }
    boolean fits(int sourceWidth, int sourceHeight) {
        return sourceWidth > 0 && sourceHeight > 0
                && Math.abs((double) sourceWidth / sourceHeight - (double) width / height) < .01;
    }
}
