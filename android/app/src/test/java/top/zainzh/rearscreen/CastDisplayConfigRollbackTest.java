package top.zainzh.rearscreen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class CastDisplayConfigRollbackTest {
    private static final class FakeDisplay implements CastDisplayConfigRollback.Port {
        int id = 7;
        String identity = "physical:rear-A";
        int dpi = 320;
        int rotation = 0;
        @Override public int displayId() { return id; }
        @Override public String displayIdentity() { return identity; }
        @Override public Integer dpi() { return dpi; }
        @Override public Integer rotation(int displayId) { return rotation; }
        @Override public void setDpi(int value) { dpi = value; }
        @Override public void setRotation(int displayId, int value) { rotation = value; }
    }

    @Test public void serializedSettingsWriteWinsAfterFailedUriRollbackForBothFields() throws Exception {
        DisplayActionGate gate = new DisplayActionGate();
        FakeDisplay display = new FakeDisplay();
        CountDownLatch uriInWait = new CountDownLatch(1);
        CountDownLatch settingsAttempted = new CountDownLatch(1);
        Thread uri = new Thread(() -> gate.run(() -> {
            try {
                CastDisplayConfigRollback snapshot = CastDisplayConfigRollback.capture(display, true, true);
                display.setDpi(400);
                display.setRotation(7, 1);
                uriInWait.countDown();
                if (!settingsAttempted.await(2, TimeUnit.SECONDS)) throw new AssertionError("settings did not attempt write");
                // This is the failed URI's rollback, still inside the production display-action gate.
                snapshot.restore(display);
            } catch (Exception e) { throw new RuntimeException(e); }
        }));
        uri.start();
        assertTrue(uriInWait.await(2, TimeUnit.SECONDS));
        Thread settings = new Thread(() -> {
            settingsAttempted.countDown();
            try { gate.call(() -> { display.setDpi(480); display.setRotation(7, 2); return true; }); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        settings.start();
        uri.join(3000); settings.join(3000);
        assertEquals(480, display.dpi);
        assertEquals(2, display.rotation);
    }

    @Test public void failedRequestRestoresOnlyItsExplicitFieldsForSameDisplay() throws Exception {
        FakeDisplay display = new FakeDisplay();
        CastDisplayConfigRollback snapshot = CastDisplayConfigRollback.capture(display, true, true);
        display.dpi = 400; display.rotation = 1;
        snapshot.restore(display);
        assertEquals(320, display.dpi);
        assertEquals(0, display.rotation);

        CastDisplayConfigRollback dpiOnly = CastDisplayConfigRollback.capture(display, true, false);
        display.dpi = 400; display.rotation = 3;
        dpiOnly.restore(display);
        assertEquals(320, display.dpi);
        assertEquals("an unspecified rotation is not touched", 3, display.rotation);
    }

    @Test public void snapshotCannotRestoreOntoAReplacementPhysicalDisplay() throws Exception {
        FakeDisplay display = new FakeDisplay();
        CastDisplayConfigRollback snapshot = CastDisplayConfigRollback.capture(display, true, true);
        display.id = 8; display.identity = "physical:rear-B"; display.dpi = 480; display.rotation = 2;
        snapshot.restore(display);
        assertEquals(480, display.dpi);
        assertEquals(2, display.rotation);
    }
}
