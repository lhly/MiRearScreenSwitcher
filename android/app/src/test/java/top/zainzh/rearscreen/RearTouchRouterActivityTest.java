package top.zainzh.rearscreen;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public final class RearTouchRouterActivityTest {
    private static final String HOST = "top.zainzh.rearscreen";
    private static RearTouchState state() {
        return new RearTouchState(new RearTouchState.Output() {
            public void event(int action, int[] ids, float[] xs, float[] ys, long down, long time) { }
            public void ended(float x, float y) { }
        });
    }
    private static RearTouchState.Contact c(float x, float y, int tracking) {
        return new RearTouchState.Contact(0, tracking, x, y);
    }
    private static boolean dispatch(RearTouchRouter router, List<RearTouchState.Contact> contacts,
                                    long time, RearInputProxy.AcceptedInteraction callback) throws Exception {
        return RearInputProxy.dispatchAcceptedRouterInteraction(router.frame(contacts, time), time, callback);
    }

    @Test public void delayedFinalUpFlowsRouterProxyReporterAndUsesReleaseUptime() throws Exception {
        List<Long> reports = new ArrayList<>();
        RearTouchActivityReporter reporter = new RearTouchActivityReporter(
                (display, time) -> reports.add(time),
                (display, task, pkg) -> RearTouchPresentationGate.Decision.ALLOW);
        RearTouchRouter router = new RearTouchRouter(state(), 1000, 2000, 160, true, time -> { });
        RearInputProxy.AcceptedInteraction effect = time -> assertEquals(RearTouchActivityReporter.Result.REPORTED,
                reporter.acceptedInteraction(time, 7, 1, 42, HOST, () -> true, () -> true));

        assertTrue(dispatch(router, Collections.singletonList(c(500, 700, 1)), 1000, effect));
        assertTrue(dispatch(router, Collections.emptyList(), 6000, effect)); // matched real UP after a quiet hold
        assertEquals(Arrays.asList(1000L, 6000L), reports);
    }

    @Test public void notificationAndMenuReleaseAreAcceptedButEmptyAndSyntheticCleanupAreNot() throws Exception {
        AtomicInteger dismissed = new AtomicInteger(), activityReports = new AtomicInteger();
        RearTouchRouter router = new RearTouchRouter(state(), 1000, 2000, 160, true, time -> { });
        router.notification(7, 0, 0, 100, 100, token -> dismissed.incrementAndGet());
        RearInputProxy.AcceptedInteraction count = time -> activityReports.incrementAndGet();
        assertTrue(dispatch(router, Collections.singletonList(c(50, 50, 1)), 10, count));
        assertTrue(dispatch(router, Collections.emptyList(), 20, count));
        assertFalse(dispatch(router, Collections.emptyList(), 30, count)); // repeated empty is not a new UP
        assertEquals(1, dismissed.get());
        assertEquals(2, activityReports.get());

        AtomicInteger menuTouches = new AtomicInteger();
        RearTouchRouter menuRouter = new RearTouchRouter(state(), 1000, 2000, 160, true, time -> { });
        menuRouter.setMenu(new RearTouchRouter.Menu() {
            public void visible(boolean open) { }
            public void touch(int action, float x, float y, long down, long time) { menuTouches.incrementAndGet(); }
        });
        assertTrue(dispatch(menuRouter, Collections.singletonList(c(995, 1000, 2)), 100, count));
        assertTrue(dispatch(menuRouter, Collections.singletonList(c(950, 1000, 2)), 200, count));
        menuRouter.tick(451); // visual affordance only; no activity effect
        assertTrue(menuRouter.isMenuArmed());
        assertTrue(dispatch(menuRouter, Collections.emptyList(), 5000, count)); // real UP commits menu open
        assertTrue(menuRouter.isMenuOpen());
        assertTrue(dispatch(menuRouter, Collections.singletonList(c(500, 500, 3)), 5100, count));
        assertTrue(menuTouches.get() > 0);
        int beforeCancel = activityReports.get();
        menuRouter.cancel(5200);
        assertFalse(RearInputProxy.dispatchAcceptedRouterInteraction(false, 5200, count));
        assertEquals(beforeCancel, activityReports.get());
    }

    @Test public void backCommitUsesRealReleaseWhilePauseDrainAndClockTickDoNot() throws Exception {
        AtomicInteger backs = new AtomicInteger(), reports = new AtomicInteger();
        RearInputProxy.AcceptedInteraction count = time -> reports.incrementAndGet();
        RearTouchRouter backRouter = new RearTouchRouter(state(), 1000, 2000, 160, true,
                time -> backs.incrementAndGet());
        assertTrue(dispatch(backRouter, Collections.singletonList(c(995, 1000, 1)), 1000, count));
        assertTrue(dispatch(backRouter, Collections.singletonList(c(920, 1000, 1)), 1100, count));
        assertTrue(dispatch(backRouter, Collections.emptyList(), 5000, count));
        assertEquals(1, backs.get());

        RearTouchRouter paused = new RearTouchRouter(state(), 1000, 2000, 160, true, time -> { });
        assertFalse(dispatch(paused, Collections.emptyList(), 1, count)); // initial no-touch frame
        assertTrue(dispatch(paused, Collections.singletonList(c(600, 900, 2)), 10, count));
        paused.outputAvailable(false, 20);
        paused.outputAvailable(true, 30);
        paused.tick(5000);
        assertFalse(dispatch(paused, Collections.emptyList(), 6000, count)); // pause-cancelled contact drain
        assertTrue(dispatch(paused, Collections.singletonList(c(600, 900, 3)), 7000, count));
        assertTrue(dispatch(paused, Collections.emptyList(), 8000, count)); // a new post-resume interaction is live
        paused.cancel(6100);
    }
}
