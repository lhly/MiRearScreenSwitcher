package top.zainzh.rearscreen;

/** Epoch checks for native reader ACKs; the reader process may have several ordered ACKs buffered. */
final class RearInputReaderAckGate {
    private RearInputReaderAckGate() {}
    static boolean acceptReady(long ackEpoch, long currentEpoch, long resumeEpoch,
                                boolean outputAvailable, boolean closed) {
        return !closed && (resumeEpoch >= 0 && ackEpoch == resumeEpoch
                || outputAvailable && ackEpoch == currentEpoch);
    }
    static boolean acceptPaused(long ackEpoch, long currentEpoch, boolean outputAvailable, boolean closed) {
        return !closed && !outputAvailable && ackEpoch == currentEpoch;
    }
}
