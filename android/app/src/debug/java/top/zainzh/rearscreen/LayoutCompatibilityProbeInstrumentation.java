package top.zainzh.rearscreen;

import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Opt-in physical-device matrix; uses an intentionally restricted Activity and restores test preferences. */
@android.annotation.TargetApi(33)
public final class LayoutCompatibilityProbeInstrumentation extends Instrumentation {
    private ITaskService remote;
    private boolean startedByProbe;
    private String mode;
    private int externalDisplay;
    private String label;
    private String expectedViewport;
    private final CountDownLatch connected = new CountDownLatch(1);
    private final JSONArray checks = new JSONArray();
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName n, IBinder b) { remote = ITaskService.Stub.asInterface(b); connected.countDown(); }
        public void onServiceDisconnected(ComponentName n) { remote = null; }
    };
    private interface Check { boolean ready() throws Exception; }
    private void await(String label, Check check) throws Exception {
        long end = SystemClock.uptimeMillis() + 12000;
        do { if (check.ready()) return; SystemClock.sleep(100); } while (SystemClock.uptimeMillis() < end);
        throw new AssertionError(label);
    }
    private String shell(String command) throws Exception {
        try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(command));ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8");
        }
    }
    private String pkg(){return getTargetContext().getPackageName();}
    private JSONObject state()throws Exception{return new JSONObject(remote.getRearVirtualCastStatus());}
    private void pass(String label)throws Exception{checks.put(label);Bundle b=new Bundle();b.putString("check",label);sendStatus(1,b);}
    private JSONObject startCast(int requested, boolean explicit)throws Exception{
        startedByProbe = true;
        shell("input -d 1 keyevent KEYCODE_WAKEUP");
        shell("am start --display 1 --windowingMode 1 -n "+pkg()+"/top.zainzh.rearscreen.VirtualRearHostActivity --ei task -1 --ei dpi "+requested
            +" --ez explicitDpi "+explicit+" --es component "+pkg()+"/top.zainzh.rearscreen.FixtureRestrictedLayoutActivity");
        await("restricted target cast",()->"running".equals(state().optString("state"))&&FixtureRestrictedLayoutActivity.instance!=null);
        return state();
    }
    private void stop()throws Exception{
        if (!startedByProbe) return;
        int host = state().optInt("host", -1);
        remote.stopRearVirtualCast(null);
        await("host and input released",()->!"running".equals(state().optString("state"))&&!"running".equals(new JSONObject(remote.getRearInputProxyStatus()).optString("state"))
                && (host < 0 || !remote.isTaskOnDisplay(host, 1)));
        FixtureRestrictedLayoutActivity a=FixtureRestrictedLayoutActivity.instance;if(a!=null)runOnMainSync(a::finish);
        await("fixture closed",()->FixtureRestrictedLayoutActivity.instance==null);
        SystemClock.sleep(500);
        startedByProbe = false;
    }
    private void menu(int x)throws Exception{
        String host=pkg()+"/top.zainzh.rearscreen.VirtualRearHostActivity";
        shell("am start --display 1 -n "+host+" --ez preview_menu true");SystemClock.sleep(300);
        shell("am start --display 1 -n "+host+" --ef preview_menu_x "+x+" --ef preview_menu_y 298");
    }
    private void verify(int rotation,boolean full,int dpi)throws Exception{
        await("rotation and viewport",()->state().optInt("contentRotation")==rotation&&state().optBoolean("fullViewport")==full&&!state().optBoolean("viewportChanging"));
        JSONObject s=state();if(!"unrestricted".equals(s.optString("sizePolicy"))||s.getInt("requestedDensity")!=dpi)throw new AssertionError(s.toString());
        RearCastViewport v=RearCastGeometry.decode(s.getJSONObject("viewport").toString());
        if(!full&&expectedViewport!=null&&!expectedViewport.equals(v.left+","+v.top+","+v.right+","+v.bottom))
            throw new AssertionError("Unexpected production viewport "+s);
        await("real unrestricted Activity configuration",()->{
            FixtureRestrictedLayoutActivity a=FixtureRestrictedLayoutActivity.instance;
            if(a==null||a.getDisplay().getDisplayId()!=s.getInt("display"))return false;
            android.graphics.Rect bounds=a.getWindowManager().getCurrentWindowMetrics().getBounds();
            return bounds.width()==v.contentWidth(rotation)&&bounds.height()==v.contentHeight(rotation)
                &&a.getResources().getConfiguration().orientation==(v.contentWidth(rotation)>=v.contentHeight(rotation)?2:1);
        });
        // Window metrics settle before the opening/rotation transition's input transform.
        SystemClock.sleep(700);
        FixtureRestrictedLayoutActivity a=FixtureRestrictedLayoutActivity.instance;int before=a.ups;
        String output=remote.executeShellCommandWithResult("CLASSPATH='"+getTargetContext().getApplicationInfo().sourceDir+"' app_process / top.zainzh.rearscreen.RearViewportShellFixture "+s.getInt("display")+" tap 500 300 2>&1");
        if(!output.contains("completed tap"))throw new AssertionError(output);
        await("mapped tap received",()->FixtureRestrictedLayoutActivity.instance!=null&&FixtureRestrictedLayoutActivity.instance.ups==before+1);
        a=FixtureRestrictedLayoutActivity.instance;float[] expected=v.contentPoint(500,300,rotation);
        if(Math.abs(a.lastX-expected[0])>2||Math.abs(a.lastY-expected[1])>2)throw new AssertionError("touch mismatch "+a.lastX+","+a.lastY+" expected "+Arrays.toString(expected)+" state="+s+" injection="+output);
        RearPresentationPreferences.Selection saved=RearPresentationPreferences.read(getTargetContext(),pkg());
        if(saved.rotation!=rotation||saved.full!=full)throw new AssertionError("Presentation choice lost");
        shell("screencap -p -d 4630946457447247252 /data/local/tmp/layout_"+dpi+"_"+rotation+"_"+full+".png");
        pass("dpi_"+dpi+"_rotation_"+rotation+"_full_"+full+"_actual_bounds_and_touch");
    }
    private void restore(SharedPreferences p,Map<String,?> values){
        SharedPreferences.Editor e=p.edit().clear();for(Map.Entry<String,?> item:values.entrySet()){
            Object v=item.getValue();if(v instanceof Boolean)e.putBoolean(item.getKey(),(Boolean)v);else if(v instanceof Integer)e.putInt(item.getKey(),(Integer)v);else if(v instanceof String)e.putString(item.getKey(),(String)v);
        }e.commit();
    }
    private void douyin() throws Exception {
        String target="com.ss.android.ugc.aweme";
        for(int variant=0;variant<3;variant++) {
            int dpi=variant==2?200:240;
            RearDensityPreferences.setEnabled(getTargetContext(),target,variant!=0);
            RearDensityPreferences.setLayoutDensity(getTargetContext(),target,dpi);
            RearPresentationPreferences.saveRotation(getTargetContext(),target,0);
            RearPresentationPreferences.saveViewport(getTargetContext(),target,!"douyinSafe".equals(mode));
            shell("am start --display 0 -n com.ss.android.ugc.aweme/.main.MainActivity");
            SystemClock.sleep(1000);
            int task=remote.getTaskIdByPackage(target);
            if(task<0||!remote.isTaskOnDisplay(task,0))throw new AssertionError("Douyin task unavailable");
            startedByProbe=true;
            shell("input -d 1 keyevent KEYCODE_WAKEUP");
            shell("am start --display 1 --windowingMode 1 -n "+pkg()+"/top.zainzh.rearscreen.VirtualRearHostActivity --ei task "+task+" --ei dpi 240 --es targetPackage "+target);
            await("Douyin cast",()->"running".equals(state().optString("state"))&&target.equals(state().optString("package")));
            SystemClock.sleep(2200);
            JSONObject s=state();
            if(s.getInt("requestedDensity")!=dpi||!s.optString("sizePolicy").equals(variant==0?"disabled":"unrestricted"))throw new AssertionError(s.toString());
            JSONArray windows=new JSONArray();
            java.util.List<android.view.accessibility.AccessibilityWindowInfo> visible=getUiAutomation().getWindowsOnAllDisplays().get(s.getInt("display"));
            if(visible!=null)for(android.view.accessibility.AccessibilityWindowInfo window:visible){
                android.view.accessibility.AccessibilityNodeInfo node=window.getRoot();
                if(node!=null&&target.contentEquals(node.getPackageName())){
                    android.graphics.Rect bounds=new android.graphics.Rect();window.getBoundsInScreen(bounds);
                    windows.put(new JSONObject().put("bounds",bounds.toShortString()).put("type",window.getType()));
                }
            }
            s.put("targetWindows",windows);
            String name=mode+"_"+variant+"_dpi_"+dpi;
            shell("screencap -p -d 4630946457447247252 /data/local/tmp/"+name+".png");
            try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getFilesDir(),name+".json"))){out.write(s.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            pass(name+"_"+s.optString("sizePolicy"));
            stop();
        }
    }
    private JSONObject nodeGeometry(android.view.accessibility.AccessibilityNodeInfo node, int depth) throws Exception {
        android.graphics.Rect bounds=new android.graphics.Rect(); node.getBoundsInScreen(bounds);
        JSONObject j=new JSONObject().put("class",String.valueOf(node.getClassName()))
                .put("id",node.getViewIdResourceName()).put("bounds",bounds.toShortString()).put("visible",node.isVisibleToUser());
        JSONArray children=new JSONArray();
        if(depth<35)for(int i=0;i<node.getChildCount();i++){
            android.view.accessibility.AccessibilityNodeInfo child=node.getChild(i);
            if(child!=null)children.put(nodeGeometry(child,depth+1));
        }
        return j.put("children",children); // Deliberately omit text/descriptions from the third-party page.
    }
    private void videoSnapshot(String name, int display) throws Exception {
        JSONObject result=state();
        getUiAutomation().clearCache();
        java.util.List<android.view.accessibility.AccessibilityWindowInfo> windows=getUiAutomation().getWindowsOnAllDisplays().get(display);
        JSONArray nodes=new JSONArray();
        if(windows!=null)for(android.view.accessibility.AccessibilityWindowInfo window:windows){
            android.view.accessibility.AccessibilityNodeInfo node=window.getRoot();
            if(node!=null&&"com.ss.android.ugc.aweme".contentEquals(node.getPackageName()))nodes.put(nodeGeometry(node,0));
        }
        result.put("targetWindows",nodes);
        String raw=shell("dumpsys window windows");
        JSONArray frames=new JSONArray();
        for(String block:raw.split("  Window #")){
            int end=block.indexOf('\n');String header=end<0?block:block.substring(0,end);
            if(header.contains("com.ss.android.ugc.aweme")){
                for(String line:block.split("\n"))if(line.contains("Frames:")||line.contains("mFullConfiguration=")||line.contains("mDisplayId=")||line.contains("isVisible="))frames.put(line.trim());
            }
        }
        result.put("windowFrames",frames);
        String physical=display==0?"4630946457447247251":"4630946457447247252";
        shell("screencap -p -d "+physical+" /data/local/tmp/"+name+".png");
        try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getFilesDir(),name+".json"))){out.write(result.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        pass(name);
    }
    private void video() throws Exception {
        String target="com.ss.android.ugc.aweme";
        shell("am start --display 0 -n com.ss.android.ugc.aweme/.main.MainActivity");
        SystemClock.sleep(1700);
        // One tap pauses the current feed item; no swiping, liking or account actions.
        shell("input -d 0 tap 600 1200");SystemClock.sleep(700);
        videoSnapshot("video_main_reference",0);
        for(int variant=0;variant<3;variant++){
            int dpi=variant==2?200:240;
            RearDensityPreferences.setEnabled(getTargetContext(),target,variant!=0);
            RearDensityPreferences.setLayoutDensity(getTargetContext(),target,dpi);
            RearPresentationPreferences.saveRotation(getTargetContext(),target,0);
            RearPresentationPreferences.saveViewport(getTargetContext(),target,false);
            int task=remote.getTaskIdByPackage(target);
            if(task<0||!remote.isTaskOnDisplay(task,0))throw new AssertionError("Video task unavailable");
            startedByProbe=true;shell("input -d 1 keyevent KEYCODE_WAKEUP");
            shell("am start --display 1 --windowingMode 1 -n "+pkg()+"/top.zainzh.rearscreen.VirtualRearHostActivity --ei task "+task+" --ei dpi 240 --es targetPackage "+target);
            await("video cast",()->"running".equals(state().optString("state"))&&target.equals(state().optString("package")));
            SystemClock.sleep(1800);videoSnapshot("video_"+variant+"_dpi_"+dpi,state().getInt("display"));
            if(variant==1){
                shell("wm set-ignore-orientation-request -d "+state().getInt("display")+" true");
                SystemClock.sleep(800);videoSnapshot("video_1_ignore_orientation",state().getInt("display"));
                for(int rotation=1;rotation<4;rotation++){menu(700);final int wanted=rotation;await("video rotation",()->state().optInt("contentRotation")==wanted);SystemClock.sleep(900);videoSnapshot("video_rotation_"+rotation,state().getInt("display"));}
            }
            stop();SystemClock.sleep(600);
        }
    }
    private void matchedVideo() throws Exception {
        String target="com.ss.android.ugc.aweme";
        RearDensityPreferences.setEnabled(getTargetContext(),target,true);
        RearDensityPreferences.setLayoutDensity(getTargetContext(),target,200);
        RearPresentationPreferences.saveRotation(getTargetContext(),target,0);
        RearPresentationPreferences.saveViewport(getTargetContext(),target,false);
        for(boolean direct:new boolean[]{false,true}){
            int task=remote.getTaskIdByPackage(target);
            if(task<0||!remote.isTaskOnDisplay(task,0))throw new AssertionError("Matched source unavailable");
            startedByProbe=true;shell("input -d 1 keyevent KEYCODE_WAKEUP");
            shell("am start --display 1 --windowingMode 1 -n "+pkg()+"/top.zainzh.rearscreen.VirtualRearHostActivity --ei task "+(direct?-1:task)
                +" --ei dpi 200 --es targetPackage "+target+(direct?" --es component com.ss.android.ugc.aweme/.main.MainActivity":""));
            await("matched video",()->"running".equals(state().optString("state"))&&target.equals(state().optString("package")));
            SystemClock.sleep(1500);videoSnapshot(label+(direct?"_entry":"_move"),state().getInt("display"));stop();
        }
    }
    private android.graphics.Rect largestSurface(android.view.accessibility.AccessibilityNodeInfo node, android.graphics.Rect best){
        if("android.view.SurfaceView".contentEquals(node.getClassName())&&node.isVisibleToUser()){
            android.graphics.Rect bounds=new android.graphics.Rect();node.getBoundsInScreen(bounds);
            if(bounds.width()>0&&bounds.height()>0&&(long)bounds.width()*bounds.height()>(long)best.width()*best.height())best=bounds;
        }
        for(int i=0;i<node.getChildCount();i++){
            android.view.accessibility.AccessibilityNodeInfo child=node.getChild(i);
            if(child!=null)best=largestSurface(child,best);
        }
        return best;
    }
    private void findLandscape()throws Exception{
        int task=remote.getTaskIdByPackage("com.ss.android.ugc.aweme");
        if(task<0||!remote.isTaskOnDisplay(task,0))throw new AssertionError("Feed unavailable on main display");
        shell("am task focus "+task);SystemClock.sleep(900);
        for(int i=0;i<12;i++){
            getUiAutomation().clearCache();android.graphics.Rect best=new android.graphics.Rect();
            java.util.List<android.view.accessibility.AccessibilityWindowInfo> windows=getUiAutomation().getWindowsOnAllDisplays().get(0);
            if(windows!=null)for(android.view.accessibility.AccessibilityWindowInfo window:windows){
                android.view.accessibility.AccessibilityNodeInfo node=window.getRoot();
                if(node!=null&&"com.ss.android.ugc.aweme".contentEquals(node.getPackageName()))best=largestSurface(node,best);
            }
            if(best.width()>best.height()*1.3f){videoSnapshot("landscape_main_reference",0);return;}
            shell("input -d 0 swipe 600 1800 600 500 300");SystemClock.sleep(1000);
        }
        throw new AssertionError("No landscape player in twelve feed entries; no media assumption made");
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);mode=args==null?null:args.getString("mode");externalDisplay=args==null?0:Integer.parseInt(args.getString("display","0"));label=args==null?"snapshot":args.getString("label","snapshot");expectedViewport=args==null?null:args.getString("expected_viewport");if(!label.matches("[a-z0-9_]+"))throw new IllegalArgumentException("Invalid label");start();}
    @Override public void onStart(){
        Bundle result=new Bundle();int code=0;Context c=getTargetContext();
        SharedPreferences density=RearDensityPreferences.preferences(c),presentation=RearPresentationPreferences.preferences(c);
        Map<String,?> oldDensity=new HashMap<>(density.getAll()),oldPresentation=new HashMap<>(presentation.getAll());
        try{
            android.accessibilityservice.AccessibilityServiceInfo accessibility=getUiAutomation().getServiceInfo();
            accessibility.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    |android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                    |android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            getUiAutomation().setServiceInfo(accessibility);
            runOnMainSync(()->TaskServiceManager.acquire(c,connection));if(!connected.await(8,TimeUnit.SECONDS))throw new AssertionError("Shizuku unavailable");
            if("running".equals(state().optString("state")))throw new AssertionError("Stop the existing debug cast first");
            if("external".equals(mode)){videoSnapshot(label,externalDisplay);result.putString("result",checks.toString());}
            else if("find_landscape".equals(mode)){findLandscape();result.putString("result",checks.toString());}
            else if("matched".equals(mode)){matchedVideo();result.putString("result",checks.toString());}
            else if("video".equals(mode)) { video(); result.putString("result",checks.toString()); }
            else if("douyin".equals(mode)||"douyinSafe".equals(mode)) { douyin(); result.putString("result",checks.toString()); }
            else {
            RearDensityPreferences.setEnabled(c,pkg(),true);
            for(int dpi:new int[]{240,200})for(boolean full:new boolean[]{false,true}){
                RearDensityPreferences.setLayoutDensity(c,pkg(),dpi);RearPresentationPreferences.saveRotation(c,pkg(),0);RearPresentationPreferences.saveViewport(c,pkg(),full);
                startCast(240,false);verify(0,full,dpi);
                for(int r=1;r<4;r++){menu(700);verify(r,full,dpi);}
                stop();
            }
            RearDensityPreferences.setLayoutDensity(c,pkg(),200); // Saved rotation3/full survives DPI change and a fresh session.
            startCast(320,true);verify(3,true,320);stop();pass("explicit_URI_DPI_overrides_saved_DPI_and_restores_rotation");
            result.putString("result",checks.toString());
            }
        }catch(Throwable error){code=1;result.putString("error",error.toString());result.putString("completed",checks.toString());}
        finally{
            try{if(remote!=null)stop();}catch(Exception ignored){}
            restore(density,oldDensity);restore(presentation,oldPresentation);
            runOnMainSync(()->TaskServiceManager.release(connection));
        }
        try(FileOutputStream out=new FileOutputStream(new File(c.getFilesDir(),"layout-compatibility-result.json"))){out.write(result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception ignored){}
        finish(code,result);
    }
}
