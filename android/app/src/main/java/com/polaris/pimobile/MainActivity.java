package com.polaris.pimobile;

import android.os.Bundle;
import android.util.Log;
import android.webkit.WebSettings;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    private ApkUpdater apkUpdater;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        apkUpdater = new ApkUpdater(this);
        // 注入到 WebView：window.PiApk.getState() / download(url) / install()
        if (getBridge() != null && getBridge().getWebView() != null) {
            WebView wv = getBridge().getWebView();
            wv.addJavascriptInterface(apkUpdater, "PiApk");

            // 页面在 https://localhost，图片来自 http://<ip>:8848 —— 属于混合内容。
            // MIXED_CONTENT_ALWAYS_ALLOW 是唯一能让 <img> 加载 http 的档位，
            // 注意：WebView 一旦设置过该模式就不能再改回，所以只在首次设置。
            try {
                wv.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
                Log.i("MainActivity", "mixedContentMode = ALWAYS_ALLOW");
            } catch (Exception e) {
                Log.w("MainActivity", "setMixedContentMode failed: " + e.getMessage());
            }
        }
    }

    /**
     * 返回键：先交给网页处理（关抽屉/回上级目录/回引导页），
     * 网页返回 "handled" 或 false 时才真的退出。
     */
    @Override
    public void onDestroy() {
        if (apkUpdater != null) apkUpdater.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        final WebView webView = getBridge() != null ? getBridge().getWebView() : null;
        if (webView == null) {
            super.onBackPressed();
            return;
        }
        webView.evaluateJavascript(
            "(function(){try{return (window.__onAndroidBack && window.__onAndroidBack()) || 'exit';}catch(e){return 'exit';}})()",
            value -> {
                boolean handled = value != null && value.contains("handled");
                if (!handled) {
                    runOnUiThread(() -> moveTaskToBack(true));
                }
            }
        );
    }
}
