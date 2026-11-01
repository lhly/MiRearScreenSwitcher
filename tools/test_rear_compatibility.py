#!/usr/bin/env python3
"""Exercise production selection policies using the two-panel baseline and changed hardware identities."""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JAVA = r'''package top.zainzh.rearscreen;
import java.util.*;
import java.nio.file.*;
public class CompatibilityChecks {
 static int checks;
 static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 static RearDisplayPolicy.Candidate panel(int id,int type,String name,String unique){return new RearDisplayPolicy.Candidate(id,type,name,unique,976,596);}
 static RearInputPolicy.Device touch(String path,String name,int x,int y,int association){
  RearInputPolicy.Device d=new RearInputPolicy.Device();d.path=path;d.name=name;d.x=d.y=d.slot=d.direct=true;d.external=0;d.slots=10;d.xmax=x;d.ymax=y;d.associatedDisplay=association;return d;
 }
 static RearInputPolicy.Device choose(RearInputPolicy.Device... devices){return RearInputPolicy.select(Arrays.asList(devices),7,1200,720,1440,3200);}
 public static void main(String[] args)throws Exception {
  RearDisplayPolicy.Candidate main=panel(0,1,"Built-in Screen","local:main"), rear=panel(1,1,"Built-in Screen","local:rear"), newer=panel(7,1,"Built-in Screen","local:new");
  RearDisplayPolicy.Candidate hdmi=panel(1,2,"Rear monitor","local:external"), virtual=panel(2,5,"Rear screen","virtual:2");
  check(RearDisplayPolicy.select(List.of(main,rear))==rear,"17 panel changed");
  check(RearDisplayPolicy.select(List.of(virtual,hdmi,newer,main))==newer,"dynamic ID or external exclusion failed");
  check(RearDisplayPolicy.select(List.of(main,hdmi,virtual))==null,"external/virtual selected as rear");
  check(RearDisplayPolicy.select(List.of(main,rear,newer))==null,"multiple internal panels guessed");
  RearDisplayPolicy.Candidate named=panel(9,1,"Rear display","local:named");
  check(RearDisplayPolicy.select(List.of(main,rear,named))==named,"unique rear label lost");
  check(RearDisplayPolicy.select(List.of(main,named,panel(8,1,"Subscreen","local:other")))==null,"multiple rear labels guessed");
  check(RearDisplayPolicy.select(List.of(panel(7,0,"Unknown","local:physical")))!=null,"physical unique ID fallback lost");
  check(RearDisplayPolicy.select(List.of(panel(1,0,"Rear display","")))==null,"name alone accepted as physical");
  List<RearInputPolicy.Device> baseline=RearInputPolicy.parse(Files.readString(Path.of(args[0])));
  check(baseline.size()==2,"17 input fixture parse");
  RearInputPolicy.Device original=RearInputPolicy.select(baseline,1,976,596,1200,2608);
  check(original!=null&&original.path.equals("/dev/input/event6"),"17 reader selection changed");
  check(original.xmax==97599&&original.ymax==59599&&original.slots==10,"17 raw coordinate ranges changed");
  RearInputPolicy.Device primary=touch("/dev/input/event8","Xiaomi_Touch_Input_0",143999,319999,0);
  RearInputPolicy.Device renamed=touch("/dev/input/event201","vendor touchscreen",119999,71999,7);
  check(choose(primary,renamed)==renamed,"associated renamed rear or event>127 failed");
  check(choose(primary)==null,"main touch grabbed");
  check(choose(touch("/dev/input/event1","Xiaomi_Touch_Input_1",119999,71999,0))==null,"main association overridden by legacy name");
  check(choose(touch("/dev/input/event1","rear_touch",119999,71999,8))==null,"other display input accepted");
  check(choose(touch("/dev/input/event1","rear_touch",119999,71999,-2))==null,"conflicting association accepted");
  RearInputPolicy.Device semantic=touch("/dev/input/event2","vendor-rear-touch",119999,71999,-1);
  check(choose(primary,semantic)==semantic,"semantic renamed rear rejected");
  RearInputPolicy.Device geometry=touch("/dev/input/event2","vendor_touch",119999,71999,-1);
  check(choose(primary,geometry)==geometry,"distinct rear geometry rejected");
  check(choose(touch("/dev/input/event2","vendor_touch",143999,319999,-1))==null,"unassociated main geometry accepted");
  check(choose(geometry,touch("/dev/input/event3","other_touch",119999,71999,-1))==null,"ambiguous rear geometry guessed");
  check(choose(semantic,touch("/dev/input/event3","rear_touch",119999,71999,-1))==null,"ambiguous semantic name guessed");
  geometry.external=1;check(choose(geometry)==null,"external USB touchscreen accepted");geometry.external=-1;
  check(choose(geometry)==null,"unknown input geometry alone accepted");geometry.external=0;
  geometry.direct=false;check(choose(geometry)==null,"indirect touchpad accepted");geometry.direct=true;
  renamed.slots=17;check(choose(renamed)==null,"unsupported slots accepted");renamed.slots=10;
  renamed.xmax=0;check(choose(renamed)==null,"invalid coordinates accepted");renamed.xmax=119999;
  check(RearInputPolicy.select(List.of(renamed),0,1200,720,1440,3200)==null,"main selected as rear ID");
  check(RearInputPolicy.select(List.of(renamed),7,0,0,1440,3200)==null,"unknown display geometry accepted");
  geometry.associatedDisplay=-1;
  RearInputPolicy.associate(List.of(geometry),"  Device 8: vendor_touch\n    Viewport INTERNAL: displayId=7, uniqueId=local:new\n");
  check(geometry.associatedDisplay==7,"viewport association parse failed");
  RearInputPolicy.associate(List.of(geometry),"  Device 8: vendor_touch\n    Viewport INTERNAL: displayId=0, uniqueId=local:main\n");
  check(geometry.associatedDisplay==-2,"conflicting viewport association lost");
  System.out.println("PASS "+checks+" compatibility checks: 17 baseline, changed IDs/names, external exclusion, associations, geometry and ambiguity");
 }
}'''

with tempfile.TemporaryDirectory() as directory:
    temporary = Path(directory)
    source = temporary / "CompatibilityChecks.java"
    source.write_text(JAVA)
    jdk = Path(os.environ.get("JAVA_HOME", ROOT / ".local/toolchains/jdk21")) / "bin"
    code = ROOT / "android/app/src/main/java/top/zainzh/rearscreen"
    subprocess.run([str(jdk / "javac"), "-d", directory, str(code / "RearDisplayPolicy.java"), str(code / "RearInputPolicy.java"), str(source)], check=True)
    subprocess.run([str(jdk / "java"), "-cp", directory, "top.zainzh.rearscreen.CompatibilityChecks", str(ROOT / "tools/fixtures/rear-input-17-pro-max.txt")], check=True)
