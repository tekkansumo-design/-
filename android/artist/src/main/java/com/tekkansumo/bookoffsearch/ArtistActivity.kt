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
    }

    override fun onDestroy() {
        ArtistSearch.renderer = null
        web.destroy()
        shadow.destroy()
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
