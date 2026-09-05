package com.tekkansumo.bookoffchecker

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 取得がうまくいかないときの原因切り分け用。
 *
 * 同じ商品ページを 2 通りで取って比べる。
 *   1. 静的 HTML（OkHttp + Jsoup）… アプリが実際に使っている方法
 *   2. WebView（JavaScript 実行後）… ブラウザで見えているのと同じ状態
 *
 * 1 が 0 件で 2 が取れるなら在庫情報が JS 描画なので取得方法ごと変える必要が
 * あり、両方 0 件ならページ構成が想定と違う。1 が取れているなら表示側の問題。
 */
class DiagnoseActivity : AppCompatActivity() {

    companion object {
        private const val JS = """
        (function(){
          var out={title:'',h1:'',links:0,shop:[],len:0};
          out.title=document.title||'';
          var h=document.querySelector('h1');
          out.h1=h?(h.innerText||h.textContent||'').replace(/\s+/g,' ').trim():'';
          var as=document.querySelectorAll('a[href]');
          out.links=as.length;
          for(var i=0;i<as.length;i++){
            var raw=as[i].getAttribute('href')||'';
            if(/\/shop\/shop\d+/i.test(raw)||/\/shop\/shop\d+/i.test(as[i].href||'')){
              var t=(as[i].textContent||'').replace(/\s+/g,' ').trim();
              out.shop.push(t.slice(0,40)+'  ->  '+raw.slice(0,70));
            }
          }
          out.len=document.documentElement.innerHTML.length;
          return JSON.stringify(out);
        })()
        """
    }

    private lateinit var report: TextView
    private lateinit var web: WebView
    private lateinit var input: EditText
    private val sb = StringBuilder()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#16110d"))
            setPadding(20, 20, 20, 20)
        }

        input = EditText(this).apply {
            setText(Store.ids(this@DiagnoseActivity).firstOrNull() ?: "0016309421")
            setTextColor(Color.parseColor("#f0e6da"))
            hint = "商品ID"
        }

        val run = Button(this).apply {
            text = "この商品IDで診断する"
            setTextColor(Color.parseColor("#1b1309"))
            setBackgroundColor(Color.parseColor("#e0a458"))
            setOnClickListener { start() }
        }

        val share = Button(this).apply {
            text = "結果を共有（開発者に送る）"
            setOnClickListener {
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, sb.toString())
                    }, "診断結果を送る"))
            }
        }

        report = TextView(this).apply {
            setTextColor(Color.parseColor("#f0e6da"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            text = "商品IDを入れて「診断する」を押してください。\n" +
                    "静的HTMLとWebViewの両方で店舗リンクを数えます。"
        }

        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // XHR で後から差し込まれる場合があるので 2 回みる
                web.postDelayed({ evaluate("3秒後") }, 3000)
                web.postDelayed({ evaluate("8秒後"); finishReport() }, 8000)
            }
        }

        root.addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(run, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(ScrollView(this).apply { addView(report) },
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 260))
        root.addView(share, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(root)
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private fun log(s: String) {
        sb.append(s).append('\n')
        runOnUiThread { report.text = sb.toString() }
    }

    private fun pid() = input.text.toString().filter { it.isDigit() }.padStart(10, '0')

    private fun start() {
        sb.setLength(0)
        val pid = pid()
        log("商品ID: $pid")
        log("URL: ${Scraper.url(pid)}")
        log("")
        log("=== 1. 静的HTML（アプリが実際に使っている方法）===")
        Thread {
            try {
                Scraper.fetch(pid).use { r ->
                    val body = r.body?.string() ?: ""
                    log("HTTP ${r.code} / ${body.length} 文字")
                    if (body.isEmpty()) {
                        log("本文が空です。")
                    } else {
                        val (name, shops) = Scraper.parse(Scraper.url(pid), body)
                        log("h1: ${name ?: "(見つからない)"}")
                        val raw = Regex("""/shop/shop\d+""", RegexOption.IGNORE_CASE)
                            .findAll(body).count()
                        log("HTML 内の /shop/shopNNN の出現数: $raw")
                        log("抽出できた在庫店舗: ${shops.size} 件")
                        shops.take(10).forEach { log("  ${it.name}  ->  ${it.url}") }
                        if (shops.isEmpty()) {
                            log("  ※ 0 件。ここが原因の可能性が高い")
                        }
                    }
                }
            } catch (e: Exception) {
                log("失敗: ${e.javaClass.simpleName}: ${e.message}")
            }
            log("")
            log("=== 2. WebView（JavaScript 実行後）===")
            log("読み込み中...")
            runOnUiThread { web.loadUrl(Scraper.url(pid())) }
        }.apply { isDaemon = true }.start()
    }

    private fun evaluate(label: String) {
        web.evaluateJavascript(JS) { raw ->
            try {
                val v = JSONTokener(raw).nextValue()
                val o = JSONObject(v as String)
                val shop = o.getJSONArray("shop")
                log("[$label] title: ${o.optString("title").take(50)}")
                log("[$label] h1: ${o.optString("h1").take(50)}")
                log("[$label] HTML ${o.optInt("len")} 文字 / <a> ${o.optInt("links")} 個 "
                        + "/ 店舗リンク ${shop.length()} 件")
                for (i in 0 until minOf(shop.length(), 10)) log("    ${shop.getString(i)}")
            } catch (e: Exception) {
                log("[$label] 読み取り失敗: $raw")
            }
        }
    }

    private fun finishReport() {
        log("")
        log("=== 結論の読み方 ===")
        log("1 が 0 件で 2 が取れている → 在庫情報が JavaScript 描画。取得方法を変える")
        log("1 も 2 も 0 件           → ページ構成が想定と違う。抽出条件を作り直す")
        log("1 が取れている           → 取得は正常。表示側の問題")
        log("")
        log("下の「結果を共有」でこの内容を送ってください。")
    }
}
