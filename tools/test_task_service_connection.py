#!/usr/bin/env python3
"""Run lifecycle regression tests against production Java sources using a small fake Android runtime.

Requires a JDK (javac/java), without Flutter, Gradle, an emulator, or network access.
The fake runtime controls Binder callbacks and time; it does not validate Android system policies.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'top/zainzh/rearscreen'
STUBS = {
'android/annotation/SuppressLint.java': 'package android.annotation; public @interface SuppressLint { String[] value(); }',
 f'{PACKAGE}/RearDisplay.java': 'package top.zainzh.rearscreen; public class RearDisplay {static void attach(ITaskService s){} static int requireId(){return 1;} static int id(){return 1;} }',
'android/content/ComponentName.java': '''package android.content; public class ComponentName { public ComponentName(String p,String c){} }''',
'android/content/Context.java': '''package android.content; public class Context {
 public static final java.util.List<Intent> started=new java.util.ArrayList<>();
 public static final int MODE_PRIVATE=0; public String getPackageName(){return "test.mrss";}
 public Context getApplicationContext(){return this;} private static final java.util.Map<String,SharedPreferences> prefs=new java.util.HashMap<>(); public SharedPreferences getSharedPreferences(String n,int m){return prefs.computeIfAbsent(n,k->new SharedPreferences());}
 public ComponentName startForegroundService(Intent i){started.add(i);return null;} public ComponentName startService(Intent i){started.add(i);return null;}
 public android.content.pm.PackageManager getPackageManager(){return new android.content.pm.PackageManager();}
 public String getString(int id,Object... args){return "连接超时";}
}''',
'android/content/SharedPreferences.java': '''package android.content; public class SharedPreferences {
 private final java.util.Map<String,Object> values=new java.util.HashMap<>();
 public boolean getBoolean(String k,boolean d){return values.containsKey(k)?(Boolean)values.get(k):d;}
 public String getString(String k,String d){return values.containsKey(k)?(String)values.get(k):d;}
 public Editor edit(){return new Editor();} public class Editor { public Editor putString(String k,String v){values.put(k,v);return this;} public Editor putBoolean(String k,boolean v){values.put(k,v);return this;} public void apply(){} }
}''',
'org/json/JSONObject.java': '''package org.json; public class JSONObject { private String text; public JSONObject(String s){text=s;} public String optString(String k,String fallback){return text.contains("running")?"running":fallback;} }''',
f'{PACKAGE}/ScreenRecordService.java': '''package top.zainzh.rearscreen; class ScreenRecordService {static boolean running; static boolean isRunning(){return running;}}''',
f'{PACKAGE}/RootBackend.java': '''package top.zainzh.rearscreen; import android.content.*; public class RootBackend implements PrivilegeBackend {
 static int requests,unbinds; static boolean allowed=true,cached=false,delay=false; static Prepared pending;
 static final java.util.List<ServiceConnection> binds=new java.util.ArrayList<>(); RootBackend(Context c){}
 public State availability(){return cached?State.READY:State.NEEDS_AUTHORIZATION;}
 public void prepare(boolean request,Prepared cb){if(!request){cb.complete(availability());return;} requests++;if(delay){pending=cb;return;}cached=allowed;cb.complete(allowed?State.READY:State.DENIED);}
 public void bind(ServiceConnection c){binds.add(c);}public void unbind(ServiceConnection c){unbinds++;}
 static void ready(int index,android.os.IBinder b){binds.get(index).onServiceConnected(new ComponentName("test","root"),b);}
}''',
'android/content/Intent.java': '''package android.content; public class Intent { public static final String ACTION_VIEW="android.intent.action.VIEW"; public static final int FLAG_ACTIVITY_NEW_TASK=0x10000000,FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS=0x00800000; public Class<?> target; public android.net.Uri data; public String action; public int activityFlags; private java.util.Map<String,Boolean> flags=new java.util.HashMap<>(); public Intent(Context c,Class<?> t){target=t;} public Intent setAction(String a){action=a;return this;} public Intent addFlags(int f){activityFlags|=f;return this;} public Intent setData(android.net.Uri u){data=u;return this;} public Intent putExtra(String n,String v){return this;} public Intent putExtra(String n,boolean v){flags.put(n,v);return this;} public boolean getBooleanExtra(String n,boolean d){return flags.getOrDefault(n,d);} }''',
'android/app/PendingIntent.java': '''package android.app; public class PendingIntent { public static final int FLAG_UPDATE_CURRENT=1,FLAG_IMMUTABLE=2; public static int lastFlags; public android.content.Intent intent; public static PendingIntent getActivity(android.content.Context c,int request,android.content.Intent i,int flags){PendingIntent p=new PendingIntent();p.intent=i;lastFlags=flags;return p;} }''',
'android/net/Uri.java': '''package android.net; public class Uri { private String value; public static Uri parse(String s){Uri u=new Uri();u.value=s;return u;} public String toString(){return value;} }''',
f'{PACKAGE}/BuildConfig.java': '''package top.zainzh.rearscreen; public class BuildConfig { public static boolean INPUT_PROXY_PROTOTYPE=false; public static boolean VIRTUAL_CAST_ENABLED=false; public static final boolean DEBUG=true; public static final String URI_SCHEME="mrss-debug"; }''',
f'{PACKAGE}/UriCommandService.java': '''package top.zainzh.rearscreen; public class UriCommandService {}''',
f'{PACKAGE}/UriReceiverActivity.java': '''package top.zainzh.rearscreen; public class UriReceiverActivity {}''',
f'{PACKAGE}/RearCastMode.java': '''package top.zainzh.rearscreen; public class RearCastMode { public static boolean useVirtual(android.content.Context c){return true;} }''',
'android/content/ServiceConnection.java': '''package android.content; public interface ServiceConnection { void onServiceConnected(ComponentName n,android.os.IBinder b); void onServiceDisconnected(ComponentName n); }''',
'android/content/pm/PackageManager.java': '''package android.content.pm; public class PackageManager { public static final int PERMISSION_GRANTED=0; public Object getApplicationInfo(String p,int f){return p;} public CharSequence getApplicationLabel(Object a){return a.toString();} }''',
'android/os/RemoteException.java': '''package android.os; public class RemoteException extends Exception {}''',
'android/os/IBinder.java': '''package android.os; public interface IBinder { interface DeathRecipient { void binderDied(); } boolean isBinderAlive(); void linkToDeath(DeathRecipient r,int f) throws RemoteException; boolean unlinkToDeath(DeathRecipient r,int f); }''',
'android/os/Looper.java': '''package android.os; public class Looper { private static final Looper MAIN=new Looper(); public static Looper getMainLooper(){return MAIN;} public static Looper myLooper(){return MAIN;} }''',
'android/os/SystemClock.java': '''package android.os; public class SystemClock { public static long now; public static long elapsedRealtime(){return now;} }''',
'android/os/Handler.java': '''package android.os; import java.util.*; public class Handler {
 private static final List<Job> jobs=new ArrayList<>(); private static class Job { Handler h; Runnable r; long at; Job(Handler h,Runnable r,long at){this.h=h;this.r=r;this.at=at;} }
 public Handler(Looper l){} public boolean post(Runnable r){return postDelayed(r,0);}
 public boolean postDelayed(Runnable r,long ms){synchronized(jobs){jobs.add(new Job(this,r,SystemClock.now+ms));}return true;}
 public void removeCallbacks(Runnable r){synchronized(jobs){jobs.removeIf(j->j.h==this&&j.r==r);}}
 public void removeCallbacksAndMessages(Object o){synchronized(jobs){jobs.removeIf(j->j.h==this);}}
 public static void advance(long ms){SystemClock.now+=ms;drain();}
 public static void drain(){while(true){Job next=null;synchronized(jobs){for(Job j:jobs)if(j.at<=SystemClock.now&&(next==null||j.at<next.at))next=j;if(next!=null)jobs.remove(next);}if(next==null)return;next.r.run();}}
}''',
'android/os/Build.java': '''package android.os; public class Build { public static class VERSION { public static int SDK_INT=37; } public static class VERSION_CODES { public static final int O=26,Q=29; } }''',
'android/util/Log.java': '''package android.util; public class Log { public static int i(String t,String m){return 0;} public static int w(String t,String m){return 0;} public static int w(String t,String m,Throwable e){return 0;} public static int e(String t,String m,Throwable e){return 0;} }''',
'android/service/quicksettings/Tile.java': '''package android.service.quicksettings; public class Tile { public static final int STATE_INACTIVE=1; public String subtitle; public void setState(int n){} public void setSubtitle(String s){subtitle=s;} public void setStateDescription(String s){} public void updateTile(){} }''',
'android/service/quicksettings/TileService.java': '''package android.service.quicksettings; public class TileService extends android.content.Context { public static int pendingStarts,intentStarts; private final Tile tile=new Tile(); public Tile getQsTile(){return tile;} public void startActivityAndCollapse(android.app.PendingIntent p){pendingStarts++;started.add(p.intent);} public void startActivityAndCollapse(android.content.Intent i){intentStarts++;started.add(i);} public void onStartListening(){} public void onStopListening(){} public void onDestroy(){} public void onClick(){} }''',
'android/widget/Toast.java': '''package android.widget; public class Toast { public static final int LENGTH_LONG=1,LENGTH_SHORT=0; public static Toast makeText(android.content.Context c,String s,int n){return new Toast();} public void show(){} }''',
'rikka/shizuku/Shizuku.java': '''package rikka.shizuku; import android.content.*; import android.os.*; import java.util.*; public class Shizuku {
 public interface OnBinderReceivedListener{void onBinderReceived();} public interface OnBinderDeadListener{void onBinderDead();} public interface OnRequestPermissionResultListener{void onRequestPermissionResult(int q,int r);}
 public static class UserServiceArgs { public UserServiceArgs(ComponentName c){} public UserServiceArgs daemon(boolean b){return this;} public UserServiceArgs processNameSuffix(String s){return this;} public UserServiceArgs debuggable(boolean b){return this;} public UserServiceArgs version(int v){return this;} }
 public static boolean available=true; public static int permission=0,unbinds,kills;
 public static final List<ServiceConnection> binds=new ArrayList<>(); public static OnBinderReceivedListener received; public static OnBinderDeadListener dead; public static OnRequestPermissionResultListener granted;
 public static boolean pingBinder(){return available;} public static int checkSelfPermission(){return permission;}
 public static void addBinderReceivedListenerSticky(OnBinderReceivedListener l){received=l;l.onBinderReceived();} public static void addBinderDeadListener(OnBinderDeadListener l){dead=l;} public static void addRequestPermissionResultListener(OnRequestPermissionResultListener l){granted=l;}
 public static void bindUserService(UserServiceArgs a,ServiceConnection c){binds.add(c);} public static void unbindUserService(UserServiceArgs a,ServiceConnection c,boolean remove){unbinds++;if(remove)kills++;}
 public static void ready(int index,IBinder b){binds.get(index).onServiceConnected(new ComponentName("test","TaskService"),b);}
}''',
f'{PACKAGE}/ITaskService.java': '''package top.zainzh.rearscreen; public interface ITaskService {
 android.os.IBinder asBinder(); String getForegroundAppOnDisplay(int d); String getCurrentForegroundApp(); default String getRearVirtualCastStatus(){return "{}";} default String getRearInputProxyStatus(){return "{}";} default void stopRearVirtualCast(String s){} default void stopRearInputProxy(String s){} boolean disableSubScreenLauncher(); boolean enableSubScreenLauncher(); boolean moveTaskToDisplay(int t,int d); boolean launchWakeActivity(int d); boolean collapseStatusBar();
 class Stub { public static ITaskService asInterface(android.os.IBinder b){return (ITaskService)b;} }
}''',
f'{PACKAGE}/TaskService.java': '''package top.zainzh.rearscreen; import android.os.*; import java.util.*; public class TaskService implements IBinder,ITaskService {
 public boolean alive=true,failLink,moveSuccess=true; public volatile int moves,restores; public int links; public Runnable beforeTarget; public final List<DeathRecipient> recipients=new ArrayList<>();
 public boolean isBinderAlive(){return alive;} public void linkToDeath(DeathRecipient r,int f) throws RemoteException {if(failLink)throw new RemoteException();recipients.add(r);links++;} public boolean unlinkToDeath(DeathRecipient r,int f){return recipients.remove(r);}
 public void die(){alive=false;for(DeathRecipient r:new ArrayList<>(recipients))r.binderDied();}
 public IBinder asBinder(){return this;} public String getForegroundAppOnDisplay(int d){return null;} public String getCurrentForegroundApp(){if(beforeTarget!=null)beforeTarget.run();return "com.test:42";}
 public boolean disableSubScreenLauncher(){return true;} public boolean enableSubScreenLauncher(){restores++;return true;} public boolean moveTaskToDisplay(int t,int d){moves++;return moveSuccess;} public boolean launchWakeActivity(int d){return true;} public boolean collapseStatusBar(){return true;}
}''',
f'{PACKAGE}/RearScreenKeeperService.java': '''package top.zainzh.rearscreen; public class RearScreenKeeperService {static boolean isRunning(){return false;}}''',
f'{PACKAGE}/R.java': '''package top.zainzh.rearscreen; public class R { public static class string { public static int toast_please_switch_back=1,toast_cast_to_rear=2,privilege_service_unavailable=3,privilege_not_connected=4; } }''',
}
HARNESS = '''package top.zainzh.rearscreen;
import android.content.*; import android.os.*; import rikka.shizuku.Shizuku;
import java.util.concurrent.*;
public class ConnectionRegressionTest {
 static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
 static class Owner implements ServiceConnection { int ready,lost; public void onServiceConnected(ComponentName n,IBinder b){ready++;} public void onServiceDisconnected(ComponentName n){lost++;} }
 static final Context context=new Context();
 static void acquire(Owner o){TaskServiceManager.acquire(context,o);}
 static void awaitFinished(SwitchToRearTileService tile) throws Exception {
  java.lang.reflect.Field f=SwitchToRearTileService.class.getDeclaredField("pending");f.setAccessible(true);
  long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
  while(System.nanoTime()<end){Handler.drain();if(f.get(tile)==null)return;Thread.sleep(2);}
  throw new AssertionError("worker did not finish");
 }
 static void await(java.util.function.BooleanSupplier done) throws Exception {
  long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
  while(System.nanoTime()<end){Handler.drain();if(done.getAsBoolean())return;Thread.sleep(2);}
  throw new AssertionError("async transition timed out");
 }
 public static void main(String[] args) throws Exception {
  String scenario=args[0]; Owner a=new Owner(),b=new Owner(); TaskService service=new TaskService();
  switch(scenario){
   case "switch_timeout_cancel":
    CountDownLatch enteredSwitch=new CountDownLatch(1),resumeSwitch=new CountDownLatch(1),returnedSwitch=new CountDownLatch(1);
    acquire(a);Shizuku.ready(0,new TaskService(){public String getForegroundAppOnDisplay(int d){enteredSwitch.countDown();try{resumeSwitch.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}returnedSwitch.countDown();return null;}});
    java.util.concurrent.atomic.AtomicReference<String> timeoutResult=new java.util.concurrent.atomic.AtomicReference<>("pending");
    TaskServiceManager.switchMode(context,PrivilegeMode.ROOT,timeoutResult::set);check(enteredSwitch.await(1,TimeUnit.SECONDS),"switch worker not entered");
    Handler.advance(8000);check("failed".equals(timeoutResult.get())&&PrivilegeMode.read(context)==PrivilegeMode.SHIZUKU,"switch timeout did not recover");
    PrivilegeOperations.begin();PrivilegeOperations.end();resumeSwitch.countDown();check(returnedSwitch.await(1,TimeUnit.SECONDS),"worker did not resume");Thread.sleep(20);
    check(PrivilegeMode.read(context)==PrivilegeMode.SHIZUKU&&RootBackend.binds.isEmpty(),"late switch committed");TaskServiceManager.release(a);break;
   case "switch_old_callbacks":
    acquire(a);RootBackend.cached=true;
    java.util.concurrent.atomic.AtomicReference<String> switched=new java.util.concurrent.atomic.AtomicReference<>("pending");
    TaskServiceManager.switchMode(context,PrivilegeMode.ROOT,switched::set);await(()->!"pending".equals(switched.get()));
    check(switched.get()==null&&RootBackend.binds.size()==1,"switch did not start root");
    Shizuku.ready(0,service);check(a.ready==0,"old backend callback accepted");RootBackend.ready(0,new TaskService());
    Shizuku.dead.onBinderDead();Shizuku.received.onBinderReceived();check(a.ready==1&&a.lost==0,"old backend event disconnected root");TaskServiceManager.release(a);break;
   case "switch_active_command":
    acquire(a);PrivilegeOperations.begin();java.util.concurrent.atomic.AtomicReference<String> commandResult=new java.util.concurrent.atomic.AtomicReference<>();
    TaskServiceManager.switchMode(context,PrivilegeMode.ROOT,commandResult::set);PrivilegeOperations.end();
    check("busy".equals(commandResult.get())&&PrivilegeMode.read(context)==PrivilegeMode.SHIZUKU,"switched during command");TaskServiceManager.release(a);break;
   case "switch_active_session":
    acquire(a);Shizuku.ready(0,new TaskService(){public String getRearVirtualCastStatus(){return "running";}});
    java.util.concurrent.atomic.AtomicReference<String> sessionResult=new java.util.concurrent.atomic.AtomicReference<>("pending");
    TaskServiceManager.switchMode(context,PrivilegeMode.ROOT,sessionResult::set);await(()->!"pending".equals(sessionResult.get()));
    check("busy".equals(sessionResult.get())&&a.lost==0&&PrivilegeMode.read(context)==PrivilegeMode.SHIZUKU,"switched live session");TaskServiceManager.release(a);break;
   case "root_retry_limit":
    PrivilegeMode.ROOT.save(context);RootBackend.cached=true;acquire(a);
    for(int i=0;i<3;i++){Handler.advance(5000);Handler.advance(3000);}
    check(RootBackend.binds.size()==3&&TaskServiceManager.state(context)==PrivilegeBackend.State.ERROR,"unbounded root retry");
    acquire(a);Handler.advance(100000);check(RootBackend.binds.size()==3,"acquire bypassed retry limit");TaskServiceManager.release(a);break;
   case "root_denied_no_loop":
    PrivilegeMode.ROOT.save(context);RootBackend.allowed=false;acquire(a);
    check(RootBackend.requests==0,"acquire requested su");TaskServiceManager.requestConnection(context);
    check(TaskServiceManager.state(context)==PrivilegeBackend.State.DENIED,"denial lost");
    for(int i=0;i<10;i++){acquire(a);Handler.advance(10000);TaskServiceManager.state(context);}
    check(RootBackend.requests==1&&RootBackend.binds.isEmpty(),"denial loop");TaskServiceManager.release(a);break;
   case "root_authorization_wait":
    PrivilegeMode.ROOT.save(context);RootBackend.delay=true;acquire(a);TaskServiceManager.requestConnection(context);
    Handler.advance(10000);check(TaskServiceManager.state(context)==PrivilegeBackend.State.AUTHORIZING,"authorization used bind timeout");
    RootBackend.cached=true;RootBackend.pending.complete(PrivilegeBackend.State.READY);RootBackend.ready(0,service);
    check(a.ready==1&&Shizuku.binds.isEmpty(),"root depended on Shizuku");TaskServiceManager.release(a);break;
   case "root_cancel_authorization":
    PrivilegeMode.ROOT.save(context);RootBackend.delay=true;acquire(a);TaskServiceManager.requestConnection(context);TaskServiceManager.release(a);
    RootBackend.pending.complete(PrivilegeBackend.State.READY);check(RootBackend.binds.isEmpty(),"cancelled grant bound root");break;
   case "root_shared_release":
    PrivilegeMode.ROOT.save(context);RootBackend.cached=true;acquire(a);acquire(b);RootBackend.ready(0,service);
    TaskServiceManager.release(a);check(RootBackend.unbinds==0&&b.ready==1,"released other root owner");
    TaskServiceManager.release(b);check(RootBackend.unbinds==1&&Shizuku.binds.isEmpty(),"wrong root unbind");break;
   case "shared_release":
    acquire(a);acquire(b);check(Shizuku.binds.size()==1,"duplicate remote bind");Shizuku.ready(0,service);
    check(a.ready==1&&b.ready==1,"missing owner callback");acquire(a);Shizuku.ready(0,service);check(a.ready==1&&service.links==1,"duplicate ready");
    TaskServiceManager.release(a);check(Shizuku.unbinds==0,"released other owner");
    service.die();check(a.lost==0&&b.lost==1,"released owner was notified");Handler.advance(1000);Shizuku.ready(1,new TaskService());check(b.ready==2,"remaining owner not reconnected");TaskServiceManager.release(b);break;
   case "callback_release":
    Owner first=new Owner(){public void onServiceConnected(ComponentName n,IBinder binder){super.onServiceConnected(n,binder);TaskServiceManager.release(this);TaskServiceManager.release(b);acquire(b);}};
    acquire(first);acquire(b);Shizuku.ready(0,service);check(b.ready==0,"reacquired owner received stale callback");Shizuku.ready(1,new TaskService());check(b.ready==1,"reacquired owner not connected");TaskServiceManager.release(b);break;
   case "release_pending":
    acquire(a);TaskServiceManager.release(a);Shizuku.ready(0,service);Handler.advance(10000);
    check(a.ready==0&&Shizuku.binds.size()==1&&Shizuku.unbinds==1,"pending callback retained owner");break;
   case "bind_timeout":
    acquire(a);Handler.advance(5000);check(Shizuku.unbinds==1,"timed-out bind not detached");Handler.advance(1000);
    Shizuku.ready(0,service);check(a.ready==0,"stale callback accepted");Shizuku.ready(1,new TaskService());check(a.ready==1,"retry callback missing");TaskServiceManager.release(a);break;
   case "server_restart":
    acquire(a);Shizuku.ready(0,service);Shizuku.available=false;Shizuku.dead.onBinderDead();Handler.advance(1000);
    check(a.lost==1&&Shizuku.binds.size()==1,"bound unavailable server");Shizuku.available=true;Shizuku.received.onBinderReceived();Shizuku.ready(1,new TaskService());check(a.ready==2,"server restart not recovered");TaskServiceManager.release(a);break;
   case "permission_granted":
    Shizuku.permission=-1;acquire(a);check(Shizuku.binds.isEmpty(),"bound without permission");Shizuku.permission=0;Shizuku.granted.onRequestPermissionResult(0,0);Shizuku.ready(0,service);check(a.ready==1,"permission grant not recovered");TaskServiceManager.release(a);break;
   case "release_after_death":
    acquire(a);Shizuku.ready(0,service);service.die();TaskServiceManager.release(a);Handler.advance(10000);check(Shizuku.binds.size()==1,"reconnected released owner");break;
   case "dead_on_connect":
    acquire(a);service.failLink=true;Shizuku.ready(0,service);check(a.ready==0,"dead binder delivered");Handler.advance(1000);Shizuku.ready(1,new TaskService());check(a.ready==1,"link failure not recovered");TaskServiceManager.release(a);break;
   default:
    SwitchToRearTileService tile=new SwitchToRearTileService();tile.onStartListening();
    try {
     switch(scenario){
      case "tile_prototype_serial_route_pre34": Build.VERSION.SDK_INT=33;
      case "tile_prototype_serial_route":
       BuildConfig.VIRTUAL_CAST_ENABLED=true;tile.onClick();
       check(Context.started.size()==1&&Context.started.get(0).target==UriReceiverActivity.class
        &&Context.started.get(0).data.toString().equals("mrss-debug://switch?current=1")
        &&Intent.ACTION_VIEW.equals(Context.started.get(0).action)
        &&Context.started.get(0).getBooleanExtra("fromQuickSettings",false)&&service.moves==0,"tile lost the foreground Activity route");
       check((Context.started.get(0).activityFlags&Intent.FLAG_ACTIVITY_NEW_TASK)!=0,"tile receiver missing new-task flag");
       if(Build.VERSION.SDK_INT>=34) check(android.service.quicksettings.TileService.pendingStarts==1
        &&(android.app.PendingIntent.lastFlags&android.app.PendingIntent.FLAG_IMMUTABLE)!=0,"modern tile did not use immutable Activity pending intent");
       else check(android.service.quicksettings.TileService.intentStarts==1,"old Android did not use Activity overload");break;
      case "tile_prototype_permission_denied":
       BuildConfig.VIRTUAL_CAST_ENABLED=true;Shizuku.permission=-1;tile.onClick();
       check(Context.started.isEmpty()&&service.moves==0,"prototype bypassed permission gate");break;
      case "tile_delayed_ready": tile.onClick();Handler.advance(1600);Shizuku.ready(0,service);awaitFinished(tile);check(service.moves==1,"click lost after one second");break;
      case "tile_duplicate_click": Shizuku.ready(0,service);tile.onClick();tile.onClick();awaitFinished(tile);check(service.moves==1,"duplicate move");break;
      case "tile_close_pending": tile.onClick();tile.onStopListening();Shizuku.ready(0,service);Handler.drain();check(service.moves==0,"closed panel moved task");break;
      case "tile_timeout": tile.onClick();Handler.advance(5000);Shizuku.ready(0,service);Handler.drain();check(service.moves==0&&tile.getQsTile().subtitle.contains("超时"),"late connection executed expired click");break;
      case "tile_other_owner": acquire(a);Shizuku.ready(0,service);tile.onStopListening();check(Shizuku.unbinds==0&&a.lost==0,"tile killed shared service");TaskServiceManager.release(a);break;
      case "tile_destroy_pending": tile.onClick();tile.onDestroy();Shizuku.ready(0,service);Handler.drain();check(service.moves==0,"destroyed tile moved task");break;
      case "tile_failed_move": service.moveSuccess=false;Shizuku.ready(0,service);tile.onClick();awaitFinished(tile);check(service.restores==1&&tile.getQsTile().subtitle.contains("失败"),"failure did not restore launcher");break;
      case "tile_close_during_target":
       CountDownLatch entered=new CountDownLatch(1),resume=new CountDownLatch(1);
       service.beforeTarget=()->{entered.countDown();try{resume.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}};
       Shizuku.ready(0,service);tile.onClick();check(entered.await(2,TimeUnit.SECONDS),"worker did not start");tile.onStopListening();resume.countDown();awaitFinished(tile);check(service.moves==0,"cancelled operation moved later foreground task");break;
      default:throw new AssertionError("unknown scenario "+scenario);
     }
    } finally {tile.onStopListening();tile.onDestroy();}
  }
  check(Shizuku.kills==0,"killed remote user service");System.out.println("PASS "+scenario);
 }
}
'''

class ConnectionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='mrss-connection-tests-')
        folder = Path(cls.temp.name)
        cls.classes = folder / 'classes'
        cls.classes.mkdir()
        java_home = os.environ.get('JAVA_HOME')
        cls.javac = str(Path(java_home) / 'bin/javac') if java_home else shutil.which('javac')
        cls.java = str(Path(java_home) / 'bin/java') if java_home else shutil.which('java')
        if not cls.javac or not cls.java:
            raise RuntimeError('A JDK with javac/java is required')
        for name, source in STUBS.items():
            path = folder / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source)
        path = folder / PACKAGE / 'ConnectionRegressionTest.java'
        path.write_text(HARNESS)
        sources = list(folder.rglob('*.java'))
        sources += [ROOT / 'android/app/src/main/java' / PACKAGE / name for name in
                    ['TaskServiceManager.java', 'SwitchToRearTileService.java', 'PrivilegeMode.java', 'PrivilegeBackend.java', 'ShizukuBackend.java', 'PrivilegeOperations.java']]
        subprocess.run([cls.javac, '--release', '11', '-d', str(cls.classes), *map(str, sources)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def run_scenario(self, name):
        result = subprocess.run([self.java, '-cp', str(self.classes),
                                 'top.zainzh.rearscreen.ConnectionRegressionTest', name],
                                text=True, capture_output=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('PASS ' + name, result.stdout)

for scenario in ['switch_timeout_cancel', 'switch_old_callbacks', 'switch_active_command', 'switch_active_session', 'root_retry_limit', 'root_denied_no_loop', 'root_authorization_wait', 'root_cancel_authorization', 'root_shared_release', 'shared_release', 'callback_release', 'release_pending', 'bind_timeout', 'server_restart',
                 'permission_granted', 'release_after_death', 'dead_on_connect',
                 'tile_delayed_ready', 'tile_duplicate_click', 'tile_close_pending',
                 'tile_timeout', 'tile_other_owner', 'tile_destroy_pending',
                 'tile_failed_move', 'tile_close_during_target', 'tile_prototype_serial_route',
                 'tile_prototype_serial_route_pre34', 'tile_prototype_permission_denied']:
    setattr(ConnectionTests, 'test_' + scenario, lambda self, name=scenario: self.run_scenario(name))

if __name__ == '__main__':
    unittest.main(verbosity=2)
