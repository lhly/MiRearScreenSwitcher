#!/usr/bin/env python3
"""Contract checks for uniform defaults, existing choices, per-App persistence and disabled-feature isolation."""
from pathlib import Path
import os, subprocess, tempfile
ROOT=Path(__file__).resolve().parents[1]
STUBS={
'android/content/SharedPreferences.java': '''package android.content; public interface SharedPreferences {
 boolean getBoolean(String key,boolean fallback); Editor edit(); interface Editor { Editor putBoolean(String key,boolean value);void apply(); }
}''',
'android/content/Context.java': '''package android.content;import java.util.*; public class Context {
 public static final int MODE_PRIVATE=0;static final Map<String,Map<String,Boolean>> disk=new HashMap<>();
 public SharedPreferences getSharedPreferences(String name,int mode){Map<String,Boolean> data=disk.computeIfAbsent(name,k->new HashMap<>());
 return new SharedPreferences(){public boolean getBoolean(String k,boolean fallback){return data.getOrDefault(k,fallback);}
 public Editor edit(){return new Editor(){Map<String,Boolean> pending=new HashMap<>();public Editor putBoolean(String k,boolean v){pending.put(k,v);return this;}
 public void apply(){data.putAll(pending);}};}};}
}''',
'PrefsTest.java': '''package top.zainzh.rearscreen;import android.content.Context; public class PrefsTest {
 static void eq(boolean a,boolean b){if(a!=b)throw new AssertionError(a+" != "+b);}
 public static void main(String[] args){Context a=new Context();String first="com.example.reader",other="com.example.video",third="com.example.browser";
  if(!BuildConfig.VIRTUAL_CAST_ENABLED){
   RearDensityPreferences.preferences(a).edit().putBoolean(RearDensityPreferences.key(first),true).apply();
   eq(RearDensityPreferences.enabled(a,first),false);RearDensityPreferences.setEnabled(a,other,true);eq(RearDensityPreferences.enabled(a,other),false);
   if(RearDensityPreferences.preferences(a).getBoolean(RearDensityPreferences.key(other),false))throw new AssertionError("Disabled feature wrote preference");
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
  boolean rejected=false;try{RearDensityPreferences.setEnabled(a,"invalid/../key",true);}catch(IllegalArgumentException expected){rejected=true;}
  if(!rejected)throw new AssertionError("Invalid package accepted");
  System.out.println("PASS uniform default OFF, existing ON/OFF choices retained, per-App durable choices, independent updates and package validation");
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
