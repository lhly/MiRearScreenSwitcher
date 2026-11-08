package top.zainzh.rearscreen;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded shell fallback for privileged Scene task snapshots only. */
final class SceneTaskShellRunner {
    static final int MAX_OUTPUT_CHARS = 65536;
    static final class Result {
        final String output;
        final int exitCode;
        final boolean timedOut;
        final boolean truncated;
        final String failureCode;
        Result(String output, int exitCode, boolean timedOut, boolean truncated, String failureCode) {
            this.output = output; this.exitCode = exitCode; this.timedOut = timedOut;
            this.truncated = truncated; this.failureCode = failureCode;
        }
    }

    private SceneTaskShellRunner() {}

    static Result run(String command, long timeoutMs) {
        Process process = null;
        try {
            process = new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
            final Process child = process;
            final AtomicReference<String> output = new AtomicReference<>("");
            final AtomicBoolean truncated = new AtomicBoolean(false);
            Thread reader = new Thread(() -> {
                StringBuilder text = new StringBuilder();
                try (BufferedReader buffered = new BufferedReader(new InputStreamReader(child.getInputStream()), 8192)) {
                    String line;
                    while ((line = buffered.readLine()) != null) {
                        if (text.length() + line.length() + 1 > MAX_OUTPUT_CHARS) { truncated.set(true); continue; }
                        text.append(line).append('\n');
                    }
                } catch (Exception ignored) { }
                output.set(text.toString());
            }, "rear-scene-shell-output");
            reader.setDaemon(true);
            reader.start();
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(250, TimeUnit.MILLISECONDS);
                reader.join(250);
                return new Result(output.get(), -1, true, truncated.get(), null);
            }
            reader.join(750);
            if (reader.isAlive()) {
                process.destroyForcibly();
                return new Result(output.get(), process.exitValue(), false, true, null);
            }
            return new Result(output.get(), process.exitValue(), false, truncated.get(), null);
        } catch (Exception e) {
            if (process != null) process.destroyForcibly();
            return new Result("", -1, false, false, e.getClass().getSimpleName());
        }
    }
}
