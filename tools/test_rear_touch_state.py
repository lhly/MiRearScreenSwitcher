#!/usr/bin/env python3
"""Host test of the actual contact converter; no Android runtime or input device."""
from pathlib import Path
import os, subprocess, tempfile
ROOT = Path(__file__).resolve().parents[1]
JAVA = r'''
package top.zainzh.rearscreen;
import java.util.*;
public class TouchStateTest {
 static List<String> events=new ArrayList<>(); static int ended; static long lastTime;
 static RearTouchState state=new RearTouchState(new RearTouchState.Output(){
 public void event(int a,int[] ids,float[] x,float[] y,long d,long t){
  if(t<d || t<lastTime)throw new AssertionError("gesture time reversed"); lastTime=t;
  events.add(a+":"+Arrays.toString(ids));
 }
 public void ended(float x,float y){ended++;}});
 static RearTouchState.Contact c(int s,int t){return new RearTouchState.Contact(s,t,100+s,200);}
 static void frame(RearTouchState.Contact... c)throws Exception{state.frame(Arrays.asList(c),123);}
 static void expect(String... e){if(!events.equals(Arrays.asList(e)))throw new AssertionError(events);events.clear();}
 public static void main(String[] args)throws Exception{
  frame(c(7,40));frame(c(7,40),c(0,20));frame(c(0,20));frame();
  expect("0:[0]","261:[0, 1]","6:[0, 1]","1:[1]");
  if(ended!=1)throw new AssertionError("premature reset on pointer-up");
  frame(c(0,42));frame(c(0,43));frame();expect("0:[0]","1:[0]","0:[0]","1:[0]");
  frame(c(0,1),c(1,2),c(2,3));frame();expect("0:[0]","261:[0, 1]","517:[0, 1, 2]","518:[0, 1, 2]","262:[0, 1]","1:[0]");
  frame(c(0,7));state.cancel(124);state.cancel(125);frame();expect("0:[0]","3:[0]");
  frame(c(0,8));state.frame(Arrays.asList(new RearTouchState.Contact(0,8,220,350)),126);frame();expect("0:[0]","2:[0]","1:[0]");
  for(int r=0;r<4;r++){
   float[] a=RearTouchState.coordinate(0,0,976,596,r);
   float[][] corners={{0,0},{0,595},{975,595},{975,0}};
   if(!Arrays.equals(a,corners[r]))throw new AssertionError("rotation "+r);
  }
  boolean rejected=false;try{frame(c(0,1),c(0,2));}catch(IllegalArgumentException e){rejected=true;}
  if(!rejected)throw new AssertionError("duplicate slot");
  frame(c(0,49));state.outputAvailable(true,129);frame();expect("0:[0]","1:[0]");
  // Losing output in a two-finger gesture must cancel once and ignore held contacts after reconnect.
  frame(c(0,50),c(1,51)); state.outputAvailable(false,130);expect("0:[0]","261:[0, 1]","3:[0, 1]");
  frame(c(1,51));state.outputAvailable(true,131);frame(c(1,51));frame(c(1,51),c(2,52));frame(c(2,52));frame();expect();
  frame(c(0,53));frame();expect("0:[0]","1:[0]");
  // Fingers released during the outage allow the first fresh touch immediately after reconnect.
  state.outputAvailable(false,132);frame(c(0,54));frame();state.outputAvailable(true,133);
  frame(c(0,55));frame();expect("0:[0]","1:[0]");
  System.out.println("PASS pointer order, slot reuse, simultaneous removal, cancel, move, rotation, invalid frame, output-loss cancel and fresh-gesture recovery");
 }
}'''
with tempfile.TemporaryDirectory() as tmp:
    p=Path(tmp)/'TouchStateTest.java';p.write_text(JAVA)
    jdk=Path(os.environ.get('JAVA_HOME',ROOT/'.local/toolchains/jdk21'))/'bin'
    subprocess.run([str(jdk/'javac'),'-d',tmp,str(ROOT/'android/app/src/main/java/top/zainzh/rearscreen/RearCastViewport.java'),str(ROOT/'android/app/src/main/java/top/zainzh/rearscreen/RearTouchState.java'),str(p)],check=True)
    subprocess.run([str(jdk/'java'),'-cp',tmp,'top.zainzh.rearscreen.TouchStateTest'],check=True)
