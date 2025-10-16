package com.tgwgroup.MiRearScreenSwitcher;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import io.flutter.app.FlutterApplication;
import rikka.sui.Sui;

/**
 * 自定义Application - 初始化Shizuku
 */
public class MyApplication extends FlutterApplication {
    
    private static final String TAG = "MyApplication";
    private static boolean isSui = false;
    
    static {
        // 关键！在静态块中初始化Sui
        try {
            isSui = Sui.init("com.tgwgroup.MiRearScreenSwitcher");
            Log.d(TAG, "Sui initialized: " + isSui);
        } catch (Throwable e) {
            Log.e(TAG, "Sui init failed", e);
        }
    }
    
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        
        // HiddenAPI豁免（Android 9+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                Class<?> hiddenApiBypass = Class.forName("org.lsposed.hiddenapibypass.HiddenApiBypass");
                hiddenApiBypass.getMethod("addHiddenApiExemptions", String.class)
                    .invoke(null, "L");
                Log.d(TAG, "HiddenApiBypass enabled");
            } catch (Exception e) {
                Log.w(TAG, "HiddenApiBypass not available: " + e.getMessage());
            }
        }
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Application onCreate");
    }
    
    public static boolean isSui() {
        return isSui;
    }
}

