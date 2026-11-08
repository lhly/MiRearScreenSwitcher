#!/usr/bin/env python3
"""Run one selected Android instrumentation test through a private ADB server."""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import shlex
import subprocess
import sys
from pathlib import Path
from typing import Any

DEFAULT_ADB_PORT = 5037
PHONE_DEVICE = "d708dac7"
APP_ID = "top.zainzh.rearscreen"
JAVA_ID = r"[A-Za-z_$][A-Za-z0-9_$]*"
JAVA_CLASS = re.compile(rf"{JAVA_ID}(?:\.{JAVA_ID})*")
TEST_SELECTOR = re.compile(rf"^{re.escape(APP_ID)}\.(?:{JAVA_ID}\.)*{JAVA_ID}(?:#{JAVA_ID})?$")
PACKAGE_ID = re.compile(rf"^{JAVA_ID}(?:\.{JAVA_ID})+$")
SUMMARY = re.compile(r"\bOK \((\d+) tests?\)")


class GuardError(RuntimeError):
    pass


def read_session(path: Path) -> dict[str, Any]:
    try:
        session = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise GuardError(f"无法读取设备session: {exc}") from exc
    if (not isinstance(session, dict) or isinstance(session.get("schema_version"), bool) or
            session.get("schema_version") != 1):
        raise GuardError("session必须使用schema_version=1")
    server = session.get("server")
    target = session.get("target")
    if not isinstance(server, dict) or not isinstance(target, dict):
        raise GuardError("session必须包含server和target对象")
    host, port, serial = server.get("host"), server.get("port"), session.get("serial")
    if not isinstance(host, str) or host not in {"localhost", "127.0.0.1", "::1"}:
        raise GuardError("ADB server仅允许显式loopback endpoint")
    if not isinstance(port, int) or isinstance(port, bool) or not (1 <= port <= 65535):
        raise GuardError("server.port必须是有效TCP端口")
    if port == DEFAULT_ADB_PORT:
        raise GuardError("拒绝共享/默认ADB server端口5037")
    if port == 15001:
        raise GuardError("拒绝已知禁用ADB endpoint 127.0.0.1:15001")
    if not isinstance(serial, str) or not serial.strip() or any(char.isspace() for char in serial):
        raise GuardError("session必须提供精确serial")
    if serial == "127.0.0.1:15001":
        raise GuardError("拒绝已知禁用设备目标127.0.0.1:15001")
    kind = target.get("kind")
    expected_device = target.get("expected_device")
    expected_model = target.get("expected_model")
    if (not isinstance(kind, str) or kind not in {"emulator", "phone"} or
            not isinstance(expected_device, str) or not expected_device or
            not isinstance(expected_model, str) or not expected_model):
        raise GuardError("target必须指定kind、expected_device和expected_model")
    if kind == "emulator":
        avd_name = target.get("avd_name")
        if not isinstance(avd_name, str) or not avd_name.startswith("remrss-root-"):
            raise GuardError("只接受root专用remrss-root-* AVD")
    elif target.get("expected_serial") != PHONE_DEVICE:
        raise GuardError(f"真机目标必须固定expected_serial={PHONE_DEVICE}")
    return session


def find_adb(explicit: str | None) -> str:
    candidate = explicit or os.environ.get("REMRSS_ADB")
    if candidate:
        path = Path(candidate)
        if path.is_file():
            return str(path)
        raise GuardError("指定的ADB文件不存在")
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise GuardError("请通过--adb或ANDROID_HOME显式提供本机ADB路径")
    path = Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
    if not path.is_file():
        raise GuardError("ANDROID_HOME中未找到platform-tools/adb")
    return str(path)


def adb_result(adb: str, session: dict[str, Any], args: list[str], timeout: float = 15.0) -> subprocess.CompletedProcess[str]:
    server = session["server"]
    serial = session["serial"]
    command = [adb, "-H", server["host"], "-P", str(server["port"]), "-s", serial, *args]
    try:
        return subprocess.run(command, text=True, capture_output=True, timeout=timeout, check=False)
    except subprocess.TimeoutExpired as exc:
        raise GuardError(f"ADB请求超时: {' '.join(args)}") from exc


