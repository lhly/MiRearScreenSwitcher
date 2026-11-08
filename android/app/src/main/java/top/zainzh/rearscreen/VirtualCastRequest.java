package top.zainzh.rearscreen;

import top.zainzh.rearscreen.platform.RearCapabilityCore;

/** Validated casting URI input, shared by launch and stop routing. No shell text is accepted here. */
final class VirtualCastRequest {
    // This controls the effective layout density. Density compatibility can retain the source DPI.
    static final int DEFAULT_DENSITY_DPI = 200;
    enum RearOwnerDisposition { EMPTY, CORE_SCENE, HOST, SYSTEM_LAUNCHER, UNKNOWN }
    @FunctionalInterface interface SceneTaskMatcher { boolean matches(String foreground, int displayId); }
    @FunctionalInterface interface SceneTaskIdMatcher { boolean matches(int taskId, int displayId); }
    @FunctionalInterface interface ScenePackageMatcher { boolean owns(String packageName); }
    @FunctionalInterface interface PackageStopper { void stop() throws Exception; }

    static boolean yes(String value) { return "1".equals(value) || "true".equalsIgnoreCase(value); }
    static boolean isReplaceableRearOwner(String foreground, String hostPackage) {
        return foreground != null && (foreground.startsWith(hostPackage + ":")
                || foreground.startsWith("com.xiaomi.subscreencenter:"));
    }
    static boolean isReplaceableRearOwner(String foreground, String hostPackage, int displayId) {
        return classifyRearOwner(foreground, hostPackage, displayId) != RearOwnerDisposition.UNKNOWN;
    }

    static RearOwnerDisposition classifyRearOwner(String foreground, String hostPackage, int displayId) {
        return classifyRearOwner(foreground, hostPackage, displayId, RearCapabilityCore::isActiveSceneTaskForeground);
    }

    /** Shared production decision seam: only a currently registered exact Scene task bypasses unknown-app protection. */
    static RearOwnerDisposition classifyRearOwner(
            String foreground, String hostPackage, int displayId, SceneTaskMatcher sceneTaskMatcher) {
        if (foreground == null || foreground.isEmpty()) return RearOwnerDisposition.EMPTY;
        if (sceneTaskMatcher != null && sceneTaskMatcher.matches(foreground, displayId))
            return RearOwnerDisposition.CORE_SCENE;
        if (hostPackage != null && foreground.startsWith(hostPackage + ":")) return RearOwnerDisposition.HOST;
        if (foreground.startsWith("com.xiaomi.subscreencenter:")) return RearOwnerDisposition.SYSTEM_LAUNCHER;
        return RearOwnerDisposition.UNKNOWN;
    }

    /** Run the legacy package-wide stop only when no live Core Scene owns that app package. */
    static boolean stopTargetPackageUnlessSceneOwned(String targetPackage, ScenePackageMatcher scenePackageMatcher,
                                                     PackageStopper stopper) throws Exception {
        if (targetPackage != null && scenePackageMatcher != null && scenePackageMatcher.owns(targetPackage)) return false;
        if (targetPackage != null && stopper != null) stopper.stop();
        return true;
    }
    static boolean stopTargetPackageUnlessSceneOwned(String targetPackage, PackageStopper stopper) throws Exception {
        return stopTargetPackageUnlessSceneOwned(targetPackage, RearCapabilityCore::hasActiveScenePackage, stopper);
    }
    static boolean mayRemoveStaleVirtualHostTask(int taskId, int displayId, SceneTaskIdMatcher sceneTaskMatcher) {
        return taskId > 0 && displayId > 0 && (sceneTaskMatcher == null || !sceneTaskMatcher.matches(taskId, displayId));
    }
    static boolean mayRemoveStaleVirtualHostTask(int taskId, int displayId) {
        return mayRemoveStaleVirtualHostTask(taskId, displayId, RearCapabilityCore::isActiveSceneTask);
    }
    static boolean startsProjection(String action, String physicalTask, String virtualSession) {
        return action == null && ((physicalTask != null && !physicalTask.isEmpty())
                || (virtualSession != null && !virtualSession.isEmpty()));
    }
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
