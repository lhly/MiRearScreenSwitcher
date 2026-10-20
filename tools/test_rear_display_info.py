#!/usr/bin/env python3
"""Exercise real display parsing and cache recovery without Android or a connected phone."""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
STUBS = {
    "android/graphics/Rect.java": """package android.graphics;
public class Rect { public int left,top,right,bottom;
 public Rect(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}
} """,
    "android/util/Log.java": """package android.util;
public class Log {
 public static int d(String t,String m){return 0;}
 public static int w(String t,String m){return 0;}
 public static int w(String t,String m,Throwable e){return 0;}
 public static int e(String t,String m,Throwable e){return 0;}
} """,
    "top/zainzh/rearscreen/ITaskService.java": """package top.zainzh.rearscreen;
interface ITaskService { String executeShellCommandWithResult(String command) throws Exception; }
""",
}
HARNESS = r'''package top.zainzh.rearscreen;
import java.io.IOException;
public class RearDisplayInfoChecks {
 static int checks;
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
 static String viewport(int id,String unique,int width,int height,String density){
  return "DisplayViewport{valid=true, displayId="+id+(unique==null?"":", uniqueId='"+unique+"'")
    +density+", deviceWidth="+width+", deviceHeight="+height+"}";
 }
 static String device(String unique,int width,int height,String density,String cutout,String filler){
  return "DisplayDeviceInfo{\"display } label\": uniqueId=\""+unique+"\", "+width+" x "+height
    +", "+filler+density+", "+cutout+"}";
 }
 static String good(){return viewport(1,"rear-dynamic",880,440,", densityDpi=320")+"\n"
   +device("main",880,440,"density 500","cutout DisplayCutout{insets=Rect(0, 60 - 0, 0)}","")+"\n"
   +device("rear-dynamic",880,440,"density 400","cutout DisplayCutout{insets=Rect(180, 0 - 0, 0)}","");}
 static RearDisplayHelper.RearDisplayInfo parse(String value){return RearDisplayHelper.parseRearDisplayInfo(value);}
 static class Service implements ITaskService {
  String value; boolean fails;int calls;
  public String executeShellCommandWithResult(String command)throws Exception{
   check(command.equals("dumpsys display"),"unexpected shell command");calls++;
   if(fails)throw new IOException("disconnected");return value;
  }
 }
 public static void main(String[] args){
  check(!new RearDisplayHelper.RearDisplayInfo().isValid(),"unread info appeared valid");
  for(String invalid:new String[]{null,"","permission denied",viewport(11,"rear-dynamic",880,440,", densityDpi=320")})
   check(parse(invalid)==null,"invalid/missing viewport accepted");
  RearDisplayHelper.RearDisplayInfo info=parse(good());
  check(info!=null&&info.width==880&&info.height==440&&info.densityDpi==320&&info.cutout.left==180,"wrong display or override density");
  String longModes="supportedModes [{id=1, data={value=\"brace } {\"}}], "+" ".repeat(6000);
  info=parse(viewport(1,"other-rear",777,333,"")+device("other-rear",777,333,"density 280",
    "cutout DisplayCutout{insets=Rect(12, 3, 4, 5)}",longModes));
  check(info!=null&&info.width==777&&info.densityDpi==280&&info.cutout.top==3,"long device block or standard cutout lost");
  String noCutout=viewport(1,"rear-dynamic",880,440,", densityDpi=320")
    +device("rear-dynamic",880,440,"density 400","cutout null","");
  check(parse(noCutout)!=null&&!parse(noCutout).hasCutout(),"confirmed no-cutout rejected");
  check(parse(good().replace("rear-dynamic'","not-present'"))==null,"wrong identity fell back to same-size main display");
  check(parse(good().replace(", uniqueId='rear-dynamic'",""))==null,"ambiguous same-size fallback accepted");
  check(parse(noCutout.replace(", uniqueId='rear-dynamic'",""))!=null,"unique dimension fallback failed");
  check(parse(good().replace("valid=true, displayId=1","valid=false, displayId=1"))==null,"invalid viewport accepted");
  check(parse(good().replace("densityDpi=320","densityDpi=0"))==null,"invalid override silently replaced");
  check(parse(good().replace(", densityDpi=320","").replace("density 400","unknown"))==null,"unknown density guessed");
  check(parse(good().replace("Rect(180, 0 - 0, 0)","Rect(880, 0 - 0, 0)"))==null,"invalid safe area accepted");
  check(parse(good().replace("cutout DisplayCutout{insets=Rect(180, 0 - 0, 0)}","no metadata"))==null,"unknown cutout treated as absent");
  check(parse(good().replace("deviceWidth=880","deviceWidth=0"))==null,"zero size accepted");
  check(parse(noCutout.substring(0,noCutout.length()-1))==null,"truncated device record accepted");
  check(RearDisplayHelper.getRearDisplayInfo(null)==null,"missing service returned guessed info");
  DisplayInfoCache cache=DisplayInfoCache.getInstance();cache.clear();
  check(cache.getCachedInfo()==null&&!cache.isInitialized(),"uninitialized cache had guesses");
  Service service=new Service();service.value="permission denied";cache.initialize(service);
  check(!cache.isInitialized()&&cache.getCachedInfo()==null,"failed read poisoned cache");
  service.value=good();cache.initialize(service);
  check(cache.isInitialized()&&cache.getCachedInfo().cutout.left==180&&service.calls==2,"read did not recover");
  cache.initialize(service);check(service.calls==2,"successful cache not reused");
  service.fails=true;cache.refresh(service);
  check(!cache.isInitialized()&&cache.getCachedInfo()==null,"failed refresh exposed stale/guessed data");
  service.fails=false;service.value=noCutout;cache.initialize(service);
  check(cache.isInitialized()&&!cache.getCachedInfo().hasCutout(),"refresh retry did not recover");
  cache.clear();cache.initialize(null);check(!cache.isInitialized()&&cache.getCachedInfo()==null,"null service cached");
  if(args.length>0){try{check(parse(java.nio.file.Files.readString(java.nio.file.Path.of(args[0])))!=null,"recorded device dump failed");}
   catch(IOException e){throw new AssertionError(e);}}
  System.out.println("PASS "+checks+" display checks: exact identity, dynamic geometry, current density, complete blocks, unknown rejection, cache recovery and refresh");
 }
}'''

with tempfile.TemporaryDirectory(prefix="remrss-display-info-") as temporary:
    folder = Path(temporary)
    sources = []
    for name, text in STUBS.items():
        path = folder / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        sources.append(str(path))
    harness = folder / "RearDisplayInfoChecks.java"
    harness.write_text(HARNESS)
    production = ROOT / "android/app/src/main/java/top/zainzh/rearscreen"
    java = Path(os.environ.get("JAVA_HOME", ROOT / ".local/toolchains/jdk21")) / "bin"
    subprocess.run([str(java / "javac"), "-d", temporary, *sources, str(harness),
                    str(production / "RearDisplayHelper.java"), str(production / "DisplayInfoCache.java")], check=True)
    command = [str(java / "java"), "-cp", temporary, "top.zainzh.rearscreen.RearDisplayInfoChecks"]
    # Optional local evidence is never copied into test fixtures or the repository.
    snapshot = os.environ.get("MRSS_DISPLAY_DUMP")
    if snapshot:
        command.append(snapshot)
    subprocess.run(command, check=True)
