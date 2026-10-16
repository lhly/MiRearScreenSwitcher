package top.zainzh.rearscreen;

import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 充电和通知的短时背屏显示，不停止官方进程或修改系统息屏设置。 */
final class RearAnimationDisplay {
    static void prepare(ITaskService service, RearAnimationManager.Session session, String ownPackage) throws Exception {
        String foreground = service.getForegroundAppOnDisplay(1);
        if (foreground != null && !foreground.startsWith(ownPackage + ":")
                && !foreground.startsWith("com.xiaomi.subscreencenter:")
                && !foreground.startsWith("com.xiaomi.mirror:")) {
            try { session.rearTaskId = Integer.parseInt(foreground.substring(foreground.lastIndexOf(':') + 1)); }
            catch (NumberFormatException ignored) { }
        }
        // 先唤醒，让系统退出Doze，再将动画置前。force-stop会触发官方延迟重睡眠。
        service.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
    }

    static boolean awaitForeground(ITaskService service, String component, String ownPackage,
                                   BooleanSupplier current) throws Exception {
        for (int attempt = 0; attempt < 30 && current.getAsBoolean(); attempt++) {
            String stacks = service.executeShellCommandWithResult("am stack list");
            if (stacks != null) for (String line : stacks.split("\n")) {
                if (!line.contains(component)) continue;
                Matcher task = Pattern.compile("taskId=(\\d+):").matcher(line);
                if (task.find()) {
                    int id = Integer.parseInt(task.group(1));
                    if (service.isTaskOnDisplay(id, 1)
                            && (ownPackage + ":" + id).equals(service.getForegroundAppOnDisplay(1))) return true;
                }
            }
            Thread.sleep(50);
        }
        return false;
    }

    static String quote(String value) { return "'" + value.replace("'", "'\"'\"'") + "'"; }
}
