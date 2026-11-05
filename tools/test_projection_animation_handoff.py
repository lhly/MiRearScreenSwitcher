#!/usr/bin/env python3
"""Exercise the production animation owner when explicit casting replaces its display."""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
STUBS = {
    'android/util/Log.java': '''package android.util;public class Log {public static int d(String a,String b){return 0;}public static int e(String a,String b,Throwable c){return 0;}}''',
    'android/content/Intent.java': '''package android.content;import java.util.*;public class Intent {public final String action;public String pkg;public final Map<String,Long> extras=new HashMap<>();public Intent(String a){action=a;}public Intent setPackage(String p){pkg=p;return this;}public Intent putExtra(String k,long v){extras.put(k,v);return this;}}''',
    'android/content/Context.java': '''package android.content;import java.util.*;public class Context {public final List<Intent> sent=new ArrayList<>();public String getPackageName(){return "top.zainzh.rearscreen";}public void sendBroadcast(Intent i){sent.add(i);}}''',
    'Dependencies.java': '''package top.zainzh.rearscreen;class BuildConfig {static final String APPLICATION_ID="top.zainzh.rearscreen";}class RearScreenChargingActivity {static final String EXTRA_REQUEST_ID="chargingRequestId";}''',
    'ProjectionHandoffCheck.java': r'''package top.zainzh.rearscreen;
import android.content.*;import java.util.*;import java.util.concurrent.*;
public class ProjectionHandoffCheck {
 static int checks;static void check(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
 public static void main(String[] args)throws Exception {
  Context c=new Context();
  RearAnimationManager.Session n=RearAnimationManager.beginAnimation(RearAnimationManager.AnimationType.NOTIFICATION);
  RearAnimationManager.markInterruptedChargingAsAlwaysOn(true);
  RearAnimationManager.projectionStarted(c);
  check(!RearAnimationManager.isCurrent(n.type,n.id));check(!RearAnimationManager.isIdle(n.id));
  check(!RearAnimationManager.shouldResumeChargingAnimation());check(c.sent.size()==1);
  Intent close=c.sent.get(0);check(close.action.endsWith(".FINISH_NOTIFICATION_ANIMATION"));
  check(close.pkg.equals(c.getPackageName()));check(close.extras.get(RearAnimationManager.EXTRA_SESSION_ID)==n.id);
  check(!RearAnimationManager.endAnimation(n.type,n.id));
  RearAnimationManager.Session charge=RearAnimationManager.beginAnimation(RearAnimationManager.AnimationType.CHARGING);
  RearAnimationManager.projectionStarted(c);check(!RearAnimationManager.isIdle(charge.id));
  check(c.sent.size()==2);close=c.sent.get(1);check(close.action.endsWith(".FINISH_CHARGING_ANIMATION"));
  check(close.extras.get(RearScreenChargingActivity.EXTRA_REQUEST_ID)==charge.id);
  RearAnimationManager.Session next=RearAnimationManager.beginAnimation(RearAnimationManager.AnimationType.NOTIFICATION);
  check(!RearAnimationManager.endAnimation(charge.type,charge.id));check(!RearAnimationManager.endAnimation(n.type,n.id));
  check(RearAnimationManager.isCurrent(next.type,next.id));check(RearAnimationManager.endAnimation(next.type,next.id));
  check(RearAnimationManager.isIdle(next.id));
  // A restore queued after activity destruction is also invalidated by the explicit cast.
  RearAnimationManager.projectionStarted(c);check(!RearAnimationManager.isIdle(next.id));
  int before=c.sent.size();long idle=RearAnimationManager.getGeneration();
  RearAnimationManager.projectionStarted(c);check(c.sent.size()==before);check(!RearAnimationManager.isIdle(idle));
  // Ordinary failed/no-op casts never commit the handoff, so the original owner remains valid.
  RearAnimationManager.Session unchanged=RearAnimationManager.beginAnimation(RearAnimationManager.AnimationType.CHARGING);
  check(RearAnimationManager.isCurrent(unchanged.type,unchanged.id));
  check(RearAnimationManager.endAnimation(unchanged.type,unchanged.id));check(RearAnimationManager.isIdle(unchanged.id));
  CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),attempted=new CountDownLatch(1);
  List<String> order=Collections.synchronizedList(new ArrayList<>());
  Thread first=new Thread(()->RearAnimationManager.runDisplayAction(()->{order.add("cast-start");entered.countDown();try{release.await();}catch(InterruptedException e){throw new AssertionError(e);}RearAnimationManager.projectionStarted(c);order.add("cast-commit");}));
  long oldId=unchanged.id;
  Thread restore=new Thread(()->{attempted.countDown();RearAnimationManager.runDisplayAction(()->{if(RearAnimationManager.isIdle(oldId))order.add("stale-restore");order.add("restore-checked");});});
  first.start();check(entered.await(2,TimeUnit.SECONDS));restore.start();check(attempted.await(2,TimeUnit.SECONDS));release.countDown();
  first.join(2000);restore.join(2000);check(!first.isAlive()&&!restore.isAlive());
  check(order.equals(List.of("cast-start","cast-commit","restore-checked")));
  System.out.println("PASS "+checks+" projection takeover, exact close, successor ownership and serialized stale-restore checks");
 }
}''',
}
with tempfile.TemporaryDirectory(prefix='remrss-projection-handoff-') as tmp:
    tmp = Path(tmp)
    for name, content in STUBS.items():
        target = tmp / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content)
    java_home = Path(os.environ.get('JAVA_HOME', ROOT / '.local/toolchains/jdk21'))
    javac = str(java_home / 'bin/javac') if (java_home / 'bin/javac').exists() else 'javac'
    java = str(java_home / 'bin/java') if (java_home / 'bin/java').exists() else 'java'
    production = ROOT / 'android/app/src/main/java/top/zainzh/rearscreen/RearAnimationManager.java'
    subprocess.run([javac, '-d', str(tmp), str(production), *map(str, tmp.rglob('*.java'))], check=True)
    subprocess.run([java, '-cp', str(tmp), 'top.zainzh.rearscreen.ProjectionHandoffCheck'], check=True, timeout=15)
