package top.zainzh.rearscreen;

import static org.junit.Assert.*;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public final class PersistentNotificationResumePolicyTest {
    private static final class MutableSettings implements PersistentNotificationResumePolicy.CurrentSettings {
        boolean service = true, keep = true, locked, covered, followDnd = true, dnd, hideTitle, hideContent;
        Set<String> selected = new HashSet<>();
        MutableSettings() { selected.add("source.app"); }
        @Override public boolean serviceEnabled() { return service; }
        @Override public boolean keepUntilRemoved() { return keep; }
        @Override public boolean packageSelected(String packageName) { return selected.contains(packageName); }
        @Override public boolean onlyWhenLocked() { return locked; }
        @Override public boolean mainScreenCovered() { return covered; }
        @Override public boolean followDnd() { return followDnd; }
        @Override public boolean dndActive() { return dnd; }
        @Override public boolean hideTitle() { return hideTitle; }
        @Override public boolean hideContent() { return hideContent; }
    }

    private static OrdinaryNotificationChargeReturn.Notice notice() {
        return new OrdinaryNotificationChargeReturn.Notice("key", 100, "source.app", "old title", "old body", 1, 7);
    }

    @Test public void persistedPolicyWinsBeforeReloadBroadcastUpdatesCachedServiceFields() {
        MutableSettings persisted = new MutableSettings();
        PersistentNotificationResumePolicy.Snapshot before = new PersistentNotificationResumePolicy.Snapshot(persisted, "source.app");
        assertTrue(PersistentNotificationResumePolicy.allows(notice(), true, before));

        // UI has already written preferences; the service's cached fields have not received RELOAD yet.
        persisted.service = false;
        PersistentNotificationResumePolicy.Snapshot afterServiceOff = new PersistentNotificationResumePolicy.Snapshot(persisted, "source.app");
        assertFalse(PersistentNotificationResumePolicy.allows(notice(), true, afterServiceOff));
        persisted.service = true;
        persisted.keep = false;
        assertFalse(PersistentNotificationResumePolicy.allows(notice(), true,
                new PersistentNotificationResumePolicy.Snapshot(persisted, "source.app")));
        persisted.keep = true;
        persisted.selected.clear();
        assertFalse(PersistentNotificationResumePolicy.allows(notice(), true,
                new PersistentNotificationResumePolicy.Snapshot(persisted, "source.app")));
    }

    @Test public void privacyTighteningRendersCurrentSbnOnlyAfterRedaction() {
        MutableSettings persisted = new MutableSettings();
        persisted.hideTitle = true;
        persisted.hideContent = true;
        PersistentNotificationResumePolicy.Snapshot current = new PersistentNotificationResumePolicy.Snapshot(persisted, "source.app");
        assertTrue(PersistentNotificationResumePolicy.allows(notice(), true, current));

        OrdinaryNotificationChargeReturn.Notice safe = PersistentNotificationResumePolicy.renderCurrent(
                notice(), "current private title", "current private body", 2,
                current.hideTitle, current.hideContent, "Hidden title", "New message");
        assertEquals("Hidden title", safe.title);
        assertEquals("New message", safe.text);
        assertEquals(2, safe.when);
    }

    @Test public void revokedSourceOrDndPolicyBlocksResume() {
        MutableSettings persisted = new MutableSettings();
        assertFalse(PersistentNotificationResumePolicy.allows(notice(), false,
                new PersistentNotificationResumePolicy.Snapshot(persisted, "source.app")));
        persisted.dnd = true;
        assertFalse(PersistentNotificationResumePolicy.allows(notice(), true,
                new PersistentNotificationResumePolicy.Snapshot(persisted, "source.app")));
    }
}
