package top.zainzh.rearscreen;

import android.app.ActivityOptions;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.PixelCopy;
import android.view.View;
import android.widget.TextView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.test.filters.SdkSuppress;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 26)
public class ChargingGlassTest {
    private static final Instrumentation inst = InstrumentationRegistry.getInstrumentation();
    private static final Context context = inst.getTargetContext();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static ImageReader reader;
    private static VirtualDisplay display;
    private static int displayId;
    private static boolean physicalRear;
    private RearScreenChargingActivity activity;
    private SharedPreferences prefs;
    private Boolean savedAlways;

    @BeforeClass public static void createDisplay() {
        if (android.os.Build.VERSION.SDK_INT >= 29) inst.getUiAutomation().adoptShellPermissionIdentity();
        DisplayManager manager = context.getSystemService(DisplayManager.class);
        physicalRear = "true".equals(InstrumentationRegistry.getArguments().getString("chargingUsePhysicalRear"));
        if (physicalRear) {
            assertNotNull(manager.getDisplay(1));
            displayId = 1;
        } else if ("true".equals(InstrumentationRegistry.getArguments().getString("chargingUsePrimary"))) {
            // Some old emulator images cannot launch activities on secondary displays.
            displayId = 0;
        } else {
            reader = ImageReader.newInstance(976, 596, PixelFormat.RGBA_8888, 3);
            reader.setOnImageAvailableListener(source -> {
                Image image = source.acquireLatestImage();
                if (image != null) image.close();
            }, main);
            display = manager.createVirtualDisplay("charging-glass-acceptance", 976, 596, 450, reader.getSurface(),
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
            assertNotNull(display);
            displayId = display.getDisplay().getDisplayId();
        }
    }
    @AfterClass public static void releaseDisplay() {
        if (display != null) display.release();
        if (reader != null) reader.close();
        if (android.os.Build.VERSION.SDK_INT >= 29) inst.getUiAutomation().dropShellPermissionIdentity();
    }
    @Before public void prepare() {
        prefs = context.getSharedPreferences("mrss_settings", Context.MODE_PRIVATE);
        savedAlways = prefs.contains("charging_always_on_enabled") ? prefs.getBoolean("charging_always_on_enabled", false) : null;
        prefs.edit().putBoolean("charging_always_on_enabled", true).commit();
    }
    @After public void cleanup() throws Exception {
        if (activity != null) { inst.runOnMainSync(activity::finish); await(activity::isDestroyed, 2500); }
        SharedPreferences.Editor edit = prefs.edit();
        if (savedAlways == null) edit.remove("charging_always_on_enabled");
        else edit.putBoolean("charging_always_on_enabled", savedAlways);
        edit.commit();
        RearAnimationManager.endAnimation(RearAnimationManager.getCurrentAnimation());
    }
    private void launch(int level) throws Exception {
        RearAnimationManager.startAnimation(RearAnimationManager.AnimationType.CHARGING);
        Instrumentation.ActivityMonitor monitor = inst.addMonitor(FixtureChargingActivity.class.getName(), null, false);
        try {
            ActivityOptions options = ActivityOptions.makeBasic(); options.setLaunchDisplayId(displayId);
            context.startActivity(new Intent(context, FixtureChargingActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("fixtureRearDisplayId", displayId).putExtra("batteryLevel", level), options.toBundle());
            activity = (RearScreenChargingActivity) monitor.waitForActivityWithTimeout(5000);
            assertNotNull(activity);
            await(() -> activity.findViewById(R.id.full_screen_liquid) != null
                    && activity.findViewById(R.id.full_screen_liquid).getWidth() > 0, 4000);
        } finally { inst.removeMonitor(monitor); }
    }
    private ChargingLiquidView liquid() { return activity.findViewById(R.id.full_screen_liquid); }
    private void safe(Insets insets) {
        inst.runOnMainSync(() -> ViewCompat.dispatchApplyWindowInsets(activity.findViewById(R.id.charging_container),
                new WindowInsetsCompat.Builder()
                        .setDisplayCutout(new androidx.core.view.DisplayCutoutCompat(
                                new android.graphics.Rect(insets.left, insets.top, insets.right, insets.bottom), java.util.Collections.emptyList()))
                        .setInsets(WindowInsetsCompat.Type.displayCutout(), insets).build()));
        inst.waitForIdleSync();
    }
    private Bitmap snapshot(String name) throws Exception {
        Bitmap result = Bitmap.createBitmap(activity.getWindow().getDecorView().getWidth(),
                activity.getWindow().getDecorView().getHeight(), Bitmap.Config.ARGB_8888);
        CountDownLatch latch = new CountDownLatch(1); AtomicInteger status = new AtomicInteger(-1);
        inst.runOnMainSync(() -> PixelCopy.request(activity.getWindow(), result,
                value -> { status.set(value); latch.countDown(); }, main));
        assertTrue(latch.await(5, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, status.get());
        if (name != null) {
            File folder = new File(context.getFilesDir(), "charging-glass"); assertTrue(folder.isDirectory() || folder.mkdirs());
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(folder, name + ".png"))) {
                result.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
        }
        return result;
    }
    private long frames() { AtomicLong result = new AtomicLong(); inst.runOnMainSync(() -> result.set(liquid().getRenderedFrameCount())); return result.get(); }
    private void assertTopFilled(Bitmap image) {
        for (int x = image.getWidth() / 3; x < image.getWidth() - 20; x += 13) {
            for (int y : new int[]{1, 4, 12, 24}) {
                int color = image.getPixel(x, y);
                assertTrue("100% must cover the top edge at " + x + "," + y, Color.green(color) > 12 && Color.green(color) > Color.red(color));
            }
        }
    }

    @Test public void acceptedChargeStatesAreDynamicAndFullChargeCoversTop() throws Exception {
        launch(23);
        if (!physicalRear) safe(Insets.of(296, 0, 0, 0));
        Thread.sleep(2300);
        Bitmap low = snapshot("23"); low.recycle();
        inst.runOnMainSync(() -> RearScreenChargingActivity.updateBatteryLevelStatic(67)); Thread.sleep(800);
        Bitmap first = snapshot("67"); Thread.sleep(550); Bitmap second = snapshot("67-motion");
        int changed = 0;
        for (int y = first.getHeight() / 2; y < first.getHeight() - 20; y += 5)
            for (int x = 20; x < first.getWidth() - 20; x += 5)
                if (first.getPixel(x, y) != second.getPixel(x, y)) changed++;
        assertTrue("The material must animate, not replay a fixed image", changed > 100);
        first.recycle(); second.recycle();
        captureFrameTiming();
        assertTextContrast();
        inst.runOnMainSync(() -> { liquid().setAnimationActive(true); RearScreenChargingActivity.updateBatteryLevelStatic(100); }); Thread.sleep(800);
        Bitmap full = snapshot("100"); assertTopFilled(full); full.recycle();
        Thread.sleep(450); Bitmap later = snapshot(null); assertTopFilled(later); later.recycle();
        assertEquals(context.getString(R.string.charging_glass_complete), ((TextView)activity.findViewById(R.id.charging_status)).getText().toString());
    }

    private void captureFrameTiming() throws Exception {
        if (!"true".equals(InstrumentationRegistry.getArguments().getString("chargingCaptureMetrics"))) return;
        android.os.HandlerThread thread = new android.os.HandlerThread("charging-metrics"); thread.start();
        java.util.ArrayList<Long> total = new java.util.ArrayList<>(), gpu = new java.util.ArrayList<>();
        android.view.Window.OnFrameMetricsAvailableListener listener = (window, metrics, dropped) -> {
            long duration = metrics.getMetric(android.view.FrameMetrics.TOTAL_DURATION);
            if (duration >= 0) total.add(duration);
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                long gpuDuration = metrics.getMetric(android.view.FrameMetrics.GPU_DURATION);
                if (gpuDuration >= 0) gpu.add(gpuDuration);
            }
        };
        inst.runOnMainSync(() -> activity.getWindow().addOnFrameMetricsAvailableListener(listener, new Handler(thread.getLooper())));
        long before = frames(), start = android.os.SystemClock.elapsedRealtime();
        Thread.sleep(2000);
        long count = frames() - before, elapsed = android.os.SystemClock.elapsedRealtime() - start;
        inst.runOnMainSync(() -> activity.getWindow().removeOnFrameMetricsAvailableListener(listener));
        thread.quitSafely(); thread.join(1500);
        java.util.Collections.sort(total); java.util.Collections.sort(gpu);
        String report = "window=" + liquid().getWidth() + "x" + liquid().getHeight()
            + "\ndraws=" + count + " elapsed_ms=" + elapsed + " observed_draw_fps=" + count * 1000.0 / elapsed
            + "\nwindow_frame_samples=" + total.size() + " total_p95_ms=" + percentile(total)
            + "\ngpu_samples=" + gpu.size() + " gpu_p95_ms=" + percentile(gpu) + "\n";
        File folder = new File(context.getFilesDir(), "charging-glass"); assertTrue(folder.isDirectory() || folder.mkdirs());
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(folder, "frame-metrics.txt"))) {
            out.write(report.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        android.util.Log.i("ChargingGlassAcceptance", report);
    }
    private static double percentile(java.util.ArrayList<Long> values) {
        return values.isEmpty() ? -1 : values.get(Math.min(values.size() - 1, (int)(values.size() * .95))) / 1_000_000.0;
    }

    private void assertTextContrast() throws Exception {
        inst.runOnMainSync(() -> { liquid().setAnimationActive(false); liquid().invalidate(); });
        Thread.sleep(150);
        Bitmap foreground = snapshot("text-foreground");
        View group = activity.findViewById(R.id.charging_text_group);
        int[] region = new int[4];
        inst.runOnMainSync(() -> {
            TextView battery = activity.findViewById(R.id.battery_text);
            TextView status = activity.findViewById(R.id.charging_status);
            int[] location = new int[2]; group.getLocationInWindow(location);
            region[0] = location[0]; region[1] = location[1] + battery.getTop();
            region[2] = location[0] + group.getWidth(); region[3] = location[1] + status.getBottom();
            android.util.Log.i("ChargingGlassAcceptance", "text_group=" + group.getWidth() + "x" + group.getHeight()
                + " battery_height=" + battery.getHeight() + " font_px=" + battery.getTextSize());
            group.setVisibility(View.INVISIBLE);
        });
        Thread.sleep(120); Bitmap background = snapshot("text-background");
        double minimum = 21; int samples = 0;
        for (int y = Math.max(0, region[1]); y < Math.min(foreground.getHeight(), region[3]); y++) {
            for (int x = Math.max(0, region[0]); x < Math.min(foreground.getWidth(), region[2]); x++) {
                int color = foreground.getPixel(x, y);
                if (Color.red(color) > 248 && Color.green(color) > 248 && Color.blue(color) > 248) {
                    minimum = Math.min(minimum, 1.05 / (luminance(background.getPixel(x, y)) + .05)); samples++;
                }
            }
        }
        inst.runOnMainSync(() -> group.setVisibility(View.VISIBLE));
        foreground.recycle(); background.recycle();
        assertTrue(samples > 100);
        assertTrue("White charging text contrast must be >= 4.5:1; actual=" + minimum, minimum >= 4.5);
    }
    private static double luminance(int color) {
        double[] v = {Color.red(color) / 255.0, Color.green(color) / 255.0, Color.blue(color) / 255.0};
        for (int i = 0; i < 3; i++) v[i] = v[i] <= .04045 ? v[i] / 12.92 : Math.pow((v[i] + .055) / 1.055, 2.4);
        return .2126 * v[0] + .7152 * v[1] + .0722 * v[2];
    }

    // Building synthetic display-cutout WindowInsets requires the API 29 platform builder.
    @Test @SdkSuppress(minSdkVersion = 29)
    public void textCentersInCurrentSafeRegionAndBackgroundStaysFullScreen() throws Exception {
        launch(67); Thread.sleep(2100);
        for (Insets edge : new Insets[]{Insets.of(296, 0, 0, 0), Insets.of(0, 0, 296, 0),
                Insets.of(0, 180, 0, 0), Insets.of(0, 0, 0, 180), Insets.NONE}) {
            safe(edge);
            int width = activity.findViewById(R.id.charging_container).getWidth();
            int height = activity.findViewById(R.id.charging_container).getHeight();
            View group = activity.findViewById(R.id.charging_text_group);
            await(() -> group.getWidth() == width - edge.left - edge.right, 2000);
            inst.runOnMainSync(() -> {
                assertEquals((width + edge.left - edge.right) / 2f, group.getX() + group.getWidth() / 2f, 1f);
                assertEquals((height + edge.top - edge.bottom) / 2f, group.getY() + group.getHeight() / 2f, 1f);
                assertEquals(width, liquid().getWidth()); assertEquals(height, liquid().getHeight());
            });
        }
        if (display != null) {
            display.resize(596, 976, 450);
            await(() -> liquid().getWidth() == 596, 3000);
            safe(Insets.of(0, 0, 0, 296));
            inst.runOnMainSync(() -> {
                TextView text = activity.findViewById(R.id.battery_text);
                assertEquals(1, text.getLineCount());
                assertTrue(text.getPaint().measureText(text.getText().toString()) <= text.getWidth());
            });
            snapshot("portrait").recycle();
            display.resize(976, 596, 450);
        }
    }

    @Test public void invisibleStopsFramesAndAlwaysOnReducesRedraws() throws Exception {
        launch(67); Thread.sleep(2300);
        assertTrue(liquid().isFrameLoopRunning());
        inst.runOnMainSync(() -> activity.findViewById(R.id.charging_container).setVisibility(View.INVISIBLE));
        await(() -> !liquid().isFrameLoopRunning(), 1000); Thread.sleep(100);
        long frozen = frames(); Thread.sleep(450); assertEquals(frozen, frames());
        inst.runOnMainSync(() -> activity.findViewById(R.id.charging_container).setVisibility(View.VISIBLE));
        await(() -> liquid().isFrameLoopRunning(), 1000);
        await(() -> liquid().isAmbientMotion(), 10000);
        long before = frames(); Thread.sleep(1100);
        assertTrue("Ambient rendering must stay below 30fps with scheduling tolerance", frames() - before <= 35);
    }

    @Test public void reducedMotionKeepsFullChargeStatic() throws Exception {
        String original = android.provider.Settings.Global.getString(context.getContentResolver(), android.provider.Settings.Global.ANIMATOR_DURATION_SCALE);
        try {
            shell("settings put global animator_duration_scale 0");
            await(() -> !android.animation.ValueAnimator.areAnimatorsEnabled(), 2000);
            launch(100); await(() -> liquid().getFillLevel() == 1f, 2000); Thread.sleep(1500);
            assertFalse(liquid().isFrameLoopRunning());
            long before = frames(); Thread.sleep(450); assertEquals(before, frames());
            Bitmap image = snapshot("reduced-100"); assertTopFilled(image); image.recycle();
        } finally {
            shell(original == null ? "settings delete global animator_duration_scale"
                    : "settings put global animator_duration_scale " + original);
        }
    }
    private static void await(BooleanSupplier test, long timeout) throws Exception {
        long end = android.os.SystemClock.uptimeMillis() + timeout;
        while (!test.getAsBoolean() && android.os.SystemClock.uptimeMillis() < end) Thread.sleep(30);
        assertTrue("Timed out waiting for charging UI", test.getAsBoolean());
    }
    private static void shell(String command) throws Exception {
        try (ParcelFileDescriptor.AutoCloseInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(inst.getUiAutomation().executeShellCommand(command))) { while (in.read() != -1) { } }
    }
}
