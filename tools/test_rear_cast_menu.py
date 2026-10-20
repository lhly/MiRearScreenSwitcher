#!/usr/bin/env python3
"""Replay real two-stage navigation and rotated App streams without Android or hardware."""
from pathlib import Path
import os, subprocess, tempfile
ROOT = Path(__file__).resolve().parents[1]
JAVA = r'''
package top.zainzh.rearscreen;
import java.util.*;
public class MenuTest {
 static final List<Integer> app=new ArrayList<>(),menu=new ArrayList<>();
 static final List<Boolean> visible=new ArrayList<>(); static int backs; static final List<Boolean> armedFeedback=new ArrayList<>();
 static RearTouchRouter router; static RearTouchState state; static float lastX,lastY; static int pointers;
 static RearTouchState.Contact c(int id,float x,float y){return new RearTouchState.Contact(id,id+1,x,y);}
 static void setup(){
  app.clear();menu.clear();visible.clear();armedFeedback.clear();backs=0;pointers=0;
  state=new RearTouchState(new RearTouchState.Output(){
   public void event(int a,int[] ids,float[] x,float[] y,long d,long t){app.add(a&255);lastX=x[0];lastY=y[0];pointers=Math.max(pointers,ids.length);}
   public void ended(float x,float y){}
  },new RearCastViewport(976,596,0,450,296,32,944,564));
  router=new RearTouchRouter(state,976,596,450,true,t->backs++,(inward,y,active,ready,armed)->armedFeedback.add(active&&armed));
  router.setMenu(new RearTouchRouter.Menu(){
   public void visible(boolean open){visible.add(open);}
   public void touch(int action,float x,float y,long d,long t){menu.add(action);}
  });
 }
 static void f(long t,RearTouchState.Contact... c)throws Exception{router.frame(Arrays.asList(c),t);}
 static void eq(Object a,Object b){if(!a.equals(b))throw new AssertionError(a+" != "+b);}
 static void arm()throws Exception{
  f(100,c(0,970,250));f(180,c(0,800,250));router.tick(429);
  eq(router.isMenuArmed(),false);eq(visible,List.of());router.tick(430);
  eq(router.isMenuArmed(),true);eq(visible,List.of());eq(backs,0);
  eq(armedFeedback.get(armedFeedback.size()-1),true);
 }
 static void open()throws Exception{arm();f(800);eq(visible,List.of(true));eq(backs,0);}
 public static void main(String[] args)throws Exception{
  setup();f(100,c(0,970,250));f(180,c(0,800,250));f(220);router.tick(1000);eq(backs,1);eq(visible,List.of());
  setup();f(100,c(0,930,250));f(180,c(0,760,250));router.tick(730);f(800);
  eq(app,List.of(0,3));eq(visible,List.of(true));eq(backs,0);
  router.dismissMenu(850);f(900,c(0,500,200));f(950);eq(app,List.of(0,3,0,1));
  setup();open();router.tick(1800);f(1900);eq(backs,0);eq(menu,List.of());eq(app,List.of());
  f(2000,c(0,600,200));f(2040,c(0,620,205));f(2080);eq(menu,List.of(0,2,1));eq(app,List.of());
  router.dismissMenu(2100);eq(visible,List.of(true,false));f(2200,c(0,500,200));f(2250);eq(app,List.of(0,1));
  setup();open();f(800);f(900,c(0,600,200));f(940,c(0,600,200),c(1,650,250));f(980,c(0,600,200));f(1000);
  eq(menu,List.of(0,3));eq(app,List.of());router.dismissMenu(1100);
  setup();open();router.outputAvailable(false,800);f(820,c(0,800,250));f(900);router.outputAvailable(true,950);
  f(1000,c(0,500,200));f(1050);eq(visible,List.of(true,false));eq(app,List.of(0,1));
  setup();f(100,c(0,970,250));f(180,c(0,800,250));f(500,c(0,700,250));router.tick(750);eq(visible,List.of());
  f(800,c(0,965,250));router.tick(1400);eq(visible,List.of());f(1450);eq(backs,0);
  setup();f(100,c(0,970,250));f(180,c(0,800,250));f(200,c(0,800,250),c(1,700,250));router.tick(900);f(1000);eq(visible,List.of());eq(backs,0);
  setup();open();f(800);f(900,c(0,600,200));router.dismissMenu(920);f(950,c(0,610,210));f(980);
  eq(menu,List.of(0,3));eq(app,List.of());f(1000,c(0,500,200));f(1050);eq(app,List.of(0,1));
  setup();f(100,c(0,970,250));f(180,c(0,800,250));router.cancel(300);router.tick(900);f(1000);eq(visible,List.of());
  // A quarter-second pause tolerates natural jitter; a real drag still restarts it.
  eq(RearTouchRouter.MENU_HOLD_MS,250L);
  setup();f(100,c(0,970,250));f(180,c(0,800,250));f(300,c(0,775,250));router.tick(429);
  eq(router.isMenuArmed(),false);router.tick(430);eq(router.isMenuArmed(),true);eq(visible,List.of());f(500);eq(visible,List.of(true));
  setup();f(100,c(0,970,250));f(180,c(0,800,250));f(300,c(0,740,250));router.tick(549);
  eq(router.isMenuArmed(),false);router.tick(550);eq(router.isMenuArmed(),true);eq(visible,List.of());f(600);eq(visible,List.of(true));
  // Stage two is a reversible affordance, never an action while the finger is held.
  setup();arm();router.tick(10000);eq(visible,List.of());eq(backs,0);eq(router.isMenuArmed(),true);
  f(12000);eq(visible,List.of(true));eq(backs,0); // A long hold is still an explicit menu UP.
  setup();arm();f(900,c(0,965,250));eq(router.isMenuArmed(),false);
  eq(armedFeedback.get(armedFeedback.size()-1),false);router.tick(10000);f(11000);
  eq(visible,List.of());eq(backs,0); // Returning to the edge cancels both actions, however long held.
  f(12000,c(0,970,250));f(12070,c(0,800,250));f(12150);eq(backs,1);eq(visible,List.of());
  setup();arm();f(900,c(0,890,250));eq(router.isMenuArmed(),true);f(950);eq(visible,List.of(true)); // Hysteresis.
  setup();arm();f(900,c(0,910,250));eq(router.isMenuArmed(),false);f(1000);eq(visible,List.of());eq(backs,0);
  setup();arm();f(900,c(0,915,250));f(1000,c(0,800,250));router.tick(1249);eq(router.isMenuArmed(),false);
  router.tick(1250);eq(router.isMenuArmed(),true);eq(visible,List.of());f(1300);eq(visible,List.of(true));
  setup();arm();f(900,c(0,800,250),c(1,700,250));router.tick(3000);f(3100);eq(visible,List.of());eq(backs,0);
  setup();arm();router.outputAvailable(false,800);router.outputAvailable(true,900);f(1000,c(0,800,250));f(1100);
  eq(visible,List.of());eq(backs,0);f(1200,c(0,500,250));f(1250);eq(app,List.of(0,1));
  setup();arm();router.cancel(800);router.tick(3000);f(3100);eq(visible,List.of());eq(backs,0);
  // Rotation mapping: four corners, two pointers, fresh contact after rotation pause.
  RearCastViewport v=new RearCastViewport(976,596,0,450,296,32,944,564);
  float[][] tl={{0,0},{0,647},{647,531},{531,0}};
  for(int r=0;r<4;r++){
   setup();state.contentRotation(r,10);f(100,c(0,296,32));eq(lastX,tl[r][0]);eq(lastY,tl[r][1]);
   f(120,c(0,296,32),c(1,943,563));eq(pointers,2);f(140,c(1,943,563));f(160);
   float[] br=v.contentPoint(943.9f,563.9f,r);
   if(br[0]<0||br[1]<0||br[0]>=v.contentWidth(r)||br[1]>=v.contentHeight(r))throw new AssertionError("Rotated fractional edge outside");
   setup();f(100,c(0,500,200));router.outputAvailable(false,120);state.contentRotation(r,125);router.outputAvailable(true,130);
   f(140,c(0,510,210));f(160);f(180,c(0,296,32));f(200);eq(app,List.of(0,3,0,1));eq(lastX,tl[r][0]);eq(lastY,tl[r][1]);
  }
  System.out.println("PASS 250ms readiness/jitter tolerance, release-only menu, indefinite hold, withdraw/re-arm/hysteresis, normal Back, cancellation/isolation, pause recovery, four rotations");
 }
}'''
with tempfile.TemporaryDirectory() as tmp:
    test=Path(tmp)/'MenuTest.java';test.write_text(JAVA)
    jdk=Path(os.environ.get('JAVA_HOME',ROOT/'.local/toolchains/jdk21'))/'bin'
    src=ROOT/'android/app/src/main/java/top/zainzh/rearscreen'
    subprocess.run([str(jdk/'javac'),'-d',tmp,*map(str,[src/'RearCastViewport.java',src/'RearTouchState.java',src/'RearTouchRouter.java',test])],check=True)
    subprocess.run([str(jdk/'java'),'-cp',tmp,'top.zainzh.rearscreen.MenuTest'],check=True)
