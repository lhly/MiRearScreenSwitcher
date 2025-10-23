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

package com.tgwgroup.MiRearScreenSwitcher;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

/**
 * V2.6: URI接收Activity
 * 完全透明，只负责转发URI到UriCommandService，然后立即finish
 * 不会显示任何UI，避免跳到MRSS页面
 */
public class UriReceiverActivity extends Activity {
    private static final String TAG = "UriReceiverActivity";
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 不设置任何布局，保持透明
        
        Intent intent = getIntent();
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction())) {
            Uri uri = intent.getData();
            if (uri != null && "mrss".equals(uri.getScheme())) {
                Log.d(TAG, "🔗 URI接收: " + uri.toString());
                
                // 转发到UriCommandService处理
                Intent serviceIntent = new Intent(this, UriCommandService.class);
                serviceIntent.setData(uri);
                startService(serviceIntent);
                
                Log.d(TAG, "✓ 已转发到UriCommandService");
            }
        }
        
        // 立即finish，不显示任何UI
        finish();
    }
    
    @Override
    public void finish() {
        super.finish();
        // 禁用转场动画，完全透明
        overridePendingTransition(0, 0);
    }
}

