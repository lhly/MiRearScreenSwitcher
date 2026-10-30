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
    private ActivityMonitor homeMonitor;
    private boolean editingDpi;
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
        if ("android.widget.EditText".contentEquals(n.getClassName())
                && (!editingDpi || (n.getText()!=null && n.getText().toString().matches("[0-9]*")))) return n;
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
        for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo found=clickable(n.getChild(i),text);if(found!=null)return found;}
        // The dialog scrim is also clickable and contains all button text. Prefer the actual child action.
        if(n.isClickable() && contains(n,text))return n;
        return null;
    }
    private AccessibilityNodeInfo entry() { return clickable(root(),getTargetContext().getString(R.string.rear_density_title)); }
    private AccessibilityNodeInfo description(AccessibilityNodeInfo n, String value) {
        if(n==null)return null;
        if(n.isClickable() && n.getContentDescription()!=null && value.contentEquals(n.getContentDescription()))return n;
        for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo found=description(n.getChild(i),value);if(found!=null)return found;}
        return null;
    }
    private AccessibilityNodeInfo described(AccessibilityNodeInfo n,String value) {
        if(n==null)return null;
        if(n.getContentDescription()!=null&&value.contentEquals(n.getContentDescription()))return n;
        for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo found=described(n.getChild(i),value);if(found!=null)return found;}
        return null;
    }
    private void search(String value) throws Exception {
        await("Search field",()->field()!=null);
        Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);
        await("Set text", () -> { AccessibilityNodeInfo n=field();return n!=null && n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args); });
        await("Search committed",()->{AccessibilityNodeInfo n=field();return n!=null && value.contentEquals(n.getText());});
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
    private void action(String text) throws Exception {
        await("Visible action " + text, () -> { AccessibilityNodeInfo r=root();if(clickable(r,text)!=null)return true;scrollForward(r);return false; });
        AccessibilityNodeInfo node = clickable(root(), text);
        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
        if (!node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw new AssertionError("Action denied: " + text);
    }
    private boolean scrollForward(AccessibilityNodeInfo n) {
        if(n==null)return false;
        for(int i=0;i<n.getChildCount();i++)if(scrollForward(n.getChild(i)))return true;
        // A numeric field can expose horizontal scrolling; only scroll the vertical dialog body.
        return n.isScrollable() && n.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN)
                && n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId());
    }
    private void layoutEditor() throws Exception {
        android.content.SharedPreferences p = RearDensityPreferences.preferences(getTargetContext());
        String enabledKey = RearDensityPreferences.key(enabledPackage), dpiKey = "dpi:" + enabledPackage;
        Object oldEnabled = p.getAll().get(enabledKey), oldDpi = p.getAll().get(dpiKey);
        String night = shell("cmd uimode night").trim().replace("Night mode: ", "");
        String font = shell("settings get system font_scale").trim();
        String rotation = shell("wm user-rotation -d 0").trim();
        try {
            p.edit().remove(dpiKey).commit(); search(enabledPackage); choose(enabledPackage, true);
            action(getTargetContext().getString(R.string.rear_density_layout_title));
            editingDpi = true;
            await("Numeric editor opened", () -> field()!=null);
            search("71"); action(getTargetContext().getString(R.string.rear_density_layout_save));
            await("Invalid value rejected", () -> contains(root(), getTargetContext().getString(R.string.rear_density_layout_error)));
            if (RearDensityPreferences.layoutDensity(getTargetContext(), enabledPackage) != 0) throw new AssertionError("Invalid DPI persisted");
            search("200"); DensityCompatibilityActivity old = activity(); runOnMainSync(old::recreate);
            await("Editor draft survives recreation", () -> { AccessibilityNodeInfo n=field();return activity()!=old && n!=null && "200".contentEquals(n.getText()); });
            action(getTargetContext().getString(R.string.rear_density_layout_save));
            await("Saved numeric DPI", () -> RearDensityPreferences.layoutDensity(getTargetContext(), enabledPackage)==200);
            editingDpi = false;
            passed("layout_DPI_validation_draft_recreation_and_save"); shot("layout-value");
            action(getTargetContext().getString(R.string.rear_density_layout_title));
            editingDpi = true;
            shell("cmd uimode night no"); SystemClock.sleep(1000); shot("layout-light");
            shell("settings put system font_scale 1.5"); shell("cmd uimode night yes");
            await("Large type", () -> activity().getResources().getConfiguration().fontScale>1.4f); shot("layout-dark-large");
            shell("wm user-rotation -d 0 lock 1");
            await("Landscape editor", () -> activity().getResources().getConfiguration().orientation==2);
            shot("layout-landscape"); search("220");
            if (!field().performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId()))
                throw new AssertionError("IME Done action unavailable");
            await("Landscape IME saves DPI", () -> RearDensityPreferences.layoutDensity(getTargetContext(), enabledPackage)==220);
            editingDpi = false;
            await("IME editor dismissed", () -> { AccessibilityNodeInfo n=field();return n!=null && enabledPackage.contentEquals(n.getText()); });
            SystemClock.sleep(500);
            action(getTargetContext().getString(R.string.rear_density_layout_title));
            editingDpi = true;
            shot("layout-before-reset");
            action(getTargetContext().getString(R.string.rear_density_layout_reset));
            await("DPI reset", () -> RearDensityPreferences.layoutDensity(getTargetContext(), enabledPackage)==0);
            editingDpi = false;
            passed("layout_editor_theme_large_text_landscape_and_reset");
        } finally {
            android.content.SharedPreferences.Editor e=p.edit().remove(enabledKey).remove(dpiKey);
            if(oldEnabled instanceof Boolean)e.putBoolean(enabledKey,(Boolean)oldEnabled);
            if(oldDpi instanceof Integer)e.putInt(dpiKey,(Integer)oldDpi); e.commit();
            shell("wm user-rotation -d 0 " + rotation);
            shell(font.equals("null") ? "settings delete system font_scale" : "settings put system font_scale "+font);
            shell("cmd uimode night " + night);
        }
    }
    private DensityCompatibilityActivity activity() { return (DensityCompatibilityActivity) monitor.getLastActivity(); }
    private android.app.Activity home() { return homeMonitor.getLastActivity(); }
    private AccessibilityNodeInfo modeRow() { return row(root(),getTargetContext().getString(R.string.rear_cast_mode_title)); }
    private boolean scrollHome(AccessibilityNodeInfo n,AccessibilityNodeInfo.AccessibilityAction action) {
        if(n==null)return false;
        for(int i=0;i<n.getChildCount();i++)if(scrollHome(n.getChild(i),action))return true;
        return n.isScrollable()&&n.getActionList().contains(action)&&n.performAction(action.getId());
    }
    private void homeTop() {
        for(int i=0;i<15&&scrollHome(root(),AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP);i++)SystemClock.sleep(100);
    }
    private void showMode() throws Exception {
        if(modeRow()==null)homeTop();
        await("Visible casting mode",()->{if(modeRow()!=null)return true;scrollForward(root());return false;});
        modeRow().performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
        SystemClock.sleep(250);
    }
    private void chooseMode(boolean value) throws Exception {
        showMode();
        if(modeRow().isChecked()!=value&&!modeRow().performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new AssertionError("Mode click denied");
        await("Mode and density entry agree",()->modeRow()!=null&&modeRow().isChecked()==value
                &&RearCastMode.useVirtual(getTargetContext())==value&&(entry()!=null)==value);
    }
    private void visibility() throws Exception {
        android.content.SharedPreferences modePreferences=RearCastMode.preferences(getTargetContext());
        android.content.SharedPreferences density=RearDensityPreferences.preferences(getTargetContext());
        Object oldMode=modePreferences.getAll().get(RearCastMode.VIRTUAL);
        String enabledKey=RearDensityPreferences.key(enabledPackage),dpiKey="dpi:"+enabledPackage;
        Object oldEnabled=density.getAll().get(enabledKey),oldDpi=density.getAll().get(dpiKey);
        String night=shell("cmd uimode night").trim().replace("Night mode: ","");
        String font=shell("settings get system font_scale").trim(),rotation=shell("wm user-rotation -d 0").trim();
        try {
            RearDensityPreferences.setEnabled(getTargetContext(),enabledPackage,true);
            RearDensityPreferences.setLayoutDensity(getTargetContext(),enabledPackage,220);
            shell("cmd uimode night no");shell("settings put system font_scale 1.0");shell("wm user-rotation -d 0 lock 0");
            chooseMode(false);shot("visibility-light-off");
            chooseMode(true);shot("visibility-light-on");passed("home_toggle_immediately_hides_and_shows_density_entry");
            if(!entry().performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new AssertionError("Density entry denied");
            await("Density page opened",()->activity()!=null&&field()!=null);
            search(enabledPackage);checked(enabledPackage,true);
            if(RearDensityPreferences.layoutDensity(getTargetContext(),enabledPackage)!=220)throw new AssertionError("DPI cleared by mode change");
            AccessibilityNodeInfo back=description(root(),activity().getString(R.string.ui_back));
            if(back==null||!back.performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new AssertionError("Back unavailable");
            await("Density entry after return",()->entry()!=null);chooseMode(false);
            android.app.Activity before=home();runOnMainSync(before::recreate);
            await("Recreated home keeps entry hidden",()->home()!=before&&modeRow()!=null&&!modeRow().isChecked()&&entry()==null);
            chooseMode(true);
            if(!RearDensityPreferences.enabled(getTargetContext(),enabledPackage)
                    ||RearDensityPreferences.layoutDensity(getTargetContext(),enabledPackage)!=220)throw new AssertionError("Per-App settings lost");
            passed("off_recreation_return_and_reenable_preserve_app_preferences");
            RearCastMode.setVirtual(getTargetContext(),false);
            await("External mode update hides entry",()->modeRow()!=null&&!modeRow().isChecked()&&entry()==null);
            RearCastMode.setVirtual(getTargetContext(),true);
            await("External mode update shows entry",()->modeRow()!=null&&modeRow().isChecked()&&entry()!=null);
            passed("shared_preference_updates_refresh_both_controls");
            shell("cmd uimode night yes");shell("settings put system font_scale 1.5");
            await("Dark large home",()->home().getResources().getConfiguration().fontScale>1.4f
                    &&(home().getResources().getConfiguration().uiMode&48)==32);
            chooseMode(false);shot("visibility-dark-large-off");chooseMode(true);shot("visibility-dark-large-on");
            shell("wm user-rotation -d 0 lock 1");
            await("Landscape home",()->home().getResources().getConfiguration().orientation==2);
            chooseMode(false);shot("visibility-landscape-off");chooseMode(true);
            entry().performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
            await("Landscape density entry above navigation",()->{
                AccessibilityNodeInfo n=entry(),nav=described(root(),NativeStrings.get(getTargetContext(),"screen_title"));
                if(n==null||nav==null)return false;
                android.graphics.Rect bounds=new android.graphics.Rect(),navigation=new android.graphics.Rect();
                n.getBoundsInScreen(bounds);nav.getBoundsInScreen(navigation);
                if(!bounds.isEmpty()&&bounds.top>=0&&bounds.bottom<navigation.top-16)return true;
                android.graphics.Rect window=new android.graphics.Rect();root().getBoundsInScreen(window);
                int x=window.centerX(),startY=window.top+window.height()*2/3,endY=window.top+window.height()/4;
                shell("input swipe "+x+" "+startY+" "+x+" "+endY+" 350");
                SystemClock.sleep(350);return false;
            });shot("visibility-landscape-on");
            DensityCompatibilityActivity oldPage=activity();
            android.graphics.Rect entryBounds=new android.graphics.Rect();entry().getBoundsInScreen(entryBounds);
            shell("input tap "+entryBounds.centerX()+" "+entryBounds.centerY());
            await("Landscape entry accepts touch",()->activity()!=oldPage&&field()!=null);
            AccessibilityNodeInfo returnButton=description(root(),activity().getString(R.string.ui_back));
            if(returnButton==null||!returnButton.performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new AssertionError("Landscape return denied");
            await("Landscape home after return",()->entry()!=null);
            passed("light_dark_large_type_and_landscape_visibility");
        } finally {
            android.content.SharedPreferences.Editor m=modePreferences.edit().remove(RearCastMode.VIRTUAL);
            if(oldMode instanceof Boolean)m.putBoolean(RearCastMode.VIRTUAL,(Boolean)oldMode);m.commit();
            android.content.SharedPreferences.Editor d=density.edit().remove(enabledKey).remove(dpiKey);
            if(oldEnabled instanceof Boolean)d.putBoolean(enabledKey,(Boolean)oldEnabled);
            if(oldDpi instanceof Integer)d.putInt(dpiKey,(Integer)oldDpi);d.commit();
            shell("wm user-rotation -d 0 "+rotation);
            shell(font.equals("null")?"settings delete system font_scale":"settings put system font_scale "+font);
            shell("cmd uimode night "+night);
        }
    }
    private AccessibilityNodeInfo text(AccessibilityNodeInfo n, String value) {
        if(n==null)return null;
        if(n.getText()!=null&&value.contentEquals(n.getText()))return n;
        for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo child=text(n.getChild(i),value);if(child!=null)return child;}
        return null;
    }
    private String group(boolean enabled,int count) {
        return activity().getString(enabled?R.string.rear_density_group_enabled:R.string.rear_density_group_disabled,count);
    }
    private java.util.Set<String> scanHome() {
        homeTop();java.util.Set<String> found=new java.util.HashSet<>();
        String[] keys={"screen_size","dpi_caption","rotation_caption","proximity_sensor","keep_screen_on","always_wake_up",
                "charging_animation","charging_always_on","tutorial"};
        for(int i=0;i<20;i++){
            AccessibilityNodeInfo tree=root();
            for(String key:keys)if(contains(tree,NativeStrings.get(getTargetContext(),key)))found.add(key);
            if(contains(tree,getTargetContext().getString(R.string.rear_screenshot_title)))found.add("screenshot");
            if(!scrollHome(tree,AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN))break;
            SystemClock.sleep(150);
        }
        return found;
    }
    private void modeSettings() throws Exception {
        android.content.SharedPreferences prefs=RearCastMode.preferences(getTargetContext());
        Object oldMode=prefs.getAll().get(RearCastMode.VIRTUAL);
        java.util.Map<String,?> nativeBefore=new java.util.HashMap<>(getTargetContext().getSharedPreferences("mrss_settings",0).getAll());
        java.util.Map<String,?> legacyBefore=new java.util.HashMap<>(getTargetContext().getSharedPreferences("FlutterSharedPreferences",0).getAll());
        try {
            chooseMode(false);SystemClock.sleep(400);java.util.Set<String> direct=scanHome();
            chooseMode(true);SystemClock.sleep(400);java.util.Set<String> virtual=scanHome();
            for(String key:new String[]{"screen_size","dpi_caption","rotation_caption"}){
                if(!direct.contains(key)||virtual.contains(key))throw new AssertionError("Mode-specific visibility: "+key+" direct="+direct+" virtual="+virtual);
            }
            passed("physical_display_section_only_in_direct_mode");
            for(String key:new String[]{"proximity_sensor","keep_screen_on","always_wake_up","charging_animation","charging_always_on","tutorial","screenshot"})
                if(!direct.contains(key)||!virtual.contains(key))throw new AssertionError("Common control missing: "+key);
            if(!nativeBefore.equals(getTargetContext().getSharedPreferences("mrss_settings",0).getAll())
                    ||!legacyBefore.equals(getTargetContext().getSharedPreferences("FlutterSharedPreferences",0).getAll()))throw new AssertionError("Mode change modified common preferences");
            passed("common_wake_charging_screenshot_and_tutorial_preserved");
        } finally {
            android.content.SharedPreferences.Editor e=prefs.edit().remove(RearCastMode.VIRTUAL);
            if(oldMode instanceof Boolean)e.putBoolean(RearCastMode.VIRTUAL,(Boolean)oldMode);e.commit();
        }
    }
    private int screenshotY() throws Exception {
        AccessibilityNodeInfo n=text(root(),getTargetContext().getString(R.string.rear_screenshot_title));
        if(n==null)throw new AssertionError("Screenshot section is not visible");
        android.graphics.Rect r=new android.graphics.Rect();n.getBoundsInScreen(r);return r.top;
    }
    private void motion() throws Exception {
        android.content.SharedPreferences prefs=RearCastMode.preferences(getTargetContext());Object oldMode=prefs.getAll().get(RearCastMode.VIRTUAL);
        String scale=shell("settings get global animator_duration_scale").trim(),font=shell("settings get system font_scale").trim();
        String rotation=shell("wm user-rotation -d 0").trim();
        try {
            shell("settings put system font_scale 1.0");shell("wm user-rotation -d 0 lock 0");
            shell("settings put global animator_duration_scale 4");SystemClock.sleep(500);
            chooseMode(false);SystemClock.sleep(1200);homeTop();shot("motion-before");int before=screenshotY();
            long start=SystemClock.uptimeMillis();runOnMainSync(()->RearCastMode.setVirtual(getTargetContext(),true));
            SystemClock.sleep(160);int intermediate=screenshotY();
            // Capture immediately: shot() deliberately waits for settled UI and cannot prove motion.
            Bitmap frame=getUiAutomation().takeScreenshot();
            try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getFilesDir(),"density-motion-intermediate.png"))){frame.compress(Bitmap.CompressFormat.PNG,100,out);}finally{frame.recycle();}
            long sampled=SystemClock.uptimeMillis()-start;SystemClock.sleep(1200);int after=screenshotY();shot("motion-after");
            if(before==after||intermediate<=Math.min(before,after)||intermediate>=Math.max(before,after))
                throw new AssertionError("No intermediate layout: "+before+","+intermediate+","+after+" sampled="+sampled);
            Bundle measurement=new Bundle();measurement.putString("motion_positions",before+","+intermediate+","+after+" sample_ms="+sampled);sendStatus(1,measurement);
            passed("measured_intermediate_expand_and_collapse_frame_at_system_scale_4");
            for(int i=0;i<7;i++){final boolean value=(i&1)==0;runOnMainSync(()->RearCastMode.setVirtual(getTargetContext(),value));SystemClock.sleep(60);}
            runOnMainSync(()->RearCastMode.setVirtual(getTargetContext(),false));SystemClock.sleep(1200);
            await("Rapid reversal settled in direct mode",()->modeRow()!=null&&!modeRow().isChecked()&&entry()==null);
            passed("rapid_reversals_retarget_without_stale_controls");
            shell("settings put global animator_duration_scale 0");SystemClock.sleep(500);homeTop();int noMotionBefore=screenshotY();
            runOnMainSync(()->RearCastMode.setVirtual(getTargetContext(),true));SystemClock.sleep(60);int immediate=screenshotY();
            SystemClock.sleep(300);int settled=screenshotY();
            if(immediate!=settled||immediate==noMotionBefore)throw new AssertionError("Disabled system animations did not settle immediately");
            passed("system_animation_scale_zero_snaps_to_final_layout");
        } finally {
            android.content.SharedPreferences.Editor e=prefs.edit().remove(RearCastMode.VIRTUAL);if(oldMode instanceof Boolean)e.putBoolean(RearCastMode.VIRTUAL,(Boolean)oldMode);e.commit();
            shell(scale.equals("null")?"settings delete global animator_duration_scale":"settings put global animator_duration_scale "+scale);
            shell(font.equals("null")?"settings delete system font_scale":"settings put system font_scale "+font);shell("wm user-rotation -d 0 "+rotation);
        }
    }
    private void groups() throws Exception {
        android.content.SharedPreferences p=RearDensityPreferences.preferences(getTargetContext());
        java.util.Map<String,?> saved=new java.util.HashMap<>(p.getAll());
        java.util.List<String> packages=new java.util.ArrayList<>();
        for(android.content.pm.ResolveInfo app:getTargetContext().getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)) {
            String pkg=app.activityInfo.packageName;
            if(!pkg.equals(getTargetContext().getPackageName())&&!packages.contains(pkg))packages.add(pkg);
        }
        String night=shell("cmd uimode night").trim().replace("Night mode: ", "");
        String font=shell("settings get system font_scale").trim(),rotation=shell("wm user-rotation -d 0").trim();
        try {
            android.content.SharedPreferences.Editor e=p.edit();
            for(String pkg:packages)e.putBoolean(RearDensityPreferences.key(pkg),pkg.equals(enabledPackage));
            e.commit();search("");
            await("Enabled section above disabled",()->{
                AccessibilityNodeInfo on=text(root(),group(true,1)),off=text(root(),group(false,packages.size()-1));
                if(on==null||off==null||row(enabledPackage)==null)return false;
                android.graphics.Rect a=new android.graphics.Rect(),b=new android.graphics.Rect(),c=new android.graphics.Rect();
                on.getBoundsInScreen(a);row(enabledPackage).getBoundsInScreen(b);off.getBoundsInScreen(c);
                return a.top<b.top&&b.top<c.top&&on.isHeading()&&off.isHeading();
            });passed("enabled_first_group_count_and_heading_semantics");
            search(disabledPackage);checked(disabledPackage,false);
            await("Only disabled matches",()->text(root(),group(false,1))!=null&&text(root(),group(true,1))==null);
            choose(disabledPackage,true);
            await("Toggle moves to enabled group",()->text(root(),group(true,1))!=null&&text(root(),group(false,1))==null);
            choose(disabledPackage,false);
            await("Toggle moves to disabled group",()->text(root(),group(false,1))!=null&&text(root(),group(true,1))==null);
            passed("switch_regroups_immediately_during_package_search");
            search(getTargetContext().getPackageManager().getApplicationLabel(
                    getTargetContext().getPackageManager().getApplicationInfo(enabledPackage,0)).toString());
            checked(enabledPackage,true);search("remrss.no.such.group.match");
            await("Empty results have no groups",()->contains(root(),activity().getString(R.string.rear_density_empty))
                    &&text(root(),group(true,1))==null&&text(root(),group(false,1))==null);
            search(enabledPackage);DensityCompatibilityActivity old=activity();runOnMainSync(old::recreate);
            await("Search/group survives recreation",()->activity()!=old&&field()!=null&&enabledPackage.contentEquals(field().getText())
                    &&text(root(),group(true,1))!=null);checked(enabledPackage,true);
            passed("name_search_empty_result_and_recreation");
            AccessibilityNodeInfo back=description(root(),activity().getString(R.string.ui_back));
            if(back==null||!back.performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new AssertionError("Back unavailable");
            await("Home after back",()->entry()!=null);shot("groups-home");
            final DensityCompatibilityActivity previous=activity();
            if(!entry().performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new AssertionError("Reopen denied");
            await("Reopen puts enabled group first",()->activity()!=previous&&text(root(),group(true,1))!=null);
            passed("back_and_reopen_preserve_enabled_choice");
            e=p.edit();for(String pkg:packages)e.putBoolean(RearDensityPreferences.key(pkg),false);e.commit();search("");
            await("No empty enabled section",()->text(root(),group(false,packages.size()))!=null&&text(root(),group(true,0))==null);
            e=p.edit();for(String pkg:packages)e.putBoolean(RearDensityPreferences.key(pkg),true);e.commit();
            await("No empty disabled section",()->text(root(),group(true,packages.size()))!=null&&text(root(),group(false,0))==null);
            passed("all_off_and_all_on_hide_empty_sections");
            e=p.edit();for(String pkg:packages)e.putBoolean(RearDensityPreferences.key(pkg),pkg.equals(enabledPackage));e.commit();
            shell("settings put system font_scale 1.0");shell("wm user-rotation -d 0 lock 0");shell("cmd uimode night no");
            await("Light grouped list",()->(activity().getResources().getConfiguration().uiMode&48)==16&&text(root(),group(true,1))!=null);
            shot("groups-light");shell("cmd uimode night yes");
            await("Dark grouped list",()->(activity().getResources().getConfiguration().uiMode&48)==32);shot("groups-dark");
            shell("settings put system font_scale 1.5");
            await("Large grouped list",()->activity().getResources().getConfiguration().fontScale>1.4f);shot("groups-dark-large");
            shell("wm user-rotation -d 0 lock 1");
            await("Landscape grouped list",()->activity().getResources().getConfiguration().orientation==2);shot("groups-landscape-large");
            search(enabledPackage);checked(enabledPackage,true);
            await("Landscape search group",()->text(root(),group(true,1))!=null);shot("groups-landscape-search");
            passed("light_dark_large_font_and_landscape_groups");
        } finally {
            android.content.SharedPreferences.Editor e=p.edit().clear();
            for(java.util.Map.Entry<String,?> v:saved.entrySet()){
                if(v.getValue() instanceof Boolean)e.putBoolean(v.getKey(),(Boolean)v.getValue());
                else if(v.getValue() instanceof Integer)e.putInt(v.getKey(),(Integer)v.getValue());
                else if(v.getValue() instanceof String)e.putString(v.getKey(),(String)v.getValue());
            }e.commit();
            shell("wm user-rotation -d 0 "+rotation);
            shell(font.equals("null")?"settings delete system font_scale":"settings put system font_scale "+font);
            shell("cmd uimode night "+night);
        }
    }
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
            homeMonitor=addMonitor(MainActivity.class.getName(),null,false);
            // HyperOS rejects a private settings Activity started by a background instrumented UID.
            // Enter through the same visible home row that the user uses.
            shell("am start --display 0 -n " + getTargetContext().getPackageName() + "/top.zainzh.rearscreen.MainActivity");
            await("Home navigation", () -> root()!=null);
            AccessibilityNodeInfo screen = description(root(), NativeStrings.get(getTargetContext(), "screen_title"));
            if(screen!=null)screen.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if("mode_settings".equals(mode)) {
                modeSettings();
            } else if("motion".equals(mode)) {
                motion();
            } else if("visibility".equals(mode)) {
                visibility();
            } else {
            await("Home density entry",()->entry()!=null);
            if(!entry().performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new AssertionError("Home entry click denied");
            await("Settings Activity on main display",()->activity()!=null && activity().getDisplay().getDisplayId()==0);
            if("groups".equals(mode)) {
                groups();
            } else if("layout".equals(mode)) {
                layoutEditor();
            } else if("setup".equals(mode)){
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
            }
            result.putString("result",checks.toString());
        } catch(Throwable e){code=1;result.putString("error",android.util.Log.getStackTraceString(e));result.putString("completed",checks.toString());try{shot("layout-failure");}catch(Exception ignored){}}
        finally {if(monitor!=null)removeMonitor(monitor);if(homeMonitor!=null)removeMonitor(homeMonitor);}
        finish(code,result);
    }
}
