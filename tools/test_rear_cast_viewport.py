#!/usr/bin/env python3
"""Exercise safe-area touch sequences and physical edge navigation with the real converters."""
from pathlib import Path
import os, subprocess, tempfile

ROOT = Path(__file__).resolve().parents[1]
JAVA = r'''
package top.zainzh.rearscreen;
import java.util.*;
public class ViewportTest {
 static final RearCastViewport v=RearCastViewport.safe(976,596,0,450,296,0,0,0,new int[]{101,101,101,101});
 static List<Integer> actions=new ArrayList<>(); static List<float[]> xs=new ArrayList<>(),ys=new ArrayList<>();
 static int backs; static RearTouchState state; static RearTouchRouter router;
 static RearTouchState.Contact c(int slot,int id,float x,float y){return new RearTouchState.Contact(slot,id,x,y);}
 static void setup(){actions.clear();xs.clear();ys.clear();backs=0;
  state=new RearTouchState(new RearTouchState.Output(){
   public void event(int a,int[] ids,float[] x,float[] y,long d,long t){
    actions.add(a&255);xs.add(x.clone());ys.add(y.clone());
    for(int i=0;i<x.length;i++)if(x[i]<0||x[i]>=648||y[i]<0||y[i]>=532)throw new AssertionError("Out-of-viewport injection");
   } public void ended(float x,float y){}
  },v);
  router=new RearTouchRouter(state,976,596,450,true,t->backs++);
 }
 static void f(long t,RearTouchState.Contact... cs)throws Exception{router.frame(Arrays.asList(cs),t);}
 static void expect(Integer... expected){if(!actions.equals(Arrays.asList(expected)))throw new AssertionError(actions.toString());}
 static void eq(float actual,float expected){if(Math.abs(actual-expected)>.001)throw new AssertionError(actual+" != "+expected);}
 public static void main(String[] args)throws Exception{
  if(v.left!=296||v.top!=32||v.right!=944||v.bottom!=564||v.width()!=648||v.height()!=532)throw new AssertionError(v.key());
  boolean invalid=false;try{RearCastViewport.safe(976,596,1,450,0,0,0,296,new int[4]);}catch(IllegalArgumentException e){invalid=true;}
  if(!invalid)throw new AssertionError("Unsupported rotation must fail closed");
  invalid=false;try{new RearCastViewport(976,596,0,450,976,0,976,596);}catch(IllegalArgumentException e){invalid=true;}
  if(!invalid)throw new AssertionError("Empty viewport accepted");
  setup(); RearTouchState.Contact physical=c(0,7,500,300);f(100,physical);f(140);expect(0,1);
  eq(xs.get(0)[0],204);eq(ys.get(0)[0],268);eq(physical.x,500);eq(physical.y,300);
  setup();f(100,c(0,7,296,32));f(150);f(200,c(0,8,943,563));f(250);expect(0,1,0,1);
  eq(xs.get(2)[0],647);eq(ys.get(2)[0],531);
  setup();f(100,c(0,7,500,300));f(150);f(210,c(0,8,500,300));f(260);expect(0,1,0,1);
  setup();f(100,c(0,7,500,300));f(140,c(0,7,550,280));f(160);expect(0,2,1);
  setup();f(100,c(0,7,500,300));f(120,c(0,7,490,300),c(1,8,650,350));
  f(150,c(0,7,470,300),c(1,8,680,350));f(180,c(1,8,680,350));f(200);expect(0,5,2,6,1);
  eq(xs.get(1)[0],194);eq(xs.get(1)[1],354);eq(ys.get(1)[1],318);
  setup();f(100,c(0,7,100,300));f(150,c(0,7,500,300));f(200);expect();
  f(300,c(0,8,500,300));f(350);expect(0,1);
  setup();f(100,c(0,7,500,300));f(150,c(0,7,295,300));f(180,c(0,7,600,300));f(200);expect(0,3);
  setup();f(100,c(0,7,500,300));f(150,c(0,7,500,300),c(1,8,200,300));f(180,c(0,7,500,300));f(200);expect(0,3);
  setup();f(100,c(0,7,970,250));f(160,c(0,7,900,250));f(230,c(0,7,800,250));f(280);expect();
  if(backs!=1)throw new AssertionError("Back from physical black margin lost");
  f(350,c(0,8,500,300));f(400);expect(0,1);
  setup();f(100,c(0,7,930,250));f(160,c(0,7,800,250));f(200);expect(0,3);
  if(backs!=1)throw new AssertionError("Back inside viewport lost");
  setup();f(100,c(0,7,500,300));router.outputAvailable(false,150);router.outputAvailable(true,170);
  f(190,c(0,7,500,300));f(200);f(300,c(0,8,500,300));f(350);expect(0,3,0,1);
  setup();f(100,c(0,7,200,300));router.outputAvailable(false,150);f(160);router.outputAvailable(true,170);
  f(300,c(0,8,500,300));f(350);expect(0,1);
  // Switching cancels both pointers; old contacts cannot become a late DOWN in the new region.
  RearCastViewport full=v.full();
  if(!full.isFull()||v.isFull()||full.width()!=976||full.height()!=596)throw new AssertionError(full.key());
  for(int r=0;r<4;r++){
   List<Integer> a=new ArrayList<>();List<float[]> points=new ArrayList<>();
   RearTouchState dynamic=new RearTouchState(new RearTouchState.Output(){
    public void event(int action,int[] ids,float[] x,float[] y,long d,long t){
     a.add(action&255);points.add(new float[]{x[0],y[0]});
    }public void ended(float x,float y){}
   },v);
   dynamic.contentRotation(r,10);
   dynamic.frame(List.of(c(0,1,500,300)),100);
   dynamic.frame(List.of(c(0,1,500,300),c(1,2,600,350)),110);
   dynamic.outputAvailable(false,120);dynamic.configure(full,r,125);dynamic.outputAvailable(true,130);
   dynamic.frame(List.of(c(0,1,510,300)),140);dynamic.frame(List.of(),150);
   if(!a.equals(List.of(0,5,3)))throw new AssertionError("Leaked pointer across resize: "+a);
   dynamic.frame(List.of(c(0,3,100,100)),160);dynamic.frame(List.of(),170);
   float[][] expected={{100,100},{100,875},{875,495},{495,100}};
   float[] p=points.get(3);eq(p[0],expected[r][0]);eq(p[1],expected[r][1]);
   if(!a.equals(List.of(0,5,3,0,1)))throw new AssertionError(a.toString());
   dynamic.outputAvailable(false,180);dynamic.configure(v,r,185);dynamic.outputAvailable(true,190);
   dynamic.frame(List.of(c(0,4,100,100)),200);dynamic.frame(List.of(c(0,4,500,300)),210);dynamic.frame(List.of(),220);
   if(a.size()!=5)throw new AssertionError("Safe border accepted outside finger");
   dynamic.frame(List.of(c(0,5,296,32)),230);dynamic.frame(List.of(),240);
   float[][] safeTL={{0,0},{0,647},{647,531},{531,0}};
   p=points.get(5);eq(p[0],safeTL[r][0]);eq(p[1],safeTL[r][1]);
   if(!a.equals(List.of(0,5,3,0,1,0,1)))throw new AssertionError(a.toString());
   // A finger previously outside the safe area must not permanently latch cancellation after resize.
   dynamic.frame(List.of(c(0,6,100,100)),250);dynamic.outputAvailable(false,260);
   dynamic.frame(List.of(),270);dynamic.configure(full,r,280);dynamic.outputAvailable(true,290);
   dynamic.frame(List.of(c(0,7,100,100)),300);dynamic.frame(List.of(),310);
   if(a.size()!=9)throw new AssertionError("Viewport switch left input latched");
  }
  System.out.println("PASS dynamic safe/full resize with four rotations, two-pointer cancellation and fresh-contact recovery");
  System.out.println("PASS viewport geometry, mapped taps/double/swipe/multitouch, bounds cancellation, physical Back, lock reconnect");
 }
}'''
with tempfile.TemporaryDirectory() as tmp:
    test=Path(tmp)/'ViewportTest.java';test.write_text(JAVA)
    jdk=Path(os.environ.get('JAVA_HOME',ROOT/'.local/toolchains/jdk21'))/'bin'
    sources=ROOT/'android/app/src/main/java/top/zainzh/rearscreen'
    subprocess.run([str(jdk/'javac'),'-d',tmp,*[str(sources/f) for f in ['RearCastViewport.java','RearTouchState.java','RearTouchRouter.java']],str(test)],check=True)
    subprocess.run([str(jdk/'java'),'-cp',tmp,'top.zainzh.rearscreen.ViewportTest'],check=True)
