package com.tekkansumo.ebaylister.core

import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
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

class EbayError(val status: Int, val errors: JSONArray, text: String) : AppError(
    "eBay API $status: " + (0 until errors.length()).joinToString(" / ") {
        val e = errors.optJSONObject(it) ?: JSONObject()
        val m = e.optString("longMessage").ifEmpty { e.optString("message") }
        if (e.has("errorId")) "[${e.opt("errorId")}] $m" else m
    }.ifEmpty { text.take(300) }
) {
    fun hasId(id: Int) = (0 until errors.length()).any { errors.optJSONObject(it)?.optInt("errorId") == id }

    fun param(name: String): String? {
        for (i in 0 until errors.length()) {
            val ps = errors.optJSONObject(i)?.optJSONArray("parameters") ?: continue
            for (j in 0 until ps.length()) {
                val p = ps.optJSONObject(j) ?: continue
                if (p.optString("name") == name) return p.optString("value")
            }
        }
        return null
    }
}

class EbayResp(val json: JSONObject, val headers: Headers)

/** eBay REST API。ebay_lister.py の Ebay クラスの移植。 */
class Ebay(private val http: OkHttpClient) {

    /** テスト用。設定すると全ホストをここに向ける。 */
    var hostOverride: String? = null

    private val tokens = HashMap<String, Triple<String, Long, String>>()
    private val trees = HashMap<String, String>()

    private fun sandbox(c: JSONObject) = c.optString("ebay_env") == "sandbox"
    fun apiHost(c: JSONObject) = hostOverride ?: if (sandbox(c)) "https://api.sandbox.ebay.com" else "https://api.ebay.com"
    fun authHost(c: JSONObject) = if (sandbox(c)) "https://auth.sandbox.ebay.com" else "https://auth.ebay.com"
    fun apimHost(c: JSONObject) = hostOverride ?: if (sandbox(c)) "https://apim.sandbox.ebay.com" else "https://apim.ebay.com"
    fun webHost(c: JSONObject) = if (sandbox(c)) "https://sandbox.ebay.com" else "https://" + Conf.market(c).host

    // ── OAuth ──
    private fun basic(c: JSONObject): String {
        val id = c.optString("client_id")
        val secret = c.optString("client_secret")
        if (id.isEmpty() || secret.isEmpty()) throw AppError("eBay の Client ID / Client Secret が未設定です（設定タブ）")
        return "Basic " + Base64.getEncoder().encodeToString("$id:$secret".toByteArray())
    }

