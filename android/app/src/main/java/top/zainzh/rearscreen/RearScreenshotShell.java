package top.zainzh.rearscreen;

import android.os.ParcelFileDescriptor;
import android.util.Log;
import java.io.*;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.*;

/** Shell-identity I/O. No application bitmap, template or preference is read in this process. */
final class RearScreenshotShell {
    private RearScreenshotShell() {}
    private static Object displayInfo() throws Exception {
        Object manager = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null);
        Object info = manager.getClass().getMethod("getDisplayInfo", int.class).invoke(manager, 1);
        if (info == null) throw new IOException("Rear display unavailable");
        return info;
    }
    static ParcelFileDescriptor capture(int[] metadata) throws Exception {
        if (metadata == null || metadata.length != 3) throw new IllegalArgumentException("Expected rotation, width and height");
        Object info = displayInfo(); Class<?> type = info.getClass();
        int rotation = type.getField("rotation").getInt(info);
        int width = type.getField("logicalWidth").getInt(info), height = type.getField("logicalHeight").getInt(info);
        Object address = type.getField("address").get(info);
        if (address == null) throw new IOException("Rear physical display address unavailable");
        Method physicalId = address.getClass().getMethod("getPhysicalDisplayId");
        long id = (Long) physicalId.invoke(address);
        File file = File.createTempFile("remrss-capture-", ".png", new File("/data/local/tmp"));
        try {
            // Waking the rear display does not unlock the main screen.
            run(new String[]{"/system/bin/input", "-d", "1", "keyevent", "KEYCODE_WAKEUP"});
            Thread.sleep(200);
            run(new String[]{"/system/bin/screencap", "-p", "-d", Long.toUnsignedString(id), file.getPath()});
            Object after = displayInfo();
            if (rotation != type.getField("rotation").getInt(after) || width != type.getField("logicalWidth").getInt(after)
                    || height != type.getField("logicalHeight").getInt(after)) throw new IOException("Rear display changed during capture");
            if (file.length() < 24) throw new IOException("Empty rear screenshot");
            metadata[0] = rotation; metadata[1] = width; metadata[2] = height;
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } finally { if (!file.delete()) Log.w("RearScreenshot", "Could not unlink capture file"); }
    }
    static boolean saveLegacy(ParcelFileDescriptor source) {
        File pending = null;
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(source)) {
            File directory = new File("/storage/emulated/0/Pictures/RearDisplay");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create screenshot directory");
            String name = "RD_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8) + ".png";
            pending = new File(directory, "." + name);
            try (OutputStream output = new FileOutputStream(pending)) {
                byte[] buffer = new byte[32768]; int count; long total = 0;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > 48 * 1024 * 1024) throw new IOException("Screenshot too large");
                    output.write(buffer, 0, count);
                }
            }
            if (pending.length() < 24) throw new IOException("Empty screenshot");
            File destination = new File(directory, name);
            if (!pending.renameTo(destination)) throw new IOException("Cannot publish screenshot");
            pending = null;
            run(new String[]{"/system/bin/am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file://" + destination});
            return true;
        } catch (Exception e) { Log.e("RearScreenshot", "Legacy screenshot save failed", e); return false; }
        finally { if (pending != null && !pending.delete()) Log.w("RearScreenshot", "Could not remove partial screenshot"); }
    }
    private static void run(String[] command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        ScheduledFuture<?> timeout = timer.schedule(process::destroy, 8, TimeUnit.SECONDS);
        try (InputStream output = process.getInputStream()) {
            byte[] buffer = new byte[1024]; while (output.read(buffer) != -1) { /* drain bounded command output */ }
            if (process.waitFor() != 0) throw new IOException("Screenshot command failed: " + command[0]);
        } finally { timeout.cancel(false); timer.shutdownNow(); process.destroy(); }
    }
}
