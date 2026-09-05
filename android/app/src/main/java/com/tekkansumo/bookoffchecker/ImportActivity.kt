package com.tekkansumo.bookoffchecker

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONTokener

/**
 * お気に入りなどのページから商品IDを取り込む画面。
 *
 * お気に入りはログインが必要なので、アプリ内のブラウザでログインしてもらい、
 * 表示中のページから商品IDを拾う。ページの HTML 構造に依存しないよう、
 * /used/<数字> の形を総当たりで拾う方式にしてある。
 */
class ImportActivity : AppCompatActivity() {

    companion object {
        private const val START_URL = "https://shopping.bookoff.co.jp/"

        /** 表示中のページから商品IDを集めて JSON 文字列で返す。 */
        private const val JS_EXTRACT = """
        (function(){
          var s = [];
          var seen = {};
          function add(id){ if(!seen[id]){ seen[id] = 1; s.push(id); } }
          // a タグを優先（表示順を保てる）
          var as = document.querySelectorAll('a[href]');
          for (var i = 0; i < as.length; i++) {
            var m = as[i].href.match(/\/used\/(\d{8,12})/);
            if (m) add(m[1]);
          }
          // JS 描画などで a タグ以外に入っている場合の保険
          var h = document.documentElement.innerHTML;
          var re = /\/used\/(\d{8,12})/g, m2;
          while ((m2 = re.exec(h)) !== null) add(m2[1]);
          return JSON.stringify(s);
        })()
        """
    }

    private lateinit var web: WebView
    private lateinit var status: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#16110d"))
        }

        status = TextView(this).apply {
            text = "ログインして「お気に入り」ページを開き、下のボタンを押してください"
            setTextColor(Color.parseColor("#a08d78"))
            textSize = 12f
            setPadding(24, 18, 24, 6)
        }

        val btn = Button(this).apply {
            text = "このページから取り込む"
            setTextColor(Color.parseColor("#1b1309"))
            setBackgroundColor(Color.parseColor("#e0a458"))
            setOnClickListener { extract() }
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 0, 24, 24)
            setBackgroundColor(Color.parseColor("#1d1712"))
            addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(btn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        web = WebView(this)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }
        // ログイン遷移をアプリ内で完結させたいので外部ブラウザには渡さない
        web.webViewClient = WebViewClient()
        web.loadUrl(START_URL)

        root.addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })
    }

    override fun onPause() {
        // ログイン状態を次回も使えるように書き出す
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private fun extract() {
        web.evaluateJavascript(JS_EXTRACT) { raw ->
            val ids = decode(raw)
            if (ids.isEmpty()) {
                status.text = "このページには商品IDが見つかりませんでした"
                return@evaluateJavascript
            }
            val (added, totalCount) = Store.addIds(this, ids)
            status.text = "このページで ${ids.size} 件検出　→　新規 $added 件を追加（合計 $totalCount 件）"
        }
    }

    /** evaluateJavascript の結果は JSON エンコードされた文字列で返る。 */
    private fun decode(raw: String?): List<String> {
        if (raw == null) return emptyList()
        return try {
            val v = JSONTokener(raw).nextValue()
            val arr = if (v is String) JSONArray(v) else return emptyList()
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
