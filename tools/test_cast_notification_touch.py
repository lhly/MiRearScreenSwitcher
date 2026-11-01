#!/usr/bin/env python3
"""Replay capsule tap isolation against the production physical touch router."""
from pathlib import Path
import os
import subprocess
import tempfile
ROOT = Path(__file__).resolve().parents[1]
JAVA = r'''
package top.zainzh.rearscreen;
import java.util.*;
class CapsuleTouchTest {
 static RearTouchRouter router; static List<Integer> app; static List<Long> dismissed; static int checks;
 static void check(boolean c){checks++;if(!c)throw new AssertionError("check "+checks);}
 static void setup(){
  app=new ArrayList<>();dismissed=new ArrayList<>();
  var state=new RearTouchState(new RearTouchState.Output(){
   public void event(int a,int[] id,float[] x,float[] y,long d,long t){app.add(a&255);}
   public void ended(float x,float y){}
  },new RearCastViewport(976,596,0,450,296,32,944,564));
  router=new RearTouchRouter(state,976,596,450,true,t->{});region(1);
 }
 static void region(long token){router.notification(token,350,50,900,200,dismissed::add);}
 static RearTouchState.Contact c(int slot,float x,float y){return new RearTouchState.Contact(slot,slot+1,x,y);}
 static void f(long time,RearTouchState.Contact... c)throws Exception{router.frame(Arrays.asList(c),time);}
 public static void main(String[] args)throws Exception{
  setup();f(100,c(0,500,300));f(200);check(app.equals(List.of(0,1)));check(dismissed.isEmpty());
  setup();f(100,c(0,500,100));f(200);check(app.isEmpty());check(dismissed.equals(List.of(1L)));
  setup();f(100,c(0,500,100));f(150,c(0,650,100));f(200);check(app.isEmpty());check(dismissed.isEmpty());
  setup();f(100,c(0,500,100),c(1,500,300));f(150,c(0,500,100));f(200);check(app.isEmpty());check(dismissed.isEmpty());
  setup();f(100,c(0,500,100));region(0);f(150,c(0,500,300));f(200);check(app.isEmpty());check(dismissed.isEmpty());
  f(300,c(0,500,300));f(400);check(app.equals(List.of(0,1)));
  setup();f(100,c(0,500,100));region(2);f(200);check(dismissed.isEmpty());check(app.isEmpty());
  f(300,c(0,500,100));f(400);check(dismissed.equals(List.of(2L)));check(app.isEmpty());
  setup();region(0);f(100,c(0,500,100));region(1);f(150,c(0,510,100));f(200);
  check(app.equals(List.of(0,2,1)));check(dismissed.isEmpty());
  setup();f(100,c(0,500,100));router.outputAvailable(false,120);router.outputAvailable(true,140);f(160);region(0);
  f(200,c(0,500,300));f(300);check(dismissed.isEmpty());check(app.equals(List.of(0,1)));
  setup();f(100,c(0,500,100));router.cancel(120);f(140);f(200,c(0,500,300));f(300);
  check(dismissed.isEmpty());check(app.equals(List.of(0,1)));
  setup();boolean invalid=false;try{router.notification(1,-1,0,900,200,dismissed::add);}catch(IllegalArgumentException e){invalid=true;}
  check(invalid);
  System.out.println("PASS "+checks+" capsule tap/move/multitouch/expiry/replacement/cancellation checks");
 }
}'''
with tempfile.TemporaryDirectory() as tmp:
    test=Path(tmp)/'CapsuleTouchTest.java'; test.write_text(JAVA)
    jdk=Path(os.environ.get('JAVA_HOME', ROOT/'.local/toolchains/jdk21'))/'bin'
    src=ROOT/'android/app/src/main/java/top/zainzh/rearscreen'
    subprocess.run([str(jdk/'javac'),'-d',tmp,*[str(src/name) for name in ('RearCastViewport.java','RearTouchState.java','RearTouchRouter.java')],str(test)],check=True)
    subprocess.run([str(jdk/'java'),'-cp',tmp,'top.zainzh.rearscreen.CapsuleTouchTest'],check=True)
