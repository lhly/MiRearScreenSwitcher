/*
 * Author: AntiOblivionis
 * QQ: 319641317
 * Github: https://github.com/GoldenglowSusie/
 * Bilibili: 罗德岛T0驭械术师澄闪
 * 
 * Co-developed with AI assistants:
 * - Cursor
 * - Claude-4.5-Sonnet
 * - GPT-5
 * - Gemini-2.5-Pro
 */

package top.zainzh.rearscreen;

import android.util.Log;
import android.app.Application;
import rikka.sui.Sui;

/**
 * 自定义Application - 初始化Shizuku
 */
public class MyApplication extends Application {
    
    private static final String TAG = "MyApplication";
    
    static {
        // 关键！在静态块中初始化Sui
        try {
            Sui.init(BuildConfig.APPLICATION_ID);
        } catch (Throwable e) {
            Log.e(TAG, "Sui init failed", e);
        }
    }
    
}