def adb_call(adb: str, session: dict[str, Any], args: list[str], timeout: float = 15.0) -> str:
    result = adb_result(adb, session, args, timeout)
    if result.returncode != 0:
        raise GuardError(f"ADB请求失败 ({result.returncode}): {' '.join(args)}\n{result.stderr.strip()}")
    return result.stdout.strip()


def inventory(adb: str, session: dict[str, Any]) -> None:
    # This is the only no-serial request, pinned to the explicit private server.
    server = session["server"]
    command = [adb, "-H", server["host"], "-P", str(server["port"]), "devices", "-l"]
    try:
        result = subprocess.run(command, text=True, capture_output=True, timeout=15, check=False)
    except subprocess.TimeoutExpired as exc:
        raise GuardError("ADB inventory超时") from exc
    if result.returncode != 0:
        raise GuardError(f"ADB inventory失败: {result.stderr.strip()}")
    rows = [line.split() for line in result.stdout.splitlines()[1:] if line.strip()]
    if len(rows) != 1 or len(rows[0]) < 2 or rows[0][0] != session["serial"] or rows[0][1] != "device":
        raise GuardError("私有ADB server必须恰好列出本session唯一且ready的serial")


def validate_identity(adb: str, session: dict[str, Any]) -> None:
    inventory(adb, session)
    target = session["target"]
    device = adb_call(adb, session, ["shell", "getprop", "ro.product.device"])
    model = adb_call(adb, session, ["shell", "getprop", "ro.product.model"])
    if device != target["expected_device"] or model != target["expected_model"]:
        raise GuardError(f"设备身份不匹配: device={device!r}, model={model!r}")
    if target["kind"] == "emulator":
        qemu = adb_call(adb, session, ["shell", "getprop", "ro.kernel.qemu"])
        if qemu != "1":
            raise GuardError("目标不是Android emulator (ro.kernel.qemu != 1)")
        avd = adb_call(adb, session, ["emu", "avd", "name"])
        avd_name = next((line.strip() for line in avd.splitlines() if line.strip() and line.strip() != "OK"), "")
        if avd_name != target["avd_name"]:
            raise GuardError(f"AVD身份不匹配: {avd_name!r}")
    else:
        serialno = adb_call(adb, session, ["shell", "getprop", "ro.serialno"])
        if serialno != PHONE_DEVICE:
            raise GuardError(f"拒绝非授权真机serial: {serialno!r}")


def aapt_path(explicit: str | None) -> str:
    candidate = explicit or os.environ.get("REMRSS_AAPT")
    if candidate and Path(candidate).is_file():
        return candidate
    if candidate:
        raise GuardError("指定的aapt文件不存在")
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        versions = sorted((Path(sdk) / "build-tools").glob("*/aapt"), reverse=True)
        if versions:
            return str(versions[0])
    found = shutil.which("aapt")
    if found:
        return found
    raise GuardError("需要本机aapt读取APK元数据 (通过--aapt或ANDROID_HOME提供)")


def xmltree_instrumentations(xmltree: str) -> list[tuple[str, str]]:
    lines = xmltree.splitlines()
    declarations: list[tuple[str, str]] = []
    index = 0
    while index < len(lines):
        line = lines[index]
        element = re.match(r"^(\s*)E: instrumentation(?:\s|$)", line)
        if not element:
            index += 1
            continue
        element_indent = len(element.group(1))
        name: str | None = None
        target: str | None = None
        index += 1
        while index < len(lines):
            candidate = lines[index]
            child_element = re.match(r"^(\s*)E:", candidate)
            if child_element and len(child_element.group(1)) <= element_indent:
                break
            attr_indent = re.match(r"^(\s*)A: ", candidate)
            if attr_indent and len(attr_indent.group(1)) > element_indent:
                name_match = re.match(r"^\s*A: android:name(?:\([^)]*\))?=\"([^\"]*)\"", candidate)
                target_match = re.match(r"^\s*A: android:targetPackage(?:\([^)]*\))?=\"([^\"]*)\"", candidate)
                if name_match:
                    name = name_match.group(1)
                if target_match:
                    target = target_match.group(1)
            index += 1
        if name and target:
            declarations.append((name, target))
    return declarations


