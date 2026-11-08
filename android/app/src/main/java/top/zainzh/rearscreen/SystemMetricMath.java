package top.zainzh.rearscreen;

/** Metric units and missing samples are explicit; invalid readings never become zero load. */
public final class SystemMetricMath {
    private SystemMetricMath() { }

    public static long[] cpuTicks(String line) {
        if (line == null) return null;
        String[] fields = line.trim().split("\\s+");
        if (fields.length < 9 || !fields[0].equals("cpu")) return null;
        long[] ticks = new long[8];
        try {
            for (int i = 0; i < ticks.length; i++) {
                ticks[i] = Long.parseLong(fields[i + 1]);
                if (ticks[i] < 0) return null;
            }
            return ticks; // guest/guest_nice are already included in user/nice.
        } catch (NumberFormatException invalid) { return null; }
    }

    public static double cpuPercent(long[] before, long[] after) {
        if (before == null || after == null || before.length != 8 || after.length != 8) return Double.NaN;
        try {
            long total = 0;
            for (int i = 0; i < 8; i++) {
                if (before[i] < 0 || after[i] < before[i]) return Double.NaN;
                total = Math.addExact(total, after[i] - before[i]);
            }
            long idle = Math.addExact(after[3] - before[3], after[4] - before[4]);
            return total > 0 ? 100.0 * (total - idle) / total : Double.NaN;
        } catch (ArithmeticException overflow) { return Double.NaN; }
    }

    public static double gpuPercent(String line) {
        if (line == null) return Double.NaN;
        String[] fields = line.trim().split("\\s+");
        if (fields.length != 2) return Double.NaN;
        try {
            long busy = Long.parseLong(fields[0]), total = Long.parseLong(fields[1]);
            return busy >= 0 && total > 0 && busy <= total ? 100.0 * busy / total : Double.NaN;
        } catch (NumberFormatException invalid) { return Double.NaN; }
    }

    public static double bytesPerSecond(long before, long after, long elapsedMs) {
        return before >= 0 && after >= before && elapsedMs > 0 ? (after - before) * 1000.0 / elapsedMs : Double.NaN;
    }

    public static boolean maySample(boolean resumed, boolean visible, boolean displayOn, boolean interrupted) {
        return resumed && visible && displayOn && !interrupted;
    }

    public static int refreshIntervalMs(long visibleMs) {
        return visibleMs < 8000 ? 2000 : 5000;
    }
}
