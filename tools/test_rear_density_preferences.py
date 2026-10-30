#!/usr/bin/env python3
"""Contract checks for uniform defaults, existing choices, per-App persistence and disabled-feature isolation."""
from pathlib import Path
import os, subprocess, tempfile
ROOT=Path(__file__).resolve().parents[1]
STUBS={
'android/content/SharedPreferences.java': '''package android.content; public interface SharedPreferences {
 boolean getBoolean(String key,boolean fallback); int getInt(String key,int fallback); Editor edit();
 interface Editor { Editor putBoolean(String key,boolean value);Editor putInt(String key,int value);Editor remove(String key);void apply(); }
}''',
'android/content/Context.java': '''package android.content;import java.util.*; public class Context {
 public static final int MODE_PRIVATE=0;static final Map<String,Map<String,Object>> disk=new HashMap<>();
 public SharedPreferences getSharedPreferences(String name,int mode){Map<String,Object> data=disk.computeIfAbsent(name,k->new HashMap<>());
 return new SharedPreferences(){public boolean getBoolean(String k,boolean fallback){return (Boolean)data.getOrDefault(k,fallback);}
 public int getInt(String k,int fallback){return (Integer)data.getOrDefault(k,fallback);}
 public Editor edit(){return new Editor(){Map<String,Object> pending=new HashMap<>();public Editor putBoolean(String k,boolean v){pending.put(k,v);return this;}
 public Editor putInt(String k,int v){pending.put(k,v);return this;}public Editor remove(String k){pending.put(k,null);return this;}
 public void apply(){for(Map.Entry<String,Object> e:pending.entrySet()){if(e.getValue()==null)data.remove(e.getKey());else data.put(e.getKey(),e.getValue());}}};}};}
}''',
'PrefsTest.java': '''package top.zainzh.rearscreen;import android.content.Context; public class PrefsTest {
 static void eq(boolean a,boolean b){if(a!=b)throw new AssertionError(a+" != "+b);}
 public static void main(String[] args){Context a=new Context();String first="com.example.reader",other="com.example.video",third="com.example.browser";
  if(!BuildConfig.VIRTUAL_CAST_ENABLED){
   RearDensityPreferences.preferences(a).edit().putBoolean(RearDensityPreferences.key(first),true).apply();
   eq(RearDensityPreferences.enabled(a,first),false);RearDensityPreferences.setEnabled(a,other,true);eq(RearDensityPreferences.enabled(a,other),false);
   if(RearDensityPreferences.preferences(a).getBoolean(RearDensityPreferences.key(other),false))throw new AssertionError("Disabled feature wrote preference");
   RearDensityPreferences.setLayoutDensity(a,other,240);
   if(RearDensityPreferences.layoutDensity(a,other)!=0)throw new AssertionError("Disabled feature wrote DPI");
   System.out.println("PASS disabled feature ignores compatibility preferences and writes");return;
  }
  for(String pkg:new String[]{first,other,third})eq(RearDensityPreferences.enabled(a,pkg),false);
  // Simulate choices already stored by an earlier installed version.
  RearDensityPreferences.preferences(a).edit().putBoolean("enabled:"+first,true).putBoolean("enabled:"+other,false).apply();
  eq(RearDensityPreferences.enabled(a,first),true);eq(RearDensityPreferences.enabled(a,other),false);
  RearDensityPreferences.setEnabled(a,first,false);RearDensityPreferences.setEnabled(a,other,true);
  Context afterRestart=new Context();eq(RearDensityPreferences.enabled(afterRestart,first),false);eq(RearDensityPreferences.enabled(afterRestart,other),true);
  eq(RearDensityPreferences.enabled(afterRestart,third),false);RearDensityPreferences.setEnabled(afterRestart,other,false);
  eq(RearDensityPreferences.enabled(a,other),false);eq(RearDensityPreferences.enabled(a,first),false);
  RearDensityPreferences.setEnabled(afterRestart,first,true);eq(RearDensityPreferences.enabled(a,first),true);
  if(VirtualCastRequest.density(null)!=200||RearDensityPreferences.layoutDensity(a,first)!=0)throw new AssertionError("Default layout density changed");
  RearDensityPreferences.setLayoutDensity(a,first,240);RearDensityPreferences.setLayoutDensity(a,other,320);
  if(RearDensityPreferences.layoutDensity(new Context(),first)!=240||RearDensityPreferences.layoutDensity(new Context(),other)!=320)throw new AssertionError("Custom DPI lost after default update");
  RearDensityPreferences.setEnabled(a,first,false);
  if(RearDensityPreferences.layoutDensity(a,first)!=240)throw new AssertionError("Disabling compatibility cleared custom DPI");
  RearDensityPreferences.setLayoutDensity(a,first,0);
  if(RearDensityPreferences.layoutDensity(a,first)!=0||RearDensityPreferences.layoutDensity(a,other)!=320)throw new AssertionError("Reset changed another App");
  RearDensityPreferences.preferences(a).edit().putBoolean("dpi:"+first,true).apply();
  if(RearDensityPreferences.layoutDensity(a,first)!=0)throw new AssertionError("Corrupt DPI did not fall back");
  boolean rejected=false;try{RearDensityPreferences.setEnabled(a,"invalid/../key",true);}catch(IllegalArgumentException expected){rejected=true;}
  if(!rejected)throw new AssertionError("Invalid package accepted");
  System.out.println("PASS default200, existing ON/OFF and custom DPI retained, reset/corruption fallback, independent durable updates and package validation");
 }
}'''}
jdk=Path(os.environ.get('JAVA_HOME',ROOT/'.local/toolchains/jdk21'))/'bin';src=ROOT/'android/app/src/main/java/top/zainzh/rearscreen'
for enabled in [True,False]:
 with tempfile.TemporaryDirectory() as tmp:
  out=Path(tmp);files=[]
  for name,text in STUBS.items():p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text);files.append(str(p))
  p=out/'BuildConfig.java';p.write_text('package top.zainzh.rearscreen;final class BuildConfig {static final boolean VIRTUAL_CAST_ENABLED='+str(enabled).lower()+';}');files.append(str(p))
  subprocess.run([str(jdk/'javac'),'-d',tmp,*files,*[str(src/name) for name in ['RearDensityPreferences.java','VirtualCastRequest.java']]],check=True)
  subprocess.run([str(jdk/'java'),'-cp',tmp,'top.zainzh.rearscreen.PrefsTest'],check=True)
