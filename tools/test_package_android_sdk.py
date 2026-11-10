import importlib.util
import io
import json
import tempfile
import unittest
from unittest import mock
import zipfile
from pathlib import Path


SCRIPT = Path(__file__).with_name("package_android_sdk.py")
spec = importlib.util.spec_from_file_location("package_android_sdk", SCRIPT)
pkg = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pkg)


class PackageAndroidSdkTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / "repo"
        self.root.mkdir()
        self.output_parent = Path(self.temp.name) / "out"
        self.output_parent.mkdir()
        (self.root / pkg.SDK_VERSION_FILE).parent.mkdir(parents=True)
        (self.root / pkg.SDK_VERSION_FILE).write_text("sdkVersion=0.9.7\n", encoding="utf-8")
        (self.root / pkg.HOST_VERSION_FILE).write_text("versionName=4.0.0-rc.4\nversionCode=2025\n", encoding="utf-8")
        service = self.root / pkg.HOST_SERVICE_SOURCE
        service.parent.mkdir(parents=True)
        service.write_text("private static final int USER_SERVICE_CONTRACT_VERSION = 61;\n", encoding="utf-8")
        build = self.root / pkg.SDK_BUILD_FILE
        build.parent.mkdir(parents=True, exist_ok=True)
        build.write_text("defaultConfig { minSdk = 24 }\n", encoding="utf-8")
        for template in pkg.TEMPLATE_FILES:
            target = self.root / "sdk/sample/standalone" / template
            target.parent.mkdir(parents=True, exist_ok=True)
            if template == "settings.gradle.kts":
                content = 'rootProject.name = "Sample"\ninclude(":app")\n'
            elif template == "app/build.gradle.kts":
                content = '''val hostPackage = providers.gradleProperty("remrssHostPackage").orElse("top.zainzh.rearscreen").get()
android { defaultConfig { applicationId = "dev.remrss.sample.consumer"; minSdk = 24 } }
buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("debug") } }
dependencies { implementation(files("libs/platform-sdk-0.9.7.aar")) }
'''
            else:
                content = "template\n"
            target.write_text(content, encoding="utf-8")
        for relative in pkg.WRAPPER_FILES:
            target = self.root / "android" / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(b"wrapper")
        main = self.root / "sdk/sample/src/main"
        main.mkdir(parents=True)
        (main / "AndroidManifest.xml").write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android">
          <application><meta-data android:name="miui.rear.policy" android:value="1" />
            <activity android:name=".RearSceneActivity" android:taskAffinity="${applicationId}.rear" android:launchMode="standard">
              <meta-data android:name="miui.rear.policy" android:value="1" />
            </activity></application></manifest>\n''', encoding="utf-8")
        (main / "App.kt").write_text("class App\n", encoding="utf-8")
        test = self.root / "sdk/sample/src/test"
        test.mkdir(parents=True)
        (test / "AppTest.kt").write_text("class AppTest\n", encoding="utf-8")
        (self.root / "sdk/android/README.md").write_text('''SDK `0.9.7` guide\n\n## 引用本地 AAR\n\n```kotlin\ndependencies {\n    implementation(files("libs/platform-sdk-0.9.7.aar"))\n}\n```\n\n## Next\n''', encoding="utf-8")
        (self.root / "sdk/android/docs").mkdir(parents=True, exist_ok=True)
        (self.root / "sdk/android/docs/INTEGRATION.md").write_text('''SDK `0.9.7` integration guide\n\n## 1. 引用本地 AAR\n\n```kotlin\ndependencies {\n    implementation(files("libs/platform-sdk-0.9.7.aar"))\n}\n```\n\n## 2. Next\n''', encoding="utf-8")
        (self.root / "sdk/delivery/NOTICE.md").parent.mkdir(parents=True, exist_ok=True)
        (self.root / "sdk/delivery/NOTICE.md").write_text("notice\n", encoding="utf-8")
        (self.root / "LICENSE").write_text("license\n", encoding="utf-8")
        self.aar = Path(self.temp.name) / "platform-sdk-release.aar"
        classes = io.BytesIO()
        with zipfile.ZipFile(classes, "w") as archive:
            archive.writestr("dev/remrss/platform/sdk/RearClient.class", b"class")
            archive.writestr("top/zainzh/rearscreen/platform/ipc/IRemoteRearPlatformService.class", b"class")
        with zipfile.ZipFile(self.aar, "w") as archive:
            archive.writestr("AndroidManifest.xml", '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.remrss.platform.sdk"><uses-sdk android:minSdkVersion="24" /></manifest>')
            archive.writestr("classes.jar", classes.getvalue())
            archive.writestr("META-INF/com/android/build/gradle/aar-metadata.properties", "minCompileSdk=37\n")
        self.sample_apk = Path(self.temp.name) / "sample-debug.apk"
        self._write_sample_apk("dev.remrss.sample.consumer")
        self.aapt = Path(self.temp.name) / "aapt"
        self.aapt.write_text("""#!/usr/bin/env python3
import re, sys, zipfile
with zipfile.ZipFile(sys.argv[-1]) as apk:
    manifest = apk.read('AndroidManifest.xml').decode('utf-8')
match = re.search(r'package=\"([^\"]+)\"', manifest)
if not match:
    raise SystemExit('manifest package missing')
print(f"package: name='{match.group(1)}' versionCode='1' versionName='1.0'")
""", encoding="utf-8")
        self.aapt.chmod(0o755)
        self.find_aapt_patcher = mock.patch.object(pkg, "find_aapt", return_value=self.aapt)
        self.find_aapt_patcher.start()

    def tearDown(self):
        self.find_aapt_patcher.stop()
        self.temp.cleanup()

    def _write_interop_receipt(self, **updates):
        receipt = {
            "schemaVersion": 1,
            "result": "passed",
            "sdkVersion": "0.9.7",
            "referenceHost": {
                "versionName": "4.0.0-rc.4",
                "versionCode": 2025,
                "internalServiceContract": 61,
            },
            "verifiedScopes": ["sdk.connect", "rear.notification.publish-display-terminal"],
            "consumerKind": "independent exact-AAR fixture; bundled sample not installed",
            "deviceEvidenceRoot": "/private/fixture/device-evidence",
        }
        receipt.update(updates)
        path = Path(self.temp.name) / "interop-receipt.json"
        path.write_text(json.dumps(receipt), encoding="utf-8")
        return path

    def _write_sample_apk(self, application_id):
        with zipfile.ZipFile(self.sample_apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", f'<manifest package="{application_id}" />'.encode())
            archive.writestr("classes.dex", b"dex")

    def test_exports_only_standalone_sample_inputs_and_metadata(self):
        output = self.output_parent / "bundle"
        bundle, archive, metadata = pkg.package(self.root, self.aar, self.sample_apk, output)
        self.assertEqual(metadata["sdk"]["version"], "0.9.7")
        self.assertEqual(metadata["referenceHost"]["versionName"], "4.0.0-rc.4")
        self.assertEqual(metadata["referenceHost"]["versionCode"], 2025)
        self.assertEqual(metadata["referenceHost"]["internalServiceContract"], 61)
        self.assertEqual(metadata["hostBuild"]["debugCompilation"], "not-recorded")
        self.assertEqual(metadata["hostBuild"]["releaseCompilation"], "not-recorded")
        self.assertEqual(metadata["hostBuild"]["debugAndReleaseCompilation"], "not-recorded")
        self.assertFalse(metadata["hostBuild"]["hostApkProduced"])

        self.assertEqual(metadata["hostSdkInteroperability"]["status"], "not-verified")
        self.assertEqual(metadata["sample"]["applicationId"], "dev.remrss.sample.consumer")
        self.assertEqual(metadata["sample"]["apkApplicationId"], "dev.remrss.sample.consumer")
        self.assertTrue(metadata["sample"]["applicationIdVerified"])
        self.assertIn("Kotlin standard library", metadata["sdk"]["runtimeDependencies"])
        self.assertNotIn("AIDL (Android build tooling)", metadata["sdk"]["runtimeDependencies"])
        self.assertEqual(metadata["sdk"]["testedToolchain"]["kotlinGradlePlugin"], "2.4.20")
        self.assertEqual(metadata["sdk"]["minCompileSdk"], 37)
        self.assertEqual(metadata["sdk"]["capabilities"], [
            {"name": "rear.scene", "version": 1},
            {"name": "rear.scene.keep_visible", "version": 1},
            {"name": "data.battery", "version": 1},
            {"name": "data.systemMetrics", "version": 1},
            {"name": "action.invoke", "version": 1},
            {"name": "rear.notification", "version": 1},
        ])
        self.assertFalse(metadata["sample"]["hostSigningRequired"])
        self.assertTrue((bundle / "sample/app/src/main/AndroidManifest.xml").is_file())
        self.assertEqual((bundle / "sample/app/src/test/AppTest.kt").read_text(), "class AppTest\n")
        self.assertTrue((bundle / "sample/gradle/wrapper/gradle-wrapper.jar").is_file())
        self.assertTrue(zipfile.is_zipfile(bundle / "sample/app/libs/platform-sdk-0.9.7.aar"))
        self.assertEqual(metadata["sample"]["aarDependency"], "app/libs/platform-sdk-0.9.7.aar")
        self.assertTrue(zipfile.is_zipfile(bundle / "aar/platform-sdk-0.9.7.aar"))
        self.assertTrue(zipfile.is_zipfile(bundle / "sample/apk/rear-sdk-sample-debug.apk"))
        self.assertTrue((bundle / "LICENSE").is_file())
        self.assertTrue((bundle / "NOTICE.md").is_file())
        self.assertFalse((bundle / ".remrss-sdk-delivery").exists())
        self.assertIn('implementation(files("libs/platform-sdk-0.9.7.aar"))', (bundle / "docs/INTEGRATION.md").read_text())
        self.assertFalse((bundle / "sample/settings.gradle.kts").read_text().find("include(\":app\")") < 0)
        self.assertFalse((bundle / "sdk").exists())
        self.assertTrue(zipfile.is_zipfile(archive))
        with zipfile.ZipFile(archive) as zf:
            self.assertIn("bundle/delivery.json", zf.namelist())
            self.assertNotIn("bundle/.planning/findings.md", zf.namelist())

    def test_scoped_interop_receipt_is_recorded_without_private_paths(self):
        receipt_path = self._write_interop_receipt()
        bundle, archive, metadata = pkg.package(
            self.root, self.aar, self.sample_apk, self.output_parent / "scoped-interop",
            host_sdk_interop_receipt=receipt_path,
        )
        interop = metadata["hostSdkInteroperability"]
        self.assertEqual(interop["status"], "verified")
        self.assertEqual(interop["consumerKind"], "independent exact-AAR fixture; bundled sample not installed")
        self.assertEqual(interop["verifiedScopes"], ["sdk.connect", "rear.notification.publish-display-terminal"])
        self.assertIn("不代表随包独立sample APK", interop["note"])
        self.assertFalse((bundle / "deviceEvidenceRoot").exists())
        with zipfile.ZipFile(archive) as zipped:
            serialized = zipped.read("scoped-interop/delivery.json").decode("utf-8")
            self.assertNotIn("deviceEvidenceRoot", serialized)
            self.assertNotIn("/private/fixture/device-evidence", serialized)
            self.assertNotIn("result", json.loads(serialized)["hostSdkInteroperability"])

    def test_rejects_interop_receipt_when_sdk_or_host_reference_mismatches(self):
        for field, value in (
            ("sdkVersion", "0.9.6"),
            ("referenceHost", {"versionName": "4.0.0-rc.3", "versionCode": 2025, "internalServiceContract": 61}),
            ("referenceHost", {"versionName": "4.0.0-rc.4", "versionCode": 2024, "internalServiceContract": 61}),
            ("referenceHost", {"versionName": "4.0.0-rc.4", "versionCode": 2025, "internalServiceContract": 60}),
        ):
            with self.subTest(field=field, value=value):
                receipt_path = self._write_interop_receipt(**{field: value})
                with self.assertRaises(pkg.PackageError):
                    pkg.package(
                        self.root, self.aar, self.sample_apk, self.output_parent / "mismatched-receipt",
                        host_sdk_interop_receipt=receipt_path,
                    )

    def test_rejects_failed_or_empty_scope_interop_receipts(self):
        for updates in ({"result": "failed"}, {"verifiedScopes": []}, {"consumerKind": "  "}):
            with self.subTest(updates=updates):
                receipt_path = self._write_interop_receipt(**updates)
                with self.assertRaises(pkg.PackageError):
                    pkg.package(
                        self.root, self.aar, self.sample_apk, self.output_parent / "invalid-receipt",
                        host_sdk_interop_receipt=receipt_path,
                    )

    def test_rejects_docs_with_a_different_sdk_version_before_writing_output(self):
        (self.root / "sdk/android/README.md").write_text("SDK `0.3.0`\n", encoding="utf-8")
        output = self.output_parent / "wrong-doc-version"
        with self.assertRaisesRegex(pkg.PackageError, "README and integration guide must identify sdkVersion"):
            pkg.package(self.root, self.aar, self.sample_apk, output)
        self.assertFalse(output.exists())

    def test_rejects_stale_build_filename_in_integration_install_example(self):
        guide = self.root / "sdk/android/docs/INTEGRATION.md"
        guide.write_text(guide.read_text(encoding="utf-8").replace(
            "platform-sdk-0.9.7.aar", "platform-sdk-release.aar"), encoding="utf-8")
        output = self.output_parent / "stale-install-guide"
        with self.assertRaisesRegex(pkg.PackageError, "installation section must reference delivered platform-sdk-0.9.7.aar"):
            pkg.package(self.root, self.aar, self.sample_apk, output)
        self.assertFalse(output.exists())

    def test_accepts_apk_whose_official_badging_identity_matches_standalone_consumer(self):
        self._write_sample_apk("dev.remrss.sample.consumer")
        _, _, metadata = pkg.package(
            self.root, self.aar, self.sample_apk, self.output_parent / "matching-consumer"
        )
        self.assertEqual(metadata["sample"]["applicationId"], "dev.remrss.sample.consumer")
        self.assertEqual(metadata["sample"]["apkApplicationId"], "dev.remrss.sample.consumer")
        self.assertTrue(metadata["sample"]["applicationIdVerified"])

    def test_rejects_module_sample_apk_when_it_is_not_the_standalone_consumer(self):
        self._write_sample_apk("dev.remrss.sample")
        output = self.output_parent / "module-apk"
        with self.assertRaisesRegex(
            pkg.PackageError,
            "standalone consumer declares dev.remrss.sample.consumer, but APK metadata reports dev.remrss.sample",
        ):
            pkg.package(self.root, self.aar, self.sample_apk, output)
        self.assertFalse(output.exists())

    def test_accepts_root_rc4_delivery_destination(self):
        output = self.root / ".planning/2026-11-05-platform-implementation/rc4-owned-notifications/delivery/bundle"
        bundle, _, _ = pkg.package(self.root, self.aar, self.sample_apk, output)
        self.assertTrue((bundle / "delivery.json").is_file())

    def test_debug_host_apk_metadata_records_actual_variant(self):
        _, _, metadata = pkg.package(
            self.root, self.aar, self.sample_apk, self.output_parent / "debug-apk",
            host_debug_compiled=True, host_apk_produced=True,
        )
        self.assertEqual(metadata["hostBuild"]["debugCompilation"], "passed")
        self.assertEqual(metadata["hostBuild"]["releaseCompilation"], "not-recorded")
        self.assertTrue(metadata["hostBuild"]["hostApkProduced"])
        self.assertEqual(metadata["hostBuild"]["hostApkVariant"], "debug")

    def test_legacy_host_compile_flag_still_means_both_build_types_passed(self):
        bundle, _, metadata = pkg.package(
            self.root, self.aar, self.sample_apk, self.output_parent / "legacy",
            host_compiled=True,
        )
        host_build = metadata["hostBuild"]
        self.assertEqual(host_build["debugCompilation"], "passed")
        self.assertEqual(host_build["releaseCompilation"], "passed")
        self.assertEqual(host_build["debugAndReleaseCompilation"], "passed")
        self.assertFalse(host_build["hostApkProduced"])
        self.assertEqual(json.loads((bundle / "delivery.json").read_text())["hostBuild"], host_build)

    def test_debug_only_host_compile_does_not_claim_release_or_both(self):
        _, _, metadata = pkg.package(
            self.root, self.aar, self.sample_apk, self.output_parent / "debug-only",
            host_debug_compiled=True,
        )
        self.assertEqual(metadata["hostBuild"]["debugCompilation"], "passed")
        self.assertEqual(metadata["hostBuild"]["releaseCompilation"], "not-recorded")
        self.assertEqual(metadata["hostBuild"]["debugAndReleaseCompilation"], "not-recorded")

    def test_release_only_ci_metadata_does_not_claim_debug_build_and_records_host_apk(self):
        bundle, _, metadata = pkg.package(
            self.root, self.aar, self.sample_apk, self.output_parent / "release-only",
            host_release_compiled=True, host_apk_produced=True,
        )
        host_build = metadata["hostBuild"]
        self.assertEqual(host_build["debugCompilation"], "not-recorded")
        self.assertEqual(host_build["releaseCompilation"], "passed")
        self.assertEqual(host_build["debugAndReleaseCompilation"], "not-recorded")
        self.assertTrue(host_build["hostApkProduced"])
        self.assertEqual(json.loads((bundle / "delivery.json").read_text())["hostBuild"], host_build)

    def test_rejects_host_apk_metadata_without_release_compile_evidence(self):
        with self.assertRaisesRegex(pkg.PackageError, "requires verified Host compilation"):
            pkg.package(
                self.root, self.aar, self.sample_apk, self.output_parent / "inconsistent",
                host_apk_produced=True,
            )
        self.assertFalse((self.output_parent / "inconsistent").exists())

    def test_battery_observation_guide_guards_callback_epoch_and_closes_stale_lease(self):
        guide = (Path(__file__).resolve().parents[1] / "sdk/android/docs/INTEGRATION.md").read_text(encoding="utf-8")
        section = guide.split("private fun observeBatteryWhileVisible()", 1)[1].split("## 7.", 1)[0]
        self.assertIn("val requestEpoch = ++dataObservationEpoch", section)
        self.assertIn("requestEpoch == dataObservationEpoch", section)
        self.assertIn("subscription.close()", section)
        self.assertIn("dataObservationEpoch++", section)
        self.assertNotIn("if (activityStarted) batteryObservation = subscription", section)

    def test_versioned_directory_keeps_full_version_in_zip_name(self):
        output = self.output_parent / "remrss-platform-sdk-0.9.7"
        _, archive, _ = pkg.package(self.root, self.aar, self.sample_apk, output)
        self.assertEqual(archive.name, "remrss-platform-sdk-0.9.7.zip")

    def test_rejects_wrong_tested_host_before_writing_output(self):
        (self.root / pkg.HOST_VERSION_FILE).write_text("versionName=4.0.0-rc.2\nversionCode=2023\n", encoding="utf-8")
        output = self.output_parent / "bundle"
        with self.assertRaisesRegex(pkg.PackageError, "expected RC4 Host"):
            pkg.package(self.root, self.aar, self.sample_apk, output)
        self.assertFalse(output.exists())

    def test_default_host_service_matches_real_rc4_contract_61(self):
        self.assertEqual(pkg.package.__defaults__[0], 61)
        _, _, metadata = pkg.package(
            self.root, self.aar, self.sample_apk, self.output_parent / "default-service"
        )
        self.assertEqual(metadata["referenceHost"]["internalServiceContract"], 61)

    def test_rejects_old_rc4_service_contracts_59_and_60_before_writing_output(self):
        service = self.root / pkg.HOST_SERVICE_SOURCE
        for stale_version in (59, 60):
            with self.subTest(stale_version=stale_version):
                service.write_text(
                    f"private static final int USER_SERVICE_CONTRACT_VERSION = {stale_version};\n",
                    encoding="utf-8",
                )
                output = self.output_parent / f"old-service-{stale_version}"
                with self.assertRaisesRegex(pkg.PackageError, "Host TaskService service contract does not match internal service 61"):
                    pkg.package(self.root, self.aar, self.sample_apk, output)
                self.assertFalse(output.exists())

    def test_rejects_stale_internal_service_contract_before_writing_output(self):
        service = self.root / pkg.HOST_SERVICE_SOURCE
        service.write_text("private static final int USER_SERVICE_CONTRACT_VERSION = 58;\n", encoding="utf-8")
        output = self.output_parent / "stale-service"
        with self.assertRaisesRegex(pkg.PackageError, "RC4 SDK reference metadata must match internal service contract 61"):
            pkg.package(self.root, self.aar, self.sample_apk, output, host_service=58)
        self.assertFalse(output.exists())

    def test_rejects_repo_output_outside_ignored_delivery_tree(self):
        with self.assertRaisesRegex(pkg.PackageError, "ignored RC4 delivery"):
            pkg._prepare_destination(self.root / "dist/sdk", self.root)

    def test_refuses_unmanaged_existing_output(self):
        output = self.output_parent / "bundle"
        output.mkdir()
        (output / "keep.txt").write_text("keep", encoding="utf-8")
        with self.assertRaisesRegex(pkg.PackageError, "unmanaged output"):
            pkg._prepare_destination(output, self.root)
        self.assertEqual((output / "keep.txt").read_text(), "keep")

    def test_refuses_orphan_zip_without_managed_directory(self):
        (self.output_parent / "bundle.zip").write_bytes(b"valid or not, preserve it")
        with self.assertRaisesRegex(pkg.PackageError, "orphan archive"):
            pkg._prepare_destination(self.output_parent / "bundle", self.root)

    def test_rejects_symlinks_in_sample_inputs(self):
        main = self.root / "sdk/sample/src/main"
        (main / "linked.kt").symlink_to(main / "App.kt")
        with self.assertRaisesRegex(pkg.PackageError, "symbolic links"):
            pkg.package(self.root, self.aar, self.sample_apk, self.output_parent / "bundle")

    def test_rejects_scene_policy_as_boolean_or_missing(self):
        manifest = self.root / "sdk/sample/src/main/AndroidManifest.xml"
        manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application><meta-data android:name="miui.rear.policy" android:value="true" /></application></manifest>', encoding="utf-8")
        with self.assertRaisesRegex(pkg.PackageError, "application must declare integer"):
            pkg.package(self.root, self.aar, self.sample_apk, self.output_parent / "bundle")

    def test_rejects_private_keys_and_machine_local_state(self):
        main = self.root / "sdk/sample/src/main"
        (main / "signing.jks").write_bytes(b"not a key")
        with self.assertRaisesRegex(pkg.PackageError, "private/build state"):
            pkg.package(self.root, self.aar, self.sample_apk, self.output_parent / "bundle")

    def test_rejects_standalone_source_project_dependency(self):
        build = self.root / "sdk/sample/standalone/app/build.gradle.kts"
        build.write_text('dependencies { implementation(project(":platform-sdk")) }\n', encoding="utf-8")
        with self.assertRaisesRegex(pkg.PackageError, "local AAR, not another Gradle project"):
            pkg.package(self.root, self.aar, self.sample_apk, self.output_parent / "bundle")

    def test_rejects_non_apk_sample_artifact(self):
        self.sample_apk.write_bytes(b"not an APK")
        with self.assertRaisesRegex(pkg.PackageError, "sample APK is not a valid ZIP"):
            pkg.package(self.root, self.aar, self.sample_apk, self.output_parent / "bundle")

    def test_rejects_non_sdk_classes_inside_aar(self):
        classes = io.BytesIO()
        with zipfile.ZipFile(classes, "w") as archive:
            archive.writestr("top/zainzh/rearscreen/TaskService.class", b"host implementation")
        replacement = Path(self.temp.name) / "bad.aar"
        with zipfile.ZipFile(replacement, "w") as archive:
            archive.writestr("AndroidManifest.xml", '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.remrss.platform.sdk"><uses-sdk android:minSdkVersion="24" /></manifest>')
            archive.writestr("classes.jar", classes.getvalue())
            archive.writestr("META-INF/com/android/build/gradle/aar-metadata.properties", "minCompileSdk=37\n")
        with self.assertRaisesRegex(pkg.PackageError, "outside the SDK client/IPC allowlist"):
            pkg.package(self.root, replacement, self.sample_apk, self.output_parent / "bundle")


if __name__ == "__main__":
    unittest.main()
