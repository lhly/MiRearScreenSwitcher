package top.zainzh.rearscreen;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;
import android.widget.Toast;
import java.util.concurrent.atomic.AtomicBoolean;

/** The foreground URI worker owns capture/export even after Quick Settings closes. */
public class RearScreenshotTileService extends TileService {
    private long lastClickAt = -500L;
    @Override public void onStartListening() {
        super.onStartListening();
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(Tile.STATE_INACTIVE);
            if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle(null);
            if (Build.VERSION.SDK_INT >= 30) tile.setStateDescription("");
            tile.updateTile();
        }
    }
    @Override public void onClick() {
        super.onClick();
        long now = SystemClock.elapsedRealtime();
        // Multi-display SystemUI can dispatch the same tap twice a few milliseconds apart.
        if (now - lastClickAt < 500L) {
            Log.i("RearScreenshotTile", "Ignoring duplicate tile click");
            return;
        }
        lastClickAt = now;
        Log.i("RearScreenshotTile", "Screenshot requested from tile");
        AtomicBoolean dispatched = new AtomicBoolean();
        Runnable capture = () -> {
            // Some OEMs deliver the same unlock completion more than once.
            if (!dispatched.compareAndSet(false, true)) {
                Log.i("RearScreenshotTile", "Ignoring repeated unlock callback");
                return;
            }
            try {
                Intent intent = new Intent(this, UriCommandService.class)
                        .setData(Uri.parse(BuildConfig.URI_SCHEME + "://screenshot"))
                        .putExtra("fromQuickSettings", true);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
                else startService(intent);
            } catch (RuntimeException e) {
                Log.e("RearScreenshotTile", "Could not start screenshot worker", e);
                Toast.makeText(this, R.string.rear_screenshot_failed, Toast.LENGTH_LONG).show();
            }
        };
        if (isLocked()) unlockAndRun(capture);
        else capture.run();
    }
}
