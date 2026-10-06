package com.tekkansumo.bookoffsearch

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * アーティスト名で CD / DVD を検索し、定価との差が大きい商品の在庫店舗を
 * 在庫商品数の多い順に表示する画面。処理本体は ArtistSearch。
 */
class ArtistActivity : AppCompatActivity() {

    companion object {
        private const val PREF = "artist"

        /** 入荷店舗モーダルを開いてから描画後の HTML を返す。 */
        private const val JS_OPEN_MODAL = """
        (function(){
          var t=document.querySelector('[data-modal="modalStoreInformation"]');
          if(t){ try{ t.click(); }catch(e){} }
          return 1;
        })()
        """
    }

    private lateinit var web: WebView
    private lateinit var shadow: WebView
    private lateinit var amz: WebView
    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        // 店舗一覧が JS で描画される場合の取得用。画面には出さない
        shadow = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.blockNetworkImage = true
            visibility = View.INVISIBLE
        }
        root.addView(shadow, FrameLayout.LayoutParams(400, 800))

        // Amazon 取得用。amazon.co.jp のページを開いておき、その中から fetch() する
        // （同一オリジンなので Cookie も効き、TLS も本物の Chrome になる）
        amz = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.blockNetworkImage = true
            visibility = View.INVISIBLE
            addJavascriptInterface(AmzBridge(), "AmzBridge")
        }
        root.addView(amz, FrameLayout.LayoutParams(400, 800))

        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            textZoom = 100
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: android.webkit.WebResourceRequest?
            ): Boolean {
                val u = request?.url ?: return false
                if (u.scheme == "http" || u.scheme == "https") {
                    startActivity(Intent(Intent.ACTION_VIEW, u))
                    return true
                }
                return false
            }
        }
        web.addJavascriptInterface(Bridge(), "App")
        root.addView(web, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        web.loadUrl("file:///android_asset/artist.html")

        ArtistSearch.renderer = { url -> render(url) }
        ArtistSearch.amazonFetch = { url -> amazonFetch(url) }
    }

    override fun onDestroy() {
        ArtistSearch.renderer = null
        ArtistSearch.amazonFetch = null
        web.destroy()
        shadow.destroy()
        amz.destroy()
        super.onDestroy()
    }

    private fun prefs() = getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /**
     * 作業スレッドから呼ばれる。WebView は UI スレッドでしか触れないので
     * 読み込み → モーダルを開く → 少し待って HTML を回収、を UI スレッドに載せて待つ。
     */
    private val renderLock = Object()

    private fun render(url: String): String? = synchronized(renderLock) {
        if (isFinishing || isDestroyed) return null
        val latch = CountDownLatch(1)
        var out: String? = null
        main.post {
            var fired = false
            shadow.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, u: String?) {
                    if (fired) return
                    fired = true
                    main.postDelayed({
                        shadow.evaluateJavascript(JS_OPEN_MODAL) {
                            main.postDelayed({
                                shadow.evaluateJavascript(
                                    "document.documentElement.outerHTML"
                                ) { raw ->
                                    out = try {
                                        JSONTokener(raw).nextValue() as? String
                                    } catch (e: Exception) {
                                        null
                                    }
                                    latch.countDown()
                                }
                            }, 2500)
                        }
                    }, 1500)
                }
            }
            shadow.loadUrl(url)
        }
        if (!latch.await(40, TimeUnit.SECONDS)) {
            main.post { shadow.stopLoading() }
            return null
        }
        out
    }

    // ───────────────────────── Amazon ─────────────────────────

    private class Pending {
        val latch = CountDownLatch(1)
        var status = -1
        var body = ""
    }

    private val pending = HashMap<Int, Pending>()
    private var nextId = 1
    private val amzLock = Object()

    /** fetch() の結果を JS から受け取る。 */
    inner class AmzBridge {
        @JavascriptInterface
        fun done(id: Int, status: Int, body: String) {
            val p = synchronized(pending) { pending.remove(id) } ?: return
            p.status = status
            p.body = body
            p.latch.countDown()
        }
    }

    /** amz が amazon.co.jp のページを開いている状態にする。 */
    private fun ensureAmazonOrigin(): Boolean {
        val ok = CountDownLatch(1)
        var already = false
        main.post {
            val cur = amz.url ?: ""
            if (cur.startsWith(AmazonParser.BASE)) {
                already = true
                ok.countDown()
            } else {
                var fired = false
                amz.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, u: String?) {
                        if (fired || u == null || !u.startsWith(AmazonParser.BASE)) return
                        fired = true
                        ok.countDown()
                    }
                }
                // 軽いページで足りる。オリジンと Cookie が欲しいだけ
                amz.loadUrl(AmazonParser.BASE + "/gp/help/customer/display.html")
            }
        }
        return ok.await(40, TimeUnit.SECONDS) || already
    }

    /** 作業スレッドから呼ばれる。(HTTP ステータス, 本文) を返す。失敗は null。 */
    private fun amazonFetch(url: String): Pair<Int, String>? = synchronized(amzLock) {
        if (isFinishing || isDestroyed) return null
        if (!ensureAmazonOrigin()) return null
        val p = Pending()
        val id = synchronized(pending) { val i = nextId++; pending[i] = p; i }
        val js = "(function(){fetch(" + JSONObject.quote(url) + ",{credentials:'include'," +
                "headers:{'Accept':'text/html,*/*'}})" +
                ".then(function(r){return r.text().then(function(t){AmzBridge.done($id,r.status,t);});})" +
                ".catch(function(e){AmzBridge.done($id,-1,String(e));});})()"
        main.post { amz.evaluateJavascript(js, null) }
        if (!p.latch.await(40, TimeUnit.SECONDS)) {
            synchronized(pending) { pending.remove(id) }
            return null
        }
        if (p.status < 0) null else Pair(p.status, p.body)
    }

    private fun keepScreenOn(on: Boolean) {
        runOnUiThread {
            if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    inner class Bridge {

        /** 前回の入力値。 */
        @JavascriptInterface
        fun getInputs(): String = prefs().getString("inputs", null) ?: "{}"

        @JavascriptInterface
        fun start(json: String): Boolean {
            val o = JSONObject(json)
            prefs().edit().putString("inputs", json).apply()
            val genres = ArrayList<String>()
            val ga = o.optJSONArray("genres")
            if (ga != null) for (i in 0 until ga.length()) genres.add(ga.getString(i))
            val p = ArtistSearch.Params(
                artist = o.optString("artist").trim(),
                genres = genres,
                minDiff = o.optInt("minDiff", 500),
                byAuthor = o.optBoolean("byAuthor", true),
                strict = o.optBoolean("strict", false)
            )
            if (p.artist.isEmpty() || p.genres.isEmpty()) return false
            val ok = ArtistSearch.start(p)
            if (ok) keepScreenOn(true)
            return ok
        }

        @JavascriptInterface
        fun cancel() = ArtistSearch.cancel()

        /** version が変わっていなければ空文字（毎回大きな JSON を作らない）。 */
        @JavascriptInterface
        fun getState(since: Int): String {
            if (since == ArtistSearch.version()) return ""
            val st = ArtistSearch.stateJson()
            if (!st.optBoolean("running")) keepScreenOn(false)
            return st.toString()
        }

        @JavascriptInterface
        fun share(pref: String) {
            val text = ArtistSearch.shareText(pref.ifEmpty { null })
            runOnUiThread {
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }, "結果を共有"))
            }
        }
    }
}
