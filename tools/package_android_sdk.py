#!/usr/bin/env python3
"""Export the RC4 Android SDK as a narrowly allowlisted, standalone bundle."""

from __future__ import annotations

import argparse
import io
import json
import os
import re
import shutil
import subprocess
import tempfile
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path


SDK_VERSION_FILE = Path("sdk/android/version.properties")
HOST_VERSION_FILE = Path("version.properties")
HOST_SERVICE_SOURCE = Path("android/app/src/main/java/top/zainzh/rearscreen/TaskService.java")
SDK_BUILD_FILE = Path("sdk/android/build.gradle.kts")
BUILD_SDK_AAR = "platform-sdk-release.aar"
SDK_AAR_PREFIX = "platform-sdk-"
CONTRACT = "remrss.platform/1"
CAPABILITIES = [
    {"name": "rear.scene", "version": 1},
    {"name": "rear.scene.keep_visible", "version": 1},
    {"name": "data.battery", "version": 1},
    {"name": "data.systemMetrics", "version": 1},
    {"name": "action.invoke", "version": 1},
    {"name": "rear.notification", "version": 1},
]
FEATURES = ["rear-info", "scene-lifecycle", "keep-visible", "consent-gated-battery-and-system-metrics",
            "publisher-scoped-registered-actions", "per-invocation-host-confirmation",
            "publisher-owned-rear-notifications", "notification-authorization", "notification-result-events"]
RUNTIME_DEPENDENCIES = ["Android framework (minSdk 24)", "org.json (Android platform)", "Kotlin standard library"]
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
TEMPLATE_FILES = (
    "settings.gradle.kts",
    "build.gradle.kts",
    "gradle.properties",
    "app/build.gradle.kts",
    "README.md",
)
WRAPPER_FILES = (
    "gradlew",
    "gradlew.bat",
    "gradle/wrapper/gradle-wrapper.jar",
    "gradle/wrapper/gradle-wrapper.properties",
)
MANAGED_MARKER = "delivery.json"


class PackageError(ValueError):
    """Raised for invalid inputs or unsafe export destinations."""


