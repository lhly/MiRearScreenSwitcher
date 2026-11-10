package top.zainzh.rearscreen;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SettingsControllerRouteMigrationTest {
    @Test public void releaseKeepsAboutRouteAndHidesOnlyDebugModuleDetails() {
        assertEquals(40, SettingsController.normalizePageForBuild(40, false));
        assertEquals(2, SettingsController.normalizePageForBuild(30, false));
        assertEquals(30, SettingsController.normalizePageForBuild(30, true));
    }

    @Test public void legacyRouteMigrationStillRunsBeforeBuildGating() {
        assertEquals(100, SettingsController.restorePageForBuild(3, 1, false));
        assertEquals(3, SettingsController.restorePageForBuild(2, 1, false));
        assertEquals(40, SettingsController.restorePageForBuild(40, 2, false));
        assertEquals(2, SettingsController.restorePageForBuild(30, 2, false));
    }
}
