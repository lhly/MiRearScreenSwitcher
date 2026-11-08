#!/usr/bin/env python3
"""Check URI target validation and scoped stop selectors without Android/ADB."""
from pathlib import Path
import os,subprocess,tempfile,shutil
ROOT=Path(__file__).resolve().parents[1]
JAVA=Path(os.environ.get('JAVA_HOME',ROOT/'.local/toolchains/jdk21'))/'bin'
HARNESS=r'''package top.zainzh.rearscreen;
public final class VirtualRequestChecks {
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 static void rejected(Runnable r){try{r.run();throw new AssertionError("accepted invalid input");}catch(IllegalArgumentException expected){}}
 public static void main(String[] args){
  check(VirtualCastRequest.startsProjection(null,"com.example.app:42",null));
  check(VirtualCastRequest.startsProjection(null,null,"session-1"));
  check(!VirtualCastRequest.startsProjection(null,null,null));
  check(!VirtualCastRequest.startsProjection(null,"",""));
  check(!VirtualCastRequest.startsProjection("ACTION_RETURN_TO_MAIN",null,"old-session"));
  check(!VirtualCastRequest.startsProjection("ACTION_SET_KEEP_SCREEN_ON_ENABLED","com.example.app:42",null));
  check(!VirtualCastRequest.startsProjection("ACTION_SET_PROXIMITY_ENABLED",null,null));
  String host="top.zainzh.rearscreen";
  check(VirtualCastRequest.isReplaceableRearOwner(host+":55",host));
  check(VirtualCastRequest.isReplaceableRearOwner("com.xiaomi.subscreencenter:12",host));
  check(!VirtualCastRequest.isReplaceableRearOwner("com.example.app:55",host));
  check(!VirtualCastRequest.isReplaceableRearOwner(host+".other:55",host));
  check(!VirtualCastRequest.isReplaceableRearOwner("com.xiaomi.subscreencenter.other:12",host));
  check(!VirtualCastRequest.isReplaceableRearOwner(null,host)&&!VirtualCastRequest.isReplaceableRearOwner("",host));
  check(VirtualCastRequest.yes("true")&&VirtualCastRequest.yes("TRUE")&&VirtualCastRequest.yes("1")&&!VirtualCastRequest.yes(null));
  check(VirtualCastRequest.density(null)==200&&VirtualCastRequest.density("72")==72&&VirtualCastRequest.density("640")==640);
  check(VirtualCastRequest.density("240")==240&&VirtualCastRequest.density("320")==320);
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
  System.out.println("PASS exact rear-owner gates, URI validation, independent density, rotation rejection, exact task/package stop selectors");
 }
}'''
with tempfile.TemporaryDirectory(prefix='mrss-vd-request-') as folder:
 out=Path(folder);test=out/'VirtualRequestChecks.java';test.write_text(HARNESS)
 capability=out/'RearCapabilityCore.java';capability.parent.mkdir(parents=True,exist_ok=True);capability.write_text('package top.zainzh.rearscreen.platform; public final class RearCapabilityCore { public static boolean isActiveSceneTaskForeground(String s,int d){return false;} public static boolean hasActiveScenePackage(String s){return false;} public static boolean isActiveSceneTask(int t,int d){return false;} }')
 subprocess.run([str(JAVA/'javac'),'-d',str(out),str(capability),str(ROOT/'android/app/src/main/java/top/zainzh/rearscreen/VirtualCastRequest.java'),str(test)],check=True)
 subprocess.run([str(JAVA/'java'),'-cp',str(out),'top.zainzh.rearscreen.VirtualRequestChecks'],check=True)
