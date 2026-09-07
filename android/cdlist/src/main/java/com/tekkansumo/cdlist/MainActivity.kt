package com.tekkansumo.cdlist

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 画面は assets/index.html（artist_cd_web.py の UI_HTML / UI_JS から生成）。
 * 通信は Flask ではなくここの Bridge が受ける。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView

    private val ownedFile: File get() = File(filesDir, "artist_cd_owned.json")

    /** Checker のイベントを WebView の window.__native() に流す。 */
    private val listener: (String, JSONObject) -> Unit = { kind, data ->
        val payload = JSONObject.quote(data.toString())
        val k = JSONObject.quote(kind)
        web.post {
            web.evaluateJavascript(
                "window.__native && window.__native(JSON.parse($k), JSON.parse($payload))", null
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this)
        setContentView(web)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            textZoom = 100
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean {
                // 商品ページなどはブラウザで開く
                val u = request?.url ?: return false
                if (u.scheme == "http" || u.scheme == "https") {
                    startActivity(Intent(Intent.ACTION_VIEW, u))
                    return true
                }
                return false
            }
        }
        web.addJavascriptInterface(Bridge(), "App")
        web.loadUrl("file:///android_asset/index.html")

        Checker.addListener(listener)
    }

    override fun onDestroy() {
        Checker.removeListener(listener)
        web.destroy()
        super.onDestroy()
    }

    /** 時間のかかる問い合わせの返事。JS 側の Promise を解く。 */
    private fun reply(rid: Int, body: JSONObject) {
        listener("reply", JSONObject().put("rid", rid).put("body", body))
    }

    private fun bg(work: () -> Unit) {
        Thread(work).apply { isDaemon = true }.start()
    }

    private fun shareCsv(text: String, name: String) {
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, if (name.endsWith(".csv")) name else "$name.csv")
        f.writeText("﻿$text", Charsets.UTF_8)      // Excel 用 BOM
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "CSV を送る"))
    }

    /**
     * JS から呼ばれる窓口。WebView の JavaBridge スレッドで走る。
     * 通信を伴うものは待たせると画面が固まるので、別スレッドにして
     * 返事を __native('reply') で返す。
     */
    inner class Bridge {

        @JavascriptInterface
        fun artists(rid: Int, word: String) {
            bg { reply(rid, MusicBrainz.artists(word)) }
        }

        @JavascriptInterface
        fun disco(rid: Int, mbid: String) {
            bg { reply(rid, MusicBrainz.discography(mbid, Store.load(ownedFile))) }
        }

        @JavascriptInterface
        fun setOwned(id: String, owned: Boolean, metaJson: String): String {
            val meta = try {
                JSONObject(metaJson)
            } catch (e: Exception) {
                JSONObject()
            }
            val n = Store.setOwned(ownedFile, id, owned, meta)
            return JSONObject().put("ok", true).put("count", n).toString()
        }

        @JavascriptInterface
        fun plan(artist: String, itemsJson: String): String {
            if (Checker.running) {
                return JSONObject().put("ok", false).put("msg", "実行中です").toString()
            }
            val arr = try {
                JSONArray(itemsJson)
            } catch (e: Exception) {
                JSONArray()
            }
            val items = ArrayList<Target>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                if (id.isEmpty()) continue
                items.add(Target(id, o.optString("title", "")))
            }
            if (items.isEmpty()) {
                return JSONObject().put("ok", false).put("msg", "対象がありません").toString()
            }
            val n = Checker.setPlan(artist, items)
            return JSONObject().put("ok", true).put("count", n).toString()
        }

        @JavascriptInterface
        fun start() {
            Checker.start()
        }

        @JavascriptInterface
        fun cancel() {
            Checker.cancel()
        }

        @JavascriptInterface
        fun diag(keyword: String) {
            runOnUiThread {
                startActivity(
                    Intent(this@MainActivity, DiagnoseActivity::class.java)
                        .putExtra(DiagnoseActivity.EXTRA_KEYWORD, keyword)
                )
            }
        }

        @JavascriptInterface
        fun csv(text: String, name: String) {
            runOnUiThread { shareCsv(text, name) }
        }
    }
}
