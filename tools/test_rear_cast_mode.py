#!/usr/bin/env python3
"""Production defaults retain direct casting; choices persist and unsupported systems stay direct."""
from pathlib import Path
import os, subprocess, tempfile
ROOT = Path(__file__).resolve().parents[1]
STUBS = {
 'android/content/SharedPreferences.java': '''package android.content;public interface SharedPreferences {boolean getBoolean(String k,boolean v);Editor edit();interface Editor{Editor putBoolean(String k,boolean v);void apply();}}''',
 'android/content/Context.java': '''package android.content;import java.util.*;public class Context {public static final int MODE_PRIVATE=0;static final Map<String,Boolean> data=new HashMap<>();public SharedPreferences getSharedPreferences(String n,int m){return new SharedPreferences(){public boolean getBoolean(String k,boolean v){return data.getOrDefault(k,v);}public Editor edit(){return new Editor(){public Editor putBoolean(String k,boolean v){data.put(k,v);return this;}public void apply(){}};}};}}''',
 'android/os/Build.java': '''package android.os;public class Build{public static class VERSION{public static int SDK_INT=37;}}''',
 'ModeTest.java': '''package top.zainzh.rearscreen;import android.content.Context;import android.os.Build;public class ModeTest {
 static void eq(boolean a,boolean b){if(a!=b)throw new AssertionError(a+" != "+b);}
 public static void main(String[] args){Context c=new Context();eq(RearCastMode.useVirtual(c),BuildConfig.VIRTUAL_CAST_ENABLED&&BuildConfig.DEBUG);
  RearCastMode.setVirtual(c,true);eq(RearCastMode.useVirtual(new Context()),BuildConfig.VIRTUAL_CAST_ENABLED);
  Build.VERSION.SDK_INT=32;eq(RearCastMode.useVirtual(c),false);Build.VERSION.SDK_INT=24;eq(RearCastMode.useVirtual(c),false);
  Build.VERSION.SDK_INT=33;eq(RearCastMode.useVirtual(c),BuildConfig.VIRTUAL_CAST_ENABLED);Build.VERSION.SDK_INT=37;
  RearCastMode.setVirtual(c,false);eq(RearCastMode.useVirtual(new Context()),false);
  System.out.println("PASS debug="+BuildConfig.DEBUG+" feature="+BuildConfig.VIRTUAL_CAST_ENABLED+" defaults, saved choices, API boundary and direct fallback");
 }}'''
}
jdk = Path(os.environ.get('JAVA_HOME', ROOT/'.local/toolchains/jdk21'))/'bin'
for debug, enabled in [(False, True), (True, True), (False, False)]:
 with tempfile.TemporaryDirectory() as tmp:
  files=[]
  for name, content in STUBS.items():
   p=Path(tmp)/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content);files.append(str(p))
  p=Path(tmp)/'BuildConfig.java';p.write_text('package top.zainzh.rearscreen;final class BuildConfig {static final boolean DEBUG='+str(debug).lower()+';static final boolean VIRTUAL_CAST_ENABLED='+str(enabled).lower()+';}');files.append(str(p))
  subprocess.run([str(jdk/'javac'),'-d',tmp,*files,str(ROOT/'android/app/src/main/java/top/zainzh/rearscreen/RearCastMode.java')],check=True)
  subprocess.run([str(jdk/'java'),'-cp',tmp,'top.zainzh.rearscreen.ModeTest'],check=True)