def apk_metadata(aapt: str, apk: Path) -> tuple[str, list[tuple[str, str]]]:
    if not apk.is_file():
        raise GuardError(f"APK不存在: {apk}")
    try:
        badging = subprocess.run([aapt, "dump", "badging", str(apk)], text=True, capture_output=True, timeout=30, check=False)
        xmltree = subprocess.run([aapt, "dump", "xmltree", str(apk), "AndroidManifest.xml"], text=True, capture_output=True, timeout=30, check=False)
    except subprocess.TimeoutExpired as exc:
        raise GuardError(f"aapt读取APK超时: {apk}") from exc
    if badging.returncode != 0:
        raise GuardError(f"aapt无法读取APK badging: {apk}\n{badging.stderr.strip()}")
    if xmltree.returncode != 0:
        raise GuardError(f"aapt无法读取APK manifest xmltree: {apk}\n{xmltree.stderr.strip()}")
    package_match = re.search(r"^package: name='([^']+)'", badging.stdout, re.MULTILINE)
    if not package_match or not PACKAGE_ID.fullmatch(package_match.group(1)):
        raise GuardError(f"APK缺少合法包名: {apk}")
    return package_match.group(1), xmltree_instrumentations(xmltree.stdout)


def normalize_class(name: str, package: str) -> str:
    if name.startswith("."):
        return package + name
    if "." not in name:
        return package + "." + name
    return name


def reported_success(output: str) -> bool:
    return any(line.strip() == "Success" for line in output.splitlines())


def package_installed(adb: str, session: dict[str, Any], package: str) -> bool:
    validate_identity(adb, session)
    result = adb_result(adb, session, ["shell", "pm", "path", shlex.quote(package)])
    output = result.stdout.strip()
    if result.returncode == 0:
        lines = [line.strip() for line in output.splitlines() if line.strip()]
        if lines and all(line.startswith("package:") for line in lines):
            return True
        raise GuardError(f"pm path返回无效成功响应: {package}: {output!r}")
    if result.returncode == 1 and not output and not result.stderr.strip():
        return False
    raise GuardError(
        f"pm path查询失败，不能视为包不存在 ({result.returncode}): {package}\n{result.stderr.strip()}\n{output}"
    )


def validate_test_selector(selector: str) -> tuple[str, str | None]:
    if not TEST_SELECTOR.fullmatch(selector):
        raise GuardError(f"--class必须是单个本项目全限定测试类或类#方法: {selector!r}")
    class_name, separator, method = selector.partition("#")
    return class_name, method if separator else None


def normalize_runner(runner: str, test_package: str, declarations: list[tuple[str, str]], app_package: str) -> str:
    if "/" in runner:
        package, class_name = runner.split("/", 1)
        if package != test_package:
            raise GuardError("runner component必须属于test APK包名")
    else:
        class_name = runner
    if not JAVA_CLASS.fullmatch(class_name):
        raise GuardError(f"runner必须是合法Java类名，不能包含shell语法: {runner!r}")
    declared = [(normalize_class(name, test_package), target) for name, target in declarations]
    if (class_name, app_package) not in declared:
        raise GuardError("test APK未声明指向本轮app包的指定Instrumentation runner")
    return f"{test_package}/{class_name}"


