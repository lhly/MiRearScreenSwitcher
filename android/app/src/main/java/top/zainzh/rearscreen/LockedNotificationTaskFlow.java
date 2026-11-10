package top.zainzh.rearscreen;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Bounded launch-and-move path for HyperOS builds that reject locked rear-display starts. */
final class LockedNotificationTaskFlow {
    interface Tasks {
        boolean start(String command) throws Exception;
        String list() throws Exception;
        boolean isOnDisplay(int taskId, int displayId) throws Exception;
        boolean moveToDisplay(int taskId, int displayId) throws Exception;
        void sleep(long millis) throws InterruptedException;
    }

    private static final int MAX_POLLS = 30;
    private static final long POLL_MS = 50;

    private LockedNotificationTaskFlow() {}

    static int startAndMove(Tasks tasks, String component, String command, int rearDisplay,
                            BooleanSupplier current) throws Exception {
        if (!current.getAsBoolean()) return -1;
        Set<Integer> before = componentTaskIds(tasks.list(), component);
        if (!current.getAsBoolean() || !tasks.start(command)) return -1;
        for (int attempt = 0; attempt < MAX_POLLS && current.getAsBoolean(); attempt++) {
            int taskId = uniqueFreshTaskOnDisplay(tasks.list(), component, before, tasks, 0);
            if (taskId == AMBIGUOUS) return -1;
            if (taskId > 0) {
                if (!current.getAsBoolean() || !tasks.moveToDisplay(taskId, rearDisplay)) return -1;
                return current.getAsBoolean() ? taskId : -1;
            }
            tasks.sleep(POLL_MS);
        }
        return -1;
    }

    static Set<Integer> componentTaskIds(String stacks, String component) {
        Set<Integer> ids = new HashSet<>();
        if (stacks == null) return ids;
        for (String line : stacks.split("\\n")) {
            if (!line.contains(component)) continue;
            Matcher task = Pattern.compile("taskId=(\\d+):").matcher(line);
            if (task.find()) ids.add(Integer.parseInt(task.group(1)));
        }
        return ids;
    }

    static int uniqueFreshTaskOnDisplay(String stacks, String component, Set<Integer> before,
                                        Tasks tasks, int display) throws Exception {
        if (stacks == null) return -1;
        int found = -1;
        for (String line : stacks.split("\\n")) {
            if (!line.contains(component)) continue;
            Matcher task = Pattern.compile("taskId=(\\d+):").matcher(line);
            if (!task.find()) continue;
            int id = Integer.parseInt(task.group(1));
            if (before.contains(id)) continue;
            if (!tasks.isOnDisplay(id, display)) continue;
            if (found > 0 && found != id) return AMBIGUOUS;
            found = id;
        }
        return found;
    }

    private static final int AMBIGUOUS = -2;
}
