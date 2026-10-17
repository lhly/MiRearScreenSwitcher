#!/usr/bin/env python3
"""Exercise the minified APK's reflective entry point and real Binder dispatch on a test device."""

import argparse
import os
from pathlib import Path
import re
import shlex
import subprocess
import tempfile
import uuid


RUNNER = r'''
package top.zainzh.audit;

import android.os.IBinder;
import android.os.Parcel;
import java.lang.reflect.Field;

public final class ReleaseContractRunner {
    private static final String CONTRACT = "top.zainzh.rearscreen.ITaskService";

    private static int transaction(String name) throws Exception {
        Field field = Class.forName(CONTRACT + "$Stub").getDeclaredField("TRANSACTION_" + name);
        field.setAccessible(true);
        return field.getInt(null);
    }

    public static void main(String[] args) throws Exception {
        Class<?> entry = Class.forName("top.zainzh.rearscreen.TaskService");
        IBinder binder = (IBinder) entry.getConstructor().newInstance();
        if (!CONTRACT.equals(binder.getInterfaceDescriptor())) throw new AssertionError("Binder descriptor changed");
        Class<?> api = Class.forName(CONTRACT);
        api.getMethod("destroy");
        api.getMethod("getCurrentForegroundApp");
        api.getMethod("setRearDpi", int.class);
        api.getMethod("setDisplayRotation", int.class, int.class);
        if (transaction("executeShellCommand") != IBinder.FIRST_CALL_TRANSACTION + 20
                || transaction("executeShellCommandWithResult") != IBinder.FIRST_CALL_TRANSACTION + 21)
            throw new AssertionError("Published AIDL transaction IDs changed");

        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        String uid;
        try {
            data.writeInterfaceToken(CONTRACT);
            data.writeString("id -u");
            if (!binder.transact(transaction("executeShellCommandWithResult"), data, reply, 0))
                throw new AssertionError("Shell result transaction was rejected");
            reply.readException();
            uid = reply.readString().trim();
            if (Integer.parseInt(uid) < 0) throw new AssertionError("Invalid UID result");
        } finally { data.recycle(); reply.recycle(); }

        data = Parcel.obtain(); reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CONTRACT);
            data.writeString("true");
            if (!binder.transact(transaction("executeShellCommand"), data, reply, 0))
                throw new AssertionError("Shell transaction was rejected");
            reply.readException();
            if (reply.readInt() != 1) throw new AssertionError("Shell boolean result changed");
        } finally { data.recycle(); reply.recycle(); }
        System.out.println("Release Binder contract OK; uid=" + uid + "; methods=" + api.getDeclaredMethods().length);
    }
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--serial", required=True, help="Dedicated Android test device")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    sdk = Path(os.environ.get("ANDROID_HOME", repo / ".local/android-sdk"))
    java_home = Path(os.environ.get("JAVA_HOME", repo / ".local/toolchains/jdk21"))
    version = lambda path: tuple(int(part) for part in re.findall(r"\d+", path.name))
    build_tools = max((sdk / "build-tools").iterdir(), key=version)
    platform = max((sdk / "platforms").glob("android-*"), key=version) / "android.jar"
    adb = [str(sdk / "platform-tools/adb"), "-s", args.serial]

    def run(command):
        result = subprocess.run([str(part) for part in command], capture_output=True, text=True, timeout=60)
        if result.returncode:
            raise RuntimeError(result.stdout + result.stderr)
        return result.stdout

    def shell(*command):
        return run(adb + ["shell", shlex.join(command)])

    remote = "/data/local/tmp/remrss-contract-" + uuid.uuid4().hex[:12]
    with tempfile.TemporaryDirectory(prefix="remrss-contract-") as temporary:
        temporary = Path(temporary)
        source = temporary / "ReleaseContractRunner.java"
        source.write_text(RUNNER)
        classes = temporary / "classes"
        classes.mkdir()
        run([java_home / "bin/javac", "-source", "11", "-target", "11", "-classpath", platform, "-d", classes, source])
        runner = temporary / "runner.jar"
        run([build_tools / "d8", "--lib", platform, "--min-api", "24", "--output", runner, *classes.rglob("*.class")])
        shell("mkdir", remote)
        try:
            run(adb + ["push", str(runner), remote + "/runner.jar"])
            run(adb + ["push", str(args.apk.resolve()), remote + "/app.apk"])
            shell("chmod", "444", remote + "/runner.jar", remote + "/app.apk")
            command = "CLASSPATH=" + shlex.quote(remote + "/runner.jar:" + remote + "/app.apk")
            command += " " + shlex.join(["app_process", "/system/bin", "top.zainzh.audit.ReleaseContractRunner"])
            print(run(adb + ["shell", command]).strip())
        finally:
            shell("rm", "-f", remote + "/runner.jar", remote + "/app.apk")
            shell("rmdir", remote)


if __name__ == "__main__":
    main()
