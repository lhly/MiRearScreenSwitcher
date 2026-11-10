package top.zainzh.rearscreen;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 充电和通知的短时背屏显示，不停止官方进程或修改系统息屏设置。 */
final class RearAnimationDisplay {
    static final String EXTRA_LOCKED_REAR_PLACEMENT = "lockedRearPlacement";

    static void prepare(ITaskService service, RearAnimationManager.Session session, String ownPackage) throws Exception {
        String foreground = service.getForegroundAppOnDisplay(RearDisplay.requireId());
        if (foreground != null && (!foreground.startsWith(ownPackage + ":") || RearInfoSession.owns(foreground, ownPackage)
                || RearModuleSession.INSTANCE.owns(foreground, ownPackage))
                && !foreground.startsWith("com.xiaomi.subscreencenter:")
                && !foreground.startsWith("com.xiaomi.mirror:")) {
            try { session.rearTaskId = Integer.parseInt(foreground.substring(foreground.lastIndexOf(':') + 1)); }
            catch (NumberFormatException ignored) { }
        }
        RearModuleSession.INSTANCE.captureAnimationTask(session.id, session.rearTaskId);
        // 先唤醒，让系统退出Doze，再将动画置前。force-stop会触发官方延迟重睡眠。
        service.executeShellCommand("input -d " + RearDisplay.requireId() + " keyevent KEYCODE_WAKEUP");
    }

    static boolean awaitForeground(ITaskService service, String component, String ownPackage,
                                   BooleanSupplier current) throws Exception {
        return awaitForeground(service, component, ownPackage, current, ignored -> { });
    }

    static boolean awaitForeground(ITaskService service, String component, String ownPackage,
                                   BooleanSupplier current, Consumer<String> observation) throws Exception {
        String lastMatchingTask = "none";
        String lastForeground = "none";
        int display = RearDisplay.requireId();
        for (int attempt = 0; attempt < 30 && current.getAsBoolean(); attempt++) {
            String stacks = service.executeShellCommandWithResult("am stack list");
            if (stacks != null) for (String line : stacks.split("\n")) {
                if (!line.contains(component)) continue;
                Matcher task = Pattern.compile("taskId=(\\d+):").matcher(line);
                if (task.find()) {
                    int id = Integer.parseInt(task.group(1));
                    boolean onDisplay = service.isTaskOnDisplay(id, display);
                    String foreground = service.getForegroundAppOnDisplay(display);
                    String foregroundKind = (ownPackage + ":" + id).equals(foreground) ? "expected-module"
                            : foreground != null && (foreground.startsWith("com.xiaomi.subscreencenter:")
                            || foreground.startsWith("com.xiaomi.mirror:")) ? "official"
                            : foreground == null ? "none" : "other";
                    lastMatchingTask = "id=" + id + ",onDisplay=" + onDisplay;
                    lastForeground = foregroundKind;
                    if (onDisplay && foregroundKind.equals("expected-module")) return true;
                }
            }
            Thread.sleep(50);
        }
        observation.accept("lastTask=" + lastMatchingTask + ",foreground=" + lastForeground + ",display=" + display);
        return false;
    }

    /**
     * HyperOS can reject a direct rear-display Activity start while the primary keyguard is
     * showing. Start our existing content-hidden placeholder on display 0, then move that exact
     * task to the rear display. The Activity never requests a screen wake from its window; only
     * the existing explicit rear-display wake is used.
     */
    static int startLockedPlaceholder(ITaskService service, String component, String command,
                                         int rearDisplay, BooleanSupplier current) throws Exception {
        return LockedNotificationTaskFlow.startAndMove(new LockedNotificationTaskFlow.Tasks() {
            @Override public boolean start(String value) throws Exception { return service.executeShellCommand(value); }
            @Override public String list() throws Exception { return service.executeShellCommandWithResult("am stack list"); }
            @Override public boolean isOnDisplay(int taskId, int displayId) throws Exception {
                return service.isTaskOnDisplay(taskId, displayId);
            }
            @Override public boolean moveToDisplay(int taskId, int displayId) throws Exception {
                return service.moveTaskToDisplay(taskId, displayId);
            }
            @Override public void sleep(long millis) throws InterruptedException { Thread.sleep(millis); }
        }, component, command, rearDisplay, current);
    }

    static String quote(String value) { return PrivilegedShellArguments.quote(value); }
}
