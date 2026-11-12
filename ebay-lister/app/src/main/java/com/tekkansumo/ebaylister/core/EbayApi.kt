package com.tekkansumo.ebaylister.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** eBay のアプリ設定。developer.ebay.com の「Application Keys」で発行した値。 */
data class EbayConfig(
    val clientId: String,
    val clientSecret: String,
    /** RuName（eBay Redirect URL name）。User Tokens の画面で作る */
    val ruName: String,
    val sandbox: Boolean,
    val marketplaceId: String = "EBAY_US",
) {
    val currency: String get() = MARKETPLACES[marketplaceId]?.currency ?: "USD"
    val contentLanguage: String get() = MARKETPLACES[marketplaceId]?.language ?: "en-US"

    companion object {
        data class Marketplace(val currency: String, val language: String, val label: String)

        val MARKETPLACES = linkedMapOf(
            "EBAY_US" to Marketplace("USD", "en-US", "アメリカ (ebay.com)"),
            "EBAY_GB" to Marketplace("GBP", "en-GB", "イギリス (ebay.co.uk)"),
            "EBAY_AU" to Marketplace("AUD", "en-AU", "オーストラリア (ebay.com.au)"),
            "EBAY_CA" to Marketplace("CAD", "en-CA", "カナダ (ebay.ca)"),
            "EBAY_DE" to Marketplace("EUR", "de-DE", "ドイツ (ebay.de)"),
        )
    }
}

/** ユーザートークンの保存先。Android では SharedPreferences で実装する。 */
interface TokenStore {
    var refreshToken: String?
    var accessToken: String?
    var accessTokenExpiresAt: Long
}

class EbayException(message: String, val status: Int = 0) : Exception(message)

/** Taxonomy API が返すアスペクト（Item Specifics の項目）1 つ分 */
data class Aspect(
    val name: String,
    val required: Boolean,
    /** FREE_TEXT か SELECTION_ONLY */
    val mode: String,
    /** SINGLE か MULTI */
    val cardinality: String,
    /** REQUIRED / RECOMMENDED / OPTIONAL のどれか（required のときは REQUIRED） */
    val usage: String,
    val values: List<String>,
) {
    fun toJson(maxValues: Int = Int.MAX_VALUE): JSONObject = JSONObject().apply {
        put("name", name)
        put("required", required)
        put("mode", mode)
        put("cardinality", cardinality)
        put("usage", usage)
        put("values", JSONArray(values.take(maxValues)))
        if (values.size > maxValues) put("more_values", values.size - maxValues)
    }
}

/** 出品に必要な値。UI の下書きから組み立てる。 */
data class ListingRequest(
    val sku: String,
    val title: String,
    val descriptionHtml: String,
    val categoryId: String,
    val condition: String,
    val conditionDescription: String,
    val aspects: Map<String, List<String>>,
    val brand: String,
    val mpn: String,
    val gtin: String,
    val imageUrls: List<String>,
    val price: String,
    val quantity: Int,
    val fulfillmentPolicyId: String,
    val paymentPolicyId: String,
    val returnPolicyId: String,
    val merchantLocationKey: String,
)

