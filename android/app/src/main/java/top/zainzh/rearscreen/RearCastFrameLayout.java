package top.zainzh.rearscreen;

import android.content.Context;
import android.graphics.Region;
import android.widget.FrameLayout;

/** Keep a conservative composition lane for the transformed indicator above the SurfaceView. */
final class RearCastFrameLayout extends FrameLayout {
    RearCastFrameLayout(Context context) { super(context); }
    @Override public boolean gatherTransparentRegion(Region region) {
        super.gatherTransparentRegion(region);
        if (region != null) {
            int[] xy = new int[2]; getLocationInWindow(xy);
            int lane = Math.round(64 * getResources().getDisplayMetrics().density);
            // A visibility/RenderNode transform need not recompute the SurfaceView's transparent hint.
            // Reserve the whole animation envelope once; transparent pixels still reveal the video.
            // The session menu can cover the safe image; reserve it before any visibility animation.
            region.op(xy[0], xy[1], xy[0] + getWidth(), xy[1] + getHeight(), Region.Op.DIFFERENCE);
            region.op(xy[0] + Math.max(0, getWidth() - lane), xy[1],
                    xy[0] + getWidth(), xy[1] + getHeight(), Region.Op.DIFFERENCE);
        }
        return false;
    }
}
