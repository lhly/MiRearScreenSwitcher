#!/usr/bin/env python3
"""Exercise reversible cast protection with real recovery files and competing owners."""
from pathlib import Path
import os, subprocess, tempfile

ROOT = Path(__file__).resolve().parents[1]
JAVA = r'''package top.zainzh.rearscreen;
import java.io.*;
import java.nio.file.*;
import java.util.*;
public final class FreezeStateTest {
 static final String APP="com.anxcye.anx_reader";
 static class Store implements RearFreezeState.Store {
  String value; boolean failWrite; int writes;
  Store(String s){value=s;}
  public String read(){return value;}
  public void write(String s)throws Exception { if(failWrite)throw new IOException("provider down"); value=s;writes++; }
 }
 interface Test { void run(Store s, File f)throws Exception; }
 static int passed;
 static void check(boolean b){if(!b)throw new AssertionError();}
 static void test(String initial,Test test)throws Exception{
  File dir=Files.createTempDirectory("freeze-state-").toFile();
  try{test.run(new Store(initial),dir);passed++;}
  finally{for(File f:dir.listFiles())f.delete();dir.delete();}
 }
 public static void main(String[]args)throws Exception{
  for(String original:Arrays.asList(null,"","com.existing.one,  com.existing.two"))test(original,(s,f)->{
   try(RearFreezeState g=new RearFreezeState(s,f)){
    g.ensure(APP);check(RearFreezeState.contains(s.value,APP));g.ensure(APP);check(s.writes==1);
    g.restore();check(Objects.equals(s.value,original));g.restore();check(s.writes==2);
   }
  });
  test(APP,(s,f)->{try(RearFreezeState g=new RearFreezeState(s,f)){g.ensure(APP);g.restore();check(s.writes==0);}});
  test("com.original.one",(s,f)->{
   try(RearFreezeState g=new RearFreezeState(s,f)){g.ensure(APP);}
   // Abrupt owner death leaves the write-ahead record, then a new process restores it.
   try(RearFreezeState recovered=new RearFreezeState(s,f)){recovered.restore();check("com.original.one".equals(s.value));}
  });
  test("com.original.one",(s,f)->{try(RearFreezeState g=new RearFreezeState(s,f)){
   g.ensure(APP);s.value+=", com.user.added";g.restore();
   check("com.original.one, com.user.added".equals(s.value));
  }});
  test("com.original.one",(s,f)->{try(RearFreezeState g=new RearFreezeState(s,f)){
   g.ensure(APP);s.value="com.new.policy";g.ensure(APP);g.restore();check("com.new.policy".equals(s.value));
  }});
  test("com.original.one",(s,f)->{try(RearFreezeState g=new RearFreezeState(s,f)){
   g.ensure(APP);s.value="com.user.replaced";g.restore();check("com.user.replaced".equals(s.value));
  }});
  test("com.original.one",(s,f)->{
   try(RearFreezeState g=new RearFreezeState(s,f)){
    g.ensure(APP);s.failWrite=true;
    try{g.restore();throw new AssertionError();}catch(IOException expected){}
    check(new File(f,"pending.properties").exists());
   }
   s.failWrite=false;
   try(RearFreezeState recovered=new RearFreezeState(s,f)){recovered.restore();check("com.original.one".equals(s.value));}
  });
  test("com.original.one",(s,f)->{try(RearFreezeState g=new RearFreezeState(s,f)){
   s.failWrite=true;try{g.ensure(APP);throw new AssertionError();}catch(IOException expected){}
   s.failWrite=false;g.restore();check("com.original.one".equals(s.value));
  }});
  test("com.original.one",(s,f)->{try(RearFreezeState g=new RearFreezeState(s,f)){
   g.ensure(APP);String active=s.value;
   try{new RearFreezeState(s,f);throw new AssertionError();}catch(IOException expected){}
   check(active.equals(s.value));g.restore();
  }});
  test("com.original.one",(s,f)->{try(RearFreezeState g=new RearFreezeState(s,f)){
   try{g.ensure("bad; package");throw new AssertionError();}catch(IllegalArgumentException expected){}
   check(s.writes==0);
  }});
  System.out.println("PASS "+passed+" freeze recovery cases: exact baseline, missing value, existing entry, crash, concurrent edits, policy rebuild, failed writes and exclusive ownership");
 }
}'''
with tempfile.TemporaryDirectory() as temp:
    target = Path(temp)/'FreezeStateTest.java'
    target.write_text(JAVA)
    jdk = Path(os.environ.get('JAVA_HOME', ROOT/'.local/toolchains/jdk21'))/'bin'
    subprocess.run([str(jdk/'javac'), '-d', temp, str(ROOT/'android/app/src/main/java/top/zainzh/rearscreen/RearFreezeState.java'), str(target)], check=True)
    subprocess.run([str(jdk/'java'), '-cp', temp, 'top.zainzh.rearscreen.FreezeStateTest'], check=True)
