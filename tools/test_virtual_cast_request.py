#!/usr/bin/env python3
"""Check URI target validation and scoped stop selectors without Android/ADB."""
from pathlib import Path
import subprocess,tempfile,shutil
ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/'.local/toolchains/jdk21/bin'
HARNESS=r'''package top.zainzh.rearscreen;
public final class VirtualRequestChecks {
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 static void rejected(Runnable r){try{r.run();throw new AssertionError("accepted invalid input");}catch(IllegalArgumentException expected){}}
 public static void main(String[] args){
  check(VirtualCastRequest.yes("true")&&VirtualCastRequest.yes("TRUE")&&VirtualCastRequest.yes("1")&&!VirtualCastRequest.yes(null));
  check(VirtualCastRequest.density(null)==240&&VirtualCastRequest.density("72")==72&&VirtualCastRequest.density("640")==640);
  rejected(()->VirtualCastRequest.density("71"));rejected(()->VirtualCastRequest.density("641"));rejected(()->VirtualCastRequest.density("abc"));
  VirtualCastRequest.rotation(null);VirtualCastRequest.rotation("0");rejected(()->VirtualCastRequest.rotation("1"));
  check(VirtualCastRequest.component("tv.danmaku.bili/.MainActivity").equals("tv.danmaku.bili/.MainActivity"));
  VirtualCastRequest.component("com.example.app/com.example.Outer$Inner");
  for(String value:new String[]{"com.foo;id", "com.foo bar", "com.foo'", "com.foo/abc", "com.foo$(id)"})rejected(()->VirtualCastRequest.packageName(value));
  for(String value:new String[]{"com.foo/.A;id", "com.foo/.A extra", "com.foo/", "com.foo/.A/B"})rejected(()->VirtualCastRequest.component(value));
  check(VirtualCastRequest.matchesReturn("1",null,null,10,11,"com.foo","com.child"));
  check(VirtualCastRequest.matchesReturn(null,"10",null,10,11,"com.foo","com.child"));
  check(VirtualCastRequest.matchesReturn(null,"11",null,10,11,"com.foo","com.child"));
  check(!VirtualCastRequest.matchesReturn(null,"1",null,10,11,"com.foo","com.child"));
  check(!VirtualCastRequest.matchesReturn(null,"-1",null,10,-1,"com.foo","com.child"));
  check(VirtualCastRequest.matchesReturn(null,null,"com.foo",10,11,"com.foo","com.child"));
  check(VirtualCastRequest.matchesReturn(null,null,"com.child",10,11,"com.foo","com.child"));
  check(!VirtualCastRequest.matchesReturn(null,null,"com.other",10,11,"com.foo","com.child"));
  check(VirtualCastRequest.matchesReturn(null,null,null,10,11,"com.foo","com.child"));
  rejected(()->VirtualCastRequest.matchesReturn(null,"10;id",null,10,11,"com.foo","com.child"));
  System.out.println("PASS URI validation, independent density, rotation rejection, exact task/package stop selectors");
 }
}'''
with tempfile.TemporaryDirectory(prefix='mrss-vd-request-') as folder:
 out=Path(folder);test=out/'VirtualRequestChecks.java';test.write_text(HARNESS)
 subprocess.run([str(JAVA/'javac'),'-d',str(out),str(ROOT/'android/app/src/main/java/top/zainzh/rearscreen/VirtualCastRequest.java'),str(test)],check=True)
 subprocess.run([str(JAVA/'java'),'-cp',str(out),'top.zainzh.rearscreen.VirtualRequestChecks'],check=True)