class EbayApi(private val config: EbayConfig, private val tokens: TokenStore) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val apiHost = if (config.sandbox) "https://api.sandbox.ebay.com" else "https://api.ebay.com"
    private val authHost = if (config.sandbox) "https://auth.sandbox.ebay.com" else "https://auth.ebay.com"
    private val mediaHost = if (config.sandbox) "https://apim.sandbox.ebay.com" else "https://apim.ebay.com"

    @Volatile private var appToken: String? = null
    @Volatile private var appTokenExpiresAt = 0L
    @Volatile private var treeId: String? = null
    private val aspectCache = ConcurrentHashMap<String, List<Aspect>>()

    // ---------------------------------------------------------------- OAuth

    /** ブラウザで開く同意画面の URL。承認後は RuName に設定した accept URL に code 付きで戻る。 */
    fun authorizeUrl(state: String): String =
        "$authHost/oauth2/authorize?client_id=${enc(config.clientId)}" +
            "&redirect_uri=${enc(config.ruName)}&response_type=code" +
            "&scope=${enc(USER_SCOPES.joinToString(" "))}&state=${enc(state)}"

    fun exchangeCode(code: String) {
        val json = tokenRequest(
            "grant_type=authorization_code&code=${enc(code)}&redirect_uri=${enc(config.ruName)}"
        )
        tokens.accessToken = json.getString("access_token")
        tokens.accessTokenExpiresAt = now() + json.optLong("expires_in", 7200) * 1000 - 60_000
        tokens.refreshToken = json.getString("refresh_token")
    }

    val isLoggedIn: Boolean get() = !tokens.refreshToken.isNullOrEmpty()

    fun logout() {
        tokens.refreshToken = null
        tokens.accessToken = null
        tokens.accessTokenExpiresAt = 0
    }

    private fun userToken(): String {
        val cur = tokens.accessToken
        if (cur != null && now() < tokens.accessTokenExpiresAt) return cur
        val refresh = tokens.refreshToken
            ?: throw EbayException("eBay にログインしていません。設定画面から「eBay にログイン」してください。")
        val json = tokenRequest(
            "grant_type=refresh_token&refresh_token=${enc(refresh)}" +
                "&scope=${enc(USER_SCOPES.joinToString(" "))}"
        )
        val t = json.getString("access_token")
        tokens.accessToken = t
        tokens.accessTokenExpiresAt = now() + json.optLong("expires_in", 7200) * 1000 - 60_000
        return t
    }

    /** Taxonomy / Browse 用のアプリケーショントークン（client credentials） */
    private fun appToken(): String {
        val cur = appToken
        if (cur != null && now() < appTokenExpiresAt) return cur
        val json = tokenRequest(
            "grant_type=client_credentials&scope=${enc("https://api.ebay.com/oauth/api_scope")}"
        )
        val t = json.getString("access_token")
        appToken = t
        appTokenExpiresAt = now() + json.optLong("expires_in", 7200) * 1000 - 60_000
        return t
    }

    private fun tokenRequest(form: String): JSONObject {
        if (config.clientId.isBlank() || config.clientSecret.isBlank()) {
            throw EbayException("eBay の Client ID / Client Secret が未設定です。")
        }
        val basic = Base64.getEncoder()
            .encodeToString("${config.clientId}:${config.clientSecret}".toByteArray())
        val req = Request.Builder()
            .url("$apiHost/identity/v1/oauth2/token")
            .header("Authorization", "Basic $basic")
            .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        return execute(req).let { JSONObject(it) }
    }

    // ------------------------------------------------------------- Taxonomy

    private fun categoryTreeId(): String {
        treeId?.let { return it }
        val json = getJson(
            "/commerce/taxonomy/v1/get_default_category_tree_id?marketplace_id=${config.marketplaceId}",
            appToken()
        )
        return json.getString("categoryTreeId").also { treeId = it }
    }

    /** キーワードからカテゴリ候補を返す。[{category_id, name, path}] */
    fun categorySuggestions(query: String): JSONArray {
        val json = getJson(
            "/commerce/taxonomy/v1/category_tree/${categoryTreeId()}/get_category_suggestions?q=${enc(query)}",
            appToken()
        )
        val out = JSONArray()
        val list = json.optJSONArray("categorySuggestions") ?: return out
        for (i in 0 until minOf(list.length(), 10)) {
            val s = list.getJSONObject(i)
            val cat = s.getJSONObject("category")
            val ancestors = s.optJSONArray("categoryTreeNodeAncestors") ?: JSONArray()
            val path = (ancestors.length() - 1 downTo 0)
                .map { ancestors.getJSONObject(it).optString("categoryName") } + cat.optString("categoryName")
            out.put(JSONObject().apply {
                put("category_id", cat.getString("categoryId"))
                put("name", cat.optString("categoryName"))
                put("path", path.joinToString(" > "))
            })
        }
        return out
    }

    fun itemAspects(categoryId: String): List<Aspect> {
        aspectCache[categoryId]?.let { return it }
        val json = getJson(
            "/commerce/taxonomy/v1/category_tree/${categoryTreeId()}/get_item_aspects_for_category?category_id=${enc(categoryId)}",
            appToken()
        )
        val list = json.optJSONArray("aspects") ?: JSONArray()
        val out = (0 until list.length()).map { i ->
            val a = list.getJSONObject(i)
            val c = a.optJSONObject("aspectConstraint") ?: JSONObject()
            val vals = a.optJSONArray("aspectValues") ?: JSONArray()
            val required = c.optBoolean("aspectRequired", false)
            Aspect(
                name = a.getString("localizedAspectName"),
                required = required,
                mode = c.optString("aspectMode", "FREE_TEXT"),
                cardinality = c.optString("itemToAspectCardinality", "SINGLE"),
                usage = if (required) "REQUIRED" else c.optString("aspectUsage", "OPTIONAL"),
                values = (0 until vals.length()).map { vals.getJSONObject(it).optString("localizedValue") },
            )
        }
        aspectCache[categoryId] = out
        return out
    }

    // --------------------------------------------------------------- Browse

    /** 相場の参考に、同じ GTIN かキーワードの出品中アイテムを返す。 */
    fun searchActiveListings(gtin: String?, query: String?): JSONArray {
        val q = buildString {
            append("/buy/browse/v1/item_summary/search?limit=15")
            if (!gtin.isNullOrBlank()) append("&gtin=").append(enc(gtin))
            if (!query.isNullOrBlank()) append("&q=").append(enc(query))
        }
        val req = Request.Builder()
            .url(apiHost + q)
            .header("Authorization", "Bearer ${appToken()}")
            .header("X-EBAY-C-MARKETPLACE-ID", config.marketplaceId)
            .get().build()
        val json = JSONObject(execute(req))
        val items = json.optJSONArray("itemSummaries") ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until items.length()) {
            val it = items.getJSONObject(i)
            val price = it.optJSONObject("price")
            val ship = it.optJSONArray("shippingOptions")?.optJSONObject(0)?.optJSONObject("shippingCost")
            out.put(JSONObject().apply {
                put("title", it.optString("title"))
                put("price", price?.optString("value") + " " + price?.optString("currency"))
                if (ship != null) put("shipping", ship.optString("value") + " " + ship.optString("currency"))
                put("condition", it.optString("condition"))
                put("seller_country", it.optJSONObject("itemLocation")?.optString("country") ?: "")
            })
        }
        return out
    }

    // -------------------------------------------------------------- Account

    /** 出品に使うビジネスポリシー一覧 {fulfillment:[{id,name}], payment:[...], return:[...]} */
    fun policies(): JSONObject {
        val token = userToken()
        val mp = config.marketplaceId
        fun list(path: String, key: String, idKey: String): JSONArray {
            val json = getJson("/sell/account/v1/$path?marketplace_id=$mp", token)
            val arr = json.optJSONArray(key) ?: JSONArray()
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                out.put(JSONObject().put("id", p.getString(idKey)).put("name", p.optString("name")))
            }
            return out
        }
        return JSONObject().apply {
            put("fulfillment", list("fulfillment_policy", "fulfillmentPolicies", "fulfillmentPolicyId"))
            put("payment", list("payment_policy", "paymentPolicies", "paymentPolicyId"))
            put("return", list("return_policy", "returnPolicies", "returnPolicyId"))
        }
    }

    /** 在庫ロケーション一覧 [{key, name}] */
    fun locations(): JSONArray {
        val json = getJson("/sell/inventory/v1/location?limit=100", userToken())
        val arr = json.optJSONArray("locations") ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val l = arr.getJSONObject(i)
            val addr = l.optJSONObject("location")?.optJSONObject("address")
            out.put(JSONObject().apply {
                put("key", l.getString("merchantLocationKey"))
                put("name", l.optString("name").ifBlank {
                    listOfNotNull(addr?.optString("city"), addr?.optString("postalCode"), addr?.optString("country"))
                        .filter { it.isNotBlank() }.joinToString(" ")
                })
            })
        }
        return out
    }

    fun createLocation(key: String, postalCode: String, city: String, state: String) {
        val address = JSONObject().put("postalCode", postalCode).put("country", "JP")
        if (city.isNotBlank()) address.put("city", city)
        if (state.isNotBlank()) address.put("stateOrProvince", state)
        val body = JSONObject().apply {
            put("location", JSONObject().put("address", address))
            put("locationTypes", JSONArray().put("WAREHOUSE"))
            put("name", "Japan warehouse")
            put("merchantLocationStatus", "ENABLED")
        }
        send("POST", "/sell/inventory/v1/location/${enc(key)}", body, userToken())
    }

    // ---------------------------------------------------------------- Media

    /** 写真を eBay の画像サーバーに上げて、出品に使える URL を返す。 */
    fun uploadImage(jpeg: ByteArray, fileName: String): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("image", fileName, jpeg.toRequestBody("image/jpeg".toMediaType()))
            .build()
        val req = Request.Builder()
            .url("$mediaHost/commerce/media/v1_beta/image/create_image_from_file")
            .header("Authorization", "Bearer ${userToken()}")
            .post(body).build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw EbayException("画像アップロード失敗: ${errorText(text)}", res.code)
            val direct = runCatching { JSONObject(text).optString("imageUrl") }.getOrNull()
            if (!direct.isNullOrBlank()) return direct
            // 本文に URL が無いときは Location ヘッダの画像リソースを取得する
            val loc = res.header("Location") ?: throw EbayException("画像 URL を取得できませんでした")
            val get = Request.Builder().url(loc).header("Authorization", "Bearer ${userToken()}").get().build()
            return JSONObject(execute(get)).getString("imageUrl")
        }
    }

    // ------------------------------------------------------------ Inventory

    /** 在庫アイテム → オファー → 公開 の順で出品し、listingId を返す。 */
    fun publish(r: ListingRequest): String {
        val token = userToken()

        val aspects = JSONObject()
        r.aspects.forEach { (k, v) -> if (v.isNotEmpty()) aspects.put(k, JSONArray(v)) }
        val product = JSONObject().apply {
            put("title", r.title)
            put("description", r.descriptionHtml)
            put("aspects", aspects)
            put("imageUrls", JSONArray(r.imageUrls))
            if (r.brand.isNotBlank()) put("brand", r.brand)
            if (r.mpn.isNotBlank()) put("mpn", r.mpn)
            val digits = r.gtin.filter { it.isDigit() }
            when (digits.length) {
                12 -> put("upc", JSONArray().put(digits))
                8, 13 -> put("ean", JSONArray().put(digits))
                14 -> put("isbn", JSONArray().put(digits))
            }
        }
        val item = JSONObject().apply {
            put("availability", JSONObject().put(
                "shipToLocationAvailability", JSONObject().put("quantity", r.quantity)))
            put("condition", r.condition)
            if (r.conditionDescription.isNotBlank() && !r.condition.startsWith("NEW")) {
                put("conditionDescription", r.conditionDescription.take(1000))
            }
            put("product", product)
        }
        send("PUT", "/sell/inventory/v1/inventory_item/${enc(r.sku)}", item, token)

        val offer = JSONObject().apply {
            put("sku", r.sku)
            put("marketplaceId", config.marketplaceId)
            put("format", "FIXED_PRICE")
            put("availableQuantity", r.quantity)
            put("categoryId", r.categoryId)
            put("listingDescription", r.descriptionHtml)
            put("merchantLocationKey", r.merchantLocationKey)
            put("listingPolicies", JSONObject().apply {
                put("fulfillmentPolicyId", r.fulfillmentPolicyId)
                put("paymentPolicyId", r.paymentPolicyId)
                put("returnPolicyId", r.returnPolicyId)
            })
            put("pricingSummary", JSONObject().put(
                "price", JSONObject().put("value", r.price).put("currency", config.currency)))
        }
        val offerId = JSONObject(send("POST", "/sell/inventory/v1/offer", offer, token))
            .getString("offerId")

        val pub = JSONObject(send("POST", "/sell/inventory/v1/offer/$offerId/publish", null, token))
        return pub.getString("listingId")
    }

    fun listingUrl(listingId: String): String {
        val host = when (config.marketplaceId) {
            "EBAY_GB" -> "www.ebay.co.uk"
            "EBAY_AU" -> "www.ebay.com.au"
            "EBAY_CA" -> "www.ebay.ca"
            "EBAY_DE" -> "www.ebay.de"
            else -> "www.ebay.com"
        }
        return if (config.sandbox) "https://sandbox.ebay.com/itm/$listingId" else "https://$host/itm/$listingId"
    }

    // ---------------------------------------------------------------- HTTP

    private fun getJson(path: String, token: String): JSONObject {
        val req = Request.Builder().url(apiHost + path)
            .header("Authorization", "Bearer $token")
            .header("Accept-Language", config.contentLanguage)
            .get().build()
        return JSONObject(execute(req))
    }

    private fun send(method: String, path: String, body: JSONObject?, token: String): String {
        val rb: RequestBody = (body?.toString() ?: "{}").toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(apiHost + path)
            .header("Authorization", "Bearer $token")
            .header("Content-Language", config.contentLanguage)
            .header("Accept-Language", config.contentLanguage)
            .method(method, rb).build()
        return execute(req).ifBlank { "{}" }
    }

    private fun execute(req: Request): String =
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                throw EbayException("eBay API エラー (${res.code}): ${errorText(text)}", res.code)
            }
            text
        }

    private fun errorText(body: String): String = runCatching {
        val j = JSONObject(body)
        j.optJSONArray("errors")?.let { errs ->
            (0 until errs.length()).joinToString(" / ") {
                val e = errs.getJSONObject(it)
                e.optString("longMessage").ifBlank { e.optString("message") }
            }
        } ?: j.optString("error_description").ifBlank { body.take(300) }
    }.getOrDefault(body.take(300))

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun now() = System.currentTimeMillis()

    companion object {
        val USER_SCOPES = listOf(
            "https://api.ebay.com/oauth/api_scope",
            "https://api.ebay.com/oauth/api_scope/sell.inventory",
            "https://api.ebay.com/oauth/api_scope/sell.account",
        )

        /** Inventory API の condition 値と日本語ラベル */
        val CONDITIONS = linkedMapOf(
            "NEW" to "新品",
            "NEW_OTHER" to "新品（開封済み・箱なし等）",
            "NEW_WITH_DEFECTS" to "新品（傷あり）",
            "LIKE_NEW" to "ほぼ新品",
            "USED_EXCELLENT" to "中古 - 非常に良い",
            "USED_VERY_GOOD" to "中古 - 良い",
            "USED_GOOD" to "中古 - 可",
            "USED_ACCEPTABLE" to "中古 - 難あり",
            "FOR_PARTS_OR_NOT_WORKING" to "ジャンク",
        )
    }
}
