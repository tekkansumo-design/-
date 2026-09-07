package com.tekkansumo.cdprice

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 画面は assets/index.html（WebView）で、通信と解析は Kotlin 側。
 * JS からは App.xxx（[Bridge]）を呼び、Kotlin からの通知は window.__native() に流す。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView

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
            loadsImagesAutomatically = true
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: android.webkit.WebResourceRequest?
            ): Boolean {
                val u = request?.url ?: return false
                if (u.scheme == "http" || u.scheme == "https") {
                    openExternal(u.toString())
                    return true
                }
                return false
            }
        }
        web.addJavascriptInterface(Bridge(), "App")
        web.loadUrl("file:///android_asset/index.html")

        Finder.addListener(listener)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })
    }

    override fun onDestroy() {
        Finder.removeListener(listener)
        web.destroy()
        super.onDestroy()
    }

    private fun openExternal(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            // 開けるアプリが無いときは何もしない
        }
    }

    private fun shareCsv(): Boolean {
        val hits = Finder.hits().sortedBy { it.price }
        if (hits.isEmpty()) return false
        val sb = StringBuilder("﻿")   // Excel 用 BOM
        sb.append(csvRow(listOf("サイト", "商品名", "価格", "中古", "URL")))
        for (h in hits) {
            sb.append(
                csvRow(
                    listOf(
                        Sites.byId(h.site)?.label ?: h.site,
                        h.title,
                        h.price.toString(),
                        if (h.used) "中古" else "",
                        h.url
                    )
                )
            )
        }
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, "cd-price.csv")
        f.writeText(sb.toString(), Charsets.UTF_8)

        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "CSV を送る"))
        return true
    }

    private fun csvRow(cols: List<String>): String =
        cols.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" } + "\r\n"

    inner class Bridge {

        @JavascriptInterface
        fun getSites(): String = JSONArray().apply {
            Sites.ALL.forEach {
                put(JSONObject().apply {
                    put("id", it.id)
                    put("label", it.label)
                    put("needsJs", it.needsJs)
                })
            }
        }.toString()

        @JavascriptInterface
        fun getState(): String = Finder.stateJson().toString()

        @JavascriptInterface
        fun search(word: String, sitesJson: String) {
            val ids = ArrayList<String>()
            val arr = JSONArray(sitesJson)
            for (i in 0 until arr.length()) ids.add(arr.getString(i))
            Finder.start(this@MainActivity, word, ids)
        }

        @JavascriptInterface
        fun searchAlbums(titlesJson: String, sitesJson: String) {
            val titles = ArrayList<String>()
            val t = JSONArray(titlesJson)
            for (i in 0 until t.length()) titles.add(t.getString(i))
            val ids = ArrayList<String>()
            val a = JSONArray(sitesJson)
            for (i in 0 until a.length()) ids.add(a.getString(i))
            Finder.searchAlbums(this@MainActivity, titles, ids)
        }

        @JavascriptInterface
        fun pickArtist(mbid: String) = Finder.pickArtist(mbid)

        @JavascriptInterface
        fun cancel() = Finder.cancel()

        @JavascriptInterface
        fun clear() = Finder.clear()

        @JavascriptInterface
        fun open(url: String) {
            runOnUiThread { openExternal(url) }
        }

        @JavascriptInterface
        fun openDiagnose() {
            runOnUiThread { startActivity(Intent(this@MainActivity, DiagnoseActivity::class.java)) }
        }

        @JavascriptInterface
        fun exportCsv(): Boolean {
            // 中身の有無だけ先に返し、共有シートは UI スレッドで開く
            if (Finder.hits().isEmpty()) return false
            runOnUiThread { shareCsv() }
            return true
        }
    }
}