def parse_instrumentation_output(output: str, selector: str) -> int:
    selected_class, selected_method = validate_test_selector(selector)
    if any(token in output for token in ("INSTRUMENTATION_FAILED", "FAILURES!!!", "shortMsg=", "Process crashed")) or re.search(r"Process .* died", output, re.IGNORECASE):
        raise GuardError("instrumentation报告失败或进程崩溃")

    statuses: list[tuple[dict[str, str], int]] = []
    current: dict[str, str] = {}
    final_codes: list[int] = []
    for line in output.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key_value = line.removeprefix("INSTRUMENTATION_STATUS: ")
            key, separator, value = key_value.partition("=")
            if separator:
                current[key.strip()] = value.strip()
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            try:
                status_code = int(line.removeprefix("INSTRUMENTATION_STATUS_CODE: ").strip())
            except ValueError as exc:
                raise GuardError("instrumentation status code格式错误") from exc
            statuses.append((current, status_code))
            current = {}
        elif line.startswith("INSTRUMENTATION_CODE: "):
            try:
                final_codes.append(int(line.removeprefix("INSTRUMENTATION_CODE: ").strip()))
            except ValueError as exc:
                raise GuardError("instrumentation final code格式错误") from exc
    if current:
        raise GuardError("instrumentation最后一个status block未结束")
    if not statuses:
        raise GuardError("instrumentation缺少raw测试状态；必须使用-r")
    if final_codes != [-1]:
        raise GuardError(f"instrumentation缺少唯一正常结束码-1: {final_codes}")

    started: list[tuple[str, str]] = []
    succeeded: list[tuple[str, str]] = []
    for record, status_code in statuses:
        test_class = record.get("class")
        method = record.get("test")
        if test_class is None or method is None:
            raise GuardError("instrumentation status缺少class/test字段")
        if test_class != selected_class or (selected_method is not None and method != selected_method):
            raise GuardError(f"instrumentation执行了selector之外的测试: {test_class}#{method}")
        identity = (test_class, method)
        if status_code == 1:
            started.append(identity)
        elif status_code == 0:
            if identity not in started:
                raise GuardError("instrumentation测试没有先报告开始状态")
            started.remove(identity)
            succeeded.append(identity)
        elif status_code in {-2, -3, -4}:
            raise GuardError(f"instrumentation测试失败、跳过或assumption失败: {test_class}#{method} ({status_code})")
        else:
            raise GuardError(f"instrumentation出现不支持的status code: {status_code}")

    if started:
        raise GuardError(f"instrumentation有未完成的测试状态: {started}")
    summaries = SUMMARY.findall(output)
    if len(summaries) != 1:
        raise GuardError("instrumentation缺少唯一JUnit成功汇总")
    summary_count = int(summaries[0])
    if summary_count <= 0 or not succeeded or len(succeeded) != summary_count:
        raise GuardError(f"JUnit成功用例数不匹配: summary={summary_count}, status={len(succeeded)}")
    if len(set(succeeded)) != len(succeeded):
        raise GuardError("instrumentation重复报告测试成功")
    return summary_count


def confirm_after_install_failure(adb: str, session: dict[str, Any], package: str, original_error: Exception) -> bool:
    try:
        return package_installed(adb, session, package)
    except Exception as query_error:
        raise GuardError(f"安装失败: {original_error}\n无法确认是否需要清理 {package}: {query_error}") from original_error


