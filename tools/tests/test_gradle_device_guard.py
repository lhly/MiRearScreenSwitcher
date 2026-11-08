import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HOOK = ROOT / "gradle/device-task-guard.gradle.kts"


class GradleDeviceGuardContractTests(unittest.TestCase):
    def test_android_build_and_fixture_share_the_same_hook(self):
        production = (ROOT / "android/build.gradle.kts").read_text(encoding="utf-8")
        fixture = (ROOT / "tools/device-guard-fixture/build.gradle.kts").read_text(encoding="utf-8")
        hook = HOOK.read_text(encoding="utf-8")
        self.assertIn('apply(from = "../gradle/device-task-guard.gradle.kts")', production)
        self.assertIn('apply(from = "../../gradle/device-task-guard.gradle.kts")', fixture)
        for task_type in (
            "DeviceProviderInstrumentTestTask",
            "ManagedDeviceInstrumentationTestTask",
            "ManagedDeviceTestTask",
            "ManagedDeviceInstrumentationTestSetupTask",
            "ManagedDeviceSetupTask",
            "ManagedDeviceCleanTask",
        ):
            with self.subTest(task_type=task_type):
                self.assertIn(f'"{task_type}"', hook)
        for required in ("cleanmanageddevices", "connected", "install", "uninstall", "androidtest", "devicecheck", "dofirst"):
            with self.subTest(required=required):
                self.assertIn(required, hook.lower())
        self.assertIn("lintAnalyzeDebugAndroidTest", fixture)
        self.assertIn('!lowerName.startsWith("lint")', hook)
        self.assertIn(":app:assembleRelease :app:lintRelease", (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8"))

    def test_fixture_is_android_plugin_and_adb_free(self):
        source_files = [
            ROOT / "tools/device-guard-fixture/settings.gradle.kts",
            ROOT / "tools/device-guard-fixture/build.gradle.kts",
            ROOT / "tools/device-guard-fixture/device/build.gradle.kts",
        ]
        text = "\n".join(path.read_text(encoding="utf-8") for path in source_files)
        self.assertNotIn("com.android.", text)
        self.assertNotIn("adb", text.lower())
        self.assertIn('marker("setup")', text)
        self.assertIn('marker("clean-managed")', text)


if __name__ == "__main__":
    unittest.main()
