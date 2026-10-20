#!/usr/bin/env python3
"""Exercise per-App defaults, independent updates, restart reads and disabled-feature isolation."""
from pathlib import Path
import os, subprocess, tempfile
ROOT = Path(__file__).resolve().parents[1]
STUBS = {
    'android/content/SharedPreferences.java': '''package android.content; public interface SharedPreferences {
 int getInt(String k,int fallback);boolean getBoolean(String k,boolean fallback);Editor edit();
 interface Editor {Editor putInt(String k,int v);Editor putBoolean(String k,boolean v);void apply();}}
''',
    'android/content/Context.java': '''package android.content;import java.util.*;public class Context {
 public static final int MODE_PRIVATE=0;static final Map<String,Map<String,Object>> disk=new HashMap<>();
 public SharedPreferences getSharedPreferences(String name,int mode){Map<String,Object> data=disk.computeIfAbsent(name,k->new HashMap<>());
 return new SharedPreferences(){public int getInt(String k,int v){return (Integer)data.getOrDefault(k,v);}
 public boolean getBoolean(String k,boolean v){return (Boolean)data.getOrDefault(k,v);}
 public Editor edit(){return new Editor(){Map<String,Object> pending=new HashMap<>();public Editor putInt(String k,int v){pending.put(k,v);return this;}
 public Editor putBoolean(String k,boolean v){pending.put(k,v);return this;}public void apply(){data.putAll(pending);}};}};}}
''',
    'PresentationTest.java': '''package top.zainzh.rearscreen;import android.content.Context;public class PresentationTest {
 static void eq(Context c,String pkg,int rotation,boolean full){RearPresentationPreferences.Selection p=RearPresentationPreferences.read(c,pkg);
  if(p.rotation!=rotation||p.full!=full)throw new AssertionError(pkg+" "+p.rotation+" "+p.full);}
 public static void main(String[] args){Context c=new Context();String a="com.example.reader",b="com.example.video";
  eq(c,a,0,false);eq(c,b,0,false);
  RearPresentationPreferences.saveRotation(c,a,3);RearPresentationPreferences.saveViewport(c,a,true);
  if(!BuildConfig.VIRTUAL_CAST_ENABLED){eq(c,a,0,false);
   if(RearPresentationPreferences.preferences(c).getInt("rotation:"+a,-1)!=-1||RearPresentationPreferences.preferences(c).getBoolean("full:"+a,false))throw new AssertionError("Disabled feature wrote preferences");
   System.out.println("PASS disabled feature ignores presentation and performs no writes");return;}
  eq(new Context(),a,3,true);eq(c,b,0,false);RearPresentationPreferences.saveRotation(c,b,1);eq(c,b,1,false);eq(c,a,3,true);
  RearPresentationPreferences.saveViewport(c,a,false);eq(c,a,3,false);RearPresentationPreferences.saveRotation(c,a,0);eq(new Context(),a,0,false);
  for(int r=0;r<4;r++)for(boolean full:new boolean[]{false,true}){RearPresentationPreferences.saveRotation(c,a,r);RearPresentationPreferences.saveViewport(c,a,full);eq(new Context(),a,r,full);}
  boolean rejected=false;try{RearPresentationPreferences.saveRotation(c,a,4);}catch(IllegalArgumentException expected){rejected=true;}if(!rejected)throw new AssertionError("Invalid rotation accepted");
  rejected=false;try{RearPresentationPreferences.saveViewport(c,"bad/../key",true);}catch(IllegalArgumentException expected){rejected=true;}if(!rejected)throw new AssertionError("Invalid package accepted");
  RearPresentationPreferences.preferences(c).edit().putInt("rotation:"+a,9).apply();eq(c,a,0,true);
  System.out.println("PASS presentation defaults, four rotations/full-safe, independent fields/apps, explicit defaults, cold reads and validation");
 }}'''
}
jdk = Path(os.environ.get('JAVA_HOME', ROOT / '.local/toolchains/jdk21')) / 'bin'
src = ROOT / 'android/app/src/main/java/top/zainzh/rearscreen'
for enabled in [True, False]:
    with tempfile.TemporaryDirectory() as tmp:
        out = Path(tmp); files = []
        for name, content in STUBS.items():
            p = out / name; p.parent.mkdir(parents=True, exist_ok=True); p.write_text(content); files.append(str(p))
        p = out / 'BuildConfig.java'; p.write_text('package top.zainzh.rearscreen;final class BuildConfig {static final boolean VIRTUAL_CAST_ENABLED=' + str(enabled).lower() + ';}'); files.append(str(p))
        subprocess.run([str(jdk/'javac'), '-d', tmp, *files, *[str(src/name) for name in ['RearPresentationPreferences.java', 'RearCastViewport.java', 'VirtualCastRequest.java']]], check=True)
        subprocess.run([str(jdk/'java'), '-cp', tmp, 'top.zainzh.rearscreen.PresentationTest'], check=True)
