#!/usr/bin/env python3
"""Run the real geometry reader against valid, absent, missing and invalid platform metadata."""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
STUBS = {
    "android/os/Build.java": "package android.os; public class Build {public static class VERSION {public static int SDK_INT=37;}}",
    "android/graphics/Insets.java": "package android.graphics; public class Insets {public int left,top,right,bottom;}",
    "android/view/DisplayCutout.java": """package android.view; public class DisplayCutout {
      public int left,top,right,bottom; public int getSafeInsetLeft(){return left;} public int getSafeInsetTop(){return top;}
      public int getSafeInsetRight(){return right;} public int getSafeInsetBottom(){return bottom;}
      public android.graphics.Insets getWaterfallInsets(){return new android.graphics.Insets();}
    }""",
    "android/hardware/display/DisplayManagerGlobal.java": """package android.hardware.display;
    public class DisplayManagerGlobal {public static Object info;public static int requested=-1;
      public static DisplayManagerGlobal getInstance(){return new DisplayManagerGlobal();}
      public Object getDisplayInfo(int id){requested=id;return info;}}
    """,
    "org/json/JSONObject.java": """package org.json; public class JSONObject {
      public JSONObject(){} public JSONObject(String s){} public JSONObject put(String k,Object v){return this;}
      public int getInt(String k){return 0;} public int optInt(String k,int v){return v;} public String toString(){return "{}";}}
    """,
    "top/zainzh/rearscreen/RearDisplay.java": "package top.zainzh.rearscreen; public class RearDisplay {public static int requireId(){return 7;}}",
    "top/zainzh/rearscreen/CompatibilityDiagnostics.java": "package top.zainzh.rearscreen; public class CompatibilityDiagnostics {static void record(String a,String b){}}",
    "GeometryDiscoveryChecks.java": r'''package top.zainzh.rearscreen;
      import android.hardware.display.DisplayManagerGlobal;
      import android.view.DisplayCutout;
      public class GeometryDiscoveryChecks {
       public static class Info { public int logicalWidth=976,logicalHeight=596,rotation=0,logicalDensityDpi=450;public DisplayCutout displayCutout; }
       public static class Missing { public int logicalWidth=976,logicalHeight=596,rotation=0,logicalDensityDpi=450; }
       interface Action {void run()throws Exception;}
       static int checks;
       static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
       static void fails(Action a,String m)throws Exception{try{a.run();throw new AssertionError(m);}catch(ReflectiveOperationException|java.io.IOException|IllegalArgumentException expected){checks++;}}
       public static void main(String[] args)throws Exception {
        Info i=new Info();i.displayCutout=new DisplayCutout();i.displayCutout.left=296;DisplayManagerGlobal.info=i;
        RearCastViewport v=RearCastGeometry.read();check(v.left==296&&v.width()==680&&v.height()==596,"17 safe area changed");
        check(DisplayManagerGlobal.requested==7,"fixed display ID still used");
        i.displayCutout=null;v=RearCastGeometry.read();check(v.isFull()&&v.width()==976&&v.height()==596,"explicit absent cutout rejected");
        DisplayManagerGlobal.info=new Missing();fails(()->RearCastGeometry.read(),"missing metadata became no cutout");
        DisplayManagerGlobal.info=null;fails(()->RearCastGeometry.read(),"missing display accepted");
        DisplayManagerGlobal.info=i;i.logicalWidth=0;fails(()->RearCastGeometry.read(),"invalid size accepted");i.logicalWidth=976;
        i.logicalDensityDpi=0;fails(()->RearCastGeometry.read(),"invalid density accepted");i.logicalDensityDpi=450;
        i.rotation=1;fails(()->RearCastGeometry.read(),"unprepared physical rotation accepted");i.rotation=0;
        i.displayCutout=new DisplayCutout();i.displayCutout.left=1000;fails(()->RearCastGeometry.read(),"invalid obstruction accepted");
        android.os.Build.VERSION.SDK_INT=30;fails(()->RearCastGeometry.read(),"old system accepted");
        System.out.println("PASS "+checks+" geometry discovery checks: 17 obstruction, dynamic ID, explicit null, unknown metadata and invalid geometry");
       }
      }''',
}
with tempfile.TemporaryDirectory() as directory:
    temporary = Path(directory)
    files = []
    for name, content in STUBS.items():
        p = temporary / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content)
        files.append(str(p))
    jdk = Path(os.environ.get("JAVA_HOME", ROOT / ".local/toolchains/jdk21")) / "bin"
    code = ROOT / "android/app/src/main/java/top/zainzh/rearscreen"
    subprocess.run([str(jdk / "javac"), "-d", directory, *files, str(code / "RearCastGeometry.java"), str(code / "RearCastViewport.java")], check=True)
    subprocess.run([str(jdk / "java"), "-cp", directory, "top.zainzh.rearscreen.GeometryDiscoveryChecks"], check=True)
