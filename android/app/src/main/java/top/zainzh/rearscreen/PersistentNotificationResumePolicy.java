package top.zainzh.rearscreen;

/** Stateless policy and privacy rendering for a charge-covered ordinary notification. */
final class PersistentNotificationResumePolicy {
    private PersistentNotificationResumePolicy() {}

    interface CurrentSettings {
        boolean serviceEnabled();
        boolean keepUntilRemoved();
        boolean packageSelected(String packageName);
        boolean onlyWhenLocked();
        boolean mainScreenCovered();
        boolean followDnd();
        boolean dndActive();
        boolean hideTitle();
        boolean hideContent();
    }

    static final class Snapshot {
        final boolean serviceEnabled, keepUntilRemoved, packageSelected, onlyWhenLocked, mainScreenCovered;
        final boolean followDnd, dndActive, hideTitle, hideContent;
        Snapshot(CurrentSettings settings, String packageName) {
            serviceEnabled = settings.serviceEnabled();
            keepUntilRemoved = settings.keepUntilRemoved();
            packageSelected = settings.packageSelected(packageName);
            onlyWhenLocked = settings.onlyWhenLocked();
            mainScreenCovered = settings.mainScreenCovered();
            followDnd = settings.followDnd();
            dndActive = settings.dndActive();
            hideTitle = settings.hideTitle();
            hideContent = settings.hideContent();
        }
    }

    static boolean allows(OrdinaryNotificationChargeReturn.Notice notice,
            boolean serviceEnabled, boolean keepUntilRemoved, boolean sourceStillPosted,
            boolean packageSelected, boolean onlyWhenLocked, boolean mainScreenCovered,
            boolean followDnd, boolean dndActive) {
        return notice != null && notice.key != null && !notice.key.isEmpty() && notice.postTime > 0
                && serviceEnabled && keepUntilRemoved && sourceStillPosted && packageSelected
                && (!onlyWhenLocked || mainScreenCovered) && (!followDnd || !dndActive);
    }

    static boolean allows(OrdinaryNotificationChargeReturn.Notice notice, boolean sourceStillPosted,
            Snapshot current) {
        return current != null && allows(notice, current.serviceEnabled, current.keepUntilRemoved,
                sourceStillPosted, current.packageSelected, current.onlyWhenLocked, current.mainScreenCovered,
                current.followDnd, current.dndActive);
    }

    static OrdinaryNotificationChargeReturn.Notice renderCurrent(OrdinaryNotificationChargeReturn.Notice source,
            String currentTitle, String currentText, long currentWhen,
            boolean hideTitle, boolean hideContent, String hiddenTitle, String hiddenContent) {
        if (source == null) return null;
        return source.withContent(hideTitle ? hiddenTitle : currentTitle,
                hideContent ? hiddenContent : currentText, currentWhen);
    }
}
