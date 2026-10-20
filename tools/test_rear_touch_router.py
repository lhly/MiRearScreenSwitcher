#!/usr/bin/env python3
"""Replay touch streams through the real navigation router without an Android runtime."""
from pathlib import Path
import os, subprocess, tempfile

ROOT = Path(__file__).resolve().parents[1]
JAVA = r'''
package top.zainzh.rearscreen;
import java.util.*;
public class RouterTest {
 static final List<Integer> events = new ArrayList<>(); static int backs;
 static final List<Boolean> feedbackActive = new ArrayList<>(), feedbackReady = new ArrayList<>();
 static final List<Float> feedbackDistance = new ArrayList<>();
 static RearTouchRouter router;
 static RearTouchState.Contact c(int slot,int id,float x,float y){return new RearTouchState.Contact(slot,id,x,y);}
 static void setup(boolean enabled){
  events.clear();backs=0;feedbackActive.clear();feedbackReady.clear();feedbackDistance.clear();
  RearTouchState s=new RearTouchState(new RearTouchState.Output(){
   public void event(int a,int[] ids,float[] x,float[] y,long d,long t){events.add(a&255);}
   public void ended(float x,float y){}
  });
  router=new RearTouchRouter(s,976,596,450,enabled,t->backs++, (distance,y,active,ready,menuArmed)->{
   if(active&&!events.contains(3))throw new AssertionError("Arrow must follow App cancellation");
   feedbackActive.add(active);feedbackReady.add(ready);feedbackDistance.add(distance);
  });
 }
 static void f(long t,RearTouchState.Contact... c)throws Exception{router.frame(Arrays.asList(c),t);}
 static void expect(int count,Integer... actions){
  if(backs!=count||!events.equals(Arrays.asList(actions)))throw new AssertionError("backs="+backs+" events="+events);
 }
 static void back(long t)throws Exception{f(t,c(0,1,970,250));f(t+70,c(0,1,920,250));f(t+150,c(0,1,780,250));f(t+190);}
 static void feedback(Boolean... active){
  if(!feedbackActive.equals(Arrays.asList(active)))throw new AssertionError("Arrow lifecycle="+feedbackActive);
 }
 public static void main(String[] args)throws Exception{
  setup(true);back(100);expect(1,0,3); // App is cancelled exactly once; UP belongs to navigation.
  f(400,c(0,2,450,250));f(420);expect(1,0,3,0,1); // No stale contacts after Back.
  setup(true);back(100);router.outputAvailable(false,400);router.outputAvailable(true,450);
  f(500,c(0,2,450,250));f(550);expect(1,0,3,0,1); // A completed Back leaves no stale held finger across lock.
  setup(true);f(100,c(0,1,970,250));f(150);f(200,c(0,2,970,250));f(250);expect(0,0,1,0,1); // Edge double tap.
  setup(true);f(100,c(0,1,500,250));f(180,c(0,1,900,250));f(250);expect(0,0,2,1); // App horizontal swipe.
  setup(true);f(100,c(0,1,970,250));f(180,c(0,1,960,400));f(250);expect(0,0,2,1); // Edge vertical swipe.
  setup(true);f(100,c(0,1,970,550));f(180,c(0,1,780,550));f(250);expect(0,0,2,1); // Bottom navigation region.
  setup(true);f(100,c(0,1,913,250));f(180,c(0,1,700,250));f(250);expect(0,0,2,1); // Outside 62px strip.
  setup(true);f(100,c(0,1,970,250));f(900,c(0,1,780,250));f(950);expect(0,0,2,1); // Long-press/drag stays in App.
  setup(true);f(100,c(0,1,970,250));f(150,c(0,1,780,250));f(220,c(0,1,965,250));f(260);expect(0,0,3); // Reverse to cancel.
  setup(true);f(100,c(0,1,970,250));f(130,c(0,1,910,250));f(135);expect(1,0,3); // Short fast flick.
  setup(true);f(100,c(0,1,970,250));f(500,c(0,1,910,250));f(600);expect(0,0,3); // Same distance slowly is insufficient.
  setup(true);f(100,c(0,1,970,250));f(120,c(0,1,960,250),c(1,2,800,250));f(180,c(1,2,700,250));f(220);expect(0,0,5,6,1);
  setup(true);f(100,c(0,1,970,250));f(150,c(0,1,780,250));f(170,c(0,1,780,250),c(1,2,800,250));f(220);expect(0,0,3);
  setup(true);f(100,c(0,1,970,250));f(150,c(0,1,780,250));router.cancel(180);f(190);expect(0,0,3);
  setup(true);f(100,c(0,1,970,250));router.outputAvailable(false,110);
  f(120,c(0,1,900,250));router.outputAvailable(true,140);f(160,c(0,1,750,250));f(200);expect(0,0,3);
  back(300);expect(1,0,3,0,3); // Held fingers cannot navigate after reconnect; a fresh gesture can.
  setup(true);router.outputAvailable(false,100);f(120,c(0,1,970,250));f(140);router.outputAvailable(true,160);back(200);expect(1,0,3);
  setup(false);back(100);expect(0,0,2,2,1); // Physical comparison route unchanged.
  setup(true);f(100,c(0,1,970,250));f(150,c(0,2,950,250));f(200,c(0,2,700,250));f(250);expect(0,0,1,0,2,1);
  setup(true);f(100,c(0,1,970,250));f(150,c(0,1,780,250));
  boolean rejected=false;try{f(180,c(0,1,700,250),c(0,2,800,250));}catch(IllegalArgumentException e){rejected=true;}
  if(!rejected)throw new AssertionError("Consumed navigation must still validate slots");
  setup(true);back(100);feedback(true,true,false);
  if(!feedbackReady.equals(Arrays.asList(false,true,true))||!feedbackDistance.subList(0,2).equals(Arrays.asList(50f,190f)))
   throw new AssertionError("Progress/readiness must match the actual Back decision");
  setup(true);f(100,c(0,1,970,250));f(150,c(0,1,780,250));f(220,c(0,1,965,250));f(260);
  feedback(true,true,false);if(feedbackReady.get(2)||backs!=0)throw new AssertionError("Retreat must cancel arrow and navigation");
  setup(true);f(100,c(0,1,970,250));f(150,c(0,1,780,250));f(170,c(0,1,780,250),c(1,2,800,250));f(220);
  feedback(true,false);if(feedbackReady.get(1))throw new AssertionError("Second finger cancels immediately");
  setup(true);f(100,c(0,1,970,250));f(150,c(0,1,780,250));router.outputAvailable(false,180);router.outputAvailable(true,200);
  f(210,c(0,1,750,250));f(220);feedback(true,false);expect(0,0,3);
  setup(true);f(100,c(0,1,970,250));f(150,c(0,1,780,250));router.cancel(180);router.cancel(190);f(200);feedback(true,false);
  setup(true);f(100,c(0,1,970,250));f(150,c(0,1,780,250));router.geometry(976,596,450);feedback(true,false);
  setup(true);f(100,c(0,1,970,250));f(150);f(200,c(0,2,970,250));f(250);feedback();
  setup(false);back(100);feedback();
  setup(true);back(100);back(400);feedback(true,true,false,true,true,false);
  System.out.println("PASS Back/content/multi-touch/isolation and arrow progress, readiness, retreat, pause, stop, geometry reset, fresh gestures");
 }
}'''
with tempfile.TemporaryDirectory() as tmp:
    test=Path(tmp)/'RouterTest.java';test.write_text(JAVA)
    jdk=Path(os.environ.get('JAVA_HOME',ROOT/'.local/toolchains/jdk21'))/'bin'
    sources=ROOT/'android/app/src/main/java/top/zainzh/rearscreen'
    subprocess.run([str(jdk/'javac'),'-d',tmp,str(sources/'RearCastViewport.java'),str(sources/'RearTouchState.java'),str(sources/'RearTouchRouter.java'),str(test)],check=True)
    subprocess.run([str(jdk/'java'),'-cp',tmp,'top.zainzh.rearscreen.RouterTest'],check=True)
