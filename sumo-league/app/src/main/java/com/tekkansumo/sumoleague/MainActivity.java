package com.tekkansumo.sumoleague;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** 対戦表の画面（assets/index.html）を表示するだけの入れ物。 */
public class MainActivity extends Activity {
    private WebView web;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setTextZoom(100);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Store(this), "AndroidStore");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        // シートを閉じる・タブを戻すなど画面側で処理できたら終了しない
        web.evaluateJavascript("(window.handleBack && window.handleBack()) ? 1 : 0", r -> {
            if (!"1".equals(r)) finish();
        });
    }

    /** 入力内容を端末に保存する。WebView の localStorage より消えにくい。 */
    static class Store {
        private final Context ctx;
        private final SharedPreferences prefs;

        Store(Context ctx) {
            this.ctx = ctx.getApplicationContext();
            this.prefs = ctx.getSharedPreferences("league", Context.MODE_PRIVATE);
        }

        @JavascriptInterface
        public String load() {
            return prefs.getString("state", null);
        }

        @JavascriptInterface
        public void save(String json) {
            prefs.edit().putString("state", json).apply();
        }

        @JavascriptInterface
        public void copy(String text) {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("league", text));
        }
    }
}
