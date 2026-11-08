package top.zainzh.rearscreen;

/** Pure gate shared by the existing global wakeup loops and their restoration path. */
final class LegacyDisplayKeeperPolicy {
    private LegacyDisplayKeeperPolicy() { }

    static boolean mayWake(boolean userEnabled, boolean moduleLeaseSuspended,
            boolean sceneLeaseSuspended, boolean moduleLeaseActive, boolean sceneLeaseActive) {
        return userEnabled && !moduleLeaseSuspended && !sceneLeaseSuspended
                && !moduleLeaseActive && !sceneLeaseActive;
    }

    static boolean mayRestoreAfterScene(boolean restorePriorMode, boolean userEnabled,
            boolean matchingSceneLease, boolean anotherSceneLeaseActive, boolean moduleLeaseActive) {
        return restorePriorMode && userEnabled && matchingSceneLease
                && !anotherSceneLeaseActive && !moduleLeaseActive;
    }
}
