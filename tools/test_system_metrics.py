#!/usr/bin/env python3
"""Check counter semantics, reset recovery and visibility gates without a device."""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
HARNESS = r'''package top.zainzh.rearscreen;
public class SystemMetricChecks {
 static int checks;
 static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 static void near(double actual,double expected){check(Math.abs(actual-expected)<0.0001,"expected "+expected+" got "+actual);}
 static void missing(double value){check(Double.isNaN(value),"invalid sample became a value: "+value);}
 public static void main(String[] args){
  long[] a=SystemMetricMath.cpuTicks("cpu 100 20 30 400 50 10 5 1 80 10");
  long[] b=SystemMetricMath.cpuTicks("cpu 110 20 35 470 60 12 7 2 100 20");
  near(SystemMetricMath.cpuPercent(a,b),20);
  missing(SystemMetricMath.cpuPercent(a,a));missing(SystemMetricMath.cpuPercent(b,a));
  missing(SystemMetricMath.cpuPercent(null,b));
  check(SystemMetricMath.cpuTicks("cpu0 1 2 3 4 5 6 7 8")==null,"accepted per-core as total");
  for(String bad:new String[]{null,"","permission denied","cpu 1 2","cpu -1 2 3 4 5 6 7 8","cpu 99999999999999999999 0 0 0 0 0 0 0"})
   check(SystemMetricMath.cpuTicks(bad)==null,"accepted invalid cpu");
  missing(SystemMetricMath.cpuPercent(new long[8],new long[]{Long.MAX_VALUE,1,0,0,0,0,0,0}));
  near(SystemMetricMath.gpuPercent(" 20177 1002893 "),2.0118784);
  near(SystemMetricMath.gpuPercent("0 100"),0);near(SystemMetricMath.gpuPercent("100 100"),100);
  for(String bad:new String[]{null,"0 0","-1 100","101 100","1 2 3","permission denied","1 -5"}) missing(SystemMetricMath.gpuPercent(bad));
  near(SystemMetricMath.bytesPerSecond(1000,5500,3000),1500);
  near(SystemMetricMath.bytesPerSecond(1000,1000,2000),0);
  missing(SystemMetricMath.bytesPerSecond(-1,1000,2000));missing(SystemMetricMath.bytesPerSecond(1000,10,2000));
  missing(SystemMetricMath.bytesPerSecond(0,10,0));missing(SystemMetricMath.bytesPerSecond(0,10,-1));
  for(int flags=0;flags<16;flags++) check(SystemMetricMath.maySample((flags&1)!=0,(flags&2)!=0,(flags&4)!=0,(flags&8)!=0)==(flags==7),"visibility gate "+flags);
  for(long ms:new long[]{-1,0,7999})check(SystemMetricMath.refreshIntervalMs(ms)==2000,"early cadence "+ms);
  for(long ms:new long[]{8000,600000})check(SystemMetricMath.refreshIntervalMs(ms)==5000,"idle cadence "+ms);
  RearInfoSession.attach(42);check(RearInfoSession.owns("app:42","app"),"panel not recorded");
  check(!RearInfoSession.owns("other:42","app"),"wrong package accepted");
  RearInfoSession.attach(43);RearInfoSession.detach(42);check(RearInfoSession.owns("app:43","app"),"old activity detached replacement");
  RearInfoSession.detach(43);check(!RearInfoSession.owns("app:43","app"),"destroyed panel retained");
  System.out.println("PASS "+checks+" metric and visibility checks");
 }
}'''

with tempfile.TemporaryDirectory(prefix="remrss-metrics-") as temporary:
    directory = Path(temporary)
    harness = directory / "SystemMetricChecks.java"
    harness.write_text(HARNESS)
    java = Path(os.environ.get("JAVA_HOME", ROOT / ".local/toolchains/jdk21")) / "bin"
    source = ROOT / "android/app/src/main/java/top/zainzh/rearscreen"
    subprocess.run([str(java / "javac"), "-d", temporary, str(harness),
                    str(source / "SystemMetricMath.java"), str(source / "RearInfoSession.java")], check=True)
    subprocess.run([str(java / "java"), "-cp", temporary, "top.zainzh.rearscreen.SystemMetricChecks"], check=True)