    private fun tokenRequest(c: JSONObject, form: Map<String, String>): JSONObject {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val req = Request.Builder().url(apiHost(c) + "/identity/v1/oauth2/token")
            .header("Authorization", basic(c)).post(body).build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val j = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
            if (r.code != 200) {
                val desc = j.optString("error_description").ifEmpty { j.optString("error") }.ifEmpty { text.take(200) }
                throw AppError("eBay トークン取得に失敗しました (${r.code}): $desc")
            }
            return j
        }
    }

    @Synchronized
    fun token(c: JSONObject, kind: String): String {
        val sig = listOf(c.optString("ebay_env"), c.optString("client_id"), c.optString("refresh_token")).joinToString("|")
        tokens[kind]?.let { (t, exp, s) -> if (s == sig && exp > System.currentTimeMillis() + 60_000) return t }
        val j = if (kind == "user") {
            if (c.optString("refresh_token").isEmpty()) throw AppError("eBay アカウントと未連携です（設定タブの「eBay と連携」）")
            tokenRequest(c, mapOf("grant_type" to "refresh_token",
                "refresh_token" to c.optString("refresh_token"), "scope" to USER_SCOPES.joinToString(" ")))
        } else {
            tokenRequest(c, mapOf("grant_type" to "client_credentials",
                "scope" to if (kind == "insights") INSIGHTS_SCOPE else API_SCOPE))
        }
        val t = j.getString("access_token")
        tokens[kind] = Triple(t, System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000, sig)
        return t
    }

    fun authUrl(c: JSONObject, state: String): String {
        if (c.optString("client_id").isEmpty() || c.optString("ru_name").isEmpty())
            throw AppError("Client ID と RuName を先に保存してください")
        return authHost(c) + "/oauth2/authorize?" + query(mapOf(
            "client_id" to c.optString("client_id"), "redirect_uri" to c.optString("ru_name"),
            "response_type" to "code", "scope" to USER_SCOPES.joinToString(" "), "state" to state))
    }

    @Synchronized
    fun exchangeCode(c: JSONObject, code: String): JSONObject {
        val j = tokenRequest(c, mapOf("grant_type" to "authorization_code", "code" to code,
            "redirect_uri" to c.optString("ru_name")))
        tokens.remove("user")
        return j
    }

    // ── 汎用 ──
    fun call(
        c: JSONObject, method: String, path: String, kind: String = "app", host: String? = null,
        params: Map<String, Any> = emptyMap(), json: Any? = null, body: RequestBody? = null,
    ): EbayResp {
        val url = (host ?: apiHost(c)) + path
        val b = url.toHttpUrl().newBuilder()
        params.forEach { (k, v) -> b.addQueryParameter(k, v.toString()) }
        val rb = Request.Builder().url(b.build())
            .header("Authorization", "Bearer " + token(c, kind))
            .header("Accept", "application/json")
            .header("X-EBAY-C-MARKETPLACE-ID", c.optString("marketplace_id"))
        val payload = when {
            json != null -> {
                rb.header("Content-Language", Conf.market(c).language)
                json.toString().toRequestBody("application/json".toMediaType())
            }
            body != null -> body
            method == "POST" || method == "PUT" -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        rb.method(method, payload)
        http.newCall(rb.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code !in listOf(200, 201, 204)) {
                val errs = try { JSONObject(text).optJSONArray("errors") } catch (e: Exception) { null }
                throw EbayError(r.code, errs ?: JSONArray(), text)
            }
            val j = if (text.isBlank()) JSONObject() else try { JSONObject(text) } catch (e: Exception) { JSONObject() }
            return EbayResp(j, r.headers)
        }
    }

    // ── Taxonomy ──
    @Synchronized
    private fun treeId(c: JSONObject): String {
        val mp = c.optString("marketplace_id")
        return trees.getOrPut(mp) {
            call(c, "GET", "/commerce/taxonomy/v1/get_default_category_tree_id",
                params = mapOf("marketplace_id" to mp)).json.getString("categoryTreeId")
        }
    }

    fun categorySuggestions(c: JSONObject, q: String): JSONArray {
        val tid = treeId(c)
        val j = call(c, "GET", "/commerce/taxonomy/v1/category_tree/$tid/get_category_suggestions",
            params = mapOf("q" to q.take(350))).json
        val out = JSONArray()
        val list = j.optJSONArray("categorySuggestions") ?: JSONArray()
        for (i in 0 until list.length()) {
            val s = list.getJSONObject(i)
            val cat = s.optJSONObject("category") ?: JSONObject()
            val anc = s.optJSONArray("categoryTreeNodeAncestors") ?: JSONArray()
            val path = (anc.length() - 1 downTo 0).map { anc.getJSONObject(it).optString("categoryName") } +
                cat.optString("categoryName")
            out.put(JSONObject().put("id", cat.optString("categoryId"))
                .put("name", cat.optString("categoryName")).put("path", path.joinToString(" > ")))
        }
        return out
    }

    fun itemAspects(c: JSONObject, categoryId: String): List<Aspect> {
        val tid = treeId(c)
        val j = call(c, "GET", "/commerce/taxonomy/v1/category_tree/$tid/get_item_aspects_for_category",
            params = mapOf("category_id" to categoryId)).json
        val list = j.optJSONArray("aspects") ?: JSONArray()
        return (0 until list.length()).map { i ->
            val a = list.getJSONObject(i)
            val con = a.optJSONObject("aspectConstraint") ?: JSONObject()
            val vals = a.optJSONArray("aspectValues") ?: JSONArray()
            Aspect(
                name = a.optString("localizedAspectName"),
                required = con.optBoolean("aspectRequired"),
                usage = con.optString("aspectUsage"),
                mode = con.optString("aspectMode"),
                multi = con.optString("itemToAspectCardinality") == "MULTI",
                values = (0 until vals.length()).map { vals.getJSONObject(it).optString("localizedValue") },
            )
        }
    }

    // ── Browse ──
    fun searchActive(c: JSONObject, q: String, limit: Int = 50): List<PriceItem> {
        val j = call(c, "GET", "/buy/browse/v1/item_summary/search", params = mapOf("q" to q, "limit" to limit)).json
        val list = j.optJSONArray("itemSummaries") ?: JSONArray()
        return (0 until list.length()).mapNotNull { i ->
            val it = list.getJSONObject(i)
            val p = it.optJSONObject("price") ?: return@mapNotNull null
            val price = p.optString("value").toDoubleOrNull() ?: return@mapNotNull null
            PriceItem(it.optString("title"), price, p.optString("currency"), "",
                it.optString("condition"), it.optString("itemWebUrl"))
        }
    }

    fun searchByImage(c: JSONObject, jpeg: ByteArray, limit: Int = 8): List<String> {
        val j = call(c, "POST", "/buy/browse/v1/item_summary/search_by_image", params = mapOf("limit" to limit),
            json = JSONObject().put("image", Base64.getEncoder().encodeToString(jpeg))).json
        val list = j.optJSONArray("itemSummaries") ?: JSONArray()
        return (0 until list.length()).map { list.getJSONObject(it).optString("title") }.filter { it.isNotEmpty() }
    }

    // ── Marketplace Insights ──
    fun searchSoldApi(c: JSONObject, q: String, limit: Int = 50): List<PriceItem> {
        val j = call(c, "GET", "/buy/marketplace_insights/v1_beta/item_sales/search", kind = "insights",
            params = mapOf("q" to q, "limit" to limit)).json
        val list = j.optJSONArray("itemSales") ?: JSONArray()
        return (0 until list.length()).mapNotNull { i ->
            val it = list.getJSONObject(i)
            val p = it.optJSONObject("lastSoldPrice") ?: return@mapNotNull null
            val price = p.optString("value").toDoubleOrNull() ?: return@mapNotNull null
            PriceItem(it.optString("title"), price, p.optString("currency"),
                it.optString("lastSoldDate").take(10), it.optString("condition"), it.optString("itemWebUrl"))
        }
    }

    // ── Account ──
    fun policies(c: JSONObject): JSONObject {
        val mp = c.optString("marketplace_id")
        val out = JSONObject()
        for ((kind, key) in listOf("fulfillment" to "fulfillmentPolicies", "payment" to "paymentPolicies",
            "return" to "returnPolicies")) {
            val list = call(c, "GET", "/sell/account/v1/${kind}_policy", kind = "user",
                params = mapOf("marketplace_id" to mp)).json.optJSONArray(key) ?: JSONArray()
            out.put(kind, JSONArray((0 until list.length()).map {
                val p = list.getJSONObject(it)
                JSONObject().put("id", p.optString("${kind}PolicyId")).put("name", p.optString("name"))
            }))
        }
        val locs = call(c, "GET", "/sell/inventory/v1/location", kind = "user",
            params = mapOf("limit" to 100)).json.optJSONArray("locations") ?: JSONArray()
        out.put("location", JSONArray((0 until locs.length()).map {
            val l = locs.getJSONObject(it)
            val key = l.optString("merchantLocationKey")
            JSONObject().put("id", key).put("name", l.optString("name").ifEmpty { key })
        }))
        return out
    }

    fun createLocation(c: JSONObject, key: String, addr: JSONObject) {
        val body = JSONObject().put("location", JSONObject().put("address", addr))
            .put("locationTypes", JSONArray(listOf("WAREHOUSE"))).put("name", key)
            .put("merchantLocationStatus", "ENABLED")
        call(c, "POST", "/sell/inventory/v1/location/" + enc(key), kind = "user", json = body)
    }

    // ── 出品 ──
    fun uploadImage(c: JSONObject, data: ByteArray, mime: String): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("image", "photo.jpg", data.toRequestBody(mime.toMediaType())).build()
        val r = call(c, "POST", "/commerce/media/v1_beta/image/create_image_from_file", kind = "user",
            host = apimHost(c), body = body)
        r.json.optString("imageUrl").takeIf { it.isNotEmpty() }?.let { return it }
        val loc = r.headers["Location"]
        if (loc != null) {
            val u = loc.toHttpUrl()
            val j = call(c, "GET", u.encodedPath, kind = "user", host = apimHost(c)).json
            j.optString("imageUrl").takeIf { it.isNotEmpty() }?.let { return it }
        }
        throw AppError("画像のアップロード結果に imageUrl がありません")
    }

    fun putInventoryItem(c: JSONObject, sku: String, body: JSONObject) {
        call(c, "PUT", "/sell/inventory/v1/inventory_item/" + enc(sku), kind = "user", json = body)
    }

    fun upsertOffer(c: JSONObject, body: JSONObject): String {
        val oid = try {
            return call(c, "POST", "/sell/inventory/v1/offer", kind = "user", json = body).json.getString("offerId")
        } catch (e: EbayError) {
            // 同じ SKU の offer が既にある → それを更新する
            (if (e.hasId(25002)) e.param("offerId") else null) ?: throw e
        }
        val upd = JSONObject(body.toString())
        for (k in listOf("sku", "marketplaceId", "format")) upd.remove(k)
        call(c, "PUT", "/sell/inventory/v1/offer/$oid", kind = "user", json = upd)
        return oid
    }

    fun publishOffer(c: JSONObject, offerId: String): String =
        call(c, "POST", "/sell/inventory/v1/offer/$offerId/publish", kind = "user").json.optString("listingId")

    companion object {
        const val API_SCOPE = "https://api.ebay.com/oauth/api_scope"
        const val INSIGHTS_SCOPE = "https://api.ebay.com/oauth/api_scope/buy.marketplace.insights"
        val USER_SCOPES = listOf(
            API_SCOPE,
            "https://api.ebay.com/oauth/api_scope/sell.inventory",
            "https://api.ebay.com/oauth/api_scope/sell.account",
        )

        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        fun query(m: Map<String, String>) = m.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
    }
}

data class Aspect(
    val name: String, val required: Boolean, val usage: String, val mode: String,
    val multi: Boolean, val values: List<String>,
)

data class PriceItem(
    val title: String, val price: Double, val currency: String, val date: String,
    val condition: String, val url: String,
) {
    fun toJson(): JSONObject = JSONObject().put("title", title).put("price", price).put("currency", currency)
        .put("date", date).put("condition", condition).put("url", url)
}
