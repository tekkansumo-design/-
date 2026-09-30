package com.tekkansumo.ebaylister

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.tekkansumo.ebaylister.core.Api
import com.tekkansumo.ebaylister.core.ConfStore
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var photoFile: File? = null
    private var photoUri: Uri? = null

    /** <input type=file> の結果。ギャラリーの複数選択とカメラ撮影の両方を受ける。 */
    private val pick = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val cb = fileCallback ?: return@registerForActivityResult
        fileCallback = null
        if (res.resultCode != RESULT_OK) {
            cb.onReceiveValue(null)
            return@registerForActivityResult
        }
        val uris = ArrayList<Uri>()
        res.data?.clipData?.let { c -> for (i in 0 until c.itemCount) uris.add(c.getItemAt(i).uri) }
        if (uris.isEmpty()) res.data?.data?.let { uris.add(it) }
        if (uris.isEmpty() && (photoFile?.length() ?: 0L) > 0L) photoUri?.let { uris.add(it) }
        cb.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api(this)

        web = WebView(this)
        setContentView(web)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            textZoom = 100
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                // 出品ページや情報源のリンクはブラウザで開く
                val u = request?.url ?: return false
                if (u.scheme == "http" || u.scheme == "https") {
                    openExternal(u)
                    return true
                }
                return false
            }
        }
        // WebChromeClient を付けないと alert/confirm/prompt とファイル選択が動かない
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?, callback: ValueCallback<Array<Uri>>, params: FileChooserParams,
            ): Boolean = showChooser(callback, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
        }
        web.addJavascriptInterface(Bridge(), "App")
        web.loadUrl("file:///android_asset/index.html")

        // 戻るキーではアプリを終了せず裏に回す（調査中の結果を失わないように）
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        web.destroy()
        super.onDestroy()
    }

    private fun openExternal(u: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, u))
        } catch (e: ActivityNotFoundException) { /* ブラウザが無い */ }
    }

    private fun showChooser(callback: ValueCallback<Array<Uri>>, multiple: Boolean): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = callback

        val get = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        }
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }      // 前回の撮影分は送信済みなので消す
        val f = File(dir, "shot_${System.currentTimeMillis()}.jpg")
        photoFile = f
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        photoUri = uri
        val cam = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            // chooser 経由だと EXTRA_OUTPUT の権限が伝わらないので ClipData でも渡す
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(get, "写真を選ぶ / 撮る")
            .putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cam))
        return try {
            pick.launch(chooser)
            true
        } catch (e: ActivityNotFoundException) {
            fileCallback = null
            callback.onReceiveValue(null)
            false
        }
    }

    /** JS から呼ばれる窓口。重い処理は別スレッドで走らせ、結果を window.__resolve に返す。 */
    inner class Bridge {

        @JavascriptInterface
        fun call(id: Int, method: String, path: String, body: String) {
            WORKERS.execute {
                val json = try { JSONObject(body.ifEmpty { "{}" }) } catch (e: Exception) { JSONObject() }
                val (ok, txt) = api(this@MainActivity).handle(method, path, json)
                web.post {
                    web.evaluateJavascript("window.__resolve($id,$ok,${JSONObject.quote(txt)})", null)
                }
            }
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            runOnUiThread { openExternal(Uri.parse(url)) }
        }
    }

    companion object {
        private val WORKERS = Executors.newCachedThreadPool()
        private var API: Api? = null

        /** 下書き（撮影画像と調査結果）を画面の作り直しで失わないよう、プロセスで 1 つだけ持つ。 */
        @Synchronized
        fun api(ctx: Context): Api = API ?: run {
            val prefs = ctx.applicationContext.getSharedPreferences("ebay_lister", Context.MODE_PRIVATE)
            Api(object : ConfStore {
                override fun load(): String? = prefs.getString("conf", null)
                override fun save(json: String) {
                    prefs.edit().putString("conf", json).apply()
                }
            }).also { API = it }
        }
    }
}