def parse_properties(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith(("#", "!")):
            continue
        if "=" not in line:
            raise PackageError(f"invalid properties line in {path}: {line}")
        key, value = line.split("=", 1)
        result[key.strip()] = value.strip()
    return result


def require_regular_file(path: Path, label: str) -> None:
    if path.is_symlink() or not path.is_file():
        raise PackageError(f"{label} must be a regular file: {path}")


def validate_archive(path: Path, label: str, required_entries: set[str]) -> set[str]:
    try:
        with zipfile.ZipFile(path) as archive:
            bad_entry = archive.testzip()
            if bad_entry:
                raise PackageError(f"{label} has a corrupt archive entry: {bad_entry}")
            names = set(archive.namelist())
    except (zipfile.BadZipFile, OSError) as exc:
        raise PackageError(f"{label} is not a valid ZIP archive: {exc}") from exc
    missing = required_entries - names
    if missing:
        raise PackageError(f"{label} is missing required entries: {', '.join(sorted(missing))}")
    return names


def find_aapt(repo: Path) -> Path:
    """Locate the Android SDK's official APK badging tool without guessing an APK identity."""
    roots: list[Path] = []
    for variable in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        value = os.environ.get(variable)
        if value:
            roots.append(Path(value).expanduser())
    local_properties = repo / "android/local.properties"
    if local_properties.is_file():
        sdk_dir = parse_properties(local_properties).get("sdk.dir")
        if sdk_dir:
            configured = Path(sdk_dir).expanduser()
            roots.append(configured if configured.is_absolute() else local_properties.parent / configured)
    for sdk in roots:
        build_tools = sdk / "build-tools"
        if not build_tools.is_dir():
            continue
        versions = []
        for directory in build_tools.iterdir():
            if not directory.is_dir() or not re.fullmatch(r"\d+(?:\.\d+)+", directory.name):
                continue
            version = tuple(int(part) for part in directory.name.split("."))
            binary = directory / ("aapt.exe" if os.name == "nt" else "aapt")
            if binary.is_file():
                versions.append((version, binary))
        if versions:
            return max(versions, key=lambda item: item[0])[1]
    from_path = shutil.which("aapt")
    if from_path:
        return Path(from_path)
    raise PackageError("Android SDK build-tools aapt is required to inspect the sample APK applicationId")


def apk_application_id(path: Path, aapt: Path) -> str:
    """Read the packaged application identity through Google's Android aapt metadata reader."""
    try:
        result = subprocess.run(
            [str(aapt), "dump", "badging", str(path)],
            check=False, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=30,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise PackageError(f"cannot read sample APK metadata with aapt: {exc}") from exc
    if result.returncode != 0:
        detail = result.stderr.strip() or f"exit={result.returncode}"
        raise PackageError(f"aapt could not inspect sample APK: {detail}")
    match = re.search(r"^package:\s+name='([^']+)'", result.stdout, re.MULTILINE)
    if not match:
        raise PackageError("aapt output does not contain the sample APK package name")
    return match.group(1)


def validate_aar(path: Path) -> int:
    validate_archive(path, "AAR", {
        "AndroidManifest.xml", "classes.jar", "META-INF/com/android/build/gradle/aar-metadata.properties"
    })
    try:
        with zipfile.ZipFile(path) as archive:
            manifest = ET.fromstring(archive.read("AndroidManifest.xml"))
            classes_data = archive.read("classes.jar")
            aar_metadata = archive.read("META-INF/com/android/build/gradle/aar-metadata.properties").decode("utf-8")
    except (ET.ParseError, KeyError, OSError) as exc:
        raise PackageError(f"AAR contents are invalid: {exc}") from exc
    if manifest.get("package") != "dev.remrss.platform.sdk":
        raise PackageError("AAR manifest namespace does not match the ReMRSS platform SDK")
    uses_sdk = manifest.find("uses-sdk")
    if uses_sdk is None or uses_sdk.get(ANDROID_NS + "minSdkVersion") != "24":
        raise PackageError("AAR manifest must declare minSdkVersion 24")
    aar_properties = {}
    for line in aar_metadata.splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            aar_properties[key.strip()] = value.strip()
    try:
        min_compile_sdk = int(aar_properties["minCompileSdk"])
    except (KeyError, ValueError) as exc:
        raise PackageError("AAR metadata must declare a numeric minCompileSdk") from exc
    try:
        with zipfile.ZipFile(io.BytesIO(classes_data)) as classes:
            class_names = [entry for entry in classes.namelist() if entry.endswith(".class")]
    except zipfile.BadZipFile as exc:
        raise PackageError("AAR classes.jar is invalid") from exc
    allowed_prefixes = ("dev/remrss/platform/sdk/", "top/zainzh/rearscreen/platform/ipc/")
    unexpected = [name for name in class_names if not name.startswith(allowed_prefixes)]
    if unexpected:
        raise PackageError(f"AAR contains classes outside the SDK client/IPC allowlist: {', '.join(unexpected[:5])}")
    return min_compile_sdk


def copy_tree_files(source: Path, destination: Path) -> None:
    """Copy regular files only; reject links and special files at any depth."""
    if source.is_symlink() or not source.is_dir():
        raise PackageError(f"source directory missing or unsafe: {source}")
    for item in sorted(source.rglob("*")):
        rel = item.relative_to(source)
        lowered = [part.lower() for part in rel.parts]
        if any(part in {".git", ".gradle", "local.properties"} for part in lowered) or any(
            part == ".env" or part.endswith((".jks", ".keystore", ".pem", ".p12", ".pfx"))
            for part in lowered
        ):
            raise PackageError(f"private/build state is not allowed in sample source: {rel}")
        target = destination / rel
        if item.is_symlink():
            raise PackageError(f"symbolic links are not allowed in export input: {item}")
        if item.is_dir():
            target.mkdir(parents=True, exist_ok=True)
        elif item.is_file():
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(item, target)
        else:
            raise PackageError(f"special files are not allowed in export input: {item}")


def validate_sample_manifest(path: Path) -> None:
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError) as exc:
        raise PackageError(f"sample manifest is invalid: {exc}") from exc
    application = root.find("application")
    if application is None:
        raise PackageError("sample manifest must declare an application")

    def has_rear_policy(node: ET.Element) -> bool:
        return any(
            meta.get(ANDROID_NS + "name") == "miui.rear.policy"
            and meta.get(ANDROID_NS + "value") == "1"
            for meta in node.findall("meta-data")
        )

    if not has_rear_policy(application):
        raise PackageError("sample application must declare integer miui.rear.policy=1")
    scenes = [
        activity for activity in application.findall("activity")
        if activity.get(ANDROID_NS + "name", "").endswith("RearSceneActivity")
    ]
    if len(scenes) != 1 or not has_rear_policy(scenes[0]):
        raise PackageError("sample RearSceneActivity must declare integer miui.rear.policy=1")
    scene = scenes[0]
    if scene.get(ANDROID_NS + "taskAffinity") != "${applicationId}.rear" or scene.get(ANDROID_NS + "launchMode") != "standard":
        raise PackageError("sample rear Activity must use its isolated task affinity and standard launch mode")
    if scene.get(ANDROID_NS + "allowTaskReparenting", "false").lower() != "false":
        raise PackageError("sample rear Activity must not allow task reparenting")


def _resolved_inside(path: Path, parent: Path) -> bool:
    try:
        path.resolve().relative_to(parent.resolve())
        return True
    except ValueError:
        return False


def _prepare_destination(destination: Path, repo: Path) -> None:
    # The default location is ignored planning state. Custom destinations are
    # accepted only outside the repository or under this ignored delivery root.
    ignored_roots = (
        repo / ".planning/2026-11-05-platform-implementation/rc4-owned-notifications/delivery",
        repo / ".planning/2026-11-05-platform-implementation/rc4-owned-notifications/host-sdk/delivery",
        repo / ".planning/2026-11-05-platform-implementation/rc4-owned-notifications/sdk-delivery/output",
    )
    if _resolved_inside(destination, repo) and not any(_resolved_inside(destination, root) for root in ignored_roots):
        raise PackageError("output inside the repository must stay under an ignored RC4 delivery directory")
    if destination.is_symlink():
        raise PackageError(f"output path may not be a symbolic link: {destination}")
    if destination.exists():
        marker = destination / MANAGED_MARKER
        try:
            metadata = json.loads(marker.read_text(encoding="utf-8")) if marker.is_file() else None
        except (OSError, json.JSONDecodeError):
            metadata = None
        if not destination.is_dir() or not isinstance(metadata, dict) or metadata.get("sdk", {}).get("name") != "ReMRSS Android Platform SDK":
            raise PackageError(f"refusing to replace an unmanaged output directory: {destination}")
    elif Path(f"{destination}.zip").exists():
        raise PackageError(f"refusing to replace an orphan archive without its managed directory: {destination}.zip")



def load_interop_receipt(
    receipt_path: Path | None, sdk_version: str, host_name: str, host_code: int, host_service: int,
) -> dict | None:
    """Validate scoped consumer evidence and return only its public metadata fields."""
    if receipt_path is None:
        return None
    require_regular_file(receipt_path, "Host-SDK interoperability receipt")
    try:
        receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise PackageError(f"Host-SDK interoperability receipt is invalid JSON: {exc}") from exc
    if not isinstance(receipt, dict):
        raise PackageError("Host-SDK interoperability receipt must be a JSON object")
    if receipt.get("schemaVersion") != 1:
        raise PackageError("Host-SDK interoperability receipt schemaVersion must be 1")
    if receipt.get("result") != "passed":
        raise PackageError("Host-SDK interoperability receipt result must be passed")
    if receipt.get("sdkVersion") != sdk_version:
        raise PackageError("Host-SDK interoperability receipt SDK version does not match this package")
    reference = receipt.get("referenceHost")
    if not isinstance(reference, dict):
        raise PackageError("Host-SDK interoperability receipt referenceHost must be an object")
    if (reference.get("versionName") != host_name
            or type(reference.get("versionCode")) is not int or reference.get("versionCode") != host_code
            or type(reference.get("internalServiceContract")) is not int
            or reference.get("internalServiceContract") != host_service):
        raise PackageError("Host-SDK interoperability receipt Host version or service contract does not match this package")
    scopes = receipt.get("verifiedScopes")
    if (not isinstance(scopes, list) or not scopes
            or any(not isinstance(scope, str)
                   or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,119}", scope) for scope in scopes)
            or len(set(scopes)) != len(scopes)):
        raise PackageError("Host-SDK interoperability receipt verifiedScopes must be a non-empty list of unique scope names")
    consumer_kind = receipt.get("consumerKind")
    if (not isinstance(consumer_kind, str) or not consumer_kind.strip()
            or len(consumer_kind.strip()) > 160 or "\n" in consumer_kind or "\r" in consumer_kind):
        raise PackageError("Host-SDK interoperability receipt consumerKind must be a concise non-empty string")
    return {"status": "verified", "consumerKind": consumer_kind.strip(), "verifiedScopes": scopes,
            "note": "实机验收仅覆盖所列verifiedScopes；不代表随包独立sample APK或其他未列范围已验证。"}


def package(
    repo: Path, aar: Path, sample_apk: Path, output: Path,
    host_service: int = 61, host_compiled: bool = False, host_debug_compiled: bool = False,
    host_release_compiled: bool = False, host_apk_produced: bool = False, aapt_path: Path | None = None,
    host_sdk_interop_receipt: Path | None = None,
) -> tuple[Path, Path, dict]:
    repo = repo.resolve()
    aar = aar.absolute()
    sample_apk = sample_apk.absolute()
    output = output.absolute()
    require_regular_file(aar, "AAR")
    require_regular_file(sample_apk, "sample APK")
    min_compile_sdk = validate_aar(aar)
    validate_archive(sample_apk, "sample APK", {"AndroidManifest.xml", "classes.dex"})
    actual_application_id = apk_application_id(sample_apk, aapt_path or find_aapt(repo))
    version_props = parse_properties(repo / SDK_VERSION_FILE)
    sdk_version = version_props.get("sdkVersion", "")
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?", sdk_version):
        raise PackageError("sdkVersion is missing or invalid in sdk/android/version.properties")
    readme_text = (repo / "sdk/android/README.md").read_text(encoding="utf-8")
    integration_text = (repo / "sdk/android/docs/INTEGRATION.md").read_text(encoding="utf-8")
    if f"`{sdk_version}`" not in readme_text or f"`{sdk_version}`" not in integration_text:
        raise PackageError("SDK README and integration guide must identify sdkVersion from version.properties")
    aar_name = f"{SDK_AAR_PREFIX}{sdk_version}.aar"
    installation_sections = []
    for text, heading in (
        (readme_text, "## 引用本地 AAR"),
        (integration_text, "## 1. 引用本地 AAR"),
    ):
        if heading not in text:
            raise PackageError(f"SDK docs must include the local AAR installation section: {heading}")
        section = text.split(heading, 1)[1].split("\n## ", 1)[0]
        expected_dependency = f'implementation(files("libs/{aar_name}"))'
        if expected_dependency not in section or "platform-sdk-release.aar" in section:
            raise PackageError(f"SDK docs installation section must reference delivered {aar_name}")
        installation_sections.append(section)
    host_props = parse_properties(repo / HOST_VERSION_FILE)
    host_name = host_props.get("versionName", "")
    host_code = host_props.get("versionCode", "")
    if host_name != "4.0.0-rc.4" or host_code != "2025":
        raise PackageError(f"expected RC4 Host 4.0.0-rc.4/2025, got {host_name}/{host_code}")
    if host_service != 61:
        raise PackageError("RC4 SDK reference metadata must match internal service contract 61")
    if host_apk_produced and not (host_compiled or host_debug_compiled or host_release_compiled):
        raise PackageError("recording a Host APK requires verified Host compilation")
    service_text = (repo / HOST_SERVICE_SOURCE).read_text(encoding="utf-8")
    match = re.search(r"USER_SERVICE_CONTRACT_VERSION\s*=\s*(\d+)\s*;", service_text)
    if not match or int(match.group(1)) != host_service:
        raise PackageError(f"Host TaskService service contract does not match internal service {host_service}")
    interop_metadata = load_interop_receipt(
        host_sdk_interop_receipt, sdk_version, host_name, int(host_code), host_service,
    )
    sdk_build = (repo / SDK_BUILD_FILE).read_text(encoding="utf-8")
    if not re.search(r"minSdk\s*=\s*24\b", sdk_build):
        raise PackageError("SDK minSdk declaration is not 24")

    standalone = repo / "sdk/sample/standalone"
    source_main = repo / "sdk/sample/src/main"
    source_test = repo / "sdk/sample/src/test"
    for file in TEMPLATE_FILES:
        require_regular_file(standalone / file, "standalone template")
    if not source_main.is_dir():
        raise PackageError("single-source sample entry is missing: sdk/sample/src/main")
    validate_sample_manifest(source_main / "AndroidManifest.xml")

    settings = (standalone / "settings.gradle.kts").read_text(encoding="utf-8")
    sample_build = (standalone / "app/build.gradle.kts").read_text(encoding="utf-8")
    if "includeBuild(" in settings or re.search(r"include\s*\(\s*['\"]:(?:platform-sdk|host)['\"]\s*\)", settings):
        raise PackageError("standalone settings must not include Host or SDK source projects")
    if re.search(r"\bproject\s*\(", sample_build):
        raise PackageError("standalone sample must consume the local AAR, not another Gradle project")
    sdk_aar = f"{SDK_AAR_PREFIX}{sdk_version}.aar"
    expected_dependency = f'implementation(files("libs/{sdk_aar}"))'
    if expected_dependency not in sample_build:
        raise PackageError(f"standalone sample must depend on its versioned local {sdk_aar}")
    app_id_match = re.search(r'applicationId\s*=\s*"([A-Za-z0-9_.]+)"', sample_build)
    host_match = re.search(r'providers\.gradleProperty\("remrssHostPackage"\)\.orElse\("([A-Za-z0-9_.]+)"\)', sample_build)
    if not app_id_match or not host_match:
        raise PackageError("standalone sample must declare a literal applicationId and production Host default")
    application_id = app_id_match.group(1)
    if actual_application_id != application_id:
        raise PackageError(
            f"sample APK applicationId mismatch: standalone consumer declares {application_id}, "
            f"but APK metadata reports {actual_application_id}"
        )
    default_host = host_match.group(1)
    if default_host != "top.zainzh.rearscreen":
        raise PackageError("standalone sample must default to the production Host package")
    if not re.search(r"minSdk\s*=\s*24\b", sample_build):
        raise PackageError("standalone sample minSdk must remain 24")
    if "signingConfigs.getByName(\"debug\")" not in sample_build:
        raise PackageError("standalone sample Release must use its own debug key, not a Host signing key")

    _prepare_destination(output, repo)
    output.parent.mkdir(parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix=".remrss-sdk-stage-", dir=output.parent))
    zip_stage = output.parent / f".{output.name}.zip.tmp"
    backup_dir = output.parent / f".{output.name}.backup"
    zip_path = Path(f"{output}.zip")
    zip_backup = output.parent / f".{zip_path.name}.backup"
    installed_output = False
    installed_zip = False
    try:
        bundle = stage / output.name
        (bundle / "aar").mkdir(parents=True)
        shutil.copy2(aar, bundle / "aar" / sdk_aar)
        sample = bundle / "sample"
        for rel in TEMPLATE_FILES:
            src = standalone / rel
            require_regular_file(src, "standalone template")
            target = sample / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, target)
        for rel in WRAPPER_FILES:
            src = repo / "android" / rel
            require_regular_file(src, "Gradle wrapper")
            target = sample / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, target)
        copy_tree_files(source_main, sample / "app/src/main")
        if source_test.exists():
            copy_tree_files(source_test, sample / "app/src/test")
        (sample / "app/libs").mkdir(parents=True, exist_ok=True)
        shutil.copy2(aar, sample / "app/libs" / sdk_aar)
        apk_output = sample / "apk/rear-sdk-sample-debug.apk"
        apk_output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(sample_apk, apk_output)
        shutil.copy2(repo / "LICENSE", bundle / "LICENSE")
        require_regular_file(repo / "sdk/delivery/NOTICE.md", "SDK license notice")
        shutil.copy2(repo / "sdk/delivery/NOTICE.md", bundle / "NOTICE.md")
        readme = repo / "sdk/android/README.md"
        require_regular_file(readme, "SDK integration guide")
        shutil.copy2(readme, bundle / "README.md")
        guide = repo / "sdk/android/docs/INTEGRATION.md"
        require_regular_file(guide, "SDK integration guide")
        (bundle / "docs").mkdir(parents=True, exist_ok=True)
        shutil.copy2(guide, bundle / "docs/INTEGRATION.md")

        metadata = {
            "schemaVersion": 1,
            "sdk": {"name": "ReMRSS Android Platform SDK", "version": sdk_version,
                    "artifact": f"aar/{sdk_aar}", "minSdk": 24, "minCompileSdk": min_compile_sdk,
                    "contract": CONTRACT, "capabilities": CAPABILITIES, "features": FEATURES,
                    "license": "GPL-3.0", "runtimeDependencies": RUNTIME_DEPENDENCIES,
                    "compileRequirements": ["Kotlin compiler must read metadata 2.4.0; verified with Kotlin Gradle Plugin 2.4.20"],
                    "testedToolchain": {"androidGradlePlugin": "9.4.1", "gradle": "9.7.1",
                                        "kotlinGradlePlugin": "2.4.20", "compileSdk": 37,
                                        "sampleTargetSdk": 36, "jvmTarget": 11}},
            "referenceHost": {"versionName": host_name, "versionCode": int(host_code),
                              "internalServiceContract": host_service,
                              "versionSource": str(HOST_VERSION_FILE)},
            "hostBuild": {
                "debugCompilation": "passed" if host_compiled or host_debug_compiled else "not-recorded",
                "releaseCompilation": "passed" if host_compiled or host_release_compiled else "not-recorded",
                "debugAndReleaseCompilation": "passed" if host_compiled else "not-recorded",
                "hostApkProduced": host_apk_produced,
                "hostApkVariant": ("release" if host_release_compiled or host_compiled else "debug") if host_apk_produced else None,
            },
            "hostSdkInteroperability": interop_metadata or {
                "status": "not-verified",
                "note": "没有提供与本 SDK 和 referenceHost 匹配的互通验收证据。",
            },
            "sample": {"directory": "sample", "applicationId": application_id,
                       "apkApplicationId": actual_application_id, "applicationIdVerified": True,
                       "aarDependency": f"app/libs/{sdk_aar}",
                       "apk": "sample/apk/rear-sdk-sample-debug.apk", "apkVariant": "debug",
                       "defaultHostPackage": default_host, "hostSigningRequired": False},
        }
        (bundle / "delivery.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

        if zip_path.exists() and not zipfile.is_zipfile(zip_path):
            raise PackageError(f"refusing to replace non-ZIP output: {zip_path}")
        with zipfile.ZipFile(zip_stage, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for item in sorted(bundle.rglob("*")):
                if item.is_file():
                    archive.write(item, Path(output.name) / item.relative_to(bundle))
        if backup_dir.exists() or zip_backup.exists():
            raise PackageError("stale delivery backup exists; inspect it before retrying")
        if output.exists():
            os.replace(output, backup_dir)
        if zip_path.exists():
            os.replace(zip_path, zip_backup)
        os.replace(bundle, output)
        installed_output = True
        os.replace(zip_stage, zip_path)
        installed_zip = True
        if backup_dir.exists():
            shutil.rmtree(backup_dir)
        if zip_backup.exists():
            zip_backup.unlink()
        return output, zip_path, metadata
    except Exception:
        if zip_stage.exists():
            zip_stage.unlink()
        if installed_zip and zip_path.exists():
            zip_path.unlink()
        if installed_output and output.exists():
            shutil.rmtree(output)
        if backup_dir.exists():
            os.replace(backup_dir, output)
        if zip_backup.exists():
            os.replace(zip_backup, zip_path)
        raise
    finally:
        shutil.rmtree(stage, ignore_errors=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--aar", type=Path, required=True, help="built platform-sdk-release.aar from the Gradle output directory")
    parser.add_argument("--sample-apk", type=Path, required=True, help="independently built sample debug APK")
    parser.add_argument("--output", type=Path, help="bundle directory; defaults to ignored RC4 planning delivery path")
    parser.add_argument("--host-service", type=int, default=61)
    parser.add_argument("--host-sdk-interop-receipt", type=Path,
                        help="scoped Host-SDK interoperability evidence JSON; only validated scope fields enter metadata")
    parser.add_argument("--host-compile-verified", action="store_true",
                        help="legacy shorthand: record only after both Host debug and release compilation passed")
    parser.add_argument("--host-debug-compile-verified", action="store_true",
                        help="record only the reference Host debug compilation as passed")
    parser.add_argument("--host-release-compile-verified", action="store_true",
                        help="record only after the reference Host release compile/lint or release APK build passed")
    parser.add_argument("--host-apk-produced", action="store_true",
                        help="record only after a reference Host APK was produced by this workflow; records debug/release variant from verified build flags")
    args = parser.parse_args()
    try:
        repo = args.repo.resolve()
        sdk_version = parse_properties(repo / SDK_VERSION_FILE).get("sdkVersion", "unknown")
        output = args.output or repo / ".planning/2026-11-05-platform-implementation/rc4-owned-notifications/delivery" / f"remrss-platform-sdk-{sdk_version}"
        bundle, archive, meta = package(
            repo, args.aar, args.sample_apk, output, args.host_service,
            host_compiled=args.host_compile_verified,
            host_debug_compiled=args.host_debug_compile_verified,
            host_release_compiled=args.host_release_compile_verified,
            host_apk_produced=args.host_apk_produced,
            host_sdk_interop_receipt=args.host_sdk_interop_receipt,
        )
    except (PackageError, OSError, shutil.Error, zipfile.BadZipFile) as exc:
        parser.error(str(exc))
    print(f"bundle={bundle}")
    print(f"zip={archive}")
    print(f"sdkVersion={meta['sdk']['version']}")
    reference = meta["referenceHost"]
    print(f"referenceHost={reference['versionName']}/{reference['versionCode']} service={reference['internalServiceContract']}")
    host_build = meta["hostBuild"]
    print(f"hostBuild=debug:{host_build['debugCompilation']}, release:{host_build['releaseCompilation']}, "
          f"both:{host_build['debugAndReleaseCompilation']}, apkProduced:{host_build['hostApkProduced']}; "
          f"hostSdkInterop={meta['hostSdkInteroperability']['status']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
