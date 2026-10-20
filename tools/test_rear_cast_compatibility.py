#!/usr/bin/env python3
"""Verify explicit source-density rendering, effective layout and scaled pointers."""
from pathlib import Path
import os, subprocess, tempfile
ROOT = Path(__file__).resolve().parents[1]
JAVA = r'''
package top.zainzh.rearscreen;
import java.util.*;
public class CompatibilityTest {
 static void eq(float a,float b){if(Math.abs(a-b)>.002)throw new AssertionError(a+" != "+b);}
 static RearCastViewport safe=RearCastViewport.safe(976,596,0,450,296,0,0,0,new int[]{101,101,101,101});
 static void check(RearCastViewport v,int requested,int actual){
  eq(v.bufferWidth/(actual/160f),v.width()/(requested/160f));eq(v.bufferHeight/(actual/160f),v.height()/(requested/160f));
 }
 public static void main(String[] args)throws Exception{
  if(RearCastCompatibility.density(false,240,480)!=240 || RearCastCompatibility.density(true,240,480)!=480)throw new AssertionError("Explicit preference ignored");
  RearCastViewport v=RearCastCompatibility.viewport(safe,240,480);check(v,240,480);
  if(v.bufferWidth!=1296||v.bufferHeight!=1064||v.width()!=648||v.height()!=532||!safe.key().equals(v.key())||safe.renderKey().equals(v.renderKey()))throw new AssertionError("Unsafe buffer geometry");
  check(RearCastCompatibility.viewport(safe.full(),240,480),240,480);
  check(RearCastCompatibility.viewport(safe,480,480),480,480);
  for(int r=0;r<4;r++){
   final int rotation=r;RearCastViewport[] current={v};List<Integer> actions=new ArrayList<>();List<float[]> points=new ArrayList<>();
   RearTouchState state=new RearTouchState(new RearTouchState.Output(){
    public void event(int a,int[] ids,float[] xs,float[] ys,long down,long time){
     actions.add(a&255);points.add(new float[]{xs[0],ys[0]});
     for(int i=0;i<xs.length;i++)if(xs[i]<0||ys[i]<0||xs[i]>=current[0].contentWidth(rotation)||ys[i]>=current[0].contentHeight(rotation))throw new AssertionError("Out of render bounds");
    }public void ended(float x,float y){}
   },v);
   state.contentRotation(r,1);
   state.frame(List.of(new RearTouchState.Contact(0,1,500,300)),10);
   float[][] expected={{408,536},{536,887},{887,527},{527,408}};float[] p=points.get(0);eq(p[0],expected[r][0]);eq(p[1],expected[r][1]);
   state.frame(List.of(new RearTouchState.Contact(0,1,500,300),new RearTouchState.Contact(1,2,700,350)),20);
   state.outputAvailable(false,30);RearCastViewport full=RearCastCompatibility.viewport(safe.full(),240,480);
   state.configure(full,r,40);current[0]=full;state.outputAvailable(true,50);
   state.frame(List.of(new RearTouchState.Contact(0,1,500,300)),60);state.frame(List.of(),70);
   if(!actions.equals(List.of(0,5,3)))throw new AssertionError("Old scaled contacts leaked "+actions);
   state.frame(List.of(new RearTouchState.Contact(0,3,100,100)),80);state.frame(List.of(),90);
   if(!actions.equals(List.of(0,5,3,0,1)))throw new AssertionError("Scaled recovery failed "+actions);
   float[] edge=v.contentPoint(943.999f,563.999f,r);
   if(edge[0]<0||edge[1]<0||edge[0]>=v.contentWidth(r)||edge[1]>=v.contentHeight(r))throw new AssertionError("Fractional edge");
  }
  RearCastViewport rounded=RearCastCompatibility.viewport(safe,240,450);
  if(rounded.bufferWidth!=1215||rounded.bufferHeight!=998)throw new AssertionError("Fractional scale rounding");
  boolean rejected=false;try{RearCastCompatibility.viewport(safe.full(),72,1280);}catch(IllegalArgumentException expected){rejected=true;}
  if(!rejected)throw new AssertionError("Unbounded buffer allocation");
  System.out.println("PASS explicit density choice, unchanged effective dp, fixed physical bounds, scaled pointers/all rotations, multi cancel and safe/full recovery, fractional scaling and allocation limit");
 }
}'''
with tempfile.TemporaryDirectory() as tmp:
 test=Path(tmp)/'CompatibilityTest.java';test.write_text(JAVA)
 jdk=Path(os.environ.get('JAVA_HOME',ROOT/'.local/toolchains/jdk21'))/'bin'
 src=ROOT/'android/app/src/main/java/top/zainzh/rearscreen'
 subprocess.run([str(jdk/'javac'),'-d',tmp,*[str(src/f) for f in ['RearCastCompatibility.java','RearCastViewport.java','RearTouchState.java']],str(test)],check=True)
 subprocess.run([str(jdk/'java'),'-cp',tmp,'top.zainzh.rearscreen.CompatibilityTest'],check=True)
