/*
 * Author: AntiOblivionis
 * QQ: 319641317
 * Github: https://github.com/GoldenglowSusie/
 * Bilibili: 罗德岛T0驭械术师澄闪
 *
 * Chief Tester: 汐木泽
 *
 * Co-developed with AI assistants:
 * - Cursor
 * - Claude-4.5-Sonnet
 * - GPT-5
 * - Gemini-2.5-Pro
 */

package top.zainzh.rearscreen;

import android.util.Log;

/**
 * 显示屏信息缓存
 * 在应用启动时获取一次，之后直接使用缓存数据
 */
public class DisplayInfoCache {
    private static final String TAG = "DisplayInfoCache";
    
    // 单例
    private static volatile DisplayInfoCache instance;
    
    // 缓存的背屏信息
    private RearDisplayHelper.RearDisplayInfo cachedInfo;
    private boolean initialized = false;
    
    private DisplayInfoCache() {}
    
    public static DisplayInfoCache getInstance() {
        if (instance == null) {
            synchronized (DisplayInfoCache.class) {
                if (instance == null) {
                    instance = new DisplayInfoCache();
                }
            }
        }
        return instance;
    }
    
    /**
     * 初始化缓存（在应用启动时调用一次）
     */
    public synchronized void initialize(ITaskService taskService) {
        if (isInitialized()) {
            Log.d(TAG, "ℹ️ 已初始化，跳过");
            return;
        }
        
        try {
            Log.d(TAG, "🔄 开始获取背屏信息...");
            RearDisplayHelper.RearDisplayInfo info = RearDisplayHelper.getRearDisplayInfo(taskService);
            if (info == null || !info.isValid()) {
                cachedInfo = null;
                initialized = false;
                Log.w(TAG, "背屏信息暂不可用，保留未初始化状态以便重试");
                return;
            }
            cachedInfo = info;
            initialized = true;
            
            Log.d(TAG, String.format("✅ 背屏信息已缓存: %dx%d, DPI=%d, Cutout=%s",
                cachedInfo.width, cachedInfo.height, cachedInfo.densityDpi,
                cachedInfo.hasCutout() ? cachedInfo.cutout.toString() : "无"));
                
        } catch (Exception e) {
            Log.e(TAG, "❌ 初始化失败", e);
            cachedInfo = null;
            initialized = false;
        }
    }
    
    /**
     * 获取已确认的背屏信息；尚未读取成功时返回 null。
     */
    public synchronized RearDisplayHelper.RearDisplayInfo getCachedInfo() {
        return initialized && cachedInfo != null && cachedInfo.isValid() ? cachedInfo : null;
    }
    
    /**
     * 强制重新获取（用于刷新缓存）
     */
    public synchronized void refresh(ITaskService taskService) {
        cachedInfo = null;
        initialized = false;
        initialize(taskService);
    }
    
    /**
     * 检查是否已初始化
     */
    public synchronized boolean isInitialized() {
        return getCachedInfo() != null;
    }
    
    /**
     * 清除缓存（用于测试或重置）
     */
    public synchronized void clear() {
        cachedInfo = null;
        initialized = false;
        Log.d(TAG, "🗑️ 缓存已清除");
    }
}

