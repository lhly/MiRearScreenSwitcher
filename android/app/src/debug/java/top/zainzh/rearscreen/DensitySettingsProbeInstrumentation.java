package top.zainzh.rearscreen;

import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.graphics.Bitmap;
import java.io.*;
import org.json.*;

/** Real accessibility actions. Supply two installed test apps as disabled_package and enabled_package. */
public final class DensitySettingsProbeInstrumentation extends Instrumentation {
    private String mode;
    private String disabledPackage;
    private String enabledPackage;
    private ActivityMonitor monitor;
    private final JSONArray checks = new JSONArray();
    private interface Check { boolean ready() throws Exception; }
    private AccessibilityNodeInfo root() {
        getUiAutomation().clearCache();
        java.util.List<android.view.accessibility.AccessibilityWindowInfo> windows=getUiAutomation().getWindowsOnAllDisplays().get(0);
        if(windows!=null)for(android.view.accessibility.AccessibilityWindowInfo window:windows){
            AccessibilityNodeInfo n=window.getRoot();
            if(n!=null && getTargetContext().getPackageName().contentEquals(n.getPackageName()))return n;
        }
        return null;
    }
    private void await(String label, Check check) throws Exception {
        long end = SystemClock.uptimeMillis() + 12000;
        do { if (check.ready()) return; SystemClock.sleep(100); } while (SystemClock.uptimeMillis() < end);
        throw new AssertionError(label);
    }
    private AccessibilityNodeInfo field(AccessibilityNodeInfo n) {
        if (n == null) return null;
        if ("android.widget.EditText".contentEquals(n.getClassName())) return n;
        for (int i=0;i<n.getChildCount();i++) { AccessibilityNodeInfo found=field(n.getChild(i));if(found!=null)return found; }
        return null;
    }
    private AccessibilityNodeInfo field() { return field(root()); }
    private boolean contains(AccessibilityNodeInfo n,String text) {
        if(n==null)return false;
        if(n.getText()!=null && n.getText().toString().contains(text))return true;
        for(int i=0;i<n.getChildCount();i++)if(contains(n.getChild(i),text))return true;
        return false;
    }
    private AccessibilityNodeInfo row(AccessibilityNodeInfo n,String pkg) {
        if(n==null)return null;
        if(n.isCheckable() && contains(n,pkg))return n;
        for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo found=row(n.getChild(i),pkg);if(found!=null)return found;}
        return null;
    }
    private AccessibilityNodeInfo row(String pkg) { return row(root(),pkg); }
    private AccessibilityNodeInfo clickable(AccessibilityNodeInfo n,String text) {
        if(n==null)return null;
        if(n.isClickable() && contains(n,text))return n;
        for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo found=clickable(n.getChild(i),text);if(found!=null)return found;}
        return null;
    }
    private AccessibilityNodeInfo entry() { return clickable(root(),getTargetContext().getString(R.string.rear_density_title)); }
    private void search(String value) throws Exception {
        await("Search field",()->field()!=null);
        Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);
        if(!field().performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args))throw new AssertionError("Set search text denied");
        await("Search committed",()->field()!=null && value.contentEquals(field().getText()));
    }
    private void checked(String pkg,boolean value) throws Exception {
        await("Visible row "+pkg,()->row(pkg)!=null);
        if(row(pkg).isChecked()!=value)throw new AssertionError("Wrong UI switch state for "+pkg);
        if(RearDensityPreferences.enabled(getTargetContext(),pkg)!=value)throw new AssertionError("UI preference mismatch");
    }
    private void choose(String pkg,boolean value) throws Exception {
        await("Row before toggle",()->row(pkg)!=null);
        if(row(pkg).isChecked()!=value && !row(pkg).performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new AssertionError("Row click denied");
        await("Toggle applied",()->row(pkg)!=null && row(pkg).isChecked()==value);checked(pkg,value);
    }
    private void passed(String label) { checks.put(label);Bundle b=new Bundle();b.putString("check",label);sendStatus(1,b); }
    private void shot(String name) throws Exception {
        // Configuration callbacks precede the OEM theme/rotation transition and Compose reload.
        waitForIdleSync();SystemClock.sleep(1800);Bitmap image=getUiAutomation().takeScreenshot();if(image==null)throw new AssertionError("Screenshot unavailable");
        try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getFilesDir(),"density-"+name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}
    }
    private String shell(String command) throws Exception {
        try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(command));ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))>=0)out.write(buffer,0,n);return out.toString("UTF-8");
        }
    }
    private DensityCompatibilityActivity activity() { return (DensityCompatibilityActivity) monitor.getLastActivity(); }
    @Override public void onCreate(Bundle args){
        super.onCreate(args);mode=args.getString("mode","setup");
        disabledPackage=args.getString("disabled_package");enabledPackage=args.getString("enabled_package");start();
    }
    @Override public void onStart(){
        Bundle result=new Bundle();int code=0;
        try {
            VirtualCastRequest.packageName(disabledPackage);VirtualCastRequest.packageName(enabledPackage);
            if(disabledPackage.equals(enabledPackage))throw new IllegalArgumentException("Supply two distinct test apps");
            android.content.pm.PackageManager packages=getTargetContext().getPackageManager();
            String disabledLabel=packages.getApplicationLabel(packages.getApplicationInfo(disabledPackage,0)).toString();
            packages.getApplicationInfo(enabledPackage,0);
            android.accessibilityservice.AccessibilityServiceInfo info=getUiAutomation().getServiceInfo();
            info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            getUiAutomation().setServiceInfo(info);
            monitor=addMonitor(DensityCompatibilityActivity.class.getName(),null,false);
            // HyperOS rejects a private settings Activity started by a background instrumented UID.
            // Enter through the same visible home row that the user uses.
            shell("am start --display 0 -n " + getTargetContext().getPackageName() + "/top.zainzh.rearscreen.MainActivity");
            await("Home density entry",()->entry()!=null);
            if(!entry().performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new AssertionError("Home entry click denied");
            await("Settings Activity on main display",()->activity()!=null && activity().getDisplay().getDisplayId()==0);
            if("setup".equals(mode)){
                search(disabledLabel);checked(disabledPackage,false);passed("default_off_and_name_search");
                search(disabledPackage);choose(disabledPackage,true);choose(disabledPackage,false);passed("explicit_on_then_off");shot("app-off");
                search(enabledPackage);checked(enabledPackage,false);choose(enabledPackage,true);passed("other_app_default_off_then_enabled");shot("app-on");
            } else {
                search(disabledPackage);checked(disabledPackage,false);passed("explicit_off_survives_cold_process");
                search(enabledPackage);checked(enabledPackage,true);passed("explicit_on_survives_cold_process");
                DensityCompatibilityActivity old=activity();runOnMainSync(old::recreate);
                await("Activity recreated",()->activity()!=old && field()!=null && enabledPackage.contentEquals(field().getText()));checked(enabledPackage,true);
                passed("recreation_keeps_search_and_choice");
                try {
                    shell("cmd uimode night no");await("Light theme",()->(activity().getResources().getConfiguration().uiMode&48)==16);shot("light");
                    shell("settings put system font_scale 1.3");await("Large font",()->Math.abs(activity().getResources().getConfiguration().fontScale-1.3)<.01 && field()!=null && enabledPackage.contentEquals(field().getText()));checked(enabledPackage,true);shot("light-large");
                    shell("cmd uimode night yes");await("Dark theme",()->(activity().getResources().getConfiguration().uiMode&48)==32);checked(enabledPackage,true);shot("dark-large");
                    shell("wm user-rotation -d 0 lock 1");await("Landscape",()->activity().getResources().getConfiguration().orientation==2);checked(enabledPackage,true);shot("landscape-large");
                    shell("wm user-rotation -d 0 lock 0");await("Portrait",()->activity().getResources().getConfiguration().orientation==1 && field()!=null && enabledPackage.contentEquals(field().getText()));checked(enabledPackage,true);shot("portrait-restored");
                    passed("theme_font_landscape_search_and_state");
                } finally { shell("wm user-rotation -d 0 lock 0");shell("settings put system font_scale 1.0");shell("cmd uimode night auto"); }
            }
            result.putString("result",checks.toString());
        } catch(Throwable e){code=1;result.putString("error",e.toString());result.putString("completed",checks.toString());}
        finally {if(monitor!=null)removeMonitor(monitor);}
        finish(code,result);
    }
}
