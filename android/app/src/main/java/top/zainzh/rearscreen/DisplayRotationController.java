package top.zainzh.rearscreen;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shell-side rotation transaction. Reports the applied display angle, never just the user request. */
final class DisplayRotationController {
    interface CommandRunner { Result run(String... command) throws Exception; }
    interface Pause { void sleep(long millis) throws InterruptedException; }
    static final class Result {
        final int exitCode;
        final String output;
        Result(int exitCode, String output) { this.exitCode = exitCode; this.output = output; }
    }

    private static final Pattern DISPLAY = Pattern.compile("\\bDisplay: mDisplayId=(\\d+)\\b");
    private static final Pattern ROTATION = Pattern.compile(
            "(?m)^\\s*mRotation=(ROTATION_0|ROTATION_90|ROTATION_180|ROTATION_270|[0-3])\\b");
    private final CommandRunner runner;
    private final Pause pause;
    private final Consumer<String> log;

    DisplayRotationController(CommandRunner runner, Pause pause, Consumer<String> log) {
        this.runner = runner;
        this.pause = pause;
        this.log = log;
    }

    synchronized int getRotation(int displayId) {
        if (displayId < 0) return -1;
        try {
            Result result = runner.run("dumpsys", "window", "displays");
            return result.exitCode == 0 ? parseRotation(result.output, displayId) : -1;
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            log.accept("Cannot read display " + displayId + " rotation: " + error);
            return -1;
        }
    }

    synchronized boolean setRotation(int displayId, int rotation) {
        if (displayId < 0 || rotation < 0 || rotation > 3) return false;
        String id = Integer.toString(displayId);
        String previousUser = null, previousFixed = null;
        boolean changed = false;
        try {
            previousUser = checked("wm", "user-rotation", "-d", id).trim();
            if (!previousUser.matches("free|lock [0-3]")) throw new IOException("Unknown user rotation: " + previousUser);
            if (displayId == 1) {
                previousFixed = checked("wm", "fixed-to-user-rotation", "-d", id).trim();
                if (!previousFixed.matches("default|enabled|disabled|enabled_if_no_auto_rotation")) {
                    throw new IOException("Unknown fixed-to-user policy: " + previousFixed);
                }
                // Secondary-display app orientations (including the rear launcher) override a plain lock.
                changed = true;
                checked("wm", "fixed-to-user-rotation", "-d", id, "enabled");
                if (!"enabled".equals(checked("wm", "fixed-to-user-rotation", "-d", id).trim())) {
                    throw new IOException("Fixed-to-user policy was not applied");
                }
            }
            changed = true;
            checked("wm", "user-rotation", "-d", id, "lock", Integer.toString(rotation));
            if (!("lock " + rotation).equals(checked("wm", "user-rotation", "-d", id).trim())) {
                throw new IOException("User rotation lock was not applied");
            }
            // Rotation/configuration changes can be asynchronous; bound retries and verify actual state.
            for (int attempt = 0; attempt < 12; attempt++) {
                if (getRotation(displayId) == rotation) {
                    log.accept("Verified display " + displayId + " rotation=" + rotation);
                    return true;
                }
                pause.sleep(100);
            }
            throw new IOException("Display did not reach rotation " + rotation);
        } catch (Exception error) {
            boolean interrupted = error instanceof InterruptedException || Thread.currentThread().isInterrupted();
            Thread.interrupted(); // Allow rollback commands to finish before restoring the interrupt flag.
            log.accept("Cannot rotate display " + displayId + ": " + error);
            if (changed) {
                try {
                    if ("free".equals(previousUser)) checked("wm", "user-rotation", "-d", id, "free");
                    else checked("wm", "user-rotation", "-d", id, "lock", previousUser.substring(5));
                } catch (Exception rollbackError) { log.accept("Cannot restore user rotation: " + rollbackError); }
                if (previousFixed != null) {
                    try { checked("wm", "fixed-to-user-rotation", "-d", id, previousFixed); }
                    catch (Exception rollbackError) { log.accept("Cannot restore fixed-to-user policy: " + rollbackError); }
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            return false;
        }
    }

    private String checked(String... command) throws Exception {
        Result result = runner.run(command);
        if (result.exitCode != 0) throw new IOException(Arrays.toString(command) + ": " + result.output);
        return result.output;
    }

    static int parseRotation(String dump, int displayId) {
        Matcher displays = DISPLAY.matcher(dump);
        int start = -1, end = dump.length();
        while (displays.find()) {
            if (start >= 0) { end = displays.start(); break; }
            if (Integer.toString(displayId).equals(displays.group(1))) start = displays.end();
        }
        if (start < 0) return -1;
        Matcher rotation = ROTATION.matcher(dump.substring(start, end));
        if (!rotation.find()) return -1;
        String value = rotation.group(1);
        if (value.startsWith("ROTATION_")) return Integer.parseInt(value.substring(9)) / 90;
        return Integer.parseInt(value);
    }

    /** Drain both streams together, with bounded time/output, even if wm stalls or emits an error. */
    static Result runCommand(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        InputStream stream = process.getInputStream();
        FutureTask<String> output = new FutureTask<>(() -> {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int size;
            while ((size = stream.read(buffer)) != -1) {
                if (bytes.size() + size > 1024 * 1024) throw new IOException("Rotation command output too large");
                bytes.write(buffer, 0, size);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        });
        Thread reader = new Thread(output, "display-rotation-output");
        reader.setDaemon(true);
        reader.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        try {
            while (true) {
                try {
                    int exit = process.exitValue();
                    return new Result(exit, output.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
                } catch (IllegalThreadStateException running) {
                    if (System.nanoTime() >= deadline) throw new IOException("Rotation command timed out");
                    Thread.sleep(10);
                }
            }
        } finally {
            process.destroy();
            stream.close();
            output.cancel(true);
        }
    }
}
