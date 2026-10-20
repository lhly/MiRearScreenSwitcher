package top.zainzh.rearscreen;

import android.os.Handler;
import android.os.SystemClock;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

/** Local visual QA only: real router feedback, no evdev, App touches or Back key injection. */
final class RearBackGesturePreview {
    static void play(Handler main, String mode, int density, RearTouchRouter.Feedback feedback, BooleanSupplier alive, Runnable menuOpened) {
        boolean secondStage = mode.equals("menu-release") || mode.equals("menu-cancel") || mode.equals("menu-multi");
        if (!secondStage && !mode.equals("commit") && !mode.equals("cancel") && !mode.equals("multi")) return;
        RearTouchState state = new RearTouchState(new RearTouchState.Output() {
            public void event(int a, int[] ids, float[] x, float[] y, long down, long time) { }
            public void ended(float x, float y) { }
        });
        RearTouchRouter router = new RearTouchRouter(state, 976, 596, density, true, time -> { }, feedback);
        if (secondStage) router.setMenu(new RearTouchRouter.Menu() {
            public void visible(boolean open) { if (open) menuOpened.run(); }
            public void touch(int action, float x, float y, long down, long time) { }
        });
        frame(main, router, alive, 0, c(970));
        frame(main, router, alive, 60, c(920));
        frame(main, router, alive, 150, c(830));
        frame(main, router, alive, 250, c(750));
        if (secondStage) {
            main.postDelayed(() -> {
                if (!alive.getAsBoolean()) return;
                try { router.tick(SystemClock.uptimeMillis()); }
                catch (Exception e) { android.util.Log.e("RearBackGesture", "Visual hold failed", e); }
            }, 250 + RearTouchRouter.MENU_HOLD_MS + 20);
            if (mode.equals("menu-cancel")) frame(main, router, alive, 2500, c(965));
            if (mode.equals("menu-multi")) frame(main, router, alive, 2500, c(750), new RearTouchState.Contact(1, 2, 650, 250));
            frame(main, router, alive, 3200);
            return;
        }
        if (mode.equals("cancel")) frame(main, router, alive, 1150, c(965));
        if (mode.equals("multi")) frame(main, router, alive, 1150, c(750), new RearTouchState.Contact(1, 2, 650, 250));
        frame(main, router, alive, 1350);
    }
    private static RearTouchState.Contact c(float x) { return new RearTouchState.Contact(0, 1, x, 250); }
    private static void frame(Handler main, RearTouchRouter router, BooleanSupplier alive, long delay, RearTouchState.Contact... contacts) {
        main.postDelayed(() -> {
            if (!alive.getAsBoolean()) return;
            try { router.frame(Arrays.asList(contacts), SystemClock.uptimeMillis()); }
            catch (Exception e) { android.util.Log.e("RearBackGesture", "Visual preview failed", e); }
        }, delay);
    }
}
