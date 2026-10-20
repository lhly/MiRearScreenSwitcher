package top.zainzh.rearscreen;

import android.os.SystemClock;
import java.util.*;

/** Deterministic shell injection through the real converter, without writing any evdev node. */
public final class RearTouchShellFixture {
    public static void main(String[] args) throws Exception {
        if (android.os.Process.myUid() != 2000 || (args.length != 2 && args.length != 4 && args.length != 5)) throw new SecurityException("Shell fixture only");
        float tapX = args.length >= 4 ? Float.parseFloat(args[2]) : 400;
        float tapY = args.length >= 4 ? Float.parseFloat(args[3]) : 350;
        int display = args.length == 5 ? Integer.parseInt(args[4]) : 1;
        if (display != 1) {
            Object global = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null);
            Object info = global.getClass().getMethod("getDisplayInfo", int.class).invoke(global, display);
            if (display < 2 || info == null || !info.getClass().getField("name").get(info).toString().startsWith("ReMRSS-prototype-"))
                throw new SecurityException("Fixture requires an owned ReMRSS virtual display");
            if (!args[1].equals("none")) throw new IllegalArgumentException("Virtual test must not reset rear gestures");
        }
        if (!Float.isFinite(tapX) || !Float.isFinite(tapY) || tapX < 0 || tapY < 0 || tapX >= 976 || tapY >= 596)
            throw new IllegalArgumentException("Fixture coordinates outside rear display");
        RearInputInjector injector = RearInputInjector.shell(display);
        RearTouchState state = new RearTouchState(new RearTouchState.Output() {
            public void event(int action, int[] ids, float[] xs, float[] ys, long down, long time) throws Exception {
                injector.touch(action, ids, xs, ys, down, time);
            }
            public void ended(float x, float y) throws Exception { injector.resetSystemGesture(args[1], x, y); }
        });
        Object global = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null);
        Object rear = global.getClass().getMethod("getDisplayInfo", int.class).invoke(global, 1);
        int density = rear.getClass().getField("logicalDensityDpi").getInt(rear);
        RearTouchRouter router = new RearTouchRouter(state, 976, 596, density, display > 1, injector::back);
        try {
            if (args[0].equals("back")) {
                if (display < 2) throw new IllegalArgumentException("Back test requires virtual display");
                frame(router, c(0, 10, 970, 250));
                for (int i = 1; i <= 12; i++) { SystemClock.sleep(20); frame(router, c(0, 10, 970 - i * 20, 250)); }
                frame(router);
            } else if (args[0].equals("double") || args[0].equals("tap")) {
                int count = args[0].equals("double") ? 2 : 1;
                for (int i = 0; i < count; i++) {
                    frame(router, c(0, 10 + i, tapX, tapY)); SystemClock.sleep(35); frame(router); SystemClock.sleep(65);
                }
            } else if (args[0].equals("swipe") || args[0].equals("pinch")) {
                boolean pinch = args[0].equals("pinch");
                frame(router, c(0, 10, 220, tapY)); SystemClock.sleep(20);
                if (pinch) frame(router, c(0, 10, 220, tapY), c(1, 11, 740, tapY));
                for (int i = 1; i <= 12; i++) {
                    SystemClock.sleep(20);
                    if (pinch) frame(router, c(0, 10, 220 + i * 15, tapY), c(1, 11, 740 - i * 15, tapY));
                    else frame(router, c(0, 10, 220 + i * 15, tapY));
                }
                if (pinch) frame(router, c(0, 10, 400, tapY));
                frame(router);
            } else if (args[0].equals("cancel")) {
                frame(router, c(0, 10, 400, tapY)); SystemClock.sleep(50); router.cancel(SystemClock.uptimeMillis());
            } else throw new IllegalArgumentException("Unknown touch fixture");
            System.out.println("OK " + args[0]);
        } finally { router.cancel(SystemClock.uptimeMillis()); }
    }
    private static RearTouchState.Contact c(int slot, int id, float x, float y) { return new RearTouchState.Contact(slot, id, x, y); }
    private static void frame(RearTouchRouter router, RearTouchState.Contact... contacts) throws Exception {
        router.frame(Arrays.asList(contacts), SystemClock.uptimeMillis());
    }
}
