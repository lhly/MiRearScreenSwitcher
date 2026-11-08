package top.zainzh.rearscreen;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public final class RearInputInjectorAckTest {
    @Test public void reporterRunsOnlyAfterInjectionSinkAcknowledges() throws Exception {
        assertTrue(RearInputInjector.injectAcknowledged(event -> true, null, 0));
        try {
            RearInputInjector.injectAcknowledged(event -> false, null, 0);
            fail("rejected injection must not advance the accepted-input path");
        } catch (IllegalStateException expected) { }
    }

    @Test public void sinkFailureDoesNotReturnAcceptedAcknowledgement() throws Exception {
        try {
            RearInputInjector.injectAcknowledged(event -> { throw new SecurityException("denied"); }, null, 0);
            fail("failed sink must not return ACK");
        } catch (SecurityException expected) { }
    }

    @Test public void acknowledgementBelongsToProductionSinkCall() throws Exception {
        AtomicInteger accepted = new AtomicInteger();
        assertTrue(RearInputInjector.injectAcknowledged(event -> { accepted.incrementAndGet(); return true; }, null, 0));
        assertEquals(1, accepted.get());
    }

}
