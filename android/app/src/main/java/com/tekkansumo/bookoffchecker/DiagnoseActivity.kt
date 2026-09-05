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
          var out={title:'',h1:'',len:0,links:[],kw:[],apis:[]};
          out.title=document.title||'';
          var h=document.querySelector('h1');
          out.h1=h?(h.innerText||h.textContent||'').replace(/\s+/g,' ').trim():'';
          out.len=document.documentElement.innerHTML.length;

          // ページ上の全リンク。どんな URL の形なのかを見る
          var as=document.querySelectorAll('a[href]');
          for(var i=0;i<as.length;i++){
            var t=(as[i].textContent||'').replace(/\s+/g,' ').trim().slice(0,22);
            out.links.push(t+' | '+(as[i].getAttribute('href')||'').slice(0,72));
          }

          // 「店舗」「在庫」を含む末端要素。在庫欄がどこにあるか探す
          var all=document.querySelectorAll('body *');
          for(var i=0;i<all.length&&out.kw.length<30;i++){
            var e=all[i];
            if(e.children&&e.children.length) continue;
            var t=(e.textContent||'').replace(/\s+/g,' ').trim();
            if(!t||t.length>60) continue;
            if(/店舗|在庫/.test(t)){
              var c=String(e.className||'').slice(0,26);
              out.kw.push(e.tagName.toLowerCase()+(c?'.'+c:'')+' : '+t);
            }
          }

          // ソース中の API っぽいパス。在庫が別取得ならここに出る
          var src=document.documentElement.innerHTML;
          // クエリ付き（?goods=... など）も拾えるように区切りは引用符だけにする
          var m=src.match(/["']([^"'\s]{0,160}(?:stock|shop|tenpo|api|inventory)[^"'\s]{0,160})["']/gi)||[];
          var seen={};
          for(var i=0;i<m.length&&out.apis.length<30;i++){
            var u=m[i].slice(1,-1);
            if(u.indexOf('/')<0) continue;      // 単語だけのものは除く
            if(seen[u]) continue; seen[u]=1; out.apis.push(u);
          }
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
        root.addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 700))
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

    private fun countOf(s: String, kw: String): Int {
        var n = 0
        var i = s.indexOf(kw)
        while (i >= 0) { n++; i = s.indexOf(kw, i + 1) }
        return n
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
                            log("  ※ 0 件。/shop/shopNNN 以外の形を探す:")
                            for (kw in listOf("店舗在庫", "店舗", "在庫", "stock", "shop")) {
                                log("    \"$kw\" の出現数: ${countOf(body, kw)}")
                            }
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
                log("[$label] title: ${o.optString("title").take(60)}")
                log("[$label] h1: ${o.optString("h1").take(60)}")
                log("[$label] HTML ${o.optInt("len")} 文字")

                val links = o.getJSONArray("links")
                log("[$label] ページ上のリンク ${links.length()} 件（全部出す）:")
                for (i in 0 until links.length()) log("    ${links.getString(i)}")

                val kw = o.getJSONArray("kw")
                log("[$label] 「店舗」「在庫」を含む要素 ${kw.length()} 件:")
                for (i in 0 until kw.length()) log("    ${kw.getString(i)}")

                val apis = o.getJSONArray("apis")
                log("[$label] stock/shop/api を含むパス ${apis.length()} 件:")
                for (i in 0 until apis.length()) log("    ${apis.getString(i)}")
            } catch (e: Exception) {
                log("[$label] 読み取り失敗: ${raw?.take(200)}")
            }
        }
    }

    private fun finishReport() {
        log("")
        log("=== ここまでで分かっていること ===")
        log("商品名は取れているので通信と解析は動いている。")
        log("在庫店舗が /shop/shopNNN のリンクとして置かれているという前提が誤り。")
        log("上のリンク一覧と「店舗」「在庫」要素から正しい形を割り出す。")
        log("")
        log("下の WebView は操作できます。ページを下までスクロールして、")
        log("店舗在庫の欄があるか、あるなら押すと何が起きるかも見てください。")
        log("")
        log("「結果を共有」でこの内容を送ってください。")
    }
}