def run_instrument(adb: str, session: dict[str, Any], app_apk: Path, test_apk: Path, runner: str, selector: str, aapt: str, timeout: float) -> None:
    if session["target"]["kind"] != "emulator":
        raise GuardError("instrumentation只允许root专用emulator；真机不安装debug/test APK")
    validate_test_selector(selector)
    app_package, _ = apk_metadata(aapt, app_apk)
    test_package, declarations = apk_metadata(aapt, test_apk)
    if app_package not in {APP_ID + ".debug", APP_ID + ".rootdebug"}:
        raise GuardError(f"只允许ReMRSS调试APK包名: {app_package}")
    if test_package != app_package + ".test":
        raise GuardError(f"test APK必须属于同一ReMRSS variant: {test_package}")
    runner_component = normalize_runner(runner, test_package, declarations, app_package)

    installed: list[str] = []
    operation_error: Exception | None = None
    try:
        validate_identity(adb, session)
        for package in (app_package, test_package):
            if package_installed(adb, session, package):
                raise GuardError(f"拒绝覆盖已安装的baseline包: {package}")
        validate_identity(adb, session)
        try:
            install_output = adb_call(adb, session, ["install", str(app_apk)], timeout=timeout)
            if not reported_success(install_output):
                raise GuardError(f"应用APK安装未成功: {install_output}")
            installed.append(app_package)
        except Exception as exc:
            if confirm_after_install_failure(adb, session, app_package, exc):
                installed.append(app_package)
            raise
        validate_identity(adb, session)
        try:
            install_output = adb_call(adb, session, ["install", str(test_apk)], timeout=timeout)
            if not reported_success(install_output):
                raise GuardError(f"test APK安装未成功: {install_output}")
            installed.append(test_package)
        except Exception as exc:
            if confirm_after_install_failure(adb, session, test_package, exc):
                installed.append(test_package)
            raise
        validate_identity(adb, session)
        output = adb_call(
            adb,
            session,
            ["shell", "am", "instrument", "-w", "-r", "-e", "class", shlex.quote(selector), shlex.quote(runner_component)],
            timeout=timeout,
        )
        print(output)
        parse_instrumentation_output(output, selector)
    except Exception as exc:
        operation_error = exc

    cleanup_errors: list[str] = []
    for package in reversed(installed):
        try:
            validate_identity(adb, session)
            cleanup_output = adb_call(adb, session, ["uninstall", package], timeout=timeout)
            if not reported_success(cleanup_output):
                raise GuardError(f"卸载未成功: {cleanup_output}")
        except Exception as exc:
            cleanup_errors.append(f"{package}: {exc}")
    if operation_error and cleanup_errors:
        raise GuardError(f"测试失败: {operation_error}\n本轮新增包清理也失败: {'; '.join(cleanup_errors)}") from operation_error
    if operation_error:
        raise operation_error
    if cleanup_errors:
        raise GuardError(f"测试已成功，但本轮新增包清理失败: {'; '.join(cleanup_errors)}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    verify = commands.add_parser("verify", help="只读核验session目标；不安装、运行或清理包")
    verify.add_argument("--session", type=Path, required=True, help="root私有设备session JSON")
    verify.add_argument("--adb", help="本机ADB可执行文件路径；未给时从ANDROID_HOME查找")
    instrument = commands.add_parser("instrument", help="在root专用模拟器执行单个选定debug instrumentation测试")
    instrument.add_argument("--session", type=Path, required=True, help="root私有设备session JSON")
    instrument.add_argument("--adb", help="本机ADB可执行文件路径；未给时从ANDROID_HOME查找")
    instrument.add_argument("--aapt", help="本机aapt可执行文件路径；未给时从ANDROID_HOME查找")
    instrument.add_argument("--app-apk", type=Path, required=True)
    instrument.add_argument("--test-apk", type=Path, required=True)
    instrument.add_argument("--runner", required=True, help="APK manifest中声明的Instrumentation Java类")
    instrument.add_argument("--class", dest="selector", required=True, help="一个本项目测试类，可选追加#method")
    instrument.add_argument("--timeout", type=float, default=180.0)
    args = parser.parse_args(argv)
    try:
        session = read_session(args.session)
        adb = find_adb(args.adb)
        if args.command == "verify":
            validate_identity(adb, session)
            print(f"身份核验通过: {session['target']['kind']} / {session['serial']}")
            return 0
        if args.timeout <= 0:
            raise GuardError("timeout必须大于0")
        validate_test_selector(args.selector)
        aapt = aapt_path(args.aapt)
        run_instrument(adb, session, args.app_apk, args.test_apk, args.runner, args.selector, aapt, args.timeout)
    except (GuardError, OSError, subprocess.SubprocessError) as exc:
        print(f"设备入口拒绝/失败: {exc}", file=sys.stderr)
        return 2
    print("Instrumentation通过；本轮新增包已清理。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
