package top.zainzh.rearscreen;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import org.junit.Test;

public class RearInputReaderLineProtocolTest {
    @Test public void staleReadyThenBufferedFrameIsDiscardedUntilCurrentReady() throws Exception {
        long currentEpoch = 2;
        long resumeEpoch = 2;
        boolean ready = false;

        // R1's READY2 is already buffered when Java starts R2. The production ACK gate rejects it.
        assertFalse(RearInputReaderAckGate.acceptReady(1, currentEpoch, resumeEpoch, false, false));
        String[] staleFrame = "F 1234 1 0:7:120:240".split(" ");
        assertTrue("Production line consumer must discard the buffered frame",
                RearInputReaderLineProtocol.discardFrameIfNotReady(staleFrame, ready, 5));

        assertTrue(RearInputReaderAckGate.acceptReady(2, currentEpoch, resumeEpoch, false, false));
    }

    @Test public void malformedStaleFrameAndUnknownLineRemainProtocolErrors() throws Exception {
        assertInvalid("F 1234 2 0:7:120:240", 5);
        assertInvalid("F 1234 1 bad-contact", 5);
        assertFalse("Unknown lines stay on the normal protocol-error path",
                RearInputReaderLineProtocol.discardFrameIfNotReady("WHATEVER 1".split(" "), false, 5));
    }

    @Test public void bufferedFrameIsDiscardedAsSoonAsOutputPauseBegins() throws Exception {
        String[] frame = "F 1234 1 0:7:120:240".split(" ");
        boolean ready = true;
        boolean outputAvailable = false; // Native S has been requested; PAUSED ACK may still be queued.
        assertTrue(RearInputReaderLineProtocol.discardFrameIfNotReady(
                frame, ready && outputAvailable, 5));
    }

    private static void assertInvalid(String line, int slots) throws Exception {
        try {
            RearInputReaderLineProtocol.discardFrameIfNotReady(line.split(" "), false, slots);
            fail("Expected malformed frame to fail: " + line);
        } catch (IOException expected) { }
    }
}
