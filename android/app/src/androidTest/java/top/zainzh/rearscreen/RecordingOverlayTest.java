package top.zainzh.rearscreen;

import android.app.Instrumentation;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.junit.Assert.*;

/** Real overlay-window lifecycle and input checks; no screenrecord process is started. */
public class RecordingOverlayTest {
    final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    final Context context = instrumentation.getTargetContext();
    ScreenRecordService service;
    View panel;
    String appop, night;
    boolean createdChannel;

    String shell(String command) throws Exception {
        try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.getUiAutomation().executeShellCommand(command))) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] bytes = new byte[4096]; int count;
            while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
            return output.toString("UTF-8");
        }
    }
    Object field(Object target, String name) {
        try {
            Field field = ScreenRecordService.class.getDeclaredField(name); field.setAccessible(true); return field.get(target);
        } catch (Exception error) { throw new AssertionError(error); }
    }
    void waitFor(BooleanSupplier condition) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 8000;
        while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < deadline) Thread.sleep(30);
        assertTrue("Overlay state timed out", condition.getAsBoolean());
    }
    @Before public void launch() throws Exception {
        String mode = shell("cmd uimode night"); night = mode.contains("auto") ? "auto" : mode.contains("yes") ? "yes" : "no";
        Matcher matcher = Pattern.compile("SYSTEM_ALERT_WINDOW: (allow|ignore|deny|default|foreground)")
                .matcher(shell("appops get " + context.getPackageName() + " SYSTEM_ALERT_WINDOW"));
        appop = matcher.find() ? matcher.group(1) : "default";
        shell("appops set " + context.getPackageName() + " SYSTEM_ALERT_WINDOW allow");
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel("rear_screen_keeper") == null) {
            manager.createNotificationChannel(new NotificationChannel("rear_screen_keeper", "ReMRSS", NotificationManager.IMPORTANCE_LOW));
            createdChannel = true;
        }
        MainActivity activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.runOnMainSync(() -> {
            Intent intent = new Intent(activity, ScreenRecordService.class);
            if (Build.VERSION.SDK_INT >= 26) activity.startForegroundService(intent); else activity.startService(intent);
        });
        waitFor(() -> field(null, "instance") != null && field(field(null, "instance"), "floatingView") != null);
        service = (ScreenRecordService) field(null, "instance"); panel = (View) field(service, "floatingView");
        waitFor(() -> panel.isAttachedToWindow() && panel.getWidth() > 0);
        instrumentation.waitForIdleSync();
    }
    @After public void cleanup() throws Exception {
        context.stopService(new Intent(context, ScreenRecordService.class));
        waitFor(() -> !ScreenRecordService.isRunning());
        shell("cmd uimode night " + night);
        shell("appops set " + context.getPackageName() + " SYSTEM_ALERT_WINDOW " + appop);
        if (createdChannel && Build.VERSION.SDK_INT >= 26) context.getSystemService(NotificationManager.class).deleteNotificationChannel("rear_screen_keeper");
        instrumentation.runOnMainSync(() -> { MainActivity activity = MainActivity.getCurrentInstance(); if (activity != null) activity.finish(); });
    }
    void screenshot(String name) throws Exception {
        instrumentation.waitForIdleSync(); Thread.sleep(120);
        try (FileOutputStream output = new FileOutputStream(new File(context.getCacheDir(), name + ".png"))) {
            instrumentation.getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        }
    }
    @Test public void themeUpdatesExistingWindowAndKeepsRecordingUiState() throws Exception {
        View toggle = panel.findViewWithTag("recording_toggle"), close = panel.findViewWithTag("recording_close");
        for (String mode : new String[]{"no", "yes"}) {
            shell("cmd uimode night " + mode);
            waitFor(() -> ((service.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) == mode.equals("yes"));
            instrumentation.runOnMainSync(() -> {
                assertSame(panel, field(service, "floatingView"));
                assertEquals(service.getColor(R.color.remrss_overlay_surface), ((GradientDrawable) panel.getBackground()).getColor().getDefaultColor());
                assertTrue(toggle.getWidth() >= 48 * service.getResources().getDisplayMetrics().density - 1);
            });
            screenshot("recording-overlay-" + (mode.equals("yes") ? "dark" : "light"));
        }
        AtomicReference<WindowManager.LayoutParams> position = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try {
                // Model an already-running recorder without requiring a physical rear display.
                java.lang.reflect.Method render = ScreenRecordService.class.getDeclaredMethod("updateRecordButtonState", View.class, boolean.class);
                render.setAccessible(true); render.invoke(service, toggle, true); close.setVisibility(View.GONE);
                WindowManager.LayoutParams copy = new WindowManager.LayoutParams(); copy.copyFrom((WindowManager.LayoutParams) panel.getLayoutParams()); position.set(copy);
            } catch (Exception error) { throw new AssertionError(error); }
        });
        shell("cmd uimode night no");
        waitFor(() -> (service.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES);
        instrumentation.runOnMainSync(() -> {
            assertSame(panel, field(service, "floatingView")); assertTrue(toggle.isActivated()); assertEquals(View.GONE, close.getVisibility());
            assertEquals(service.getString(R.string.ui_recording_stop), toggle.getContentDescription());
            assertEquals(position.get().x, ((WindowManager.LayoutParams) panel.getLayoutParams()).x);
            assertEquals(position.get().y, ((WindowManager.LayoutParams) panel.getLayoutParams()).y);
        });
        screenshot("recording-overlay-stop");
    }
    @Test public void backgroundDragMovesWindowAndCloseRemovesOverlayService() throws Exception {
        AtomicReference<int[]> start = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> { int[] location = new int[2]; panel.getLocationOnScreen(location); start.set(location); });
        float x = start.get()[0] + panel.getWidth() / 2f, y = start.get()[1] + 3 * service.getResources().getDisplayMetrics().density;
        long time = SystemClock.uptimeMillis();
        inject(time, time, MotionEvent.ACTION_DOWN, x, y);
        inject(time, time + 80, MotionEvent.ACTION_MOVE, x - 100, y + 100);
        inject(time, time + 100, MotionEvent.ACTION_UP, x - 100, y + 100);
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> {
            int[] next = new int[2]; panel.getLocationOnScreen(next);
            assertTrue(Math.abs(next[0] - start.get()[0]) > 50); assertTrue(next[1] - start.get()[1] > 50);
            View close = panel.findViewWithTag("recording_close");
            assertEquals(service.getString(R.string.ui_recording_close), close.getContentDescription()); close.performClick();
        });
        waitFor(() -> !ScreenRecordService.isRunning()); assertFalse(panel.isAttachedToWindow());
    }
    void inject(long down, long time, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, time, action, x, y, 0); event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { assertTrue(instrumentation.getUiAutomation().injectInputEvent(event, true)); } finally { event.recycle(); }
    }
}
