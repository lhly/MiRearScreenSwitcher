package top.zainzh.rearscreen;

/** Validated casting URI input, shared by launch and stop routing. No shell text is accepted here. */
final class VirtualCastRequest {
    // This controls the effective layout density. Density compatibility can retain the source DPI.
    static final int DEFAULT_DENSITY_DPI = 240;
    static boolean yes(String value) { return "1".equals(value) || "true".equalsIgnoreCase(value); }
    static String packageName(String value) {
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))
            throw new IllegalArgumentException("无效的应用包名");
        return value;
    }
    static String component(String value) {
        if (value == null) throw new IllegalArgumentException("未指定 Activity");
        String[] parts = value.split("/", -1);
        if (parts.length != 2 || !parts[1].matches("\\.?[A-Za-z_][A-Za-z0-9_.$]*"))
            throw new IllegalArgumentException("无效的 Activity");
        packageName(parts[0]);
        return value;
    }
    static int density(String value) {
        int dpi = value == null ? DEFAULT_DENSITY_DPI : Integer.parseInt(value);
        if (dpi < 72 || dpi > 640) throw new IllegalArgumentException("虚拟显示 DPI 需在 72–640 之间");
        return dpi;
    }
    static void rotation(String value) {
        if (value != null && !"0".equals(value)) throw new IllegalArgumentException("投放 URI 不接受非零 rotation 参数，请使用背屏菜单旋转内容");
    }
    static boolean matchesReturn(String current, String requestedTask, String requestedPackage,
                                 int originalTask, int foregroundTask, String originalPackage, String foregroundPackage) {
        if (yes(current)) return true;
        if (requestedTask != null) {
            int target = Integer.parseInt(requestedTask);
            return target >= 0 && (target == originalTask || target == foregroundTask);
        }
        if (requestedPackage != null) {
            packageName(requestedPackage);
            return requestedPackage.equals(originalPackage) || requestedPackage.equals(foregroundPackage);
        }
        return true; // A bare return is an idempotent stop of the current virtual cast.
    }
}
