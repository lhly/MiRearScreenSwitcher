#!/usr/bin/env python3
"""Exercise real shell argument behavior without Android or root privileges."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = r'''package top.zainzh.rearscreen;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
public class ShellArgumentChecks {
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 static void reject(Runnable value){try{value.run();throw new AssertionError("unsafe input accepted");}catch(IllegalArgumentException expected){}}
 public static void main(String[] args)throws Exception{
  String file=args[0]+"/unexpected";
  String attack="$(printf unsafe > "+file+") `printf unsafe > "+file+"` \" ' ; & |\ntext";
  String command=PrivilegedShellArguments.notification("top.zainzh.rearscreen/.RearScreenNotificationActivity",attack,attack,attack,7);
  Process child=new ProcessBuilder("sh","-c","am(){ printf '%s\\000' \"$@\"; }; "+command).start();
  String result=new String(child.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
  check(child.waitFor()==0,"shell failed");check(!Files.exists(Path.of(file)),"argument executed as shell code");
  check(result.split(java.util.regex.Pattern.quote(attack),-1).length==4,"notification argument changed");
  for(String value:new String[]{"com.test;id","com.test$(id)","com.test`id`","com.test x","com.test\nwhoami","../com.test","-p"}){
   reject(()->PrivilegedShellArguments.validateTargets(value,null));
   reject(()->PrivilegedShellArguments.validateTargets(null,value+"/.Main"));
  }
  for(String value:new String[]{"com.test/.Main;id","com.test/.Main$(id)","com.test/.Main --es cmd x","com.test//.Main"})
   reject(()->PrivilegedShellArguments.validateTargets(null,value));
  PrivilegedShellArguments.validateTargets("com.test_app","com.test_app/.Main$Inner");
  PrivilegedShellArguments.validateTargets(null,null);
  reject(()->PrivilegedShellArguments.quote("a\0b"));
  String output="/storage/emulated/0/Movies/ReMRSS_20261102_120000.mp4";
  reject(()->PrivilegedShellArguments.stopRecording(-1,output,2));
  reject(()->PrivilegedShellArguments.stopRecording(123,output,9));
  reject(()->PrivilegedShellArguments.stopRecording(123,"/tmp/video;id",2));
  String guarded=PrivilegedShellArguments.stopRecording(123,output,2);
  check(guarded.contains("/proc/123/comm")&&guarded.contains("/proc/123/cmdline"),"recording signal lacks identity guards");
  System.out.println("PASS external targets, literal notification arguments, NUL and recording identity guards");
 }
}'''

with tempfile.TemporaryDirectory(prefix="remrss-shell-arguments-") as temp:
    target = Path(temp)
    source = target / "ShellArgumentChecks.java"
    source.write_text(SOURCE)
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin/java") if java_home else shutil.which("java")
    javac = str(Path(java_home) / "bin/javac") if java_home else shutil.which("javac")
    code = ROOT / "android/app/src/main/java/top/zainzh/rearscreen"
    subprocess.run([javac, "--release", "11", "-d", temp, str(source),
                    str(code / "PrivilegedShellArguments.java"), str(code / "VirtualCastRequest.java")], check=True)
    subprocess.run([java, "-cp", temp, "top.zainzh.rearscreen.ShellArgumentChecks", temp], check=True)
