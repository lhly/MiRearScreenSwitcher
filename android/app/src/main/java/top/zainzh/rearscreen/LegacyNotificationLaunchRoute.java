package top.zainzh.rearscreen;

/** The Xiaomi rear-display launch route is selected only from the primary keyguard state. */
final class LegacyNotificationLaunchRoute {
    enum Route { DIRECT_REAR, HIDDEN_MAIN_THEN_REAR }
    private LegacyNotificationLaunchRoute() {}
    static Route forKeyguard(boolean keyguardLocked) {
        return keyguardLocked ? Route.HIDDEN_MAIN_THEN_REAR : Route.DIRECT_REAR;
    }
}
