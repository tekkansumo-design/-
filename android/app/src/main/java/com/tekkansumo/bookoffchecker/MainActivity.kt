package com.tekkansumo.bookoffchecker

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView

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

    private val askNoti = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 拒否されてもチェック自体は動く。通知が出ないだけ */ }

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
                view: WebView?, request: android.webkit.WebResourceRequest?
            ): Boolean {
                // 店舗リンクなどはブラウザで開く
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

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            askNoti.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // 戻るキーではアプリを終了せず裏に回す（チェックを続行させる）
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })
    }

    override fun onDestroy() {
        Checker.removeListener(listener)
        web.destroy()
        super.onDestroy()
    }

    private fun startCheck() {
        val i = Intent(this, CheckService::class.java)
        ContextCompat.startForegroundService(this, i)
    }

    private fun shareCsv() {
        val sb = StringBuilder("﻿")   // Excel 用 BOM
        sb.append(csvRow(listOf("商品ID", "商品名", "在庫店舗数", "店舗")))
        for (r in Checker.results()) {
            sb.append(
                csvRow(
                    listOf(
                        r.pid, r.name, r.shops.size.toString(),
                        r.shops.joinToString(" / ") { it.name })
                )
            )
        }
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, "bookoff.csv")
        f.writeText(sb.toString(), Charsets.UTF_8)

        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "CSV を送る"))
    }

    private fun csvRow(cols: List<String>): String =
        cols.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" } + "\r\n"

    private fun toList(json: String): List<String> {
        val a = JSONArray(json)
        return (0 until a.length()).map { a.getString(it) }
    }

    /**
     * JS から呼ばれる窓口。メソッドは WebView の JavaBridge スレッドで走るので、
     * 画面や Intent に触るものは runOnUiThread に載せる。
     */
    inner class Bridge {

        @JavascriptInterface
        fun getState(): String = Checker.stateJson().toString()

        @JavascriptInterface
        fun getIds(): String = JSONArray(Store.ids(this@MainActivity)).toString()

        @JavascriptInterface
        fun saveIds(json: String): String =
            JSONArray(Store.saveIds(this@MainActivity, toList(json))).toString()

        @JavascriptInterface
        fun start() {
            runOnUiThread { startCheck() }
        }

        @JavascriptInterface
        fun cancel() {
            Checker.cancel()
        }

        @JavascriptInterface
        fun reset(): Boolean = Checker.reset()

        @JavascriptInterface
        fun getPrefectures(): String {
            val found = Prefectures.found(Checker.results())
            return JSONObject().apply {
                put("all", JSONArray(Prefectures.ALL))
                put("found", JSONArray(found))
            }.toString()
        }

        @JavascriptInterface
        fun getMailConfig(): String {
            val c = Store.mailConfig(this@MainActivity)
            c.put("password", if (c.optString("password").isEmpty()) "" else "****")
            return c.toString()
        }

        @JavascriptInterface
        fun saveMailConfig(json: String) {
            Store.saveMailConfig(this@MainActivity, JSONObject(json))
        }

        @JavascriptInterface
        fun mailPreview(prefsJson: String): String =
            Mailer.buildHtml(Checker.results(), toList(prefsJson)).first

        /** 送信はネットワークを使うので非同期。結果は __native('mail', ...) で返す。 */
        @JavascriptInterface
        fun sendMail(prefsJson: String) {
            val prefs = toList(prefsJson)
            Thread {
                val (html, count) = Mailer.buildHtml(Checker.results(), prefs)
                val err = Mailer.send(Store.mailConfig(this@MainActivity), html, count)
                listener(
                    "mail",
                    JSONObject().apply {
                        put("ok", err == null)
                        put("msg", err ?: "${count}件を送信しました")
                    }
                )
            }.apply { isDaemon = true }.start()
        }

        @JavascriptInterface
        fun openImport() {
            runOnUiThread {
                startActivity(Intent(this@MainActivity, ImportActivity::class.java))
            }
        }

        @JavascriptInterface
        fun openDiagnose() {
            runOnUiThread {
                startActivity(Intent(this@MainActivity, DiagnoseActivity::class.java))
            }
        }

        @JavascriptInterface
        fun exportCsv() {
            runOnUiThread { shareCsv() }
        }
    }
}
