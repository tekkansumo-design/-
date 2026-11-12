package com.tekkansumo.ebaylister

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.tekkansumo.ebaylister.core.EbayApi
import com.tekkansumo.ebaylister.core.EbayConfig
import com.tekkansumo.ebaylister.core.ListingAgent
import com.tekkansumo.ebaylister.core.ListingRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var prefs: Prefs
    private lateinit var photos: Photos
    private val worker = Executors.newCachedThreadPool()

    /** 設定が変わったら作り直す */
    private var ebayCache: Pair<EbayConfig, EbayApi>? = null
    private fun ebay(): EbayApi {
        val cfg = prefs.ebayConfig()
        ebayCache?.let { (c, api) -> if (c == cfg) return api }
        return EbayApi(cfg, prefs.tokens).also { ebayCache = cfg to it }
    }

    private val captureFile by lazy { File(cacheDir, "capture/capture.jpg").apply { parentFile?.mkdirs() } }

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) background("photo") {
            photos.add(captureUri())
            sendPhotos()
        }
    }

    private val pickImages = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(Photos.MAX_PHOTOS)
    ) { uris ->
        if (uris.isNotEmpty()) background("photo") {
            uris.forEach { photos.add(it) }
            sendPhotos()
        }
    }

    private val authFlow = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val code = res.data?.getStringExtra(AuthActivity.EXTRA_CODE)
        if (res.resultCode != RESULT_OK || code == null) {
            emit("error", JSONObject().put("where", "login").put("message", "eBay へのログインが完了しませんでした。"))
            return@registerForActivityResult
        }
        background("login") {
            ebay().exchangeCode(code)
            emit("login", JSONObject().put("loggedIn", true))
            loadAccount()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        photos = Photos(this)

        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.textZoom = 100
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.scheme == "http" || u.scheme == "https") {
                    startActivity(Intent(Intent.ACTION_VIEW, u))
                    return true
                }
                return false
            }
        }
        web.addJavascriptInterface(Bridge(), "App")
        web.loadUrl("file:///android_asset/index.html")
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun captureUri(): Uri =
        FileProvider.getUriForFile(this, "$packageName.fileprovider", captureFile)

    // ------------------------------------------------------------ JS bridge

    inner class Bridge {
        @JavascriptInterface
        fun state(): String = JSONObject().apply {
            put("settings", prefs.toJson())
            put("loggedIn", ebay().isLoggedIn)
            put("conditions", JSONObject(EbayApi.CONDITIONS as Map<*, *>))
            put("marketplaces", JSONObject(EbayConfig.MARKETPLACES.mapValues { it.value.label } as Map<*, *>))
            put("currency", prefs.ebayConfig().currency)
        }.toString()

        @JavascriptInterface
        fun saveSettings(json: String) {
            prefs.save(JSONObject(json))
            emit("login", JSONObject().put("loggedIn", ebay().isLoggedIn))
        }

        @JavascriptInterface
        fun refreshPhotos() = background("photo") { sendPhotos() }

        @JavascriptInterface
        fun scan() = runOnUiThread {
            val opts = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
                )
                .enableAutoZoom()
                .build()
            GmsBarcodeScanning.getClient(this@MainActivity, opts).startScan()
                .addOnSuccessListener { b ->
                    emit("jan", JSONObject().put("code", b.rawValue.orEmpty()))
                }
                .addOnFailureListener { e ->
                    emit("error", JSONObject().put("where", "scan")
                        .put("message", "バーコードを読み取れませんでした: ${e.message}"))
                }
        }

        @JavascriptInterface
        fun takePhoto() = runOnUiThread { takePicture.launch(captureUri()) }

        @JavascriptInterface
        fun pickPhotos() = runOnUiThread {
            pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        @JavascriptInterface
        fun removePhoto(name: String) = background("photo") {
            photos.remove(name)
            sendPhotos()
        }

        @JavascriptInterface
        fun clearItem() = background("photo") {
            photos.clear()
            sendPhotos()
        }

        @JavascriptInterface
        fun analyze(json: String) = background("analyze") {
            val req = JSONObject(json)
            val key = prefs.get("claudeKey")
            if (key.isBlank()) error("設定画面で Claude API キーを入力してください。")
            val files = photos.list()
            if (req.optString("jan").isBlank() && files.isEmpty()) {
                error("JAN コードを入力するか、商品の写真を撮ってください。")
            }
            val cfg = prefs.ebayConfig()
            val agent = ListingAgent(key, ebay(), cfg.marketplaceId, cfg.currency) { msg ->
                emit("progress", JSONObject().put("text", msg))
            }
            val draft = agent.run(
                ListingAgent.Input(
                    jan = req.optString("jan"),
                    notes = req.optString("notes"),
                    condition = req.optString("condition"),
                    photos = files.map { it.readBytes() },
                )
            )
            emit("draft", draft)
        }

        @JavascriptInterface
        fun suggestCategories(query: String) = background("category") {
            emit("categories", JSONObject().put("list", ebay().categorySuggestions(query)))
        }

        @JavascriptInterface
        fun loadAspects(categoryId: String, categoryName: String) = background("category") {
            val defs = ebay().itemAspects(categoryId)
            emit("aspects", JSONObject().apply {
                put("category_id", categoryId)
                put("category_name", categoryName)
                put("defs", JSONArray(defs.map { it.toJson(300) }))
            })
        }

        @JavascriptInterface
        fun login() = runOnUiThread {
            val cfg = prefs.ebayConfig()
            if (cfg.clientId.isBlank() || cfg.clientSecret.isBlank() || cfg.ruName.isBlank()) {
                emit("error", JSONObject().put("where", "login")
                    .put("message", "Client ID・Client Secret・RuName を入力して保存してください。"))
                return@runOnUiThread
            }
            val url = ebay().authorizeUrl(UUID.randomUUID().toString())
            authFlow.launch(Intent(this@MainActivity, AuthActivity::class.java).putExtra(AuthActivity.EXTRA_URL, url))
        }

        @JavascriptInterface
        fun logout() {
            ebay().logout()
            emit("login", JSONObject().put("loggedIn", false))
        }

        @JavascriptInterface
        fun loadAccount() = background("account") { this@MainActivity.loadAccount() }

        @JavascriptInterface
        fun createLocation(json: String) = background("account") {
            val j = JSONObject(json)
            val key = "jp-" + j.optString("postalCode").filter { it.isLetterOrDigit() }.ifBlank { "home" }
            ebay().createLocation(key, j.optString("postalCode"), j.optString("city"), j.optString("state"))
            prefs.save(JSONObject().put("locationKey", key))
            this@MainActivity.loadAccount()
        }

        @JavascriptInterface
        fun publish(json: String) = background("publish") {
            val d = JSONObject(json)
            val missing = listOf("fulfillmentPolicyId", "paymentPolicyId", "returnPolicyId", "locationKey")
                .filter { prefs.get(it).isBlank() }
            if (missing.isNotEmpty()) {
                error("設定画面で配送・支払い・返品ポリシーと発送元ロケーションを選んでください。")
            }
            val files = photos.list()
            if (files.isEmpty()) error("写真が 1 枚以上必要です。")

            val api = ebay()
            val urls = files.mapIndexed { i, f ->
                emit("progress", JSONObject().put("text", "写真をアップロード中 (${i + 1}/${files.size})"))
                api.uploadImage(f.readBytes(), f.name)
            }

            val aspects = mutableMapOf<String, List<String>>()
            d.optJSONObject("aspects")?.let { a ->
                a.keys().forEach { k ->
                    val arr = a.getJSONArray(k)
                    aspects[k] = (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }
                }
            }
            emit("progress", JSONObject().put("text", "eBay に出品しています…"))
            val sku = "JP" + SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
            val listingId = api.publish(
                ListingRequest(
                    sku = sku,
                    title = d.getString("title"),
                    descriptionHtml = d.getString("description_html"),
                    categoryId = d.getString("category_id"),
                    condition = d.getString("condition"),
                    conditionDescription = d.optString("condition_description"),
                    aspects = aspects,
                    brand = d.optString("brand"),
                    mpn = d.optString("mpn"),
                    gtin = d.optString("gtin"),
                    imageUrls = urls,
                    price = d.getString("price"),
                    quantity = d.optInt("quantity", 1),
                    fulfillmentPolicyId = prefs.get("fulfillmentPolicyId"),
                    paymentPolicyId = prefs.get("paymentPolicyId"),
                    returnPolicyId = prefs.get("returnPolicyId"),
                    merchantLocationKey = prefs.get("locationKey"),
                )
            )
            emit("published", JSONObject().put("listingId", listingId).put("url", api.listingUrl(listingId)).put("sku", sku))
        }

        @JavascriptInterface
        fun openUrl(url: String) = runOnUiThread {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }

        @JavascriptInterface
        fun toast(msg: String) = runOnUiThread {
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // --------------------------------------------------------------- helpers

    private fun loadAccount() {
        val api = ebay()
        emit("account", JSONObject().apply {
            put("policies", api.policies())
            put("locations", api.locations())
            put("settings", prefs.toJson())
        })
    }

    private fun sendPhotos() {
        val arr = JSONArray()
        photos.list().forEach { f -> arr.put(JSONObject().put("id", f.name).put("thumb", photos.thumbnail(f))) }
        emit("photos", JSONObject().put("photos", arr))
    }

    /** 重い処理はワーカースレッドで。例外は UI にエラーとして返す。 */
    private fun background(where: String, block: () -> Unit) {
        worker.execute {
            try {
                block()
            } catch (e: Throwable) {
                emit("error", JSONObject().put("where", where).put("message", e.message ?: e.toString()))
            }
        }
    }

    private fun emit(kind: String, data: JSONObject) {
        val k = JSONObject.quote(kind)
        val payload = JSONObject.quote(data.toString())
        web.post {
            web.evaluateJavascript("window.__native && window.__native($k, JSON.parse($payload))", null)
        }
    }
}
