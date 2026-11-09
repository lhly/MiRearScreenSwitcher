package top.zainzh.rearscreen;

/** Shared final gate: "displayed" requires a completed draw and live host facts. */
final class OwnedNotificationDrawEvidence {
    private OwnedNotificationDrawEvidence() {}

    static boolean qualifies(boolean currentToken, boolean currentGeneration, boolean hostReady,
                             boolean rearDisplayOn, boolean validViewport, boolean positiveBounds,
                             boolean attached, boolean visible, boolean windowVisible) {
        return currentToken && currentGeneration && hostReady && rearDisplayOn && validViewport &&
                positiveBounds && attached && visible && windowVisible;
    }
}
