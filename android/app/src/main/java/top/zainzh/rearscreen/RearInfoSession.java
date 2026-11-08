package top.zainzh.rearscreen;

/** Lets short-lived notifications restore the manually opened information task. No background work. */
final class RearInfoSession {
    private static volatile int task = -1;
    private static volatile Runnable changed;
    static void attach(int id) { task = id; }
    static void attach(int id, Runnable listener) { changed = listener; task = id; }
    static void detach(int id) { if (task == id) { task = -1; changed = null; } }
    static void animationChanged() { Runnable listener = changed; if (listener != null) listener.run(); }
    static boolean owns(String foreground, String packageName) {
        return task > 0 && (packageName + ":" + task).equals(foreground);
    }
}
