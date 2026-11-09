"""Freeze the private Shizuku AIDL slots before appending new methods."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
AIDL = ROOT / "android/app/src/main/aidl/top/zainzh/rearscreen/ITaskService.aidl"
EXPECTED_LEGACY = [
    ('void destroy()', 16777114),
    ('String getCurrentForegroundApp()', 1),
    ('int getTaskIdByPackage(String packageName)', 2),
    ('boolean moveTaskToDisplay(int taskId, int displayId)', 3),
    ('boolean launchWakeActivity(int displayId)', 4),
    ('boolean forceStatusBarToMainDisplay()', 5),
    ('boolean disableSubScreenLauncher()', 6),
    ('boolean enableSubScreenLauncher()', 7),
    ('boolean isLauncherProcessRunning()', 8),
    ('boolean killLauncherProcess()', 9),
    ('boolean collapseStatusBar()', 11),
    ('int getCurrentRearDpi()', 12),
    ('boolean setRearDpi(int dpi)', 13),
    ('boolean resetRearDpi()', 14),
    ('boolean takeRearScreenshot()', 15),
    ('boolean isTaskOnDisplay(int taskId, int displayId)', 16),
    ('String getForegroundAppOnDisplay(int displayId)', 17),
    ('boolean setDisplayRotation(int displayId, int rotation)', 18),
    ('int getDisplayRotation(int displayId)', 19),
    ('boolean executeShellCommand(String cmd)', 20),
    ('String executeShellCommandWithResult(String cmd)', 21),
    ('String startRearInputProxy(String readerPath, int taskId, IBinder owner)', 22),
    ('String getRearInputProxyStatus()', 23),
    ('boolean renewRearInputProxyLease(String sessionId)', 24),
    ('void stopRearInputProxy(String sessionId)', 25),
    ('String startRearVirtualCast(in Surface surface, String readerPath, int taskId, int hostTaskId, int density, String component, IBinder owner, IRearGestureListener gestures, String viewport, boolean densityCompatibility, int contentRotation, boolean fullViewport)', 26),
    ('String getRearVirtualCastStatus()', 27),
    ('boolean renewRearVirtualCastLease(String sessionId)', 28),
    ('void stopRearVirtualCast(String sessionId)', 29),
    ('boolean updateRearVirtualCastSurface(String sessionId, in Surface surface)', 30),
    ('String getRearCastViewport()', 31),
    ('void dismissRearCastMenu(String sessionId)', 32),
    ('String rotateRearVirtualCast(String sessionId, int direction)', 33),
    ('String beginRearCastViewport(String sessionId, boolean full)', 34),
    ('String finishRearCastViewport(String sessionId, in Surface surface)', 35),
    ('String getRearCastRenderPlan(int taskId, String component, int density, boolean densityCompatibility, boolean fullViewport)', 36),
    ('ParcelFileDescriptor captureRearScreenshot(out int[] metadata)', 37),
    ('boolean saveRearScreenshotLegacy(in ParcelFileDescriptor source)', 38),
    ('String readSystemMetrics()', 39),
    ('String getRearDisplayState()', 40),
    ('String getCompatibilityDiagnostics()', 41),
    ('String getRearPhysicalDisplayId()', 42),
    ('String readSystemWeather()', 43),
    ('boolean setRearCastNotificationBounds(String sessionId, long token, int left, int top, int right, int bottom)', 44),
    ('int launchSceneActivity(String component, int displayId, String launchTicket)', 45),
    ('boolean verifySceneActivity(int taskId, String component, int displayId)', 46),
    ('boolean finishSceneActivityTask(int taskId, String component, int displayId)', 47),
    ('String getPackageNameFromTaskId(int taskId)', 48),
    ('String inspectSceneActivityTask(int taskId, String component, int displayId)', 49),
    ('boolean wakeRearDisplayForScene(int displayId)', 50),
    ('String presentSceneActivityTask(int taskId, String component, int displayId, ISceneTaskActionGate actionGate)', 51),
    ('String getOfficialThemeStatus()', 52),
    ('String installOfficialTheme(String registration, int systemId, String title, in byte[] compiledResource)', 53),
    ('String removeOfficialTheme(String registration)', 54),
    ('void prepareOfficialThemeHost(int hostTaskId, String component, IBinder owner)', 55),
    ('void cancelOfficialThemeHost(IBinder owner)', 56),
    ('String recoverRearVirtualCastHost(String sessionId, int hostTaskId, int rearDisplayId, int action, ISceneTaskActionGate actionGate)', 57),
    ('String setRearVirtualCastOutputAvailable(String sessionId, boolean available, in Surface replacement, ISceneTaskActionGate actionGate)', 58),
    ('String getDisplayCastOccupancy(int displayId)', 59),
]
EXPECTED_NEW = ("boolean reportOwnedNotificationActivity(int displayId, long notificationSessionId, long expiresAtElapsedRealtimeMs, long drawEventUptime, ISceneTaskActionGate currentGate)", 60)

def methods(source):
    source = re.sub(r"/\*.*?\*/|//[^\n]*", "", source, flags=re.S)
    pattern = re.compile(r"(?m)^\s*([\w.<>, \[\]\n]+?\s+\w+\s*\([^;]*?\))\s*=\s*(\d+)\s*;", re.S)
    return [(re.sub(r"\s+", " ", signature.strip()), int(slot)) for signature, slot in pattern.findall(source)]

class TaskServiceAidlOrderTests(unittest.TestCase):
    def test_all_legacy_signatures_and_slots_are_unchanged_and_new_method_is_last(self):
        actual = methods(AIDL.read_text(encoding="utf-8"))
        self.assertEqual(EXPECTED_LEGACY, actual[:-1])
        self.assertEqual(EXPECTED_NEW, actual[-1])
        self.assertEqual(60, len(actual))
        self.assertEqual(len({slot for _, slot in actual}), len(actual))

if __name__ == "__main__":
    unittest.main()
